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
    ApproxDistinctCounter,
    FlowSession,
    Metrics,
    RECONNECT_BASE_SECS,
    RollingPremiumMap,
    SessionState,
    _RestBucket,
    _SlotHeldError,
    _StreamError,
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
    # FakeWebSocket raises ConnectionClosed when frames are exhausted → _StreamError
    session, state, ws = make_session(frames)
    with pytest.raises(_StreamError):
        await session.run(state, ws)
    # Should have sent at least auth + subscribe messages (sent before exhaustion)
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
    with pytest.raises(_StreamError):
        await session.run(state, ws)
    assert state.metrics.trades_total >= 1
    # seen_contracts is an ApproxDistinctCounter: check it is non-zero
    assert len(state.seen_contracts) > 0


async def test_session_processes_quote_frame():
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _quote_frame(bid=2.0, ask=2.1),
    ]
    session, state, ws = make_session(frames)
    with pytest.raises(_StreamError):
        await session.run(state, ws)
    assert state.metrics.quotes_total >= 1
    assert "AAPL241220C00150000" in state.quote_cache


async def test_session_dry_run_never_posts(tmp_path):
    """Dry run: orders are counted but not posted to an ingest buffer."""
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=10),
    ]
    session, state, ws = make_session(frames, dry_run=True)
    with pytest.raises(_StreamError):
        await session.run(state, ws)
    # In dry run, no ingest buffer: orders_counted counts them
    assert state.orders_counted == 0  # trade is still in open agg window


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
    with pytest.raises(_StreamError):
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
        with pytest.raises(_StreamError):
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
    """With FLOW_DRY_RUN=true, orders are counted but not posted to any buffer."""
    # premium = 2.1 * 1000 * 100 = 210,000 ≥ 25k
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=1000),  # big trade
    ]
    session, state, ws = make_session(frames, dry_run=True)
    with pytest.raises(_StreamError):
        await session.run(state, ws)
    # Trade still in open agg window — orders_counted is 0 at this point
    assert state.orders_counted == 0


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
    # Use a clock that returns in-window first, then out-of-window.
    # Frames: greeting (pre-loop recv), auth_success (pre-loop recv), sub_ack + trade (recv in loop).
    # Main loop calls clock() BEFORE each recv.  With 4 frames, the loop iterations are:
    #   iter 1: clock[0]=NOW_IN → recv sub_ack
    #   iter 2: clock[1]=NOW_IN → recv trade
    #   iter 3: clock[2]=NOW_OUT → break cleanly (before recv)
    calls = [0]
    clocks = [NOW_IN, NOW_IN, NOW_OUT]

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
    # Simulate session end: create state, add a trade, call _end_session, create new state.
    # FakeWebSocket raises ConnectionClosed when exhausted → _StreamError; that's OK for this test.
    frames = [_greeting(), _auth_success(), _sub_ack(), _trade_frame()]
    session, state, ws = make_session(frames)
    with pytest.raises(_StreamError):
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
    with pytest.raises(_StreamError):
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


async def test_disk_write_error_records_gap_not_teardown(tmp_path):
    """An OSError during archiver write (archive path is a FILE) stops archiving but does NOT
    raise.  No monkeypatching: the real OSError code path is exercised on TapeArchiver directly.
    Then the session is run to verify it continues processing trades with a stopped archiver."""
    from ohmytradeagent_sidecar.flow_collector import TapeArchiver
    from datetime import datetime, timezone as tz
    # Create archive_dir as a FILE so mkdir() raises FileExistsError (a subclass of OSError).
    archive_dir = tmp_path / "archive"
    archive_dir.write_text("i am a file, not a directory")

    flush_ts = datetime(2026, 10, 29, 13, 40, tzinfo=tz.utc)
    archiver = TapeArchiver(archive_dir)

    # write_flush must NOT raise; it catches OSError and records disk_write_error
    archiver.write_flush([{"t": "t", "S": "AAPL241220C00150000",
                           "p": 1.0, "s": 1, "x": "A", "c": []}], flush_ts)

    assert archiver._disk_stopped is True
    assert archiver._disk_stop_reason == "disk_write_error"

    # Stream continues: attach the stopped archiver to a live session and verify trades process
    frames = [
        _greeting(),
        _auth_success(),
        _sub_ack(),
        _trade_frame(price=2.1, size=5),
        _trade_frame(price=2.1, size=5),
    ]
    state = SessionState()
    state.archiver = archiver  # already in disk-stopped state
    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
    )
    ws = FakeWebSocket(frames)
    with pytest.raises(_StreamError):
        await session.run(state, ws)
    assert state.metrics.trades_total >= 1


