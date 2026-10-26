import pandas as pd, numpy as np, datetime as dt
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.metrics import roc_auc_score
rng = np.random.default_rng(42)
bars = pd.read_parquet("rth_1m.parquet"); bars["m"] = bars.t.dt.hour*60+bars.t.dt.minute-570
sess = {k: g.sort_values("m").reset_index(drop=True) for k, g in bars.groupby(["s","d"])}
days = {}; [days.setdefault(s, []).append(d) for (s,d) in sess]
prev = {}
for s, ds in days.items():
    ds.sort()
    for a, b in zip(ds, ds[1:]): prev[(s,b)] = sess[(s,a)]
e = pd.read_parquet("events.parquet"); e = e[(e.kind=="real")&(e.fired==1)&(e.gap==0)&(e.d!=dt.date(2026,7,1))].copy()
rows = []
for r in e.itertuples():
    g = sess[(r.s, r.d)]; i = int(np.searchsorted(g.m.values, r.fire_min)); c, h, l, v, o = (g[x].values for x in "chlvo")
    if i < 5 or (r.s, r.d) not in prev: continue
    p = prev[(r.s, r.d)]; sg = 1 if r.dir=="ABOVE" else -1
    f = dict(fire_min=r.fire_min, etf=int(r.s in ("SPY","QQQ")), direction=sg, off=r.off*sg,
             mom5=sg*(c[i]/c[i-5]-1), mom15=sg*(c[i]/c[max(0,i-15)]-1), from_open=sg*(c[i]/o[0]-1),
             rng_so_far=(h[:i+1].max()-l[:i+1].min())/o[0], vol_ratio=v[max(0,i-5):i+1].mean()/max(v[:i+1].mean(),1),
             bar_body=sg*(c[i]-o[i])/o[i], prev_ret=sg*(p.c.values[-1]/p.c.values[0]-1), prev_rng=(p.h.max()-p.l.min())/p.c.values[-1],
             gap=sg*(o[0]/p.c.values[-1]-1), dow=r.d.weekday())
    rows.append(dict(f, d=r.d, y=int(r.r60 > 0) if pd.notna(r.r60) else None, r60=r.r60))
D = pd.DataFrame(rows).dropna(subset=["y"]).sort_values("d").reset_index(drop=True); X = D.drop(columns=["d","y","r60"]).values; y = D.y.values.astype(int)
print(f"n={len(D)} legs, {D.d.nunique()} days, base rate P(r60>0)={y.mean():.3f}")
months = sorted({(x.year, x.month) for x in D.d}); ym = D.d.map(lambda x: (x.year, x.month)).values
def walk_forward(yv):
    pred = np.full(len(D), np.nan)
    for k in range(1, len(months)):
        tr = np.array([m < months[k] for m in ym]); te = np.array([m == months[k] for m in ym])
        if tr.sum() < 60 or te.sum() == 0: continue
        mdl = GradientBoostingClassifier(n_estimators=150, max_depth=2, learning_rate=0.05, subsample=0.8, random_state=0).fit(X[tr], yv[tr])
        pred[te] = mdl.predict_proba(X[te])[:,1]
    ok = ~np.isnan(pred); auc = roc_auc_score(y[ok], pred[ok]); take = ok & (pred > 0.55)
    return auc, int(take.sum()), (D.r60.values[take].mean()*1e4 if take.sum() else np.nan), int(ok.sum())
auc, n_take, ev, n_oos = walk_forward(y)
print(f"REAL labels: walk-forward AUC={auc:.3f} on {n_oos} OOS legs | trades with p>0.55: {n_take}, mean r60 = {ev:+.1f} bps")
sh = [walk_forward(rng.permutation(y))[0] for _ in range(5)]
print("SHUFFLED-label control AUCs:", [round(a,3) for a in sh], "| mean", round(float(np.mean(sh)),3))
sd = D.r60.std()*1e4; absr = D.r60.abs().mean()*1e4
for acc in (0.52, 0.55, 0.60):
    edge = (2*acc-1)*absr; print(f"  a {acc:.2f}-accurate direction model -> {edge:.1f} bps/trade -> ~{(2.8*sd/edge)**2:,.0f} trades to confirm at 80% power (we have {len(D)})")
