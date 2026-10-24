# Per-half straddle stats: n, mean, median, day-clustered 95% CI, iid CI, win rate.
import pandas as pd, numpy as np, os
from scipy import stats
O = os.path.dirname(os.path.abspath(__file__))
R = pd.read_csv(f"{O}/straddle_trades.csv"); R = R[R.ok]
R["half"] = np.where(R.d <= "2026-08-31", "FIT", "TEST")
def ccl(x, g):
    x = np.asarray(x, float); mu = x.mean(); e = pd.Series(x - mu).groupby(np.asarray(g)).sum(); G = len(e)
    se = np.sqrt(G / (G - 1) * (e ** 2).sum()) / len(x); t = stats.t.ppf(0.975, G - 1); return mu - t * se, mu + t * se, G
rows = []
for tag, X in (("all", R), ("excl_stale_exit", R[~R.stale_exit.astype(bool)]), ("excl_ETFs", R[~R.u.isin(["SPY", "QQQ", "IWM", "GLD"])])):
    for h in ("FIT", "TEST"):
        Y = X[X.half == h]
        for col in ("net", "gross"):
            lo, hi, G = ccl(Y[col], Y.d); s = stats.t.interval(0.95, len(Y) - 1, Y[col].mean(), stats.sem(Y[col]))
            rows.append(dict(set=tag, half=h, ret=col, n=len(Y), days=G, mean=Y[col].mean(), median=Y[col].median(),
                             lo_day=lo, hi_day=hi, lo_iid=s[0], hi_iid=s[1], win=(Y[col] > 0).mean()))
pd.set_option("display.width", 250); print(pd.DataFrame(rows).round(4).to_string(index=False))
print("half-spread median call/put:", R.hs_c.median(), R.hs_p.median(), "| floor-binding share:", ((R.hs_c == 0.01) & (R.hs_p == 0.01)).mean())
print("dte dist:", R.dte.value_counts().sort_index().to_dict(), "| stale exits:", int(R.stale_exit.sum()))
print("top 5 net:", R.nlargest(5, "net")[["d", "u", "net"]].to_dict("records"))
