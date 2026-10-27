"""H13.2 step 3: studentized max-t across the hypothesis's 6 variants on TEST (White reality-check style).
Variants: stock {SPY,QQQ} x {all,|r_first|>0.3%} on TEST_ALL 2022..2026 (pnl, net 1bp) + SPY 0DTE {all, big} on
2025-07..2026-10 (net return per $ premium). Joint day bootstrap over the union of calendar dates (B=10000); each
variant's bootstrap t* is centred at its own sample mean and computed on the resampled dates it has an observation for.
One-sided (predicted sign POSITIVE): p_adj(i) = P(max_j t*_j >= t_i)."""
import glob, numpy as np, pandas as pd
H = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r13/h132"
rng = np.random.default_rng(1313)
cols = {}
for sym in ["SPY", "QQQ"]:
    for lab in ["all", "abs_gt_0.3pct"]:
        s = pd.read_parquet(f"{H}/stock_pnl_{sym}_{lab}.parquet").iloc[:, 0]; s.index = pd.to_datetime(s.index)
        cols[f"{sym}|{lab}"] = s.loc["2022-01-01":"2026-12-31"]
for lab in ["all", "abs_gt_0.3pct"]:
    s = pd.read_parquet(f"{H}/opt_ret_{lab}_TEST_2025_07_2026_10.parquet").iloc[:, 0]; s.index = pd.to_datetime(s.index)
    cols[f"SPY0DTE|{lab}"] = s
M = pd.DataFrame(cols).sort_index(); A = M.values; n_obs = (~np.isnan(A)).sum(0)
mean = np.nanmean(A, 0); sd = np.nanstd(A, 0, ddof=1); t_obs = mean / (sd / np.sqrt(n_obs))
B = 10000; N = len(M); tmax = np.empty(B)
for b in range(B):
    idx = rng.integers(0, N, N); S = A[idx]; k = (~np.isnan(S)).sum(0)
    m = np.nanmean(S, 0); s = np.nanstd(S, 0, ddof=1)
    tmax[b] = np.max((m - mean) / (s / np.sqrt(k)))
p_adj = [(tmax >= t).mean() for t in t_obs]
R = pd.DataFrame({"variant": M.columns, "n": n_obs, "mean": mean, "t": t_obs, "p_raw_1sided": [1 - __import__("scipy.stats").stats.t.cdf(t, n - 1) for t, n in zip(t_obs, n_obs)],
                  "p_maxt_adj": p_adj, "pass_predicted_sign_TEST": [p < 0.05 and t > 0 for p, t in zip(p_adj, t_obs)]})
print(f"union of TEST dates: {N}; max observed t = {t_obs.max():.3f}; bootstrap max-t 95th pct = {np.percentile(tmax, 95):.3f}")
pd.set_option("display.width", 200); print(R.round(4).to_string(index=False)); R.to_csv(f"{H}/maxt_test.csv", index=False)
