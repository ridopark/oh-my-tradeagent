"""Dark-pool (off-exchange) print collector — pure functions + the Alpaca REST client
(PLAN-2026-10-29-darkpool-prints, phase 2).

Everything that decides WHAT a print is lives here and is unit-tested without I/O: classification,
side inference from the NBBO, ET 5-minute bucketing, the per-bucket accumulators the BFF upserts,
and the per-symbol cursor. ``AlpacaDataClient`` is the only network code: it POLLS
``GET /v2/stocks/trades`` and ``GET /v2/stocks/quotes`` over REST with a per-cycle request budget.

NEVER a WebSocket. Alpaca allows ONE data socket per account on this plan and the market-data pod
holds it (equity feed -> watchlist triggers); a second socket evicts the live feed.

Definitions (binding, they must match the BFF — ``DarkpoolIngestBatch`` / the V15 schema):

* off-exchange iff the SIP exchange code ``x == 'D'`` (FINRA ADF/TRF). That is wider than "dark
  pool" strictly — the TRF also carries internalizer prints — which is why the schema says
  "off-exchange" and only the page title says "dark pool".
* a print is KEPT individually iff off-exchange AND (notional >= ``min_notional`` OR size >=
  ``min_size``). Kept prints are stamped with the NBBO at or before the print, within 5 s.
* ``side``: ``buy`` if price >= ask, ``sell`` if price <= bid, ``mid`` strictly between, ``unknown``
  when there is no usable quote — none at or before the print within 5 s, or a one-sided
  (bid <= 0 or ask <= 0) or crossed (ask < bid) NBBO, which classifies nothing — or when the
  price falls outside [bid − D, ask + D] where D = max(one spread, 10 bp of mid): prints far
  outside the NBBO are erroneous/intermarket and are not counted as directional. Never inferred
  from VWAP for a kept print.
* ``large`` = off-exchange with notional >= ``LARGE_NOTIONAL`` ($200,000) — the SAME number the BFF
  uses for its price-level summary, so ``large_count``/``large_volume`` agree with it.
* bars: 5-minute buckets on the ET clock over ALL trades of the symbol. ``buy_volume`` /
  ``sell_volume`` cover off-exchange prints only: kept prints by their NBBO side (``mid`` and
  ``unknown`` count toward neither), and the small un-quoted prints by the bar's RUNNING
  off-exchange VWAP — buy if price > the VWAP of the off-exchange prints already in the bar,
  sell if price < it, and NEITHER on a tie. The FIRST off-exchange print of a bar has no reference
  VWAP and counts toward neither (classifying it against itself would make every bar's first
  print a buy). So ``buy_volume + sell_volume <= dp_volume`` by construction.
"""

from __future__ import annotations

import asyncio
import logging
import pathlib
from collections.abc import Awaitable, Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx

ET = ZoneInfo("America/New_York")
OFF_EXCHANGE = "D"
# Must equal the BFF's large-print threshold (DarkpoolRepository summary levels).
LARGE_NOTIONAL = 200_000.0
QUOTE_MAX_AGE = timedelta(seconds=5)
BUCKET = timedelta(minutes=5)
TRADES_PAGE_LIMIT = 10_000
# Measured on the 2026-10-28 tape: ~600 req/min at the open, ~900 at the close, ~140 midday,
# 91% of it NBBO lookups; a restart burst at the open is ~2,500-3,500. The cap bounds one cycle;
# round-robin resume (darkpool_main) spreads the rest over the following cycles.
DEFAULT_MAX_REQUESTS = 1_000
# Stop the cycle when the account's X-Ratelimit-Remaining (10,000/min, shared with the
# market-data pod and the quote collector) drops below this.
RATELIMIT_REMAINING_FLOOR = 2_000
MAX_RETRY_AFTER_SECS = 5.0
DATA_BASE_URL = "https://data.alpaca.markets"
BarKey = tuple[str, datetime]


def parse_ts(raw: str) -> datetime:
    """RFC-3339 with any fractional precision -> aware UTC datetime (nanoseconds truncate to
    microseconds; the raw string is what goes back on the wire, see ``Trade.ts_raw``)."""
    dt = datetime.fromisoformat(raw)
    if dt.tzinfo is None:
        raise ValueError(f"timestamp without a zone: {raw!r}")
    return dt.astimezone(timezone.utc)


def format_ts(dt: datetime) -> str:
    """RFC-3339 UTC with a ``Z`` suffix; whole seconds stay whole."""
    dt = dt.astimezone(timezone.utc)
    if dt.microsecond:
        return dt.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass(frozen=True)
