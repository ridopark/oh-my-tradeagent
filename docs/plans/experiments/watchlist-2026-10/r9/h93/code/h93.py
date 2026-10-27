# H9.3 CATALYST DAY: tag legs AGREE / SILENT / EXCLUDED from earnings_table.csv, then AGREE-SILENT with day-cluster bootstrap.
import sys, numpy as np, pandas as pd
H, WL = "..", "../../.."
ETF = {"SPY", "QQQ", "IWM", "GLD"}
B, SEED = 10000, 93
t = pd.read_csv(f"{H}/earnings_table.csv", dtype=str)
sess = [x.strftime("%Y-%m-%d") for x in pd.bdate_range("2026-06-01", "2026-10-30")]
sess = [x for x in sess if x not in {"2026-06-19", "2026-07-03", "2026-09-07"}]
ix = {d: i for i, d in enumerate(sess)}

def tag(s, d):
    if s in ETF: return "SILENT"
    r = t[t.s == s]
    if ((r.effective_session == d) & r.timing.isin(["pre_open", "after_close"])).any(): return "AGREE"
    near = [abs(ix[x] - ix[d]) <= 3 for x in list(r.nasdaq_date) + list(r.effective_session.dropna())]
    return "EXCLUDED" if any(near) else "SILENT"

e = pd.read_parquet(f"{WL}/events.parquet")
e = e[(e.fired == 1) & (e.gap == 0)].copy()
e["d"] = e.d.astype(str)
e["tag"] = [tag(s, d) for s, d in zip(e.s, e.d)]
e["etf"] = e.s.isin(ETF)
e.to_parquet(f"{H}/tagged_legs.parquet")
real, plc = e[e.kind == "real"], e[e.kind == "placebo"]

def boot(a, b, y, rng):
    """mean(a)-mean(b); a,b are DataFrames (d, y). Day-cluster bootstrap over the union of their days."""
    pt = a[y].mean() - b[y].mean()
    days = np.array(sorted(set(a.d) | set(b.d)))
    ga = a.groupby("d")[y].agg(["sum", "count"]).reindex(days, fill_value=0)
    gb = b.groupby("d")[y].agg(["sum", "count"]).reindex(days, fill_value=0)
    idx = rng.integers(0, len(days), (B, len(days)))
    sa, ca = ga["sum"].values[idx].sum(1), ga["count"].values[idx].sum(1)
    sb, cb = gb["sum"].values[idx].sum(1), gb["count"].values[idx].sum(1)
    ok = (ca > 0) & (cb > 0)
    dr = sa[ok] / ca[ok] - sb[ok] / cb[ok]
    if len(dr) < 2: return pt, float("nan"), float("nan"), float("nan"), int((~ok).sum())
    lo, hi = np.percentile(dr, [2.5, 97.5])
    return pt, lo, hi, dr.std(ddof=1), int((~ok).sum())

def cr1_t(a, b, y):
    """Day-clustered CR1 t of the AGREE dummy in y ~ 1 + AGREE (for the max-t handoff)."""
    df = pd.concat([a.assign(x=1.0), b.assign(x=0.0)])
    X = np.c_[np.ones(len(df)), df.x.values]; Y = df[y].values
    XtXi = np.linalg.inv(X.T @ X); beta = XtXi @ X.T @ Y; u = Y - X @ beta
    G = df.d.nunique(); N = len(df)
    meat = sum(np.outer(X[m].T @ u[m], X[m].T @ u[m]) for m in [(df.d == g).values for g in df.d.unique()])
    V = G / (G - 1) * (N - 1) / (N - 2) * XtXi @ meat @ XtXi
    return beta[1] / np.sqrt(V[1, 1])

bp = lambda v: f"{1e4 * v:+.1f}"
out = []
P = lambda *a: out.append(" ".join(str(x) for x in a))
for label, R, PL in [("PRIMARY (ETFs=SILENT)", real, plc), ("SENSITIVITY (stocks only)", real[~real.etf], plc[~plc.etf])]:
    P(f"\n### {label}")
    P("\nCounts (fired, non-gap legs): half | tag | real legs / days / tickers | placebo legs")
    for h in ["FIT", "TEST"]:
        for tg in ["AGREE", "SILENT", "EXCLUDED"]:
            r = R[(R.half == h) & (R.tag == tg)]; p = PL[(PL.half == h) & (PL.tag == tg)]
            P(f"| {h} | {tg} | {len(r)} / {r.d.nunique()} / {r.s.nunique()} | {len(p)} |")
    for y in ["r60", "rclose"]:
        P(f"\n{y} (bps): half | real AGREE mean (n) | real SILENT mean (n) | AGREE-SILENT [95% CI] | t_CR1 | plc AGREE mean (n) | real AGREE - plc AGREE [CI] | plc AGREE-SILENT [CI]")
        for h in ["FIT", "TEST"]:
            rng = np.random.default_rng(SEED)
            A = R[(R.half == h) & (R.tag == "AGREE")].dropna(subset=[y]); S = R[(R.half == h) & (R.tag == "SILENT")].dropna(subset=[y])
            pA = PL[(PL.half == h) & (PL.tag == "AGREE")].dropna(subset=[y]); pS = PL[(PL.half == h) & (PL.tag == "SILENT")].dropna(subset=[y])
            d1 = boot(A, S, y, rng); tt = cr1_t(A, S, y) if A.d.nunique() > 1 and len(S) else float("nan")
            d2 = boot(A, pA, y, rng); d3 = boot(pA, pS, y, rng)
            P(f"| {h} | {bp(A[y].mean())} ({len(A)}) | {bp(S[y].mean())} ({len(S)}) | {bp(d1[0])} [{bp(d1[1])}, {bp(d1[2])}] | {tt:+.2f} | "
              f"{bp(pA[y].mean())} ({len(pA)}) | {bp(d2[0])} [{bp(d2[1])}, {bp(d2[2])}] | {bp(d3[0])} [{bp(d3[1])}, {bp(d3[2])}] | dropped={d1[4]}")
    P("\nAGREE legs (real):")
    a = R[(R.tag == "AGREE")].sort_values("d")
    for r in a.itertuples(): P(f"  {r.half} {r.d} {r.s} {r.dir} fire_min={r.fire_min:.0f} r60={bp(r.r60) if pd.notna(r.r60) else 'nan'} rclose={bp(r.rclose) if pd.notna(r.rclose) else 'nan'}")
txt = "\n".join(out); print(txt); open(f"{H}/out.txt", "w").write(txt + "\n")
