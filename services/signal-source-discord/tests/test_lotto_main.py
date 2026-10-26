"""Lotto poller tests (PLAN-2026-10-26-copytrade-lotto-v1, phase 4).

The BFF read endpoint (phase 3) is mocked with ``httpx.MockTransport`` behind a small stateful
fake that honours ``after``/``limit`` paging, the ingest token and the channel allow-list. Emits go
to ``InMemoryEmitter``; nothing here dials Temporal.
"""

from __future__ import annotations

import json
import logging
import pathlib
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx
import pytest
from test_lotto_parser import AUTHOR, load_messages

from ohmytradeagent_sidecar import lotto_main
from ohmytradeagent_sidecar.emitter import InMemoryEmitter, workflow_id_for
from ohmytradeagent_sidecar.lotto_emit import (
    ChatRow,
    LottoPoller,
    parse_cutoff_et,
    parse_posted_at,
    snowflake_at,
)
from ohmytradeagent_sidecar.lotto_main import BffMessagesClient
from ohmytradeagent_sidecar.lotto_parser import LottoBook

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "lotto"
TOKEN = "ingest-token"
CHANNEL = "880503518878892143"
TENANT = "paper_lotto"
STRATEGY = "copytrade-lotto-v1"
LOG = logging.getLogger("test.lotto")

T0 = datetime(2026, 9, 4, 18, 43, tzinfo=timezone.utc)  # 14:43 ET on a Friday


class FakeBff:
    """Stateful stand-in for ``GET /internal/options-chat/messages``."""

    def __init__(self) -> None:
        self.rows: list[dict] = []
        self.requests: list[httpx.Request] = []
        self.fail_next: list = []  # status ints or exceptions, consumed in order

    def add(self, row: dict) -> dict:
        self.rows.append(row)
        return row

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.fail_next:
            nxt = self.fail_next.pop(0)
            if isinstance(nxt, Exception):
                raise nxt
            return httpx.Response(nxt, json={"error": "injected"})
        assert request.url.path == "/internal/options-chat/messages"
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return httpx.Response(401, json={"error": "unauthorized"})
        params = request.url.params
        if params.get("channel") != CHANNEL:
            return httpx.Response(400, json={"error": "unknown channel"})
        after = int(params.get("after", "0"))
        limit = int(params.get("limit", "200"))
        assert 1 <= limit <= 200
        items = sorted(
            (r for r in self.rows if int(r["message_id"]) > after),
            key=lambda r: int(r["message_id"]),
        )[:limit]
        return httpx.Response(
            200, json={"channel_id": CHANNEL, "count": len(items), "items": items}
        )

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def row(
    posted_at: datetime,
    content: str,
    *,
    seq: int = 0,
    author: str = AUTHOR,
    reply_to_id: str | None = None,
    message_id: str | None = None,
) -> dict:
    return {
        "message_id": message_id or str(snowflake_at(posted_at) + seq),
        "author_name": author,
        "author_color": None,
        "author_avatar_url": None,
        "posted_at": _iso(posted_at),
        "content": content,
        "reply_to_id": reply_to_id,
        "edited": False,
        "deleted": False,
    }


class Clock:
    def __init__(self, now: datetime) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now


def make_poller(
    bff: FakeBff,
    clock: Clock,
    *,
    emit_max_age_secs: float = 120,
    dry_run: bool = True,
    emitter: InMemoryEmitter | None = None,
) -> tuple[LottoPoller, InMemoryEmitter]:
    client = BffMessagesClient(
        base_url="http://bff:8083",
        token=TOKEN,
        log=LOG,
        client=httpx.AsyncClient(transport=bff.transport(), base_url="http://bff:8083"),
    )
    emitter = emitter or InMemoryEmitter()
    poller = LottoPoller(
        source=client,
        emitter=emitter,
        book=LottoBook(author=AUTHOR),
        channel_id=CHANNEL,
        author=AUTHOR,
        tenant_id=TENANT,
        strategy_id=STRATEGY,
        lookback_days=14,
        emit_max_age_secs=emit_max_age_secs,
        dry_run=dry_run,
        log=LOG,
        clock=clock,
    )
    return poller, emitter


