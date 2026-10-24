import pandas as pd, numpy as np, sys
rng = np.random.default_rng(5)
R = pd.read_parquet(sys.argv[1]); tag = sys.argv[2]
R["v"] = R.hyp+"|st"+R.st.astype(str)+"|tg"+R.tg.astype(str)+"|ts"+R.ts.astype(str)
S = R.groupby(["v","hyp","half"]).ret.agg(["size","mean"]).unstack("half")
S.columns = [f"{a}_{b}" for a,b in S.columns]; S = S.reset_index()
# reality check on TEST: day-clustered bootstrap of max mean across all variants (White RC, demeaned)
T = R[R.half=="TEST"]; days = T.d.unique()
P = T.pivot_table(index="d", columns="v", values="ret", aggfunc="sum"); N = T.pivot_table(index="d", columns="v", values="ret", aggfunc="count")
P, N = P.fillna(0), N.fillna(0); mu = P.sum()/N.sum()
mx = []
for _ in range(2000):
    idx = rng.integers(0, len(days), len(days))
    mb = P.values[idx].sum(0)/np.maximum(N.values[idx].sum(0),1)
    mx.append(np.max(mb - mu.values))
mx = np.array(mx)
S["rc_p_test"] = S.v.map({v: (mx >= m).mean() for v,m in mu.items()})
# single-variant CI on TEST
def ci(v):
    x = P[v].values; n = N[v].values; idx = rng.integers(0,len(x),(2000,len(x)))
    b = x[idx].sum(1)/np.maximum(n[idx].sum(1),1); return np.percentile(b,[2.5,97.5])
S[["test_lo","test_hi"]] = np.array([ci(v) for v in S.v])
for c in ["mean_FIT","mean_TEST","test_lo","test_hi"]: S[c] = (S[c]*1e4).round(1)
S["both_pos"] = (S.mean_FIT>0)&(S.mean_TEST>0)
S["tag"] = tag; S.to_csv(f"ledger_{tag}.csv", index=False)
print(f"[{tag}] variants: {len(S)}  both-halves-positive: {S.both_pos.sum()}  min RC p (TEST): {S.rc_p_test.min():.3f}")
best = S.sort_values("mean_FIT", ascending=False).groupby("hyp").head(1)
print(best[["v","size_FIT","mean_FIT","size_TEST","mean_TEST","test_lo","test_hi","rc_p_test"]].to_string(index=False))
print("\nper-hypothesis median across 12 exits (bps):"); print(S.groupby("hyp")[["mean_FIT","mean_TEST"]].median())
