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
"""

from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import resource
import sys
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime, time as dtime, timezone
from typing import Any

import websockets
import websockets.exceptions
from dotenv import load_dotenv

from .flow_collector import (
    ET,
    FLOW_ARCHIVE_CAP_BYTES,
    FLOW_ARCHIVE_FREE_FLOOR_BYTES,
    FLOW_QUOTE_CAP,
    FLOW_QUOTE_UNIVERSE_SHARE,
    Control,
    Order,
    OrderAggregator,
    Quote,
    QuoteSet,
    TapeArchiver,
    Trade,
    classify,
    parse_frame,
    trade_to_archive_row,
)
from .darkpool_main import parse_dry_run as _parse_dry_run
from .runtime import required, setup_logging

UTC = timezone.utc
WS_URL = "wss://stream.data.alpaca.markets/v1beta1/opra"
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
# Stock spot price endpoint (batch).
STOCK_LATEST_URL = "/v2/stocks/trades/latest"
# Max REST calls per minute (shared 10k budget with market-data and darkpool).
FLOW_MAX_REST_PER_MIN = 300


def parse_dry_run(raw: str | None) -> bool:
    """Fail-safe: only the literal ``false`` posts."""
    return _parse_dry_run(raw)


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
# Gap tracker
# ---------------------------------------------------------------------------


@dataclass
class GapRecord:
    from_ts: str
    reason: str
    to_ts: str | None = None


# ---------------------------------------------------------------------------
# Fake WebSocket for testing
# ---------------------------------------------------------------------------


class FakeWebSocket:
    """Minimal async context-manager fake for tests."""

    def __init__(self, frames: list[bytes], *, close_after: int = -1) -> None:
        self._frames = list(frames)
        self._sent: list[bytes] = []
        self.close_after = close_after
        self._count = 0
        self.closed = False

    async def send(self, data: bytes) -> None:
        self._sent.append(data)

    async def recv(self) -> bytes:
        if self.close_after >= 0 and self._count >= self.close_after:
            raise websockets.exceptions.ConnectionClosed(None, None)
        if not self._frames:
            raise websockets.exceptions.ConnectionClosed(None, None)
        self._count += 1
        return self._frames.pop(0)

    async def __aenter__(self) -> FakeWebSocket:
        return self

    async def __aexit__(self, *args: Any) -> None:
        self.closed = True


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------


@dataclass
class Metrics:
    trades_total: int = 0
    trades_1s_peak: int = 0
    _trades_this_second: int = 0
    quotes_total: int = 0
    quotes_1s_peak: int = 0
    _quotes_this_second: int = 0
    loop_lag_max_ms: float = 0.0
    orders_total: int = 0
    orders_25k: int = 0
    orders_50k: int = 0
    orders_100k: int = 0
    quoted_premium: float = 0.0
    total_premium: float = 0.0
    rss_mb_peak: float = 0.0
    distinct_contracts: int = 0
    reconnects: int = 0
    gap_seconds: float = 0.0
    rest_calls_min_peak: int = 0
    _last_tick_time: float = field(default_factory=time.monotonic)

    def tick_second(self) -> None:
        now = time.monotonic()
        lag_ms = (now - self._last_tick_time) * 1000.0
        self.loop_lag_max_ms = max(self.loop_lag_max_ms, lag_ms)
        self._last_tick_time = now
        self.trades_1s_peak = max(self.trades_1s_peak, self._trades_this_second)
        self.quotes_1s_peak = max(self.quotes_1s_peak, self._quotes_this_second)
        self._trades_this_second = 0
        self._quotes_this_second = 0
        rss = rss_mb()
        self.rss_mb_peak = max(self.rss_mb_peak, rss)

    def add_trade(self) -> None:
        self.trades_total += 1
        self._trades_this_second += 1

    def add_quote(self) -> None:
        self.quotes_total += 1
        self._quotes_this_second += 1

    def add_order(self, premium: float, side_source: str) -> None:
        self.orders_total += 1
        if premium >= 25_000:
            self.orders_25k += 1
        if premium >= 50_000:
            self.orders_50k += 1
        if premium >= 100_000:
            self.orders_100k += 1
        self.total_premium += premium
        if side_source == "quote":
            self.quoted_premium += premium


# ---------------------------------------------------------------------------
# Flow runner (the real loop + the testable core)
# ---------------------------------------------------------------------------


@dataclass
class SessionState:
    """Mutable state for one streaming session (reset on reconnect)."""

    agg: OrderAggregator = field(default_factory=OrderAggregator)
    quote_cache: dict[str, Quote] = field(default_factory=dict)  # contract → latest Quote
    quote_set: QuoteSet = field(default_factory=QuoteSet)
    metrics: Metrics = field(default_factory=Metrics)
    archiver: TapeArchiver | None = None
    last_trade_ts: str | None = None
    gap_open: GapRecord | None = None
    gaps: list[GapRecord] = field(default_factory=list)
    seen_contracts: set[str] = field(default_factory=set)
    orders_pending: list[Order] = field(default_factory=list)  # not yet posted (Step 2)
    last_quote_rotation: float = field(default_factory=time.monotonic)
    last_metrics_log: float = field(default_factory=time.monotonic)


class FlowSession:
    """The streaming client for one connection lifetime.

    Designed for testability: ``connect_fn`` and ``clock`` are injectable, and the
    full public protocol is exercised via ``run()`` over a ``FakeWebSocket``.
    """

    def __init__(
        self,
        *,
        key_id: str,
        secret: str,
        heartbeat_fn: Callable[[], None],
        archive_dir: pathlib.Path | None,
        dry_run: bool,
        log: logging.Logger,
        connect_fn: Callable[..., Any] | None = None,
        clock: Callable[[], datetime] | None = None,
        sleep: Callable[[float], Awaitable[None]] | None = None,
    ) -> None:
        self._key_id = key_id
        self._secret = secret
        self._heartbeat = heartbeat_fn
        self._archive_dir = archive_dir
        self._dry_run = dry_run
        self._log = log
        self._connect_fn = connect_fn or websockets.connect
        self._clock = clock or now_utc
        self._sleep = sleep or asyncio.sleep

    async def run(
        self,
        state: SessionState,
        ws: Any,
    ) -> None:
        """Run one WebSocket session from greeting through trades until disconnection."""
        import msgpack

        self._log.info("flow session started")
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

        # Subscribe: wildcard trades + current quote set
        sub_msg: dict = {"action": "subscribe", "trades": ["*"]}
        if state.quote_set.current:
            sub_msg["quotes"] = sorted(state.quote_set.current)
        await ws.send(msgpack.packb(sub_msg))

        # Read stream
        flush_deadline = time.monotonic() + FLUSH_INTERVAL_SECS
        metrics_deadline = time.monotonic() + METRICS_INTERVAL_SECS
        quote_rotation_deadline = state.last_quote_rotation + QUOTE_ROTATION_SECS

        archive_rows_buf: list[dict] = []

        while True:
            self._heartbeat()
            now = self._clock()

            if not in_flow_window(now):
                break

            # Throttle recv: use wait_for with a short timeout so we can tick
            try:
                frame = await asyncio.wait_for(ws.recv(), timeout=0.1)
            except asyncio.TimeoutError:
                frame = None
            except (websockets.exceptions.ConnectionClosed, EOFError, OSError):
                break

            if frame is not None:
                for rec in parse_frame(frame):
                    if isinstance(rec, Trade):
                        state.metrics.add_trade()
                        state.seen_contracts.add(rec.contract)
                        quote = state.quote_cache.get(rec.contract)
                        labels = classify(rec, quote, now)
                        labels["_quote"] = quote
                        closed = state.agg.feed(rec, labels)
                        for order in closed:
                            state.metrics.add_order(order.premium, order.side_source)
                            if not self._dry_run:
                                state.orders_pending.append(order)
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

            # 1-second tick
            if mono >= flush_deadline:
                state.metrics.tick_second()
                # Flush idle order windows
                for order in state.agg.flush(now):
                    state.metrics.add_order(order.premium, order.side_source)
                    if not self._dry_run:
                        state.orders_pending.append(order)
                # Archive flush
                if state.archiver and archive_rows_buf:
                    state.archiver.write_flush(archive_rows_buf, now)
                    archive_rows_buf = []
                flush_deadline = mono + FLUSH_INTERVAL_SECS

            # Metrics log line
            if mono >= metrics_deadline:
                self._log_metrics(state, now)
                metrics_deadline = mono + METRICS_INTERVAL_SECS

            # Quote-set rotation
            if mono >= quote_rotation_deadline:
                await self._rotate_quotes(state, ws)
                state.last_quote_rotation = mono
                quote_rotation_deadline = mono + QUOTE_ROTATION_SECS

        # Final archive flush for this session
        if state.archiver and archive_rows_buf:
            state.archiver.write_flush(archive_rows_buf, self._clock())

    async def _rotate_quotes(self, state: SessionState, ws: Any) -> None:
        """Re-choose the quoted set and send subscribe/unsubscribe diffs."""
        import msgpack

        # In Step 1, the universe is the static darkpool-universe (no BFF call).
        # QuoteSet.choose takes (universe_contracts, rolling_premium, cap, share).
        # With no spot prices or rolling_premium available in Step 1, use empty args.
        _, subscribe, unsubscribe = state.quote_set.choose(
            [],  # no universe in Step 1 (spot REST not wired)
            {},  # no rolling premium
            cap=FLOW_QUOTE_CAP,
            universe_share=FLOW_QUOTE_UNIVERSE_SHARE,
        )
        if subscribe:
            await ws.send(msgpack.packb({"action": "subscribe", "quotes": subscribe}))
        if unsubscribe:
            await ws.send(msgpack.packb({"action": "unsubscribe", "quotes": unsubscribe}))

    def _log_metrics(self, state: SessionState, now: datetime) -> None:
        m = state.metrics
        trades_s_mean = (
            m.trades_total / max(1, METRICS_INTERVAL_SECS)
        )  # approximation over the window
        rss = rss_mb()
        self._log.info(
            "flow metrics: trades/s mean=%.1f peak=%d quotes/s mean=%.1f peak=%d "
            "loop_lag_max_ms=%.0f orders/min total=%d >=25k=%d >=50k=%d >=100k=%d "
            "quoted_premium_share=%.2f rss_mb=%.0f rss_peak_mb=%.0f "
            "open_windows=%d quoted=%d seen_contracts=%d "
            "archive_bytes=%d gaps=%d reconnects=%d",
            trades_s_mean,
            m.trades_1s_peak,
            m.quotes_total / max(1, METRICS_INTERVAL_SECS),
            m.quotes_1s_peak,
            m.loop_lag_max_ms,
            m.orders_total,
            m.orders_25k,
            m.orders_50k,
            m.orders_100k,
            m.quoted_premium / max(1.0, m.total_premium) if m.total_premium else 0.0,
            rss,
            m.rss_mb_peak,
            len(state.agg._open),
            len(state.quote_set.current),
            len(state.seen_contracts),
            state.archiver.bytes_today if state.archiver else 0,
            len(state.gaps),
            m.reconnects,
        )


class _SlotHeldError(Exception):
    """The previous pod still holds the OPRA options-stream slot (406)."""


class _StreamError(Exception):
    """Unrecoverable stream error."""


# ---------------------------------------------------------------------------
# Top-level loop
# ---------------------------------------------------------------------------


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
) -> None:
    """Poll forever: idle outside the 09:25–16:20 ET window, connect/stream inside.

    On disconnection, record the gap and reconnect with back-off.  A 406 backs off
    ``SLOT_HELD_BACKOFF_SECS`` (60 s) without counting as a reconnect (the previous
    pod is still alive).
    """
    _clock = clock or now_utc
    _sleep = sleep or asyncio.sleep

    session = FlowSession(
        key_id=key_id,
        secret=secret,
        heartbeat_fn=heartbeat_path.touch,
        archive_dir=archive_dir,
        dry_run=dry_run,
        log=log,
        connect_fn=connect_fn,
        clock=_clock,
        sleep=_sleep,
    )

    state = SessionState()
    if archive_dir:
        state.archiver = TapeArchiver(
            archive_dir,
            cap_bytes=int(os.getenv("FLOW_ARCHIVE_CAP_BYTES", str(FLOW_ARCHIVE_CAP_BYTES))),
            free_floor_bytes=int(os.getenv("FLOW_ARCHIVE_FREE_FLOOR_BYTES", str(FLOW_ARCHIVE_FREE_FLOOR_BYTES))),
            log=log,
        )

    reconnect_wait = RECONNECT_BASE_SECS
    last_disconnect_ts: str | None = None

    while True:
        heartbeat_path.touch()
        now = _clock()

        if not in_flow_window(now):
            await _sleep(1.0)
            continue

        connect_args = dict(
            open_timeout=15,
            ping_interval=None,
            max_size=2**22,
        )
        try:
            log.info("flow: connecting to %s", WS_URL)
            async with (connect_fn or websockets.connect)(WS_URL, **connect_args) as ws:
                # Record recovery gap if we were disconnected
                if last_disconnect_ts is not None:
                    reconnect_ts = _fmt_ts(now)
                    state.gaps.append(GapRecord(
                        from_ts=last_disconnect_ts,
                        reason="reconnect",
                        to_ts=reconnect_ts,
                    ))
                    if state.archiver:
                        state.archiver.record_gap(last_disconnect_ts, reconnect_ts, "reconnect")
                    state.metrics.gap_seconds += (
                        now - datetime.fromisoformat(last_disconnect_ts.replace("Z", "+00:00"))
                    ).total_seconds()
                    last_disconnect_ts = None
                await session.run(state, ws)
            # session.run() returned cleanly (window closed) → reset back-off for next morning
            reconnect_wait = RECONNECT_BASE_SECS
        except _SlotHeldError as exc:
            log.warning("flow: slot held (406) — backing off %ss: %s", SLOT_HELD_BACKOFF_SECS, exc)
            heartbeat_path.touch()
            await _sleep(SLOT_HELD_BACKOFF_SECS)
        except _StreamError as exc:
            log.error("flow: stream error: %s", exc)
            heartbeat_path.touch()
        except (websockets.exceptions.WebSocketException, OSError, Exception) as exc:
            log.warning("flow: disconnected: %r; reconnecting in %.0fs", exc, reconnect_wait)
            state.metrics.reconnects += 1
            last_disconnect_ts = _fmt_ts(_clock())
            heartbeat_path.touch()
            await _sleep(reconnect_wait)
            reconnect_wait = min(reconnect_wait + RECONNECT_BASE_SECS, RECONNECT_MAX_SECS)


def _fmt_ts(dt: datetime) -> str:
    dt = dt.astimezone(UTC)
    if dt.microsecond:
        return dt.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------


async def _run() -> int:
    load_dotenv()
    log = setup_logging(os.getenv("LOG_LEVEL", "INFO"), "flow_collector")
    dry_run = parse_dry_run(os.getenv("FLOW_DRY_RUN", "true"))

    state_dir = pathlib.Path(os.getenv("STATE_DIR", "/app/state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    heartbeat_path = state_dir / "heartbeat"

    archive_dir_env = os.getenv("FLOW_ARCHIVE_DIR", "")
    archive_dir = pathlib.Path(archive_dir_env) if archive_dir_env else None

    key_id = required("APCA_API_KEY_ID_DATA")
    secret = required("APCA_API_SECRET_KEY_DATA")

    log.info(
        "flow collector starting (dry_run=%s window=%s-%s ET archive=%s)",
        dry_run,
        WINDOW_OPEN_ET.strftime("%H:%M"),
        WINDOW_CLOSE_ET.strftime("%H:%M"),
        archive_dir or "disabled",
    )

    await run_loop(
        key_id=key_id,
        secret=secret,
        archive_dir=archive_dir,
        dry_run=dry_run,
        heartbeat_path=heartbeat_path,
        log=log,
    )
    return 1


def main() -> None:
    sys.exit(asyncio.run(_run()))


if __name__ == "__main__":
    main()
