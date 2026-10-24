import pandas as pd, numpy as np
rng = np.random.default_rng(9)
e = pd.read_parquet("events.parquet"); B = pd.read_parquet("rth_1m.parquet")
print(B.columns.tolist(), len(B), B.head(2).to_dict("records"))
f = e[(e.fired==1)]; print(f.groupby(["kind","gap"]).size())
def clus(x, col):
    g = x.groupby("d")[col].agg(["sum","count"]); idx = rng.integers(0, len(g), (2000, len(g)))
    s = g["sum"].values[idx].sum(1)/g["count"].values[idx].sum(1); return x[col].mean()*1e4, *(np.percentile(s, [2.5, 97.5])*1e4)
for k in ["real","placebo"]:
    x = f[(f.kind==k)&(f.gap==0)].copy(); sg = np.where(x.dir=="ABOVE", 1, -1)
    for c in ["r5","r15","r30","r60","rclose"]:
        x[c+"s"] = x[c]*sg if k=="real" or True else x[c]
    print(k, len(x), "days", x.d.nunique(), {c: "%+.1f[%+.1f,%+.1f]" % clus(x, c+"s") for c in ["r5","r15","r30","r60","rclose"]})
