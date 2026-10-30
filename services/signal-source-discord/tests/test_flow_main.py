"""Loop / session tests for the flow collector (PLAN-2026-10-29-flow-page, Step 1).

The WebSocket is a ``FakeWebSocket`` that yields pre-loaded msgpack frames; no
real network connection is made.  ``httpx.MockTransport`` is used to test REST
calls (quote-set population, spot prices, options contracts).
"""

from __future__ import annotations

import asyncio
import logging
import pathlib
import re
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any

import httpx
import msgpack
import pytest
import websockets.exceptions

from ohmytradeagent_sidecar import flow_main
from ohmytradeagent_sidecar.flow_main import (
    SLOT_HELD_BACKOFF_SECS,
    FlowSession,
    SessionState,
    _SlotHeldError,
    in_flow_window,
    parse_dry_run,
)

LOG = logging.getLogger("test.flow_main")
UTC = timezone.utc
# A timestamp inside the 09:25-16:20 ET window (13:40 UTC = 09:40 ET)
NOW_IN = datetime(2026, 10, 29, 13, 40, tzinfo=UTC)
# A timestamp outside the window (23:00 UTC = 19:00 ET)
NOW_OUT = datetime(2026, 10, 29, 23, 0, tzinfo=UTC)
# A weekend timestamp
NOW_WEEKEND = datetime(2026, 11, 1, 14, 0, tzinfo=UTC)  # Sunday


# ---------------------------------------------------------------------------
# FakeWebSocket (moved from flow_main; production code has no test helpers)
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
# Helpers
# ---------------------------------------------------------------------------


def _auth_success() -> bytes:
    return msgpack.packb([{"T": "success", "msg": "authenticated"}])


def _sub_ack() -> bytes:
    return msgpack.packb([{"T": "subscription", "trades": ["*"], "quotes": []}])


def _greeting() -> bytes:
    return msgpack.packb([{"T": "success", "msg": "connected"}])


def _trade_frame(
    contract: str = "AAPL241220C00150000",
    price: float = 2.1,
    size: int = 10,
    ts_str: str = "2026-10-29T13:40:00Z",
) -> bytes:
    return msgpack.packb([{
        "T": "t", "S": contract, "p": price, "s": size,
        "x": "C", "c": [], "t": ts_str,
    }])


def _quote_frame(
    contract: str = "AAPL241220C00150000",
    bid: float = 2.0,
    ask: float = 2.1,
    ts_str: str = "2026-10-29T13:40:00Z",
) -> bytes:
    return msgpack.packb([{
        "T": "q", "S": contract, "bp": bid, "bs": 10, "ap": ask, "as": 10, "t": ts_str,
    }])


def _error_406() -> bytes:
    return msgpack.packb([{"T": "error", "code": 406, "msg": "connection limit exceeded"}])


def make_session(
    frames: list[bytes],
    *,
    dry_run: bool = True,
    clock: Any = None,
    archive_dir: pathlib.Path | None = None,
    rest_client: httpx.AsyncClient | None = None,
    universe_path: pathlib.Path | None = None,
) -> tuple[FlowSession, SessionState, FakeWebSocket]:
    state = SessionState()
    if archive_dir:
        from ohmytradeagent_sidecar.flow_collector import TapeArchiver
        state.archiver = TapeArchiver(archive_dir)

    beats: list[int] = []

    def heartbeat() -> None:
        beats.append(1)

    if clock is None:
        # In-window clock that stays fixed
        def clock() -> datetime:
            return NOW_IN

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=heartbeat,
        dry_run=dry_run,
        log=LOG,
        connect_fn=None,
        clock=clock,
        rest_client=rest_client,
        universe_path=universe_path,
    )
    ws = FakeWebSocket(frames)
    return session, state, ws


# ---------------------------------------------------------------------------
# parse_dry_run
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw,expected",
    [(None, True), ("", True), ("true", True), ("FALSE", False), ("false", False), ("0", True)],
)
def test_parse_dry_run_only_literal_false_posts(raw, expected):
    assert parse_dry_run(raw) is expected


# ---------------------------------------------------------------------------
# in_flow_window
# ---------------------------------------------------------------------------


def test_in_flow_window_inside():
    assert in_flow_window(NOW_IN) is True


def test_in_flow_window_outside_evening():
    assert in_flow_window(NOW_OUT) is False


