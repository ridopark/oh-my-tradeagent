"""H8.2 attention drift/reversal, per PREREG8.md. Run from anywhere: python3 h82.py [--keep-0701]"""
import sys, os
import numpy as np, pandas as pd

H = os.path.dirname(os.path.abspath(__file__))
WL = os.path.abspath(os.path.join(H, "..", ".."))
KEEP_0701 = "--keep-0701" in sys.argv
ETF = {"SPY", "QQQ", "IWM", "GLD"}
FIT = ("2026-06-22", "2026-08-31"); TEST = ("2026-09-01", "2026-10-22")
rng = np.random.default_rng(8)

# ---------- listings ----------
L = pd.read_csv(f"{WL}/legs_raw.psv", sep="|", header=None, names="d wf ts ticker dir level mode mult".split())
L["et"] = pd.to_datetime(L.ts).dt.tz_convert("America/New_York")
late = L[L.et.dt.strftime("%H:%M") >= "09:30"]
if not KEEP_0701:  # Amendment A1: armed 22:55 ET, after the close -> cannot certify pre-open selection
    L = L[~L.d.isin(late.d.unique())]
L = L[(L.d >= FIT[0]) & (L.d <= TEST[1])]
lst = L.groupby(["d", "ticker"]).dir.agg(lambda s: set(s)).reset_index()
lst["sign"] = lst.dir.map(lambda s: 1 if s == {"ABOVE"} else (-1 if s == {"BELOW"} else 0))
lst = lst.rename(columns={"ticker": "s"})[["d", "s", "sign"]]

# ---------- daily bars (split+div adjusted) ----------
B = pd.read_json(f"{H}/daily_all.jsonl.gz", lines=True)
B["d"] = pd.to_datetime(B.t).dt.tz_convert("America/New_York").dt.strftime("%Y-%m-%d")
cal = sorted(B[B.s == "SPY"].d)
O = B.pivot(index="d", columns="s", values="o").reindex(cal)
C = B.pivot(index="d", columns="s", values="c").reindex(cal)
R = {"oc": C / O - 1, "cn": O.shift(-1) / C - 1, "c5": C.shift(-5) / C - 1}  # NaN where future bar missing -> dropped
univ = [s for s in open(f"{H}/syms.txt").read().split() if s in O.columns]

rows = []
for h, M in R.items():
    long = M.stack().rename("r").reset_index()
    long = long[long.s.isin(univ)]
    long = long.merge(lst, on=["d", "s"], how="left")
    long["listed"] = long.sign.notna()
    ctrl = long[~long.listed].groupby("d").r.agg(c_mean="mean")
    ctrl["c_abs"] = long[~long.listed].assign(a=lambda x: x.r.abs()).groupby("d").a.mean()
    x = long[long.listed].join(ctrl, on="d")
    x["h"] = h
    rows.append(x)
X = pd.concat(rows)
X["signed"] = X.sign * (X.r - X.c_mean)
X["unsigned"] = X.r.abs() - X.c_abs
X["half"] = np.where(X.d <= FIT[1], "FIT", "TEST")
X["etf"] = X.s.isin(ETF)


def cl_stats(df, col, cluster="d"):
    v = df[col].to_numpy(); g = df[cluster].to_numpy()
    n = len(v); est = v.mean()
    sums = pd.Series(v - est).groupby(g).sum().to_numpy(); G = len(sums)
    se = np.sqrt((sums ** 2).sum() * G / (G - 1)) / n
    return est, se, n, G


def table(df, metrics, cluster="d", maxt=False, B=10000):
    out = []
    for h, col in metrics:
        sub = df[(df.h == h)].dropna(subset=[col])
        est, se, n, G = cl_stats(sub, col, cluster)
        out.append(dict(h=h, metric=col, n=n, days=G, est_bp=est * 1e4, se_bp=se * 1e4, t=est / se,
                        lo_bp=(est - 1.96 * se) * 1e4, hi_bp=(est + 1.96 * se) * 1e4))
    T = pd.DataFrame(out)
    if maxt:  # day-cluster bootstrap, studentized max-|t| over the 6 metrics
        subs = [df[(df.h == h)].dropna(subset=[col])[[cluster, col]].rename(columns={col: "v"}) for h, col in metrics]
        days = np.array(sorted(df[cluster].unique()))
        grp = [{k: g.v.to_numpy() for k, g in s.groupby(cluster)} for s in subs]
        mx = np.empty(B)
        for b in range(B):
            dd = rng.choice(days, len(days), replace=True)
            ts = []
            for k, gmap in enumerate(grp):
                parts = [gmap[d] for d in dd if d in gmap]
                v = np.concatenate(parts); est = v.mean()
                sums = np.array([(p - est).sum() for p in parts]); G = len(parts)
                se = np.sqrt((sums ** 2).sum() * G / (G - 1)) / len(v)
                ts.append((est - T.est_bp[k] / 1e4) / se)
            mx[b] = np.max(np.abs(ts))
        c = np.quantile(mx, 0.95)
        T["crit"] = c
        T["slo_bp"] = T.est_bp - c * T.se_bp; T["shi_bp"] = T.est_bp + c * T.se_bp
        T["p_adj"] = [(mx >= abs(t)).mean() for t in T.t]
    return T


