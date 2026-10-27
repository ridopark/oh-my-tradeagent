"""Independent re-implementation of R1/R2 for D0 (PDH/PDL, PMH/PML), D1_5m pivots, D4 round.
Adds: (a) outcome from touch-bar close (f30) and next-bar open (g30) instead of from the level,
(b) 'xday' placebo: same relative distance from price at activation minute, applied on a random
other day of the same symbol/period (keeps distance/time-of-day distribution, destroys history),
(c) offset at touch = side*(lvl-c[j])/lvl (mechanical part of b30)."""
import pandas as pd, numpy as np, datetime as dt, sys
from multiprocessing import Pool
SYMS = "SPY,QQQ,NVDA,AMZN,TSLA,AAPL,INTC,PLTR,MSFT,META,GOOGL,MU".split(",")
def step_for(s, px):
    if s in ("SPY","QQQ"): return 5.0
    return 1.0 if px < 100 else (5.0 if px < 500 else 10.0)
def touch(c, h, l, lvl, frm):
    n = len(c)
    for j in range(max(frm, 1), n-31):
        p = c[j-1]
        if p < lvl*(1-.001) and h[j] >= lvl*(1-.0005): return j, 1
        if p > lvl*(1+.001) and l[j] <= lvl*(1+.0005): return j, -1
    return None
def out(o, c, j, lvl, side):
    b30 = side*(lvl-c[j+30])/lvl; f30 = side*(c[j]-c[j+30])/c[j]; g30 = side*(o[j+1]-c[j+31])/o[j+1]
    brk = bool(np.any(side*(c[j+1:j+31]-lvl)/lvl > .002)); off = side*(lvl-c[j])/lvl
    return b30, f30, g30, brk, off
def one(sym):
    rng = np.random.default_rng(SYMS.index(sym)+101)
    df = pd.read_json(f"decade/{sym}.jsonl.gz", lines=True, compression="gzip")
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df["d"] = df.t.dt.date
    df["mm"] = df.t.dt.hour*60+df.t.dt.minute
    rth = df[(df.mm>=570)&(df.mm<960)].copy(); rth["m"] = rth.mm-570
    pm = df[(df.mm>=240)&(df.mm<570)].groupby("d").agg(PMH=("h","max"), PML=("l","min"))
    sess = {d: g.sort_values("m") for d, g in rth.groupby("d")}; days = sorted(sess)
    daily = rth.groupby("d").agg(H=("h","max"), L=("l","min"), C=("c","last"))
    arr = {}
    for d in days:
        g = sess[d]; arr[d] = tuple(g[x].values for x in ("o","h","l","c","m"))
    def period(d): return "Y2026" if d >= dt.date(2026,1,1) else ("TEST" if d >= dt.date(2022,1,1) else "FIT")
    good = [k for k in range(5, len(days)) if len(arr[days[k]][3]) >= 300]
    byper = {}
    for k in good: byper.setdefault(period(days[k]), []).append(k)
    rows = []
    for k in good:
        d = days[k]; o, h, l, c, m = arr[d]; pc = daily.C.iloc[k-1]; per = period(d)
        defs = {"D0_pdhl": [(daily.H.iloc[k-1], 15), (daily.L.iloc[k-1], 15)],
                "D0_pmhl": [(pm.loc[d,"PMH"],15),(pm.loc[d,"PML"],15)] if d in pm.index else []}
        b5 = pd.DataFrame({"g": m//5, "h": h, "l": l}).groupby("g").agg(h=("h","max"), l=("l","min"))
        hh, ll, idx = b5.h.values, b5.l.values, b5.index.values
        defs["D1_5m"] = [(hh[i], (idx[i]+7)*5) for i in range(6, len(hh)-6) if hh[i] == hh[i-6:i+7].max()] + \
                        [(ll[i], (idx[i]+7)*5) for i in range(6, len(ll)-6) if ll[i] == ll[i-6:i+7].min()]
        st = step_for(sym, pc); base = np.floor(pc/st)*st
        defs["D4_round"] = [(base+i*st, 15) for i in range(-6, 7)]
        for name, lv in defs.items():
            lv = sorted({round(x,2): f for x, f in lv if np.isfinite(x) and abs(x/pc-1) <= .03}.items())
            merged = []
            for x, f in lv:
                if merged and abs(x/merged[-1][0]-1) < .001: continue
                merged.append((x, f))
            for x, f in merged:
                # position (positional index) of activation: first bar with minute >= f
                fpos = int(np.searchsorted(m, f))
                if fpos >= len(c)-31: continue
                ref = c[max(fpos-1, 0)]; rel = x/ref
                cands = [("real", x, d, o, h, l, c, fpos)]
                for _ in range(2):
                    u = rng.choice([-1, 1])*rng.uniform(.003, .01)
                    cands.append(("plac_orig", x*(1+u), d, o, h, l, c, fpos))
                for _ in range(2):  # cross-day relative placebo
                    k2 = byper[per][rng.integers(len(byper[per]))]
                    if k2 == k: continue
                    o2, h2, l2, c2, m2 = arr[days[k2]]; fp2 = int(np.searchsorted(m2, f))
                    if fp2 >= len(c2)-31: continue
                    cands.append(("plac_xday", rel*c2[max(fp2-1,0)], days[k2], o2, h2, l2, c2, fp2))
                for kind, lvl, dd, oo, hh_, ll_, cc, fp in cands:
                    t = touch(cc, hh_, ll_, lvl, fp)
                    if t: rows.append((sym, dd, per, name, kind, t[0], t[1], *out(oo, cc, t[0], lvl, t[1])))
    return pd.DataFrame(rows, columns=["s","d","half","defn","kind","j","side","b30","f30","g30","brk","off"])
if __name__ == "__main__":
    with Pool(6) as p: R = pd.concat(p.map(one, SYMS))
    R.to_parquet("review2/sr_re.parquet"); print(R.groupby(["defn","kind","half"]).size().unstack("half"))
