import pandas as pd, numpy as np
df = pd.read_json("stock_1m.jsonl.gz", lines=True, compression="gzip")
df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df["d"] = df.t.dt.date
m = df.t.dt.hour*60+df.t.dt.minute
rth = df[(m>=570)&(m<960)]; pm = df[(m>=240)&(m<570)]
D = rth.groupby(["s","d"]).agg(H=("h","max"), L=("l","min"), C=("c","last")).reset_index()
P = pm.groupby(["s","d"]).agg(PMH=("h","max"), PML=("l","min"), PMC=("c","last")).reset_index()
D = D.sort_values(["s","d"])
for c in ["H","L","C"]: D["PD"+c] = D.groupby("s")[c].shift(1)
D["PWH"] = D.groupby("s").H.transform(lambda x: x.shift(1).rolling(5).max())
D["PWL"] = D.groupby("s").L.transform(lambda x: x.shift(1).rolling(5).min())
D = D.merge(P, on=["s","d"], how="left")
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"])
legs["d"] = pd.to_datetime(legs.d).dt.date; legs = legs.drop_duplicates(["d","s","dir","lvl"])
x = legs.merge(D, on=["s","d"], how="left").dropna(subset=["PDH","PMC"])
cands = ["PDH","PDL","PDC","PMH","PML","PWH","PWL"]
for c in cands: x["d_"+c] = (x.lvl/x[c]-1).abs()
def roundness(v, px):
    for step in [10,5,2.5,1,0.5]:
        if abs(v/step - round(v/step)) < 1e-6: return step
    return 0
x["round"] = [roundness(v,p) for v,p in zip(x.lvl, x.PMC)]
x["dist_pm"] = (x.lvl/x.PMC-1)*np.where(x.dir=="ABOVE",1,-1)
tol = .0015
x["best"] = x[["d_"+c for c in cands]].idxmin(axis=1).str[2:]; x["bestd"] = x[["d_"+c for c in cands]].min(axis=1)
print("n", len(x))
for c in cands: print(c, "match<=0.15%:", round((x["d_"+c]<=tol).mean(),3))
print("ANY candidate within 0.15%:", round((x.bestd<=tol).mean(),3), " within 0.3%:", round((x.bestd<=.003).mean(),3))
print("round-number levels (step>=1 or index .5):", x["round"].value_counts().to_dict())
print("signed dist from premarket close (bps) quantiles:", (x.dist_pm.quantile([.05,.25,.5,.75,.95])*1e4).round(0).to_dict())
print("dist by group median bps:", (x.groupby(x.s.isin(["SPY","QQQ"])).dist_pm.median()*1e4).round(0).to_dict())
x.to_parquet("levels_features.parquet")
