"""Tests for the watchlist watcher's pure per-tick logic.

The browser/poll loop is excluded (covered by integration). Here we drive
``process(msgs, et_date)`` directly with an ``InMemoryWatchlistEmitter`` —
the same testability seam ``watcher.py`` uses for ``_tick``/``_build_payload``.
"""

from __future__ import annotations

import logging
import pathlib

import pytest
from playwright.async_api import Error as PlaywrightError

from ohmytradeagent_sidecar.discord_dom import RawMessage
from ohmytradeagent_sidecar.emitter import (
    InMemoryWatchlistEmitter,
    watchlist_workflow_id_for,
)
from ohmytradeagent_sidecar.watchlist_watcher import (
    WatchlistWatcher,
    _is_fatal_page_error,
)


AUTHOR = "TradingTheTrend"
WATCHLIST = "SPY 762c > 761.00\nQQQ 480p < 481.00"


def _msg(
    message_id: str,
    *,
    author: str = AUTHOR,
    content: str = WATCHLIST,
    timestamp_iso: str | None = "2026-06-03T15:00:00Z",
) -> RawMessage:
    # Default posted-time → 2026-06-03 in America/New_York (11:00 EDT), so the
    # existing emit-tests (et_date="2026-06-03") still pass the posted-date gate.
    return RawMessage(
        message_id=message_id,
        author=author,
        timestamp_iso=timestamp_iso or "",
        content=content,
    )


def _make_watcher(
    tmp_path: pathlib.Path,
    emitter: InMemoryWatchlistEmitter,
    *,
    tenant_id: str = "dev",
    additional_targets: list[tuple[str, str]] | None = None,
) -> WatchlistWatcher:
    return WatchlistWatcher(
        channel_url="https://discord/channel/watchlist",
        state_dir=tmp_path,
        emitter=emitter,
        tenant_id=tenant_id,
        strategy_id="copytrade-v1",
        author=AUTHOR,
        log=logging.getLogger("test"),
        poll_interval_secs=45.0,
        additional_targets=additional_targets,
    )


