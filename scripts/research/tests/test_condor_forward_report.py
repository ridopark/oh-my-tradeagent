#!/usr/bin/env python3
"""Tests for `scripts/research/condor_forward_report.py` — the pre-registered
criteria math, journal attribution, and P&L / max-risk arithmetic.

Run standalone:
    python3 -m unittest discover -s scripts/research/tests
"""
from __future__ import annotations

import datetime as dt
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from decimal import Decimal

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent.parent / "condor_forward_report.py"
_spec = importlib.util.spec_from_file_location("condor_forward_report", SCRIPT_PATH)
assert _spec and _spec.loader, f"could not load {SCRIPT_PATH}"
cfr = importlib.util.module_from_spec(_spec)
sys.modules["condor_forward_report"] = cfr  # needed for @dataclass
_spec.loader.exec_module(cfr)  # type: ignore[union-attr]

T, S = "staging_paper", "gated_condor"
# short call 581, short put 579, long call 584, long put 576 → width 3
LEGS = [
    "XSP261005C00581000",
    "XSP261005P00579000",
    "XSP261005C00584000",
    "XSP261005P00576000",
]
D0 = dt.date(2026, 10, 5)


def ev(kind, day, subject, hm="18:00", wf=None):
    return {
        "tenant_id": T,
        "strategy_id": S,
        "occurred_at": f"{day}T{hm}:00+00:00",  # 18:00Z = 14:00 ET
        "kind": kind,
        "workflow_id": wf or f"{T}:{S}/condor-session/{day}",
        "subject": subject,
    }


def gate(day, on=True):
    return ev("CondorGateEvaluated", day, {"et_date": str(day), "richness": 1.1, "gate": on})


def filled(day, credit="0.40", model="0.42"):
    return ev(
        "CondorEntryFilled",
        day,
        {"outcome": "FILLED", "credit": credit, "model_credit": model, "filled_qty": 1,
         "rungs": 2, "legs": LEGS},
    )


def settled(day, pnl, partial=False):
    return ev(
        "CondorSettled",
        day,
        {"expected_pnl": pnl, "partial": partial, "outcome": "OTM"},
        hm="20:15",
        wf=f"t-{T}/s-{S}/condor/{day}",
    )


def abandoned(day):
    return ev("CondorEntryAbandoned", day, {"reason": "below_floor"})


def winning_trade(day, pnl=40):
    return [gate(day), filled(day), settled(day, pnl)]


def book(audit, journal=(), live=()):
    return cfr.build(cfr.Inputs(list(journal), list(audit), list(live)), S)


def days(n):
    return [D0 + dt.timedelta(days=i) for i in range(n)]


class MathTest(unittest.TestCase):
    def test_max_risk_and_return_from_occ_strikes(self):
        a = book(winning_trade(D0)).attempts[D0]
        self.assertEqual(cfr.wing_width(LEGS), Decimal(3))
        self.assertEqual(a.max_risk, Decimal("260.00"))  # (3 - 0.40) * 100
        self.assertAlmostEqual(a.ret, 40 / 260)
        self.assertEqual(a.credit_delta, Decimal("-0.02"))

    def test_full_flatten_pnl_uses_closing_fills(self):
        audit = [gate(D0), filled(D0),
                 ev("CondorFlattened", D0, {"shorts_covered": True}, wf=f"x/condor/{D0}")]
        closes = [
            {"intent_key": f"condor-{T}-{S}-{D0}-x0", "strategy_id": S, "side": "BUY",
             "filled_qty": 1, "avg_fill_price": "0.30", "broker_target": "paper"},
            {"intent_key": f"condor-{T}-{S}-{D0}-x2", "strategy_id": S, "side": "SELL",
             "filled_qty": 1, "avg_fill_price": "0.05", "broker_target": "paper"},
        ]
        a = book(audit, closes).attempts[D0]
        self.assertEqual(a.pnl, Decimal("15.00"))  # 40 - 30 + 5

    def test_partial_flatten_adds_remainder_settlement(self):
        audit = [gate(D0), filled(D0), settled(D0, "-100", partial=True)]
        closes = [{"intent_key": f"condor-{T}-{S}-{D0}-x0", "strategy_id": S, "side": "BUY",
                   "filled_qty": 1, "avg_fill_price": "0.50", "broker_target": "paper"}]
        self.assertEqual(book(audit, closes).attempts[D0].pnl, Decimal("-110.00"))

    def test_partial_settle_without_expected_pnl_is_unpriced_not_a_crash(self):
        audit = [gate(D0), filled(D0), settled(D0, None, partial=True)]
        self.assertIsNone(book(audit).attempts[D0].pnl)


