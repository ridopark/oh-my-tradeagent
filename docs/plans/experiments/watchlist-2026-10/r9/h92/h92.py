# H9.2 early option flow tag vs leg outcomes, per PREREG9 + AMENDMENTS.md
import json, numpy as np, pandas as pd, sys
WL = sys.argv[1]; H = WL + "/r9/h92"
rng = np.random.default_rng(92); B = 10000
fl = {}
for l in open(H + "/flow_raw.jsonl"):
    r = json.loads(l); a = np.zeros(60)
    for m, rt, v in r["m"]: a[m] += v if rt == "call" else -v
    fl[(r["s"], r["d"])] = np.cumsum(a)          # cum call-put through minute index m
days = {}
for (s, d) in fl: days.setdefault(s, []).append(d)
for s in days: days[s].sort()

def tag(s, d, dirn, fm):
    if (s, d) not in fl: return np.nan, "NA_nodata"
    k = int(min(fm, 59)); ds = days[s]; i = ds.index(d)
    base = np.array([fl[(s, x)][k] for x in ds[max(0, i-20):i]])
    nz = sum(1 for x in ds[max(0, i-20):i] if np.any(fl[(s, x)] != 0))
    if len(base) < 20 or nz < 15 or base.std(ddof=1) == 0: return np.nan, "NA_baseline"
    z = (fl[(s, d)][k] - base.mean()) / base.std(ddof=1) * (1 if dirn == "ABOVE" else -1)
    return z, "AGREE" if z >= 1 else "DISAGREE" if z <= -1 else "SILENT"

e = pd.read_parquet(WL + "/events.parquet")
e = e[(e.fired == 1) & (e.gap == 0) & (e.fire_min >= 15)].copy()
e["d"] = e.d.astype(str)
e = e[(e.kind == "real") | e.s.isin(e[e.kind == "real"].s.unique())]   # A5
zt = [tag(r.s, r.d, r.dir, r.fire_min) for r in e.itertuples()]
e["z"] = [a for a, _ in zt]; e["tag"] = [b for _, b in zt]
e.to_parquet(H + "/legs_tagged.parquet")
real = e[e.kind == "real"]; plac = e[e.kind == "placebo"]

def boot(df, a, b, col, other=None):
    """day-clustered bootstrap of mean(col|tag a) - mean(col|tag b); other = second df for tag b (placebo)."""
    X = df[df.tag == a][["d", col]].dropna(); Y = (other if other is not None else df)
    Y = Y[Y.tag == b][["d", col]].dropna()
    if len(X) < 2 or len(Y) < 2: return len(X), len(Y), np.nan, np.nan, np.nan
    pt = X[col].mean() - Y[col].mean()
    dd = np.array(sorted(set(X.d) | set(Y.d)))
    gx = X.groupby("d")[col].agg(["sum", "count"]).reindex(dd, fill_value=0).values
    gy = Y.groupby("d")[col].agg(["sum", "count"]).reindex(dd, fill_value=0).values
    idx = rng.integers(0, len(dd), (B, len(dd)))
    sx, cx = gx[idx, 0].sum(1), gx[idx, 1].sum(1); sy, cy = gy[idx, 0].sum(1), gy[idx, 1].sum(1)
    ok = (cx > 0) & (cy > 0); bs = sx[ok]/cx[ok] - sy[ok]/cy[ok]
    return len(X), len(Y), pt, np.percentile(bs, 2.5), np.percentile(bs, 97.5)

out = []
P = lambda x: f"{x*1e4:+.1f}"
out.append("## Tag counts (real legs)\n\n" + real.groupby(["half", "tag"]).size().unstack(fill_value=0).to_string())
out.append("## Tag counts (placebo legs, A5 scope)\n\n" + plac.groupby(["half", "tag"]).size().unstack(fill_value=0).to_string())
m = real[real.tag.isin(["AGREE", "SILENT", "DISAGREE"])].groupby(["half", "tag"]).agg(
    n=("r60", "size"), r60_bps=("r60", lambda x: x.mean()*1e4), rclose_bps=("rclose", lambda x: x.mean()*1e4),
    ndays=("d", "nunique"))
out.append("## Mean outcomes per tag (real, bps)\n\n" + m.round(1).to_string())
mp = plac[plac.tag.isin(["AGREE", "SILENT", "DISAGREE"])].groupby(["half", "tag"]).agg(
    n=("r60", "size"), r60_bps=("r60", lambda x: x.mean()*1e4), rclose_bps=("rclose", lambda x: x.mean()*1e4))
out.append("## Mean outcomes per tag (placebo, bps)\n\n" + mp.round(1).to_string())
rows = []; verdict = {}
for half in ["FIT", "TEST"]:
    R = real[real.half == half]; Pl = plac[plac.half == half]
    for col in ["r60", "rclose"]:
        for name, a, b, oth in [("AGREE-SILENT", "AGREE", "SILENT", None), ("AGREE-DISAGREE", "AGREE", "DISAGREE", None),
                                ("AGREE-placeboAGREE", "AGREE", "AGREE", Pl)]:
            nx, ny, pt, lo, hi = boot(R, a, b, col, oth)
            rows.append(dict(half=half, outcome=col, contrast=name, nA=nx, nB=ny, est_bps=pt*1e4, lo_bps=lo*1e4, hi_bps=hi*1e4,
                             excl0=bool(lo > 0 or hi < 0) if not np.isnan(pt) else None))
            if col == "r60": verdict[(half, name)] = (pt, lo, hi)
res = pd.DataFrame(rows); res.to_csv(H + "/contrasts.csv", index=False)
out.append("## Contrasts (day-clustered bootstrap, B=10000, 95% CI, bps)\n\n" + res.round(1).to_string(index=False))
c1 = all(verdict[(h, "AGREE-SILENT")][0] > 0 and verdict[(h, "AGREE-SILENT")][1] > 0 for h in ["FIT", "TEST"])
c2 = all(verdict[(h, "AGREE-DISAGREE")][0] > 0 for h in ["FIT", "TEST"])
out.append(f"## Pass rule\n\nAGREE-SILENT r60 > 0 with CI excl 0 in FIT and TEST: {c1}\n"
           f"AGREE-DISAGREE r60 point > 0 in both halves: {c2}\n**H9.2 {'PASS' if c1 and c2 else 'FAIL'}**")
# TEST studentized t for the max-t combiner (bootstrap SE)
pt, lo, hi = verdict[("TEST", "AGREE-SILENT")]
out.append(f"TEST AGREE-SILENT r60 t (est / ((hi-lo)/3.92)) = {pt/((hi-lo)/3.92):.2f}")
na = real[real.tag.str.startswith("NA")]
out.append("## Coverage gaps (real legs)\n\n" + (na[["s", "d", "dir", "fire_min", "tag", "half"]].to_string(index=False) if len(na) else "none"))
out.append(f"Placebo legs NA: {plac.tag.str.startswith('NA').sum()} of {len(plac)}")
# sensitivity (descriptive only, not the test): continuous z vs r60 rank corr per half
for half in ["FIT", "TEST"]:
    R = real[(real.half == half) & real.z.notna()]
    out.append(f"{half} Spearman(z, r60) = {R.z.corr(R.r60, method='spearman'):+.3f}, (z, rclose) = {R.z.corr(R.rclose, method='spearman'):+.3f}, n={len(R)}")
print("\n\n".join(out))