class Trade:
    symbol: str
    trade_id: int
    ts: datetime
    ts_raw: str
    price: float
    size: int
    exchange: str
    conditions: tuple[str, ...]
    tape: str | None

    @property
    def notional(self) -> float:
        return self.price * self.size

    @classmethod
    def from_alpaca(cls, symbol: str, row: Mapping) -> Trade:
        return cls(
            symbol=symbol,
            trade_id=int(row["i"]),
            ts=parse_ts(row["t"]),
            ts_raw=str(row["t"]),
            price=float(row["p"]),
            size=int(row["s"]),
            exchange=str(row.get("x") or ""),
            conditions=tuple(str(c) for c in (row.get("c") or ())),
            tape=(str(row["z"]) if row.get("z") else None),
        )


@dataclass(frozen=True)
class Quote:
    bid: float
    ask: float
    ts: datetime


def classify(trade: Trade) -> bool:
    """Off-exchange iff the SIP exchange code is exactly ``D``."""
    return trade.exchange == OFF_EXCHANGE


def is_large(trade: Trade, min_notional: float = LARGE_NOTIONAL) -> bool:
    return trade.notional >= min_notional


def keep(trade: Trade, *, min_notional: float, min_size: int) -> bool:
    """Kept individually (NBBO-stamped, posted as a row) iff off-exchange and big enough."""
    return classify(trade) and (trade.notional >= min_notional or trade.size >= min_size)


def side(price: float, quote: Quote | None, print_ts: datetime) -> str:
    """NBBO side of a print; ``unknown`` without a usable quote at or before the print within
    5 s, when the quote is one-sided (bid or ask <= 0) or crossed (ask < bid), or when the
    price falls outside [bid − D, ask + D] where D = max(one spread, 10 bp of mid) — prints
    far outside the NBBO are erroneous/intermarket and must not be counted as directional."""
    if quote is None or quote.ts > print_ts or print_ts - quote.ts > QUOTE_MAX_AGE:
        return "unknown"
    if quote.bid <= 0 or quote.ask <= 0 or quote.ask < quote.bid:
        return "unknown"
    spread = quote.ask - quote.bid
    mid = (quote.bid + quote.ask) / 2
    d = max(spread, mid * 0.001)  # max(one spread, 10 bp of mid)
    if price < quote.bid - d or price > quote.ask + d:
        return "unknown"
    if price >= quote.ask:
        return "buy"
    if price <= quote.bid:
        return "sell"
    return "mid"


def bucket5m(ts: datetime) -> datetime:
    """Start of the 5-minute bucket on the ET clock, returned as an aware UTC instant."""
    et = ts.astimezone(ET)
    start = et.replace(minute=et.minute - et.minute % 5, second=0, microsecond=0)
    return start.astimezone(timezone.utc)


@dataclass
class BarAccumulator:
    """One (symbol, bucket). Lives across poll cycles so a bucket that straddles cycles is re-sent
    whole; the BFF upserts on (symbol, bucket)."""

    symbol: str
    bucket: datetime
    dp_volume: int = 0
    dp_trades: int = 0
    total_volume: int = 0
    total_trades: int = 0
    buy_volume: int = 0
    sell_volume: int = 0
    large_count: int = 0
    large_volume: int = 0
    dp_notional: float = field(default=0.0, repr=False)

    def add(self, trade: Trade, nbbo_side: str | None) -> None:
        """``nbbo_side`` is the NBBO side of a KEPT print, None for everything else."""
        self.total_volume += trade.size
        self.total_trades += 1
        if not classify(trade):
            return
        if nbbo_side is not None:
            s = nbbo_side
        elif self.dp_volume == 0:
            s = None  # first off-exchange print of the bar: no reference VWAP
        else:
            vwap = self.dp_notional / self.dp_volume
            s = "buy" if trade.price > vwap else "sell" if trade.price < vwap else None
        if s == "buy":
            self.buy_volume += trade.size
        elif s == "sell":
            self.sell_volume += trade.size
        if is_large(trade):
            self.large_count += 1
            self.large_volume += trade.size
        self.dp_volume += trade.size
        self.dp_trades += 1
        self.dp_notional += trade.notional

    def to_wire(self) -> dict:
        return {
            "symbol": self.symbol,
            "bucket": format_ts(self.bucket),
            "dp_volume": self.dp_volume,
            "dp_trades": self.dp_trades,
            "total_volume": self.total_volume,
            "total_trades": self.total_trades,
            "buy_volume": self.buy_volume,
            "sell_volume": self.sell_volume,
            "large_count": self.large_count,
            "large_volume": self.large_volume,
            "dp_vwap": round(self.dp_notional / self.dp_volume, 4) if self.dp_volume else None,
        }


