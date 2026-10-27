"""H13.1: build the per-night table (one row per session x underlying x structure) from the fetched 1-DTE bars.
usage: python3 02_build_nights.py bars/<file>.jsonl.gz  -> nights.parquet + diagnostics printed."""
import gzip, json, sys, numpy as np, pandas as pd
from scipy.stats import norm
from scipy.optimize import brentq
WL = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl"
H = WL + "/r13/h131"
R, Q = 0.04, 0.0
sp = pd.read_parquet(f"{H}/sessions.parquet")

rows = []
with gzip.open(sys.argv[1], "rt") as f:
    for line in f:
        b = json.loads(line); rows.append((b["s"], b["t"], b["o"], b["h"], b["l"], b["c"], b["v"], b.get("n", np.nan), b.get("vw", np.nan)))
bars = pd.DataFrame(rows, columns=["s", "t", "o", "h", "l", "c", "v", "n", "vw"])
bars["t"] = pd.to_datetime(bars.t, utc=True).dt.tz_convert("America/New_York")
bars["d"] = bars.t.dt.normalize().dt.tz_localize(None); bars["hm"] = bars.t.dt.strftime("%H:%M")
bars = bars.sort_values(["s", "t"]).reset_index(drop=True)
bars["exp"] = pd.to_datetime("20" + bars.s.str[3:9], format="%Y%m%d")
print(f"bars: {len(bars)} rows, {bars.s.nunique()} contracts, dates {bars.d.min().date()}..{bars.d.max().date()}")
# ---- date-mixing checks: every bar must be on its session's D or E, and E must equal the contract expiry
occ_rows = []
for col, leg in [("occ_atm_c", "atm_c"), ("occ_atm_p", "atm_p"), ("occ_str_c", "str_c"), ("occ_str_p", "str_p")]:
    occ_rows.append(sp[["s", "d", "e", col]].rename(columns={col: "occ"}).assign(leg=leg))
occ = pd.concat(occ_rows); occ["d"] = pd.to_datetime(occ.d); occ["e"] = pd.to_datetime(occ.e)
assert (occ.e == pd.to_datetime("20" + occ.occ.str[3:9], format="%Y%m%d")).all(), "OCC expiry != next session"
m = bars.merge(occ[["occ", "d", "e"]].drop_duplicates("occ"), left_on="s", right_on="occ", how="left")
stray = m[(m.d_x != m.d_y) & (m.d_x != m.e)]
print(f"date-mixing check: {len(stray)} bars outside their night's {{D, E}} (expected 0); contracts in bars but not in OCC list: {m.occ.isna().sum()}")
if len(stray): print("  stray bar dates:", sorted(set(stray.d_x.dt.date)), "-> dropped (2024-06-01 = NYSE Saturday T+1 test session, test prints)")
bars = bars[~bars.index.isin(stray.index)]

g = {k: v for k, v in bars.groupby(["s", "d"])}

def pick_entry(occ_, d):
    x = g.get((occ_, d))
    if x is None: return None
    x = x[(x.hm >= "15:50") & (x.hm <= "15:55")]
    if x.empty: return None
    b = x.iloc[-1]; px = b.vw if (pd.notna(b.vw) and b.vw > 0) else b.c
    return dict(px=px, c=b.c, vw=b.vw, hm=b.hm, n=b.n, v=b.v, exact=(b.hm == "15:55"))

def pick_exit(occ_, e):
    x = g.get((occ_, e))
    if x is None: return None
    x = x[(x.hm >= "09:35") & (x.hm <= "09:45")]
    if x.empty: return None
    b = x.iloc[0]; px = b.vw if (pd.notna(b.vw) and b.vw > 0) else b.c
    return dict(px=px, c=b.c, vw=b.vw, hm=b.hm, n=b.n, v=b.v, exact=(b.hm == "09:35"))