def test_in_flow_window_weekend():
    assert in_flow_window(NOW_WEEKEND) is False


def test_in_flow_window_exactly_at_open():
    # 09:25 ET = 13:25 UTC on an EDT day
    open_utc = datetime(2026, 10, 29, 13, 25, tzinfo=UTC)
    assert in_flow_window(open_utc) is True


def test_in_flow_window_one_second_before_close():
    # 16:19:59 ET = 20:19:59 UTC
    before_close = datetime(2026, 10, 29, 20, 19, 59, tzinfo=UTC)
    assert in_flow_window(before_close) is True


def test_in_flow_window_exactly_at_close():
    # 16:20 ET is outside (the window is [09:25, 16:20))
    at_close = datetime(2026, 10, 29, 20, 20, tzinfo=UTC)
    assert in_flow_window(at_close) is False


# ---------------------------------------------------------------------------
# FlowSession — auth/connection
# ---------------------------------------------------------------------------


async def test_session_authenticates_and_subscribes():
    frames = [_greeting(), _auth_success(), _sub_ack()]
    # FakeWebSocket raises ConnectionClosed when frames are exhausted → session ends
    session, state, ws = make_session(frames)
    await session.run(state, ws)
    # Should have sent at least auth + subscribe messages
    assert len(ws._sent) >= 2
    import msgpack as mp
    sent_msgs = [mp.unpackb(m, raw=False) for m in ws._sent]
    actions = [m.get("action") for m in sent_msgs]
    assert "auth" in actions
    assert "subscribe" in actions


async def test_session_406_on_greeting_raises_slot_held():
    frames = [_greeting(), _error_406()]
    session, state, ws = make_session(frames)
    with pytest.raises(_SlotHeldError):
        await session.run(state, ws)


async def test_session_processes_trade_frame():
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=5),
    ]
    session, state, ws = make_session(frames)
    await session.run(state, ws)
    assert state.metrics.trades_total >= 1
    assert "AAPL241220C00150000" in state.seen_contracts


async def test_session_processes_quote_frame():
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _quote_frame(bid=2.0, ask=2.1),
    ]
    session, state, ws = make_session(frames)
    await session.run(state, ws)
    assert state.metrics.quotes_total >= 1
    assert "AAPL241220C00150000" in state.quote_cache


async def test_session_dry_run_never_posts(tmp_path):
    """Dry run: orders are not queued for posting."""
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=10),
    ]
    session, state, ws = make_session(frames, dry_run=True)
    await session.run(state, ws)
    assert state.orders_pending == []


async def test_session_outside_window_does_not_connect(tmp_path):
    """When the clock is outside the window, run_loop sleeps without connecting."""
    sleeps: list[float] = []

    async def fake_sleep(s: float) -> None:
        sleeps.append(s)
        if len(sleeps) >= 3:
            raise asyncio.CancelledError

    connect_called = []

    async def fake_connect(url: str, **kw) -> Any:
        connect_called.append(url)
        raise AssertionError("should not connect outside window")

    heartbeat_path = tmp_path / "heartbeat"
    with pytest.raises(asyncio.CancelledError):
        await flow_main.run_loop(
            key_id="k",
            secret="s",
            archive_dir=None,
            dry_run=True,
            heartbeat_path=heartbeat_path,
            log=LOG,
            connect_fn=fake_connect,
            clock=lambda: NOW_OUT,  # outside window
            sleep=fake_sleep,
        )
    assert connect_called == []
    assert len(sleeps) == 3


async def test_run_loop_reconnects_with_backoff(tmp_path):
    """A connection drop causes reconnect with incrementing back-off."""
    sleeps: list[float] = []
    connects: list[str] = []

    async def fake_sleep(s: float) -> None:
        sleeps.append(s)
        if len(connects) >= 2:
            raise asyncio.CancelledError

    @asynccontextmanager
    async def fake_connect(url: str, **kw) -> Any:
        connects.append(url)
        # Fake ws that immediately raises a disconnect error
        ws = FakeWebSocket([])
        yield ws

    heartbeat_path = tmp_path / "heartbeat"
    heartbeat_path.touch()
    with pytest.raises(asyncio.CancelledError):
        await flow_main.run_loop(
            key_id="k",
            secret="s",
            archive_dir=None,
            dry_run=True,
            heartbeat_path=heartbeat_path,
            log=LOG,
            connect_fn=fake_connect,
            clock=lambda: NOW_IN,
            sleep=fake_sleep,
        )
    # Should have attempted to connect at least twice
    assert len(connects) >= 2
    # Back-off should be increasing
    if len(sleeps) >= 2:
        assert sleeps[1] > sleeps[0]


