"""Break-rate real-placebo, raw vs stratified by touch-time bucket (touch timing differs real vs placebo)."""
import pandas as pd, numpy as np
rng = np.random.default_rng(11)
R = pd.read_parquet("sr_decade.parquet"); R["brk"] = R.brk.astype(float); R["tb"] = (R.m//30).clip(upper=12)
def strat_diff(x):
    g = x.groupby(["tb","kind"]).brk.mean().unstack(); w = x[x.kind=="real"].tb.value_counts(normalize=True)
    return ((g.real-g.placebo)*w).sum()
for defn in ["D2_vprofile","D0_pdhl_pmhl","D3_vwap_static","D1_5m_pivots"]:
    for half in ["FIT","TEST","Y2026"]:
        x = R[(R.defn==defn)&(R.half==half)]
        days = x.d.unique(); grp = {d: g for d, g in x.groupby("d")}
        raw = x[x.kind=="real"].brk.mean()-x[x.kind=="placebo"].brk.mean(); st = strat_diff(x)
        bs = []
        for _ in range(300):
            s = pd.concat([grp[d] for d in rng.choice(days, len(days))]); bs.append(strat_diff(s))
        mr, mp = x[x.kind=="real"].m.mean(), x[x.kind=="placebo"].m.mean()
        print(f"{defn:15s} {half:5s} touch-min real {mr:5.0f} plac {mp:5.0f} | brk diff raw {raw*100:+.2f}pp  time-stratified {st*100:+.2f}pp [{np.percentile(bs,2.5)*100:+.2f},{np.percentile(bs,97.5)*100:+.2f}]")
