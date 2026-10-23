#!/usr/bin/env python3
"""Tests for `scripts/research/watchlist_forward.py` — the pre-registered scoring rules.

Run standalone:
    python3 -m unittest discover -s scripts/research/tests
"""
import datetime as dt
import importlib.util
import pathlib
import sys
import tempfile
import unittest
from zoneinfo import ZoneInfo

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent.parent / "watchlist_forward.py"
_spec = importlib.util.spec_from_file_location("watchlist_forward", SCRIPT_PATH)
assert _spec and _spec.loader, f"could not load {SCRIPT_PATH}"
wf = importlib.util.module_from_spec(_spec)
sys.modules["watchlist_forward"] = wf
_spec.loader.exec_module(wf)  # type: ignore[union-attr]


def bars(closes, start_m=0, opens=None, highs=None, lows=None):
    out = []
    for k, c in enumerate(closes):
        o = opens[k] if opens else c
        out.append({"m": start_m + k, "o": o, "c": c,
                    "h": highs[k] if highs else max(o, c), "l": lows[k] if lows else min(o, c)})
    return out


class SnowflakeTest(unittest.TestCase):
    def test_watchlist_post_time(self):
        # 2026-10-22 watchlist message; known to be posted just before 09:21 ET.
        t = wf.snowflake_time("1562817935255273506").astimezone(ZoneInfo("America/New_York"))
        self.assertEqual((t.date(), t.hour, t.minute), (dt.date(2026, 10, 22), 9, 20))


class BreakOutcomeTest(unittest.TestCase):
    def test_gap_open_beyond_level_is_excluded(self):
        self.assertEqual(wf.break_outcome(bars([101] * 100, opens=[101] * 100), 100, 1)[0], "gap")

    def test_no_trigger(self):
        self.assertEqual(wf.break_outcome(bars([99] * 100), 100, 1)[0], "no_trigger")

    def test_fired_long_r60_and_close(self):
        closes = [99] * 10 + [100.5] + [101.5] * 379
        closes[70] = 102.0                            # exactly trigger + 60 minutes
        closes[386:] = [99.0] * 4                     # after the 15:55 bar: must not count
        st, tm, r60, rc = wf.break_outcome(bars(closes), 100, 1)
        self.assertEqual((st, tm), ("fired", 10))
        self.assertAlmostEqual(r60, 1.5 / 100.5)
        self.assertAlmostEqual(rc, 1.0 / 100.5)

    def test_short_side_sign_and_missing_r60(self):
        closes = [101] * 350 + [99.5] + [99.0] * 39   # triggers at m=350; no bar at m>=410
        st, tm, r60, rc = wf.break_outcome(bars(closes), 100, -1)
        self.assertEqual((st, tm, r60), ("fired", 350, None))
        self.assertAlmostEqual(rc, 0.5 / 99.5)       # 15:55 bar is m=385, still 99.0


class F2Test(unittest.TestCase):
    def test_target_hit_long_etf(self):
        # 5m closes: 99.9 (m4), 100.2 (m9) -> entry 100.2; candidate target 100.5 reached later.
        closes = [99.9] * 5 + [100.2] * 5 + [100.3] * 10 + [100.6] * 370
        r = wf.f2_trade("SPY", bars(closes), 100, 1, [100.5, None, None, None, None, None])
        self.assertAlmostEqual(r, (100.5 - 100.2) / 100.2 - 1e-4)

    def test_stop_on_5m_close_back_through(self):
        # back below the level on the next 5m close, then recovers: the stop must fire at 99.8
        closes = [99.9] * 5 + [100.2] * 5 + [99.8] * 5 + [101.0] * 375
        r = wf.f2_trade("NVDA", bars(closes), 100, 1, [None] * 6)
        self.assertAlmostEqual(r, (99.8 - 100.2) / 100.2 - 3e-4)

    def test_no_entry_when_gap(self):
        self.assertIsNone(wf.f2_trade("SPY", bars([101] * 390, opens=[101] * 390), 100, 1, [None] * 6))


class FlowTest(unittest.TestCase):
    def test_needs_15_nonzero_sessions(self):
        self.assertIsNone(wf.flow_z(10, [0] * 6 + [5] * 14))

    def test_z_and_tags(self):
        hist = [10, 20] * 10
        z = wf.flow_z(30, hist)
        self.assertGreater(z, 2)
        self.assertEqual(wf.tag(z), "AGREE")
        self.assertEqual(wf.tag(-z), "DISAGREE")
        self.assertEqual(wf.tag(0.2), "SILENT")
        self.assertEqual(wf.tag(1.0), "AGREE")       # threshold is inclusive at exactly 1
        self.assertEqual(wf.tag(0.99), "SILENT")
        self.assertEqual(wf.tag(-1.0), "DISAGREE")
        self.assertIsNone(wf.tag(None))


class AnalysisGuardTest(unittest.TestCase):
    def test_refuses_before_analysis_date(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
            wf.analyze(d, dt.date(2027, 4, 22))

    def test_cluster_boot_deterministic(self):
        rows = [{"d": f"d{i % 5}", "v": float(i)} for i in range(50)]
        a = wf.cluster_boot(rows, "d", lambda r: r["v"], reps=500)
        self.assertEqual(a, wf.cluster_boot(rows, "d", lambda r: r["v"], reps=500))
        self.assertAlmostEqual(a[0], 24.5)


if __name__ == "__main__":
    unittest.main()