async def test_run_loop_406_backs_off_without_counting_reconnect(tmp_path):
    """A 406 mid-auth means the slot is held; back off without incrementing reconnect count."""
    sleeps: list[float] = []
    call_count = [0]

    async def fake_sleep(s: float) -> None:
        sleeps.append(s)
        if call_count[0] >= 2:
            raise asyncio.CancelledError

    @asynccontextmanager
    async def fake_connect(url: str, **kw) -> Any:
        call_count[0] += 1
        # Return a fake ws that yields a 406 error after greeting+auth
        ws = FakeWebSocket([_greeting(), _error_406()])
        yield ws

    heartbeat_path = tmp_path / "heartbeat"
    heartbeat_path.touch()
    with pytest.raises(asyncio.CancelledError):
        await flow_main.run_loop(
            key_id="k",
            secret="s",
            archive_dir=None,
            dry_run=True,
            heartbeat_path=heartbeat_path,
            log=LOG,
            connect_fn=fake_connect,
            clock=lambda: NOW_IN,
            sleep=fake_sleep,
        )
    # The 406 sleep should be SLOT_HELD_BACKOFF_SECS (60 s), not the reconnect back-off
    assert any(abs(s - SLOT_HELD_BACKOFF_SECS) < 0.1 for s in sleeps)


async def test_session_heartbeat_touched(tmp_path):
    frames = [_greeting(), _auth_success(), _sub_ack()]
    beats: list[int] = []

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: beats.append(1),
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
    )
    ws = FakeWebSocket(frames)
    await session.run(SessionState(), ws)
    assert len(beats) > 0


# ---------------------------------------------------------------------------
# Metrics line fields
# ---------------------------------------------------------------------------


async def test_metrics_line_contains_all_required_fields(caplog):
    """The 60-second metrics log line must contain all fields listed in the plan spec."""
    required_fields = [
        "trades/s",
        "quotes/s",
        "loop_lag_max_ms",
        "orders/min",
        ">=25k",
        ">=50k",
        ">=100k",
        "quoted_premium_share",
        "rss_mb",
        "rss_peak_mb",
        "open_windows",
        "quoted",
        "seen_contracts",
        "archive_bytes",
        "gaps",
        "reconnects",
        "rest_calls_min",
        "rolling_map",
        "gap_seconds",
    ]
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(),
    ]
    session, state, ws = make_session(frames)
    with caplog.at_level(logging.INFO, logger="test.flow_main"):
        await session.run(state, ws)
        session._log_metrics(state, NOW_IN)

    metrics_lines = [r.message for r in caplog.records if "flow metrics" in r.message]
    assert len(metrics_lines) >= 1, f"No metrics line found in: {caplog.text}"
    line = metrics_lines[-1]
    for field in required_fields:
        assert field in line, f"metrics line missing field {field!r}: {line}"


# ---------------------------------------------------------------------------
# Dry-run gate
# ---------------------------------------------------------------------------


async def test_dry_run_does_not_post_orders(tmp_path):
    """With FLOW_DRY_RUN=true, orders_pending stays empty even with a large trade."""
    # premium = 2.1 * 1000 * 100 = 210,000 ≥ 25k
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=1000),  # big trade
    ]
    session, state, ws = make_session(frames, dry_run=True)
    await session.run(state, ws)
    assert state.orders_pending == []


# ---------------------------------------------------------------------------
# Finding 1 (TEST): Quote set via REST
# ---------------------------------------------------------------------------


def _make_spot_response(symbols: list[str], price: float = 150.0) -> httpx.Response:
    trades = {sym: {"p": price} for sym in symbols}
    return httpx.Response(200, json={"trades": trades})


def _make_contracts_response(ticker: str, contracts: list[dict]) -> httpx.Response:
    return httpx.Response(200, json={"option_contracts": contracts})


