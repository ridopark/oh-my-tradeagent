#!/usr/bin/env python3
"""Tests for `scripts/research/watchlist_forward_ml.py`: v1 rule parity, feature definitions, the frozen context, the look-date guard,
the score_day write path (with `predict` stubbed), and the amended analysis (`watchlist_forward_ml_analysis.py`)."""
import datetime as dt, gzip, importlib.util, json, pathlib, sys, tempfile, unittest
import numpy as np, pandas as pd

HERE = pathlib.Path(__file__).resolve().parent.parent
def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py"); m = importlib.util.module_from_spec(spec); sys.modules[name] = m; spec.loader.exec_module(m); return m
ml = load("watchlist_forward_ml"); wf = load("watchlist_forward"); mla = load("watchlist_forward_ml_analysis")

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
        self.assertAlmostEqual(f["nearest_std_bps"], abs(100.4 / 100.5 - 1) * 1e4)   # PMH is the nearest of PDH/PDL/PMH/PML; no H5/L5
        old = sess([100] * 390); old["h"] = np.full(390, 100.41)                   # with 5 sessions the 5-session high (oldest) is nearer
        f5 = ml.features(S, [old] + [sess([100] * 390)] * 4, None, [1, 2, 3], 100.4, 1, 20, dt.date(2026, 10, 27), np.nan)
        self.assertAlmostEqual(f5["nearest_std_bps"], abs(100.4 / 100.41 - 1) * 1e4)

class FrozenTest(unittest.TestCase):
    def test_frozen_context_matches_manifest(self):
        import hashlib
        fz = HERE / "mlfwd"; meta = json.load(open(fz / "FROZEN.json"))
        self.assertEqual(hashlib.sha256(open(fz / "ml_context_v1.parquet", "rb").read()).hexdigest(), meta["sha256_context"])
        self.assertEqual(meta["features_24"], ml.F24); self.assertEqual(meta["context_rows"], 219)
    def test_analyze_refuses_before_look_date(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit): ml.analyze(d, dt.date(2027, 4, 22))

# ---------- score_day on a synthetic raw folder, with the models stubbed ----------
DAY = dt.date(2026, 10, 23); PRIOR = ["2026-10-16", "2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22"]

def day_bars(s, d, closes):
    return [{"s": s, "d": d, "m": k, "o": c, "h": c, "l": c, "c": c, "v": 1.0} for k, c in enumerate(closes)]

def write_raw(out, legs, bars):
    folder = pathlib.Path(out, "raw", DAY.isoformat()); folder.mkdir(parents=True, exist_ok=True)
    json.dump({"legs": legs, "watchlist_daily": None}, open(folder / "legs.json", "w"))
    with gzip.open(folder / "stock_1m.ndjson.gz", "wt") as f:
        for b in bars: f.write(json.dumps(b) + "\n")

def frozen_dir(tmp):
    fz = pathlib.Path(tmp, "frozen"); fz.mkdir()
    days = pd.bdate_range(end="2026-10-22", periods=15).strftime("%Y-%m-%d")
    pd.DataFrame({"d": days, "close": np.linspace(740, 760, 15)}).to_csv(fz / "spy_closes_seed.csv", index=False)
    return str(fz)

def stub_predict(X, frozen):
    """Deterministic stand-in for the models: probability = level / 1000, so attribution is checkable."""
    p = X.lvl.values / 1000
    return pd.DataFrame({"d": X.d.values, "s": X.s.values, "dir": X.dir.values, "p_tabpfn24": p, "p_gbm14": p,
                         "pick_tabpfn24": (p > ml.PICK).astype(float), "pick_gbm14": (p > ml.PICK).astype(float)})

def leg(s, dir_, lvl, armed="2026-10-23T13:20:00+00:00"):
    return {"s": s, "dir": dir_, "lvl": lvl, "armed_at": armed}