class AttributionTest(unittest.TestCase):
    def test_foreign_rows_never_enter_the_scorecard(self):
        rung = {"intent_key": f"condor-{T}-{S}-{D0}-r0", "strategy_id": S, "state": "FILLED",
                "filled_qty": 1, "broker_target": "paper", "legs": [{"leg_index": 0}]}
        prefix_only = {"intent_key": f"condor-{T}-mirror-{D0}-r0", "strategy_id": "mirror",
                       "broker_target": "live"}
        strategy_only = {"intent_key": f"cto-abc-{D0}", "strategy_id": S,
                         "broker_target": "live"}
        b = book(winning_trade(D0), [rung, prefix_only, strategy_only])
        self.assertEqual(b.condor_rows, [rung])
        self.assertEqual(len(b.excluded_rows), 2)
        self.assertEqual(b.attempts[D0].leg_nbbo, [{"leg_index": 0}])
        # the excluded broker_target=live rows must not trip the non-paper hard kill
        self.assertEqual(cfr.evaluate(b, D0).hard_kills, [])


class CriteriaTest(unittest.TestCase):
    def full_sample(self, pnl=40, n=25):
        audit = []
        for d in days(n):
            audit += winning_trade(d, pnl)
        return audit

    def as_of_eval(self):
        return D0 + dt.timedelta(days=90)

    def test_accruing_before_both_thresholds(self):
        audit = self.full_sample()
        self.assertEqual(cfr.evaluate(book(audit), D0 + dt.timedelta(days=89)).verdict, "ACCRUING")
        audit24 = self.full_sample(n=24)
        self.assertEqual(cfr.evaluate(book(audit24), self.as_of_eval()).verdict, "ACCRUING")

    def test_continue_at_eval_point(self):
        v = cfr.evaluate(book(self.full_sample()), self.as_of_eval())
        self.assertEqual(v.verdict, "CONTINUE")
        self.assertEqual(v.kills, [])

    def test_kill_on_non_positive_mean(self):
        v = cfr.evaluate(book(self.full_sample(pnl=0)), self.as_of_eval())
        self.assertEqual(v.verdict, "KILL")
        self.assertIn("mean", v.kills[0])

    def test_kill_rules_are_provisional_before_eval_point(self):
        v = cfr.evaluate(book(self.full_sample(pnl=-10, n=3)), D0 + dt.timedelta(days=5))
        self.assertEqual(v.verdict, "ACCRUING")
        self.assertTrue(v.kills)

    def test_credit_shortfall_boundary(self):
        def sample(credit):
            audit = []
            for d in days(25):
                audit += [gate(d), filled(d, credit=credit, model="0.44"), settled(d, 40)]
            return cfr.evaluate(book(audit), self.as_of_eval())

        self.assertEqual(sample("0.40").verdict, "CONTINUE")  # exactly -0.04
        v = sample("0.39")
        self.assertEqual(v.verdict, "KILL")
        self.assertIn("credit", v.kills[0])

    def test_abandon_rate_boundary(self):
        base = self.full_sample(n=30)
        extra = days(50)[30:]
        at_40 = base + [x for d in extra[:20] for x in (gate(d), abandoned(d))]  # 20/50
        self.assertEqual(cfr.evaluate(book(at_40), extra[-1] + dt.timedelta(days=90)).verdict,
                         "CONTINUE")
        over = at_40 + [gate(extra[-1] + dt.timedelta(days=1)),
                        abandoned(extra[-1] + dt.timedelta(days=1))]  # 21/51
        v = cfr.evaluate(book(over), extra[-1] + dt.timedelta(days=90))
        self.assertEqual(v.verdict, "KILL")
        self.assertIn("abandon", v.kills[0])

    def test_halted_counts_as_abandoned_and_gated_out_does_not(self):
        audit = [gate(D0), ev("CondorEntryHalted", D0, {"reason": "cancel failed"}),
                 gate(D0 + dt.timedelta(days=1), on=False)]
        m = cfr.evaluate(book(audit), D0).metrics
        self.assertEqual((m["gated"], m["abandoned"], m["abandon_rate"]), (1, 1, 1.0))

    def test_unresolved_mismatch_older_than_one_trading_day(self):
        fri = dt.date(2026, 10, 9)
        audit = [
            gate(fri), filled(fri),
            ev("CondorSettleMismatch", fri, {"reason": "settlement_spot_unavailable"},
               hm="20:15", wf=f"x/condor/{fri}"),
        ]
        b = book(audit)
        self.assertEqual(cfr.evaluate(b, dt.date(2026, 10, 12)).kills, [])  # Mon: 1 day
        self.assertTrue(cfr.evaluate(b, dt.date(2026, 10, 13)).kills)  # Tue: 2 days
        self.assertEqual(cfr.evaluate(b, dt.date(2026, 10, 13), {fri}).kills, [])