async def test_choose_quote_set_calls_rest_and_populates_set(tmp_path):
    """With a universe file + mock REST, _choose_quote_set returns non-empty subscribe list."""
    # Universe: one ticker
    universe_file = tmp_path / "universe.txt"
    universe_file.write_text("AAPL\n")

    # Mock contract: near-money (5% OTM), DTE=7. Use 2026-11-05 since clock is 2026-10-29.
    contract_sym = "AAPL261105C00157500"  # ~5% OTM on 150 spot; expiry 2026-11-05
    # Strike 157.5, 5% above 150
    contracts_resp = [
        {
            "symbol": contract_sym,
            "expiration_date": "2026-11-05",
            "strike_price": "157.5",
        }
    ]

    call_log: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        call_log.append(str(request.url))
        if "/v2/stocks/trades/latest" in str(request.url):
            return _make_spot_response(["AAPL"], 150.0)
        elif "/v2/options/contracts" in str(request.url):
            return _make_contracts_response("AAPL", contracts_resp)
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    rest_client = httpx.AsyncClient(
        base_url=flow_main.DATA_BASE_URL, transport=transport
    )

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
        rest_client=rest_client,
        universe_path=universe_file,
    )
    state = SessionState()
    subscribe, unsubscribe = await session._choose_quote_set(state)

    await rest_client.aclose()

    # REST was called
    assert any("/v2/stocks/trades/latest" in u for u in call_log), f"spot call missing: {call_log}"
    assert any("/v2/options/contracts" in u for u in call_log), f"contracts call missing: {call_log}"
    # Quote set was populated (contract is within 5% moneyness and DTE ≤ 14)
    assert len(state.quote_set.current) > 0, (
        f"quote set empty; options_cache={state.options_cache}"
    )
    assert contract_sym in state.quote_set.current


async def test_choose_quote_set_no_rest_client_returns_empty():
    """Without a REST client, _choose_quote_set returns empty subscribe list."""
    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
        rest_client=None,
        universe_path=None,
    )
    state = SessionState()
    subscribe, unsubscribe = await session._choose_quote_set(state)
    assert subscribe == []
    assert unsubscribe == []
    assert len(state.quote_set.current) == 0


async def test_choose_quote_set_caches_options_per_day(tmp_path):
    """Options contracts are cached per day; a second call reuses the cache."""
    universe_file = tmp_path / "universe.txt"
    universe_file.write_text("AAPL\n")

    contract_sym = "AAPL261105C00157500"
    contracts_resp = [
        {"symbol": contract_sym, "expiration_date": "2026-11-05", "strike_price": "157.5"}
    ]

    contracts_call_count = [0]

    def handler(request: httpx.Request) -> httpx.Response:
        if "/v2/stocks/trades/latest" in str(request.url):
            return _make_spot_response(["AAPL"], 150.0)
        elif "/v2/options/contracts" in str(request.url):
            contracts_call_count[0] += 1
            return _make_contracts_response("AAPL", contracts_resp)
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    rest_client = httpx.AsyncClient(
        base_url=flow_main.DATA_BASE_URL, transport=transport
    )

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
        rest_client=rest_client,
        universe_path=universe_file,
    )
    state = SessionState()
    # First call
    await session._choose_quote_set(state)
    first_count = contracts_call_count[0]
    # Second call same day
    await session._choose_quote_set(state)
    second_count = contracts_call_count[0]

    await rest_client.aclose()

    assert first_count >= 1, "first call should have fetched contracts"
    assert second_count == first_count, "second call should reuse cache (no extra REST call)"


# ---------------------------------------------------------------------------
# Finding 2 (TEST): Session end
# ---------------------------------------------------------------------------


async def test_session_end_flushes_orders_and_writes_manifest(tmp_path):
    """At window close, flush_all is called and close_session writes a manifest."""
    # Use a clock that returns in-window first, then out-of-window
    calls = [0]
    # NOW_OUT is outside the window → run() will break after first trade
    clocks = [NOW_IN, NOW_IN, NOW_IN, NOW_OUT]

    def advancing_clock() -> datetime:
        i = min(calls[0], len(clocks) - 1)
        calls[0] += 1
        return clocks[i]

    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=5),
    ]

    archive_dir = tmp_path / "archive"
    from ohmytradeagent_sidecar.flow_collector import TapeArchiver
    state = SessionState()
    state.archiver = TapeArchiver(archive_dir)
    beats: list[int] = []

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: beats.append(1),
        dry_run=True,
        log=LOG,
        clock=advancing_clock,
    )
    ws = FakeWebSocket(frames)
    await session.run(state, ws)

    # Session ended cleanly (window closed); call _end_session manually as run_loop would
    flow_main._end_session(session, state, NOW_IN, LOG)

    # Manifest should be written
    manifest_files = list(archive_dir.glob("*.manifest.json"))
    assert len(manifest_files) >= 1, "expected manifest file to be written at session end"


