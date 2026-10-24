import pandas as pd, numpy as np, datetime as dt
rng = np.random.default_rng(21)
raw = pd.read_json("stock_1m.jsonl.gz", lines=True, compression="gzip")
raw["t"] = pd.to_datetime(raw.t, utc=True).dt.tz_convert("America/New_York"); raw["d"] = raw.t.dt.date
mm = raw.t.dt.hour*60+raw.t.dt.minute
rth = raw[(mm>=570)&(mm<960)].assign(m=mm[(mm>=570)&(mm<960)]-570); pm = raw[(mm>=240)&(mm<570)]
D = rth.groupby(["s","d"]).agg(H=("h","max"), L=("l","min"), O=("o","first"), C=("c","last")).reset_index().sort_values(["s","d"])
for c in "HLC": D["PD"+c] = D.groupby("s")[c].shift(1)
D["PWH"] = D.groupby("s").H.transform(lambda x: x.shift(1).rolling(5).max()); D["PWL"] = D.groupby("s").L.transform(lambda x: x.shift(1).rolling(5).min())
D = D.merge(pm.groupby(["s","d"]).agg(PMH=("h","max"), PML=("l","min"), PMC=("c","last")).reset_index(), on=["s","d"], how="left")
D = D.set_index(["s","d"])
sess = {k: g.sort_values("m").reset_index(drop=True) for k,g in rth.groupby(["s","d"])}
def step(s, px): return 5 if s in ("SPY","QQQ") or px < 500 and px >= 100 else (1 if px < 100 else 10)
def target(s, d, px, sg):
    r = D.loc[(s,d)]; cands = [r[k] for k in ("PDH","PDL","PMH","PML","PWH","PWL") if pd.notna(r[k])]
    st = step(s, px); base = px*(1+sg*.0015)
    cands.append(np.ceil(base/st)*st if sg==1 else np.floor(base/st)*st)
    beyond = [c for c in cands if (c - base)*sg >= 0]
    return min(beyond, key=lambda c: abs(c-px))
def trade(s, d, lvl, sg, conf, skip30):
    g = sess.get((s,d))
    if g is None or len(g) < 300 or (s,d) not in D.index: return None
    o,h,l,c,m = (g[k].values for k in "ohlcm")
    if (o[0]-lvl)*sg > 0: return None                       # opens beyond trigger: no break
    start = 30 if skip30 else 0
    tf = {"break":1,"m5":5,"m15":15}[conf]
    # tf-bar closes: index of last 1m bar in each tf bucket
    bucket = m//tf; ends = np.r_[np.where(np.diff(bucket)!=0)[0], len(m)-1]
    entry_i = None
    if conf == "break":
        for j in range(1, len(c)):
            if m[j] < start: continue
            if (c[j-1]-lvl)*sg <= 0 and ((h[j] if sg==1 else -l[j]) >= lvl*sg):
                entry_i, p0 = j, (o[j] if (o[j]-lvl)*sg > 0 else lvl); break
    else:
        for a, b in zip(ends[:-1], ends[1:]):
            if m[b] < start+tf-1: continue
            if (c[a]-lvl)*sg <= 0 and (c[b]-lvl)*sg > 0: entry_i, p0 = b, c[b]; break
    if entry_i is None: return None
    tgt = target(s, d, p0, sg); endset = set(ends)
    for j in range(entry_i+1, len(c)):
        if m[j] > 385: return sg*(c[j-1]-p0)/p0, "eod", m[entry_i]
        stop = (j in endset) and (c[j]-lvl)*sg <= 0
        if stop: return sg*(c[j]-p0)/p0, "stop", m[entry_i]
        if (h[j] if sg==1 else -l[j]) >= tgt*sg: return sg*(tgt-p0)/p0, "target", m[entry_i]
    return sg*(c[-1]-p0)/p0, "eod", m[entry_i]
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"])
legs["d"] = pd.to_datetime(legs.d).dt.date; legs = legs.drop_duplicates(["d","s","dir","lvl"])
SPLIT = dt.date(2026,9,1); days = {}
for (s,d) in sess: days.setdefault(s, []).append(d)
rows = []
for L in legs.itertuples():
    if (L.s,L.d) not in D.index or pd.isna(D.loc[(L.s,L.d),"PMC"]) or abs(L.lvl/D.loc[(L.s,L.d),"PMC"]-1) > .10: continue
    sg = 1 if L.dir=="ABOVE" else -1; half = "TEST" if L.d >= SPLIT else "FIT"
    off = L.lvl/D.loc[(L.s,L.d),"O"]
    pool = [x for x in days[L.s] if x != L.d and (x>=SPLIT)==(L.d>=SPLIT) and (L.s,x) in D.index]
    plac = rng.choice(pool, size=min(20,len(pool)), replace=False) if pool else []
    for conf in ["break","m5","m15"]:
        for skip in [False, True]:
            v = f"{conf}|skip30={skip}"
            r = trade(L.s, L.d, L.lvl, sg, conf, skip)
            if r: rows.append(("real", half, L.d, L.s, L.dir, v, *r))
            for x in plac:
                rp = trade(L.s, x, D.loc[(L.s,x),"O"]*off, sg, conf, skip)
                if rp: rows.append(("placebo", half, x, L.s, L.dir, v, *rp))
R = pd.DataFrame(rows, columns=["kind","half","d","s","dir","v","ret","exit","entry_m"]); R.to_parquet("author_rules.parquet")
print(R.groupby(["kind","half"]).size())