def _shape(payloads) -> list[tuple[str, str, str]]:
    return [(p.action.value, p.ticker, p.signal_id) for p in payloads]


# ---------------------------------------------------------------------------
# Boot / emit behaviour
# ---------------------------------------------------------------------------


async def test_boot_rebuild_emits_nothing():
    """Stale rows (older than LOTTO_EMIT_MAX_AGE_SECS) rebuild the book and emit nothing."""
    bff = FakeBff()
    clock = Clock(T0)
    bff.add(row(T0 - timedelta(hours=2), "SPY 9/4 650p @ .70"))
    bff.add(row(T0 - timedelta(hours=1), ".85 taking some"))
    poller, emitter = make_poller(bff, clock)

    assert await poller.boot() is True
    assert emitter.emitted == []
    # The boot read starts at the lookback snowflake, not at 0.
    assert bff.requests[0].url.params["after"] == str(snowflake_at(T0 - timedelta(days=14)))

    # State WAS rebuilt: a fresh exit on the booted contract emits an STC.
    bff.add(row(T0 - timedelta(seconds=5), "dumped the rest at .20"))
    emitted = await poller.poll_once()
    assert [(p.action.value, p.ticker, float(p.price)) for p in emitted] == [("STC", "SPY", 0.2)]


async def test_boot_emits_rows_still_inside_the_age_window():
    """An EXIT posted during a pod-restart window must still close the position: boot emits
    the rows younger than LOTTO_EMIT_MAX_AGE_SECS, and only those."""
    bff = FakeBff()
    clock = Clock(T0)
    bff.add(row(T0 - timedelta(hours=2), "SPY 9/4 650p @ .70"))  # stale: state only
    fresh = bff.add(row(T0 - timedelta(seconds=30), "dumped the rest at .20"))  # inside window
    poller, emitter = make_poller(bff, clock, emit_max_age_secs=120)

    assert await poller.boot() is True
    assert [(p.action.value, p.ticker, p.signal_id) for p in emitter.emitted] == [
        ("STC", "SPY", f"{fresh['message_id']}:0")
    ]
    # Polling after boot does not emit it a second time (cursor moved past it).
    assert await poller.poll_once() == []


async def test_new_entry_then_exit_emits_bto_then_stc_same_contract():
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()

    entry = bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90 risky"))
    assert _shape(await poller.poll_once()) == [("BTO", "MU", f"{entry['message_id']}:0")]

    clock.now = T0 + timedelta(minutes=10)
    exit_ = bff.add(row(clock.now - timedelta(seconds=3), "2.35 taking some"))
    assert _shape(await poller.poll_once()) == [("STC", "MU", f"{exit_['message_id']}:0")]

    bto, stc = emitter.emitted
    assert (bto.ticker, bto.expiry, bto.strike, bto.right) == (
        stc.ticker,
        stc.expiry,
        stc.strike,
        stc.right,
    )
    assert (float(bto.price), float(stc.price)) == (1.90, 2.35)
    assert bto.tenant_id == stc.tenant_id == TENANT
    assert bto.strategy_id == STRATEGY
    assert bto.author == AUTHOR
    assert bto.message_id == entry["message_id"]
    assert bto.posted_at == parse_posted_at(entry["posted_at"])
    assert bto.tail == bto.raw_line == "MU 9/9 1050c @ 1.90 risky"


async def test_every_exit_is_emitted():
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=9), "MU 9/9 1050c @ 1.90"))
    bff.add(row(T0 - timedelta(seconds=6), "2.35 taking some"))
    bff.add(row(T0 - timedelta(seconds=3), "stop hit on the rest"))
    emitted = await poller.poll_once()
    assert [p.action.value for p in emitted] == ["BTO", "STC", "STC"]
    # F2: a priceless exit trades at the author's last posted price.
    assert float(emitted[2].price) == 2.35