class ScoreDayTest(unittest.TestCase):
    def setUp(self):
        self._predict, self._earn = ml.predict, ml.earnings_flags
        ml.predict = stub_predict; ml.earnings_flags = lambda day, tickers: {t: 0.0 for t in tickers}
    def tearDown(self):
        ml.predict, ml.earnings_flags = self._predict, self._earn
    def history(self, s):
        return [b for d in PRIOR for b in day_bars(s, d, [98] * 390)]
    def test_scores_fired_legs_only_and_writes_the_day_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, fz = str(pathlib.Path(tmp, "out")), frozen_dir(tmp)
            fired = [99] * 10 + [100.5] * 60 + [101] * 320
            write_raw(out, [leg("ACME", "ABOVE", 600.0), leg("NOPE", "ABOVE", 100.0), leg("LATE", "ABOVE", 100.0, "2026-10-23T13:31:00+00:00")],
                      day_bars("ACME", DAY.isoformat(), [c * 6 for c in fired]) + self.history("ACME")
                      + day_bars("NOPE", DAY.isoformat(), [99] * 390) + self.history("NOPE")
                      + day_bars("LATE", DAY.isoformat(), [99] * 390) + day_bars("SPY", DAY.isoformat(), [760] * 390))
            R = ml.score_day(DAY, out, fz).set_index("s")
            self.assertEqual(R.loc["ACME", "status"], "fired"); self.assertEqual(R.loc["ACME", "scored"], 1)
            self.assertAlmostEqual(R.loc["ACME", "p_gbm14"], 0.6); self.assertEqual(R.loc["ACME", "pick_gbm14"], 1.0)
            self.assertAlmostEqual(R.loc["ACME", "r60"], 101 / 100.5 - 1)
            self.assertEqual(R.loc["NOPE", "status"], "no_trigger"); self.assertTrue(np.isnan(R.loc["NOPE", "p_gbm14"]))
            self.assertEqual(R.loc["LATE", "status"], "armed_after_open")
            written = pd.read_csv(pathlib.Path(out, "ml_scores.csv"))
            self.assertEqual(len(written), 3); self.assertEqual(list(written.columns[:4]), ["d", "s", "dir", "lvl"])
            extra = pd.read_csv(pathlib.Path(out, "ml_spy_closes.csv"))                  # only the new day's SPY close, never the seed
            self.assertEqual(extra.d.tolist(), [DAY.isoformat()]); self.assertEqual(extra.close.tolist(), [760.0])
            ml.score_day(DAY, out, fz)                                                      # a re-run replaces the day's rows (amendment A3)
            self.assertEqual(len(pd.read_csv(pathlib.Path(out, "ml_scores.csv"))), 3)
    def test_two_levels_one_direction_cross_join_is_the_frozen_behaviour(self):
        """Amendment A1 pins this: the frozen join key is (d, s, dir), so two fired levels on one symbol+direction
        give 4 rows. The analysis excludes the group. If the scorer is ever re-tagged with the fix, update A1 and this test."""
        with tempfile.TemporaryDirectory() as tmp:
            out, fz = str(pathlib.Path(tmp, "out")), frozen_dir(tmp)
            fired = [99] * 10 + [100.5] * 60 + [101] * 320
            write_raw(out, [leg("ACME", "ABOVE", 100.0), leg("ACME", "ABOVE", 100.2)],
                      day_bars("ACME", DAY.isoformat(), fired) + self.history("ACME") + day_bars("SPY", DAY.isoformat(), [760] * 390))
            R = ml.score_day(DAY, out, fz)
            self.assertEqual(len(R), 4); self.assertEqual(sorted(R.lvl.unique()), [100.0, 100.2])
            kept, excluded = mla.load_scores(out)
            self.assertEqual((len(kept), excluded), (0, 1))

