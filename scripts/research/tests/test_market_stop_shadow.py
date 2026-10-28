"""Unit tests for market_stop_shadow.py (pure rule functions + check/analyze gates). Run: python3 -B -m unittest scripts/research/tests/test_market_stop_shadow.py (not in CI)"""
import json, gzip, os, sys, tempfile, unittest
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import market_stop_shadow as m

ET = ZoneInfo("America/New_York")
D = date(2026, 11, 2)   # a Monday after START
def t(hhmm, d=D): return datetime.combine(d, datetime.strptime(hhmm, "%H:%M").time(), ET)
def qqq_series(ref, path, d=D, start="09:30"):
    """QQQ 1m bars starting at `start`, closes = ref*(1+pct) for each pct in path."""
    t0 = t(start, d); return [{"t": t0 + timedelta(minutes=i), "c": ref * (1 + p)} for i, p in enumerate(path)]
def opt_bars(sym, opens, d=D, start="09:30"):
    t0 = t(start, d); return {m.occ(sym)["compact"]: [{"t": t0 + timedelta(minutes=i), "o": o, "c": o} for i, o in enumerate(opens)]}
CALL = "SPY   261106C00700000"; PUT = "SPY   261106P00700000"
def fill(side, qty, px, when, key="k", sym=CALL):
    return {"intent_key": key, "option_symbol": sym, "side": side, "filled_qty": qty, "avg_fill_price": px, "filled_at": when.isoformat(), "detected_via": "ws", "qty": qty, "limit_price": px}

class Occ(unittest.TestCase):
    def test_parse(self):
        c = m.occ(CALL); self.assertEqual((c["root"], c["expiry"], c["kind"], c["strike"], c["compact"]), ("SPY", date(2026, 11, 6), "C", 700.0, "SPY261106C00700000"))

class Lots(unittest.TestCase):
    def test_fifo_and_population_start(self):
        before = fill("BUY", 5, 1.0, datetime(2026, 10, 27, 10, 0, tzinfo=ET), "old")
        b1 = fill("BUY", 3, 1.0, t("10:00"), "a"); b2 = fill("BUY", 2, 1.2, t("10:05"), "b"); s = fill("SELL", 4, 1.5, t("11:00"), "s")
        lots = m.build_lots([before, b1, b2, s])
        self.assertEqual([l["id"] for l in lots], ["a", "b"])                      # the pre-START fill is not in the population
        self.assertEqual([x["qty"] for x in lots[0]["sells"]], [3]); self.assertEqual([x["qty"] for x in lots[1]["sells"]], [1])

class Reference(unittest.TestCase):
    def test_last_complete_bar_before_entry_minute(self):
        q = qqq_series(100, [0, 0.001, 0.002, 0.003])        # bars start 09:30..09:33
        self.assertAlmostEqual(m.reference_close(q, t("09:32").replace(second=30)), 100 * 1.001)   # bar 09:31 is the last that STARTS before 09:32
        self.assertIsNone(m.reference_close(q, t("09:30")))                                # nothing starts before the first bar
    def test_premarket_entry_uses_prior_session_close(self):
        prev = qqq_series(100, [0, 0.01], d=D - timedelta(days=3), start="15:58")
        self.assertAlmostEqual(m.reference_close(prev, t("08:00")), 101.0)

class Roll(unittest.TestCase):
    def test_floor_when_few_closes_or_no_negative_autocov(self):
        self.assertEqual(m.roll_hs([1, 1.1, 1.2], 1.0), m.HS_FLOOR)
        self.assertEqual(m.roll_hs([1, 1.1, 1.2, 1.3, 1.4, 1.5], 1.0), m.HS_FLOOR)   # trending: cov>0 -> 0 -> floor
    def test_bid_ask_bounce_gives_half_spread(self):
        import random; random.seed(7)
        closes = [1.00 + 0.10 * random.randint(0, 1) for _ in range(4000)]   # Roll model: random bid/ask hits, spread 0.10 -> half-spread 0.05
        self.assertAlmostEqual(m.roll_hs(closes, 1.0), 0.05, places=2)
        self.assertAlmostEqual(m.roll_hs([1.0, 1.1] * 4, 1.0), 0.10, places=6)   # deterministic alternation: cov = -S^2 -> sqrt = S (formula check)

