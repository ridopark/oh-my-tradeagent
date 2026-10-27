#!/usr/bin/env python3
"""The single pre-registered analysis for watchlist-forward-ml-v1, made mechanical.

Amendment A7 (docs/plans/PREREG-2026-10-24-watchlist-forward-ml-v1-AMENDMENTS.md). The frozen scorer
`watchlist_forward_ml.py` (tag `watchlist-forward-ml-v1`) stays byte-identical: scoring is unchanged. This
module reads `ml_scores.csv`, applies amendment A1 (a day with two levels on one symbol and direction is
excluded) and the PREREG's PASS/KILL rule with Holm across M1/M2, and never writes.

Usage (read-only):
  watchlist_forward_ml_analysis.py --out ~/data/watchlist-forward analyze      # refuses before 2027-04-23
"""
import argparse, importlib.util, os, sys
from datetime import datetime
import numpy as np, pandas as pd

def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(os.path.abspath(__file__)), f"{name}.py"))
    m = importlib.util.module_from_spec(spec); sys.modules.setdefault(name, m); spec.loader.exec_module(m); return m
ml = _load("watchlist_forward_ml")            # frozen: ANALYSIS_DATE, ET
wfa = _load("watchlist_forward_analysis")     # v1's day-clustered boot, holm, decide, mean

SEED = 20261024
MODELS = (("M1", "p_tabpfn24", "pick_tabpfn24"), ("M2", "p_gbm14", "pick_gbm14"))

def load_scores(out):
    """Scored legs with an r60, minus amendment A1's cross-joined groups. Returns (rows, excluded groups)."""
    R = pd.read_csv(os.path.join(out, "ml_scores.csv"))
    R = R[(R.scored == 1) & R.r60.notna()]
    dup = R.groupby(["d", "s", "dir"]).lvl.transform("nunique") > 1
    return R[~dup], int(R[dup].groupby(["d", "s", "dir"]).ngroups)

def auc_perm(y, p, n_perm, seed=SEED):
    """(AUC, null 95th percentile, one-sided permutation p) or Nones when the labels have one class."""
    from sklearn.metrics import roc_auc_score
    if len(y) < 2 or y.min() == y.max():
        return None, None, None
    rng = np.random.default_rng(seed); auc = float(roc_auc_score(y, p))
    null = np.array([roc_auc_score(rng.permutation(y), p) for _ in range(n_perm)])
    return auc, float(np.percentile(null, 95)), (1 + int((null >= auc).sum())) / (n_perm + 1)

def analyze(out, today, reps=10000, n_perm=1000):
    """The single look (PREREG 'Analysis — one look' + amendment A7). Returns the result dict."""
    if today < ml.ANALYSIS_DATE:
        sys.exit(f"refusing: the single pre-registered look is on {ml.ANALYSIS_DATE}")
    R, excluded = load_scores(out)
    res = {"n": {"legs": len(R), "days": int(R.d.nunique()), "excluded_a1": excluded,
                 "m1_missing": int(R.p_tabpfn24.isna().sum()),
                 "earnings_nan_rate": float(R.earnings_day.isna().mean()) if len(R) and "earnings_day" in R else None}}
    pvals = {}
    for name, pcol, kcol in MODELS:
        x = R[R[pcol].notna()]; y = (x.r60 > 0).astype(int).values
        auc, null95, p_auc = auc_perm(y, x[pcol].values, n_perm)
        picks = x[x[kcol] == 1]
        est, lo, hi, p_picks = wfa.boot({d: g.r60.tolist() for d, g in picks.groupby("d")}, wfa.mean, reps, seed=SEED)
        pvals[name] = None if p_auc is None or p_picks is None else max(p_auc, p_picks)
        res[name] = {"n": len(x), "auc": auc, "null95": null95, "p_auc": p_auc, "picks": len(picks),
                     "pick_days": int(picks.d.nunique()), "metric": est, "ci95": (lo, hi), "p_picks": p_picks,
                     "non_picks_mean": float(x[x[kcol] == 0].r60.mean()) if (x[kcol] == 0).any() else None}
    p_adj = wfa.holm(pvals)
    fmt = lambda v: "n/a" if v is None else f"{v * 1e4:+.1f} bps"
    for name, _, _ in MODELS:
        r = res[name]; r["p"] = pvals[name]; r["p_holm"] = p_adj[name]; r["decision"] = wfa.decide(r["metric"], p_adj[name])
        print(f"{name}: n {r['n']}  AUC {r['auc']} (null95 {r['null95']}, p {r['p_auc']})  picks {r['picks']} on {r['pick_days']} days  "
              f"mean r60 {fmt(r['metric'])}  95% CI [{fmt(r['ci95'][0])}, {fmt(r['ci95'][1])}]  p {r['p']}  Holm p {r['p_holm']}  "
              f"non-picks {fmt(r['non_picks_mean'])}  -> {r['decision']}")
    print(f"n: {res['n']}")
    return res

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_subparsers(dest="cmd", required=True).add_parser("analyze")
    a = ap.parse_args()
    analyze(os.path.expanduser(a.out), datetime.now(ml.ET).date())

if __name__ == "__main__":
    main()
