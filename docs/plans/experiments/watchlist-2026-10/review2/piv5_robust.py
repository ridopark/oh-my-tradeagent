import pandas as pd, numpy as np
R = pd.read_parquet("review2/sr_re.parquet"); x = R[(R.defn=="D1_5m")&(R.kind=="real")].copy()
x["y"] = pd.to_datetime(x.d.astype(str)).dt.year
print("f30 bps by symbol:", (x.groupby("s").f30.mean()*1e4).round(1).to_dict())
print("f30 bps by year:", (x.groupby("y").f30.mean()*1e4).round(1).to_dict())
print("frac f30<0:", round((x.f30<0).mean(),3), " t-stat naive:", round(x.f30.mean()/x.f30.std()*np.sqrt(len(x)),1))
# by time of touch
x["tb"] = pd.cut(x.j, [0,90,180,270,400]); print((x.groupby("tb", observed=True).f30.agg(["size","mean"]).assign(mean=lambda d: d["mean"]*1e4)).round(1))
# per-day mean then t over days
dm = x.groupby("d").f30.mean(); print("day-level mean", round(dm.mean()*1e4,2), "t", round(dm.mean()/dm.std()*np.sqrt(len(dm)),1), "days", len(dm))