class Arms(unittest.TestCase):
    def setUp(self):
        self.und = {("SPY", "2026-11-06"): 700.0}
    def test_call_triggers_on_qqq_drop_and_fills_next_option_bar_open(self):
        q = qqq_series(100, [0, 0, -0.002, -0.006, -0.01] + [-0.01] * 60)             # trigger at the 09:33 bar (−0.6%)
        bars = opt_bars(CALL, [2.0, 2.0, 2.0, 1.9, 1.8, 1.7] + [1.6] * 60)             # exit = first bar after 09:33 -> 09:34 open 1.8
        lot = m.build_lots([fill("BUY", 2, 2.0, t("09:30").replace(second=20))])[0]  # entry 09:30:20 -> ref = bar before 09:30 -> none in-session
        q = qqq_series(100, [0], d=D - timedelta(days=3), start="15:59") + q        # add a prior close so ref=100
        ev, info = m.arm_pnl(lot, bars, self.und, q, D, shadow=True)
        self.assertEqual(info["trigger"]["t"], t("09:33").isoformat()); self.assertEqual(info["shadow_exit"]["open"], 1.8)
        px = 1.8 * (1 - m.HS_FLOOR); self.assertAlmostEqual(sum(v for _, v in ev), (px - 2.0) * 2 * 100)
    def test_put_triggers_on_qqq_rise(self):
        q = qqq_series(100, [0], d=D - timedelta(days=3), start="15:59") + qqq_series(100, [0, 0.003, 0.006] + [0.006] * 60)
        bars = opt_bars(PUT, [2.0] * 70); lot = m.build_lots([fill("BUY", 1, 2.0, t("09:30"), sym=PUT)])[0]
        ev, info = m.arm_pnl(lot, bars, self.und, q, D, shadow=True); self.assertEqual(info["trigger"]["t"], t("09:32").isoformat())
    def test_actual_sell_before_trigger_counts_after_is_ignored(self):
        q = qqq_series(100, [0], d=D - timedelta(days=3), start="15:59") + qqq_series(100, [0, 0, 0, -0.01] + [-0.01] * 60)
        bars = opt_bars(CALL, [2.0] * 70)
        f = [fill("BUY", 4, 2.0, t("09:30")), fill("SELL", 1, 2.5, t("09:31"), "s1"), fill("SELL", 1, 3.0, t("09:40"), "s2")]
        lot = m.build_lots(f)[0]
        act, _ = m.arm_pnl(lot, bars, self.und, q, D, shadow=False); sh, info = m.arm_pnl(lot, bars, self.und, q, D, shadow=True)
        self.assertEqual(len(act), 3)                       # 2 sells + residual 2 marked at last close (no expiry close captured for a future expiry)
        self.assertEqual(len(sh), 2)                        # the 09:31 sell + shadow exit of the remaining 3; the 09:40 sell is ignored
        self.assertEqual(sh[1][1], (2.0 * (1 - m.HS_FLOOR) - 2.0) * 3 * 100)
    def test_no_trigger_expires_to_intrinsic_in_both_arms(self):
        exp = date(2026, 11, 6); q = qqq_series(100, [0], d=D - timedelta(days=3), start="15:59") + qqq_series(100, [0] * 100)
        bars = opt_bars(CALL, [2.0] * 100); lot = m.build_lots([fill("BUY", 1, 2.0, t("09:30"))])[0]
        und = {("SPY", "2026-11-06"): 702.5}                # intrinsic 2.5 at expiry close
        a, _ = m.arm_pnl(lot, bars, und, q, exp, shadow=False); s, info = m.arm_pnl(lot, bars, und, q, exp, shadow=True)
        self.assertEqual(a, [(exp, 50.0)]); self.assertEqual(s, a); self.assertIsNone(info["trigger"])

class Decision(unittest.TestCase):
    def test_pass_kill_not_shown_incl_negative_totals(self):
        self.assertEqual(m.decide(1000, 900, 500, 300), "PASS")       # dd smaller and 90% kept
        self.assertEqual(m.decide(1000, 700, 500, 300), "NOT_SHOWN")  # dd smaller but <80%
        self.assertEqual(m.decide(1000, 700, 500, 600), "KILL")       # worse total, dd not smaller
        self.assertEqual(m.decide(-500, -400, 800, 600), "PASS")      # negative actual: shadow must be >= actual
        self.assertEqual(m.decide(-500, -600, 800, 600), "NOT_SHOWN")
    def test_max_drawdown(self):
        self.assertEqual(m.max_drawdown([100, -300, 50, -100]), 350.0); self.assertEqual(m.max_drawdown([10, 20]), 0.0)