def roll_half(occ_, d, e):
    """Roll half-spread: sqrt(-lag1 autocov of 1m close changes), within-date only (D 15:00-16:14 and E 09:30-09:49)."""
    pairs = []
    for dd in (d, e):
        x = g.get((occ_, dd))
        if x is None or len(x) < 4: continue
        dp = np.diff(x.c.values); pairs.append(np.column_stack([dp[1:], dp[:-1]]))
    if not pairs: return np.nan, 0
    p = np.vstack(pairs)
    if len(p) < 10: return np.nan, len(p)
    cov = np.mean(p[:, 0] * p[:, 1]) - np.mean(p[:, 0]) * np.mean(p[:, 1])
    return (np.sqrt(-cov) if cov < 0 else 0.0), len(p)

def bs(S, K, T, sig, cp):
    d1 = (np.log(S / K) + (R - Q + 0.5 * sig * sig) * T) / (sig * np.sqrt(T)); d2 = d1 - sig * np.sqrt(T)
    if cp == "C": return S * np.exp(-Q * T) * norm.cdf(d1) - K * np.exp(-R * T) * norm.cdf(d2), np.exp(-Q * T) * norm.cdf(d1)
    return K * np.exp(-R * T) * norm.cdf(-d2) - S * np.exp(-Q * T) * norm.cdf(-d1), -np.exp(-Q * T) * norm.cdf(-d1)

def iv_solve(fn_price, target):
    try: return brentq(lambda s: fn_price(s) - target, 1e-3, 5.0, xtol=1e-6)
    except ValueError: return np.nan

def hs_dollar(roll, px): return max(roll if pd.notna(roll) else 0.0, 0.01 * px, 0.005)

out = []
for r in sp.itertuples():
    d, e = pd.Timestamp(r.d), pd.Timestamp(r.e)
    T = (pd.Timestamp(e.strftime("%Y-%m-%d") + " 16:00", tz="America/New_York") - pd.Timestamp(d.strftime("%Y-%m-%d") + " 15:55", tz="America/New_York")).total_seconds() / (365 * 86400)
    for struct, (oc, op, Kc, Kp) in {"straddle": (r.occ_atm_c, r.occ_atm_p, r.K_atm, r.K_atm), "strangle": (r.occ_str_c, r.occ_str_p, r.K_c, r.K_p)}.items():
        rec = dict(s=r.s, d=d, e=e, struct=struct, Kc=Kc, Kp=Kp, S_entry=r.S_entry, S_exit=r.S_exit, S_close=r.S_close, S_open=r.S_open, T=T,
                   weekend=(e - d).days > 1)
        ec, ep, xc, xp = pick_entry(oc, d), pick_entry(op, d), pick_exit(oc, e), pick_exit(op, e)
        rec.update(has_entry=(ec is not None and ep is not None), has_exit=(xc is not None and xp is not None),
                   bars_on_D=((oc, d) in g) or ((op, d) in g), bars_on_E=((oc, e) in g) or ((op, e) in g))
        if ec is None or ep is None: out.append(rec); continue
        rc, nrc = roll_half(oc, d, e); rp, nrp = roll_half(op, d, e)
        Pc, Pp = ec["px"], ep["px"]
        rec.update(Pc_e=Pc, Pp_e=Pp, entry_exact=ec["exact"] and ep["exact"], entry_hm_c=ec["hm"], entry_hm_p=ep["hm"],
                   vw_dev_c=abs(ec["vw"] / ec["c"] - 1) if pd.notna(ec["vw"]) and ec["c"] > 0 else np.nan,
                   vw_dev_p=abs(ep["vw"] / ep["c"] - 1) if pd.notna(ep["vw"]) and ep["c"] > 0 else np.nan,
                   n_c=ec["n"], n_p=ep["n"], roll_c=rc, roll_p=rp, roll_pairs=min(nrc, nrp),
                   hsc_e=hs_dollar(rc, Pc), hsp_e=hs_dollar(rp, Pp))
        S = r.S_entry
        if struct == "straddle":
            iv = iv_solve(lambda s: bs(S, Kc, T, s, "C")[0] + bs(S, Kp, T, s, "P")[0], Pc + Pp); ivc = ivp = iv
        else:
            ivc = iv_solve(lambda s: bs(S, Kc, T, s, "C")[0], Pc); ivp = iv_solve(lambda s: bs(S, Kp, T, s, "P")[0], Pp)
        if pd.isna(ivc) or pd.isna(ivp): rec.update(iv_c=ivc, iv_p=ivp); out.append(rec); continue
        dc, dp_ = bs(S, Kc, T, ivc, "C")[1], bs(S, Kp, T, ivp, "P")[1]
        shares = (dc + dp_) * 100.0
        rec.update(iv_c=ivc, iv_p=ivp, delta_c=dc, delta_p=dp_, shares=shares)
        # exit legs (None -> intrinsic lower bound stored separately; primary analysis drops the night)
        intr_c, intr_p = max(r.S_exit - Kc, 0.0), max(Kp - r.S_exit, 0.0)
        rec.update(Pc_x=xc["px"] if xc else np.nan, Pp_x=xp["px"] if xp else np.nan, exit_exact=bool(xc and xp and xc["exact"] and xp["exact"]),
                   exit_hm_c=xc["hm"] if xc else None, exit_hm_p=xp["hm"] if xp else None, intr_c=intr_c, intr_p=intr_p,
                   Pc_x_lb=xc["px"] if xc else intr_c, Pp_x_lb=xp["px"] if xp else intr_p,
                   hsc_x=hs_dollar(rc, xc["px"]) if xc else hs_dollar(rc, intr_c), hsp_x=hs_dollar(rp, xp["px"]) if xp else hs_dollar(rp, intr_p))
        out.append(rec)
