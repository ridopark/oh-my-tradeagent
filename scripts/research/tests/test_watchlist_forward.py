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

_aspec = importlib.util.spec_from_file_location("watchlist_forward_analysis", SCRIPT_PATH.parent / "watchlist_forward_analysis.py")
wfa = importlib.util.module_from_spec(_aspec)
sys.modules["watchlist_forward_analysis"] = wfa
_aspec.loader.exec_module(wfa)  # type: ignore[union-attr]


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


def write_day(out, day, legs, stock, files=("legs.json", "stock_1m.ndjson.gz")):
    folder = pathlib.Path(out, "raw", day)
    folder.mkdir(parents=True, exist_ok=True)
    if "legs.json" in files:
        (folder / "legs.json").write_text(wf.json.dumps({"legs": legs, "watchlist_daily": None}))
    if "stock_1m.ndjson.gz" in files:
        wf.write_gz(str(folder / "stock_1m.ndjson.gz"), stock)
    wf.manifest(str(folder))


class CheckTest(unittest.TestCase):
    def test_flow_only_day_is_not_captured(self):
        with tempfile.TemporaryDirectory() as d:
            folder = pathlib.Path(d, "raw", "2026-10-23"); folder.mkdir(parents=True)
            wf.write_gz(str(folder / "flow.ndjson.gz"), [])
            wf.manifest(str(folder))
            self.assertEqual(wfa.missing_days(d, ["2026-10-23"]), ["2026-10-23"])
            write_day(d, "2026-10-23", [], [])
            self.assertEqual(wfa.missing_days(d, ["2026-10-23"]), [])

    def test_explained_gaps_need_a_reason(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d, "explained_gaps.json")
            p.write_text('{"2026-10-26": "homelab power outage"}')
            self.assertEqual(wfa.missing_days(d, ["2026-10-26", "2026-10-27"]), ["2026-10-27"])
            p.write_text('["2026-10-26"]')
            with self.assertRaises(SystemExit):
                wfa.missing_days(d, ["2026-10-26"])
            p.write_text('{"2026-10-26": ""}')
            with self.assertRaises(SystemExit):
                wfa.missing_days(d, ["2026-10-26"])


def day_bars(sym, day, closes, opens=None):
    return [dict(b, s=sym, d=day, v=1) for b in bars(closes, opens=opens)]


class ScoreDayTest(unittest.TestCase):
    def test_armed_after_open_excluded_and_four_placebos(self):
        with tempfile.TemporaryDirectory() as d:
            legs = [{"s": "SPY", "dir": "ABOVE", "lvl": 100.0, "armed_at": "2026-10-23T13:20:00+00:00"},
                    {"s": "QQQ", "dir": "ABOVE", "lvl": 100.0, "armed_at": "2026-10-23T13:31:00+00:00"}]
            stock = day_bars("SPY", "2026-10-23", [99] * 10 + [100.5] * 380) + day_bars("QQQ", "2026-10-23", [99] * 390)
            write_day(d, "2026-10-23", legs, stock)
            rows = wf.score_day(dt.date(2026, 10, 23), d)
            spy = [r for r in rows if r["s"] == "SPY"]
            qqq = [r for r in rows if r["s"] == "QQQ"]
            self.assertEqual([r["status"] for r in qqq], ["armed_after_open"])   # 09:31 ET: excluded, no placebos
            self.assertEqual(sum(r["arm"] == "real" for r in spy), 1)
            self.assertEqual(sum(r["arm"].startswith("placebo") for r in spy), 4)

    def test_append_scores_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            rows = [{"d": "2026-10-23", "s": "SPY", "dir": "ABOVE", "lvl": 100.0, "arm": "real", "status": "fired", "r60": 0.01}]
            wf.append_scores(d, dt.date(2026, 10, 23), rows)
            first = pathlib.Path(d, "scores.csv").read_text()
            wf.append_scores(d, dt.date(2026, 10, 23), rows)
            self.assertEqual(pathlib.Path(d, "scores.csv").read_text(), first)


def scores_csv(path, rows):
    import csv
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=wf.COLS)
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in wf.COLS})


def synthetic(days, real_r60, plac_r60, f2=None, tags=None):
    """One real + one placebo fired leg per day, with fixed outcomes (+ small day noise)."""
    rows = []
    for i in range(days):
        d = f"2026-{11 + i // 28:02d}-{1 + i % 28:02d}"
        noise, pnoise = ((i * 37) % 11 - 5) * 1e-4, ((i * 53) % 13 - 6) * 1e-4
        tg = tags[i % len(tags)] if tags else ""
        r = real_r60[tg] if isinstance(real_r60, dict) else real_r60
        rows.append({"d": d, "s": "SPY", "dir": "ABOVE", "lvl": 100, "arm": "real", "status": "fired", "trig_m": 30,
                     "r60": r + noise, "f2_net": "" if f2 is None else f2 + noise, "f3_tag": tg})
        rows.append({"d": d, "s": "SPY", "dir": "ABOVE", "lvl": 101, "arm": "placebo+0.010", "status": "fired",
                     "trig_m": 40, "r60": plac_r60 + pnoise})
    return rows


class AnalyzeDecisionTest(unittest.TestCase):
    ON = dt.date(2027, 4, 23)

    def run_analyze(self, rows):
        with tempfile.TemporaryDirectory() as d:
            scores_csv(pathlib.Path(d, "scores.csv"), rows)
            return wfa.analyze(d, self.ON, reps=2000)

    def test_holm_step_down(self):
        self.assertEqual(wfa.holm({"F1": 0.01, "F2": 0.04, "F3": 0.03}), {"F1": 0.03, "F2": 0.06, "F3": 0.06})
        self.assertEqual(wfa.holm({"F1": 0.5, "F2": None}), {"F1": 0.5, "F2": None})

    def test_strong_effects_pass_and_null_is_not_shown(self):
        res = self.run_analyze(synthetic(60, 0.0060, 0.0, f2=0.0040))
        self.assertEqual(res["F1"]["decision"], "PASS")
        self.assertEqual(res["F2"]["decision"], "PASS")
        res = self.run_analyze(synthetic(60, 0.00002, 0.0, f2=0.00002))
        self.assertEqual(res["F1"]["decision"], "NOT_SHOWN")

    def test_negative_metric_is_kill(self):
        res = self.run_analyze(synthetic(60, -0.0030, 0.0, f2=-0.0010))
        self.assertEqual((res["F1"]["decision"], res["F2"]["decision"]), ("KILL", "KILL"))

    def test_f3_difference_and_disagree_gate(self):
        tags = ["AGREE", "SILENT", "SILENT", "DISAGREE"]
        res = self.run_analyze(synthetic(80, {"AGREE": 0.0080, "SILENT": 0.0, "DISAGREE": 0.0010}, 0.0, tags=tags))
        self.assertAlmostEqual(res["F3"]["metric"], 0.0080, places=3)
        self.assertEqual(res["F3"]["decision"], "PASS")
        res = self.run_analyze(synthetic(80, {"AGREE": 0.0080, "SILENT": 0.0, "DISAGREE": 0.0090}, 0.0, tags=tags))
        self.assertNotEqual(res["F3"]["decision"], "PASS")                  # AGREE - DISAGREE <= 0 blocks a PASS


class AnalysisGuardTest(unittest.TestCase):
    def test_amended_analyze_refuses_before_analysis_date(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
            wfa.analyze(d, dt.date(2027, 4, 22))

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
