"""Tests for the once-per-ET-day mirror state.

``DailyMirrorState`` is the durable idempotency layer: it records the last ET
date a watchlist was mirrored so a pod restart on the same day does not re-emit.
``et_today()`` resolves the America/New_York calendar date.
"""

from __future__ import annotations

import pathlib
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from ohmytradeagent_sidecar.watchlist_state import DailyMirrorState, et_today


def test_first_call_not_mirrored(tmp_path: pathlib.Path) -> None:
    state = DailyMirrorState(tmp_path / "watchlist_state.json")
    assert state.already_mirrored_today("2026-06-03") is False


def test_record_then_already_mirrored(tmp_path: pathlib.Path) -> None:
    state = DailyMirrorState(tmp_path / "watchlist_state.json")
    state.record(et_date="2026-06-03", source_message_id="msg-1")
    assert state.already_mirrored_today("2026-06-03") is True


def test_new_day_resets(tmp_path: pathlib.Path) -> None:
    state = DailyMirrorState(tmp_path / "watchlist_state.json")
    state.record(et_date="2026-06-03", source_message_id="msg-1")
    assert state.already_mirrored_today("2026-06-04") is False


def test_survives_fresh_state_over_same_path(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "watchlist_state.json"
    DailyMirrorState(path).record(et_date="2026-06-03", source_message_id="msg-1")
    # Simulate a pod restart: a brand-new state object over the same file.
    restarted = DailyMirrorState(path)
    assert restarted.already_mirrored_today("2026-06-03") is True


def test_corrupt_file_not_mirrored(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "watchlist_state.json"
    path.write_text("{ not json", encoding="utf-8")
    state = DailyMirrorState(path)
    assert state.already_mirrored_today("2026-06-03") is False


def test_et_today_matches_zoneinfo_date() -> None:
    today = et_today()
    assert len(today) == 10 and today[4] == "-" and today[7] == "-"
    expected = (
        datetime.now(timezone.utc)
        .astimezone(ZoneInfo("America/New_York"))
        .date()
        .isoformat()
    )
    assert today == expected
