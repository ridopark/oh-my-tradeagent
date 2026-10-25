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


class UniverseTest(unittest.TestCase):
    def test_read_universe_and_union(self):
        import tempfile, os
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("# fixed names\nnvda\nAMD  # chips\n\nAMD\n"); path = f.name
        try:
            self.assertEqual(oqc.read_universe(path), ["AMD", "NVDA"])
        finally:
            os.unlink(path)
        self.assertEqual(oqc.read_universe(None), [])
        self.assertEqual(oqc.poll_set(["TSM", "AMD"], ["AMD", "NVDA"]), ["AMD", "NVDA", "TSM"])
        self.assertEqual(oqc.poll_set([], []), [])


class TapeRowsTest(unittest.TestCase):
    QUOTES = {"NVDA261031C00200000": {"ts": "2026-10-26T10:00:00-04:00", "sym": "NVDA261031C00200000", "bp": 1.20, "ap": 1.25}}

    def test_min_size_filter_and_quote_stamp(self):
        trades = {"NVDA261031C00200000": [{"t": "2026-10-26T09:59:40Z", "p": 1.25, "s": 50, "x": "C", "c": "I"},
                                          {"t": "2026-10-26T09:59:41Z", "p": 1.21, "s": 3, "x": "C", "c": "I"}],
                  "NVDA261031P00190000": [{"t": "2026-10-26T09:59:50Z", "p": 0.80, "s": 200, "x": "W", "c": "I"}]}
        rows = oqc.tape_rows(trades, self.QUOTES, 20)
        self.assertEqual([(r["sym"], r["s"]) for r in rows], [("NVDA261031C00200000", 50), ("NVDA261031P00190000", 200)])
        self.assertEqual((rows[0]["bp"], rows[0]["ap"], rows[0]["qts"]), (1.20, 1.25, "2026-10-26T10:00:00-04:00"))
        self.assertEqual((rows[1]["bp"], rows[1]["ap"], rows[1]["qts"]), (None, None, None))  # no quote this cycle: still kept

    def test_empty_and_missing_size(self):
        self.assertEqual(oqc.tape_rows(None, {}, 20), [])
        self.assertEqual(oqc.tape_rows({"X": [{"t": "t", "p": 1.0}]}, {}, 20), [])


class OiRowsTest(unittest.TestCase):
    def test_shapes_contracts_and_keeps_missing_oi_as_none(self):
        import importlib.util as iu
        spec = iu.spec_from_file_location("option_oi_collector", SCRIPT_PATH.parent / "option_oi_collector.py")
        oi = iu.module_from_spec(spec); spec.loader.exec_module(oi)
        rows = oi.oi_rows([{"symbol": "AMD261121C00250000", "open_interest": "1234", "open_interest_date": "2026-10-23",
                            "strike_price": "250", "expiration_date": "2026-11-21", "type": "call", "close_price": "3.1",
                            "close_price_date": "2026-10-23"},
                           {"symbol": "AMD261121P00200000", "strike_price": "200", "expiration_date": "2026-11-21", "type": "put"}],
                          "2026-10-26")
        self.assertEqual(rows[0], {"d": "2026-10-26", "sym": "AMD261121C00250000", "oi": 1234, "oi_date": "2026-10-23", "strike": 250.0,
                                   "expiry": "2026-11-21", "type": "call", "close": 3.1, "close_date": "2026-10-23"})
        self.assertIsNone(rows[1]["oi"]); self.assertIsNone(rows[1]["close"])


class WatchlistTickersTest(unittest.TestCase):
    def test_parses_psql_output(self):
        cmd = f'{sys.executable} -c "import sys; sys.stdin.read(); print(\'SPY\'); print(\'\'); print(\'AMD\')"'
        self.assertEqual(oqc.watchlist_tickers(cmd, DAY), ["AMD", "SPY"])


if __name__ == "__main__":
    unittest.main()
