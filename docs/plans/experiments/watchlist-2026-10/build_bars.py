import pandas as pd, gzip, json, sys
df = pd.read_json(sys.argv[1], lines=True, compression="gzip")
df["t"] = pd.to_datetime(df["t"], utc=True).dt.tz_convert("America/New_York")
df["d"] = df.t.dt.date
mins = df.t.dt.hour*60 + df.t.dt.minute
df = df[(mins >= 570) & (mins < 960)][["s","t","d","o","h","l","c","v"]]
df.to_parquet("rth_1m.parquet"); print(len(df), df.d.nunique())
