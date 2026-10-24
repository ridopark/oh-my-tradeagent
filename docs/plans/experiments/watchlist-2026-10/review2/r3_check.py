import pandas as pd, numpy as np
A = pd.read_parquet("sr_author.parquet"); L = pd.read_csv("authorlines/lines.csv"); C = pd.read_csv("authorlines/charts.csv")
L["pt"] = pd.to_datetime(L.posted_at_et); L["pd"] = L.pt.dt.date; L["pm"] = L.pt.dt.hour*60+L.pt.dt.minute-570
print(L.line_kind.value_counts().to_dict(), L.confidence.value_counts().to_dict(), L.timeframe.value_counts().to_dict())
print("note tags:", L.note.str.extract(r"^\[(\w+)\]")[0].value_counts().to_dict())
print("A dates", A.d.min(), A.d.max(), "posts", L.pd.min(), L.pd.max())
print(A.groupby(["defn","kind"]).size())
# earliest post per ticker; touches on the post day before post time?
r = A[A.kind=="real"].copy()
first = L.groupby("ticker").pt.min()
r["first_post"] = r.s.map(first)
r["touch_t"] = pd.to_datetime(r.d.astype(str)) + pd.to_timedelta(r.m+570, unit="m")
print("real touches before ANY post of that ticker:", int((r.touch_t < r.first_post).sum()), "of", len(r))
# same day as a post of that ticker, before the post time
L2 = L[["ticker","pt"]].drop_duplicates()
def pre_same_day(row):
    p = L2[(L2.ticker==row.s)&(L2.pt.dt.date==row.d)]
    return len(p)>0 and (row.touch_t < p.pt).all()
r["pre_same_day"] = r.apply(pre_same_day, axis=1)
print("real touches on a post day but before all that day's posts:", int(r.pre_same_day.sum()))
print("per-day clustering: real rows", len(r), "unique (s,d)", r.groupby(["s","d"]).ngroups, "unique d", r.d.nunique())
x = A[A.defn=="D5_author_swing"]
for k in ["real","placebo"]:
    y = x[x.kind==k]; print(k, len(y), "b30 bps", round(y.b30.mean()*1e4,1), "brk", round(y.brk.mean(),3))
print("touch minute dist real swing:", r[r.defn=="D5_author_swing"].m.describe().round(0).to_dict())
