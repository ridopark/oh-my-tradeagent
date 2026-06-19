"""Tests for the watchlist watcher's pure per-tick logic.

The browser/poll loop is excluded (covered by integration). Here we drive
``process(msgs, et_date)`` directly with an ``InMemoryWatchlistEmitter`` —
the same testability seam ``watcher.py`` uses for ``_tick``/``_build_payload``.
"""

from __future__ import annotations

import logging
import pathlib

import pytest

from ohmytradeagent_sidecar.discord_dom import RawMessage
from ohmytradeagent_sidecar.emitter import (
    InMemoryWatchlistEmitter,
    watchlist_workflow_id_for,
)
from ohmytradeagent_sidecar.watchlist_watcher import WatchlistWatcher


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
