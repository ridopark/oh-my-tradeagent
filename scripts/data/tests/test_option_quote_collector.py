#!/usr/bin/env python3
"""Tests for `scripts/data/option_quote_collector.py` — the default 0DTE mode must be unchanged and the
--watchlist window must match PREREG-2026-10-23-watchlist-forward-v1.

Run standalone:
    python3 -m unittest discover -s scripts/data/tests
"""
import datetime as dt
import importlib.util
import pathlib
import sys
import unittest

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent.parent / "option_quote_collector.py"
_spec = importlib.util.spec_from_file_location("option_quote_collector", SCRIPT_PATH)
assert _spec and _spec.loader, f"could not load {SCRIPT_PATH}"
oqc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(oqc)  # type: ignore[union-attr]

DAY = dt.date(2026, 10, 23)


class SnapshotParamsTest(unittest.TestCase):
    def test_default_is_todays_0dte_chain(self):
        self.assertEqual(oqc.snapshot_params(DAY), {"feed": "opra", "limit": 1000, "expiration_date": "2026-10-23"})

    def test_watchlist_window(self):
        p = oqc.snapshot_params(DAY, spot=200.0)
        self.assertEqual((p["expiration_date_gte"], p["expiration_date_lte"]), ("2026-10-23", "2026-11-02"))
        self.assertEqual((p["strike_price_gte"], p["strike_price_lte"]), (190.0, 210.0))
        self.assertNotIn("expiration_date", p)


class WatchlistSpotTest(unittest.TestCase):
    def test_last_trade_then_ask(self):
        self.assertEqual(oqc.watchlist_spot({"lp": 200.5, "ap": 201.0}), 200.5)
        self.assertEqual(oqc.watchlist_spot({"lp": None, "ap": 201.0}), 201.0)

    def test_no_usable_spot_is_none_never_the_0dte_default(self):
        for und in ({"lp": None, "ap": None}, {"lp": 0, "ap": 0}, {"lp": None, "ap": 0.0}, {}):
            self.assertIsNone(oqc.watchlist_spot(und))


class WatchlistTickersTest(unittest.TestCase):
    def test_parses_psql_output(self):
        cmd = f'{sys.executable} -c "import sys; sys.stdin.read(); print(\'SPY\'); print(\'\'); print(\'AMD\')"'
        self.assertEqual(oqc.watchlist_tickers(cmd, DAY), ["AMD", "SPY"])


if __name__ == "__main__":
    unittest.main()
