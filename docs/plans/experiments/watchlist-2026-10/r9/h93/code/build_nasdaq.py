# Parse raw Nasdaq calendar files -> nasdaq_earnings.csv (sample tickers only) + news query list
import json, glob, sys, os, datetime as dt
import pandas as pd
H = os.path.dirname(os.path.abspath(__file__)) + "/.."
e = pd.read_parquet(sys.argv[1])
S = sorted(e.s.unique())
rows = []
for f in sorted(glob.glob(f"{H}/raw/nasdaq_*.json")):
    D = f[-15:-5]; j = json.load(open(f))
    for r in ((j.get("data") or {}).get("rows")) or []:
        if r["symbol"] in S:
            rows.append(dict(s=r["symbol"], D=D, nasdaq_time=r["time"], name=r["name"], fq=r["fiscalQuarterEnding"]))
n = pd.DataFrame(rows); n.to_csv(f"{H}/nasdaq_earnings.csv", index=False)
with open(f"{H}/news_queries.txt", "w") as q:
    for r in n.itertuples():
        D = dt.date.fromisoformat(r.D)
        q.write(f"{r.s},{r.D},{(D-dt.timedelta(days=1)).isoformat()}T16:00:00Z,{(D+dt.timedelta(days=1)).isoformat()}T16:00:00Z\n")
print(len(n), "earnings rows;", n.s.nunique(), "tickers")
