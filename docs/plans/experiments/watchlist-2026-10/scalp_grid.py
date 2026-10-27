import pandas as pd, numpy as np, datetime as dt, itertools
bars = pd.read_parquet("rth_1m.parquet"); bars["m"] = bars.t.dt.hour*60+bars.t.dt.minute-570
sess = {k: g.sort_values("m").reset_index(drop=True) for k, g in bars.groupby(["s","d"])}
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"])
legs["d"] = pd.to_datetime(legs.d).dt.date; legs = legs.drop_duplicates(["d","s","dir","lvl"]); legs = legs[legs.d != dt.date(2026,7,1)]
V = [("fixed",T,S,None) for T in (10,20,30,50,75,100,150,200) for S in (10,20,30,50,100)]
V += [("trail",None,None,X) for X in (5,10,15,20,30,50,75,100)]
V += [("combo",T,None,X) for T in (30,50,100,200) for X in (10,20,30,50)]
def run(fav, adv, cls, kind, T, S, X):
    """fav/adv/cls: signed bps arrays (favorable high, adverse low, close) from entry, for bars after entry."""
    peak = 0.0
    for q in range(len(cls)):
        stop = -S if kind == "fixed" else peak - X          # trail from the running peak (initial = -X)
        if adv[q] <= stop: return stop, q
        if T is not None and fav[q] >= T: return T, q
        peak = max(peak, fav[q])
    return cls[-1], len(cls)-1
rows = []
for L in legs.itertuples():
    g = sess.get((L.s, L.d))
    if g is None or len(g) < 300: continue
    o,h,l,c,m = (g[x].values for x in "ohlcm"); sg = 1 if L.dir=="ABOVE" else -1
    if (o[0]-L.lvl)*sg > 0 or abs(L.lvl/o[0]-1) > .1: continue
    end = int(np.searchsorted(m, 386))                      # last usable bar = 15:55
    FAV = (sg*(h - L.lvl)/L.lvl*1e4) if sg==1 else (sg*(l - L.lvl)/L.lvl*1e4)
    ADV = (sg*(l - L.lvl)/L.lvl*1e4) if sg==1 else (sg*(h - L.lvl)/L.lvl*1e4)
    CLS = sg*(c - L.lvl)/L.lvl*1e4
    for vi,(kind,T,S,X) in enumerate(V):
        j = 1; k = 0
        while j < end-1:
            if (c[j-1]-L.lvl)*sg <= 0 and FAV[j] >= 0:
                res, q = run(FAV[j+1:end], ADV[j+1:end], CLS[j+1:end], kind, T, S, X)
                rows.append((L.s, L.d, vi, k, m[j], res, q+1)); k += 1; j = j+1+q+1
            else: j += 1
R = pd.DataFrame(rows, columns=["s","d","v","k","m","bps","hold"])
R["half"] = np.where(R.d >= dt.date(2026,9,1), "TEST", "FIT"); R["etf"] = R.s.isin(["SPY","QQQ"])
R.to_parquet("scalp_grid.parquet"); pd.DataFrame(V, columns=["kind","T","S","X"]).to_csv("scalp_grid_variants.csv", index_label="v")
print(len(R), "trades", len(V), "variants")
