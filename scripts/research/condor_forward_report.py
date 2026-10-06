#!/usr/bin/env python3
"""Gated-condor forward-test scorecard (read-only).

Phase 5 of docs/plans/PLAN-2026-10-05-gated-condor-paper-test.md. One row per
gated attempt (gate value, model vs achieved credit, slippage_vs_mid, per-leg
NBBO at submit, settlement P&L), then the plan's PRE-REGISTERED criteria:

  Evaluate at the LATER of 25 gated fills or 90 calendar days (from the first
  gate evaluation). Then:
    KILL       mean P&L / max risk <= 0
    KILL       median (achieved credit - model credit) < -$0.04 (per leg-set)
    KILL       abandon rate > 40% of gated attempts
    KILL       a settlement mismatch unresolved > 1 trading day
    CONTINUE   otherwise
  HARD KILL at any time, evaluation point or not:
    - any condor order in the LIVE exec journal (exec_alpaca_live), or a
      condor journal row with broker_target != paper
    - legs still held after expiry, or a filled condor with no settlement /
      flatten booked by the next ET date
    - a flatten that left the shorts uncovered (possible naked leg)

Expectation anchor (reported, never decisive): +6.5% of max risk per gated
trade — the 10-pt XSP grid backtest. Not checked here (operator review): a
condor-attributed account-cap trip flattening the copytrade mirror, and the
secondary U5 (collector NBBO) / U6 comparisons.

ATTRIBUTION. staging_paper shares its order_intent_journal with the copytrade
mirror. A journal row counts ONLY if its intent_key starts with "condor-" AND
its strategy_id is the condor strategy; rows matching just one are excluded
and counted as an attribution warning. Audit events are read for the condor
(tenant, strategy) only.

Data sources:
  default   the homelab, via scripts/data/homelab_psql.sh (SELECT only):
            exec_alpaca_paper (journal + legs), orchestrator (audit_log),
            exec_alpaca_live (hard-kill probe for condor orders)
  offline   --journal-jsonl / --audit-jsonl [/ --live-journal-jsonl]: one
            JSON object per line, the shape the SQL below emits

Usage:
    python3 scripts/research/condor_forward_report.py
    python3 scripts/research/condor_forward_report.py --as-of 2026-12-31
    python3 scripts/research/condor_forward_report.py \\
        --journal-jsonl j.jsonl --audit-jsonl a.jsonl

Abandon rate counts gated attempts that ended ABANDONED or HALTED (no fill).

MISMATCH vs BOOKED P&L (conservative, per the plan's two settlement rules):
  - Only a CondorSettled or CondorFlattened event (or an operator
    --resolved-mismatch) books a fill. A CondorSettleMismatch alone is NOT a
    booking: a fill with nothing else by the next ET date is the hard kill
    "position past expiry without settlement booked".
  - A round carrying any unresolved mismatch is QUARANTINED: its P&L is shown
    but kept out of the mean / total until --resolved-mismatch, because a
    mismatch means the booked number is not trusted. Its mismatch still runs
    the "> 1 trading day unresolved" KILL clock.
  - A missing / null payload field on a fill (outcome, credit, model_credit,
    filled_qty, legs, expected_pnl, a closing fill's side or price) is
    recorded as a "missing_field:" mismatch and treated the same way, as is an
    entry with no CondorGateEvaluated event ("missing_gate_event") — it still
    counts as a gated attempt in the fill / abandon denominators.

Exit status: 0 = ACCRUING/CONTINUE, 1 = KILL, 2 = HARD KILL.
"""
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import pathlib
import re
import statistics as st
import subprocess
import sys
from decimal import Decimal
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
CONDOR_PREFIX = "condor-"
MULTIPLIER = Decimal(100)

EVAL_MIN_FILLS = 25
EVAL_MIN_DAYS = 90
CREDIT_SHORTFALL_LIMIT = Decimal("-0.04")
ABANDON_RATE_LIMIT = 0.40
MISMATCH_MAX_TRADING_DAYS = 1
EXPECTATION_PCT = 6.5

PSQL = pathlib.Path(__file__).resolve().parent.parent / "data" / "homelab_psql.sh"
_SAFE_ID = re.compile(r"^[A-Za-z0-9_-]+$")
_ATTEMPT_DATE = re.compile(r"(\d{4}-\d{2}-\d{2})(?:-[rx]\d+)?$")