def aggregate(
    trades: Sequence[Trade],
    sides: Mapping[Trade, str],
    accumulators: dict[BarKey, BarAccumulator],
    *,
    min_notional: float,
    min_size: int,
) -> set[BarKey]:
    """Fold ``trades`` (in Alpaca's time order) into ``accumulators``; ``sides`` maps each KEPT
    ``Trade`` to its NBBO side. Returns the (symbol, bucket) keys touched."""
    touched: set[BarKey] = set()
    for t in trades:
        key = (t.symbol, bucket5m(t.ts))
        acc = accumulators.get(key)
        if acc is None:
            acc = accumulators[key] = BarAccumulator(t.symbol, key[1])
        nbbo_side = None
        if keep(t, min_notional=min_notional, min_size=min_size):
            nbbo_side = sides.get(t, "unknown")
        acc.add(t, nbbo_side)
        touched.add(key)
    return touched


def prune_accumulators(accumulators: dict[BarKey, BarAccumulator], keep_buckets: int = 2) -> None:
    """Drop all but the newest ``keep_buckets`` buckets per symbol (current + previous)."""
    by_symbol: dict[str, list[datetime]] = {}
    for symbol, bucket in accumulators:
        by_symbol.setdefault(symbol, []).append(bucket)
    for symbol, buckets in by_symbol.items():
        for bucket in sorted(buckets)[:-keep_buckets]:
            del accumulators[(symbol, bucket)]


def print_to_wire(trade: Trade, quote: Quote | None, nbbo_side: str) -> dict:
    """One ``darkpool_print`` row, snake_case exactly as the BFF binds it. ``ts`` is Alpaca's
    string verbatim so the (symbol, ts, trade_id) unique key survives a replay byte-for-byte."""
    return {
        "trade_id": trade.trade_id,
        "symbol": trade.symbol,
        "ts": trade.ts_raw,
        "price": trade.price,
        "size": trade.size,
        "notional": round(trade.notional, 2),
        "exchange": trade.exchange or None,
        "conditions": " ".join(trade.conditions) or None,
        "tape": trade.tape,
        "bid": quote.bid if quote else None,
        "ask": quote.ask if quote else None,
        "quote_ts": format_ts(quote.ts) if quote else None,
        "side": nbbo_side,
    }


@dataclass(frozen=True)
class Cursor:
    """Per-symbol resume point: the last trade timestamp seen and every Trade AT that timestamp.
    The next poll starts at ``last_t`` (Alpaca's ``start`` is inclusive), so the set lets
    same-instant trades be dropped without assuming ids are ordered within an instant.

    The set is keyed by the full ``Trade`` value (not by ``trade_id``) so that two trades from
    different exchanges, or with different prices/sizes, that happen to share a numeric id are
    never confused."""

    last_t: datetime
    seen_at_last_t: frozenset[Trade] = frozenset()

    @classmethod
    def start(cls, since: datetime) -> Cursor:
        return cls(last_t=since)

    def is_new(self, trade: Trade) -> bool:
        if trade.ts > self.last_t:
            return True
        return trade.ts == self.last_t and trade not in self.seen_at_last_t

    def filter_new(self, trades: Iterable[Trade]) -> list[Trade]:
        return [t for t in trades if self.is_new(t)]

    def advance(self, trades: Sequence[Trade]) -> Cursor:
        if not trades:
            return self
        last_t = max(t.ts for t in trades)
        seen = {t for t in trades if t.ts == last_t}
        if last_t == self.last_t:
            seen |= self.seen_at_last_t
        return Cursor(last_t=last_t, seen_at_last_t=frozenset(seen))


class BudgetExhausted(Exception):
    """This cycle may issue no more requests; the caller stops the cycle and resumes the symbol
    next cycle. Raised by the per-cycle cap, by ``X-Ratelimit-Remaining`` falling under
    ``RATELIMIT_REMAINING_FLOOR``, and (as ``RateLimited``) by a 429."""


class RateLimited(BudgetExhausted):
    """Alpaca answered 429. The sleep (``Retry-After`` capped at 5 s, else 1 s) already happened."""


