import pandas as pd, numpy as np, datetime as dt, warnings; warnings.filterwarnings("ignore")
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import roc_auc_score
exec(open("ml_check.py").read().split("D = pd.DataFrame(rows)")[0])          # rebuild the same feature table
D = pd.DataFrame(rows).dropna(subset=["y"]).sort_values("d").reset_index(drop=True)
feats = [c for c in D.columns if c not in ("d","y","r60")]; X = D[feats].values; y = D.y.values.astype(int); r60 = D.r60.values*1e4
months = sorted({(x.year, x.month) for x in D.d}); ym = D.d.map(lambda x: (x.year, x.month)).values
rng = np.random.default_rng(7)
def wf(yv, model="gbm", seed=0, thr=0.55, Xm=X):
    pred = np.full(len(D), np.nan)
    for k in range(1, len(months)):
        tr = np.array([m < months[k] for m in ym]); te = np.array([m == months[k] for m in ym])
        if tr.sum() < 60 or te.sum() == 0: continue
        mdl = (GradientBoostingClassifier(n_estimators=150, max_depth=2, learning_rate=0.05, subsample=0.8, random_state=seed) if model=="gbm"
               else make_pipeline(StandardScaler(), LogisticRegression(C=0.3, max_iter=5000)))
        mdl.fit(Xm[tr], yv[tr]); pred[te] = mdl.predict_proba(Xm[te])[:,1]
    ok = ~np.isnan(pred); take = ok & (pred > thr)
    return roc_auc_score(y[ok], pred[ok]), take, pred, ok
auc, take, pred, ok = wf(y)
def dci(mask):
    sub = D[mask]; g = sub.groupby("d").r60.agg(["sum","count"]); i = rng.integers(0,len(g),(4000,len(g)))
    b = g["sum"].values[i].sum(1)/g["count"].values[i].sum(1)*1e4; return np.percentile(b,[2.5,97.5]), len(g)
ci, nd = dci(take); print(f"REAL: AUC={auc:.3f} | selected n={take.sum()} over {nd} days, mean r60={r60[take].mean():+.1f} bps, day-clustered CI [{ci[0]:+.0f},{ci[1]:+.0f}]")
print("  selected legs by direction:", D[take].direction.value_counts().to_dict(), "| by month:", D[take].d.map(lambda x:x.month).value_counts().sort_index().to_dict())
print("  trivial baselines on the same OOS legs: all BELOW mean r60 =", f"{r60[ok & (D.direction==-1)].mean():+.1f}", "| all ABOVE =", f"{r60[ok & (D.direction==1)].mean():+.1f}", "| all OOS =", f"{r60[ok].mean():+.1f}")
# 50-run shuffled-label null for BOTH statistics
null_auc, null_ev = [], []
for s in range(50):
    a, t, _, _ = wf(rng.permutation(y)); null_auc.append(a); null_ev.append(r60[t].mean() if t.sum() >= 10 else np.nan)
null_auc, null_ev = np.array(null_auc), np.array(null_ev)
print(f"NULL (50 shuffles): AUC mean {null_auc.mean():.3f} sd {null_auc.std():.3f} max {null_auc.max():.3f} -> P(null AUC >= {auc:.3f}) = {(null_auc>=auc).mean():.2f}")
print(f"                    selected-EV mean {np.nanmean(null_ev):+.1f} sd {np.nanstd(null_ev):.1f} max {np.nanmax(null_ev):+.1f} -> P(null EV >= {r60[take].mean():.1f}) = {(null_ev>=r60[take].mean()).mean():.2f}")
# seeds / model / threshold / leave-one-feature-out
print("GBM seeds 1-5 (AUC, selected EV):", [(round(wf(y,seed=s)[0],3), round(r60[wf(y,seed=s)[1]].mean(),1)) for s in range(1,6)])
a2, t2, _, _ = wf(y, model="logit"); print(f"Logistic: AUC={a2:.3f}, selected n={t2.sum()}, EV={r60[t2].mean() if t2.sum() else float('nan'):+.1f}")
for thr in (0.50, 0.60, 0.65): _, t3, _, _ = wf(y, thr=thr); print(f"  thr {thr}: n={t3.sum()} EV={r60[t3].mean() if t3.sum() else float('nan'):+.1f}")
imp = GradientBoostingClassifier(n_estimators=150, max_depth=2, learning_rate=0.05, subsample=0.8, random_state=0).fit(X, y).feature_importances_
print("feature importance (full fit):", dict(sorted(zip(feats, imp.round(3)), key=lambda kv: -kv[1])))
loo = {f: round(wf(y, Xm=D[[c for c in feats if c!=f]].values)[0],3) for f in feats}; print("AUC leaving one feature out:", loo)
