#!/usr/bin/env python3
"""NYSE market-hours helper for pr-deploy-agent.

Rules (confirmed by operator 2026-10-29):
- Market OPEN  = Mon–Fri, 09:00–16:30 US/Eastern, excluding NYSE full holidays
- Market CLOSED = everything else (weekends, holidays, outside 09:00–16:30 ET)
- Early closes (e.g. day-before-Thanksgiving 13:00) are treated as OPEN (safe direction)
- DST is handled by zoneinfo; America/New_York covers EST/EDT automatically

Used by deploy_gate.py to decide:
- signal-watcher services: SAFE only when market is CLOSED
- trading-path services:   SAFE (market-closed path) when no non-terminal orders
                           UNSAFE (market-open path)  when any PositionWorkflow or non-terminal order

The holiday list covers 2026–2027. The test suite has a case that fails when the list
runs out; add years before that test starts failing.

CLI:  python3 market_hours.py [<iso-datetime-et>]
      No argument → current system time interpreted as ET.
      Prints OPEN or CLOSED, exits 0.
"""
from __future__ import annotations

import sys
from datetime import date, datetime, time
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")

# NYSE full-day closures 2026–2027.
# Source: NYSE holiday schedule. Early closes (e.g. Black Friday 13:00) are NOT listed
# here; those days are treated as full trading days (see the docstring above).
NYSE_HOLIDAYS: frozenset[date] = frozenset(
    [
        # 2026
        date(2026, 1, 1),   # New Year's Day
        date(2026, 1, 19),  # MLK Day
        date(2026, 2, 16),  # Presidents Day
        date(2026, 4, 3),   # Good Friday
        date(2026, 5, 25),  # Memorial Day
        date(2026, 6, 19),  # Juneteenth
        date(2026, 7, 3),   # Independence Day (observed; Jul 4 is Saturday)
        date(2026, 9, 7),   # Labor Day
        date(2026, 11, 26), # Thanksgiving
        date(2026, 12, 25), # Christmas
        # 2027
        date(2027, 1, 1),   # New Year's Day
        date(2027, 1, 18),  # MLK Day
        date(2027, 2, 15),  # Presidents Day
        date(2027, 3, 26),  # Good Friday
        date(2027, 5, 31),  # Memorial Day
        date(2027, 6, 18),  # Juneteenth (observed; Jun 19 is Saturday)
        date(2027, 7, 5),   # Independence Day (observed; Jul 4 is Sunday)
        date(2027, 9, 6),   # Labor Day
        date(2027, 11, 25), # Thanksgiving
        date(2027, 12, 24), # Christmas (observed; Dec 25 is Saturday)
    ]
)

# Last year in the holiday list; the test will fail when this year is in the past.
_HOLIDAY_LIST_LAST_YEAR = 2027

# Market session (US/Eastern), with 30-minute pre/post buffers per operator decision.
_OPEN = time(9, 0)   # 30 min before 09:30 open
_CLOSE = time(16, 30)  # 30 min after  16:00 close


def is_market_open(dt: datetime | None = None) -> bool:
    """Return True when the NYSE session is considered open.

    dt must be timezone-aware, or None to use the current moment.
    """
    if dt is None:
        dt = datetime.now(tz=ET)
    else:
        # Normalise to ET so callers may pass any tz-aware datetime.
        dt = dt.astimezone(ET)

    if dt.weekday() >= 5:  # Saturday=5, Sunday=6
        return False
    if dt.date() in NYSE_HOLIDAYS:
        return False
    return _OPEN <= dt.time() < _CLOSE


def _ensure_holiday_list_current(year: int) -> None:
    """Raise AssertionError when the holiday list does not cover `year`."""
    if year > _HOLIDAY_LIST_LAST_YEAR:
        raise AssertionError(
            f"NYSE_HOLIDAYS only covers up to {_HOLIDAY_LIST_LAST_YEAR}; "
            f"add {year} (and beyond) before this test fails in production."
        )


def main() -> None:
    if len(sys.argv) > 1:
        raw = sys.argv[1]
        # Accept ISO-format datetime; if no tzinfo, assume ET.
        dt = datetime.fromisoformat(raw)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=ET)
    else:
        dt = datetime.now(tz=ET)

    print("OPEN" if is_market_open(dt) else "CLOSED")


if __name__ == "__main__":
    main()