nights = pd.DataFrame(out)
nights.to_parquet(f"{H}/nights.parquet")
print("nights rows:", len(nights), "| has_entry:", nights.has_entry.sum(), "| has_entry&exit:", (nights.has_entry & nights.has_exit).sum())
print("no bars at all on D:", (~nights.bars_on_D).sum(), "| no bars at all on E:", (~nights.bars_on_E).sum())
ok = nights[nights.has_entry]
print("entry not the exact 15:55 bar:", (~ok.entry_exact.astype(bool)).sum(), "| exit not the exact 09:35 bar (of those with exit):", (~ok[ok.has_exit].exit_exact.astype(bool)).sum())
print("vw/c deviation at entry (abs, %%): median %.3f p90 %.3f p99 %.3f max %.3f" % tuple(np.nanpercentile(pd.concat([ok.vw_dev_c, ok.vw_dev_p]) * 100, [50, 90, 99, 100])))
print("entry bar trade count n: p5 %.0f median %.0f" % tuple(np.nanpercentile(pd.concat([ok.n_c, ok.n_p]), [5, 50])))
print("IV solve failures:", ok.iv_c.isna().sum() + ok.iv_p.isna().sum())
print("Roll half-spread as %% of entry price: median %.2f p90 %.2f; share at floor (1%%/tick): %.1f%%" % (
    np.nanmedian(pd.concat([ok.roll_c / ok.Pc_e, ok.roll_p / ok.Pp_e])) * 100, np.nanpercentile(pd.concat([ok.roll_c / ok.Pc_e, ok.roll_p / ok.Pp_e]).dropna(), 90) * 100,
    100 * np.mean(pd.concat([ok.hsc_e <= np.maximum(0.01 * ok.Pc_e, 0.005) + 1e-12, ok.hsp_e <= np.maximum(0.01 * ok.Pp_e, 0.005) + 1e-12]))))
print(nights.groupby(["s", "struct"]).agg(n=("d", "size"), entry=("has_entry", "sum"), both=("has_exit", "sum"), iv=("iv_c", "median"), prem=("Pc_e", "median")))