# ---------------------------------------------------------------- data access


def journal_sql(tenant: str, strategy: str) -> str:
    # Either attribution key, so rows matching only one can be counted (and excluded).
    return f"""
SELECT row_to_json(t) FROM (
  SELECT j.intent_key, j.tenant_id, j.strategy_id, j.broker_target, j.option_symbol,
         j.side, j.qty, j.limit_price, j.state, j.filled_qty, j.avg_fill_price,
         j.filled_at, j.recorded_at, j.slippage_vs_mid,
         (SELECT json_agg(json_build_object(
                   'leg_index', l.leg_index, 'option_symbol', l.option_symbol,
                   'side', l.side, 'nbbo_bid', l.nbbo_bid, 'nbbo_ask', l.nbbo_ask,
                   'nbbo_mid', l.nbbo_mid) ORDER BY l.leg_index)
            FROM order_intent_journal_leg l
           WHERE l.parent_intent_key = j.intent_key) AS legs
    FROM order_intent_journal j
   WHERE j.tenant_id = '{tenant}'
     AND (j.intent_key LIKE 'condor-%' OR j.strategy_id = '{strategy}')
   ORDER BY j.recorded_at) t;
"""


def live_journal_sql(strategy: str) -> str:
    return f"""
SELECT row_to_json(t) FROM (
  SELECT intent_key, tenant_id, strategy_id, broker_target, state, recorded_at
    FROM order_intent_journal
   WHERE intent_key LIKE 'condor-%' OR strategy_id = '{strategy}') t;
"""


def audit_sql(tenant: str, strategy: str) -> str:
    return f"""
SELECT row_to_json(t) FROM (
  SELECT tenant_id, strategy_id, occurred_at, kind, workflow_id, subject
    FROM audit_log
   WHERE tenant_id = '{tenant}' AND strategy_id = '{strategy}' AND kind LIKE 'Condor%'
   ORDER BY occurred_at) t;
"""


def homelab_rows(db: str, sql: str) -> list[dict]:
    out = subprocess.run(
        [str(PSQL), db], input=sql, capture_output=True, text=True, check=True
    ).stdout
    return [json.loads(line, parse_float=Decimal) for line in out.splitlines() if line.strip()]


def jsonl_rows(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line, parse_float=Decimal) for line in f if line.strip()]


# ---------------------------------------------------------------- model


def dec(x) -> Decimal | None:
    return None if x is None else Decimal(str(x))


def ts(x) -> dt.datetime:
    return dt.datetime.fromisoformat(str(x).replace("Z", "+00:00"))


def et_date(x) -> dt.date:
    return ts(x).astimezone(ET).date()


def occ_strike(occ: str) -> Decimal:
    return Decimal(occ[-8:]) / 1000


def wing_width(legs: list[str]) -> Decimal:
    """Legs in session order: short call, short put, long call, long put."""
    sc, sp, lc, lp = (occ_strike(o) for o in legs)
    return max(lc - sc, sp - lp)


def attempt_date(key: str) -> dt.date | None:
    m = _ATTEMPT_DATE.search(key or "")
    return dt.date.fromisoformat(m.group(1)) if m else None


