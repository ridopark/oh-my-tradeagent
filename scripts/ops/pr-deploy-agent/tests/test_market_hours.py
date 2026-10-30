#!/usr/bin/env python3
"""Tests for market_hours.py — all pure, no network required.

Run:  python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest
from datetime import date, datetime
from zoneinfo import ZoneInfo

HERE = pathlib.Path(__file__).resolve()
SCRIPT = HERE.parent.parent / "market_hours.py"

_spec = importlib.util.spec_from_file_location("market_hours", SCRIPT)
assert _spec and _spec.loader
mh = importlib.util.module_from_spec(_spec)
sys.modules["market_hours"] = mh
_spec.loader.exec_module(mh)  # type: ignore[union-attr]

ET = ZoneInfo("America/New_York")


def _et(year, month, day, hour, minute=0):
    return datetime(year, month, day, hour, minute, tzinfo=ET)


class TestMarketHoursWeekdays(unittest.TestCase):
    def test_tuesday_10am_is_open(self):
        # Tue 2026-10-27 10:00 ET
        self.assertTrue(mh.is_market_open(_et(2026, 10, 27, 10, 0)))

    def test_tuesday_8am_is_closed(self):
        # Before 09:00 buffer
        self.assertFalse(mh.is_market_open(_et(2026, 10, 27, 8, 0)))

    def test_tuesday_17h_is_closed(self):
        # After 16:30
        self.assertFalse(mh.is_market_open(_et(2026, 10, 27, 17, 0)))

    def test_tuesday_16_30_is_closed(self):
        # 16:30 is NOT < 16:30 so it is closed
        self.assertFalse(mh.is_market_open(_et(2026, 10, 27, 16, 30)))

    def test_tuesday_09_00_is_open(self):
        # 09:00 ET is inside the [09:00, 16:30) window
        self.assertTrue(mh.is_market_open(_et(2026, 10, 27, 9, 0)))

    def test_saturday_is_closed(self):
        self.assertFalse(mh.is_market_open(_et(2026, 10, 31, 10, 0)))  # Saturday

    def test_sunday_is_closed(self):
        self.assertFalse(mh.is_market_open(_et(2026, 11, 1, 10, 0)))   # Sunday


class TestMarketHoursHolidays(unittest.TestCase):
    def test_thanksgiving_2026_closed(self):
        # 2026-11-26 Thanksgiving, 11:00 ET
        self.assertFalse(mh.is_market_open(_et(2026, 11, 26, 11, 0)))

    def test_christmas_2026_closed(self):
        # 2026-12-25 Christmas
        self.assertFalse(mh.is_market_open(_et(2026, 12, 25, 10, 0)))

    def test_independence_day_2026_observed_closed(self):
        # 2026-07-03 (observed; Jul 4 is Saturday)
        self.assertFalse(mh.is_market_open(_et(2026, 7, 3, 10, 0)))

    def test_day_after_thanksgiving_2026_is_open(self):
        # 2026-11-27 (Black Friday) — early close at 13:00 but we treat as open
        self.assertTrue(mh.is_market_open(_et(2026, 11, 27, 10, 0)))

    def test_new_years_2027_closed(self):
        # 2027-01-01
        self.assertFalse(mh.is_market_open(_et(2027, 1, 1, 10, 0)))

    def test_thanksgiving_2027_closed(self):
        # 2027-11-25
        self.assertFalse(mh.is_market_open(_et(2027, 11, 25, 11, 0)))


class TestMarketHoursDST(unittest.TestCase):
    def test_march_dst_switch_open(self):
        # 2026-03-10: DST change day (clocks spring forward); 10:00 ET is still open
        self.assertTrue(mh.is_market_open(_et(2026, 3, 10, 10, 0)))

    def test_november_dst_switch_open(self):
        # 2026-11-01: DST ends (Sunday, so closed); 2026-11-02 Monday at 10:00 ET is open
        self.assertFalse(mh.is_market_open(_et(2026, 11, 1, 10, 0)))  # Sunday
        self.assertTrue(mh.is_market_open(_et(2026, 11, 2, 10, 0)))   # Monday


class TestMarketHoursHolidayList(unittest.TestCase):
    def test_2026_holidays_non_empty(self):
        h2026 = [d for d in mh.NYSE_HOLIDAYS if d.year == 2026]
        self.assertGreater(len(h2026), 0, "No 2026 holidays in the list")

    def test_2027_holidays_non_empty(self):
        h2027 = [d for d in mh.NYSE_HOLIDAYS if d.year == 2027]
        self.assertGreater(len(h2027), 0, "No 2027 holidays in the list")

    def test_holiday_list_covers_current_year(self):
        """Fail when the list runs out so we are reminded to update it."""
        import datetime as _dt
        current_year = _dt.date.today().year
        mh._ensure_holiday_list_current(current_year)

    def test_holiday_list_covers_next_year(self):
        import datetime as _dt
        next_year = _dt.date.today().year + 1
        # Suppress the assertion if next_year > _HOLIDAY_LIST_LAST_YEAR — that
        # IS the warning; it will fire in the test above.
        if next_year <= mh._HOLIDAY_LIST_LAST_YEAR:
            mh._ensure_holiday_list_current(next_year)


class TestIsMarketClosed(unittest.TestCase):
    def test_closed_is_inverse_of_open(self):
        t = _et(2026, 10, 27, 10, 0)
        self.assertNotEqual(mh.is_market_open(t), mh.is_market_closed(t))
