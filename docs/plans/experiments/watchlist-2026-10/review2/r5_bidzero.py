"""How often does the original trail_bid.py hold-exit occur before 15:55 because bid==0 quotes were dropped?"""
import pandas as pd, numpy as np, glob, json, gzip, datetime as dt
T = pd.read_parquet("trail_bid.parquet"); a = T[(T.who=="author")&(T.trail=="none0")]
syms = set(a.sym); q = []
for f in sorted(glob.glob("oq/*/*.ndjson.gz")):
    with gzip.open(f, "rt") as fh:
        for l in fh:
            r = json.loads(l)
            if r["sym"] in syms: q.append((r["ts"], r["sym"], r["bp"], r["ap"]))
Q = pd.DataFrame(q, columns=["ts","sym","bp","ap"]); Q["t"] = pd.to_datetime(Q.ts.str[:19]); Q["d"] = Q.t.dt.date
Q["m"] = Q.t.dt.hour*60+Q.t.dt.minute-570; Q = Q[(Q.m>=0)&(Q.m<=385)]
for r in a.itertuples():
    x = Q[(Q.sym==r.sym)&(Q.d==r.d)].sort_values("m")
    pos = x[(x.bp>0)&(x.ap>=x.bp)]
    last_pos_m = pos.m.max(); last_m = x.m.max(); bid_end = x[x.m==last_m].bp.iloc[0]
    print(r.d, r.sym, f"hold ret(orig) {r.ret:+.2f} | last positive-bid minute {last_pos_m} vs last quote minute {last_m} (bid there {bid_end})")
