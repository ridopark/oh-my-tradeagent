"""Hypotheses H-a..H-e on level legs; triple-barrier exits; returns one row per (leg, hyp, exit)."""
import numpy as np, pandas as pd, itertools
COST = 2e-4
EXITS = list(itertools.product([.005,.01],[.005,.01,.02],[60,385]))

def entries(o,h,l,c,m,lvl,sg,volok):
    """yield (hyp, entry_idx, sign) for one leg on one session (arrays over RTH minutes)."""
    out = {}
    beyond = (c-lvl)*sg > 0
    gap = (o[0]-lvl)*sg > 0
    if gap:
        k = np.searchsorted(m, 5); out["e_gap"] = (k, sg)
        return out
    first = int(np.argmax(beyond)) if beyond.any() else None
    if first is not None and volok:
        # filter is only known at the close of the m=29 bar: enter then if still beyond, else at a later first break
        k = int(np.searchsorted(m, 29))
        if first >= k: out["a_break_vol"] = (first, sg)
        elif k < len(c) and (c[k]-lvl)*sg > 0: out["a_break_vol"] = (k, sg)
        else:
            later = np.where(beyond[k:])[0]
            if len(later): out["a_break_vol"] = (k + int(later[0]), sg)
    # b: touch & reject before any close beyond
    lim = first if first is not None else len(c)
    near = (h if sg==1 else -l)[:lim] >= (lvl*(1-.0005) if sg==1 else -lvl*(1+.0005))
    if near.any(): out["b_reject"] = (int(np.argmax(near)), -sg)
    # c: retest after break, from 10:30
    if first is not None:
        for j in range(first+1, len(c)-1):
            if (c[j]-lvl)*sg < -.002*lvl: break
            if m[j] < 60: continue
            back = (l[j] <= lvl*1.001) if sg==1 else (h[j] >= lvl*.999)
            if back and (c[j+1]-c[j])*sg > 0: out["c_retest"] = (j+1, sg); break
    # d: anticipation from 10:30
    for j in range(len(c)):
        if m[j] < 60: continue
        if first is not None and j >= first: break
        if 0 < (lvl-c[j])*sg <= .005*lvl: out["d_anticip"] = (j, sg); break
    return out

def barrier(h,l,c,m,i,sg,st,tg,ts):
    p0 = c[i]; end = min(m[i]+ts, 385)
    for j in range(i+1, len(c)):
        if m[j] > end: return sg*(c[j-1]-p0)/p0 - COST
        adv = (l[j]-p0)/p0 if sg==1 else (p0-h[j])/p0
        fav = (h[j]-p0)/p0 if sg==1 else (p0-l[j])/p0
        if adv <= -st: return -st - COST
        if fav >= tg: return tg - COST
    return sg*(c[-1]-p0)/p0 - COST

def run(legs, sess, volok_map):
    rows = []
    for L in legs.itertuples():
        g = sess.get((L.s, L.d))
        if g is None or len(g) < 300: continue
        o,h,l,c,m = (g[k].values for k in "ohlcm")
        sg = 1 if L.dir=="ABOVE" else -1
        for hyp,(i,s2) in entries(o,h,l,c,m,L.lvl,sg,volok_map.get((L.s,L.d),False)).items():
            if i >= len(c)-1: continue
            for st,tg,ts in EXITS:
                rows.append((L.s,L.d,L.dir,hyp,st,tg,ts,barrier(h,l,c,m,i,s2,st,tg,ts)))
    return pd.DataFrame(rows, columns=["s","d","dir","hyp","st","tg","ts","ret"])

def volok(sess):
    """opening-30m range / trailing-20d mean of same (>=5d history), top tercile cutoff fixed at 1.2 (pre-set)."""
    r = pd.Series({k: (g.h[g.m<30].max()-g.l[g.m<30].min())/g.o.iloc[0] for k,g in sess.items()})
    r.index = pd.MultiIndex.from_tuples(r.index); r = r.sort_index()
    ratio = r / r.groupby(level=0).transform(lambda x: x.shift(1).rolling(20, min_periods=5).mean())
    return {k: bool(v > 1.2) for k,v in ratio.items()}

def run_overnight(legs, sess, volok_map):
    days = {}
    for (s,d) in sess: days.setdefault(s, []).append(d)
    nxt = {}
    for s, ds in days.items():
        ds.sort(); nxt.update({(s,a): b for a,b in zip(ds, ds[1:])})
    rows = []
    for L in legs.itertuples():
        g = sess.get((L.s, L.d)); nd = nxt.get((L.s, L.d))
        if g is None or len(g) < 300 or nd is None: continue
        g2 = sess[(L.s, nd)]
        o,h,l,c,m = (g[k].values for k in "ohlcm")
        sg = 1 if L.dir=="ABOVE" else -1
        for hyp,(i,s2) in entries(o,h,l,c,m,L.lvl,sg,volok_map.get((L.s,L.d),False)).items():
            p0 = c[i]
            rows.append((L.s,L.d,L.dir,hyp,0,0,"next_open", s2*(g2.o.iloc[0]-p0)/p0-COST))
            rows.append((L.s,L.d,L.dir,hyp,0,0,"next_close", s2*(g2.c.iloc[-1]-p0)/p0-COST))
    return pd.DataFrame(rows, columns=["s","d","dir","hyp","st","tg","ts","ret"])
