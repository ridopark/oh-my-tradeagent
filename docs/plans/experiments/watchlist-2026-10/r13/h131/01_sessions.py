"""Build per-session spot table for SPY/QQQ: 15:55 close/vw, 15:59 close, next-session 09:35 close; OCC list + fetch jobs."""
import gzip, json, sys, pandas as pd, numpy as np
WL = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl"
H = WL + "/r13/h131"
rows = []
for s in ("SPY", "QQQ"):
    with gzip.open(f"{WL}/decade/{s}.jsonl.gz", "rt") as f:
        for line in f:
            if not ('"t": "2024' in line or '"t": "2025' in line or '"t": "2026' in line): continue
            b = json.loads(line); rows.append((s, b["t"], b["o"], b["c"], b.get("vw", np.nan), b["v"]))
dec = pd.DataFrame(rows, columns=["s", "t", "o", "c", "vw", "v"])
dec["t"] = pd.to_datetime(dec["t"], utc=True).dt.tz_convert("America/New_York")
dec = dec[dec.t >= "2024-01-02"]
rth = pd.read_parquet(f"{WL}/rth_1m.parquet")
rth = rth[rth.s.isin(["SPY", "QQQ"])][["s", "t", "o", "c", "v"]].copy(); rth["vw"] = np.nan
bars = pd.concat([dec, rth], ignore_index=True)
bars["d"] = bars.t.dt.date; bars["hm"] = bars.t.dt.strftime("%H:%M")
bars = bars[(bars.hm >= "09:30") & (bars.hm < "16:00")]
print("bar span", bars.t.min(), bars.t.max(), "rows", len(bars))
sessions = sorted(bars.d.unique())
sess = pd.DataFrame({"d": sessions}); sess["next"] = sess.d.shift(-1)
def pick(sym, hm, col):
    x = bars[(bars.s == sym) & (bars.hm == hm)].set_index("d")[col]; return x
out = []
for sym in ("SPY", "QQQ"):
    c1555 = pick(sym, "15:55", "c"); vw1555 = pick(sym, "15:55", "vw"); c1559 = pick(sym, "15:59", "c"); c0935 = pick(sym, "09:35", "c"); c0930 = pick(sym, "09:30", "o")
    for _, r in sess.iterrows():
        d, e = r.d, r.next
        if pd.isna(e) or d not in c1555.index or e not in c0935.index: continue
        out.append(dict(s=sym, d=d, e=e, S_entry=c1555[d], S_entry_vw=vw1555.get(d, np.nan), S_close=c1559.get(d, np.nan), S_open=c0930.get(e, np.nan), S_exit=c0935[e]))
sp = pd.DataFrame(out); sp = sp[(sp.d >= pd.Timestamp("2024-02-01").date()) & (sp.d <= pd.Timestamp("2026-10-21").date())]
sp["K_atm"] = sp.S_entry.round().astype(int); sp["K_c"] = (sp.S_entry * 1.005).round().astype(int); sp["K_p"] = (sp.S_entry * 0.995).round().astype(int)
def occ(sym, e, cp, k): return f"{sym}{e.strftime('%y%m%d')}{cp}{int(round(k*1000)):08d}"
sp["e"] = pd.to_datetime(sp.e); sp["d"] = pd.to_datetime(sp.d)
sp["occ_atm_c"] = [occ(s, e, "C", k) for s, e, k in zip(sp.s, sp.e, sp.K_atm)]
sp["occ_atm_p"] = [occ(s, e, "P", k) for s, e, k in zip(sp.s, sp.e, sp.K_atm)]
sp["occ_str_c"] = [occ(s, e, "C", k) for s, e, k in zip(sp.s, sp.e, sp.K_c)]
sp["occ_str_p"] = [occ(s, e, "P", k) for s, e, k in zip(sp.s, sp.e, sp.K_p)]
sp.to_parquet(f"{H}/sessions.parquet"); print(sp.head()); print(len(sp), "session-underlyings;", sp.d.nunique(), "sessions")
print("gap>1 calendar day (weekend/holiday nights):", ((sp.e - sp.d).dt.days > 1).sum())
# fetch jobs: one request per session: D 15:00 ET .. E 09:50 ET, all 8 contracts
jobs = []
for d, g in sp.groupby("d"):
    e = g.e.iloc[0]
    st = pd.Timestamp(d.strftime("%Y-%m-%d") + " 15:00", tz="America/New_York").tz_convert("UTC")
    en = pd.Timestamp(e.strftime("%Y-%m-%d") + " 09:50", tz="America/New_York").tz_convert("UTC")
    syms = sorted(set(sum([list(x) for x in g[["occ_atm_c", "occ_atm_p", "occ_str_c", "occ_str_p"]].values], [])))
    jobs.append(f"{st.strftime('%Y-%m-%dT%H:%M:%SZ')},{en.strftime('%Y-%m-%dT%H:%M:%SZ')},{';'.join(syms)}")
open(f"{H}/jobs.txt", "w").write("\n".join(jobs) + "\n"); print(len(jobs), "jobs; first:", jobs[0])
