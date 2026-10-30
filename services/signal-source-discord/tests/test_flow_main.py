"""Loop / session tests for the flow collector (PLAN-2026-10-29-flow-page, Step 1).

The WebSocket is a ``FakeWebSocket`` that yields pre-loaded msgpack frames; no
real network connection is made.  ``httpx.MockTransport`` is not yet needed in
Step 1 (the ingest client is wired in Step 2).
"""

from __future__ import annotations

import asyncio
import logging
import pathlib
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any

import msgpack
import pytest

from ohmytradeagent_sidecar import flow_main
from ohmytradeagent_sidecar.flow_main import (
    SLOT_HELD_BACKOFF_SECS,
    FakeWebSocket,
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
) -> tuple[FlowSession, SessionState, FakeWebSocket]:
    state = SessionState()
    if archive_dir:
        from ohmytradeagent_sidecar.flow_collector import TapeArchiver
        state.archiver = TapeArchiver(archive_dir)

    beats: list[int] = []

    def heartbeat() -> None:
        beats.append(1)

    if clock is None:
        # In-window clock that advances each call
        _now = [NOW_IN]

        def clock() -> datetime:
            return _now[0]

    session = FlowSession(
        key_id="k",
        secret="s",
        heartbeat_fn=heartbeat,
        archive_dir=archive_dir,
        dry_run=dry_run,
        log=LOG,
        connect_fn=None,
        clock=clock,
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
        archive_dir=None,
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
