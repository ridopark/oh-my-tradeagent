"""Independent R5: trail on option bid path, author SPY/QQQ watchlist triggers 2026-10-06..10-22."""
import pandas as pd, numpy as np, glob, json, gzip, datetime as dt
rng = np.random.default_rng(21)
q = []
for f in sorted(glob.glob("oq/*/*.ndjson.gz")):
    with gzip.open(f, "rt") as fh:
        for l in fh:
            r = json.loads(l); q.append((r["ts"], r["sym"], r["bp"], r["ap"]))
Q = pd.DataFrame(q, columns=["ts","sym","bp","ap"]); Q["t"] = pd.to_datetime(Q.ts.str[:19]); Q["d"] = Q.t.dt.date
Q["m"] = Q.t.dt.hour*60+Q.t.dt.minute-570
Q = Q[(Q.m>=0)&(Q.m<=385)&Q.sym.str.match(r"^(SPY|QQQ)\d{6}[CP]\d{8}$")]
Q["exp"] = pd.to_datetime("20"+Q.sym.str[3:9], format="%Y%m%d").dt.date
Q["k"] = Q.sym.str[-8:].astype(int)/1000; Q["right"] = Q.sym.str[9]
print("raw quote rows", len(Q), "per contract-day-minute max", Q.groupby(["sym","d","m"]).size().max())
print("DTE available:", sorted(((pd.to_datetime(Q.exp)-pd.to_datetime(Q.d)).dt.days).unique().tolist())[:10])
good = (Q.bp>0)&(Q.ap>=Q.bp)
print("rows with bp<=0 or crossed (dropped by original):", int((~good).sum()), "of", len(Q))
Qf = Q[good].sort_values("t").drop_duplicates(["sym","d","m"], keep="first")   # original: first quote in minute
Ql = Q[good].sort_values("t").drop_duplicates(["sym","d","m"], keep="last")
B = pd.read_parquet("rth_1m.parquet"); B = B[B.s.isin(["SPY","QQQ"])&(B.d>=dt.date(2026,10,6))]
B["m"] = B.t.dt.hour*60+B.t.dt.minute-570; spot = {(r.s, r.d, r.m): r.c for r in B.itertuples()}
e = pd.read_parquet("events.parquet"); e = e[(e.kind=="real")&(e.fired==1)&(e.gap==0)&e.s.isin(["SPY","QQQ"])&(e.d>=dt.date(2026,10,6))]
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"]); legs["d"] = pd.to_datetime(legs.d).dt.date
nleg = legs.groupby(["d","s","dir"]).size(); print("triggers", len(e), "| (d,s,dir) with >1 leg:", int((nleg>1).sum()))
wl = {(r.d, r.s, r.dir): r.lvl for r in legs.itertuples()}
def sim(bid, ask, i, w):
    p0 = ask[i]; peak = bid[i]
    for j in range(i+1, len(bid)):
        peak = max(peak, bid[j])
        if w and bid[j] <= peak*(1-w): return bid[j]/p0-1, j
    return bid[-1]/p0-1, len(bid)-1
rows = []
for QQ, qtag in [(Qf,"first"), (Ql,"last")]:
    for r in e.itertuples():
        sg = 1 if r.dir=="ABOVE" else -1; right = "C" if sg==1 else "P"; lvl = wl[(r.d, r.s, r.dir)]
        day = QQ[(QQ.d==r.d)&QQ.sym.str.startswith(r.s)&(QQ.right==right)&(QQ.exp==r.d)]
        em = int(r.fire_min)+1; sp = spot.get((r.s, r.d, em-1))
        k_orig = (np.ceil(lvl)+(1 if np.ceil(lvl)==lvl else 0)) if sg==1 else (np.floor(lvl)-(1 if np.floor(lvl)==lvl else 0))
        k_atm = round(sp) if sp else None
        for ktag, kk in [("orig", k_orig), ("atm", k_atm)]:
            if kk is None: continue
            path = day[day.k==kk].sort_values("m").reset_index(drop=True)
            if len(path) < 100: continue
            bid, ask, mm = path.bp.values, path.ap.values, path.m.values
            i = int(np.searchsorted(mm, em))
            if i >= len(bid)-5: continue
            ents = [("author", i, em)]
            for _ in range(5):   # time-matched control: same contract, entry within +-30 min of author
                m2 = int(np.clip(em+rng.integers(-30, 31), 0, 380)); i2 = int(np.searchsorted(mm, m2))
                if i2 < len(bid)-5: ents.append(("rand30", i2, m2))
            for who, ii, m_ in ents:
                for w in [0, .10, .20, .30]:
                    ret, jx = sim(bid, ask, ii, w)
                    rows.append((qtag, ktag, r.d, r.s, r.dir, who, m_, w, ret, ask[ii]/bid[ii]-1, ask[ii]))
R = pd.DataFrame(rows, columns=["q","k","d","s","dir","who","m","w","ret","spr","p0"]); R.to_parquet("review2/r5_trail.parquet")
def boot(v, B=4000):
    v = np.asarray(v); idx = rng.integers(0, len(v), (B, len(v))); return np.percentile(v[idx].mean(1), [2.5, 97.5])
for (qt, kt), x in R[R.who=="author"].groupby(["q","k"]):
    hold = x[x.w==0].set_index(["d","s","dir"]).ret
    print(f"\n== quote={qt} strike={kt}: n={len(hold)} (orig study n=18), early(m<30) included: {int((x[x.w==0].m<30).sum())}, median spread {x[x.w==0].spr.median():.3f}")
    lo, hi = boot(hold); print(f"  hold mean {hold.mean():+.3f} [{lo:+.2f},{hi:+.2f}] median {hold.median():+.3f}; top-2 share of sum {np.sort(hold.values)[-2:].sum()/max(hold.sum(),1e-9):.2f}")
    for w in [.10, .20, .30]:
        t = x[x.w==w].set_index(["d","s","dir"]).ret; dlt = (t-hold).dropna(); lo, hi = boot(dlt)
        print(f"  trail{int(w*100)} mean {t.mean():+.3f} median {t.median():+.3f} | trail-hold {dlt.mean():+.3f} [{lo:+.2f},{hi:+.2f}]")
    rr = R[(R.q==qt)&(R.k==kt)&(R.who=="rand30")]
    print("  time-matched random (+-30m) mean by trail:", rr.groupby("w").ret.mean().round(3).to_dict())
