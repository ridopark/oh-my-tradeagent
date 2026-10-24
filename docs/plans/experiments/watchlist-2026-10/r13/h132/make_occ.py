"""H13.2 step 2a: per-day SPY 0DTE contract to fetch. ATM strike = nearest $1 to c(15:30); call if r_first>0, put if <0.
Writes occ_list.csv and per-month comma-joined symbol files for fetch.sh (bars for an OCC also exist on earlier
days -> opt_eval.py filters to the trade date)."""
import pandas as pd, numpy as np
OUT = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r13/h132"
d = pd.read_parquet(f"{OUT}/daily_SPY.parquet"); d.index = pd.to_datetime(d.index)
d = d.loc["2024-02-01":"2026-10-22"]
d = d[d.r_first != 0]
d["strike"] = d.c1530.round().astype(int)
d["side"] = np.where(d.r_first > 0, "C", "P")
d["occ"] = "SPY" + d.index.strftime("%y%m%d") + d.side + (d.strike * 1000).astype(int).astype(str).str.zfill(8)
d["month"] = d.index.strftime("%Y-%m")
d[["c1530", "r_first", "strike", "side", "occ", "month"]].to_csv(f"{OUT}/occ_list.csv")
for m, g in d.groupby("month"):
    open(f"{OUT}/occ_{m}.txt", "w").write(",".join(g.occ))
print(len(d), "contracts;", d.side.value_counts().to_dict(), "; months:", d.month.nunique())
print(d.head(3).to_string())
