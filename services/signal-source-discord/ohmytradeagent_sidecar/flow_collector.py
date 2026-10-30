"""Options-flow collector — pure functions + bounded state structures
(PLAN-2026-10-29-flow-page, Step 1).

Everything that classifies a trade, groups trades into orders, manages the quoted
contract set, and archives the tape lives here and is unit-tested without I/O.
``flow_main`` holds the network and timing code.

Side classification rules (binding; Q1 operator decision 2026-10-30):
  ``side_source ∈ {quote, none}``
  A quote is usable iff ``quote.ts <= trade.ts`` and
  ``trade.ts - quote.ts <= FLOW_QUOTE_MAX_AGE``.
  One-sided (bid <= 0 or ask <= 0) or crossed (ask < bid) books classify nothing.
  There is no tick-rule fallback.

Order grouping: same contract + same side, each trade within ``FLOW_ORDER_WINDOW_MS``
(1000 ms) of the PREVIOUS trade in the window, across exchanges.

Archive format: NDJSON rows ``{t,S,p,s,x,c,bp,ap,bs,as,qt}`` written as one gzip
member per flush, appended to ``<YYYY-MM-DD>.trades.ndjson.gz``.  Concatenated gzip
members constitute a valid gzip stream.  At session end a manifest JSON is written
alongside it.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import logging
import pathlib
import re
import shutil
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import msgpack

ET = ZoneInfo("America/New_York")
UTC = timezone.utc

# ---------------------------------------------------------------------------
# Constants (all overridable via env in flow_main)
# ---------------------------------------------------------------------------

FLOW_QUOTE_MAX_AGE = timedelta(seconds=3)
FLOW_ORDER_WINDOW_MS = 1_000
FLOW_BLOCK_PREMIUM = 250_000.0
FLOW_BLOCK_SIZE = 1_000
FLOW_QUOTE_CAP = 950
FLOW_QUOTE_UNIVERSE_SHARE = 0.70
# Disk guard: stop archiving above this total dir size or below this free space.
FLOW_ARCHIVE_CAP_BYTES = 20_000_000_000  # 20 GB
FLOW_ARCHIVE_FREE_FLOOR_BYTES = 15_000_000_000  # 15 GB
# Near-money cutoff for the universe quote set (fraction of spot).
FLOW_UNIVERSE_MONEYNESS_PCT = 0.05
# Max DTE for universe contracts.
FLOW_UNIVERSE_MAX_DTE = 14

# Condition-code labels (from the plan's "Implementation Decisions").
_SWEEP_CODES: frozenset[str] = frozenset({"S", "b", "d"})
_AUCTION_CODES: frozenset[str] = frozenset({"a", "b"})
_CROSS_CODES: frozenset[str] = frozenset({"c", "d"})
_MULTI_LEG_CODES: frozenset[str] = frozenset({"f", "g", "h", "i", "j", "l"})

# OCC compact symbol: TICKER(1-6) + YYMMDD + C/P + 00STRIKE0 (8 digits, strike * 1000).
_OCC_RE = re.compile(r"^([A-Z0-9]{1,6})(\d{6})([CP])(\d{8})$")


# ---------------------------------------------------------------------------
# Parsed frame types
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Trade:
    contract: str  # OCC compact symbol
    ticker: str
    expiry_str: str  # YYMMDD
    strike: float
    right: str  # 'C' or 'P'
    ts: datetime
    ts_raw: str
    price: float
    size: int
    exchange: str
    conditions: tuple[str, ...]

    @property
    def premium(self) -> float:
        return self.price * self.size * 100


@dataclass(frozen=True)
class Quote:
    contract: str
    bid: float
    ask: float
    bid_size: int
    ask_size: int
    ts: datetime
    ts_raw: str


@dataclass(frozen=True)
class Control:
    action: str  # 'success', 'error', 'subscription', 'subscription_updated', ...
    msg: str
    code: int | None = None  # error code (e.g. 406)


# ---------------------------------------------------------------------------
# OCC parse
# ---------------------------------------------------------------------------


def parse_occ(contract: str) -> tuple[str, str, float, str] | None:
    """Parse OCC compact symbol -> (ticker, expiry_str, strike, right) or None."""
    m = _OCC_RE.match(contract)
    if m is None:
        return None
    ticker, expiry_str, right, strike_raw = m.groups()
    strike = int(strike_raw) / 1000.0
    return ticker, expiry_str, strike, right


def expiry_date_et(expiry_str: str) -> date:
    """Parse YYMMDD -> date (20YY)."""
    return date(2000 + int(expiry_str[:2]), int(expiry_str[2:4]), int(expiry_str[4:6]))


def dte(expiry_str: str, trade_ts: datetime) -> int:
    """Calendar days from the ET trade date to the expiry date (inclusive of expiry)."""
    trade_date = trade_ts.astimezone(ET).date()
    return (expiry_date_et(expiry_str) - trade_date).days


# ---------------------------------------------------------------------------
# parse_frame
# ---------------------------------------------------------------------------


def parse_frame(data: bytes) -> list[Trade | Quote | Control]:
    """Decode one binary msgpack frame (a list of dicts) -> typed records.

    An empty list, a non-list payload or a dict with an unknown ``T`` field yield
    Control(action='unknown', ...) entries rather than raising.  A Trade or Quote
    whose OCC symbol cannot be parsed is returned as Control(action='bad_symbol').
    """
    try:
        raw = msgpack.unpackb(data, raw=False)
    except Exception as exc:
        return [Control(action="decode_error", msg=str(exc))]

    if not isinstance(raw, list):
        raw = [raw]

    out: list[Trade | Quote | Control] = []
    for item in raw:
        if not isinstance(item, dict):
            out.append(Control(action="non_dict", msg=repr(item)))
            continue
        t = item.get("T", "")
        if t == "t":
            parsed = _parse_trade(item)
            out.append(parsed)
        elif t == "q":
            parsed = _parse_quote(item)
            out.append(parsed)
        else:
            # success / error / subscription / subscription_updated / etc.
            out.append(
                Control(
                    action=t or "unknown",
                    msg=str(item.get("msg", "")),
                    code=item.get("code"),
                )
            )
    return out


def _parse_trade(item: dict) -> Trade | Control:
    contract = str(item.get("S", ""))
    parsed = parse_occ(contract)
    if parsed is None:
        return Control(action="bad_symbol", msg=contract)
    ticker, expiry_str, strike, right = parsed
    try:
        ts_raw = str(item["t"])
        ts = datetime.fromisoformat(ts_raw.replace("Z", "+00:00")).astimezone(UTC)
        price = float(item["p"])
        size = int(item["s"])
        exchange = str(item.get("x") or "")
        conditions = tuple(str(c) for c in (item.get("c") or []))
    except (KeyError, ValueError, TypeError) as exc:
        return Control(action="parse_error", msg=f"{contract}: {exc}")
    return Trade(
        contract=contract,
        ticker=ticker,
        expiry_str=expiry_str,
        strike=strike,
        right=right,
        ts=ts,
        ts_raw=ts_raw,
        price=price,
        size=size,
        exchange=exchange,
        conditions=conditions,
    )


def _parse_quote(item: dict) -> Quote | Control:
    contract = str(item.get("S", ""))
    parsed = parse_occ(contract)
    if parsed is None:
        return Control(action="bad_symbol", msg=contract)
    try:
        ts_raw = str(item["t"])
        ts = datetime.fromisoformat(ts_raw.replace("Z", "+00:00")).astimezone(UTC)
        bid = float(item.get("bp") or 0)
        ask = float(item.get("ap") or 0)
        bid_size = int(item.get("bs") or 0)
        ask_size = int(item.get("as") or 0)
    except (KeyError, ValueError, TypeError) as exc:
        return Control(action="parse_error", msg=f"{contract}: {exc}")
    return Quote(
        contract=contract,
        bid=bid,
        ask=ask,
        bid_size=bid_size,
        ask_size=ask_size,
        ts=ts,
        ts_raw=ts_raw,
    )


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------


def classify(trade: Trade, quote: Quote | None, now: datetime) -> dict:
    """Classify a trade.  Returns a dict with side, side_source, sweep, auction,
    cross, multi_leg, premium, dte, conditions (space-joined).

    ``now`` is used only for DTE; it may be the trade's own ``ts`` for pure-function
    tests but the real loop passes ``clock()``.
    """
    # Side from quote
    if (
        quote is None
        or quote.ts > trade.ts
        or trade.ts - quote.ts > FLOW_QUOTE_MAX_AGE
    ):
        side = "unknown"
        side_source = "none"
    elif quote.bid <= 0 or quote.ask <= 0 or quote.ask < quote.bid:
        side = "unknown"
        side_source = "none"
    else:
        if trade.price >= quote.ask:
            side = "buy"
        elif trade.price <= quote.bid:
            side = "sell"
        else:
            side = "mid"
        side_source = "quote"

    # Condition-code labels
    conds = set(trade.conditions)
    sweep = bool(conds & _SWEEP_CODES)
    auction = bool(conds & _AUCTION_CODES)
    cross = bool(conds & _CROSS_CODES)
    multi_leg = bool(conds & _MULTI_LEG_CODES)

    return {
        "side": side,
        "side_source": side_source,
        "sweep": sweep,
        "auction": auction,
        "cross": cross,
        "multi_leg": multi_leg,
        "premium": trade.premium,
        "dte": dte(trade.expiry_str, now),
        "conditions": " ".join(trade.conditions),
    }


# ---------------------------------------------------------------------------
# Order aggregator
# ---------------------------------------------------------------------------


@dataclass
class Order:
    contract: str
    ticker: str
    expiry_str: str
    strike: float
    right: str
    first_ts: datetime
    first_ts_raw: str
    last_ts: datetime
    side: str
    side_source: str
    bid: float | None
    ask: float | None
    quote_ts: datetime | None
    quote_ts_raw: str | None
    sweep: bool
    auction: bool
    cross: bool
    multi_leg: bool
    dte_val: int  # DTE at first trade
    conditions_set: list[str]  # accumulated unique conditions
    # accumulator fields
    _prices_x_sizes: float = field(default=0.0, repr=False)
    _total_size: int = field(default=0, repr=False)
    _total_premium: float = field(default=0.0, repr=False)
    _trade_count: int = field(default=0, repr=False)
    _exchange_set: set[str] = field(default_factory=set, repr=False)
    # configuration snapshots
    block_premium_threshold: float = FLOW_BLOCK_PREMIUM
    block_size_threshold: int = FLOW_BLOCK_SIZE
    order_window_ms: int = FLOW_ORDER_WINDOW_MS
    quote_max_age_ms: int = int(FLOW_QUOTE_MAX_AGE.total_seconds() * 1000)

    @property
    def size(self) -> int:
        return self._total_size

    @property
    def premium(self) -> float:
        return self._total_premium

    @property
    def vwap(self) -> float:
        if self._total_size == 0:
            return 0.0
        return self._prices_x_sizes / self._total_size

    @property
    def trade_count(self) -> int:
        return self._trade_count

    @property
    def exchange_count(self) -> int:
        return len(self._exchange_set)

    @property
    def block(self) -> bool:
        return (
            self._total_premium >= self.block_premium_threshold
            or self._total_size >= self.block_size_threshold
        )

    @property
    def order_id(self) -> str:
        ns = int(self.first_ts.timestamp() * 1_000_000_000)
        return f"{self.contract}:{ns}:{self.side}"

    def add_trade(self, trade: Trade, labels: dict) -> None:
        self.last_ts = trade.ts
        self._prices_x_sizes += trade.price * trade.size
        self._total_size += trade.size
        self._total_premium += labels["premium"]
        self._trade_count += 1
        self._exchange_set.add(trade.exchange)
        for c in trade.conditions:
            if c not in self.conditions_set:
                self.conditions_set.append(c)

    def to_wire(self) -> dict:
        return {
            "order_id": self.order_id,
            "ticker": self.ticker,
            "contract": self.contract,
            "expiry": expiry_date_et(self.expiry_str).isoformat(),
            "strike": self.strike,
            "right": self.right,
            "first_ts": _fmt_ts(self.first_ts),
            "last_ts": _fmt_ts(self.last_ts),
            "trade_count": self.trade_count,
            "exchange_count": self.exchange_count,
            "size": self.size,
            "premium": round(self.premium, 2),
            "vwap": round(self.vwap, 4),
            "side": self.side,
            "side_source": self.side_source,
            "bid": self.bid,
            "ask": self.ask,
            "quote_ts": _fmt_ts(self.quote_ts) if self.quote_ts else None,
            "sweep": self.sweep,
            "multi_leg": self.multi_leg,
            "auction": self.auction,
            "cross": self.cross,
            "block": self.block,
            "unusual": None,
            "day_volume": None,
            "open_interest": None,
            "underlying_price": None,
            "moneyness_pct": None,
            "dte": self.dte_val,
            "conditions": " ".join(self.conditions_set),
            "block_premium_threshold": self.block_premium_threshold,
            "block_size_threshold": self.block_size_threshold,
            "order_window_ms": self.order_window_ms,
            "quote_max_age_ms": self.quote_max_age_ms,
        }


def _fmt_ts(dt: datetime) -> str:
    """RFC-3339 UTC with Z suffix."""
    dt = dt.astimezone(UTC)
    if dt.microsecond:
        return dt.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


class OrderAggregator:
    """Groups trades into orders by same contract + same side within ``order_window_ms``
    of the previous trade in the window, across exchanges.

    ``feed(trade, labels)`` returns a list of completed orders (those whose window has
    not expired yet stay open).  ``flush(now)`` closes all windows idle longer than the
    window and returns them.
    """

    def __init__(
        self,
        order_window_ms: int = FLOW_ORDER_WINDOW_MS,
        block_premium: float = FLOW_BLOCK_PREMIUM,
        block_size: int = FLOW_BLOCK_SIZE,
    ) -> None:
        self._window_ms = order_window_ms
        self._block_premium = block_premium
        self._block_size = block_size
        # key: (contract, side)
        self._open: dict[tuple[str, str], Order] = {}

    def feed(self, trade: Trade, labels: dict) -> list[Order]:
        """Add ``trade`` to its order (or open a new one).  Returns orders that were
        closed because a trade arrived with a DIFFERENT side on the same contract
        (since that contract+old_side window has no more trades of its side)."""
        closed: list[Order] = []
        key = (trade.contract, labels["side"])
        existing = self._open.get(key)

        window_td = timedelta(milliseconds=self._window_ms)
        if existing is not None:
            if trade.ts - existing.last_ts > window_td:
                # Expired window: close the old one, start fresh.
                closed.append(self._open.pop(key))
                existing = None

        if existing is None:
            q = labels.get("_quote")
            order = Order(
                contract=trade.contract,
                ticker=trade.ticker,
                expiry_str=trade.expiry_str,
                strike=trade.strike,
                right=trade.right,
                first_ts=trade.ts,
                first_ts_raw=trade.ts_raw,
                last_ts=trade.ts,
                side=labels["side"],
                side_source=labels["side_source"],
                bid=q.bid if q else None,
                ask=q.ask if q else None,
                quote_ts=q.ts if q else None,
                quote_ts_raw=q.ts_raw if q else None,
                sweep=labels["sweep"],
                auction=labels["auction"],
                cross=labels["cross"],
                multi_leg=labels["multi_leg"],
                dte_val=labels["dte"],
                conditions_set=list(trade.conditions),
                block_premium_threshold=self._block_premium,
                block_size_threshold=self._block_size,
                order_window_ms=self._window_ms,
            )
            # Initialize accumulators
            order._prices_x_sizes = trade.price * trade.size
            order._total_size = trade.size
            order._total_premium = labels["premium"]
            order._trade_count = 1
            order._exchange_set = {trade.exchange}
            self._open[key] = order
        else:
            existing.add_trade(trade, labels)

        return closed

    def flush(self, now: datetime) -> list[Order]:
        """Return all open orders idle for longer than ``order_window_ms`` as of ``now``."""
        window_td = timedelta(milliseconds=self._window_ms)
        expired = [
            k for k, o in self._open.items() if now - o.last_ts > window_td
        ]
        return [self._open.pop(k) for k in expired]

    def flush_all(self) -> list[Order]:
        """Drain all open orders unconditionally (used at session end)."""
        orders = list(self._open.values())
        self._open.clear()
        return orders


# ---------------------------------------------------------------------------
# Quote set
# ---------------------------------------------------------------------------


class QuoteSet:
    """Manages the set of contracts to request live quotes for.

    ``choose(universe_contracts, rolling_premium, cap, universe_share)`` selects up to
    ``cap`` contracts: first the near-money universe (interleaved across names, sorted by
    |moneyness| then DTE) filling ``universe_share`` of the cap, then top rolling-premium
    contracts for the remainder.  Returns (chosen, subscribe_list, unsubscribe_list) as
    a diff against the previous ``choose`` result.
    """

    def __init__(self) -> None:
        self.current: frozenset[str] = frozenset()

    def choose(
        self,
        universe_contracts: list[tuple[str, str, float, int]],
        # each: (contract, ticker, abs_moneyness_frac, dte)
        rolling_premium: dict[str, float],
        cap: int = FLOW_QUOTE_CAP,
        universe_share: float = FLOW_QUOTE_UNIVERSE_SHARE,
    ) -> tuple[frozenset[str], list[str], list[str]]:
        """Select the new quoted set and return (chosen, subscribe, unsubscribe)."""
        universe_budget = int(cap * universe_share)

        # Group universe contracts by ticker, sort within each ticker by (abs_moneyness, dte).
        by_ticker: dict[str, list[tuple[str, str, float, int]]] = {}
        for entry in universe_contracts:
            contract, ticker, moneyness, dte_val = entry
            by_ticker.setdefault(ticker, []).append(entry)
        for ticker in by_ticker:
            by_ticker[ticker].sort(key=lambda e: (e[2], e[3]))

        # Interleave: round-robin across tickers (sorted for determinism).
        tickers = sorted(by_ticker)
        idx = {t: 0 for t in tickers}
        universe_chosen: list[str] = []
        while len(universe_chosen) < universe_budget:
            added_any = False
            for ticker in tickers:
                if len(universe_chosen) >= universe_budget:
                    break
                i = idx[ticker]
                contracts = by_ticker[ticker]
                if i < len(contracts):
                    universe_chosen.append(contracts[i][0])
                    idx[ticker] = i + 1
                    added_any = True
            if not added_any:
                break

        universe_set = set(universe_chosen)

        # Fill remaining slots with top rolling-premium contracts (not already chosen).
        remaining = cap - len(universe_chosen)
        rolling_sorted = sorted(
            ((v, k) for k, v in rolling_premium.items() if k not in universe_set),
            reverse=True,
        )
        premium_chosen = [k for _, k in rolling_sorted[:remaining]]

        new_set = frozenset(universe_chosen) | frozenset(premium_chosen)
        subscribe = sorted(new_set - self.current)
        unsubscribe = sorted(self.current - new_set)
        self.current = new_set
        return new_set, subscribe, unsubscribe


# ---------------------------------------------------------------------------
# Tape archiver
# ---------------------------------------------------------------------------


class TapeArchiver:
    """Append-mode ndjson.gz archiver for the OPRA trade tape.

    Each ``write_flush(rows)`` appends one gzip member to ``<dir>/<date>.trades.ndjson.gz``.
    Concatenated gzip members are a valid gzip stream; ``gzip -t`` and ``zcat | wc -l`` work
    on the file.

    ``close_session(date_str, window, build)`` writes a ``<date>.manifest.json`` alongside.

    When the archive dir exceeds ``FLOW_ARCHIVE_CAP_BYTES`` or free space falls under
    ``FLOW_ARCHIVE_FREE_FLOOR_BYTES``, writes are refused (classifying continues), a gap is
    recorded, and the next ``write_flush`` re-checks.
    """

    def __init__(
        self,
        archive_dir: pathlib.Path,
        *,
        build: str = "",
        cap_bytes: int = FLOW_ARCHIVE_CAP_BYTES,
        free_floor_bytes: int = FLOW_ARCHIVE_FREE_FLOOR_BYTES,
        log: logging.Logger | None = None,
    ) -> None:
        self._dir = archive_dir
        self._build = build
        self._cap_bytes = cap_bytes
        self._free_floor_bytes = free_floor_bytes
        self._log = log or logging.getLogger(__name__)
        self._date_str: str = ""  # set on first write
        self._path: pathlib.Path | None = None
        self._trades_written: int = 0
        self._bytes_today: int = 0
        self._first_t: str | None = None
        self._last_t: str | None = None
        self.gaps: list[dict] = []
        self._disk_stopped: bool = False
        self._disk_stop_ts: str | None = None

    def _et_date_str(self, ts: datetime) -> str:
        return ts.astimezone(ET).strftime("%Y-%m-%d")

    def _disk_ok(self) -> bool:
        """Return True if disk constraints are satisfied.

        Uses _bytes_today as an approximation of the archive dir's today contribution;
        for the volume free-space check, falls back to the parent directory when the
        archive dir does not yet exist.
        """
        try:
            probe_dir = self._dir if self._dir.exists() else self._dir.parent
            free = shutil.disk_usage(probe_dir).free
        except OSError:
            free = self._free_floor_bytes + 1  # unknown → optimistic
        try:
            dir_size = sum(
                f.stat().st_size
                for f in self._dir.rglob("*")
                if f.is_file()
            ) if self._dir.exists() else 0
        except OSError:
            dir_size = 0
        return dir_size < self._cap_bytes and free > self._free_floor_bytes

    def write_flush(self, rows: list[dict], flush_ts: datetime) -> None:
        """Append one gzip member of NDJSON rows to today's archive file.

        If disk constraints are violated, records a gap and returns without writing.
        """
        if not rows:
            return

        date_str = self._et_date_str(flush_ts)
        if self._date_str != date_str:
            self._date_str = date_str
            self._path = self._dir / f"{date_str}.trades.ndjson.gz"
            self._bytes_today = self._path.stat().st_size if self._path.exists() else 0

        if not self._disk_ok():
            if not self._disk_stopped:
                self._disk_stopped = True
                self._disk_stop_ts = _fmt_ts(flush_ts)
                self._log.warning(
                    "flow archiver: disk guard triggered — stopping archive writes "
                    "(cap=%d bytes or free space below %d bytes)",
                    self._cap_bytes,
                    self._free_floor_bytes,
                )
            return

        # If we were stopped and disk is now OK, record the gap.
        if self._disk_stopped:
            self._disk_stopped = False
            gap_from = self._disk_stop_ts or _fmt_ts(flush_ts)
            self._gaps_append(gap_from, _fmt_ts(flush_ts), "disk_cap")
            self._disk_stop_ts = None

        buf = io.BytesIO()
        with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
            for row in rows:
                line = json.dumps(row, separators=(",", ":")) + "\n"
                gz.write(line.encode())
        data = buf.getvalue()

        self._dir.mkdir(parents=True, exist_ok=True)
        with open(self._path, "ab") as fh:
            fh.write(data)

        self._bytes_today += len(data)
        self._trades_written += len(rows)
        if self._first_t is None:
            self._first_t = rows[0].get("t")
        self._last_t = rows[-1].get("t")

    def record_gap(self, from_ts: str, to_ts: str, reason: str) -> None:
        """Record a non-disk gap (reconnect, window closed, etc.)."""
        self._gaps_append(from_ts, to_ts, reason)

    def _gaps_append(self, from_ts: str, to_ts: str, reason: str) -> None:
        self.gaps.append({"from": from_ts, "to": to_ts, "reason": reason})

    def close_session(self, date_str: str, window: str = "09:25-16:20", build: str = "") -> dict | None:
        """Write the manifest for today's session and return it, or None if no file was written."""
        if self._path is None or not self._path.exists():
            return None
        sha256 = _sha256_file(self._path)
        manifest = {
            "date": date_str,
            "file": self._path.name,
            "bytes": self._path.stat().st_size,
            "sha256": sha256,
            "trades": self._trades_written,
            "first_t": self._first_t,
            "last_t": self._last_t,
            "gaps": self.gaps,
            "window": window,
            "build": build or self._build,
        }
        manifest_path = self._dir / f"{date_str}.manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2))
        return manifest

    @property
    def bytes_today(self) -> int:
        return self._bytes_today

    @property
    def trades_today(self) -> int:
        return self._trades_written


def _sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Helpers for flow_main
# ---------------------------------------------------------------------------


def trade_to_archive_row(trade: Trade, quote: Quote | None) -> dict:
    """Produce the archive NDJSON row for a single trade."""
    return {
        "t": trade.ts_raw,
        "S": trade.contract,
        "p": trade.price,
        "s": trade.size,
        "x": trade.exchange,
        "c": list(trade.conditions),
        "bp": quote.bid if quote else None,
        "ap": quote.ask if quote else None,
        "bs": quote.bid_size if quote else None,
        "as": quote.ask_size if quote else None,
        "qt": quote.ts_raw if quote else None,
    }
