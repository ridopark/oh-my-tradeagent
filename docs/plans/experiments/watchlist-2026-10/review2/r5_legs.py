import pandas as pd, datetime as dt
e = pd.read_parquet("events.parquet"); e = e[(e.kind=="real")&(e.fired==1)&(e.gap==0)&e.s.isin(["SPY","QQQ"])&(e.d>=dt.date(2026,10,6))]
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"]); legs["d"] = pd.to_datetime(legs.d).dt.date
g = legs.groupby(["d","s","dir"]).lvl.agg(list)
for r in e.itertuples():
    L = g.get((r.d, r.s, r.dir), [])
    if len(L) > 1: print(r.d, r.s, r.dir, "levels:", L, "-> trail_bid uses", L[-1], "| p0", r.p0)
print("event cols include level?", [c for c in e.columns if "lv" in c or c=="off"])