class HardKillTest(unittest.TestCase):
    def test_live_journal_condor_order(self):
        v = cfr.evaluate(book(winning_trade(D0), live=[{"intent_key": "condor-x"}]), D0)
        self.assertEqual(v.verdict, "HARD_KILL")

    def test_attributed_row_on_non_paper_target(self):
        row = {"intent_key": f"condor-{T}-{S}-{D0}-r0", "strategy_id": S, "broker_target": "live"}
        self.assertEqual(cfr.evaluate(book(winning_trade(D0), [row]), D0).verdict, "HARD_KILL")

    def test_legs_held_after_expiry(self):
        nxt = D0 + dt.timedelta(days=1)
        audit = winning_trade(D0) + [
            ev("CondorSettleMismatch", nxt, {"reason": "legs_still_held_after_expiry"},
               hm="13:35", wf=f"{T}:{S}/condor/{D0}")
        ]
        v = cfr.evaluate(book(audit), nxt)
        self.assertEqual(v.verdict, "HARD_KILL")
        self.assertIn(str(D0), v.hard_kills[0])

    def test_uncovered_shorts_after_flatten(self):
        audit = [gate(D0), filled(D0),
                 ev("CondorFlattenIncomplete", D0, {"shorts_covered": False, "longs_closed": True},
                    wf=f"x/condor/{D0}"),
                 settled(D0, "-50", partial=True)]
        self.assertEqual(cfr.evaluate(book(audit), D0).verdict, "HARD_KILL")

    def test_fill_without_settlement_by_next_day(self):
        b = book([gate(D0), filled(D0)])
        self.assertNotEqual(cfr.evaluate(b, D0).verdict, "HARD_KILL")
        self.assertEqual(cfr.evaluate(b, D0 + dt.timedelta(days=1)).verdict, "HARD_KILL")


class EmitterPayloadTest(unittest.TestCase):
    """Payloads with exactly the keys CondorSessionWorkflowImpl / CondorHoldWorkflowImpl emit
    (subject maps serialized by Jackson; hold id = WorkflowIds.condorHold)."""

    HOLD = f"t-{T}/s-{S}/condor/{D0}"

    def emitted(self):
        return [
            ev("CondorGateEvaluated", D0, {
                "et_date": str(D0), "richness": Decimal("1.083"), "trailing_median": 0.97,
                "quantile": 0.71, "trailing_count": 60, "min_quantile": 0.5, "gate": True,
                "reason": None}, wf=f"t-{T}/s-{S}/condor-session/x"),
            ev("CondorEntryFilled", D0, {
                "outcome": "FILLED", "credit": Decimal("0.41"), "model_credit": Decimal("0.43"),
                "net_mid": Decimal("0.43"), "slippage_vs_mid": Decimal("0.02"), "filled_qty": 1,
                "rungs": 3, "spot": 580.1, "legs": LEGS, "hold_workflow_id": self.HOLD},
               wf=f"t-{T}/s-{S}/condor-session/x"),
            ev("CondorSettled", D0, {
                "settlement_spot": 580.4, "credit": Decimal("0.41"),
                "settlement_debit": Decimal("0.0000"), "expected_pnl": Decimal("41.00"),
                "outcome": "OTM", "qty": 1, "partial": False, "legs_priced": LEGS},
               hm="20:15", wf=self.HOLD),
        ]

    def test_real_keys_populate_the_round(self):
        a = book(self.emitted()).attempts[D0]
        self.assertEqual((a.gate, a.outcome, a.rungs, a.qty), (True, "FILLED", 3, 1))
        self.assertEqual((a.credit, a.model_credit, a.slippage_vs_mid),
                         (Decimal("0.41"), Decimal("0.43"), Decimal("0.02")))
        self.assertEqual(a.pnl, Decimal("41.00"))
        self.assertEqual(a.max_risk, Decimal("259.00"))
        self.assertEqual(a.mismatches, [])

    def test_retry_rung_key_attaches_leg_nbbo(self):
        rung = {"intent_key": f"condor-{T}-{S}-{D0}-r3", "strategy_id": S, "state": "FILLED",
                "filled_qty": 1, "broker_target": "paper", "legs": [{"leg_index": 2}]}
        self.assertEqual(book(self.emitted(), [rung]).attempts[D0].leg_nbbo, [{"leg_index": 2}])