@dataclasses.dataclass
class Attempt:
    date: dt.date
    gate: bool | None = None
    richness: float | None = None
    trailing_median: float | None = None
    quantile: float | None = None
    outcome: str | None = None  # FILLED / PARTIAL / ABANDONED / HALTED
    reason: str | None = None
    rungs: int | None = None
    model_credit: Decimal | None = None
    net_mid: Decimal | None = None
    credit: Decimal | None = None
    slippage_vs_mid: Decimal | None = None
    qty: int | None = None
    legs: list[str] = dataclasses.field(default_factory=list)
    leg_nbbo: list[dict] = dataclasses.field(default_factory=list)
    settled: dict | None = None
    flattened: dict | None = None
    flatten_incomplete: dict | None = None
    mismatches: list[tuple[dt.date, str]] = dataclasses.field(default_factory=list)
    close_rows: list[dict] = dataclasses.field(default_factory=list)

    @property
    def filled(self) -> bool:
        return self.outcome in ("FILLED", "PARTIAL")

    def flag(self, day: dt.date, reason: str) -> None:
        self.mismatches.append((day, reason))

    @property
    def credit_delta(self) -> Decimal | None:
        if self.credit is None or self.model_credit is None:
            return None
        return self.credit - self.model_credit

    @property
    def max_risk(self) -> Decimal | None:
        if not self.filled or len(self.legs) != 4 or self.credit is None:
            return None
        return (wing_width(self.legs) - self.credit) * MULTIPLIER * (self.qty or 1)

    def close_cash(self) -> Decimal:
        """Cash from the flatten's closing fills: + for SELL (long closes), − for BUY."""
        cash = Decimal(0)
        for r in self.close_rows:
            if r.get("filled_qty") and r.get("avg_fill_price") is not None:
                leg = dec(r["avg_fill_price"]).copy_abs() * MULTIPLIER * int(r["filled_qty"])
                cash += leg if r.get("side") == "SELL" else -leg
        return cash

    @property
    def pnl(self) -> Decimal | None:
        """Realized dollars, or None while unpriced (unsettled / spot unavailable)."""
        if not self.filled or self.credit is None:
            return None
        entry = self.credit * MULTIPLIER * (self.qty or 1)
        if self.settled is not None:
            settle = dec(self.settled.get("expected_pnl"))
            if settle is None:
                return None
            # The hold prices a PARTIAL settle with credit 0 over the legs still held, so only
            # a non-partial expected_pnl carries the entry credit (CondorHoldWorkflowImpl.settle).
            if not self.settled.get("partial"):
                return settle  # already includes the entry credit
            return entry + self.close_cash() + settle
        if self.flattened is not None:
            return entry + self.close_cash()
        return None

    @property
    def ret(self) -> float | None:
        pnl, risk = self.pnl, self.max_risk
        if pnl is None or not risk:
            return None
        return float(pnl / risk)


@dataclasses.dataclass
class Inputs:
    journal: list[dict]
    audit: list[dict]
    live_journal: list[dict] | None  # None = live probe not run


@dataclasses.dataclass
class Book:
    attempts: dict[dt.date, Attempt]
    start: dt.date | None
    excluded_rows: list[dict]
    condor_rows: list[dict]
    live_rows: list[dict] | None


def attributed(row: dict, strategy: str) -> bool:
    return (row.get("intent_key") or "").startswith(CONDOR_PREFIX) and row.get(
        "strategy_id"
    ) == strategy


def build(inputs: Inputs, strategy: str) -> Book:
    attempts: dict[dt.date, Attempt] = {}

    def at(d: dt.date) -> Attempt:
        return attempts.setdefault(d, Attempt(date=d))

    gate_days: list[dt.date] = []
    for e in inputs.audit:
        if e.get("strategy_id") != strategy:
            continue
        kind, s = e["kind"], e.get("subject") or {}
        session_day = et_date(e["occurred_at"])
        hold_day = attempt_date(e.get("workflow_id") or "") or session_day
        if kind == "CondorGateEvaluated":
            d = dt.date.fromisoformat(s["et_date"]) if s.get("et_date") else session_day
            a = at(d)
            gate_days.append(d)
            a.gate = bool(s.get("gate"))
            a.richness, a.trailing_median, a.quantile = (
                s.get("richness"),
                s.get("trailing_median"),
                s.get("quantile"),
            )
        elif kind == "CondorEntryFilled":
            a = at(session_day)
            # The kind itself proves a fill; a missing outcome only flags the round.
            a.outcome = s.get("outcome") or "FILLED"
            a.credit = dec(s.get("credit"))
            a.model_credit = dec(s.get("model_credit"))
            a.net_mid = dec(s.get("net_mid"))
            a.slippage_vs_mid = dec(s.get("slippage_vs_mid"))
            a.qty = int(s.get("filled_qty") or 1)
            a.rungs = s.get("rungs")
            a.legs = list(s.get("legs") or [])
            for k in ("outcome", "credit", "model_credit", "filled_qty"):
                if s.get(k) is None:
                    a.flag(session_day, f"missing_field:{k}")
            if len(a.legs) != 4 or not all(a.legs):
                a.flag(session_day, "missing_field:legs")
        elif kind in ("CondorEntryAbandoned", "CondorEntryHalted"):
            a = at(session_day)
            a.outcome = "ABANDONED" if kind == "CondorEntryAbandoned" else "HALTED"
            a.reason = s.get("reason")
            a.rungs = s.get("rungs")
            a.net_mid = dec(s.get("net_mid"))
        elif kind == "CondorSettled":
            at(hold_day).settled = s
            if s.get("expected_pnl") is None:
                at(hold_day).flag(session_day, "missing_field:expected_pnl")
        elif kind == "CondorFlattened":
            at(hold_day).flattened = s
        elif kind == "CondorFlattenIncomplete":
            at(hold_day).flatten_incomplete = s
        elif kind == "CondorSettleMismatch":
            at(hold_day).mismatches.append((session_day, str(s.get("reason"))))

    # An entry implies the gate passed; a missing gate event is a telemetry gap, so the attempt
    # stays in the KILL denominators and is flagged (quarantined, kill clock) rather than hidden.
    for a in attempts.values():
        if a.gate is None and a.outcome is not None:
            a.flag(a.date, "missing_gate_event")

    condor_rows, excluded = [], []
    for r in inputs.journal:
        (condor_rows if attributed(r, strategy) else excluded).append(r)
    for r in condor_rows:
        d = attempt_date(r["intent_key"])
        if d is None or d not in attempts:
            continue
        key = r["intent_key"]
        if re.search(r"-x\d+$", key):
            attempts[d].close_rows.append(r)
            if r.get("side") not in ("BUY", "SELL") or (
                r.get("filled_qty") and r.get("avg_fill_price") is None
            ):
                attempts[d].flag(d, f"missing_field:close_fill {key}")
        elif r.get("state") == "FILLED" or (r.get("filled_qty") or 0) > 0:
            attempts[d].leg_nbbo = list(r.get("legs") or [])

    return Book(
        attempts=dict(sorted(attempts.items())),
        start=min(gate_days) if gate_days else None,
        excluded_rows=excluded,
        condor_rows=condor_rows,
        live_rows=inputs.live_journal,
    )


