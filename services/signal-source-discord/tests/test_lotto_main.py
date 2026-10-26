"""Lotto poller tests (PLAN-2026-10-26-copytrade-lotto-v1, phase 4).

The BFF read endpoint (phase 3) is mocked with ``httpx.MockTransport`` behind a small stateful
fake that honours ``after``/``limit`` paging, the ingest token and the channel allow-list. Emits go
to ``InMemoryEmitter``; nothing here dials Temporal.
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx
import pytest
from test_lotto_parser import AUTHOR, load_messages

from ohmytradeagent_sidecar import lotto_main
from ohmytradeagent_sidecar.emitter import InMemoryEmitter, workflow_id_for
from ohmytradeagent_sidecar.fanout_registry import FanoutRefresher, FanoutRegistryClient
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
    targets: list[tuple[str, str]] | None = None,
) -> tuple[LottoPoller, InMemoryEmitter]:
    client = BffMessagesClient(
        base_url="http://bff:8083",
        token=TOKEN,
        log=LOG,
        client=httpx.AsyncClient(transport=bff.transport(), base_url="http://bff:8083"),
    )
    emitter = emitter if emitter is not None else InMemoryEmitter()
    poller = LottoPoller(
        source=client,
        emitter=emitter,
        book=LottoBook(author=AUTHOR),
        channel_id=CHANNEL,
        author=AUTHOR,
        strategy_id=STRATEGY,
        targets=[(TENANT, STRATEGY)] if targets is None else targets,
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


async def test_boot_stale_drops_are_aggregated_not_warned(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    bff.add(row(T0 - timedelta(hours=2), "SPY 9/4 650p @ .70"))
    bff.add(row(T0 - timedelta(hours=1), ".85 taking some"))
    bff.add(row(T0 - timedelta(seconds=30), "dumped the rest at .20"))
    poller, _ = make_poller(bff, clock)
    with caplog.at_level("DEBUG", logger="test.lotto"):
        await poller.boot()
    stale = [r for r in caplog.records if "stale" in r.message and "boot" not in r.message]
    assert stale and all(r.levelno == logging.DEBUG for r in stale)
    summary = [r for r in caplog.records if r.message.startswith("lotto boot:")]
    assert len(summary) == 1 and summary[0].levelno == logging.INFO
    assert "3 rows" in summary[0].message
    assert "2 stale" in summary[0].message
    assert "1 emitted" in summary[0].message


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
    # Steady-state stale = mirror lag exceeded the window = a real signal was missed: WARNING.
    assert any(r.levelno == logging.WARNING and "stale" in r.message for r in caplog.records)
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
    assert isinstance(emitter, lotto_main.DryRunEmitter)

    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock, dry_run=True)
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    with caplog.at_level("INFO", logger="test.lotto"):
        await poller.poll_once()
    assert any(r.message.startswith("dry-run would-emit") for r in caplog.records)
    assert len(emitter.emitted) == 1


async def test_dry_run_emitter_is_bounded_and_dedupes():
    emitter = lotto_main.DryRunEmitter(max_ids=3)
    bff = FakeBff()
    clock = Clock(T0)
    poller, _ = make_poller(bff, clock, emitter=emitter)
    await poller.boot()
    first = bff.add(row(T0 - timedelta(seconds=9), "MU 9/9 1050c @ 1.90"))
    bff.add(row(T0 - timedelta(seconds=8), "2.35 taking some"))
    bff.add(row(T0 - timedelta(seconds=7), "2.40 taking some"))
    emitted = await poller.poll_once()
    assert len(emitted) == 3
    # A re-send of a remembered id is a dedupe...
    assert (await emitter.emit(emitted[1])).deduped is True
    # ...a 4th id evicts the oldest, which then re-emits (bounded memory, not durable dedupe).
    bff.add(row(T0 - timedelta(seconds=6), "2.45 taking some"))
    await poller.poll_once()
    assert len(emitter) == 3
    assert (await emitter.emit(emitted[0])).deduped is False
    assert emitted[0].message_id == first["message_id"]


# ---------------------------------------------------------------------------
# Poison rows / failure isolation
# ---------------------------------------------------------------------------


async def test_poison_row_is_skipped_and_feed_continues(caplog):
    """parser._resolve_expiry raises ValueError on an impossible date; the row must be skipped,
    the cursor must move past it, and the next row must still emit."""
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    poison = bff.add(row(T0 - timedelta(seconds=6), "MU 9/31 1050c @ 1.90"))
    good = bff.add(row(T0 - timedelta(seconds=3), "SPY 9/9 650p @ .70"))
    with caplog.at_level("ERROR", logger="test.lotto"):
        emitted = await poller.poll_once()
    assert [p.ticker for p in emitted] == ["SPY"]
    assert poller.cursor == int(good["message_id"])
    assert any(poison["message_id"] in r.message and r.exc_text for r in caplog.records)
    assert len(emitter.emitted) == 1


@pytest.mark.parametrize("age", [timedelta(minutes=10), timedelta(seconds=3)])
async def test_zero_strike_row_skipped(age, caplog):
    """to_payload fails pydantic validation (strike > 0) BEFORE the age gate, so both a stale and
    a fresh zero-strike row must be isolated."""
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    bad = bff.add(row(T0 - age, "MU 9/9 0c @ 1.90"))
    good = bff.add(row(T0 - timedelta(seconds=2), "SPY 9/9 650p @ .70", seq=1))
    with caplog.at_level("ERROR", logger="test.lotto"):
        emitted = await poller.poll_once()
    assert [p.ticker for p in emitted] == ["SPY"]
    assert poller.cursor == int(good["message_id"])
    assert any(bad["message_id"] in r.message for r in caplog.records)
    # Zero price too.
    bff.add(row(T0 - timedelta(seconds=1), "QQQ 9/9 500p @ 0", seq=2))
    assert await poller.poll_once() == []
    assert len(emitter.emitted) == 1


async def test_non_json_200_keeps_cursor(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    bodies = [httpx.Response(200, text="<html>upstream</html>"), httpx.Response(200, json=[1])]
    real = bff.handler

    def odd(request: httpx.Request) -> httpx.Response:
        return bodies.pop(0) if bodies else real(request)

    bff.transport = lambda: httpx.MockTransport(odd)  # type: ignore[method-assign]
    poller, _ = make_poller(bff, clock)
    bodies.clear()
    await poller.boot()
    cursor = poller.cursor
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    bodies += [httpx.Response(200, text="<html>upstream</html>"), httpx.Response(200, json=[1])]
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await poller.poll_once() == []  # html
        assert poller.cursor == cursor
        assert await poller.poll_once() == []  # a JSON array, not an object
        assert poller.cursor == cursor
    assert any("body" in r.message for r in caplog.records)
    assert [p.action.value for p in await poller.poll_once()] == ["BTO"]


class FlakyEmitter:
    """Raises on the first ``fail`` emits (Temporal RPC errors), then delegates."""

    def __init__(self, inner: InMemoryEmitter, fail: int) -> None:
        self.inner = inner
        self.fail = fail
        self.calls = 0

    async def emit(self, payload):
        self.calls += 1
        if self.fail > 0:
            self.fail -= 1
            raise RuntimeError("rpc unavailable")
        return await self.inner.emit(payload)

    async def close(self) -> None:
        return None


async def test_emit_failure_retries_next_poll(caplog):
    """A failed start is retried on the next poll WITHOUT re-reading the row: re-applying it to
    the book would make a BTO look like an entry on an already-open contract and drop it."""
    bff = FakeBff()
    clock = Clock(T0)
    inner = InMemoryEmitter()
    flaky = FlakyEmitter(inner, fail=1)
    poller, _ = make_poller(bff, clock, emitter=flaky)  # type: ignore[arg-type]
    await poller.boot()
    entry = bff.add(row(T0 - timedelta(seconds=6), "MU 9/9 1050c @ 1.90"))
    bff.add(row(T0 - timedelta(seconds=3), "2.35 taking some"))
    with caplog.at_level("ERROR", logger="test.lotto"):
        emitted = await poller.poll_once()
    # The BTO start failed (kept for retry); the STC after it still went out.
    assert [p.action.value for p in emitted] == ["STC"]
    assert any(entry["message_id"] in r.message for r in caplog.records)
    assert poller.cursor > int(entry["message_id"])  # cursor is not held back

    emitted = await poller.poll_once()
    assert [(p.action.value, p.signal_id) for p in emitted] == [
        ("BTO", f"{entry['message_id']}:0")
    ]
    assert [p.action.value for p in inner.emitted] == ["STC", "BTO"]
    assert await poller.poll_once() == []  # nothing left pending


async def test_emit_retry_gives_up_outside_the_age_window(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    flaky = FlakyEmitter(InMemoryEmitter(), fail=5)
    poller, _ = make_poller(bff, clock, emitter=flaky)  # type: ignore[arg-type]
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    assert await poller.poll_once() == []
    clock.now = T0 + timedelta(minutes=5)  # now older than the 120s window
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await poller.poll_once() == []
    assert any("gave up" in r.message for r in caplog.records)
    assert flaky.calls == 1  # not retried once stale


async def test_malformed_page_advances_cursor(caplog):
    """A page where EVERY row is malformed must not wedge the feed: the cursor moves past the
    rows and the per-row ERROR is throttled."""
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock)
    await poller.boot()
    bad = [
        bff.add(row(T0 - timedelta(seconds=9), f"MU 9/9 1050c @ 1.9{i}", seq=i)) for i in range(5)
    ]
    for r in bad:
        r["posted_at"] = "2026-09-04T18:42:51"  # naive: contract violation
    with caplog.at_level("ERROR", logger="test.lotto"):
        assert await poller.poll_once() == []
    assert poller.cursor == int(bad[-1]["message_id"])
    malformed = [r for r in caplog.records if "malformed" in r.message]
    assert len(malformed) == 2  # consecutive=1 and 3 of the 5
    assert emitter.emitted == []
    good = bff.add(row(T0 - timedelta(seconds=2), "SPY 9/9 650p @ .70"))
    assert [p.ticker for p in await poller.poll_once()] == ["SPY"]
    assert poller.cursor == int(good["message_id"])


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

    # Exactly 15:25:00 ET → skipped (the cutoff is inclusive).
    clock.now = at(15, 25) + timedelta(seconds=5)
    bff.add(row(at(15, 25), "IWM 0dte 220p @ .40"))
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await poller.poll_once() == []

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
        strategy_id=STRATEGY,
        targets=[(TENANT, STRATEGY)],
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
        # Record whether THIS cycle re-touched the file, then zero the mtime so the next cycle
        # has to touch it again (a non-decreasing check would pass with no touches at all).
        touches.append(heartbeat.stat().st_mtime_ns)
        os.utime(heartbeat, (0, 0))
        if len(touches) == 2:
            bff.fail_next = []  # boot succeeds on the next attempt
        if len(touches) == 4:
            bff.fail_next = [500]
        if len(touches) == 6:
            raise Stop

    with pytest.raises(Stop):
        await lotto_main.run_loop(poller, heartbeat, 5.0, sleep=fake_sleep)

    assert seen_at_first_request == [True]  # touched BEFORE the first BFF read returned
    assert len(touches) == 6  # 2 failed boots, 1 ok boot, 3 polls (one a 500): one sleep each
    assert all(t > 0 for t in touches)  # every cycle re-touched after the previous zeroing
    assert poller.cursor > 0  # the loop did get past boot


async def test_poll_exception_does_not_kill_loop(monkeypatch, tmp_path, caplog):
    heartbeat = tmp_path / "heartbeat"
    bff = FakeBff()
    poller, _ = make_poller(bff, Clock(T0))
    calls = {"n": 0}
    real_poll = poller.poll_once

    async def flaky_poll():
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("boom")
        return await real_poll()

    monkeypatch.setattr(poller, "poll_once", flaky_poll)

    class Stop(Exception):
        pass

    sleeps: list[int] = []

    async def fake_sleep(_secs: float) -> None:
        sleeps.append(heartbeat.stat().st_mtime_ns)
        os.utime(heartbeat, (0, 0))
        if len(sleeps) == 3:
            raise Stop

    with caplog.at_level("ERROR", logger="test.lotto"), pytest.raises(Stop):
        await lotto_main.run_loop(poller, heartbeat, 5.0, sleep=fake_sleep)
    assert calls["n"] == 3  # the raising poll did not end the loop
    assert all(t > 0 for t in sleeps)  # heartbeat touched on the raising cycle too
    assert any("boom" in (r.exc_text or "") for r in caplog.records)


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
    # The malformed row is kept, flagged, so the poller can advance the cursor past it.
    assert [(r.message_id, r.malformed) for r in got] == [
        (bad["message_id"], True),
        (good["message_id"], False),
    ]
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
# Fan-out targets
# ---------------------------------------------------------------------------

GW_TOKEN = "gw-token"


class FakeGateway:
    def __init__(self, targets: list[tuple[str, str]]) -> None:
        self.targets = targets
        self.requests: list[httpx.Request] = []
        self.fail_next: list[int] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.fail_next:
            return httpx.Response(self.fail_next.pop(0), json={"error": "injected"})
        if request.headers.get("Authorization") != f"Bearer {GW_TOKEN}":
            return httpx.Response(401, json={"error": "unauthorized"})
        assert request.url.path == "/internal/copytrade-fanout-targets"
        return httpx.Response(
            200,
            json={
                "targets": [{"tenant_id": t, "strategy_id": s} for t, s in self.targets],
                "count": len(self.targets),
            },
        )

    def client(self, strategy_id: str | None = STRATEGY) -> FanoutRegistryClient:
        return FanoutRegistryClient(
            base_url="http://gw:8082",
            token=GW_TOKEN,
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(self.handler), base_url="http://gw:8082"
            ),
            strategy_id=strategy_id,
        )


async def test_fanout_client_sends_strategy_id_param_only_when_set():
    gw = FakeGateway([("paper_lotto", STRATEGY)])
    assert await gw.client().fetch_targets() == [("paper_lotto", STRATEGY)]
    assert gw.requests[-1].url.params["strategy_id"] == STRATEGY
    # Backward compatible: None sends no param (the gateway default, copytrade-v1, applies).
    await gw.client(strategy_id=None).fetch_targets()
    assert "strategy_id" not in gw.requests[-1].url.params


async def test_targets_refreshed_from_registry_fail_safe(caplog):
    gw = FakeGateway([("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)])
    poller, _ = make_poller(FakeBff(), Clock(T0), targets=[])
    refresher = FanoutRefresher(
        client=gw.client(), apply_targets=poller.update_targets, log=LOG, refresh_secs=60
    )
    assert await refresher.refresh_once() is True
    assert poller.targets == [("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)]
    assert gw.requests[0].url.params["strategy_id"] == STRATEGY
    assert gw.requests[0].headers["Authorization"] == f"Bearer {GW_TOKEN}"

    # 5xx and an empty set both retain the last good set.
    gw.fail_next = [503]
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await refresher.refresh_once() is False
    assert poller.targets == [("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)]
    gw.targets = []
    assert await refresher.refresh_once() is False
    assert poller.targets == [("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)]
    assert any("retaining last good" in r.message for r in caplog.records)

    # A wrong token is a failed poll too.
    bad = FanoutRegistryClient(
        base_url="http://gw:8082",
        token="wrong",
        client=httpx.AsyncClient(transport=httpx.MockTransport(gw.handler), base_url="http://gw:8082"),
        strategy_id=STRATEGY,
    )
    gw.targets = [("other", STRATEGY)]
    assert (
        await FanoutRefresher(
            client=bad, apply_targets=poller.update_targets, log=LOG, refresh_secs=60
        ).refresh_once()
        is False
    )
    assert poller.targets == [("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)]


def test_foreign_strategy_targets_are_refused(caplog):
    poller, _ = make_poller(FakeBff(), Clock(T0), targets=[])
    with caplog.at_level("WARNING", logger="test.lotto"):
        poller.update_targets(
            [("prod_real", "copytrade-v1"), ("staging_paper", STRATEGY), ("staging_paper", STRATEGY)]
        )
    assert poller.targets == [("staging_paper", STRATEGY)]
    assert any("copytrade-v1" in r.message and "refused" in r.message for r in caplog.records)


async def test_two_tenants_two_workflow_ids_per_signal():
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(
        bff, clock, targets=[("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)]
    )
    await poller.boot()
    entry = bff.add(row(T0 - timedelta(seconds=6), "MU 9/9 1050c @ 1.90"))
    bff.add(row(T0 - timedelta(seconds=3), "2.35 taking some"))
    emitted = await poller.poll_once()
    assert [(p.action.value, p.tenant_id) for p in emitted] == [
        ("BTO", "paper_lotto"),
        ("BTO", "staging_paper"),
        ("STC", "paper_lotto"),
        ("STC", "staging_paper"),
    ]
    assert {workflow_id_for(p) for p in emitted[:2]} == {
        f"t-paper_lotto/s-copytrade-lotto-v1/sig/{entry['message_id']}:0",
        f"t-staging_paper/s-copytrade-lotto-v1/sig/{entry['message_id']}:0",
    }
    assert all(p.strategy_id == STRATEGY for p in emitted)
    assert len(emitter.emitted) == 4


class TenantFlakyEmitter:
    """Fails the first emit for one tenant only."""

    def __init__(self, inner: InMemoryEmitter, tenant_id: str) -> None:
        self.inner = inner
        self.tenant_id = tenant_id
        self.failed = False

    async def emit(self, payload):
        if payload.tenant_id == self.tenant_id and not self.failed:
            self.failed = True
            raise RuntimeError("rpc unavailable")
        return await self.inner.emit(payload)

    async def close(self) -> None:
        return None


async def test_one_tenant_emit_failure_does_not_block_the_other(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    inner = InMemoryEmitter()
    poller, _ = make_poller(
        bff,
        clock,
        emitter=TenantFlakyEmitter(inner, "staging_paper"),  # type: ignore[arg-type]
        targets=[("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)],
    )
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    with caplog.at_level("ERROR", logger="test.lotto"):
        emitted = await poller.poll_once()
    assert [p.tenant_id for p in emitted] == ["paper_lotto"]
    assert any("tenant=staging_paper" in r.message for r in caplog.records)
    # Next poll retries only the parked tenant.
    assert [p.tenant_id for p in await poller.poll_once()] == ["staging_paper"]
    assert [p.tenant_id for p in inner.emitted] == ["paper_lotto", "staging_paper"]


async def test_zero_targets_emits_nothing_and_warns(caplog):
    bff = FakeBff()
    clock = Clock(T0)
    poller, emitter = make_poller(bff, clock, targets=[])
    await poller.boot()
    bff.add(row(T0 - timedelta(seconds=3), "MU 9/9 1050c @ 1.90"))
    with caplog.at_level("WARNING", logger="test.lotto"):
        assert await poller.poll_once() == []
    assert any("no fan-out targets" in r.message for r in caplog.records)
    assert emitter.emitted == []
    # Once targets arrive, the book (rebuilt all along) attributes the next exit correctly.
    poller.update_targets([("paper_lotto", STRATEGY)])
    bff.add(row(T0 - timedelta(seconds=1), "2.35 taking some"))
    assert [(p.action.value, p.ticker) for p in await poller.poll_once()] == [("STC", "MU")]


def test_env_mode_targets(monkeypatch):
    monkeypatch.delenv("LOTTO_TENANT_IDS", raising=False)
    monkeypatch.delenv("TENANT_ID", raising=False)
    monkeypatch.setenv("STRATEGY_ID", STRATEGY)
    with pytest.raises(SystemExit):
        lotto_main.targets_from_env()
    monkeypatch.setenv("TENANT_ID", "paper_lotto")
    assert lotto_main.targets_from_env() == [("paper_lotto", STRATEGY)]
    monkeypatch.setenv("LOTTO_TENANT_IDS", " paper_lotto, staging_paper ,paper_lotto")
    assert lotto_main.targets_from_env() == [
        ("paper_lotto", STRATEGY),
        ("staging_paper", STRATEGY),
    ]


async def test_run_loop_refreshes_targets_before_boot(tmp_path):
    """Boot's in-window emits must already know their tenants: one refresh precedes boot."""
    gw = FakeGateway([("paper_lotto", STRATEGY), ("staging_paper", STRATEGY)])
    bff = FakeBff()
    bff.add(row(T0 - timedelta(seconds=30), "MU 9/9 1050c @ 1.90"))
    poller, emitter = make_poller(bff, Clock(T0), targets=[])
    refresher = FanoutRefresher(
        client=gw.client(), apply_targets=poller.update_targets, log=LOG, refresh_secs=60
    )

    class Stop(Exception):
        pass

    async def stop_sleep(_secs: float) -> None:
        raise Stop

    with pytest.raises(Stop):
        await lotto_main.run_loop(
            poller, tmp_path / "heartbeat", 5.0, sleep=stop_sleep, refresher=refresher
        )
    assert [p.tenant_id for p in emitter.emitted] == ["paper_lotto", "staging_paper"]
    assert len(gw.requests) >= 1


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
