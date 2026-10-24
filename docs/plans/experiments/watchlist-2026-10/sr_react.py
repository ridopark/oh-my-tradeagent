"""Round-4 step 1: level-reaction study. One row per (definition, real|placebo, level touch)."""
import pandas as pd, numpy as np, sys, datetime as dt
from multiprocessing import Pool
rng0 = 17
def load(path):
    df = pd.read_json(path, lines=True, compression="gzip")
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df["d"] = df.t.dt.date
    mm = df.t.dt.hour*60 + df.t.dt.minute; df["mm"] = mm
    return df
def step_for(s, px):
    if s in ("SPY","QQQ"): return 5.0
    return 1.0 if px < 100 else (5.0 if px < 500 else 10.0)
def touches(o,h,l,c,lvl,frm):
    """first touch of static level from minute index frm; returns (j, side) side=+1 resistance(from below)"""
    n = len(c)
    for j in range(max(frm,1), n-30):
        p = c[j-1]
        if p < lvl*(1-.001) and h[j] >= lvl*(1-.0005): return j, 1
        if p > lvl*(1+.001) and l[j] <= lvl*(1+.0005): return j, -1
    return None
def outcome(c, j, lvl, side):
    b15 = side*(lvl - c[j+15])/lvl; b30 = side*(lvl - c[j+30])/lvl
    thr = c[j+1:j+31]
    brk = bool(np.any(side*(thr - lvl)/lvl > .002))
    return b15, b30, brk
