import pandas as pd, numpy as np, datetime as dt
rng = np.random.default_rng(7)
bars = pd.read_parquet("rth_1m.parquet")
bars["m"] = bars.t.dt.hour*60 + bars.t.dt.minute - 570
sess = {k: g.sort_values("m").reset_index(drop=True) for k, g in bars.groupby(["s","d"])}
days_by_sym = {}
for (s,d) in sess: days_by_sym.setdefault(s, []).append(d)
for s in days_by_sym: days_by_sym[s].sort()
prevclose = {}
for s, ds in days_by_sym.items():
    for a, b in zip(ds, ds[1:]): prevclose[(s,b)] = sess[(s,a)].c.iloc[-1]

legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"])
legs["d"] = pd.to_datetime(legs.d).dt.date
legs = legs.drop_duplicates(["d","s","dir","lvl"]).reset_index(drop=True)
SPLIT = dt.date(2026,9,1)
H = [5,15,30,60]
def evaluate(s, d, direction, lvl):
    g = sess.get((s,d))
    if g is None or len(g) < 300: return None
    sg = 1 if direction == "ABOVE" else -1
    beyond = (g.c - lvl)*sg > 0
    if not beyond.any(): return dict(fired=0)
    i = int(np.argmax(beyond.values)); gap = int((g.o.iloc[0]-lvl)*sg > 0)
    p0 = g.c.iloc[i]; m0 = g.m.iloc[i]
    r = dict(fired=1, gap=gap, fire_min=int(m0), p0=p0)
    for h in H:
        j = g.index[g.m >= m0+h]
        r[f"r{h}"] = sg*(g.c.iloc[j[0]]-p0)/p0 if len(j) else np.nan
    end = g[g.m <= 385]
    r["rclose"] = sg*(end.c.iloc[-1]-p0)/p0
    path = end.iloc[i+1:] if i+1 < len(end) else end.iloc[i:]
    hi, lo = (path.h, path.l) if sg==1 else (-path.l, -path.h)
    r["mfe"] = (hi.max()-sg*p0)/p0*sg if sg==1 else (-path.l.min()+p0)/p0
    r["mae"] = (lo.min()-sg*p0)/p0 if sg==1 else (p0-path.h.max())/p0
    return r
rows = []
for _, L in legs.iterrows():
    pc = prevclose.get((L.s, L.d))
    if pc is None: continue
    off = L.lvl/pc - 1
    r = evaluate(L.s, L.d, L.dir, L.lvl)
    if r is None: continue
    rows.append(dict(kind="real", s=L.s, d=L.d, dir=L.dir, off=off, **r))
    half = [x for x in days_by_sym[L.s] if (x >= SPLIT) == (L.d >= SPLIT) and x != L.d and (L.s,x) in prevclose]
    for x in rng.choice(half, size=min(20, len(half)), replace=False) if half else []:
        rp = evaluate(L.s, x, L.dir, prevclose[(L.s,x)]*(1+off))
        if rp: rows.append(dict(kind="placebo", s=L.s, d=x, dir=L.dir, off=off, src_d=L.d, **rp))
out = pd.DataFrame(rows); out["half"] = np.where(out.d >= SPLIT, "TEST", "FIT")
out.to_parquet("events.parquet"); print(out.groupby(["kind","half"]).size())