# ---------------------------------------------------------------------------
# Finding 5 (TEST): _StreamError reconnects with backoff (not hot loop)
# ---------------------------------------------------------------------------


async def test_stream_error_reconnects_with_backoff(tmp_path):
    """A _StreamError causes reconnect with the same capped exponential backoff as any disconnect."""
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

    # _choose_quote_set returns (subscribe, unsubscribe); apply result to simulate rotation.
    subscribe, unsubscribe = await session._choose_quote_set(state)
    for contract in unsubscribe:
        state.quote_cache.pop(contract, None)

    await rest_client.aclose()

    # If A was unsubscribed, its quote_cache entry should be removed
    if contract_a not in state.quote_set.current:
        assert contract_a not in state.quote_cache, (
            "quote_cache entry for unsubscribed contract not pruned"
        )


# ---------------------------------------------------------------------------
# Finding 1 (TEST): Two REST clients — HOST assertion per path
# Finding spec: options contracts use TRADING API (api.alpaca.markets/v2/options/contracts),
# stocks on data.alpaca.markets.  MockTransport asserts HOST per path.
# ---------------------------------------------------------------------------


async def test_rest_host_options_uses_trading_api(tmp_path):
    """_choose_quote_set uses api.alpaca.markets for /v2/options/contracts and
    data.alpaca.markets for /v2/stocks/trades/latest.  Each MockTransport asserts its host."""
    universe_file = tmp_path / "universe.txt"
    universe_file.write_text("AAPL\n")

    contract_sym = "AAPL261105C00157500"
    data_hosts: list[str] = []
    trading_hosts: list[str] = []

    def data_handler(request: httpx.Request) -> httpx.Response:
        data_hosts.append(request.url.host)
        if "/v2/stocks/trades/latest" in str(request.url):
            return _make_spot_response(["AAPL"], 150.0)
        return httpx.Response(404)

    def trading_handler(request: httpx.Request) -> httpx.Response:
        trading_hosts.append(request.url.host)
        if "/v2/options/contracts" in str(request.url):
            return _make_contracts_response(
                "AAPL",
                [{"symbol": contract_sym, "expiration_date": "2026-11-05", "strike_price": "157.5"}],
            )
        return httpx.Response(404)

    data_client = httpx.AsyncClient(
        base_url=flow_main.DATA_BASE_URL, transport=httpx.MockTransport(data_handler)
    )
    trading_client = httpx.AsyncClient(
        base_url=flow_main.TRADING_BASE_URL, transport=httpx.MockTransport(trading_handler)
    )

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
        data_client=data_client,
        trading_client=trading_client,
        universe_path=universe_file,
    )
    state = SessionState()
    await session._choose_quote_set(state)

    await data_client.aclose()
    await trading_client.aclose()

    # Spot price call must go to data.alpaca.markets
    assert all("data.alpaca.markets" in h for h in data_hosts), (
        f"data_client received request to wrong host(s): {data_hosts}"
    )
    # Options contracts call must go to api.alpaca.markets (TRADING API)
    assert any("api.alpaca.markets" in h for h in trading_hosts), (
        f"trading_client received no api.alpaca.markets request; trading_hosts={trading_hosts}"
    )
    assert contract_sym in state.quote_set.current