async def test_other_author_rows_never_emit():
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=9), "MU 9/9 1050c @ 1.90", author="Edtrader"))
    bff.add(row(T0 - timedelta(seconds=6), "2.35 taking some", author="Edtrader"))
    # TTT's own exit has nothing open because the other author's entry never reached the book.
    bff.add(row(T0 - timedelta(seconds=3), "2.35 taking some"))
    assert await poller.poll_once() == []
    assert emitter.emitted == []


async def test_stale_signal_updates_state_not_emitted(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock, emit_max_age_secs=120)
    await poller.boot()

    bff.add(row(T0 - timedelta(minutes=10), "MU 9/9 1050c @ 1.90"))
    with caplog.at_level("INFO", logger="test.lotto"):
        assert await poller.poll_once() == []
    assert any("stale" in r.message for r in caplog.records)
    assert emitter.emitted == []

    bff.add(row(T0 - timedelta(seconds=30), "2.35 taking some"))
    emitted = await poller.poll_once()
    assert [(p.action.value, p.ticker) for p in emitted] == [("STC", "MU")]


async def test_restart_does_not_reemit():
    """A restart re-reads rows the previous process already emitted. Stale ones are filtered by
    age; the ones still inside the window are re-sent and Temporal's REJECT_DUPLICATE (modelled
    by the shared InMemoryEmitter) turns them into dedupes, so no second workflow starts."""
    bff = FakeBff()
    clock = Clock(T0)
    temporal = InMemoryEmitter()  # survives the "restart", like the Temporal cluster does
    first, _ = make_poller(bff, clock, emitter=temporal)
    await first.boot()
    bff.add(row(T0 - timedelta(minutes=10), "MU 9/9 1050c @ 1.90"))  # will be stale on restart
    clock.now = T0 - timedelta(minutes=10) + timedelta(seconds=5)
    assert [p.action.value for p in await first.poll_once()] == ["BTO"]
    bff.add(row(T0 - timedelta(seconds=3), "2.35 taking some"))  # still fresh on restart
    clock.now = T0
    assert [p.action.value for p in await first.poll_once()] == ["STC"]
    assert len(temporal.emitted) == 2

    # Same clock, same stored rows: the restarted pod rebuilds state; the stale BTO is state-only,
    # the fresh STC is re-sent and deduped. Nothing new reaches Temporal.
    second, _ = make_poller(bff, clock, emitter=temporal)
    assert await second.boot() is True
    assert await second.poll_once() == []
    assert len(temporal.emitted) == 2

    # And it is live: the next fresh post emits.
    bff.add(row(T0 - timedelta(seconds=1), "stop hit on the rest"))
    assert [p.action.value for p in await second.poll_once()] == ["STC"]
    assert len(temporal.emitted) == 3


