import pandas as pd, numpy as np, glob, json, gzip, datetime as dt
rng = np.random.default_rng(8)
q = []
for f in glob.glob("oq/*/*.ndjson.gz"):
    with gzip.open(f, "rt") as fh:
        for l in fh:
            r = json.loads(l); q.append((r["ts"][:16], r["sym"], r["bp"], r["ap"]))
Q = pd.DataFrame(q, columns=["ts","sym","bp","ap"]); Q["t"] = pd.to_datetime(Q.ts); Q["d"] = Q.t.dt.date
Q["m"] = Q.t.dt.hour*60+Q.t.dt.minute-570; Q = Q[(Q.m>=0)&(Q.m<=385)&(Q.bp>0)&(Q.ap>=Q.bp)].drop_duplicates(["sym","d","m"])
Q = Q[Q.sym.str.match(r"^(SPY|QQQ)\d{6}[CP]\d{8}$")]; Q["exp"] = pd.to_datetime("20"+Q.sym.str[3:9], format="%Y%m%d").dt.date
print("quotes", len(Q), "days", Q.d.nunique(), "contracts", Q.sym.nunique())
e = pd.read_parquet("events.parquet"); e = e[(e.kind=="real")&(e.fired==1)&(e.gap==0)&e.s.isin(["SPY","QQQ"])&(e.d>=dt.date(2026,10,6))]
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"]); legs["d"]=pd.to_datetime(legs.d).dt.date
wl = {(r.d, r.s, r.dir): r.lvl for r in legs.itertuples()}
print("author SPY/QQQ triggers in window:", len(e))
TRAILS = [("pct",.10),("pct",.20),("pct",.30),("vol",1),("vol",2),("vol",3),("none",0)]
def sim(path, i, kind, w, sd):
    bid, ask = path.bp.values, path.ap.values; p0 = ask[i]; peak = bid[i]
    for j in range(i+1, len(bid)):
        peak = max(peak, bid[j])
        stop = peak*(1-w) if kind=="pct" else (peak - w*sd if kind=="vol" else -1)
        if bid[j] <= stop: return bid[j]/p0-1
    return bid[-1]/p0-1
rows = []
for r in e.itertuples():
    sg = 1 if r.dir=="ABOVE" else -1; right = "C" if sg==1 else "P"
    lvl = [v for (d,s,dd),v in wl.items() if d==r.d and s==r.s and dd==r.dir][0]
    k = np.ceil(lvl)+ (1 if np.ceil(lvl)==lvl else 0) if sg==1 else np.floor(lvl) - (1 if np.floor(lvl)==lvl else 0)
    day = Q[(Q.d==r.d)&Q.sym.str.startswith(r.s)&(Q.sym.str[9]==right)]
    exps = sorted(day.exp.unique())
    for tag, ex in [("0DTE", r.d), ("next", next((x for x in exps if x > r.d), None))]:
        sym = f"{r.s}{ex:%y%m%d}{right}{int(k*1000):08d}" if ex else None
        path = day[day.sym==sym].sort_values("m").reset_index(drop=True) if sym else pd.DataFrame()
        if len(path) < 100: continue
        mid = (path.bp+path.ap)/2
        def entry_idx(minute): 
            ii = np.searchsorted(path.m.values, minute); return ii if ii < len(path)-5 else None
        ents = [("author", entry_idx(r.fire_min+1))] + [("random", entry_idx(int(x))) for x in rng.integers(30, 330, 5)]
        for who, i in ents:
            if i is None or i < 30: continue
            sd = np.std(np.diff(mid.values[i-30:i+1]))
            for kind, w in TRAILS:
                rows.append((r.d, r.s, r.dir, tag, sym, who, f"{kind}{w}", sim(path, i, kind, w, sd), path.ap[i]/path.bp[i]-1))
R = pd.DataFrame(rows, columns=["d","s","dir","exp","sym","who","trail","ret","spread"]); R.to_parquet("trail_bid.parquet")
print("trades (author):", R[(R.who=="author")&(R.trail=="none0")].groupby("exp").size().to_dict(), "median entry spread", round(R.spread.median(),3))
T = R.groupby(["exp","trail","who"]).ret.mean().unstack("who")*100
T["author-random"] = T.author - T.random; print(T.round(1))