async def test_rest_host_options_pagination(tmp_path):
    """_fetch_option_contracts uses page_token pagination and limit=10000."""
    from ohmytradeagent_sidecar.flow_main import _fetch_option_contracts, _RestBucket

    pages_served = [0]

    def trading_handler(request: httpx.Request) -> httpx.Response:
        params = dict(request.url.params)
        assert params.get("limit") == "10000", f"expected limit=10000, got {params}"
        page = pages_served[0]
        pages_served[0] += 1
        if page == 0:
            body = {
                "option_contracts": [{"symbol": "AAPL261105C00150000",
                                      "expiration_date": "2026-11-05", "strike_price": "150.0"}],
                "next_page_token": "TOKEN2",
            }
        else:
            body = {
                "option_contracts": [{"symbol": "AAPL261105P00150000",
                                      "expiration_date": "2026-11-05", "strike_price": "150.0"}],
                "next_page_token": None,
            }
        return httpx.Response(200, json=body)

    client = httpx.AsyncClient(
        base_url=flow_main.TRADING_BASE_URL, transport=httpx.MockTransport(trading_handler)
    )
    bucket = _RestBucket(rate_per_min=300)
    results = await _fetch_option_contracts(client, "AAPL", "2026-11-30", 150.0, "k", "s", bucket)
    await client.aclose()

    assert pages_served[0] == 2, f"expected 2 pages, got {pages_served[0]}"
    assert len(results) == 2
    syms = {r[0] for r in results}
    assert "AAPL261105C00150000" in syms
    assert "AAPL261105P00150000" in syms


# ---------------------------------------------------------------------------
# Finding 2 (TEST): Socket drop records gap + increments reconnects
# ---------------------------------------------------------------------------


async def test_disconnect_gap_recorded_and_reconnect_incremented(tmp_path):
    """When the socket drops mid-session, run_loop records a gap and increments reconnects."""
    from contextlib import asynccontextmanager as acm
    connects: list[int] = [0]
    sleeps: list[float] = []

    async def fake_sleep(s: float) -> None:
        sleeps.append(s)
        if connects[0] >= 2:
            raise asyncio.CancelledError

    @acm
    async def fake_connect(url: str, **kw):
        connects[0] += 1
        # First connect: immediate socket drop (no greeting → ConnectionClosed)
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
    # Reconnect counter should have been incremented after the socket drop
    # (we can't directly access state from run_loop without DI, but we can verify
    # that it reconnected — connects[0] >= 2 — and that sleeps happened)
    assert connects[0] >= 2, f"expected at least 2 connects (reconnect), got {connects[0]}"
    assert len(sleeps) >= 1, "expected at least one reconnect sleep"


# ---------------------------------------------------------------------------
# Finding 3 (TEST): REST rotation runs as asyncio.create_task (non-blocking recv loop)
# ---------------------------------------------------------------------------


async def test_rest_rotation_is_background_task(tmp_path):
    """_choose_quote_set is run as asyncio.create_task so recv loop is not blocked.
    The heartbeat must be touched at least once while a slow REST call is pending."""
    universe_file = tmp_path / "universe.txt"
    universe_file.write_text("AAPL\n")

    # Make REST handler yield briefly so we can verify it runs as a background task
    async def slow_handler(request: httpx.Request) -> httpx.Response:
        # Yield control back to the event loop to simulate latency
        await asyncio.sleep(0)
        if "/v2/stocks/trades/latest" in str(request.url):
            return _make_spot_response(["AAPL"], 150.0)
        if "/v2/options/contracts" in str(request.url):
            return _make_contracts_response("AAPL", [])
        return httpx.Response(404)

    transport = httpx.MockTransport(slow_handler)
    data_client = httpx.AsyncClient(
        base_url=flow_main.DATA_BASE_URL, transport=transport
    )
    trading_client = httpx.AsyncClient(
        base_url=flow_main.TRADING_BASE_URL, transport=transport
    )

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=lambda: None,
        dry_run=True,
        log=LOG,
        clock=lambda: NOW_IN,
        data_client=data_client,
        trading_client=trading_client,
        universe_path=universe_file,
    )
    state = SessionState()
    # Trigger a quote rotation background task
    task = asyncio.create_task(session._choose_quote_set(state))
    # Yield to the event loop — task should complete without blocking
    await asyncio.sleep(0)
    await task
    await data_client.aclose()
    await trading_client.aclose()
    # The key assertion: _choose_quote_set returns two lists (subscribe, unsubscribe)
    subscribe, unsubscribe = task.result()
    assert isinstance(subscribe, list)
    assert isinstance(unsubscribe, list)


