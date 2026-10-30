"""Entrypoint for the options-flow collector (PLAN-2026-10-29-flow-page, Step 1).

Runs as its own Deployment (``flow-collector``, ``infra/k8s/67-flow-collector.yaml``) from
the SAME image as the trading sidecar via a ``command:`` override.  No Discord, no
Chromium, no Temporal: it holds Alpaca's single OPRA options-stream slot 09:25–16:20 ET,
subscribes to all option trades (wildcard) plus live quotes for ≤ 950 chosen contracts,
classifies and groups trades into orders, archives every trade to daily gzip files, and
(Step 2, not yet) posts orders ≥ $25k to the dashboard BFF.

The OPTIONS endpoint (``/v1beta1/opra``) is a SEPARATE slot from the stock endpoint
(``/v2/sip``).  A second connect to the same endpoint answers 406 and the FIRST
connection survives — so this Deployment is one replica with ``strategy: Recreate``
and the reconnect loop treats 406 as "previous pod still holds the slot": back off 60 s
and retry, never crash-loop.

Dry run (``FLOW_DRY_RUN``, default ``true``): only the literal ``false`` posts.  The
archiver always writes (it is read-only on the market) — dry run merely prevents the
ingest client call that does not exist yet in Step 1.

Window: weekdays 09:25–16:20 ET.  Outside the window, idle and touch the heartbeat.

Two REST clients:
- ``data_client`` (``data.alpaca.markets``): spot prices / latest trades for stocks.
- ``trading_client`` (``api.alpaca.markets``): options contracts (TRADING API endpoint).
Both share the same ``FLOW_MAX_REST_PER_MIN`` / ``X-Ratelimit-Remaining`` budget via a
single ``_RestBucket`` instance.
"""

from __future__ import annotations

import asyncio
import logging
import math
import os
import pathlib
import resource
import signal
import sys
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import date, datetime, time as dtime, timedelta, timezone
from typing import Any

import httpx
import msgpack
import websockets
import websockets.exceptions
from dotenv import load_dotenv

from .darkpool_main import parse_dry_run
from .flow_collector import (
    ET,
    FLOW_ARCHIVE_CAP_BYTES,
    FLOW_ARCHIVE_FREE_FLOOR_BYTES,
    FLOW_QUOTE_CAP,
    FLOW_QUOTE_UNIVERSE_SHARE,
    FLOW_ROLLING_PREMIUM_MIN,
    FLOW_UNIVERSE_MAX_DTE,
    FLOW_UNIVERSE_MONEYNESS_PCT,
    Control,
    Order,
    OrderAggregator,
    Quote,
    QuoteSet,
    TapeArchiver,
    Trade,
    _fmt_ts,
    _usable_quote,
    classify,
    parse_frame,
    trade_to_archive_row,
)
from .runtime import required, setup_logging

UTC = timezone.utc
WS_URL = "wss://stream.data.alpaca.markets/v1beta1/opra"
DATA_BASE_URL = "https://data.alpaca.markets"
# Options contracts live on the TRADING API, not the data API (prior art: option_oi_collector.py).
TRADING_BASE_URL = "https://api.alpaca.markets"
# RTH-plus window (plan spec): connect 5 min before the open, disconnect 20 min after
WINDOW_OPEN_ET = dtime(9, 25)
WINDOW_CLOSE_ET = dtime(16, 20)
# Back-off for a 406 (slot held by previous pod): wait this long before retrying.
SLOT_HELD_BACKOFF_SECS = 60.0
# Reconnect back-off sequence: base seconds, grows linearly up to max.
RECONNECT_BASE_SECS = 2.0
RECONNECT_MAX_SECS = 60.0
# Flush interval (seconds): trade buffer is flushed to archiver and aggregator.
FLUSH_INTERVAL_SECS = 1.0
# Quote-set rotation interval (seconds).
QUOTE_ROTATION_SECS = 300.0
# Metrics line interval (seconds).
METRICS_INTERVAL_SECS = 60.0
# Stock spot price endpoint (batch) — DATA API.
STOCK_LATEST_URL = "/v2/stocks/trades/latest"
# Options contracts endpoint — TRADING API (GET https://api.alpaca.markets/v2/options/contracts).
OPTIONS_CONTRACTS_PATH = "/v2/options/contracts"
# Options contracts page size (Alpaca max).
OPTIONS_CONTRACTS_LIMIT = 10_000
# Max REST calls per minute (shared 10k budget with market-data and darkpool).
FLOW_MAX_REST_PER_MIN = 300
# X-Ratelimit-Remaining floor: stop REST for the minute when remaining < this.
RATELIMIT_REMAINING_FLOOR = 2_000
# Rolling premium map: track the last WINDOW_SECS of premium, bounded to MAX_K entries.
ROLLING_PREMIUM_WINDOW_SECS = 900.0
ROLLING_PREMIUM_MAX_K = 2_000  # memory bound: at most 2 k contracts in the map


def in_flow_window(now: datetime) -> bool:
    """Weekdays, 09:25 <= ET clock < 16:20."""
    et = now.astimezone(ET)
    return et.weekday() < 5 and WINDOW_OPEN_ET <= et.time() < WINDOW_CLOSE_ET


def now_utc() -> datetime:
    return datetime.now(UTC)


