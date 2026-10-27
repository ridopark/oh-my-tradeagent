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

_oi_spec = importlib.util.spec_from_file_location("option_oi_collector", SCRIPT_PATH.parent / "option_oi_collector.py")
assert _oi_spec and _oi_spec.loader
oi = importlib.util.module_from_spec(_oi_spec)
_oi_spec.loader.exec_module(oi)  # type: ignore[union-attr]

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


class TradeRowsTest(unittest.TestCase):
    """trade_rows against a stubbed `get`: 100 symbols per call, page_token followed, window passed through."""

    def test_chunks_100_symbols_per_call_and_follows_page_token(self):
        import urllib.parse
        syms = [f"S{i:03d}" for i in range(250)]
        calls = []

        def fake_get(url, headers, tries=5):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
            calls.append(q)
            if q["symbols"][0].startswith("S000") and "page_token" not in q:
                return {"trades": {"S000": [{"t": "t1", "p": 1.0, "s": 30}]}, "next_page_token": "p2"}
            if q.get("page_token") == ["p2"]:
                return {"trades": {"S001": [{"t": "t2", "p": 2.0, "s": 5}, {"t": "t3", "p": 2.1, "s": 40}]}, "next_page_token": None}
            return {"trades": {}}

        orig = oqc.get
        oqc.get = fake_get
        try:
            rows = oqc.trade_rows(syms, {}, "2026-10-26T10:00:00-04:00", "2026-10-26T10:01:00-04:00", {}, 20)
        finally:
            oqc.get = orig
        self.assertEqual([len(c["symbols"][0].split(",")) for c in calls], [100, 100, 100, 50])  # chunk 1 paged once
        self.assertEqual([(c["start"][0], c["end"][0]) for c in calls][0], ("2026-10-26T10:00:00-04:00", "2026-10-26T10:01:00-04:00"))
        self.assertEqual([(r["sym"], r["s"]) for r in rows], [("S000", 30), ("S001", 40)])


class OiRowsTest(unittest.TestCase):
    def test_shapes_contracts_and_keeps_missing_oi_as_none(self):
        rows = oi.oi_rows([{"symbol": "AMD261121C00250000", "open_interest": "1234", "open_interest_date": "2026-10-23",
                            "strike_price": "250", "expiration_date": "2026-11-21", "type": "call", "close_price": "3.1",
                            "close_price_date": "2026-10-23"},
                           {"symbol": "AMD261121P00200000", "strike_price": "200", "expiration_date": "2026-11-21", "type": "put"}],
                          "2026-10-26")
        self.assertEqual(rows[0], {"d": "2026-10-26", "sym": "AMD261121C00250000", "oi": 1234, "oi_date": "2026-10-23", "strike": 250.0,
                                   "expiry": "2026-11-21", "type": "call", "close": 3.1, "close_date": "2026-10-23"})
        self.assertIsNone(rows[1]["oi"]); self.assertIsNone(rows[1]["close"])


class OiMainTest(unittest.TestCase):
    """A same-day re-run replaces the day's file (no doubled rows); a failed underlying exits non-zero."""

    def _main(self, tmp, fake_contracts):
        import contextlib, io, os
        uni, env = os.path.join(tmp, "u.txt"), os.path.join(tmp, ".env")
        with open(uni, "w") as f:
            f.write("AMD\nNVDA\n")
        with open(env, "w") as f:
            f.write("APCA_API_KEY_ID_DATA=k\nAPCA_API_SECRET_KEY_DATA=s\n")
        orig_contracts, orig_argv = oi.contracts, sys.argv
        oi.contracts, sys.argv = fake_contracts, ["oi", "--out", os.path.join(tmp, "out"), "--universe-file", uni, "--env", env]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                oi.main()
        finally:
            oi.contracts, sys.argv = orig_contracts, orig_argv

    def _files(self, tmp):
        import gzip, json, os
        day = oi.datetime.now(oi.ET).date()
        return {u: [json.loads(l) for l in gzip.open(os.path.join(tmp, "out", u, f"{day}.ndjson.gz"), "rt")]
                for u in ("AMD", "NVDA") if os.path.exists(os.path.join(tmp, "out", u, f"{day}.ndjson.gz"))}

    def test_rerun_replaces_day_file(self):
        import tempfile
        one = lambda u, headers, today, days: [{"symbol": f"{u}1", "open_interest": "7"}]
        with tempfile.TemporaryDirectory() as tmp:
            self._main(tmp, one)
            self._main(tmp, one)
            files = self._files(tmp)
        self.assertEqual({u: len(rows) for u, rows in files.items()}, {"AMD": 1, "NVDA": 1})
        self.assertEqual(files["AMD"][0]["oi"], 7)

    def test_failed_underlying_exits_nonzero_but_others_are_written(self):
        import io, tempfile, contextlib

        def flaky(u, headers, today, days):
            if u == "AMD":
                raise RuntimeError("boom")
            return [{"symbol": f"{u}1"}]

        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as cm:
                self._main(tmp, flaky)
            files = self._files(tmp)
        self.assertEqual(cm.exception.code, 1)
        self.assertEqual(sorted(files), ["NVDA"])

    def _raw(self, tmp):
        day = oi.datetime.now(oi.ET).date()
        return {u: (pathlib.Path(tmp) / "out" / u / f"{day}.ndjson.gz").read_bytes() for u in ("AMD", "NVDA")}

    def test_oi_main_zero_rows_keeps_file_and_exits_1(self):
        """A transient empty /v2/options/contracts response must not replace a good day's snapshot (#998)."""
        import io, tempfile, contextlib
        one = lambda u, headers, today, days: [{"symbol": f"{u}1", "open_interest": "7"}]
        empty = lambda u, headers, today, days: []
        with tempfile.TemporaryDirectory() as tmp:
            self._main(tmp, one)
            before = self._raw(tmp)
            err = io.StringIO()
            with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
                self._main(tmp, empty)
            after = self._raw(tmp)
        self.assertEqual(cm.exception.code, 1)
        self.assertEqual(after, before)
        self.assertIn("0 contracts", err.getvalue()); self.assertIn("keeping", err.getvalue())

    def test_oi_tmp_removed_on_write_error(self):
        import io, os, tempfile, contextlib, types
        one = lambda u, headers, today, days: [{"symbol": f"{u}1"}]

        def boom(_r):
            raise OSError("disk full")

        orig_json = oi.json
        oi.json = types.SimpleNamespace(dumps=boom)  # fails after the .tmp is created
        try:
            with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as cm:
                    self._main(tmp, one)
                leftovers = [p for u in ("AMD", "NVDA") for p in os.listdir(os.path.join(tmp, "out", u))]
        finally:
            oi.json = orig_json
        self.assertEqual(cm.exception.code, 1)
        self.assertEqual(leftovers, [])


class WatchlistTickersTest(unittest.TestCase):
    def test_parses_psql_output(self):
        cmd = f'{sys.executable} -c "import sys; sys.stdin.read(); print(\'SPY\'); print(\'\'); print(\'AMD\')"'
        self.assertEqual(oqc.watchlist_tickers(cmd, DAY), ["AMD", "SPY"])


if __name__ == "__main__":
    unittest.main()
