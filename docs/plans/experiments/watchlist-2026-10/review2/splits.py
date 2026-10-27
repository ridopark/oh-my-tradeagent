import pandas as pd, numpy as np
# check raw vs adjusted prices: daily close-to-open jumps > 25%
for s in ["NVDA","TSLA","AAPL","GOOGL","AMZN"]:
    df = pd.read_json(f"decade/{s}.jsonl.gz", lines=True, compression="gzip")
    if s == "NVDA": print(df.head(2)); print(df.columns.tolist(), len(df))
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df["d"] = df.t.dt.date
    dd = df.groupby("d").agg(o=("o","first"), c=("c","last"))
    r = dd.o / dd.c.shift() - 1
    print(s, {str(k): v for k, v in r[r.abs() > .25].round(3).items()})
R = pd.read_parquet("sr_decade.parquet")
spl = {"NVDA":["2021-07-20","2024-06-10"],"TSLA":["2020-08-31","2022-08-25"],"AAPL":["2020-08-31"],"GOOGL":["2022-07-18"],"AMZN":["2022-06-06"]}
for s, ds in spl.items():
    for d0 in ds:
        d0 = pd.Timestamp(d0).date()
        x = R[(R.s==s)&(R.d>=d0)&(R.d<d0+pd.Timedelta(days=5))&(R.kind=="real")]
        y = R[(R.s==s)&(R.d>=d0-pd.Timedelta(days=30))&(R.d<d0)&(R.kind=="real")]
        print(s, d0, "real rows split..+5d:", x.groupby("defn").size().to_dict(), "| per-5d avg before:", (y.groupby("defn").size()/6).round(1).to_dict())
