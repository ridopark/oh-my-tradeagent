import pandas as pd, numpy as np, datetime as dt
rng = np.random.default_rng(12)
E = pd.read_parquet("convex/entries.parquet"); U = pd.read_parquet("convex/und.parquet")
O = pd.read_json("convex/opt_bars.jsonl.gz", lines=True, compression="gzip")
O["t"] = pd.to_datetime(O.t, utc=True).dt.tz_convert("America/New_York"); O["m"] = O.t.dt.hour*60+O.t.dt.minute-570; O["d"] = O.t.dt.date
HS = pd.read_csv("half_spread.csv"); HS["k"] = HS.kb.str.rstrip("%").astype(float)/100
hs = {(r.k, r.hm): r.half_spread for r in HS.itertuples()}
close = U[U.hm=="15:59"].set_index(["s","d"]).c
daily = U.groupby(["s","d"]).agg(o=("o","first"), c=("c","last")).reset_index().sort_values(["s","d"])
daily["ret"] = daily.groupby("s").c.pct_change(); daily["rv20"] = daily.groupby("s").ret.transform(lambda x: x.shift(1).rolling(20).std())
daily["gap"] = (daily.o/daily.groupby("s").c.shift(1)-1).abs()
daily["rvt"] = daily.groupby("s").rv20.transform(lambda x: pd.qcut(x.rank(method="first"), 3, labels=["lowvol","midvol","highvol"]))
reg = daily.set_index(["s","d"])
g = {s: x.sort_values("m") for s, x in O.groupby("S" if "S" in O else "s")}
rows = []
for r in E.itertuples():
    b = g.get(r.occ)
    if b is not None: b = b[(b.d==r.d)&(b.m<=389)]
    if b is None or (r.u, r.d) not in close.index: continue
    em = {"10:00":30,"12:00":150,"14:00":270}[r.hm]
    after = b[(b.m>=em)&(b.m<=em+5)]
    if after.empty: continue
    px = after.vw.iloc[0] if "vw" in after else after.c.iloc[0]
    if not px or px <= 0: continue
    h = hs[(r.k, r.hm)]; cost = px*(1+h)
    cl = close[(r.u, r.d)]; intr = max(0, cl-r.strike) if r.right=="C" else max(0, r.strike-cl)
    exit_hold = intr*(1-hs[(.0025,"14:00")]) if intr > 0 else 0.0
    path = b[b.m > after.m.iloc[0]]
    hit = path[path.h >= 3*cost]
    exit_3x = 3*cost*(1-hs[(.0025,"14:00")]) if len(hit) else exit_hold
    rr = reg.loc[(r.u, r.d)]
    rows.append((r.u, r.d, r.hm, r.k, r.right, px, cost, exit_hold/cost, exit_3x/cost, rr.rvt, rr.gap > .005))
R = pd.DataFrame(rows, columns=["u","d","hm","k","right","px","cost","m_hold","m_3x","rvt","gap"])
R["half"] = np.where(R.d >= dt.date(2025,7,1), "TEST", "FIT"); R.to_parquet("convex/ev.parquet")
print(len(R), "of", len(E), "entries priced")