async def test_emits_when_author_and_watchlist_shaped(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process([_msg("msg-1")], et_date="2026-06-03")

    assert len(emitter.emitted) == 1
    p = emitter.emitted[0]
    assert p.schema_version == 1
    assert p.tenant_id == "dev"
    assert p.strategy_id == "copytrade-v1"
    assert p.author == AUTHOR
    assert p.raw_text == WATCHLIST
    assert p.source_message_id == "msg-1"
    assert p.et_date.isoformat() == "2026-06-03"


async def test_rejects_wrong_author(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process([_msg("msg-1", author="someone-else")], et_date="2026-06-03")
    assert emitter.emitted == []


async def test_rejects_non_watchlist_from_right_author(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process(
        [_msg("msg-1", content="BTO NVDA 4/27 205c @ 2.11")], et_date="2026-06-03"
    )
    assert emitter.emitted == []


async def test_emits_once_per_day_across_restart(tmp_path: pathlib.Path) -> None:
    emitter1 = InMemoryWatchlistEmitter()
    w1 = _make_watcher(tmp_path, emitter1)
    await w1.process([_msg("msg-1")], et_date="2026-06-03")
    assert len(emitter1.emitted) == 1

    # Simulated restart: fresh watcher + fresh emitter, same state dir.
    emitter2 = InMemoryWatchlistEmitter()
    w2 = _make_watcher(tmp_path, emitter2)
    await w2.process([_msg("msg-1")], et_date="2026-06-03")
    assert emitter2.emitted == []


async def test_second_watchlist_same_day_no_second_emit(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process([_msg("msg-1")], et_date="2026-06-03")
    await w.process([_msg("msg-2")], et_date="2026-06-03")
    assert len(emitter.emitted) == 1
    assert emitter.emitted[0].source_message_id == "msg-1"


async def test_workflow_id_shape(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process([_msg("msg-1")], et_date="2026-06-03")
    p = emitter.emitted[0]
    assert (
        watchlist_workflow_id_for(p) == "t-dev/s-copytrade-v1/watchlist/msg-1"
    )


def _wf_id_for_msg(message_id: str) -> str:
    return f"t-dev/s-copytrade-v1/watchlist/{message_id}"


async def test_midnight_stale_across_restart_does_not_consume_today(
    tmp_path: pathlib.Path,
) -> None:
    """Regression for the midnight race (homelab incident 2026-06-04).

    At midnight ET the gate opens for the new day, but the channel's newest
    watchlist is still yesterday's. The watcher re-finds it; Temporal already
    has that workflow (from before the restart) → deduped=True. The day's slot
    must NOT be consumed, so the real morning post still mirrors.
    """
    emitter = InMemoryWatchlistEmitter()
    # Simulate Temporal already holding the stale message from before restart.
    emitter.preseed(_wf_id_for_msg("stale-yesterday"))
    w = _make_watcher(tmp_path, emitter)

    # Both messages are posted-today (this test exercises the deduped-skip path,
    # not the posted-date gate); give them today's ET timestamp.
    await w.process(
        [_msg("stale-yesterday", timestamp_iso="2026-06-04T15:00:00Z")],
        et_date="2026-06-04",
    )
    # The stale re-find was deduped — it must NOT have consumed today's slot.
    assert emitter.emitted == []
    assert w._state.already_mirrored_today("2026-06-04") is False  # type: ignore[attr-defined]

    # The real morning post (different id) arrives and IS mirrored + recorded.
    await w.process(
        [_msg("real-morning", timestamp_iso="2026-06-04T15:00:00Z")],
        et_date="2026-06-04",
    )
    assert len(emitter.emitted) == 1
    assert emitter.emitted[0].source_message_id == "real-morning"
    assert w._state.already_mirrored_today("2026-06-04") is True  # type: ignore[attr-defined]


async def test_seen_set_emits_stale_message_only_once_across_ticks(
    tmp_path: pathlib.Path,
) -> None:
    """Until a real post arrives, the same stale watchlist must be emitted at
    most once per process run (in-process seen-set), not every 45s tick.
    """
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    await w.process([_msg("stale", timestamp_iso="2026-06-04T15:00:00Z")], et_date="2026-06-04")
    await w.process([_msg("stale", timestamp_iso="2026-06-04T15:00:00Z")], et_date="2026-06-04")

    # The stale message was a fresh id here, so the FIRST tick emits it. The
    # deduped path then leaves the day open; the SECOND tick must be skipped by
    # the seen-set rather than re-emitting. emit() was called exactly once.
    assert len(emitter.emitted) == 1


async def test_brand_new_watchlist_mirrors_and_records_first_try(
    tmp_path: pathlib.Path,
) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process([_msg("msg-1", timestamp_iso="2026-06-04T15:00:00Z")], et_date="2026-06-04")
    assert len(emitter.emitted) == 1
    assert w._state.already_mirrored_today("2026-06-04") is True  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# Posted-date gate (second midnight-race manifestation): a watchlist-shaped
# message whose POSTED ET date != today is a prior-day watchlist in scrollback
# and must NOT be mirrored / mislabeled with today's date.
# ---------------------------------------------------------------------------


async def test_stale_prior_day_watchlist_is_not_mirrored(tmp_path: pathlib.Path) -> None:
    """Regression: at midnight ET the newest watchlist is still yesterday's and
    has never been mirrored (so it is NOT deduped). The posted-date gate must
    skip it and leave the day open so the real ~8:20am post still mirrors.
    """
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    # Posted 2026-06-04 (ET), processed on 2026-06-05.
    await w.process(
        [_msg("stale", timestamp_iso="2026-06-04T13:00:00Z")], et_date="2026-06-05"
    )
    assert emitter.emitted == []
    assert w._state.already_mirrored_today("2026-06-05") is False  # type: ignore[attr-defined]

    # A subsequent today-dated watchlist still mirrors (day stayed open).
    await w.process(
        [_msg("today", timestamp_iso="2026-06-05T13:00:00Z")], et_date="2026-06-05"
    )
    assert len(emitter.emitted) == 1
    assert emitter.emitted[0].source_message_id == "today"
    assert w._state.already_mirrored_today("2026-06-05") is True  # type: ignore[attr-defined]


async def test_todays_watchlist_after_skipping_stale_one(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    # Scrollback order: stale prior-day watchlist first, then today's.
    await w.process(
        [
            _msg("stale", timestamp_iso="2026-06-04T13:00:00Z"),
            _msg("today", timestamp_iso="2026-06-05T13:00:00Z"),
        ],
        et_date="2026-06-05",
    )
    assert len(emitter.emitted) == 1
    assert emitter.emitted[0].source_message_id == "today"
    assert w._state.already_mirrored_today("2026-06-05") is True  # type: ignore[attr-defined]


async def test_todays_watchlist_mirrors(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)
    await w.process(
        [_msg("msg-1", timestamp_iso="2026-06-05T13:00:00Z")], et_date="2026-06-05"
    )
    assert len(emitter.emitted) == 1
    assert w._state.already_mirrored_today("2026-06-05") is True  # type: ignore[attr-defined]


async def test_fans_out_to_additional_targets(tmp_path: pathlib.Path) -> None:
    """A genuinely-new watchlist is mirrored once per fan-out target (primary +
    extras), each tenant-scoped, so it lands in every tenant's channel. The day
    is recorded once.
    """
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(
        tmp_path,
        emitter,
        tenant_id="prod_real",
        additional_targets=[("staging_paper", "copytrade-v1")],
    )
    await w.process([_msg("msg-1")], et_date="2026-06-03")

    assert len(emitter.emitted) == 2
    assert {p.tenant_id for p in emitter.emitted} == {"prod_real", "staging_paper"}
    # The PRIMARY is emitted first (it's the once-per-day leader).
    assert emitter.emitted[0].tenant_id == "prod_real"
    # Same source message mirrored to both, distinct per-tenant workflow ids.
    assert all(p.source_message_id == "msg-1" for p in emitter.emitted)
    assert {watchlist_workflow_id_for(p) for p in emitter.emitted} == {
        "t-prod_real/s-copytrade-v1/watchlist/msg-1",
        "t-staging_paper/s-copytrade-v1/watchlist/msg-1",
    }
    assert w._state.already_mirrored_today("2026-06-03") is True  # type: ignore[attr-defined]


async def test_deduped_primary_does_not_fan_out(tmp_path: pathlib.Path) -> None:
    """When the PRIMARY emit is deduped (stale re-find), no fan-out happens and
    the day is not consumed — the extras only mirror alongside a genuinely-new
    primary post.
    """
    emitter = InMemoryWatchlistEmitter()
    emitter.preseed("t-prod_real/s-copytrade-v1/watchlist/stale")
    w = _make_watcher(
        tmp_path,
        emitter,
        tenant_id="prod_real",
        additional_targets=[("staging_paper", "copytrade-v1")],
    )
    await w.process(
        [_msg("stale", timestamp_iso="2026-06-04T15:00:00Z")], et_date="2026-06-04"
    )
    assert emitter.emitted == []  # primary deduped → no fan-out at all
    assert w._state.already_mirrored_today("2026-06-04") is False  # type: ignore[attr-defined]


async def test_missing_timestamp_is_skipped(
    tmp_path: pathlib.Path, caplog: pytest.LogCaptureFixture
) -> None:
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    caplog.set_level(logging.WARNING, logger="test")
    await w.process(
        [_msg("no-ts", timestamp_iso=None)], et_date="2026-06-05"
    )
    assert emitter.emitted == []
    assert w._state.already_mirrored_today("2026-06-05") is False  # type: ignore[attr-defined]
    assert any(r.levelno == logging.WARNING for r in caplog.records)


# ---------------------------------------------------------------------------
# Renderer-crash recovery loop (Phase 1). These drive run_on_context() with a
# FakePage/FakeContext to exercise the self-healing rebuild path without a real
# browser. The watchlist channel has today NOT yet mirrored, so each _tick does
# a full scrape (page.evaluate).
# ---------------------------------------------------------------------------


class _StopLoop(BaseException):
    """Sentinel to break the watcher's infinite loop from inside a fake.

    Subclasses ``BaseException`` (not ``Exception``) so the watcher's broad
    ``except Exception`` transient handler never swallows it — it always
    escapes the loop and lets the test assert.
    """


class FakePage:
    """Minimal Playwright-page double.

    ``evaluate`` returns queued rows or raises a queued exception per tick; the
    goto/wait_for_selector/close/is_closed surface mirrors what the watcher
    calls. Records call counts so tests can assert on rebuild behaviour.
    """

    def __init__(self, tick_results: list, *, goto_error: BaseException | None = None) -> None:
        # Each element is either a list[dict] (rows for extract_recent) or an
        # Exception instance to raise on that tick's page.evaluate.
        self._tick_results = list(tick_results)
        # If set, goto() raises this every time — simulates a rebuilt tab that
        # fails to re-navigate right after a renderer crash.
        self._goto_error = goto_error
        self.evaluate_calls = 0
        self.goto_calls = 0
        self.wait_calls = 0
        self.close_calls = 0
        self._closed = False

    async def goto(self, url: str, *, wait_until: str = "load") -> None:
        self.goto_calls += 1
        if self._goto_error is not None:
            raise self._goto_error

    async def wait_for_selector(self, selector: str, *, timeout: float = 0) -> None:
        self.wait_calls += 1

    async def evaluate(self, js: str):
        self.evaluate_calls += 1
        if not self._tick_results:
            raise _StopLoop()
        nxt = self._tick_results.pop(0)
        if isinstance(nxt, BaseException):
            raise nxt
        return nxt

    async def close(self) -> None:
        self.close_calls += 1
        self._closed = True

    def is_closed(self) -> bool:
        return self._closed


class FakeContext:
    """Hands out queued FakePages on new_page(); records every call."""

    def __init__(self, pages: list[FakePage]) -> None:
        self._pages = list(pages)
        self.new_page_calls = 0
        self.handed_out: list[FakePage] = []

    async def new_page(self) -> FakePage:
        self.new_page_calls += 1
        page = self._pages.pop(0)
        self.handed_out.append(page)
        return page


def _crash(msg: str = "Page.evaluate: Target crashed") -> PlaywrightError:
    return PlaywrightError(msg)


def _no_sleep(*_a, **_k):
    async def _noop() -> None:
        return None

    return _noop()


# --- unit: fatal-page-error detection helper -------------------------------


def test_is_fatal_page_error_matches_target_crashed() -> None:
    page = FakePage([])
    assert _is_fatal_page_error(_crash("Page.evaluate: Target crashed"), page)
    assert _is_fatal_page_error(_crash("Target closed"), page)
    assert _is_fatal_page_error(_crash("Page has been closed"), page)


def test_is_fatal_page_error_transient_is_not_fatal() -> None:
    page = FakePage([])
    assert not _is_fatal_page_error(PlaywrightError("Timeout 30000ms exceeded"), page)
    assert not _is_fatal_page_error(ValueError("some transient DOM hiccup"), page)


def test_is_fatal_page_error_closed_page_is_fatal() -> None:
    page = FakePage([])
    page._closed = True
    # Even a benign exception on a closed page is fatal — the page is gone.
    assert _is_fatal_page_error(ValueError("anything"), page)


# --- loop: crash → rebuild → resume ----------------------------------------


async def test_renderer_crash_rebuilds_page_and_resumes(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Reproduces the incident: the renderer crashes on a tick, the loop closes
    the dead page, opens a fresh one via context.new_page(), re-navigates, and
    the next tick scrapes successfully.
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    dead = FakePage([_crash()])  # tick 1 crashes
    fresh = FakePage([[], _StopLoop()])  # tick 2 scrapes empty, then stop
    ctx = FakeContext([dead, fresh])

    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    # The dead page was closed and a fresh page was built (goto + wait).
    assert dead.close_calls == 1
    assert ctx.new_page_calls == 2  # initial build + one rebuild
    assert fresh.goto_calls == 1
    assert fresh.wait_calls == 1
    # The fresh page's first tick scraped (evaluate ran).
    assert fresh.evaluate_calls >= 1


async def test_transient_tick_error_does_not_rebuild(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A non-fatal exception is swallowed and the SAME page is reused — no
    new_page/rebuild churn for a one-off DOM hiccup.
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    page = FakePage([ValueError("transient"), [], _StopLoop()])
    ctx = FakeContext([page])

    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    assert ctx.new_page_calls == 1  # only the initial page, no rebuild
    assert page.close_calls == 0
    assert page.evaluate_calls >= 2  # transient tick + a good tick on same page


async def test_bounded_crashes_exhaust_and_raise(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Every rebuilt page keeps crashing → after MAX_CONSECUTIVE_CRASHES the
    loop raises (task dies loudly) rather than spinning forever.
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    n = WatchlistWatcher.MAX_CONSECUTIVE_CRASHES
    # initial page + n rebuilt pages, all crashing every tick.
    pages = [FakePage([_crash()]) for _ in range(n + 2)]
    ctx = FakeContext(pages)

    with pytest.raises(PlaywrightError):
        await w.run_on_context(ctx)

    # Bounded: it did not open unbounded pages.
    assert ctx.new_page_calls <= n + 1
    # Close-before-raise: the crashed tab that tripped exhaustion is torn down
    # (not leaked) even though the loop gives up on that iteration.
    assert ctx.handed_out[-1].close_calls == 1


async def test_rebuild_failure_is_bounded_and_backed_off(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A renderer crash triggers a rebuild whose OWN re-navigation keeps failing
    (goto timing out right after the crash — the likeliest recovery-time
    failure). That failure must count against the SAME bounded budget and back
    off, NOT escape unguarded on the first rebuild attempt.

    Regression for the PR-513 review finding: pre-fix, an unguarded
    ``_new_ready_page`` in the except block killed the task on
    ``consecutive_crashes == 1`` instead of honoring MAX_CONSECUTIVE_CRASHES.
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    n = WatchlistWatcher.MAX_CONSECUTIVE_CRASHES
    dead = FakePage([_crash()])  # initial tab: crashes on its first tick
    # Every rebuilt tab fails to re-navigate (transient goto failure).
    rebuilds = [
        FakePage([], goto_error=PlaywrightError("Timeout 30000ms exceeded"))
        for _ in range(n + 2)
    ]
    ctx = FakeContext([dead, *rebuilds])

    # It raises (dies loudly) only after exhausting the budget — the rebuild
    # failures were counted, not swallowed and not escaped early.
    with pytest.raises(PlaywrightError):
        await w.run_on_context(ctx)

    # Retried the rebuild across multiple attempts (initial + dead-tab rebuild +
    # more) — pre-fix this died at new_page_calls == 2 on the first rebuild.
    assert ctx.new_page_calls >= 3
    # Still bounded — did not spin opening tabs forever.
    assert ctx.new_page_calls <= n + 1
    # The crashed initial tab was torn down.
    assert dead.close_calls == 1


async def test_crash_counter_resets_on_success(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Crash, rebuild, one good tick, crash again → still within budget because
    a successful tick resets the consecutive-crash counter (no raise).
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    p0 = FakePage([_crash()])  # crash
    p1 = FakePage([[], _crash()])  # good tick (resets), then crash
    p2 = FakePage([[], _StopLoop()])  # good tick, then stop
    ctx = FakeContext([p0, p1, p2])

    # Should NOT raise a PlaywrightError — the good tick reset the counter, so
    # the second crash starts a fresh budget.
    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    assert p0.close_calls == 1
    assert p1.close_calls == 1
    assert ctx.new_page_calls == 3


async def test_rebuild_never_touches_signal_page(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Isolation guard: the watcher only ever opens pages on the passed context
    and never references a signal page or the browser. A FakeContext with no
    browser/close attributes proves the watcher touches neither.
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    dead = FakePage([_crash()])
    fresh = FakePage([[], _StopLoop()])
    ctx = FakeContext([dead, fresh])

    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    # The ONLY page-creation surface used is context.new_page(); the FakeContext
    # has no browser/close/signal_page attributes for the watcher to have used.
    assert not hasattr(ctx, "close")
    assert not hasattr(ctx, "browser")
    assert ctx.new_page_calls == 2


# ---------------------------------------------------------------------------
# Honest heartbeat (Phase 2, F2). watchlist_heartbeat must advance ONLY on a
# clean _tick return — never on a transient/fatal/exhausted tick — so a dead
# tab lets the mtime go stale instead of masking the crash all day. We spy on
# .touch() counts (mtime granularity is too coarse to detect reliably).
# ---------------------------------------------------------------------------


class _TouchSpy:
    """pathlib.Path-like stand-in that only counts .touch() calls."""

    def __init__(self) -> None:
        self.touch_calls = 0

    def touch(self) -> None:
        self.touch_calls += 1


async def test_heartbeat_advances_only_on_successful_tick(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Direct F2 regression: a clean tick advances watchlist_heartbeat; a
    tick that raises does NOT (mtime stays stale while the tab is dead).
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()

    # One good (empty) tick then a transient error, then stop.
    w = _make_watcher(tmp_path, emitter)
    spy = _TouchSpy()
    w._heartbeat_path = spy  # type: ignore[assignment]
    page = FakePage([[], ValueError("transient"), _StopLoop()])
    ctx = FakeContext([page])

    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    # Exactly one touch — the single clean tick. The transient-error tick did
    # NOT touch, and the _StopLoop tick raised before touching.
    assert spy.touch_calls == 1


async def test_heartbeat_advances_on_already_mirrored_noop_tick(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A cheap already-mirrored-today early-return tick is healthy-but-idle and
    MUST still refresh the heartbeat (no false staleness all afternoon).
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    # Mark today as already mirrored so _tick takes the cheap early return
    # (no scrape). et_today() drives the gate; memoize its return here.
    et_date = mod.et_today()
    w._state.record(et_date=et_date, source_message_id="already")  # type: ignore[attr-defined]
    w._mirrored_date = et_date  # type: ignore[attr-defined]

    spy = _TouchSpy()
    w._heartbeat_path = spy  # type: ignore[assignment]
    # Page never scrapes on an already-mirrored tick; second evaluate is the
    # stop sentinel only reached if the early-return were skipped. We stop the
    # loop via a bounded number of ticks instead.
    page = FakePage([])  # evaluate() would raise _StopLoop if ever called
    ctx = FakeContext([page])

    # Run one iteration then stop: patch sleep to raise after the first touch.
    calls = {"n": 0}

    def _sleep_then_stop(*_a, **_k):
        async def _noop() -> None:
            calls["n"] += 1
            if calls["n"] >= 1:
                raise _StopLoop()

        return _noop()

    monkeypatch.setattr(mod.asyncio, "sleep", _sleep_then_stop)

    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    # The early-return tick never scraped the page ...
    assert page.evaluate_calls == 0
    # ... but it still counted as a healthy tick and refreshed the heartbeat.
    assert spy.touch_calls == 1


async def test_heartbeat_stale_after_crash_until_recovery(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Combined with Phase-1 rebuild: the heartbeat does not advance during the
    crash window, then advances again once a rebuilt page ticks cleanly.
    """
    import ohmytradeagent_sidecar.watchlist_watcher as mod

    monkeypatch.setattr(mod.asyncio, "sleep", _no_sleep)
    emitter = InMemoryWatchlistEmitter()
    w = _make_watcher(tmp_path, emitter)

    spy = _TouchSpy()
    w._heartbeat_path = spy  # type: ignore[assignment]

    dead = FakePage([_crash()])  # tick 1 crashes → rebuild, no touch
    fresh = FakePage([[], _StopLoop()])  # tick 2 clean → touch, then stop
    ctx = FakeContext([dead, fresh])

    with pytest.raises(_StopLoop):
        await w.run_on_context(ctx)

    # The crash tick did NOT touch; only the rebuilt page's clean tick did.
    assert dead.close_calls == 1
    assert ctx.new_page_calls == 2
    assert spy.touch_calls == 1