async def test_bff_5xx_keeps_cursor(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    cursor = poller.cursor

    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    bff.fail_next = [503]
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await poller.poll_once() == []
    assert poller.cursor == cursor
    assert any(r.levelno == logging.WARNING and "503" in r.message for r in caplog.records)

    bff.fail_next = [httpx.ReadTimeout("slow")]
    assert await poller.poll_once() == []
    assert poller.cursor == cursor

    # The retry picks the row up.
    assert [p.action.value for p in await poller.poll_once()] == ["BTO"]
    assert poller.cursor > cursor


async def test_bad_token_and_unknown_channel_log_error_and_keep_polling(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    poller, _ = make_poller(bff, clock)
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    for status in (401, 400, 404):
        bff.fail_next = [status]
        with caplog.at_level("WARNING", logger="test.lotto"):
            assert await poller.poll_once() == []
    assert any(r.levelno == logging.ERROR and "401" in r.message for r in caplog.records)
    assert any(r.levelno == logging.ERROR and "400" in r.message for r in caplog.records)
    assert any(r.levelno == logging.WARNING and "404" in r.message for r in caplog.records)
    assert [p.action.value for p in await poller.poll_once()] == ["BTO"]


async def test_repeated_same_failure_is_throttled(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    poller, _ = make_poller(bff, clock)
    await poller.boot()
    with caplog.at_level("WARNING", logger="test.lotto"):
        for _ in range(10):
            bff.fail_next = [404]
            await poller.poll_once()
    dark = [r for r in caplog.records if "404" in r.message]
    assert [r.message.rsplit("=", 1)[1].rstrip(")") for r in dark] == ["1", "3"]


async def test_boot_retries_until_bff_answers():
    bff = FakeBff()
    clock = Clock(T0)
    poller, _ = make_poller(bff, clock)
    bff.fail_next = [502]
    assert await poller.boot() is False
    assert await poller.boot() is True


# ---------------------------------------------------------------------------
# Dry run / ids
# ---------------------------------------------------------------------------


async def test_dry_run_never_touches_temporal(monkeypatch, caplog):
    async def _boom(*a, **k):  # pragma: no cover - the assertion is that this never runs
        raise AssertionError("TemporalEmitter.connect called in dry-run")

    monkeypatch.setattr(lotto_main.TemporalEmitter, "connect", _boom)
    emitter = await lotto_main.build_emitter(
        dry_run=True, target="x:1", namespace="n", task_queue="q", log=LOG
    )
    assert isinstance(emitter, InMemoryEmitter)

    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock, dry_run=True)
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    with caplog.at_level("INFO", logger="test.lotto"):
        await poller.poll_once()
    assert any(r.message.startswith("dry-run would-emit") for r in caplog.records)
    assert len(emitter.emitted) == 1


@pytest.mark.parametrize(
    "raw,expected", [("true", True), ("TRUE", True), ("", True), ("false", False)]
)
def test_dry_run_env_is_fail_safe(raw, expected):
    assert lotto_main.parse_dry_run(raw) is expected


async def test_expiry_day_bto_after_cutoff_not_emitted(caplog):
    """A lot that fills after the 15:30 expiry flatten gets no flatten timer in the orchestrator,
    so an expiry-day entry posted at/after the cutoff (default 15:25 ET) is dropped here. The
    book still records it so the later exit is a no-op on THAT contract, not a mis-attribution."""
    et = ZoneInfo("America/New_York")
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()

    def at(hh: int, mm: int) -> datetime:
        return datetime(2026, 9, 4, hh, mm, tzinfo=et).astimezone(timezone.utc)

    # 0DTE at 15:24 ET → BTO.
    clock.now = at(15, 24) + timedelta(seconds=5)
    bff.add(row(at(15, 24), "QQQ 0dte 500p @ .50"))
    assert [p.action.value for p in await poller.poll_once()] == ["BTO"]

    # 0DTE at 15:26 ET → no BTO, warning logged.
    clock.now = at(15, 26) + timedelta(seconds=5)
    late = bff.add(row(at(15, 26), "SPY 0dte 650p @ .70"))
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await poller.poll_once() == []
    assert any(
        r.message.startswith("skipped_late_expiry_day_entry") and late["message_id"] in r.message
        for r in caplog.records
    )
    # The book knows the contract: the exit attributes to SPY (most recently opened), not to the
    # QQQ lot, and is still emitted — exits are never cut off.
    clock.now = at(15, 27) + timedelta(seconds=5)
    bff.add(row(at(15, 27), ".90 taking some"))
    stc = await poller.poll_once()
    assert [(p.action.value, p.ticker) for p in stc] == [("STC", "SPY")]

    # 1-DTE contract at 15:40 ET → BTO (only same-day expiry is gated).
    clock.now = at(15, 40) + timedelta(seconds=5)
    bff.add(row(at(15, 40), "MU 9/9 1050c @ 1.90"))
    assert [p.action.value for p in await poller.poll_once()] == ["BTO"]
    assert [(p.action.value, p.ticker) for p in emitter.emitted] == [
        ("BTO", "QQQ"),
        ("STC", "SPY"),
        ("BTO", "MU"),
    ]


def test_parse_cutoff_et():
    assert parse_cutoff_et("15:25") == time(15, 25)
    assert parse_cutoff_et(" 9:05 ") == time(9, 5)


async def test_heartbeat_touched_at_startup_and_every_cycle(tmp_path):
    heartbeat = tmp_path / "heartbeat"
    bff = FakeBff()
    seen_at_first_request: list[bool] = []
    real = bff.handler

    def spy(request: httpx.Request) -> httpx.Response:
        if not seen_at_first_request:
            seen_at_first_request.append(heartbeat.exists())
        return real(request)

    client = BffMessagesClient(
        base_url="http://bff:8083",
        token=TOKEN,
        log=LOG,
        client=httpx.AsyncClient(transport=httpx.MockTransport(spy), base_url="http://bff:8083"),
    )
    poller = LottoPoller(
        source=client,
        emitter=InMemoryEmitter(),
        book=LottoBook(author=AUTHOR),
        channel_id=CHANNEL,
        author=AUTHOR,
        tenant_id=TENANT,
        strategy_id=STRATEGY,
        lookback_days=14,
        emit_max_age_secs=120,
        dry_run=True,
        log=LOG,
        clock=Clock(T0),
    )
    # Boot fails twice (BFF erroring), then succeeds; then 3 polls, the middle one a 500.
    bff.fail_next = [502, httpx.ConnectTimeout("down")]
    touches: list[float] = []

    class Stop(Exception):
        pass

    async def fake_sleep(_secs: float) -> None:
        touches.append(heartbeat.stat().st_mtime_ns)
        if len(touches) == 2:
            bff.fail_next = []  # boot succeeds on the next attempt
        if len(touches) == 4:
            bff.fail_next = [500]
        if len(touches) == 6:
            raise Stop

    with pytest.raises(Stop):
        await lotto_main.run_loop(poller, heartbeat, 5.0, sleep=fake_sleep)

    assert seen_at_first_request == [True]  # touched BEFORE the first BFF read returned
    assert len(touches) == 6  # 2 failed boots + 1 ok boot-then-poll... every cycle slept once
    assert all(b >= a for a, b in zip(touches, touches[1:], strict=False))
    assert poller.cursor > 0  # the loop did get past boot


async def test_signal_id_shape_and_workflow_id():
    bff = FakeBff()
    clock = Clock(T0)
    poller, _ = make_poller(bff, clock)
    await poller.boot()
    entry = bff.add(row(T0 - timedelta(seconds=6), "MU 9/04 1015c @ .51"))
    roll = bff.add(row(T0 - timedelta(seconds=3), "rolled these to 1010 @ .44"))
    emitted = await poller.poll_once()
    assert [p.signal_id for p in emitted] == [
        f"{entry['message_id']}:0",
        f"{roll['message_id']}:0",
        f"{roll['message_id']}:1",
    ]
    assert [p.action.value for p in emitted] == ["BTO", "STC", "BTO"]
    assert (
        workflow_id_for(emitted[0])
        == f"t-paper_lotto/s-copytrade-lotto-v1/sig/{entry['message_id']}:0"
    )


# ---------------------------------------------------------------------------
# BFF client
# ---------------------------------------------------------------------------


def _client(bff: FakeBff) -> BffMessagesClient:
    return BffMessagesClient(
        base_url="http://bff:8083",
        token=TOKEN,
        log=LOG,
        client=httpx.AsyncClient(transport=bff.transport(), base_url="http://bff:8083"),
    )


async def test_pages_while_count_equals_limit():
    bff = FakeBff()
    rows = [bff.add(row(T0, f"row {i}", seq=i)) for i in range(5)]
    got = await _client(bff).since(CHANNEL, after_id=0, limit=2)
    assert [r.message_id for r in got] == [r["message_id"] for r in rows]
    afters = [req.url.params["after"] for req in bff.requests]
    assert afters == ["0", rows[1]["message_id"], rows[3]["message_id"]]
    assert all(req.url.params["limit"] == "2" for req in bff.requests)

    # Exactly `limit` rows total: one more (empty) page confirms the end.
    bff.requests.clear()
    got = await _client(bff).since(CHANNEL, after_id=0, limit=5)
    assert len(got) == 5 and len(bff.requests) == 2


async def test_page_failure_mid_way_returns_rows_so_far():
    bff = FakeBff()
    rows = [bff.add(row(T0, f"row {i}", seq=i)) for i in range(4)]

    # Inject the failure on the second request only.
    calls = {"n": 0}
    real = bff.handler

    def flaky(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 2:
            return httpx.Response(500, json={"error": "injected"})
        return real(request)

    client = BffMessagesClient(
        base_url="http://bff:8083",
        token=TOKEN,
        log=LOG,
        client=httpx.AsyncClient(transport=httpx.MockTransport(flaky), base_url="http://bff:8083"),
    )
    got = await client.since(CHANNEL, after_id=0, limit=2)
    assert [r.message_id for r in got] == [r["message_id"] for r in rows[:2]]
    assert await _client(bff).since(CHANNEL, after_id=0, limit=200) is not None


def test_naive_posted_at_rejected():
    assert parse_posted_at("2026-03-10T17:07:00Z") == datetime(
        2026, 3, 10, 17, 7, tzinfo=timezone.utc
    )
    assert parse_posted_at("2026-03-10T17:07:00.123Z").tzinfo is not None
    with pytest.raises(ValueError):
        parse_posted_at("2026-03-10T17:07:00")


async def test_naive_posted_at_row_is_skipped_with_error(caplog):
    bff = FakeBff()
    bad = bff.add(row(T0, "MU 9/9 1050c @ 1.90"))
    bad["posted_at"] = "2026-09-04T18:43:00"
    good = bff.add(row(T0, "2.35 taking some", seq=1))
    with caplog.at_level("ERROR", logger="test.lotto"):
        got = await _client(bff).since(CHANNEL, after_id=0)
    assert [r.message_id for r in got] == [good["message_id"]]
    assert any(bad["message_id"] in r.message for r in caplog.records)


async def test_null_posted_at_falls_back_to_snowflake_time():
    bff = FakeBff()
    r = bff.add(row(T0, "MU 9/9 1050c @ 1.90"))
    r["posted_at"] = None
    got = await _client(bff).since(CHANNEL, after_id=0)
    assert got[0].posted_at == T0


def test_snowflake_roundtrip():
    sf = snowflake_at(T0)
    assert ChatRow.snowflake_time(str(sf)) == T0
    assert snowflake_at(T0 + timedelta(seconds=1)) > sf


# ---------------------------------------------------------------------------
# Replay + fixture end-to-end
# ---------------------------------------------------------------------------


def _fixture_rows() -> list[dict]:
    return [
        row(
            m["posted_at"],
            m["text"],
            message_id=m["id"],
            reply_to_id=m["reply_to_id"],
        )
        for m in load_messages()
    ]


# The latest expiry-day entry in the fixture: the roll INTO MU 9/04 1010c, posted 15:22 ET on
# 9/04 (trades.csv #20, one of the three runners). It is in-sample at the 15:25 default and is
# the first thing a tighter cutoff would drop.
LATEST_EXPIRY_DAY_ENTRY = "MU260904C01010000"


def _expected_shapes() -> list[tuple[str, str, float | None]]:
    """(action, contract, posted price) per golden row, expanded to the signal stream."""
    expected = json.loads((FIXTURES / "ttt_expected.json").read_text())
    out: list[tuple[str, str, float | None]] = []
    for e in expected:
        if e["label"] == "ENTRY":
            out.append(("BTO", e["contract"], e["price"]))
        elif e["label"] == "EXIT":
            out.append(("STC", e["contract"], e["price"]))
        elif e["label"] == "ROLL":
            # The golden row names the NEW contract; the closed one is checked below.
            out.append(("STC", None, None))
            out.append(("BTO", e["contract"], e["price"]))
    return out


def _payload_contract(p) -> str:
    return f"{p.ticker}{p.expiry:%y%m%d}{p.right.value}{int(round(float(p.strike) * 1000)):08d}"


async def test_fixture_end_to_end():
    """The 113 fixture rows arrive one per poll, each 5s old at poll time, and the emitted
    stream equals the phase-1 golden labels (prices where the post named one; a priceless
    exit carries the author's last posted price)."""
    bff = FakeBff()
    rows = _fixture_rows()
    clock = Clock(parse_posted_at(rows[0]["posted_at"]) - timedelta(days=1))
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    assert emitter.emitted == []

    for r in rows:
        bff.add(r)
        clock.now = parse_posted_at(r["posted_at"]) + timedelta(seconds=5)
        await poller.poll_once()

    assert len(rows) == 113
    got = [(p.action.value, _payload_contract(p), float(p.price)) for p in emitter.emitted]
    want = _expected_shapes()
    assert len(got) == len(want)
    for g, w in zip(got, want, strict=True):
        assert g[0] == w[0]
        if w[1] is not None:
            assert g[1] == w[1]
        if w[2] is not None:
            assert g[2] == w[2]
    assert sum(1 for a, _, _ in got if a == "BTO") == 26
    assert all(
        workflow_id_for(p).startswith("t-paper_lotto/s-copytrade-lotto-v1/sig/")
        for p in emitter.emitted
    )
    # The roll (15:22 ET, inside the 15:25 cutoff): STC on the 1015c then BTO into the 1010c.
    rolled = [p for p in emitter.emitted if p.signal_id.endswith(":1")]
    assert len(rolled) == 1 and _payload_contract(rolled[0]) == LATEST_EXPIRY_DAY_ENTRY
    roll_stc = emitter.emitted[emitter.emitted.index(rolled[0]) - 1]
    assert roll_stc.action.value == "STC"
    assert _payload_contract(roll_stc) == "MU260904C01015000"


async def test_replay_reports_every_signal_regardless_of_age():
    bff = FakeBff()
    for r in _fixture_rows():
        bff.add(r)
    clock = Clock(datetime(2026, 10, 26, tzinfo=timezone.utc))
    poller, emitter = make_poller(bff, clock)
    since = datetime(2026, 3, 1, tzinfo=timezone.utc)
    payloads = await poller.replay(since)
    assert len(payloads) == len(_expected_shapes())
    assert bff.requests[0].url.params["after"] == str(snowflake_at(since))


async def test_replay_cli_prints_would_emit_list(monkeypatch, capsys):
    bff = FakeBff()
    bff.add(row(T0 - timedelta(hours=2), "MU 9/9 1050c @ 1.90"))
    bff.add(row(T0 - timedelta(hours=1), "2.35 taking some"))
    monkeypatch.setenv("BFF_INTERNAL_URL", "http://bff:8083")
    monkeypatch.setenv("OPTIONS_CHAT_INGEST_TOKEN", TOKEN)
    monkeypatch.setenv("TENANT_ID", TENANT)
    monkeypatch.setenv("LOTTO_CHANNEL_ID", CHANNEL)
    rc = await lotto_main.replay_main(since=T0 - timedelta(days=1), transport=bff.transport())
    assert rc == 0
    out = capsys.readouterr().out
    assert "BTO MU" in out and "STC MU" in out and "would-emit 2" in out

    bff.fail_next = [503]
    assert await lotto_main.replay_main(since=T0, transport=bff.transport()) == 2


def test_cli_args():
    args = lotto_main.parse_args(["--replay", "--since", "2026-10-01T00:00:00Z"])
    assert args.replay and args.since == datetime(2026, 10, 1, tzinfo=timezone.utc)
    assert lotto_main.parse_args([]).replay is False
    with pytest.raises(SystemExit):
        lotto_main.parse_args(["--replay", "--since", "2026-10-01T00:00:00"])  # naive