class Gates(unittest.TestCase):
    def test_analyze_refuses_before_look_and_capture_is_append_only(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(SystemExit): m.analyze(d, date(2027, 4, 22))
            os.makedirs(os.path.join(d, "raw", "2026-11-02"))
            with self.assertRaises(SystemExit): m.capture(date(2026, 11, 2), d, {}, "true")
    def test_check_flags_missing_files_and_hash_drift(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "raw", "2026-11-02"); os.makedirs(p)
            json.dump([], open(os.path.join(p, "fills.json"), "w"))
            for f in ("qqq_1m", "opt_1m", "und_1d"): m.write_gz(os.path.join(p, f + ".ndjson.gz"), [])
            m.manifest(p); open(os.path.join(p, "fills.json"), "w").write("[ ]")   # tamper after manifest (still valid JSON, different bytes)
            r = m.check(d, date(2026, 11, 3))
            self.assertTrue(any("hash mismatch" in x for x in r["problems"])); self.assertTrue(any("fewer than 300" in x for x in r["problems"]))
            self.assertTrue(any("no capture folder" in x for x in r["problems"]))



class EndToEnd(unittest.TestCase):
    """analyze() over raw folders in the exact on-disk format capture() writes (fills.json + ndjson.gz via write_gz/bar_rows)."""
    def test_analyze_from_raw_folders(self):
        with tempfile.TemporaryDirectory() as d:
            day = date(2026, 11, 2); p = os.path.join(d, "raw", day.isoformat()); os.makedirs(p)
            json.dump([fill("BUY", 2, 2.0, t("09:40")), fill("SELL", 1, 2.6, t("10:30"), "s1")], open(os.path.join(p, "fills.json"), "w"))
            # QQQ: flat until 10:00, then −0.6% at 10:00 -> trigger; option bars: 2.0 flat, 1.5 after 10:00
            qqq = [{"t": (t("09:30") + timedelta(minutes=i)).isoformat(), "o": 0, "h": 0, "l": 0, "c": 100 * (1 - (0.006 if i >= 30 else 0)), "v": 1} for i in range(390)]
            m.write_gz(os.path.join(p, "qqq_1m.ndjson.gz"), [dict(r, s="QQQ") for r in qqq])
            ob = [{"s": m.occ(CALL)["compact"], "t": (t("09:30") + timedelta(minutes=i)).isoformat(), "o": 2.0 if i < 31 else 1.5, "h": 0, "l": 0, "c": 2.0 if i < 31 else 1.5, "v": 1} for i in range(390)]
            m.write_gz(os.path.join(p, "opt_1m.ndjson.gz"), ob)
            m.write_gz(os.path.join(p, "und_1d.ndjson.gz"), [{"s": "SPY", "d": day.isoformat(), "c": 690.0}]); m.manifest(p)
            # expiry day folder with the underlying close (OTM -> intrinsic 0 for the actual arm's residual)
            pe = os.path.join(d, "raw", "2026-11-06"); os.makedirs(pe); json.dump([], open(os.path.join(pe, "fills.json"), "w"))
            for f in ("qqq_1m", "opt_1m"): m.write_gz(os.path.join(pe, f + ".ndjson.gz"), [])
            m.write_gz(os.path.join(pe, "und_1d.ndjson.gz"), [{"s": "SPY", "d": "2026-11-06", "c": 690.0}]); m.manifest(pe)
            r = m.analyze(d, m.LOOK)
            # actual: +0.6*1*100 = +60 on the sold lot-half, residual 1 expires worthless: −200 -> total −140
            # shadow: trigger at 10:00 (QQQ −0.6%), both contracts (the 10:30 sell is after the trigger and ignored) exit at the 10:01 open 1.5×(1−1%)
            self.assertEqual(r["lots"], 1); self.assertEqual(r["triggers"], 1)
            self.assertAlmostEqual(r["actual_total"], 60 - 200, places=6)
            self.assertAlmostEqual(r["shadow_total"], (1.5 * 0.99 - 2.0) * 2 * 100, places=6)
            self.assertEqual(r["verdict"], "PASS")   # shadow −103 vs actual −140: better total and smaller drawdown
            self.assertTrue(os.path.exists(os.path.join(d, f"analysis_{m.LOOK}.json")))


class ReviewFixes(unittest.TestCase):
    def test_analyze_with_no_lots_does_not_crash(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "raw", "2026-11-02"); os.makedirs(p); json.dump([], open(os.path.join(p, "fills.json"), "w"))
            for f in ("qqq_1m", "opt_1m", "und_1d"): m.write_gz(os.path.join(p, f + ".ndjson.gz"), [])
            m.manifest(p); r = m.analyze(d, m.LOOK)
            self.assertEqual((r["lots"], r["triggers"], r["verdict"], r["ci95"]), (0, 0, "NOT_SHOWN", (None, None)))
    def test_trigger_without_option_bar_is_flagged_not_silently_held(self):
        q = qqq_series(100, [0], d=D - timedelta(days=3), start="15:59") + qqq_series(100, [0, 0, -0.01] + [-0.01] * 60)
        bars = opt_bars(CALL, [2.0, 2.0, 2.0])                      # prints stop at 09:32; trigger bar 09:32 -> no print after it
        lot = m.build_lots([fill("BUY", 1, 2.0, t("09:30"))])[0]
        s, info = m.arm_pnl(lot, bars, {}, q, D, shadow=True)
        self.assertIsNotNone(info["trigger"]); self.assertTrue(info.get("no_exit_bar")); self.assertTrue(info.get("marked_open"))
    def test_multi_day_lot_trigger_day_n_exit_day_n_plus_1(self):
        d2 = D + timedelta(days=1)
        q = qqq_series(100, [0], d=D - timedelta(days=3), start="15:59") + qqq_series(100, [0] * 390) + qqq_series(100, [0, -0.006] + [-0.006] * 60, d=d2)
        bars = {m.occ(CALL)["compact"]: opt_bars(CALL, [2.0] * 390)[m.occ(CALL)["compact"]] + opt_bars(CALL, [1.5] * 62, d=d2)[m.occ(CALL)["compact"]]}
        lot = m.build_lots([fill("BUY", 1, 2.0, t("10:00"))])[0]
        s, info = m.arm_pnl(lot, bars, {}, q, d2, shadow=True)
        self.assertEqual(info["trigger"]["t"], t("09:31", d2).isoformat()); self.assertEqual(info["shadow_exit"]["t"], t("09:32", d2).isoformat())
        self.assertEqual(s, [(d2, (1.5 * (1 - m.HS_FLOOR) - 2.0) * 100)])
    def test_capture_is_atomic_on_failure(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(Exception): m.capture(date(2026, 11, 2), d, {}, "false")   # psql command fails -> no folder, no .tmp left
            self.assertFalse(os.path.exists(os.path.join(d, "raw", "2026-11-02"))); self.assertFalse(os.path.exists(os.path.join(d, "raw", "2026-11-02.tmp")))
    def test_capture_success_renames_tmp_to_final_with_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            orig_sql, orig_paged = m.sql, m.paged
            m.sql = lambda cmd, q: []; m.paged = lambda path, params, headers, key: {}
            try: r = m.capture(date(2026, 11, 2), d, {}, "x")
            finally: m.sql, m.paged = orig_sql, orig_paged
            final = os.path.join(d, "raw", "2026-11-02")
            self.assertTrue(os.path.exists(os.path.join(final, "manifest.json"))); self.assertFalse(os.path.exists(final + ".tmp"))
            self.assertEqual(r["fills"], 0)
    def test_fills_window_is_dst_correct(self):
        seen = {}
        def fake_sql(cmd, q): seen["q"] = q; return []
        orig = m.sql; m.sql = fake_sql
        try: m.fills_for_day(date(2026, 11, 3), "x")
        finally: m.sql = orig
        self.assertIn("'2026-11-03T00:00:00-05:00'", seen["q"]); self.assertIn("'2026-11-04T00:00:00-05:00'", seen["q"])


if __name__ == "__main__":
    unittest.main()