class MismatchAndNullsTest(unittest.TestCase):
    def test_mismatch_alone_is_not_a_booking(self):
        audit = [gate(D0), filled(D0),
                 ev("CondorSettleMismatch", D0, {"reason": "settlement_spot_unavailable"},
                    hm="20:15", wf=f"t-{T}/s-{S}/condor/{D0}")]
        b = book(audit)
        nxt = D0 + dt.timedelta(days=1)
        self.assertEqual(cfr.evaluate(b, nxt).verdict, "HARD_KILL")
        self.assertNotEqual(cfr.evaluate(b, nxt, {D0}).verdict, "HARD_KILL")

    def test_booked_round_with_mismatch_is_quarantined_from_the_mean(self):
        held = ev("CondorSettleMismatch", D0, {"reason": "held_legs_read_failed"},
                  hm="13:35", wf=f"t-{T}/s-{S}/condor/{D0}")
        d1 = D0 + dt.timedelta(days=1)
        b = book(winning_trade(D0, 40) + [held] + winning_trade(d1, -100))
        m = cfr.evaluate(b, d1).metrics
        self.assertEqual((m["priced"], m["quarantined"], m["total_pnl"]), (1, 1, Decimal(-100)))
        m = cfr.evaluate(b, d1, {D0}).metrics
        self.assertEqual((m["priced"], m["quarantined"]), (2, 0))

    def test_null_fields_flag_the_round_and_render_placeholders(self):
        audit = [gate(D0),
                 ev("CondorEntryFilled", D0, {"outcome": None, "credit": None, "legs": None}),
                 settled(D0, None)]
        rung = {"intent_key": f"condor-{T}-{S}-{D0}-r0", "strategy_id": S, "state": "FILLED",
                "filled_qty": 1, "broker_target": "paper",
                "legs": [{"leg_index": None, "option_symbol": None, "side": None,
                          "nbbo_bid": None, "nbbo_ask": None, "nbbo_mid": None}]}
        close = {"intent_key": f"condor-{T}-{S}-{D0}-x0", "strategy_id": S, "side": None,
                 "filled_qty": 1, "avg_fill_price": None, "broker_target": "paper"}
        b = book(audit, [rung, close])
        reasons = {r for _, r in b.attempts[D0].mismatches}
        for k in ("outcome", "credit", "model_credit", "filled_qty", "legs", "expected_pnl"):
            self.assertIn(f"missing_field:{k}", reasons)
        self.assertTrue(any(r.startswith("missing_field:close_fill") for r in reasons))
        v = cfr.evaluate(b, D0)
        self.assertEqual(v.metrics["quarantined"], 1)
        text = cfr.render(b, v, T, S, D0)
        self.assertIn("MISMATCH:", text)
        self.assertIn("VERDICT:", text)


class OfflineCliTest(unittest.TestCase):
    def test_offline_jsonl_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            j, a = pathlib.Path(tmp, "j.jsonl"), pathlib.Path(tmp, "a.jsonl")
            j.write_text("")
            a.write_text("\n".join(json.dumps(e) for e in winning_trade(D0)) + "\n")
            out = io.StringIO()
            with redirect_stdout(out):
                rc = cfr.main(["--journal-jsonl", str(j), "--audit-jsonl", str(a),
                               "--as-of", str(D0)])
        self.assertEqual(rc, 0)
        self.assertIn("VERDICT: ACCRUING", out.getvalue())
        self.assertIn("NOT RUN", out.getvalue())


if __name__ == "__main__":
    unittest.main()
