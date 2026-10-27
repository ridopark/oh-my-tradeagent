import pandas as pd, numpy as np, sys
rng = np.random.default_rng(4)
R = pd.read_parquet(sys.argv[1])
def diff_ci(r, p, col, B=1000):
    # day-clustered bootstrap of mean(real) - mean(placebo), resampling (symbol,day) clusters jointly
    r = r.assign(k=r.s+r.d.astype(str)); p = p.assign(k=p.s+p.d.astype(str))
    gr = r.groupby("k")[col].agg(["sum","count"]); gp = p.groupby("k")[col].agg(["sum","count"])
    keys = gr.index.union(gp.index); gr = gr.reindex(keys, fill_value=0); gp = gp.reindex(keys, fill_value=0)
    idx = rng.integers(0, len(keys), (B, len(keys)))
    d = gr["sum"].values[idx].sum(1)/gr["count"].values[idx].sum(1) - gp["sum"].values[idx].sum(1)/gp["count"].values[idx].sum(1)
    return r[col].mean()-p[col].mean(), *np.percentile(d,[2.5,97.5])
out=[]
for (defn, half), x in R.groupby(["defn","half"]):
    r, p = x[x.kind=="real"], x[x.kind=="placebo"]
    if p.empty: continue
    row = dict(defn=defn, half=half, n=len(r))
    for col, sc in [("b30",1e4),("brk",100)]:
        dm, lo, hi = diff_ci(r.astype({"brk":float}), p.astype({"brk":float}), col)
        row[f"{col}_real"] = r[col].astype(float).mean()*sc; row[f"{col}_diff"] = dm*sc; row[f"{col}_ci"] = f"[{lo*sc:+.1f},{hi*sc:+.1f}]"
        row[f"{col}_sig"] = (lo>0) or (hi<0)
    out.append(row)
T = pd.DataFrame(out).round(2); pd.set_option("display.width",250); print(T.to_string(index=False))
