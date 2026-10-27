import pandas as pd, numpy as np, datetime as dt, warnings, time; warnings.filterwarnings("ignore")
from sklearn.metrics import roc_auc_score
from tabpfn import TabPFNClassifier
exec(open("ml_check.py").read().split("D = pd.DataFrame(rows)")[0])
D = pd.DataFrame(rows).dropna(subset=["y"]).sort_values("d").reset_index(drop=True)
feats = [c for c in D.columns if c not in ("d","y","r60")]; X = D[feats].values; y = D.y.values.astype(int); r60 = D.r60.values*1e4
months = sorted({(x.year, x.month) for x in D.d}); ym = D.d.map(lambda x: (x.year, x.month)).values
rng = np.random.default_rng(5)
def wf(yv, thr=0.55):
    pred = np.full(len(D), np.nan)
    for k in range(1, len(months)):
        tr = np.array([m < months[k] for m in ym]); te = np.array([m == months[k] for m in ym])
        if tr.sum() < 60 or te.sum() == 0: continue
        clf = TabPFNClassifier(device="cpu", random_state=0).fit(X[tr], yv[tr]); pred[te] = clf.predict_proba(X[te])[:,1]
    ok = ~np.isnan(pred); take = ok & (pred > thr); return roc_auc_score(y[ok], pred[ok]), take, ok
t0 = time.time(); auc, take, ok = wf(y)
g = D[take].groupby("d").r60.agg(["sum","count"]); i = rng.integers(0,len(g),(4000,len(g))); b = g["sum"].values[i].sum(1)/g["count"].values[i].sum(1)*1e4
print(f"TabPFN 2026 legs: walk-forward AUC={auc:.3f} on {ok.sum()} OOS | selected n={take.sum()} over {len(g)} days, EV={r60[take].mean():+.1f} bps, day-CI [{np.percentile(b,2.5):+.0f},{np.percentile(b,97.5):+.0f}]  ({time.time()-t0:.0f}s)")
nulls = [wf(rng.permutation(y)) for _ in range(10)]
print("  shuffled-label null AUC (10):", [round(a,3) for a,_,_ in nulls], "| selected EV:", [round(float(r60[t].mean()),1) if t.sum()>=10 else None for _,t,_ in nulls])
# decade replication: yearly walk-forward, training subsampled to 8k rows (TabPFN context limit), same 14 features
Dd = pd.read_parquet("ml_decade_legs.parquet"); fd = ["fire_min","etf","sg","off","mom5","mom15","from_open","rng_so_far","vol_ratio","bar_body","prev_ret","prev_rng","gap","dow"]
Xd = Dd[fd].values; yd = (Dd.r60 > 0).astype(int).values; yr = pd.to_datetime(Dd.d).dt.year.values; rd = Dd.r60.values*1e4; pred = np.full(len(Dd), np.nan)
for Y in range(2018, 2027):
    tr = np.where(yr < Y)[0]; te = yr == Y
    if len(tr) > 8000: tr = rng.choice(tr, 8000, replace=False)
    pred[te] = TabPFNClassifier(device="cpu", random_state=0).fit(Xd[tr], yd[tr]).predict_proba(Xd[te])[:,1]
okd = ~np.isnan(pred); print(f"TabPFN decade (21k generic breaks): AUC={roc_auc_score(yd[okd], pred[okd]):.3f}")
for Y in range(2018, 2027):
    te = yr == Y; sel = te & (pred > 0.55); print(f"  {Y}: AUC={roc_auc_score(yd[te], pred[te]):.3f} all={rd[te].mean():+.1f} selected n={sel.sum()} EV={rd[sel].mean() if sel.sum() else float('nan'):+.1f} bps")
