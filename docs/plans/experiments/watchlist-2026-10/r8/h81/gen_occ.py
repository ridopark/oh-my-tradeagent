# Candidate OCC symbols for the 09:31 ATM straddle on each listed (d, ticker). Spot = open of the 09:31 1m bar.
import json, math, datetime as dt, pandas as pd, os
O = os.path.dirname(os.path.abspath(__file__)); W = os.path.dirname(os.path.dirname(O))
L = pd.read_csv(f"{W}/legs_raw.psv", sep="|", header=None, names="d workflow_id ts ticker dir level mode mult".split())
P = L.drop_duplicates(["d", "ticker"])[["d", "ticker"]]
r = pd.read_parquet(f"{W}/rth_1m.parquet"); r["d"] = r.d.astype(str)
s931 = r[r.t.dt.strftime("%H:%M") == "09:31"].set_index(["d", "s"]).o
occ = lambda u, e, cp, k: f"{u}{e.strftime('%y%m%d')}{cp}{int(round(k*1000)):08d}"
jobs, meta = {}, []
for d, u in P.itertuples(index=False):
    S = s931.get((d, u))
    if S is None or math.isnan(S): meta.append({"d": d, "u": u, "S": None}); continue
    D = dt.date.fromisoformat(d)
    exps = [D + dt.timedelta(days=k) for k in range(2, 10) if (D + dt.timedelta(days=k)).weekday() < 5]
    ks = sorted({round(f(S / inc) * inc, 2) for inc in (0.5, 1, 2.5, 5, 10) for f in (math.floor, math.ceil)})
    syms = [occ(u, e, cp, k) for e in exps for k in ks for cp in "CP"]
    jobs.setdefault(d, []).extend(syms); meta.append({"d": d, "u": u, "S": S})
json.dump(jobs, open(f"{O}/occ_jobs.json", "w")); pd.DataFrame(meta).to_csv(f"{O}/straddle_spots.csv", index=False)
print(len(jobs), sum(map(len, jobs.values())), sum(m["S"] is None for m in meta))
