#!/usr/bin/env python3
"""Tests for `scripts/research/watchlist_forward_ml.py`: v1 rule parity, feature definitions, the frozen context, the look-date guard."""
import datetime as dt, importlib.util, json, pathlib, sys, tempfile, unittest
import numpy as np

HERE = pathlib.Path(__file__).resolve().parent.parent
def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py"); m = importlib.util.module_from_spec(spec); sys.modules[name] = m; spec.loader.exec_module(m); return m
ml = load("watchlist_forward_ml"); wf = load("watchlist_forward")

def sess(closes, start=0):
    c = np.array(closes, float); m = np.arange(start, start + len(c))
    return dict(m=m, o=c, h=c, l=c, c=c, v=np.ones(len(c)), PMH=np.nan, PML=np.nan, etf=False)

class V1ParityTest(unittest.TestCase):
    def test_trigger_matches_frozen_v1_rule(self):
        closes = [99] * 10 + [100.5] + [101.5] * 379; closes[70] = 102.0
        S = sess(closes); st, i, r60 = ml.v1_trigger(S, 100, 1)
        bars = [{"m": int(S["m"][k]), "o": S["o"][k], "h": S["h"][k], "l": S["l"][k], "c": S["c"][k]} for k in range(len(closes))]
        st1, tm, r60_v1, _ = wf.break_outcome(bars, 100, 1)
        self.assertEqual((st, i), ("fired", 10)); self.assertEqual((st1, tm), ("fired", 10)); self.assertAlmostEqual(r60, r60_v1)
    def test_gap_and_no_trigger(self):
        self.assertEqual(ml.v1_trigger(sess([101] * 390), 100, 1)[0], "gap")
        self.assertEqual(ml.v1_trigger(sess([99] * 390), 100, 1)[0], "no_trigger")

class FeatureTest(unittest.TestCase):
    def test_roundness(self):
        self.assertEqual([ml.roundness(v) for v in (770, 765, 762.5, 763, 763.5, 763.75)], [10, 5, 2.5, 1, 0.5, 0.0])
    def test_features_are_pre_trigger_and_prior_day_spy(self):
        S = sess([100] * 20 + [101] * 370); S["PMH"], S["PML"] = 100.5, 99.0
        prev = sess([98, 99, 100, 101, 100] * 78); prevs = [prev] * 5
        f = ml.features(S, prevs, sess([500] * 390), [490, 495, 500], 100.4, 1, 20, dt.date(2026, 10, 26), 0.0)   # 2026-10-26 is a Monday
        self.assertAlmostEqual(f["spy_prev"], 500 / 495 - 1)            # prior session's SPY return, never the same day's
        self.assertAlmostEqual(f["mom5"], 101 / 100 - 1); self.assertEqual(f["dow"], 0.0); self.assertEqual(f["round"], 0.0)
        self.assertAlmostEqual(f["dist_PMH"], (100.4 / 100.5 - 1) * 1e4)
        self.assertIsNone(ml.features(S, prevs, None, [1, 2], 100.4, 1, 3, dt.date(2026, 10, 27), 0.0))   # i<5: out of distribution
    def test_five_session_levels_undefined_early(self):
        S = sess([100] * 20 + [101] * 370); S["PMH"], S["PML"] = 100.5, 99.0
        f = ml.features(S, [sess([100] * 390)] * 3, None, [1, 2, 3], 100.4, 1, 20, dt.date(2026, 10, 27), np.nan)
        self.assertTrue(np.isfinite(f["nearest_std_bps"]))             # PDH/PDL/PMH/PML still defined

class FrozenTest(unittest.TestCase):
    def test_frozen_context_matches_manifest(self):
        import hashlib
        fz = HERE / "mlfwd"; meta = json.load(open(fz / "FROZEN.json"))
        self.assertEqual(hashlib.sha256(open(fz / "ml_context_v1.parquet", "rb").read()).hexdigest(), meta["sha256_context"])
        self.assertEqual(meta["features_24"], ml.F24); self.assertEqual(meta["context_rows"], 219)
    def test_analyze_refuses_before_look_date(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit): ml.analyze(d, dt.date(2027, 4, 22))

if __name__ == "__main__": unittest.main()