# ---------------------------------------------------------------- criteria


def trading_days_after(start: dt.date, end: dt.date) -> int:
    """Weekdays in (start, end]; holidays are not excluded, which only makes the
    mismatch clock stricter."""
    n, d = 0, start
    while d < end:
        d += dt.timedelta(days=1)
        n += d.weekday() < 5
    return n


@dataclasses.dataclass
class Verdict:
    verdict: str  # ACCRUING / CONTINUE / KILL / HARD_KILL
    hard_kills: list[str]
    kills: list[str]
    metrics: dict
    at_eval_point: bool


def evaluate(book: Book, as_of: dt.date, resolved: set[dt.date] | None = None) -> Verdict:
    resolved = resolved or set()
    attempts = list(book.attempts.values())
    gated = [a for a in attempts if a.gate or (a.gate is None and a.outcome is not None)]
    fills = [a for a in gated if a.filled]
    abandoned = [a for a in gated if a.outcome in ("ABANDONED", "HALTED")]
    quarantined = [a for a in fills if a.mismatches and a.date not in resolved]
    priced = [a for a in fills if a.ret is not None and a not in quarantined]
    rets = [a.ret for a in priced]
    deltas = [a.credit_delta for a in fills if a.credit_delta is not None]

    hard: list[str] = []
    if book.live_rows:
        keys = ", ".join(str(r.get("intent_key")) for r in book.live_rows[:5])
        hard.append(f"{len(book.live_rows)} condor order(s) in the LIVE exec journal: {keys}")
    non_paper = [r for r in book.condor_rows if r.get("broker_target") != "paper"]
    if non_paper:
        hard.append(f"{len(non_paper)} condor journal row(s) with broker_target != paper")
    for a in attempts:
        if any(r == "legs_still_held_after_expiry" for _, r in a.mismatches):
            hard.append(f"{a.date}: legs still held after expiry")
        if a.flatten_incomplete and not a.flatten_incomplete.get("shorts_covered"):
            hard.append(f"{a.date}: flatten left shorts uncovered (possible naked leg)")
        # A mismatch alone is not a booking (see MISMATCH vs BOOKED in the module doc).
        booked = a.settled or a.flattened or a.date in resolved
        if a.filled and not booked and as_of > a.date:
            hard.append(f"{a.date}: filled condor with no settlement booked")

    kills: list[str] = []
    mean = st.fmean(rets) if rets else None
    median_delta = st.median(deltas) if deltas else None
    abandon_rate = len(abandoned) / len(gated) if gated else None
    if mean is not None and mean <= 0:
        kills.append(f"mean P&L/max-risk {mean:+.2%} <= 0")
    if median_delta is not None and median_delta < CREDIT_SHORTFALL_LIMIT:
        kills.append(f"median credit vs model {median_delta:+.4f} < {CREDIT_SHORTFALL_LIMIT}")
    if abandon_rate is not None and abandon_rate > ABANDON_RATE_LIMIT:
        kills.append(f"abandon rate {abandon_rate:.0%} > {ABANDON_RATE_LIMIT:.0%}")
    for a in attempts:
        for day, reason in a.mismatches:
            if (
                a.date not in resolved
                and reason != "legs_still_held_after_expiry"
                and trading_days_after(day, as_of) > MISMATCH_MAX_TRADING_DAYS
            ):
                kills.append(f"{a.date}: settlement mismatch '{reason}' unresolved since {day}")

    days = (as_of - book.start).days if book.start else 0
    at_eval = len(fills) >= EVAL_MIN_FILLS and days >= EVAL_MIN_DAYS
    if hard:
        verdict = "HARD_KILL"
    elif at_eval and kills:
        verdict = "KILL"
    elif at_eval:
        verdict = "CONTINUE"
    else:
        verdict = "ACCRUING"

    t = None
    if len(rets) >= 2 and st.stdev(rets) > 0:
        t = mean / (st.stdev(rets) / len(rets) ** 0.5)
    metrics = {
        "evaluated_days": sum(a.gate is not None for a in attempts),
        "gated": len(gated),
        "fills": len(fills),
        "abandoned": len(abandoned),
        "priced": len(rets),
        "quarantined": len(quarantined),
        "days_since_start": days,
        "mean_ret": mean,
        "t": t,
        "median_credit_delta": median_delta,
        "abandon_rate": abandon_rate,
        "total_pnl": sum((a.pnl for a in priced), Decimal(0)),
    }
    return Verdict(verdict, hard, kills, metrics, at_eval)


