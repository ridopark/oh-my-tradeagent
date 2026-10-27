import pandas as pd, numpy as np, datetime as dt, sys
from multiprocessing import Pool
SYMS = "SPY,QQQ,NVDA,AMZN,TSLA,AAPL,INTC,PLTR,MSFT,META,GOOGL,MU".split(",")
def sessions(sym):
    df = pd.read_json(f"decade/{sym}.jsonl.gz", lines=True, compression="gzip")
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York")
    mm = df.t.dt.hour*60 + df.t.dt.minute; df = df[(mm>=570)&(mm<960)].assign(m=mm-570)
    df["d"] = df.t.dt.date
    r2 = pd.read_parquet("rth_1m.parquet"); r2 = r2[(r2.s==sym)&(r2.d > df.d.max())]
    if len(r2): df = pd.concat([df[["d","m","o","h","l","c"]], r2.assign(m=r2.t.dt.hour*60+r2.t.dt.minute-570)[["d","m","o","h","l","c"]]])
    return {d: g.sort_values("m") for d, g in df.groupby("d")}
def levels_pivot(g, k):
    b = g.groupby(g.m//5).agg(h=("h","max"), l=("l","min")); hh, ll, ix = b.h.values, b.l.values, b.index.values
    out = []
    for i in range(k, len(hh)-k):
        act = (ix[i]+k+1)*5
        if hh[i] == hh[i-k:i+k+1].max(): out.append((hh[i], act))
        if ll[i] == ll[i-k:i+k+1].min(): out.append((ll[i], act))
    return out
def run_session(g, k, arm):
    o,h,l,c,m = (g[x].values for x in ("o","h","l","c","m"))
    if len(c) < 380: return []
    trades, busy_until = [], -1
    piv = levels_pivot(g, k) if arm == "pivot" else None
    used = set(); win = (2*k+1)*5
    for j in range(1, len(c)-31):
        if m[j] < 15 or m[j] > 354 or j <= busy_until: continue
        if arm == "pivot":
            cands = [(L, idx) for idx, (L, a) in enumerate(piv) if a <= m[j] and idx not in used]
        else:
            lo_, hi_ = m[j]-win-30, m[j]-30
            if lo_ < 0: continue
            sel = (m >= lo_) & (m <= hi_)
            cands = [(h[sel].max(), ("H", round(h[sel].max(),4))), (l[sel].min(), ("L", round(l[sel].min(),4)))]
            cands = [(L, key) for L, key in cands if key not in used]
        p = c[j-1]
        for L, key in cands:
            side = 1 if (p < L*(1-.001) and h[j] >= L*(1-.0005)) else (-1 if (p > L*(1+.001) and l[j] <= L*(1+.0005)) else 0)
            if side:
                used.add(key); e, x = o[j+1], c[j+30]
                trades.append((m[j], side, side*(x/e-1))); busy_until = j+30; break
    return trades
def one(sym):
    S = sessions(sym); rows = []
    for d, g in S.items():
        for k in (3,6,12):
            for arm in ("pivot","control"):
                for mj, side, r in run_session(g, k, arm): rows.append((sym, d, k, arm, mj, side, r))
    return pd.DataFrame(rows, columns=["s","d","k","arm","m","side","ret"])
if __name__ == "__main__":
    with Pool(6) as p: R = pd.concat(p.map(one, SYMS))
    R["period"] = np.where(R.d >= dt.date(2026,1,1), "HOLDOUT", np.where(R.d >= dt.date(2022,1,1), "TEST", "FIT"))
    R.to_parquet("pivot_strat.parquet"); print(R.groupby(["k","arm","period"]).size().unstack())
