import pandas as pd, gzip, json, datetime as dt
with gzip.open("oq/SPY/2026-10-06.ndjson.gz", "rt") as fh:
    L = [json.loads(next(fh)) for _ in range(3)]
print(L)
e = pd.read_parquet("events.parquet"); print(e.columns.tolist()); print(e.head(3).T)
x = e[(e.kind=="real")&(e.fired==1)&(e.s.isin(["SPY","QQQ"]))&(e.d>=dt.date(2026,10,6))]
print(x[["d","s","dir","gap","fire_min"] + [c for c in x.columns if c in ("lvl","level")]].to_string())
T = pd.read_parquet("trail_bid.parquet"); print(T.head()); print(T.groupby(["exp","who","trail"]).ret.agg(["count","mean","median"]).round(3).to_string())
