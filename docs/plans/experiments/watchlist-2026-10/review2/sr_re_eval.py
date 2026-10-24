import pandas as pd, numpy as np
rng = np.random.default_rng(5)
R = pd.read_parquet("review2/sr_re.parquet"); R["brk"] = R.brk.astype(float)
print(R.groupby(["defn","kind","half"]).size().unstack("half"))
def ci(r, p, col, key, B=1000):
    """cluster bootstrap of mean(r)-mean(p); key = column used for cluster"""
    gr = r.groupby(key)[col].agg(["sum","count"]); gp = p.groupby(key)[col].agg(["sum","count"])
    keys = gr.index.union(gp.index); gr = gr.reindex(keys, fill_value=0); gp = gp.reindex(keys, fill_value=0)
    idx = rng.integers(0, len(keys), (B, len(keys)))
    d = gr["sum"].values[idx].sum(1)/gr["count"].values[idx].sum(1) - gp["sum"].values[idx].sum(1)/gp["count"].values[idx].sum(1)
    return r[col].mean()-p[col].mean(), *np.percentile(d, [2.5, 97.5])
def ci1(r, col, key, B=1000):
    g = r.groupby(key)[col].agg(["sum","count"]); idx = rng.integers(0, len(g), (B, len(g)))
    return r[col].mean(), *np.percentile(g["sum"].values[idx].sum(1)/g["count"].values[idx].sum(1), [2.5, 97.5])
R["sd"] = R.s + R.d.astype(str); R["dd"] = R.d.astype(str)
out = []
for (defn, half), x in R.groupby(["defn","half"]):
    r = x[x.kind=="real"]
    row = dict(defn=defn, half=half, n=len(r), off_real=r.off.mean()*1e4)
    for pk in ["plac_orig","plac_xday"]:
        p = x[x.kind==pk]; row[f"off_{pk[5:]}"] = p.off.mean()*1e4
        for col, sc in [("b30",1e4),("f30",1e4),("brk",100)]:
            for key in (["sd","dd"] if (pk=="plac_orig" and col=="b30") else ["dd"]):
                dm, lo, hi = ci(r, p, col, key)
                row[f"{col}-{pk[5:]}/{key}"] = f"{dm*sc:+.1f}[{lo*sc:+.1f},{hi*sc:+.1f}]" + ("*" if lo>0 or hi<0 else "")
    for col in ["f30","g30"]:
        m, lo, hi = ci1(r, col, "dd"); row[f"{col}_real_abs"] = f"{m*1e4:+.1f}[{lo*1e4:+.1f},{hi*1e4:+.1f}]" + ("*" if lo>0 or hi<0 else "")
    out.append(row)
T = pd.DataFrame(out); pd.set_option("display.width", 400); pd.set_option("display.max_columns", 50)
for cols in [["defn","half","n","off_real","off_orig","off_xday","b30-orig/sd","b30-orig/dd","b30-xday/dd"],
             ["defn","half","f30-orig/dd","f30-xday/dd","brk-orig/dd","brk-xday/dd","f30_real_abs","g30_real_abs"]]:
    print(T[cols].round(2).to_string(index=False))
T.to_csv("review2/sr_re_table.csv", index=False)
# 5m pivots: side split, and break conditioned on offset
x = R[(R.defn=="D1_5m")]
print(x.groupby(["kind","side"]).agg(n=("f30","size"), f30=("f30", lambda v: v.mean()*1e4), brk=("brk","mean"), off=("off", lambda v: v.mean()*1e4)).round(2))