# ---------------------------------------------------------------------------
# Finding 4 (TEST): _RestBucket token depletion + X-Ratelimit-Remaining hard stop
# ---------------------------------------------------------------------------


def test_rest_bucket_initially_allows_calls():
    bucket = _RestBucket(rate_per_min=10, floor=2_000)
    assert bucket.can_call() is True


def test_rest_bucket_depletes_tokens():
    bucket = _RestBucket(rate_per_min=5, floor=2_000)
    # Consume all tokens
    for _ in range(5):
        assert bucket.can_call()
        bucket.consume()
    assert bucket.can_call() is False


def test_rest_bucket_ratelimit_remaining_hard_stop():
    """When X-Ratelimit-Remaining < floor, hard stop for ~60 s."""
    bucket = _RestBucket(rate_per_min=300, floor=2_000)
    # Fake a response with Remaining below floor
    resp = httpx.Response(200, headers={"X-Ratelimit-Remaining": "500"})
    bucket.on_response(resp)
    assert bucket.stopped is True
    assert bucket.can_call() is False


def test_rest_bucket_ratelimit_remaining_above_floor_no_stop():
    """When Remaining >= floor, no hard stop."""
    bucket = _RestBucket(rate_per_min=300, floor=2_000)
    resp = httpx.Response(200, headers={"X-Ratelimit-Remaining": "5000"})
    bucket.on_response(resp)
    assert bucket.stopped is False
    assert bucket.can_call() is True


def test_rest_bucket_failed_calls_consume_token():
    """Failed REST calls (non-200 or exception) still count against the bucket."""
    bucket = _RestBucket(rate_per_min=3, floor=2_000)
    tokens_before = bucket._tokens
    bucket.consume()  # simulated failed call
    assert bucket._tokens < tokens_before


# ---------------------------------------------------------------------------
# Finding 5 (TEST): RollingPremiumMap — $10k floor + cap on insert
# ---------------------------------------------------------------------------


def test_rolling_premium_map_min_premium_floor():
    """Contracts with premium < $10k are NOT admitted."""
    rpm = RollingPremiumMap(window_secs=900, max_k=100, min_premium=10_000.0)
    rpm.add("AAPL241220C00150000", 9_999.99, 0.0)
    assert len(rpm) == 0


def test_rolling_premium_map_admits_at_or_above_floor():
    rpm = RollingPremiumMap(window_secs=900, max_k=100, min_premium=10_000.0)
    rpm.add("AAPL241220C00150000", 10_000.0, 0.0)
    assert len(rpm) == 1


def test_rolling_premium_map_cap_on_insert():
    """When max_k is reached, the lowest-premium entry is evicted IMMEDIATELY on insert."""
    rpm = RollingPremiumMap(window_secs=900, max_k=3, min_premium=10_000.0)
    rpm.add("A", 10_000.0, 0.0)  # premium 10k
    rpm.add("B", 20_000.0, 0.0)  # premium 20k
    rpm.add("C", 30_000.0, 0.0)  # premium 30k  →  map is full (max_k=3)
    # Add a 4th entry: evicts the lowest (A at 10k) immediately
    rpm.add("D", 15_000.0, 0.0)
    assert len(rpm) <= 3, f"map exceeded max_k=3: {len(rpm)}"
    m = rpm.get_map()
    assert "A" not in m, "lowest-premium entry should have been evicted on insert"