def rss_mb() -> float:
    """Current RSS in MB from /proc/self/status, fallback to resource module."""
    try:
        with open("/proc/self/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) / 1024.0
    except OSError:
        pass
    # Fallback: getrusage (on Linux reports in KB)
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


# ---------------------------------------------------------------------------
# Approximate distinct counter (replaces the capped seen_contracts set)
# ---------------------------------------------------------------------------


class ApproxDistinctCounter:
    """Approximate distinct-contract counter using a 1 MB hashed bitmap.

    Uses linear counting: hash the item to a bit in a 1 MB (8 M bit) array.
    Does not saturate — when most bits are set the estimate degrades gracefully.
    Memory: exactly 1 MB regardless of traffic volume.
    """

    _BYTES = 1_048_576       # 1 MB
    _BITS = 1_048_576 * 8   # 8 M bits

    def __init__(self) -> None:
        self._buf = bytearray(self._BYTES)
        self._set_bits: int = 0

    def add(self, item: str) -> None:
        h = hash(item) & 0x7FFFFFFF
        idx = h % self._BITS
        byte_idx = idx >> 3
        bit_idx = idx & 7
        mask = 1 << bit_idx
        if not (self._buf[byte_idx] & mask):
            self._buf[byte_idx] |= mask
            self._set_bits += 1

    def approx_count(self) -> int:
        """Linear-counting cardinality estimate."""
        zero_bits = self._BITS - self._set_bits
        if zero_bits <= 0:
            return self._BITS  # saturated: return max as a ceiling
        return round(self._BITS * math.log(self._BITS / zero_bits))

    def __len__(self) -> int:
        return self.approx_count()


# ---------------------------------------------------------------------------
# Rolling premium map (bounded, time-windowed)
# ---------------------------------------------------------------------------


class RollingPremiumMap:
    """Bounded rolling accumulator of order premium over a sliding time window.

    Tracks contracts with recent premium activity for the quote-set ranking in
    ``QuoteSet.choose``.

    Admission filter: only contracts with ``premium >= FLOW_ROLLING_PREMIUM_MIN``
    ($10k) are admitted per the plan.

    Hard cap on insert: when the map is full (``max_k`` entries), the lowest-premium
    contract is evicted immediately before admitting the new entry — so the map never
    exceeds ``max_k`` between ``prune`` calls.
    """

    def __init__(
        self,
        window_secs: float = ROLLING_PREMIUM_WINDOW_SECS,
        max_k: int = ROLLING_PREMIUM_MAX_K,
        min_premium: float = FLOW_ROLLING_PREMIUM_MIN,
    ) -> None:
        self._window = window_secs
        self._max_k = max_k
        self._min_premium = min_premium
        # {contract: (last_seen_mono, accumulated_premium)}
        self._data: dict[str, tuple[float, float]] = {}

    def add(self, contract: str, premium: float, now_mono: float) -> None:
        """Admit only when premium >= min_premium; enforce max_k on insert."""
        if premium < self._min_premium:
            return

        if contract in self._data:
            _, old = self._data[contract]
            self._data[contract] = (now_mono, old + premium)
        else:
            # Enforce cap on insert: evict the lowest-premium entry if full.
            if len(self._data) >= self._max_k:
                min_key = min(self._data, key=lambda k: self._data[k][1])
                del self._data[min_key]
            self._data[contract] = (now_mono, premium)

    def prune(self, now_mono: float) -> None:
        """Evict stale entries (older than window) then trim to max_k by premium."""
        cutoff = now_mono - self._window
        stale = [k for k, (ts, _) in self._data.items() if ts < cutoff]
        for k in stale:
            del self._data[k]
        if len(self._data) > self._max_k:
            # Drop the lowest-premium entries
            sorted_items = sorted(self._data.items(), key=lambda kv: kv[1][1])
            for k, _ in sorted_items[: len(self._data) - self._max_k]:
                del self._data[k]

    def get_map(self) -> dict[str, float]:
        return {k: v for k, (_, v) in self._data.items()}

    def __len__(self) -> int:
        return len(self._data)


# ---------------------------------------------------------------------------
# REST rate limiter (token bucket + X-Ratelimit-Remaining floor)
# ---------------------------------------------------------------------------


class _RestBucket:
    """Token bucket for REST rate-limiting.

    Enforces ``rate_per_min`` calls/min and hard-stops for the remainder of a
    rate-limit window when an Alpaca response header ``X-Ratelimit-Remaining``
    drops below ``floor``.  Both successful and failed REST calls consume a token.
    """

    def __init__(
        self,
        rate_per_min: int = FLOW_MAX_REST_PER_MIN,
        floor: int = RATELIMIT_REMAINING_FLOOR,
    ) -> None:
        self._rate = rate_per_min
        self._floor = floor
        self._tokens = float(rate_per_min)
        self._last_refill = time.monotonic()
        self._hard_stop_until: float = 0.0

    def _refill(self) -> None:
        now = time.monotonic()
        elapsed = now - self._last_refill
        self._tokens = min(self._rate, self._tokens + elapsed * self._rate / 60.0)
        self._last_refill = now

    def can_call(self) -> bool:
        """True if a call is permitted right now."""
        if time.monotonic() < self._hard_stop_until:
            return False
        self._refill()
        return self._tokens >= 1.0

    def consume(self) -> None:
        """Deduct one token (call both on success and failure)."""
        self._refill()
        self._tokens = max(0.0, self._tokens - 1.0)

    def on_response(self, response: httpx.Response) -> None:
        """Inspect the response header; hard-stop if remaining is below the floor."""
        remaining_str = response.headers.get("X-Ratelimit-Remaining", "")
        try:
            if int(remaining_str) < self._floor:
                self._hard_stop_until = time.monotonic() + 60.0
        except (ValueError, TypeError):
            pass

    @property
    def stopped(self) -> bool:
        return time.monotonic() < self._hard_stop_until


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------


@dataclass
class Metrics:
    trades_total: int = 0
    # Per-interval peak (reset at each metrics log); session maximum kept separately.
    trades_1s_peak: int = 0
    trades_1s_peak_session: int = 0
    _trades_this_second: int = 0
    _trades_since_last_log: int = 0
    quotes_total: int = 0
    quotes_1s_peak: int = 0
    quotes_1s_peak_session: int = 0
    _quotes_this_second: int = 0
    _quotes_since_last_log: int = 0
    # loop_lag_max_ms: per-interval max (reset at log); session max kept separately.
    loop_lag_max_ms: float = 0.0
    loop_lag_max_ms_session: float = 0.0
    orders_total: int = 0
    orders_25k: int = 0
    orders_50k: int = 0
    orders_100k: int = 0
    _orders_since_last_log: int = 0
    # quoted_premium_share is only computed over orders >= $25k.
    quoted_premium_25k: float = 0.0
    total_premium_25k: float = 0.0
    rss_mb_peak: float = 0.0
    reconnects: int = 0
    gap_count: int = 0  # number of gap records this session
    gap_seconds: float = 0.0
    _rest_calls_this_min: int = 0
    rest_calls_min_peak: int = 0
    _last_tick_time: float = field(default_factory=time.monotonic)

    def tick_second(self) -> None:
        now = time.monotonic()
        interval_ms = (now - self._last_tick_time) * 1000.0
        lag_ms = max(0.0, interval_ms - FLUSH_INTERVAL_SECS * 1000.0)
        self.loop_lag_max_ms = max(self.loop_lag_max_ms, lag_ms)
        self.loop_lag_max_ms_session = max(self.loop_lag_max_ms_session, lag_ms)
        self._last_tick_time = now
        self.trades_1s_peak = max(self.trades_1s_peak, self._trades_this_second)
        self.trades_1s_peak_session = max(self.trades_1s_peak_session, self._trades_this_second)
        self.quotes_1s_peak = max(self.quotes_1s_peak, self._quotes_this_second)
        self.quotes_1s_peak_session = max(self.quotes_1s_peak_session, self._quotes_this_second)
        self._trades_this_second = 0
        self._quotes_this_second = 0
        rss = rss_mb()
        self.rss_mb_peak = max(self.rss_mb_peak, rss)

    def add_trade(self) -> None:
        self.trades_total += 1
        self._trades_this_second += 1
        self._trades_since_last_log += 1

    def add_quote(self) -> None:
        self.quotes_total += 1
        self._quotes_this_second += 1
        self._quotes_since_last_log += 1

    def add_order(self, premium: float, side_source: str) -> None:
        self.orders_total += 1
        self._orders_since_last_log += 1
        if premium >= 25_000:
            self.orders_25k += 1
            self.total_premium_25k += premium
            if side_source == "quote":
                self.quoted_premium_25k += premium
        if premium >= 50_000:
            self.orders_50k += 1
        if premium >= 100_000:
            self.orders_100k += 1

    def add_rest_call(self) -> None:
        self._rest_calls_this_min += 1

    def quoted_premium_share(self) -> float:
        """Share of premium ≥ $25k orders attributed to side_source=quote."""
        if self.total_premium_25k <= 0:
            return 0.0
        return self.quoted_premium_25k / self.total_premium_25k

    def tick_metrics_interval(self) -> None:
        """Called each time the metrics line is emitted; resets per-interval counters
        and updates the REST peak."""
        self.rest_calls_min_peak = max(self.rest_calls_min_peak, self._rest_calls_this_min)
        self._rest_calls_this_min = 0
        self._trades_since_last_log = 0
        self._quotes_since_last_log = 0
        self._orders_since_last_log = 0
        # Reset per-interval peaks (session maxima are preserved above).
        self.trades_1s_peak = 0
        self.quotes_1s_peak = 0
        self.loop_lag_max_ms = 0.0


# ---------------------------------------------------------------------------
# Flow runner (the real loop + the testable core)
# ---------------------------------------------------------------------------


@dataclass
class SessionState:
    """Mutable state for one trading-day session.

    Persists across reconnects within the same calendar day; reset at session end
    (16:20 ET or SIGTERM) by creating a new ``SessionState`` in ``run_loop``.
    """

    agg: OrderAggregator = field(default_factory=OrderAggregator)
    quote_cache: dict[str, Quote] = field(default_factory=dict)  # contract → latest Quote
    quote_set: QuoteSet = field(default_factory=QuoteSet)
    metrics: Metrics = field(default_factory=Metrics)
    archiver: TapeArchiver | None = None
    last_trade_ts: str | None = None
    seen_contracts: ApproxDistinctCounter = field(default_factory=ApproxDistinctCounter)
    last_quote_rotation: float = field(default_factory=time.monotonic)
    last_metrics_log: float = field(default_factory=time.monotonic)
    rolling_premium: RollingPremiumMap = field(default_factory=RollingPremiumMap)
    options_cache: dict[str, list] = field(default_factory=dict)  # ticker → contract list
    options_cache_date: str = ""  # ET date the cache is for (reset per day)
    orders_counted: int = 0  # dry-run count of orders that would be posted


class FlowSession:
    """The streaming client for one connection lifetime.

    Designed for testability: ``connect_fn``, ``clock``, ``data_client``,
    ``trading_client``, and ``universe_path`` are injectable, and the full public
    protocol is exercised via ``run()`` over a ``FakeWebSocket`` with
    ``httpx.MockTransport`` transports.
    """

    def __init__(
        self,
        *,
        key_id: str,
        secret: str,
        heartbeat_fn: Callable[[], None],
        dry_run: bool,
        log: logging.Logger,
        connect_fn: Callable[..., Any] | None = None,
        clock: Callable[[], datetime] | None = None,
        data_client: httpx.AsyncClient | None = None,
        trading_client: httpx.AsyncClient | None = None,
        universe_path: pathlib.Path | None = None,
        rest_bucket: _RestBucket | None = None,
    ) -> None:
        self._key_id = key_id
        self._secret = secret
        self._heartbeat = heartbeat_fn
        self._dry_run = dry_run
        self._log = log
        self._connect_fn = connect_fn or websockets.connect
        self._clock = clock or now_utc
        self._data_client = data_client
        self._trading_client = trading_client or data_client
        self._universe_path = universe_path
        self._bucket = rest_bucket or _RestBucket()

    async def run(
        self,
        state: SessionState,
        ws: Any,
        stop_event: asyncio.Event | None = None,
    ) -> None:
        """Run one WebSocket session from greeting through trades until disconnection.

        Returns normally when ``in_flow_window`` transitions to False (clean session end)
        or when ``stop_event`` is set (SIGTERM).  Raises ``_StreamError`` on any
        unexpected socket drop (ConnectionClosed / EOF / OSError) so the outer
        ``run_loop`` can record the gap and reconnect.
        """
        self._log.info("flow session started")

        # Clear the quote set and cache so the new socket receives a full subscribe.
        # Without this, a reconnect would send only the diff (new contracts) rather
        # than the full chosen set, leaving the new socket without previously subscribed quotes.
        state.quote_set.current = frozenset()
        state.quote_cache.clear()

        # Greeting
        greeting = await ws.recv()
        records = parse_frame(greeting)
        self._log.debug("greeting: %s", records)

        # Auth
        await ws.send(msgpack.packb({"action": "auth", "key": self._key_id, "secret": self._secret}))

        # Read until authenticated or error
        authenticated = False
        for _ in range(10):
            frame = await ws.recv()
            for rec in parse_frame(frame):
                if isinstance(rec, Control):
                    if rec.action == "success" and rec.msg == "authenticated":
                        authenticated = True
                    elif rec.action == "error":
                        if rec.code == 406:
                            raise _SlotHeldError(f"406: {rec.msg}")
                        raise _StreamError(f"auth error {rec.code}: {rec.msg}")
            if authenticated:
                break
        if not authenticated:
            raise _StreamError("no auth acknowledgement")

        # Populate initial quote set via REST (non-blocking: background task).
        _rotation_task: asyncio.Task | None = asyncio.create_task(
            self._choose_quote_set(state)
        )

        # Subscribe: wildcard trades (quote set will be applied when the task completes).
        sub_msg: dict = {"action": "subscribe", "trades": ["*"]}
        await ws.send(msgpack.packb(sub_msg))

        # Read stream
        flush_deadline = time.monotonic() + FLUSH_INTERVAL_SECS
        metrics_deadline = time.monotonic() + METRICS_INTERVAL_SECS
        quote_rotation_deadline = state.last_quote_rotation + QUOTE_ROTATION_SECS

        archive_rows_buf: list[dict] = []

        while True:
            self._heartbeat()
            now = self._clock()

            # SIGTERM: stop_event set — exit cleanly so run_loop can end the session.
            if stop_event is not None and stop_event.is_set():
                if _rotation_task is not None and not _rotation_task.done():
                    _rotation_task.cancel()
                break

            if not in_flow_window(now):
                # Clean session end: cancel any pending rotation task.
                if _rotation_task is not None and not _rotation_task.done():
                    _rotation_task.cancel()
                break

            # Throttle recv with a short timeout so we can tick
            try:
                frame = await asyncio.wait_for(ws.recv(), timeout=0.1)
            except asyncio.TimeoutError:
                frame = None
            except (websockets.exceptions.ConnectionClosed, EOFError, OSError) as exc:
                # Unexpected disconnect inside the receive loop: this is a reconnect event,
                # not a session end.  Raise so run_loop records the gap and reconnects.
                raise _StreamError(f"connection dropped: {exc}") from exc

            if frame is not None:
                for rec in parse_frame(frame):
                    if isinstance(rec, Trade):
                        state.metrics.add_trade()
                        state.seen_contracts.add(rec.contract)
                        quote = state.quote_cache.get(rec.contract)
                        labels = classify(rec, quote, now)
                        labels["_quote"] = _usable_quote(quote, rec.ts)
                        closed = state.agg.feed(rec, labels)
                        for order in closed:
                            self._emit_order(state, order, time.monotonic())
                        if state.archiver:
                            archive_rows_buf.append(trade_to_archive_row(rec, quote))
                        state.last_trade_ts = rec.ts_raw
                    elif isinstance(rec, Quote):
                        state.metrics.add_quote()
                        state.quote_cache[rec.contract] = rec
                    elif isinstance(rec, Control) and rec.action == "error":
                        if rec.code == 406:
                            raise _SlotHeldError(f"406 mid-session: {rec.msg}")
                        self._log.warning("stream error %s: %s", rec.code, rec.msg)

            mono = time.monotonic()

            # Apply rotation task result if the background task has completed.
            if _rotation_task is not None and _rotation_task.done():
                try:
                    subscribe, unsubscribe = _rotation_task.result()
                    for contract in unsubscribe:
                        state.quote_cache.pop(contract, None)
                    if subscribe:
                        await ws.send(msgpack.packb({"action": "subscribe", "quotes": subscribe}))
                    if unsubscribe:
                        await ws.send(msgpack.packb({"action": "unsubscribe", "quotes": unsubscribe}))
                except asyncio.CancelledError:
                    pass
                except Exception as exc:
                    self._log.warning("flow: quote rotation failed: %s", exc)
                _rotation_task = None

            # 1-second tick
            if mono >= flush_deadline:
                state.metrics.tick_second()
                # Flush idle order windows
                for order in state.agg.flush(now):
                    self._emit_order(state, order, mono)
                # Archive flush
                if state.archiver and archive_rows_buf:
                    state.archiver.write_flush(archive_rows_buf, now)
                    archive_rows_buf = []
                flush_deadline = mono + FLUSH_INTERVAL_SECS

            # Metrics log line
            if mono >= metrics_deadline:
                self._log_metrics(state, now)
                metrics_deadline = mono + METRICS_INTERVAL_SECS

            # Quote-set rotation: start as a background task (does not block recv loop).
            if mono >= quote_rotation_deadline and (_rotation_task is None or _rotation_task.done()):
                _rotation_task = asyncio.create_task(self._choose_quote_set(state))
                state.last_quote_rotation = mono
                quote_rotation_deadline = mono + QUOTE_ROTATION_SECS

        # Final archive flush for this session (clean window close).
        if state.archiver and archive_rows_buf:
            state.archiver.write_flush(archive_rows_buf, self._clock())

    def _emit_order(self, state: SessionState, order: Order, mono: float) -> None:
        """Emit a completed order: update metrics and rolling map.

        Step 2 will add ingest-buffer logic here.
        """
        state.metrics.add_order(order.premium, order.side_source)
        state.rolling_premium.add(order.contract, order.premium, mono)
        if self._dry_run:
            state.orders_counted += 1
        # (Step 2: non-dry-run ingest buffer goes here)

    async def _choose_quote_set(self, state: SessionState) -> tuple[list[str], list[str]]:
        """Build the universe, prune the rolling map, run QuoteSet.choose.

        Returns ``(subscribe_diff, unsubscribe_diff)``.  Errors in REST calls are
        logged and treated as empty universe.  Designed to run as an asyncio.Task.
        """
        universe_contracts = await self._build_universe_contracts(state)
        state.rolling_premium.prune(time.monotonic())
        rolling_map = state.rolling_premium.get_map()
        _, subscribe, unsubscribe = state.quote_set.choose(
            universe_contracts,
            rolling_map,
            cap=getattr(self, "_quote_cap", FLOW_QUOTE_CAP),
            universe_share=FLOW_QUOTE_UNIVERSE_SHARE,
        )
        return subscribe, unsubscribe

    async def _build_universe_contracts(
        self, state: SessionState
    ) -> list[tuple[str, str, float, int]]:
        """Return the near-money short-dated contracts for the static universe.

        Result: ``[(contract, ticker, abs_moneyness_frac, dte)]``.
        Returns ``[]`` when no data_client or universe path is configured.
        """
        if self._data_client is None or self._universe_path is None:
            return []
        try:
            tickers = _read_universe(self._universe_path)
        except OSError as exc:
            self._log.warning("flow: cannot read universe file %s: %s", self._universe_path, exc)
            return []
        if not tickers:
            return []

        # Fetch spot prices (one batched REST call on the DATA API)
        spots: dict[str, float] = {}
        if self._bucket.can_call():
            try:
                resp = await self._data_client.get(
                    STOCK_LATEST_URL,
                    params={"symbols": ",".join(tickers)},
                    headers={"APCA-API-KEY-ID": self._key_id, "APCA-API-SECRET-KEY": self._secret},
                    timeout=10.0,
                )
                self._bucket.consume()
                self._bucket.on_response(resp)
                state.metrics.add_rest_call()
                resp.raise_for_status()
                spots = {sym: float(d["p"]) for sym, d in resp.json().get("trades", {}).items()}
            except Exception as exc:
                self._bucket.consume()  # count failed calls too
                self._log.warning("flow: spot price fetch failed: %s", exc)

        # Options contracts: cached per day; fetched from the TRADING API.
        today = self._clock().astimezone(ET).date()
        today_str = today.isoformat()
        if state.options_cache_date != today_str:
            state.options_cache.clear()
            state.options_cache_date = today_str
        max_expiry = today + timedelta(days=FLOW_UNIVERSE_MAX_DTE)
        expiry_cutoff = max_expiry.isoformat()

        universe_contracts: list[tuple[str, str, float, int]] = []
        for ticker in tickers:
            spot = spots.get(ticker)
            if not spot or spot <= 0:
                continue
            if ticker not in state.options_cache:
                if self._bucket.can_call() and self._trading_client is not None:
                    try:
                        raw = await _fetch_option_contracts(
                            self._trading_client,
                            ticker,
                            expiry_cutoff,
                            spot,
                            self._key_id,
                            self._secret,
                            self._bucket,
                            metrics=state.metrics,  # counts per page
                        )
                        filtered: list[tuple[str, str, float, int]] = []
                        for contract_sym, exp_str, strike in raw:
                            mon = abs(strike - spot) / spot
                            if mon > FLOW_UNIVERSE_MONEYNESS_PCT:
                                continue
                            try:
                                exp = date.fromisoformat(exp_str)
                            except ValueError:
                                continue
                            dte_val = (exp - today).days
                            if dte_val < 0 or dte_val > FLOW_UNIVERSE_MAX_DTE:
                                continue
                            filtered.append((contract_sym, ticker, mon, dte_val))
                        state.options_cache[ticker] = filtered  # cache only on success
                    except Exception as exc:
                        self._log.warning(
                            "flow: options contracts fetch for %s failed: %s", ticker, exc
                        )
                        # Do not cache on failure: retry at the next rotation.
                elif self._trading_client is None:
                    # No client configured: will never work, skip silently.
                    pass
                # If rate-limited (bucket empty), do not cache: retry at the next rotation.
            universe_contracts.extend(state.options_cache.get(ticker, []))

        return universe_contracts

    def _log_metrics(self, state: SessionState, now: datetime) -> None:
        m = state.metrics
        # Per-interval rates (count in last interval ÷ interval seconds)
        trades_s_mean = m._trades_since_last_log / max(1.0, METRICS_INTERVAL_SECS)
        quotes_s_mean = m._quotes_since_last_log / max(1.0, METRICS_INTERVAL_SECS)
        orders_per_min = m._orders_since_last_log / max(1.0, METRICS_INTERVAL_SECS) * 60.0
        rss = rss_mb()
        # gaps= uses the archiver as the single source of truth when present (it records
        # both reconnect gaps and disk gaps); fall back to metrics.gap_count otherwise.
        gap_count = len(state.archiver.gaps) if state.archiver else m.gap_count
        self._log.info(
            "flow metrics: trades/s mean=%.1f peak=%d quotes/s mean=%.1f peak=%d "
            "loop_lag_max_ms=%.0f orders/min rate=%.1f total=%d >=25k=%d >=50k=%d >=100k=%d "
            "quoted_premium_share=%.2f rss_mb=%.0f rss_peak_mb=%.0f "
            "open_windows=%d quoted=%d rolling_map=%d seen_contracts=~%d "
            "archive_bytes=%d gap_seconds=%.0f gaps=%d reconnects=%d rest_calls_min=%d",
            trades_s_mean,
            m.trades_1s_peak,
            quotes_s_mean,
            m.quotes_1s_peak,
            m.loop_lag_max_ms,
            orders_per_min,
            m.orders_total,
            m.orders_25k,
            m.orders_50k,
            m.orders_100k,
            m.quoted_premium_share(),
            rss,
            m.rss_mb_peak,
            len(state.agg._open),
            len(state.quote_set.current),
            len(state.rolling_premium),
            len(state.seen_contracts),
            state.archiver.bytes_today if state.archiver else 0,
            m.gap_seconds,
            gap_count,
            m.reconnects,
            m.rest_calls_min_peak,
        )
        m.tick_metrics_interval()

    def _log_session_summary(self, state: SessionState, now: datetime) -> None:
        """End-of-session summary including session maxima."""
        m = state.metrics
        gap_count = len(state.archiver.gaps) if state.archiver else m.gap_count
        self._log.info(
            "flow session summary: trades=%d quotes=%d orders=%d >=25k=%d >=50k=%d >=100k=%d "
            "trades_1s_peak_session=%d quotes_1s_peak_session=%d loop_lag_max_ms_session=%.0f "
            "quoted_premium_share=%.2f rss_peak_mb=%.0f seen_contracts=~%d "
            "archive_bytes=%d gaps=%d reconnects=%d gap_seconds=%.0f",
            m.trades_total,
            m.quotes_total,
            m.orders_total,
            m.orders_25k,
            m.orders_50k,
            m.orders_100k,
            m.trades_1s_peak_session,
            m.quotes_1s_peak_session,
            m.loop_lag_max_ms_session,
            m.quoted_premium_share(),
            m.rss_mb_peak,
            len(state.seen_contracts),
            state.archiver.bytes_today if state.archiver else 0,
            gap_count,
            m.reconnects,
            m.gap_seconds,
        )


class _SlotHeldError(Exception):
    """The previous pod still holds the OPRA options-stream slot (406)."""


class _StreamError(Exception):
    """Reconnectable stream error: auth failure, protocol error, or socket drop."""


# ---------------------------------------------------------------------------
# REST helpers
# ---------------------------------------------------------------------------


def _read_universe(path: pathlib.Path) -> list[str]:
    """Read ticker names from a universe file (one per line; ``#`` = comment)."""
    tickers: list[str] = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                tickers.append(line)
    return tickers


async def _fetch_option_contracts(
    client: httpx.AsyncClient,
    ticker: str,
    expiry_cutoff: str,
    spot: float,
    key_id: str,
    secret: str,
    bucket: _RestBucket,
    metrics: Metrics | None = None,
) -> list[tuple[str, str, float]]:
    """Fetch active option contracts for ``ticker`` from the TRADING API
    (``api.alpaca.markets``), paginating with ``page_token`` until exhausted.

    Adds ``limit=10000`` and near-money strike bounds (± ``FLOW_UNIVERSE_MONEYNESS_PCT``
    of spot) to reduce payload size and page count.

    Each HTTP call (page) increments ``metrics.add_rest_call()`` when a metrics object
    is provided; failures are counted once per failed page.

    Returns ``[(symbol, expiration_date, strike_price)]``.
    """
    low_strike = spot * (1 - FLOW_UNIVERSE_MONEYNESS_PCT)
    high_strike = spot * (1 + FLOW_UNIVERSE_MONEYNESS_PCT)
    headers = {"APCA-API-KEY-ID": key_id, "APCA-API-SECRET-KEY": secret}
    result: list[tuple[str, str, float]] = []
    page_token: str | None = None

    while True:
        if not bucket.can_call():
            break  # budget exhausted; return what we have
        params: dict = {
            "underlying_symbols": ticker,
            "expiration_date_lte": expiry_cutoff,
            "status": "active",
            "limit": OPTIONS_CONTRACTS_LIMIT,
            "strike_price_gte": low_strike,
            "strike_price_lte": high_strike,
        }
        if page_token:
            params["page_token"] = page_token
        bucket.consume()
        if metrics is not None:
            metrics.add_rest_call()
        resp = await client.get(
            OPTIONS_CONTRACTS_PATH,
            params=params,
            headers=headers,
            timeout=10.0,
        )
        bucket.on_response(resp)
        resp.raise_for_status()
        body = resp.json()
        for row in body.get("option_contracts", []):
            sym = str(row.get("symbol", ""))
            exp = str(row.get("expiration_date", ""))
            try:
                strike = float(row.get("strike_price", 0))
            except (ValueError, TypeError):
                continue
            if sym and exp and strike > 0:
                result.append((sym, exp, strike))
        page_token = body.get("next_page_token") or None
        if not page_token:
            break

    return result


# ---------------------------------------------------------------------------
# Top-level loop
# ---------------------------------------------------------------------------


def _make_session_state(
    archive_dir: pathlib.Path | None,
    cap_bytes: int,
    free_floor_bytes: int,
    build: str,
    log: logging.Logger,
) -> SessionState:
    state = SessionState()
    if archive_dir:
        state.archiver = TapeArchiver(
            archive_dir,
            build=build,
            cap_bytes=cap_bytes,
            free_floor_bytes=free_floor_bytes,
            log=log,
        )
    return state


async def run_loop(
    *,
    key_id: str,
    secret: str,
    archive_dir: pathlib.Path | None,
    dry_run: bool,
    heartbeat_path: pathlib.Path,
    log: logging.Logger,
    connect_fn: Callable[..., Any] | None = None,
    clock: Callable[[], datetime] | None = None,
    sleep: Callable[[float], Awaitable[None]] | None = None,
    data_client: httpx.AsyncClient | None = None,
    trading_client: httpx.AsyncClient | None = None,
    universe_path: pathlib.Path | None = None,
    build: str = "",
    stop_event: asyncio.Event | None = None,
) -> None:
    """Poll forever: idle outside the 09:25–16:20 ET window, connect/stream inside.

    On disconnection, record the gap and reconnect with back-off.  A 406 backs off
    ``SLOT_HELD_BACKOFF_SECS`` (60 s) without counting as a reconnect (the previous
    pod is still alive).  A ``_StreamError`` reconnects with the same capped backoff.

    When ``stop_event`` is set (SIGTERM), the loop ends the current session (manifest +
    summary) and returns normally without calling ``loop.stop()``.
    """
    _clock = clock or now_utc
    _sleep = sleep or asyncio.sleep
    _data_client = data_client
    _trading_client = trading_client or data_client

    cap_bytes = int(os.getenv("FLOW_ARCHIVE_CAP_BYTES", str(FLOW_ARCHIVE_CAP_BYTES)))
    free_floor_bytes = int(os.getenv("FLOW_ARCHIVE_FREE_FLOOR_BYTES", str(FLOW_ARCHIVE_FREE_FLOOR_BYTES)))
    quote_cap = int(os.getenv("FLOW_QUOTE_CAP", str(FLOW_QUOTE_CAP)))

    bucket = _RestBucket()

    session = FlowSession(
        key_id=key_id,
        secret=secret,
        heartbeat_fn=heartbeat_path.touch,
        dry_run=dry_run,
        log=log,
        connect_fn=connect_fn,
        clock=_clock,
        data_client=_data_client,
        trading_client=_trading_client,
        universe_path=universe_path,
        rest_bucket=bucket,
    )
    session._quote_cap = quote_cap  # overridable via env (plan go/no-go)

    state = _make_session_state(archive_dir, cap_bytes, free_floor_bytes, build, log)

    reconnect_wait = RECONNECT_BASE_SECS
    last_disconnect_ts: str | None = None

    while True:
        # SIGTERM: end any active session and return normally.
        if stop_event is not None and stop_event.is_set():
            if state.archiver is not None or state.metrics.trades_total > 0:
                _end_session(session, state, _clock(), log, build)
            return

        heartbeat_path.touch()
        now = _clock()

        if not in_flow_window(now):
            # If a disconnect's back-off crossed the window close, end the session now.
            if last_disconnect_ts is not None and (
                state.archiver is not None or state.metrics.trades_total > 0
            ):
                # Record the open reconnect gap with to=null (session ended before reconnect).
                if state.archiver:
                    state.archiver.record_gap(last_disconnect_ts, None, "reconnect")
                state.metrics.gap_count += 1
                state.metrics.gap_seconds += (
                    now - datetime.fromisoformat(last_disconnect_ts.replace("Z", "+00:00"))
                ).total_seconds()
                _end_session(session, state, now, log, build)
                state = _make_session_state(archive_dir, cap_bytes, free_floor_bytes, build, log)
                last_disconnect_ts = None
            await _sleep(1.0)
            continue

        connect_args = dict(
            open_timeout=15,
            ping_interval=20,
            ping_timeout=20,
            max_size=2**22,
        )
        try:
            log.info("flow: connecting to %s", WS_URL)
            async with (connect_fn or websockets.connect)(WS_URL, **connect_args) as ws:
                # Record recovery gap if we were disconnected
                if last_disconnect_ts is not None:
                    reconnect_ts = _fmt_ts(now)
                    state.metrics.gap_count += 1
                    state.metrics.gap_seconds += (
                        now - datetime.fromisoformat(last_disconnect_ts.replace("Z", "+00:00"))
                    ).total_seconds()
                    if state.archiver:
                        state.archiver.record_gap(last_disconnect_ts, reconnect_ts, "reconnect")
                    last_disconnect_ts = None
                await session.run(state, ws, stop_event=stop_event)

            # session.run() returned cleanly (window closed or SIGTERM) — session end
            _end_session(session, state, _clock(), log, build)
            if stop_event is not None and stop_event.is_set():
                return  # SIGTERM: do not reset state and continue
            # Reset state for the next day
            state = _make_session_state(archive_dir, cap_bytes, free_floor_bytes, build, log)
            reconnect_wait = RECONNECT_BASE_SECS

        except _SlotHeldError as exc:
            log.warning("flow: slot held (406) — backing off %ss: %s", SLOT_HELD_BACKOFF_SECS, exc)
            heartbeat_path.touch()
            await _sleep(SLOT_HELD_BACKOFF_SECS)
        except _StreamError as exc:
            log.warning("flow: disconnected: %s — reconnecting in %.0fs", exc, reconnect_wait)
            state.metrics.reconnects += 1
            last_disconnect_ts = _fmt_ts(_clock())
            heartbeat_path.touch()
            await _sleep(reconnect_wait)
            reconnect_wait = min(reconnect_wait + RECONNECT_BASE_SECS, RECONNECT_MAX_SECS)
        except (  # noqa: BLE001 — same pattern as darkpool_collector; log + reconnect
            websockets.exceptions.WebSocketException,
            OSError,
        ) as exc:
            log.warning("flow: transport error: %r; reconnecting in %.0fs", exc, reconnect_wait)
            state.metrics.reconnects += 1
            last_disconnect_ts = _fmt_ts(_clock())
            heartbeat_path.touch()
            await _sleep(reconnect_wait)
            reconnect_wait = min(reconnect_wait + RECONNECT_BASE_SECS, RECONNECT_MAX_SECS)


def _end_session(
    session: FlowSession,
    state: SessionState,
    now: datetime,
    log: logging.Logger,
    build: str = "",
) -> None:
    """Flush open order windows, write the manifest, log the summary line."""
    for order in state.agg.flush_all():
        state.metrics.add_order(order.premium, order.side_source)
    if state.archiver:
        date_str = now.astimezone(ET).strftime("%Y-%m-%d")
        state.archiver.close_session(date_str, "09:25-16:20", build)
    session._log_session_summary(state, now)
    log.info("flow session ended (state reset for next day)")


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------


async def _run() -> int:
    load_dotenv()
    log = setup_logging(os.getenv("LOG_LEVEL", "INFO"), "flow_collector")
    dry_run = parse_dry_run(os.getenv("FLOW_DRY_RUN", "true"))
    build = os.getenv("FLOW_BUILD", "dev")

    state_dir = pathlib.Path(os.getenv("STATE_DIR", "/app/state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    heartbeat_path = state_dir / "heartbeat"

    archive_dir_env = os.getenv("FLOW_ARCHIVE_DIR", "")
    archive_dir = pathlib.Path(archive_dir_env) if archive_dir_env else None

    universe_path_env = os.getenv("DARKPOOL_UNIVERSE_FILE", "")
    universe_path = pathlib.Path(universe_path_env) if universe_path_env else None

    key_id = required("APCA_API_KEY_ID_DATA")
    secret = required("APCA_API_SECRET_KEY_DATA")

    log.info(
        "flow collector starting (dry_run=%s window=%s-%s ET archive=%s build=%s)",
        dry_run,
        WINDOW_OPEN_ET.strftime("%H:%M"),
        WINDOW_CLOSE_ET.strftime("%H:%M"),
        archive_dir or "disabled",
        build,
    )

    # SIGTERM: set an event; run_loop polls it and ends the session cleanly.
    # No loop.stop() — that causes a RuntimeError when the event loop is already running.
    stop_event = asyncio.Event()

    def _handle_sigterm() -> None:
        stop_event.set()

    asyncio.get_event_loop().add_signal_handler(signal.SIGTERM, _handle_sigterm)

    async with httpx.AsyncClient(base_url=DATA_BASE_URL, timeout=30.0) as data_cl:
        async with httpx.AsyncClient(base_url=TRADING_BASE_URL, timeout=30.0) as trading_cl:
            await run_loop(
                key_id=key_id,
                secret=secret,
                archive_dir=archive_dir,
                dry_run=dry_run,
                heartbeat_path=heartbeat_path,
                log=log,
                data_client=data_cl,
                trading_client=trading_cl,
                universe_path=universe_path,
                build=build,
                stop_event=stop_event,
            )
    return 0


def main() -> None:
    sys.exit(asyncio.run(_run()))


if __name__ == "__main__":
    main()
