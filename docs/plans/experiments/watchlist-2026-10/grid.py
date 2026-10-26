import pandas as pd, numpy as np, itertools
bars = pd.read_parquet("rth_1m.parquet"); bars["m"] = bars.t.dt.hour*60+bars.t.dt.minute-570
sess = {k: g.sort_values("m").reset_index(drop=True) for k, g in bars.groupby(["s","d"])}
e = pd.read_parquet("events.parquet"); e = e[(e.fired==1)&(e.gap==0)]
COST = 2e-4
TS, ST, TG = [15,30,60,385], [None,.0025,.005,.01], [None,.005,.01,.02]
def sim(g, sg, m0, p0, ts, st, tg):
    p = g[(g.m > m0) & (g.m <= min(m0+ts, 385))]
    for _, b in p.iterrows():
        adv = (b.l-p0)/p0 if sg==1 else (p0-b.h)/p0
        fav = (b.h-p0)/p0 if sg==1 else (p0-b.l)/p0
        if st and adv <= -st: return -st - COST
        if tg and fav >= tg: return tg - COST
    return (sg*(p.c.iloc[-1]-p0)/p0 if len(p) else 0) - COST
res = []
for kind in ["real","placebo"]:
    sub = e[e.kind==kind]
    if kind=="placebo": sub = pd.concat([x.sample(min(len(x),400), random_state=3) for _, x in sub.groupby("half")])
    for _, r in sub.iterrows():
        g = sess[(r.s, r.d)]; sg = 1 if r.dir=="ABOVE" else -1
        for ts, st, tg in itertools.product(TS, ST, TG):
            res.append((kind, r.half, r.d, ts, st, tg, sim(g, sg, r.fire_min, r.p0, ts, st, tg)))
R = pd.DataFrame(res, columns=["kind","half","d","ts","st","tg","ret"]).fillna({"st":0,"tg":0})
R.to_parquet("grid.parquet")
piv = R.groupby(["kind","half","ts","st","tg"]).ret.mean().unstack(["kind","half"])*1e4
piv = piv.sort_values(("real","FIT"), ascending=False)
print("variants:", len(piv)); print(piv.round(1).head(8)); print("..."); print(piv.round(1).tail(3))
best = piv.index[0]; print("\nBEST-ON-FIT", best, "-> TEST real", round(piv.loc[best, ("real","TEST")],1), "bps; TEST placebo", round(piv.loc[best, ("placebo","TEST")],1))
print("variants with FIT>0 AND TEST>0 (real):", ((piv[("real","FIT")]>0)&(piv[("real","TEST")]>0)).sum())
