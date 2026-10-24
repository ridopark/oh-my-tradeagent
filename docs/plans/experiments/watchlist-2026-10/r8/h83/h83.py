"""H8.3 market bias: author's daily put/call tilt vs SPY/QQQ returns. Pre-registered in wl/PREREG8.md."""
import sys, datetime as dt
import numpy as np, pandas as pd
from scipy.stats import spearmanr, binomtest, rankdata

WL = sys.argv[1]
B = 10000
rng = np.random.default_rng(83)
FIT_END, TEST_START = dt.date(2026, 8, 31), dt.date(2026, 9, 1)
ETF = ["SPY", "QQQ"]

legs = pd.read_csv(f"{WL}/legs_raw.psv", sep="|", header=None,
                   names="d workflow_id ts ticker dir level mode mult".split())
legs["d"] = pd.to_datetime(legs.d).dt.date
legs = legs.drop_duplicates(["d", "ticker", "dir", "level"])

def bias(sub):
    g = sub.groupby("d").dir.agg(puts=lambda s: (s == "BELOW").sum(), n="size")
    g["calls"] = g.n - g.puts
    g["bias"] = (g.puts - g.calls) / g.n
    return g

bias_single = bias(legs[~legs.ticker.isin(ETF)])
bias_etf = bias(legs[legs.ticker.isin(ETF)])

px = pd.read_parquet(f"{WL}/rth_1m.parquet", columns=["s", "t", "d", "o", "c"])
px = px[px.s.isin(ETF)].sort_values("t")
day = px.groupby(["s", "d"]).agg(o=("o", "first"), c=("c", "last")).reset_index()
rets = {}
for s in ETF:
    x = day[day.s == s].set_index("d").sort_index()
    rets[(s, "oc")] = x.c / x.o - 1
    rets[(s, "cc")] = x.c.shift(-1) / x.c - 1   # close(d) -> close(next trading day in file)
R = pd.DataFrame(rets).dropna(how="all")
R.columns = [f"{s}_{h}" for s, h in R.columns]
MET = list(R.columns)

def spear(b, r):
    return spearmanr(b, r).statistic

def boot_idx(n):
    return rng.integers(0, n, size=(B, n))

def fast_spear_boot(b, r, idx):
    out = np.empty(len(idx))
    for i, ix in enumerate(idx):
        bb, rr = b[ix], r[ix]
        if bb.std() == 0 or rr.std() == 0:
            out[i] = np.nan; continue
        out[i] = np.corrcoef(rankdata(bb), rankdata(rr))[0, 1]
    return out

def analyse(bdf, label, drop=()):
    rows, test_boot = [], {}
    for half, lo, hi in [("FIT", dt.date(2000, 1, 1), FIT_END), ("TEST", TEST_START, dt.date(2100, 1, 1))]:
        J = bdf[["bias"]].join(R, how="inner")
        J = J[(J.index >= lo) & (J.index <= hi) & ~J.index.isin(drop)]
        for m in MET:
            j = J[["bias", m]].dropna()
            b, r = j.bias.to_numpy(float), j[m].to_numpy(float)
            n = len(j)
            idx = boot_idx(n)
            rho = spear(b, r)
            bs = fast_spear_boot(b, r, idx)
            # sign test: hit = bias sign predicts OPPOSITE return sign (puts -> down); bias==0 or ret==0 excluded
            mask = (b != 0) & (r != 0)
            hits = (np.sign(b[mask]) == -np.sign(r[mask]))
            k, nn = int(hits.sum()), int(mask.sum())
            hb = np.array([((np.sign(b[ix]) == -np.sign(r[ix])) & (b[ix] != 0) & (r[ix] != 0)).sum()
                           / max(((b[ix] != 0) & (r[ix] != 0)).sum(), 1) for ix in idx[:2000]])
            rows.append(dict(set=label, half=half, metric=m, n_days=n, rho=rho,
                             rho_lo=np.nanpercentile(bs, 2.5), rho_hi=np.nanpercentile(bs, 97.5),
                             sign_hits=k, sign_n=nn, hit_rate=k / nn if nn else np.nan,
                             hit_lo=np.percentile(hb, 2.5), hit_hi=np.percentile(hb, 97.5),
                             sign_p=binomtest(k, nn, 0.5).pvalue if nn else np.nan))
            if half == "TEST":
                test_boot[m] = (rho, bs)
    T = pd.DataFrame(rows)
    # studentized max-|t| over the 4 TEST metrics (same day-resamples per metric within a half? no: draws differ
    # per metric because n differs only via NaN in cc on the last day; re-draw jointly below for correctness)
    return T