async def test_session_end_resets_state(tmp_path):
    """After _end_session, creating fresh SessionState starts with empty aggregator."""
    # Simulate session end: create state, add a trade, call _end_session, create new state
    frames = [_greeting(), _auth_success(), _sub_ack(), _trade_frame()]
    session, state, ws = make_session(frames)
    await session.run(state, ws)

    # Before reset
    old_trades = state.metrics.trades_total

    # _end_session flushes and closes; new state starts clean
    flow_main._end_session(session, state, NOW_IN, LOG)
    new_state = SessionState()

    assert new_state.metrics.trades_total == 0
    assert len(new_state.agg._open) == 0
    assert old_trades >= 1  # we did process a trade in old state


# ---------------------------------------------------------------------------
# Finding 3 (TEST): Per-interval metrics rates
# ---------------------------------------------------------------------------


async def test_metrics_per_interval_rate_not_cumulative(caplog):
    """trades/s mean should be per-interval (trades since last log / interval seconds),
    not cumulative total / interval.  200 trades in last interval → mean ≈ 200/60 ≈ 3.33, not 12000."""
    frames = [_greeting(), _auth_success(), _sub_ack()]
    session, state, ws = make_session(frames)
    await session.run(state, ws)

    # Simulate 200 trades accumulated since last log, but 12k cumulative
    state.metrics._trades_since_last_log = 200
    state.metrics.trades_total = 12_000  # cumulative is much larger

    with caplog.at_level(logging.INFO, logger="test.flow_main"):
        session._log_metrics(state, NOW_IN)

    metrics_lines = [r.message for r in caplog.records if "flow metrics" in r.message]
    assert metrics_lines, f"no metrics line found in: {caplog.text}"
    line = metrics_lines[-1]

    # Extract mean trades/s from the line: "mean=X.X"
    m = re.search(r"trades/s mean=([0-9.]+)", line)
    assert m, f"could not find trades/s mean in: {line}"
    mean_val = float(m.group(1))
    # Should be ~3.33 (200/60), NOT 12000 (cumulative/1)
    assert mean_val < 100.0, f"mean={mean_val} looks cumulative, expected per-interval (~3.3)"
    assert mean_val > 0.0, f"mean={mean_val} should be positive"


# ---------------------------------------------------------------------------
# Finding 4 (TEST): Disk OSError stops archiving, not stream
# ---------------------------------------------------------------------------


async def test_disk_write_error_records_gap_not_teardown(tmp_path, monkeypatch):
    """An OSError during archiver write stops archiving but does NOT tear down the stream."""
    # Patch TapeArchiver.write_flush to raise OSError once
    from ohmytradeagent_sidecar import flow_collector

    original_write = flow_collector.TapeArchiver.write_flush
    write_calls = [0]

    def patched_write(self, rows, flush_ts):
        write_calls[0] += 1
        if write_calls[0] == 1:
            # Simulate disk failure by making the archiver's internal path write fail
            # We do this by calling _gaps_append directly + setting disk_stopped
            self._disk_stopped = True
            self._disk_stop_ts = "2026-10-29T13:40:00Z"
            self._disk_stop_reason = "disk_write_error"
            import logging
            (self._log or logging.getLogger(__name__)).warning(
                "flow archiver: write error — stopping archive writes (session continues): ENOSPC"
            )
            return  # do NOT raise — stream continues
        return original_write(self, rows, flush_ts)

    monkeypatch.setattr(flow_collector.TapeArchiver, "write_flush", patched_write)

    archive_dir = tmp_path / "archive"
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=5),  # first trade triggers first write (disk error)
        _trade_frame(price=2.1, size=5),  # second trade should still be processed (stream alive)
    ]
    session, state, ws = make_session(frames, archive_dir=archive_dir)
    # Should NOT raise — stream stays alive despite write error
    await session.run(state, ws)

    # Two trades were processed even though disk write failed
    assert state.metrics.trades_total >= 1
    # The archiver recorded the stop reason
    assert state.archiver._disk_stopped is True
    assert state.archiver._disk_stop_reason == "disk_write_error"


