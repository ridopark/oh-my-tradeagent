#!/usr/bin/env python3
"""Completeness check and the single pre-registered analysis for watchlist-forward-v1.

Amendment A1 (docs/plans/PREREG-2026-10-23-watchlist-forward-v1-AMENDMENTS.md). The frozen scorer
`watchlist_forward.py` (tag `watchlist-forward-v1`) stays byte-identical: capture and scoring are
unchanged. This module replaces only its `check` (which counted a flow-only day as captured) and its
`analyze` (which left the F3 difference and the Holm correction to be applied by hand).

Usage (read-only):
  watchlist_forward_analysis.py --out ~/data/watchlist-forward --env ~/scripts/option-collector.env check
  watchlist_forward_analysis.py --out ~/data/watchlist-forward analyze      # refuses before 2027-04-23
"""
import argparse, csv, importlib.util, json, os, random, sys
from datetime import datetime

_spec = importlib.util.spec_from_file_location(
    "watchlist_forward", os.path.join(os.path.dirname(os.path.abspath(__file__)), "watchlist_forward.py"))
wf = importlib.util.module_from_spec(_spec)
sys.modules.setdefault("watchlist_forward", wf)
_spec.loader.exec_module(wf)

REQUIRED_RAW = ("legs.json", "stock_1m.ndjson.gz")
ALPHA = 0.05

# ---------- completeness ----------

def explained_gaps(out):
    """{date: written reason}. A list, or an empty reason, is rejected: the PREREG asks for a reason."""
    path = os.path.join(out, "explained_gaps.json")
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        gaps = json.load(f)
    if not isinstance(gaps, dict) or any(not isinstance(v, str) or not v.strip() for v in gaps.values()):
        sys.exit(f"{path} must map each date to a non-empty written reason")
    return gaps

def captured(out, day):
    """A day counts only when its manifest covers the end-of-day files (a flow-only day does not)."""
    path = os.path.join(out, "raw", day, "manifest.json")
    if not os.path.exists(path):
        return False
    with open(path) as f:
        files = json.load(f)
    return all(name in files for name in REQUIRED_RAW)

def missing_days(out, trading_days):
    gaps = explained_gaps(out)
    return [d for d in trading_days if not captured(out, d) and d not in gaps]

def check(out, headers, today):
    spy = wf.paged("/v2/stocks/bars", {"symbols": "SPY", "timeframe": "1Day", "feed": "sip", "limit": 10000,
                   "start": wf.START.isoformat(), "end": today.isoformat()}, headers, "bars").get("SPY", [])
    trading = sorted({b["t"][:10] for b in spy if b["t"][:10] < today.isoformat()})
    missing = missing_days(out, trading)
    print(f"trading days {len(trading)}  captured {sum(captured(out, d) for d in trading)}  "
          f"explained {len(set(explained_gaps(out)) & set(trading))}  missing {missing}")
    return missing

# ---------- inference ----------

def boot(days, stat, reps, seed=wf.SEED):
    """Day-clustered bootstrap. days: {day: rows}; stat(list of rows) -> float or None.
    Returns (estimate, lo95, hi95, two-sided percentile p) or Nones when < 3 days."""
    ks = sorted(days)
    est = stat([r for k in ks for r in days[k]])
    if len(ks) < 3 or est is None:
        return None, None, None, None
    rng, draws = random.Random(seed), []
    for _ in range(reps):
        v = stat([r for k in (ks[rng.randrange(len(ks))] for _ in ks) for r in days[k]])
        if v is not None:
            draws.append(v)
    draws.sort()
    n = len(draws)
    p = min(1.0, 2 * min(sum(v <= 0 for v in draws) / n, sum(v >= 0 for v in draws) / n))
    return est, draws[int(0.025 * n)], draws[int(0.975 * n) - 1], p

def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None

def holm(pvals):
    """Holm step-down adjusted p-values; None passes through and is not counted."""
    items = sorted(((p, k) for k, p in pvals.items() if p is not None))
    adj, running = {k: None for k in pvals}, 0.0
    for i, (p, k) in enumerate(items):
        running = max(running, min(1.0, (len(items) - i) * p))
        adj[k] = round(running, 12)
    return adj

