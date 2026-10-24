import pandas as pd, numpy as np, datetime as dt, gzip, json, itertools
rng = np.random.default_rng(23)
rows = []
import sys
SRC, OUT = (sys.argv[1], sys.argv[2]) if len(sys.argv) > 2 else ("ticks_spyqqq.jsonl.gz", "tick_replay.parquet")
for src in SRC.split(","):
  with gzip.open(src, "rt") as f:
    for l in f:
        r = json.loads(l)
        if r["c"] and any(x in ("I","W","Z","T","U","4","7","9") for x in r["c"]): continue   # odd-lot/late/out-of-seq prints excluded
        rows.append((r["sym"], r["d"], r["dir"], r["lvl"], r["t"], r["p"], r["s"]))
T = pd.DataFrame(rows, columns=["sym","d","dir","lvl","t","p","sz"]); T["t"] = pd.to_datetime(T.t, utc=True)
print(len(T), "trades after condition filter;", T.groupby(["sym","d","dir"]).ngroups, "legs")
V = [("trail",None,None,X) for X in (3,5,10,15,20,30)] + [("fixed",T_,S,None) for T_ in (20,50,100,200) for S in (5,10,20)] + [("combo",100,None,10),("combo",200,None,10)]
out = []
for (sym,d,dr,lvl), g in T.groupby(["sym","d","dir","lvl"]):
    g = g.sort_values("t"); p = g.p.values; sg = 1 if dr=="ABOVE" else -1
    bps = sg*(p - lvl)/lvl*1e4            # signed distance of each print from the level
    for vi,(kind,T_,S,X) in enumerate(V):
        i, k = 1, 0
        while i < len(p):
            if bps[i-1] < 0 and bps[i] >= 0:                      # first print through the level after being on the near side
                e = bps[i]; peak = e; q = i+1; res = None
                while q < len(p):
                    stop = (e - S) if kind=="fixed" else (peak - X)
                    if bps[q] <= stop: res = bps[q] - e; break      # filled AT the print that triggers it
                    if T_ is not None and bps[q] >= e + T_: res = bps[q] - e; break
                    peak = max(peak, bps[q]); q += 1
                if res is None: res = bps[-1] - e; q = len(p)-1
                out.append((sym, d, dr, vi, k, res, (g.t.iloc[min(q,len(p)-1)] - g.t.iloc[i]).total_seconds())); k += 1; i = q+1
            else: i += 1
R = pd.DataFrame(out, columns=["s","d","dir","v","k","bps","secs"]); R["d"] = pd.to_datetime(R.d).dt.date
R["half"] = np.where(R.d >= dt.date(2026,9,1), "TEST", "FIT"); R.to_parquet(OUT)
lab = {i: f"{k} T={T_} S={S} X={X}" for i,(k,T_,S,X) in enumerate(V)}
def ci(x):
    g = x.groupby("d").bps.agg(["sum","count"]); i = rng.integers(0,len(g),(3000,len(g)))
    b = g["sum"].values[i].sum(1)/g["count"].values[i].sum(1); return f"[{np.percentile(b,2.5):+.1f},{np.percentile(b,97.5):+.1f}]"
pd.set_option("display.width", 250)
S_ = R.groupby(["v","half"]).apply(lambda x: pd.Series(dict(n=len(x), per_leg=round(len(x)/x.groupby(["s","d","dir"]).ngroups,1), win=round((x.bps>0).mean(),2), gross=round(x.bps.mean(),2), ci=ci(x), med_secs=int(x.secs.median())))).unstack("half")
S_.index = S_.index.map(lab); print(S_.to_string())
print("\nspread hurdle (round-trip, underlying-equivalent): SPY/QQQ stock ~0.3 bps; 0DTE near-money option 2% RT / lambda 150-230 = 0.9-1.3 bps")
