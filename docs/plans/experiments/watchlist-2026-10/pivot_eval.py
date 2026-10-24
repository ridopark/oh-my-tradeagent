import pandas as pd, numpy as np
rng = np.random.default_rng(6)
R = pd.read_parquet("pivot_strat.parquet")
R["cost"] = np.where(R.s.isin(["SPY","QQQ"]), 1e-4, 3e-4); R["net"] = R.ret - R.cost
def ci(x, col):
    g = x.groupby("d")[col].agg(["sum","count"]); i = rng.integers(0, len(g), (2000, len(g)))
    return np.percentile(g["sum"].values[i].sum(1)/g["count"].values[i].sum(1), [2.5, 97.5])*1e4
rows = []
for (k, arm, per), x in R.groupby(["k","arm","period"]):
    lo, hi = ci(x, "net"); day = x.groupby("d").net.sum()
    rows.append(dict(k=k, arm=arm, period=per, n=len(x), gross=x.ret.mean()*1e4, net=x.net.mean()*1e4, lo=lo, hi=hi,
                     win=(x.net>0).mean(), sharpe=day.mean()/day.std()*np.sqrt(252),
                     sym_pos=f"{(x.groupby('s').net.mean()>0).sum()}/{x.s.nunique()}",
                     yr_pos=f"{(x.groupby(pd.to_datetime(x.d).dt.year).net.mean()>0).sum()}/{pd.to_datetime(x.d).dt.year.nunique()}"))
T = pd.DataFrame(rows).round(2); pd.set_option("display.width", 250); print(T.to_string(index=False))
# split by instrument class
print("\nnet bps by class (pivot arm):")
print((R[R.arm=="pivot"].assign(cls=np.where(R.s.isin(["SPY","QQQ"]),"ETF","single")).groupby(["k","cls","period"]).net.mean()*1e4).round(2).unstack())
