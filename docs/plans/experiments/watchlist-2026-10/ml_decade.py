import pandas as pd, numpy as np, datetime as dt, sys, warnings; warnings.filterwarnings("ignore")
from multiprocessing import Pool
SYMS = "SPY,QQQ,NVDA,AMZN,TSLA,AAPL,INTC,PLTR,MSFT,META,GOOGL,MU".split(",")
def one(sym):
    df = pd.read_json(f"decade/{sym}.jsonl.gz", lines=True, compression="gzip")
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df["d"] = df.t.dt.date; mm = df.t.dt.hour*60+df.t.dt.minute
    pm = df[(mm>=240)&(mm<570)].groupby("d").agg(PMH=("h","max"), PML=("l","min"), n=("c","size")); pm = pm[pm.n>=10]
    rth = df[(mm>=570)&(mm<960)].assign(m=mm[(mm>=570)&(mm<960)]-570)
    days = sorted(rth.d.unique()); rows = []; prevc = None; prevg = None
    for d, g in rth.groupby("d"):
        g = g.sort_values("m"); o,h,l,c,v,m = (g[x].values for x in "ohlcvm")
        if d in pm.index and prevg is not None and len(c) >= 380:
            for lvl, sg in ((pm.loc[d,"PMH"], 1), (pm.loc[d,"PML"], -1)):
                if (o[0]-lvl)*sg > 0: continue
                hit = np.where((c-lvl)*sg > 0)[0]
                if not len(hit): continue
                i = hit[0]
                if i < 5 or i+60 >= len(c): continue
                rows.append(dict(s=sym, d=d, sg=sg, fire_min=int(m[i]), etf=int(sym in ("SPY","QQQ")), off=sg*(lvl/prevc-1),
                    mom5=sg*(c[i]/c[i-5]-1), mom15=sg*(c[i]/c[max(0,i-15)]-1), from_open=sg*(c[i]/o[0]-1), rng_so_far=(h[:i+1].max()-l[:i+1].min())/o[0],
                    vol_ratio=v[max(0,i-5):i+1].mean()/max(v[:i+1].mean(),1), bar_body=sg*(c[i]-o[i])/o[i], prev_ret=sg*(prevg[1]/prevg[0]-1), prev_rng=prevg[2],
                    gap=sg*(o[0]/prevc-1), dow=d.weekday(), r60=sg*(c[i+60]/c[i]-1)))
        prevc = c[-1]; prevg = (c[0], c[-1], (h.max()-l.min())/c[-1])
    return pd.DataFrame(rows)
if __name__ == "__main__":
    with Pool(6) as p: D = pd.concat(p.map(one, SYMS)).reset_index(drop=True)
    D.to_parquet("ml_decade_legs.parquet"); D["r60b"] = D.r60*1e4
    D["per"] = np.where(D.d >= dt.date(2026,1,1), "2026", np.where(D.d >= dt.date(2022,1,1), "TEST", "FIT"))
    print(f"{len(D)} generic PMH/PML break legs, {D.d.nunique()} days")
    q = D[D.per=="FIT"].mom5.quantile([1/3,2/3]).values; D["terc"] = np.where(D.mom5 <= q[0], "low", np.where(D.mom5 <= q[1], "mid", "high"))
    pd.set_option("display.width", 200)
    print("r60 (bps) by mom5 tercile (cuts fixed on FIT):"); print(D.groupby(["per","terc"]).r60b.agg(n="size", mean="mean").round(1).unstack("terc").to_string())
    print("by class, high minus low tercile:"); print((D.groupby(["per","etf","terc"]).r60b.mean().unstack("terc").assign(hl=lambda t: t.high-t.low)*1).round(1)[["low","high","hl"]].to_string())
    # GBM walk-forward by YEAR with the same 14 features
    from sklearn.ensemble import GradientBoostingClassifier
    from sklearn.metrics import roc_auc_score
    feats = ["fire_min","etf","sg","off","mom5","mom15","from_open","rng_so_far","vol_ratio","bar_body","prev_ret","prev_rng","gap","dow"]
    X = D[feats].values; y = (D.r60 > 0).astype(int).values; yr = pd.to_datetime(D.d).dt.year.values
    pred = np.full(len(D), np.nan)
    for Y in range(2018, 2027):
        tr, te = yr < Y, yr == Y
        mdl = GradientBoostingClassifier(n_estimators=200, max_depth=3, learning_rate=0.05, subsample=0.8, random_state=0).fit(X[tr], y[tr]); pred[te] = mdl.predict_proba(X[te])[:,1]
    ok = ~np.isnan(pred)
    print(f"\nGBM walk-forward by year, 2018-2026: AUC={roc_auc_score(y[ok], pred[ok]):.3f} on {ok.sum()} legs")
    for Y in range(2018, 2027):
        te = (yr == Y); sel = te & (pred > 0.55)
        print(f"  {Y}: AUC={roc_auc_score(y[te], pred[te]):.3f}  all legs r60={D.r60b[te].mean():+.1f}  selected(p>0.55) n={sel.sum()} r60={D.r60b[sel].mean() if sel.sum() else float('nan'):+.1f} bps")
    rng = np.random.default_rng(1); nulls = []
    for s in range(10):
        yp = rng.permutation(y); pp = np.full(len(D), np.nan)
        for Y in range(2018, 2027):
            tr, te = yr < Y, yr == Y; pp[te] = GradientBoostingClassifier(n_estimators=200, max_depth=3, learning_rate=0.05, subsample=0.8, random_state=0).fit(X[tr], yp[tr]).predict_proba(X[te])[:,1]
        nulls.append(roc_auc_score(y[ok], pp[ok]))
    print("shuffled-label null AUC (10 runs):", np.round(nulls,3), "mean", round(float(np.mean(nulls)),3))
    imp = GradientBoostingClassifier(n_estimators=200, max_depth=3, learning_rate=0.05, subsample=0.8, random_state=0).fit(X[yr<2022], y[yr<2022]).feature_importances_
    print("feature importance (fit 2016-2021):", dict(sorted(zip(feats, imp.round(3)), key=lambda kv: -kv[1])))