class AlpacaDataClient:
    """REST-only reads of Alpaca's stock data API. Counts every request against ``max_requests``
    per cycle and watches ``X-Ratelimit-Remaining``; ``reset_budget`` at the top of each cycle.
    No retries: a 429 sleeps briefly and ends the cycle (``RateLimited``), the next cycle resumes
    at that symbol. Any other non-2xx raises ``httpx.HTTPStatusError`` and a transport failure
    raises its ``httpx`` error — the caller isolates both per symbol."""

    def __init__(
        self,
        *,
        key_id: str,
        secret: str,
        log: logging.Logger,
        client: httpx.AsyncClient | None = None,
        max_requests: int = DEFAULT_MAX_REQUESTS,
        remaining_floor: int = RATELIMIT_REMAINING_FLOOR,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._log = log
        self.max_requests = max_requests
        self._remaining_floor = remaining_floor
        self._sleep = sleep
        self._owns_client = client is None
        self._headers = {"APCA-API-KEY-ID": key_id, "APCA-API-SECRET-KEY": secret}
        self._client = client or httpx.AsyncClient(
            base_url=DATA_BASE_URL,
            timeout=httpx.Timeout(connect=3.0, read=15.0, write=10.0, pool=3.0),
        )
        self.requests_this_cycle = 0
        self._halted: str | None = None

    def reset_budget(self) -> None:
        self.requests_this_cycle = 0
        self._halted = None

    async def _get(self, path: str, params: dict) -> dict:
        if self._halted is not None:
            raise BudgetExhausted(self._halted)
        if self.requests_this_cycle >= self.max_requests:
            raise BudgetExhausted(f"{self.max_requests} requests this cycle")
        self.requests_this_cycle += 1
        resp = await self._client.get(path, params=params, headers=self._headers)
        if resp.status_code == 429:
            try:
                delay = float(resp.headers.get("Retry-After") or 1.0)
            except ValueError:
                delay = 1.0
            delay = min(max(delay, 0.0), MAX_RETRY_AFTER_SECS)
            self._log.warning("alpaca 429 on %s: sleeping %.1fs and ending the cycle", path, delay)
            await self._sleep(delay)
            raise RateLimited(f"429 on {path}")
        resp.raise_for_status()
        remaining = resp.headers.get("X-Ratelimit-Remaining")
        if remaining is not None:
            try:
                if int(remaining) < self._remaining_floor:
                    self._halted = f"X-Ratelimit-Remaining={remaining} < {self._remaining_floor}"
            except ValueError:
                pass
        return resp.json()

    async def trades(self, symbol: str, start: datetime, end: datetime) -> list[Trade]:
        """All SIP trades of ``symbol`` in [start, end], pages joined in order."""
        out: list[Trade] = []
        token: str | None = None
        while True:
            params = {
                "symbols": symbol,
                "start": format_ts(start),
                "end": format_ts(end),
                "limit": str(TRADES_PAGE_LIMIT),
                "feed": "sip",
                "sort": "asc",
            }
            if token:
                params["page_token"] = token
            body = await self._get("/v2/stocks/trades", params)
            out.extend(
                Trade.from_alpaca(symbol, r) for r in (body.get("trades") or {}).get(symbol, [])
            )
            token = body.get("next_page_token")
            if not token:
                return out

    async def quote_before(self, symbol: str, ts: datetime) -> Quote | None:
        """The latest NBBO at or before ``ts`` within ``QUOTE_MAX_AGE``, or None."""
        body = await self._get(
            "/v2/stocks/quotes",
            {
                "symbols": symbol,
                "start": format_ts(ts - QUOTE_MAX_AGE),
                "end": format_ts(ts),
                "limit": "1",
                "feed": "sip",
                "sort": "desc",
            },
        )
        rows = (body.get("quotes") or {}).get(symbol) or []
        if not rows:
            return None
        row = rows[0]
        return Quote(bid=float(row["bp"]), ask=float(row["ap"]), ts=parse_ts(row["t"]))

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def read_universe(path: pathlib.Path) -> list[str]:
    """One symbol per line; blank lines and ``#`` comments ignored; upper-cased; first-seen
    order, deduped. Fails fast on an empty universe."""
    symbols: list[str] = []
    for line in path.read_text().splitlines():
        s = line.split("#", 1)[0].strip().upper()
        if s and s not in symbols:
            symbols.append(s)
    if not symbols:
        raise SystemExit(f"universe file {path} holds no symbols")
    return symbols