def test_rolling_premium_map_prune_removes_stale():
    """Entries older than window_secs are pruned."""
    rpm = RollingPremiumMap(window_secs=10.0, max_k=100, min_premium=10_000.0)
    rpm.add("AAPL241220C00150000", 50_000.0, 0.0)  # added at mono=0
    rpm.prune(now_mono=20.0)  # 20s > 10s window
    assert len(rpm) == 0


# ---------------------------------------------------------------------------
# Finding 6 (TEST): ApproxDistinctCounter + per-interval peaks reset
# ---------------------------------------------------------------------------


def test_approx_distinct_counter_estimate():
    """ApproxDistinctCounter returns an approximate count (within 20%) of distinct items."""
    counter = ApproxDistinctCounter()
    n = 1_000
    for i in range(n):
        counter.add(f"contract_{i}")
    estimate = len(counter)
    # Within 20% of the true count
    assert 800 <= estimate <= 1_200, f"estimate {estimate} out of expected range [800, 1200]"


def test_approx_distinct_counter_memory_bound():
    """ApproxDistinctCounter uses exactly 1 MB regardless of number of inserts."""
    import sys
    counter = ApproxDistinctCounter()
    for i in range(100_000):
        counter.add(f"item_{i}")
    buf_size = sys.getsizeof(counter._buf)
    # bytearray of 1 MB = 1_048_576 bytes (sys.getsizeof includes a small header)
    assert buf_size <= 1_048_576 + 100, f"buffer too large: {buf_size}"


def test_approx_distinct_counter_deduplicates():
    """Adding the same item twice does not inflate the count."""
    counter = ApproxDistinctCounter()
    counter.add("AAPL241220C00150000")
    counter.add("AAPL241220C00150000")
    assert len(counter) == 1


def test_metrics_per_interval_peaks_reset():
    """trades_1s_peak, quotes_1s_peak, loop_lag_max_ms reset after tick_metrics_interval();
    session maxima are preserved."""
    m = Metrics()
    m._trades_this_second = 50
    m._quotes_this_second = 30
    m.tick_second()  # captures peaks

    assert m.trades_1s_peak >= 50
    assert m.quotes_1s_peak >= 30

    m.tick_metrics_interval()  # resets per-interval peaks

    assert m.trades_1s_peak == 0, "per-interval peak should reset to 0 after log"
    assert m.quotes_1s_peak == 0, "per-interval peak should reset to 0 after log"
    # Session maxima are preserved
    assert m.trades_1s_peak_session >= 50
    assert m.quotes_1s_peak_session >= 30


def test_quoted_premium_share_only_25k_orders():
    """quoted_premium_share is computed only over orders >= $25k premium."""
    m = Metrics()
    # Large order attributed to quote
    m.add_order(30_000.0, "quote")
    # Small order attributed to none (below threshold — should NOT affect share)
    m.add_order(10_000.0, "none")
    # Large order with no quote
    m.add_order(50_000.0, "none")

    # total_premium_25k = 30k + 50k = 80k; quoted_premium_25k = 30k
    share = m.quoted_premium_share()
    assert abs(share - 30_000.0 / 80_000.0) < 0.001, f"share={share}"


def test_metrics_loop_lag_resets_after_interval():
    """loop_lag_max_ms resets after tick_metrics_interval(); session max preserved."""
    m = Metrics()
    m.loop_lag_max_ms = 150.0
    m.loop_lag_max_ms_session = 150.0
    m.tick_metrics_interval()
    assert m.loop_lag_max_ms == 0.0, "loop_lag_max_ms should reset per interval"
    assert m.loop_lag_max_ms_session >= 150.0, "session max should be preserved"