class SpyClosesTest(unittest.TestCase):
    def test_seed_plus_extra_strictly_before_day(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, fz = str(pathlib.Path(tmp, "out")), frozen_dir(tmp); pathlib.Path(out).mkdir()
            pd.DataFrame({"d": ["2026-10-23"], "close": [761.0]}).to_csv(pathlib.Path(out, "ml_spy_closes.csv"), index=False)
            before, allc = ml.spy_closes(out, fz, dt.date(2026, 10, 23))
            self.assertEqual((len(before), len(allc)), (15, 16)); self.assertAlmostEqual(before[-1], 760.0)
            self.assertEqual(ml.spy_closes(out, fz, dt.date(2026, 10, 24))[0][-1], 761.0)

# ---------- the amended analysis (A7) on synthetic scores ----------
COLS = ["d", "s", "dir", "lvl", "status", "trig_m", "r60", "scored", "p_tabpfn24", "p_gbm14", "pick_tabpfn24", "pick_gbm14", "earnings_day"]

def synthetic(days, skill, m2_pick_r60, m1_p=0.4):
    """Two scored legs per day. M2's probability tracks the sign of r60 when `skill`; M1 never picks."""
    rows = []
    for i in range(days):
        d = f"2026-{11 + i // 28:02d}-{1 + i % 28:02d}"
        for k, s in enumerate(("SPY", "QQQ")):
            up = (i + k) % 2 == 0
            r60 = (m2_pick_r60 if up else -abs(m2_pick_r60) / 2) + ((i * 37 + k * 11) % 11 - 5) * 1e-4
            p2 = (0.7 if (r60 > 0) == skill else 0.3)
            rows.append(dict(d=d, s=s, dir="ABOVE", lvl=100.0, status="fired", trig_m=30, r60=r60, scored=1,
                             p_tabpfn24=m1_p, p_gbm14=p2, pick_tabpfn24=float(m1_p > ml.PICK), pick_gbm14=float(p2 > ml.PICK), earnings_day=0.0))
    return rows

class AnalyzeDecisionTest(unittest.TestCase):
    ON = dt.date(2027, 4, 23)
    def run_analyze(self, rows):
        with tempfile.TemporaryDirectory() as d:
            pd.DataFrame(rows, columns=COLS).to_csv(pathlib.Path(d, "ml_scores.csv"), index=False)
            return mla.analyze(d, self.ON, reps=2000, n_perm=300)
    def test_skilled_picks_pass_and_no_pick_model_is_not_shown(self):
        res = self.run_analyze(synthetic(60, True, 0.0060))
        self.assertEqual(res["M2"]["decision"], "PASS"); self.assertGreater(res["M2"]["auc"], res["M2"]["null95"])
        self.assertEqual((res["M1"]["decision"], res["M1"]["picks"], res["M1"]["p"]), ("NOT_SHOWN", 0, None))
        self.assertAlmostEqual(res["M2"]["p_holm"], res["M2"]["p"], places=10)      # M1 has no p: the Holm family is M2 alone
    def test_negative_picks_are_kill_and_null_is_not_shown(self):
        res = self.run_analyze(synthetic(60, False, 0.0060))                          # picks land on the losers
        self.assertEqual(res["M2"]["decision"], "KILL")
        rows = synthetic(60, True, 0.0001)                                             # no effect: tiny r60, noise dominates
        for i, r in enumerate(rows): r["p_gbm14"] = 0.3 + 0.4 * ((i * 7) % 5) / 4; r["pick_gbm14"] = float(r["p_gbm14"] > ml.PICK)
        self.assertEqual(self.run_analyze(rows)["M2"]["decision"], "NOT_SHOWN")
    def test_refuses_before_look_date(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit): mla.analyze(d, dt.date(2027, 4, 22))
    def test_auc_perm_needs_two_classes(self):
        self.assertEqual(mla.auc_perm(np.ones(5, int), np.arange(5) / 5, 10), (None, None, None))

if __name__ == "__main__": unittest.main()