def one(args):
    sym, path, split = args
    rng = np.random.default_rng(abs(hash(sym)) % 2**32 + rng0)
    df = load(path); mm = df.mm
    rth = df[(mm>=570)&(mm<960)].copy(); rth["m"] = rth.mm-570
    pm = df[(mm>=240)&(mm<570)].groupby("d").agg(PMH=("h","max"), PML=("l","min"))
    days = sorted(rth.d.unique()); sess = {d: g.sort_values("m") for d,g in rth.groupby("d")}
    daily = rth.groupby("d").agg(H=("h","max"), L=("l","min"), C=("c","last"))
    # per-day volume profile and vwap
    prof, vwapd = {}, {}
    for d, g in sess.items():
        tp = (g.h+g.l+g.c)/3; vwapd[d] = (tp*g.v).sum()/max(g.v.sum(),1)
        prof[d] = (tp.values, g.v.values)
    H, L = daily.H.values, daily.L.values
    piv_hi = {days[i]: H[i] for i in range(2, len(days)-2) if H[i] == H[i-2:i+3].max()}
    piv_lo = {days[i]: L[i] for i in range(2, len(days)-2) if L[i] == L[i-2:i+3].min()}
    rows = []
    for k in range(5, len(days)):
        d = days[k]; g = sess[d]
        if len(g) < 300: continue
        o,h,l,c,v,m = (g[x].values for x in ("o","h","l","c","v","m"))
        pc = daily.C.iloc[k-1]; within = lambda x: abs(x/pc-1) <= .03
        half = "TEST" if d >= split[0] else "FIT"
        if d >= split[1]: half = "Y2026"
        defs = {}
        defs["D0_pdhl_pmhl"] = [(daily.H.iloc[k-1],15),(daily.L.iloc[k-1],15)] + ([(pm.loc[d,"PMH"],15),(pm.loc[d,"PML"],15)] if d in pm.index else [])
        # daily pivots confirmed by day k-1 (pivot day i <= k-3), last 10
        ph = [piv_hi[x] for x in days[max(0,k-60):k-2] if x in piv_hi][-10:]; pl = [piv_lo[x] for x in days[max(0,k-60):k-2] if x in piv_lo][-10:]
        defs["D1_daily_pivots"] = [(x,15) for x in ph+pl]
        # 1h pivots from prior 5 sessions (k=3)
        hp = []
        for x in days[k-5:k]:
            gg = sess[x]; hb = gg.groupby(gg.m//60).agg(h=("h","max"), l=("l","min"))
            hh, ll = hb.h.values, hb.l.values
            hp += [hh[i] for i in range(3,len(hh)-3) if hh[i]==hh[i-3:i+4].max()] + [ll[i] for i in range(3,len(ll)-3) if ll[i]==ll[i-3:i+4].min()]
        defs["D1_1h_pivots"] = [(x,15) for x in hp]
        # 5m intraday pivots (k=6), active after confirmation
        b5 = g.groupby(g.m//5).agg(h=("h","max"), l=("l","min")); hh, ll, idx = b5.h.values, b5.l.values, b5.index.values
        defs["D1_5m_pivots"] = [(hh[i], (idx[i]+7)*5) for i in range(6,len(hh)-6) if hh[i]==hh[i-6:i+7].max()] + \
                               [(ll[i], (idx[i]+7)*5) for i in range(6,len(ll)-6) if ll[i]==ll[i-6:i+7].min()]
        # volume profile: prior-day POC + top-3 HVN peaks; 5-day composite POC
        def peaks(tp, vol, top):
            bw = pc*.001; bins = np.floor(tp/bw); s = pd.Series(vol).groupby(bins).sum()
            arr = s.values; ix = [i for i in range(1,len(arr)-1) if arr[i]>=arr[i-1] and arr[i]>=arr[i+1]]
            ix = sorted(ix, key=lambda i: -arr[i])[:top]; return [(s.index[i]+.5)*bw for i in ix]
        tp, vol = prof[days[k-1]]; defs["D2_vprofile"] = [(x,15) for x in peaks(tp, vol, 3)]
        tp5 = np.concatenate([prof[x][0] for x in days[k-5:k]]); v5 = np.concatenate([prof[x][1] for x in days[k-5:k]])
        defs["D2_vprofile"] += [(x,15) for x in peaks(tp5, v5, 1)]
        # VWAP: prior-day vwap, weekly anchored vwap as of prior close
        wk = [x for x in days[max(0,k-5):k] if x.isocalendar()[1] == days[k-1].isocalendar()[1]]
        wtp = np.concatenate([prof[x][0] for x in wk]); wv = np.concatenate([prof[x][1] for x in wk])
        defs["D3_vwap_static"] = [(vwapd[days[k-1]],15), ((wtp*wv).sum()/max(wv.sum(),1),15)]
        st = step_for(sym, pc); base = np.floor(pc/st)*st
        defs["D4_round"] = [(base+i*st,15) for i in range(-6,7)]
        for name, lv in defs.items():
            lv = sorted({round(x,2): f for x,f in lv if np.isfinite(x) and within(x)}.items())
            merged = []
            for x,f in lv:
                if merged and abs(x/merged[-1][0]-1) < .001: continue
                merged.append((x,f))
            for x,f in merged:
                for kind, lvl in [("real",x)] + [("placebo", x*(1+rng.choice([-1,1])*rng.uniform(.003,.01))) for _ in range(2)]:
                    t = touches(o,h,l,c,lvl,f)
                    if t: rows.append((sym,d,half,name,kind,m[t[0]],*outcome(c,t[0],lvl,t[1])))
        # running session VWAP (dynamic), from 09:45
        cv = np.cumsum(((h+l+c)/3)*v)/np.maximum(np.cumsum(v),1)
        for j in range(15, len(c)-30):
            lvl = cv[j-1]; p = c[j-1]
            side = 1 if (p < lvl*(1-.001) and h[j] >= lvl*(1-.0005)) else (-1 if (p > lvl*(1+.001) and l[j] <= lvl*(1+.0005)) else 0)
            if side: rows.append((sym,d,half,"D3_session_vwap","real",m[j],*outcome(c,j,lvl,side))); break
    return pd.DataFrame(rows, columns=["s","d","half","defn","kind","m","b15","b30","brk"])
if __name__ == "__main__":
    which = sys.argv[1]
    if which == "decade":
        syms = "SPY,QQQ,NVDA,AMZN,TSLA,AAPL,INTC,PLTR,MSFT,META,GOOGL,MU".split(",")
        jobs = [(s, f"decade/{s}.jsonl.gz", (dt.date(2022,1,1), dt.date(2026,1,1))) for s in syms]
        with Pool(6) as p: R = pd.concat(p.map(one, jobs))
        R.to_parquet("sr_decade.parquet")
    print(R.groupby(["defn","kind","half"]).size().unstack(["kind","half"]))
