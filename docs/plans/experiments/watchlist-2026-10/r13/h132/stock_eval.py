"""H13.2 step 1: stock replication of Gao-Han-Li-Zhou (2018) on SPY/QQQ.
r_first = c(10:00)/prev_close - 1 ; r_last = c(16:00)/c(15:30) - 1.
Regression r_last ~ r_first per half (OLS, Newey-West 5 lags). Sign-following trade: pnl = sign(r_first)*r_last - 1bp.
Halves: FIT 2016-2021 ; TEST 2022-2025 ; 2026 separately ; TEST_ALL = 2022..2026 (pass rule uses TEST_ALL, PREREG:
"TEST 2022-2025 + 2026"). Variants: {all, |r_first|>0.3%} x {SPY, QQQ}. Descriptive only: top-tercile |r_first|,
high first-half-hour volume (> trailing 60-session median, no look-ahead).
"""
import numpy as np, pandas as pd
OUT = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r13/h132"
COST = 1e-4
rng = np.random.default_rng(13)
HALVES = {"FIT_2016_21": ("2016-01-01", "2021-12-31"), "TEST_2022_25": ("2022-01-01", "2025-12-31"),
          "T2026": ("2026-01-01", "2026-12-31"), "TEST_ALL_2022_26": ("2022-01-01", "2026-12-31")}

def nw_ols(y, x, lags=5):
    X = np.column_stack([np.ones(len(x)), x]); b = np.linalg.lstsq(X, y, rcond=None)[0]
    e = y - X @ b; n = len(y); XtX_inv = np.linalg.inv(X.T @ X)
    S = (X * e[:, None]).T @ (X * e[:, None])
    for L in range(1, lags + 1):
        w = 1 - L / (lags + 1); G = (X[L:] * e[L:, None]).T @ (X[:-L] * e[:-L, None]); S += w * (G + G.T)
    V = XtX_inv @ S @ XtX_inv
    r2 = 1 - e.var() / y.var()
    return b[1], b[1] / np.sqrt(V[1, 1]), r2, n

def boot_ci(p, B=5000):
    idx = rng.integers(0, len(p), (B, len(p))); m = p[idx].mean(1); return np.percentile(m, [2.5, 97.5])

def trade_stats(df, mask, label):
    """df: half's daily frame; mask: traded days. Sharpe uses all days of the half with 0 on untraded days."""
    p = df.pnl.where(mask, 0.0).values; pt = df.pnl[mask].values
    if len(pt) < 5: return None
    lo, hi = boot_ci(pt); sh = np.sqrt(252) * p.mean() / p.std() if p.std() > 0 else np.nan
    q = np.percentile(pt, [1, 99]); trim = pt[(pt > q[0]) & (pt < q[1])]
    return dict(variant=label, n=len(pt), mean_bps=1e4 * pt.mean(), ci_lo_bps=1e4 * lo, ci_hi_bps=1e4 * hi, t=pt.mean() / pt.std() * np.sqrt(len(pt)),
                sharpe=sh, win=(pt > 0).mean(), mean_trim1pct_bps=1e4 * trim.mean(), n_trim=len(trim),
                top5_share=np.sort(pt)[-5:].sum() / pt.sum() if pt.sum() != 0 else np.nan)

rows, regs, series = [], [], {}
for sym in ["SPY", "QQQ"]:
    d = pd.read_parquet(f"{OUT}/daily_{sym}.parquet"); d.index = pd.to_datetime(d.index)
    d["pnl"] = np.sign(d.r_first) * d.r_last - COST
    d["hivol"] = d.v_first > d.v_first.rolling(60, min_periods=30).median().shift(1)
    for h, (a, b) in HALVES.items():
        x = d.loc[a:b]
        if len(x) < 30: continue
        s, t, r2, n = nw_ols(x.r_last.values, x.r_first.values)
        regs.append(dict(sym=sym, half=h, n=n, slope=s, t_nw5=t, r2=r2, slope_bps_per_1pct=s * 100))
        # also slope excluding top/bottom 1% |r_first| days (influence check)
        q = x.r_first.abs().quantile(0.98); xx = x[x.r_first.abs() < q]
        regs[-1].update(slope_ex2pct=nw_ols(xx.r_last.values, xx.r_first.values)[0], t_ex2pct=nw_ols(xx.r_last.values, xx.r_first.values)[1])
        allm = x.r_first != 0; big = x.r_first.abs() > 0.003
        ter = x.r_first.abs() >= x.r_first.abs().quantile(2 / 3)
        for lab, m in [("all", allm), ("abs_gt_0.3pct", big), ("DESC_top_tercile", ter), ("DESC_hivol", allm & x.hivol), ("DESC_top_tercile_x_hivol", ter & x.hivol)]:
            r = trade_stats(x, m, lab)
            if r: rows.append(dict(sym=sym, half=h, **r))
            if lab in ("all", "abs_gt_0.3pct"): series[(sym, lab, h)] = x.pnl[m]
    for lab, m in [("all", d.r_first != 0), ("abs_gt_0.3pct", d.r_first.abs() > 0.003)]:
        d.pnl[m].rename(f"{sym}|{lab}").to_frame().to_parquet(f"{OUT}/stock_pnl_{sym}_{lab}.parquet")
    # yearly means (concentration in time)
    yr = d.groupby(d.index.year).apply(lambda g: pd.Series({"n": (g.r_first != 0).sum(), "all_bps": 1e4 * g.pnl[g.r_first != 0].mean(),
                                                            "big_n": (g.r_first.abs() > 0.003).sum(), "big_bps": 1e4 * g.pnl[g.r_first.abs() > 0.003].mean(),
                                                            "slope": nw_ols(g.r_last.values, g.r_first.values)[0]}))
    print(f"\n{sym} yearly:\n", yr.round(2).to_string())

R = pd.DataFrame(regs); T = pd.DataFrame(rows)
R.to_csv(f"{OUT}/stock_regression.csv", index=False); T.to_csv(f"{OUT}/stock_trades.csv", index=False)
pd.set_option("display.width", 250)
print("\nREGRESSION r_last ~ r_first (NW5):\n", R.round(4).to_string(index=False))
print("\nTRADES (net 1bp):\n", T.round(3).to_string(index=False))
