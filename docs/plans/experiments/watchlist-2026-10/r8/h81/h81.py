# H8.1 vol selection: log(realized/implied) on listed (d,ticker) vs within-ticker and cross-section controls.
import pandas as pd, numpy as np, json, glob, os
from scipy import stats
O = os.path.dirname(os.path.abspath(__file__)); W = os.path.dirname(os.path.dirname(O))
FIT, TEST = ("2026-06-22", "2026-08-31"), ("2026-09-01", "2026-10-22")
half = lambda d: "FIT" if d <= FIT[1] else "TEST"
U = open(f"{O}/syms.txt").read().strip().split(",")
D = pd.read_json(f"{O}/daily.jsonl.gz", lines=True)
D["d"] = pd.to_datetime(D.t).dt.tz_convert("America/New_York").dt.strftime("%Y-%m-%d")
D = D.sort_values(["s", "d"]); D["pd"] = D.groupby("s").d.shift(); D["pc"] = D.groupby("s").c.shift()
days = sorted(D[D.s == "SPY"].d); prev = dict(zip(days[1:], days[:-1]))
D = D[(D.d >= FIT[0]) & (D.d <= TEST[1])]
D = D[D.pd == D.d.map(prev)]                                   # require a bar on the prior trading day
# --- IV from prior-day chain ---
iv = []
for f in sorted(glob.glob(f"{O}/chain/*.json")):
    j = json.load(open(f)); c = pd.DataFrame(j["rows"])
    if c.empty: continue
    c["vol"] = pd.to_numeric(c.vol); c["strike"] = pd.to_numeric(c.strike)
    c = c[c.vol > 0]
    c = c[c.expiration == c.groupby("act_symbol").expiration.transform("min")]
    iv.append(c.groupby(["act_symbol", "expiration", "strike"]).agg(iv=("vol", "mean"), nleg=("vol", "size")).reset_index().assign(cd=j["date"]))
IV = pd.concat(iv).rename(columns={"act_symbol": "s"})
spot = D[["s", "pd", "pc"]].rename(columns={"pd": "cd"})
IV = IV.merge(spot.drop_duplicates(), on=["s", "cd"])
IV["dist"] = (IV.strike / IV.pc - 1).abs()
IV = IV.sort_values("dist").drop_duplicates(["s", "cd"])
D = D.merge(IV[["s", "cd", "iv", "dist", "expiration", "nleg"]].rename(columns={"cd": "pd"}), on=["s", "pd"], how="left")
L = pd.read_csv(f"{W}/legs_raw.psv", sep="|", header=None, names="d workflow_id ts ticker dir level mode mult".split())
L["et"] = pd.to_datetime(L.ts, format="ISO8601", utc=True).dt.tz_convert("America/New_York")
Lf = L.sort_values("et").drop_duplicates(["d", "ticker"])
late = set(zip(Lf[Lf.et.dt.strftime("%H:%M") >= "09:30"].d, Lf[Lf.et.dt.strftime("%H:%M") >= "09:30"].ticker))
listed = set(zip(Lf.d, Lf.ticker))
D["listed"] = [(d, s) in listed for d, s in zip(D.d, D.s)]
D["late"] = [(d, s) in late for d, s in zip(D.d, D.s)]
D["half"] = D.d.map(half)
# --- coverage ---
cov = D[D.listed].assign(has_iv=lambda x: x.iv.notna() & (x.dist <= 0.05))
print("listed pairs in daily bars:", len(cov), "of", len(listed), "| with IV:", cov.has_iv.sum())
print("listed missing IV by ticker:", cov[~cov.has_iv].s.value_counts().to_dict())
print("universe obs with IV:", (D.iv.notna() & (D.dist <= 0.05)).sum(), "of", len(D), "| tickers never with IV:", sorted(set(U) - set(D[D.iv.notna()].s)))
print("chain dates present:", len(glob.glob(f"{O}/chain/*.json")), "| atm dist>5% dropped:", (D.dist > 0.05).sum(), "| one-sided strike:", (D.nleg == 1).sum())
print("DTE of IV expiry:", (pd.to_datetime(D.expiration) - pd.to_datetime(D.pd)).dt.days.describe().to_dict())
D = D[D.iv.notna() & (D.dist <= 0.05)].copy()
D["ret"] = D.c / D.pc - 1
print("abs ret>40% (split check):", D[D.ret.abs() > 0.4][["s", "d", "ret"]].to_dict("records"))
D = D[D.ret.abs() <= 0.4]
m = D.iv / np.sqrt(252); EPS = 1e-4
D["y_cc"] = np.log(np.maximum(D.ret.abs(), EPS) / m)
D["y_hl"] = np.log(np.maximum((D.h - D.l) / D.pc, EPS) / m)
D["y_oc"] = np.log(np.maximum((D.c / D.o - 1).abs(), EPS) / m)      # diagnostic, not pre-registered
print("zero-move floor hits cc:", (D.ret.abs() < EPS).sum())
D.to_parquet(f"{O}/panel.parquet")
def ccl(x, g):
    """mean with day-clustered 95% CI (CR1, t_{G-1})"""
    x = np.asarray(x, float); mu = x.mean(); e = pd.Series(x - mu).groupby(np.asarray(g)).sum()
    G = len(e); se = np.sqrt(G / (G - 1) * (e ** 2).sum()) / len(x); t = stats.t.ppf(0.975, G - 1)
    return mu, mu - t * se, mu + t * se, len(x), G
def run(D, tag):
    rows = []
    for h in ("FIT", "TEST"):
        X = D[D.half == h]
        for y in ("y_cc", "y_hl", "y_oc"):
            Li = X[X.listed]; C = X[~X.listed]
            xs = Li.merge(C.groupby("d")[y].mean().rename("cx"), on="d")
            wt = Li.merge(C.groupby("s")[y].mean().rename("cw"), on="s")
            for ctl, Z, col in (("cross-section", xs, "cx"), ("within-ticker", wt, "cw")):
                mu, lo, hi, n, G = ccl(Z[y] - Z[col], Z.d)
                rows.append(dict(set=tag, half=h, y=y, control=ctl, n_listed=n, days=G,
                                 n_control=(len(C) if ctl == "cross-section" else int(C.s.isin(Li.s).sum())),
                                 listed_mean=Z[y].mean(), ctrl_mean=Z[col].mean(), diff=mu, lo=lo, hi=hi))
    return pd.DataFrame(rows)
res = pd.concat([run(D, "primary"),
                 run(D[~D.late], "excl_postopen_arms"),
                 run(D[~D.s.isin(["SPY", "QQQ", "IWM", "GLD"]) ], "excl_ETFs")])
pd.set_option("display.width", 250); print(res.round(4).to_string(index=False)); res.to_csv(f"{O}/metric_results.csv", index=False)