def decide(metric, p_adj, gate=True):
    if metric is None:
        return "NOT_SHOWN"
    if metric <= 0:
        return "KILL"
    return "PASS" if (p_adj is not None and p_adj < ALPHA and gate) else "NOT_SHOWN"

def load_scores(out):
    with open(os.path.join(out, "scores.csv")) as f:
        rows = [{k: (None if v == "" else v) for k, v in r.items()} for r in csv.DictReader(f)]
    for r in rows:
        for k in ("r60", "rclose", "f2_net", "f3_z", "trig_m"):
            r[k] = None if r.get(k) is None else float(r[k])
    return rows

def by_day(rows):
    out = {}
    for r in rows:
        out.setdefault(r["d"], []).append(r)
    return out

def analyze(out, today, reps=10000):
    """The single look (PREREG 'Analysis — one look only' + amendment A1). Returns the result dict."""
    if today < wf.ANALYSIS_DATE:
        sys.exit(f"refusing: the single pre-registered look is on {wf.ANALYSIS_DATE}")
    rows = load_scores(out)
    fired = [r for r in rows if r["status"] == "fired" and r["r60"] is not None]
    real = [r for r in fired if r["arm"] == "real"]
    # F1: real minus placebo mean r60, resampling days (both arms of a day move together).
    f1 = boot(by_day(fired), lambda rs: (lambda a, b: None if a is None or b is None else a - b)(
        mean(r["r60"] for r in rs if r["arm"] == "real"),
        mean(r["r60"] for r in rs if r["arm"].startswith("placebo"))), reps)
    # F2: mean net per trade over real legs with an F2 entry.
    f2 = boot(by_day([r for r in rows if r["arm"] == "real" and r["f2_net"] is not None]),
              lambda rs: mean(r["f2_net"] for r in rs), reps)
    # F3: AGREE minus SILENT mean r60 (trigger >= 09:45 is already enforced by the scorer's tagging).
    tagged = [r for r in real if r.get("f3_tag") in ("AGREE", "SILENT", "DISAGREE")]
    diff = lambda a, b: (lambda rs: (lambda x, y: None if x is None or y is None else x - y)(
        mean(r["r60"] for r in rs if r["f3_tag"] == a), mean(r["r60"] for r in rs if r["f3_tag"] == b)))
    f3 = boot(by_day(tagged), diff("AGREE", "SILENT"), reps)
    f3_vs_dis = diff("AGREE", "DISAGREE")(tagged)
    p_adj = holm({"F1": f1[3], "F2": f2[3], "F3": f3[3]})
    res = {}
    for name, (est, lo, hi, p), gate in (("F1", f1, True), ("F2", f2, True),
                                         ("F3", f3, f3_vs_dis is not None and f3_vs_dis > 0)):
        res[name] = {"metric": est, "ci95": (lo, hi), "p": p, "p_holm": p_adj[name],
                     "decision": decide(est, p_adj[name], gate)}
    res["F3"]["agree_minus_disagree"] = f3_vs_dis
    res["n"] = {"days": len(by_day(real)), "real_fired": len(real),
                "f3": {t: sum(r["f3_tag"] == t for r in tagged) for t in ("AGREE", "SILENT", "DISAGREE")}}
    for name in ("F1", "F2", "F3"):
        r = res[name]
        fmt = lambda x: "n/a" if x is None else f"{x * 1e4:+.1f} bps"
        print(f"{name}: metric {fmt(r['metric'])}  95% CI [{fmt(r['ci95'][0])}, {fmt(r['ci95'][1])}]  "
              f"p {r['p']}  Holm p {r['p_holm']}  -> {r['decision']}")
    print(f"F3 AGREE - DISAGREE (gate): {f3_vs_dis}   n: {res['n']}")
    return res

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--env")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    sub.add_parser("analyze")
    a = ap.parse_args()
    out, today = os.path.expanduser(a.out), datetime.now(wf.ET).date()
    if a.cmd == "check":
        sys.exit(1 if check(out, wf.creds(a.env), today) else 0)
    analyze(out, today)

if __name__ == "__main__":
    main()