# ---------------------------------------------------------------- report


def fmt(x, spec="") -> str:
    if x is None:
        width = re.match(r"[<>+]*(\d*)", spec).group(1)
        return "-".rjust(int(width or 0))
    return format(x, spec)


def render(book: Book, v: Verdict, tenant: str, strategy: str, as_of: dt.date) -> str:
    m = v.metrics
    lines = [
        f"Gated-condor forward scorecard — {tenant}/{strategy} as of {as_of}",
        f"start {book.start or '-'} · day {m['days_since_start']}/{EVAL_MIN_DAYS} · "
        f"fills {m['fills']}/{EVAL_MIN_FILLS} · evaluated days {m['evaluated_days']} · "
        f"gated {m['gated']}",
        "",
        f"{'date':10} {'gate':4} {'rich':>6} {'med':>6} {'q':>5} {'outcome':9} {'rg':>2} "
        f"{'model':>6} {'mid':>6} {'credit':>6} {'Δmodel':>7} {'slip':>6} "
        f"{'maxrisk':>8} {'pnl':>8} {'ret':>7}  settle",
    ]
    for a in book.attempts.values():
        if a.gate is None and not a.filled:
            continue
        settle = (
            ("partial+" if a.settled.get("partial") else "") + str(a.settled.get("outcome"))
            if a.settled
            else "flattened"
            if a.flattened
            else "-"
        )
        if a.mismatches:
            settle += " MISMATCH:" + ",".join(r for _, r in a.mismatches)
        lines.append(
            f"{a.date!s:10} {'?' if a.gate is None else 'Y' if a.gate else 'n':4} {fmt(a.richness, '6.3f')} "
            f"{fmt(a.trailing_median, '6.3f')} {fmt(a.quantile, '5.2f')} "
            f"{fmt(a.outcome if a.gate is not False else 'gated_out'):9} {fmt(a.rungs, '>2')} "
            f"{fmt(a.model_credit, '6.2f')} {fmt(a.net_mid, '6.2f')} {fmt(a.credit, '6.2f')} "
            f"{fmt(a.credit_delta, '+7.2f')} {fmt(a.slippage_vs_mid, '6.2f')} "
            f"{fmt(a.max_risk, '8.0f')} {fmt(a.pnl, '+8.2f')} {fmt(a.ret, '+7.1%')}  {settle}"
        )
        if a.reason:
            lines.append(f"{'':10}   reason: {a.reason}")
        for leg in a.leg_nbbo:
            bid, ask = dec(leg.get("nbbo_bid")), dec(leg.get("nbbo_ask"))
            spread = ask - bid if bid is not None and ask is not None else None
            lines.append(
                f"{'':10}   leg{fmt(leg.get('leg_index'))} {fmt(leg.get('side'), '4')} "
                f"{fmt(leg.get('option_symbol'), '22')} bid {fmt(bid, '.2f')} ask {fmt(ask, '.2f')} "
                f"mid {fmt(dec(leg.get('nbbo_mid')), '.3f')} spread {fmt(spread, '.2f')}"
            )
    lines += [
        "",
        f"mean P&L/max-risk {fmt(m['mean_ret'], '+.2%')} (t {fmt(m['t'], '.2f')}, "
        f"n priced {m['priced']}, quarantined {m['quarantined']}) vs expectation +{EXPECTATION_PCT}% · "
        f"total P&L ${m['total_pnl']:+.2f}",
        f"median credit vs model {fmt(m['median_credit_delta'], '+.4f')} "
        f"(kill < {CREDIT_SHORTFALL_LIMIT}) · abandon rate {fmt(m['abandon_rate'], '.0%')} "
        f"({m['abandoned']}/{m['gated']}, kill > {ABANDON_RATE_LIMIT:.0%})",
        "quarantined = fill with an unresolved mismatch / missing field: shown, kept out of "
        "the mean and total until --resolved-mismatch",
        "per-leg fill prices are not journaled for an mleg combo: slippage_vs_mid is per leg-set",
    ]
    if book.excluded_rows:
        lines.append(
            f"ATTRIBUTION: {len(book.excluded_rows)} journal row(s) matched only one of "
            f"'{CONDOR_PREFIX}' prefix / strategy_id={strategy} — excluded"
        )
    if book.live_rows is None:
        lines.append("live-journal hard-kill probe: NOT RUN (offline, no --live-journal-jsonl)")
    lines.append(
        "not machine-checked: condor-attributed account-cap trip flattening the mirror; "
        "U5 collector NBBO vs print model; U6"
    )
    lines.append("")
    for h in v.hard_kills:
        lines.append(f"HARD KILL  {h}")
    for k in v.kills:
        lines.append(f"{'KILL' if v.at_eval_point else 'kill (provisional)'}  {k}")
    lines.append(f"VERDICT: {v.verdict}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--tenant", default="staging_paper")
    p.add_argument("--strategy", default="gated_condor")
    p.add_argument("--as-of", type=dt.date.fromisoformat, default=None, help="ET date")
    p.add_argument("--paper-db", default="exec_alpaca_paper")
    p.add_argument("--live-db", default="exec_alpaca_live")
    p.add_argument("--audit-db", default="orchestrator")
    p.add_argument("--journal-jsonl")
    p.add_argument("--audit-jsonl")
    p.add_argument("--live-journal-jsonl")
    p.add_argument(
        "--resolved-mismatch",
        action="append",
        type=dt.date.fromisoformat,
        default=[],
        metavar="ATTEMPT_DATE",
        help="operator-confirmed resolution of that attempt's settlement mismatch",
    )
    a = p.parse_args(argv)
    for name in ("tenant", "strategy", "paper_db", "live_db", "audit_db"):
        if not _SAFE_ID.match(getattr(a, name)):
            p.error(f"bad --{name.replace('_', '-')}: {getattr(a, name)}")
    if bool(a.journal_jsonl) != bool(a.audit_jsonl):
        p.error("offline mode needs both --journal-jsonl and --audit-jsonl")

    if a.journal_jsonl:
        inputs = Inputs(
            jsonl_rows(a.journal_jsonl),
            jsonl_rows(a.audit_jsonl),
            jsonl_rows(a.live_journal_jsonl) if a.live_journal_jsonl else None,
        )
    else:
        inputs = Inputs(
            homelab_rows(a.paper_db, journal_sql(a.tenant, a.strategy)),
            homelab_rows(a.audit_db, audit_sql(a.tenant, a.strategy)),
            homelab_rows(a.live_db, live_journal_sql(a.strategy)),
        )
    as_of = a.as_of or dt.datetime.now(ET).date()
    book = build(inputs, a.strategy)
    v = evaluate(book, as_of, set(a.resolved_mismatch))
    print(render(book, v, a.tenant, a.strategy, as_of))
    return {"HARD_KILL": 2, "KILL": 1}.get(v.verdict, 0)


if __name__ == "__main__":
    sys.exit(main())