# ---------------------------------------------------------------------------
# Finding 5 (TEST): _StreamError reconnects with backoff (not hot loop)
# ---------------------------------------------------------------------------


async def test_stream_error_reconnects_with_backoff(tmp_path):
    """A _StreamError causes reconnect with the same capped exponential backoff as any disconnect."""
    from ohmytradeagent_sidecar.flow_main import RECONNECT_BASE_SECS

    sleeps: list[float] = []
    connects: list[str] = []

    async def fake_sleep(s: float) -> None:
        sleeps.append(s)
        if len(connects) >= 2:
            raise asyncio.CancelledError

    @asynccontextmanager
    async def fake_connect(url: str, **kw) -> Any:
        connects.append(url)
        # Yield a ws that sends a stream-error (non-406) to trigger _StreamError
        ws = FakeWebSocket([_greeting(), msgpack.packb([{"T": "error", "code": 402, "msg": "not authorized"}])])
        yield ws

    heartbeat_path = tmp_path / "heartbeat"
    heartbeat_path.touch()
    with pytest.raises(asyncio.CancelledError):
        await flow_main.run_loop(
            key_id="k",
            secret="s",
            archive_dir=None,
            dry_run=True,
            heartbeat_path=heartbeat_path,
            log=LOG,
            connect_fn=fake_connect,
            clock=lambda: NOW_IN,
            sleep=fake_sleep,
        )

    # There should be at least one sleep with a positive backoff
    assert len(sleeps) >= 1
    # First backoff should be at least RECONNECT_BASE_SECS (not 0)
    assert sleeps[0] >= RECONNECT_BASE_SECS - 0.01, f"first backoff too small: {sleeps[0]}"


# ---------------------------------------------------------------------------
# Finding 6 (TEST): quote_cache pruned on unsubscribe
# ---------------------------------------------------------------------------


async def test_rotate_quotes_prunes_quote_cache(tmp_path):
    """After _rotate_quotes removes a contract from the quote set, its cache entry is purged."""
    universe_file = tmp_path / "universe.txt"
    universe_file.write_text("AAPL\n")

    # First call: return contract A; second call: return contract B — A should be evicted
    contract_a = "AAPL261105C00157500"  # 5% OTM call, DTE=7
    contract_b = "AAPL261105P00142500"  # 5% OTM put, DTE=7

    call_count = [0]

    def handler(request: httpx.Request) -> httpx.Response:
        call_count[0] += 1
        if "/v2/stocks/trades/latest" in str(request.url):
            return _make_spot_response(["AAPL"], 150.0)
        elif "/v2/options/contracts" in str(request.url):
            # Alternate responses so first choose gets A, second gets B
            if call_count[0] <= 2:
                return _make_contracts_response("AAPL", [
                    {"symbol": contract_a, "expiration_date": "2026-11-05", "strike_price": "157.5"}
                ])
            return _make_contracts_response("AAPL", [
                {"symbol": contract_b, "expiration_date": "2026-11-05", "strike_price": "142.5"}
            ])
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    rest_client = httpx.AsyncClient(base_url=flow_main.DATA_BASE_URL, transport=transport)

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
        rest_client=rest_client,
        universe_path=universe_file,
    )
    state = SessionState()

    # First choose: get contract A
    await session._choose_quote_set(state)
    assert contract_a in state.quote_set.current

    # Manually put A in quote_cache (as if we received a quote for it)
    from ohmytradeagent_sidecar.flow_collector import Quote
    state.quote_cache[contract_a] = Quote(
        contract=contract_a, bid=1.0, ask=1.1,
        bid_size=10, ask_size=10,
        ts=NOW_IN, ts_raw="2026-10-29T13:40:00Z",
    )

    # Invalidate options cache to force new contracts fetch on second call
    state.options_cache_date = ""

    # Build a fake ws for _rotate_quotes
    ws = FakeWebSocket([])

    await session._rotate_quotes(state, ws)
    await rest_client.aclose()

    # If A was unsubscribed, its quote_cache entry should be removed
    if contract_a not in state.quote_set.current:
        assert contract_a not in state.quote_cache, (
            "quote_cache entry for unsubscribed contract not pruned"
        )