METRICS = [(h, m) for h in ("oc", "cn", "c5") for m in ("signed", "unsigned")]
fit = table(X[X.half == "FIT"], METRICS, maxt=True)
test = table(X[X.half == "TEST"], METRICS, maxt=True)
pd.set_option("display.width", 250); pd.set_option("display.float_format", lambda v: f"{v:.2f}")
print("A1 keep_0701 =", KEEP_0701, "| late-armed rows:", late[["d", "ticker"]].values.tolist())
print("\nPer-half n (unique listed pairs / days / sign counts):")
for hf in ("FIT", "TEST"):
    q = X[(X.half == hf) & (X.h == "oc")]
    print(hf, len(q), q.d.nunique(), q.sign.value_counts().to_dict(), "etf pairs", int(q.etf.sum()))
print("\nFIT (pointwise 95% day-clustered; simultaneous via max-t)\n", fit.to_string(index=False))
print("\nTEST (pointwise + studentized max-t simultaneous 95%)\n", test.to_string(index=False))

res = []
for k in range(6):
    f, t = fit.iloc[k], test.iloc[k]
    same = np.sign(f.est_bp) == np.sign(t.est_bp)
    fit_ok = (f.lo_bp > 0) or (f.hi_bp < 0)
    test_ok = (t.slo_bp > 0) or (t.shi_bp < 0)
    res.append(dict(h=f.h, metric=f.metric, same_sign=same, fit_ci_excl0=fit_ok, test_maxt_ci_excl0=test_ok,
                    test_pointwise_excl0=(t.lo_bp > 0) or (t.hi_bp < 0), PASS=bool(same and fit_ok and test_ok)))
print("\nPASS RULE\n", pd.DataFrame(res).to_string(index=False))

# ---------- robustness: signed over sign!=0 only ----------
nz = X[X.sign != 0]
print("\nSensitivity: signed metrics on sign!=0 pairs only")
for hf in ("FIT", "TEST"):
    print(hf, table(nz[nz.half == hf], [(h, "signed") for h in ("oc", "cn", "c5")]).to_string(index=False))

# ---------- robustness: week clusters (c5 overlaps 5 days) ----------
X["wk"] = pd.to_datetime(X.d).dt.strftime("%G-%V")
print("\nSensitivity: ISO-week clusters")
for hf in ("FIT", "TEST"):
    print(hf, table(X[X.half == hf], METRICS, cluster="wk").to_string(index=False))

# ---------- robustness: within-ticker control (same ticker, non-listed days, same half) ----------
listed_days = set(lst.d)
win = []
for h, M in R.items():
    for hf, (a, b) in (("FIT", FIT), ("TEST", TEST)):
        days = [d for d in cal if a <= d <= b and (d in listed_days)]  # days a list existed
        for s in X.s.unique():
            on = lst[(lst.s == s) & lst.d.isin(days)]
            off = [d for d in days if d not in set(on.d)]
            r_off = M.loc[off, s].dropna()
            if len(r_off) < 5 or on.empty:
                continue
            for _, row in on.iterrows():
                r = M.at[row.d, s]
                if np.isnan(r):
                    continue
                win.append(dict(h=h, half=hf, d=row.d, s=s, sign=row.sign,
                                signed=row.sign * (r - r_off.mean()), unsigned=abs(r) - r_off.abs().mean(), n_off=len(r_off)))
W = pd.DataFrame(win)
print("\nWithin-ticker control (raw returns; ticker's own non-listed days in same half, >=5 off days)")
for hf in ("FIT", "TEST"):
    print(hf, table(W[W.half == hf], METRICS).to_string(index=False))

# ---------- cost illustration for signed metrics (only meaningful if PASS) ----------
print("\nNet of costs, signed metrics (listed leg 3bp single/1bp ETF RT + 3bp control-basket RT), TEST:")
for h in ("oc", "cn", "c5"):
    q = X[(X.half == "TEST") & (X.h == h) & (X.sign != 0)].dropna(subset=["signed"]).copy()
    q["net"] = q.signed - np.where(q.etf, 1e-4, 3e-4) - 3e-4
    e, se, n, G = cl_stats(q, "net")
    print(h, f"net {e*1e4:.1f}bp  CI [{(e-1.96*se)*1e4:.1f},{(e+1.96*se)*1e4:.1f}] n={n} days={G}")
X.to_parquet(f"{H}/pairs{'_keep0701' if KEEP_0701 else ''}.parquet")