def maxT_test(bdf, drop=()):
    J = bdf[["bias"]].join(R, how="inner")
    J = J[(J.index >= TEST_START) & ~J.index.isin(drop)]
    J = J.iloc[:-1] if J[MET].iloc[-1].isna().any() else J   # joint resample needs complete rows
    n = len(J); idx = boot_idx(n)
    b = J.bias.to_numpy(float)
    rho = np.array([spear(b, J[m].to_numpy(float)) for m in MET])
    bs = np.column_stack([fast_spear_boot(b, J[m].to_numpy(float), idx) for m in MET])
    se = np.nanstd(bs, axis=0, ddof=1)
    t = rho / se
    mx = np.nanmax(np.abs((bs - rho) / se), axis=1)
    padj = [(mx >= abs(tk)).mean() for tk in t]
    crit = np.nanpercentile(mx, 95)
    return pd.DataFrame(dict(metric=MET, n_days=n, rho=rho, se=se, t=t, p_maxT=padj,
                             simult_lo=rho - crit * se, simult_hi=rho + crit * se)), crit

def verdict(T, M):
    out = []
    for m in MET:
        f = T[(T.half == "FIT") & (T.metric == m)].iloc[0]; s = T[(T.half == "TEST") & (T.metric == m)].iloc[0]
        mm = M[M.metric == m].iloc[0]
        same = np.sign(f.rho) == np.sign(s.rho)
        excl = lambda r: r.rho_lo > 0 or r.rho_hi < 0
        sign_ok = np.sign(f.hit_rate - .5) == np.sign(s.hit_rate - .5) == (1 if f.rho < 0 else -1)
        ok = same and excl(f) and excl(s) and mm.p_maxT < 0.05 and sign_ok
        out.append(dict(metric=m, same_sign=same, fit_ci_excl0=excl(f), test_ci_excl0=excl(s),
                        test_maxT_p_lt_05=mm.p_maxT < 0.05, sign_test_agrees=sign_ok, PASS=ok))
    return pd.DataFrame(out)

def trade(bdf, drop=()):
    J = bdf[["bias"]].join(R[["SPY_oc"]], how="inner").dropna()
    J = J[~J.index.isin(drop)]
    J["pos"] = np.where(J.bias > 0, -1.0, 1.0)
    J["pnl"] = J.pos * J.SPY_oc - 1e-4
    rows = []
    for half, sel in [("FIT", J.index <= FIT_END), ("TEST", J.index >= TEST_START), ("ALL", np.ones(len(J), bool))]:
        p = J.pnl[sel].to_numpy(); n = len(p); idx = boot_idx(n)
        bm = p[idx].mean(axis=1)
        rows.append(dict(half=half, n_days=n, n_short=int((J.pos[sel] < 0).sum()),
                         mean_bp=p.mean() * 1e4, lo_bp=np.percentile(bm, 2.5) * 1e4, hi_bp=np.percentile(bm, 97.5) * 1e4,
                         hit=(p > 0).mean(), long_only_bp=(J.SPY_oc[sel].mean() - 1e-4) * 1e4))
    return pd.DataFrame(rows)

pd.set_option("display.width", 250); pd.set_option("display.max_columns", 30)
fmt = lambda df: df.to_string(index=False, float_format=lambda v: f"{v:.4f}")

for label, bdf, drop in [("PRIMARY single-name bias", bias_single, ()),
                         ("SENSITIVITY single-name bias, drop 2026-07-01 (legs armed 22:55 ET, post-close)", bias_single, (dt.date(2026, 7, 1),)),
                         ("EXPLORATORY SPY/QQQ-leg bias", bias_etf, ())]:
    print(f"\n=== {label} ===")
    b = bdf.bias
    print(f"days with bias: {len(b)} (FIT {(b.index <= FIT_END).sum()}, TEST {(b.index >= TEST_START).sum()}); "
          f"bias mean {b.mean():.3f} sd {b.std():.3f}; share bias>0 {(b > 0).mean():.2f}, ==0 {(b == 0).mean():.2f}, <0 {(b < 0).mean():.2f}; "
          f"legs/day median {bdf.n.median():.0f}")
    T = analyse(bdf, label, drop)
    M, crit = maxT_test(bdf, drop)
    print(fmt(T.drop(columns="set")))
    print(f"-- TEST studentized max-|t| over 4 (crit95={crit:.3f}) --"); print(fmt(M))
    print("-- pass rule --"); print(verdict(T, M).to_string(index=False))
    print("-- trade: short SPY at open if bias>0 else long, exit close, 1bp --"); print(fmt(trade(bdf, drop)))
