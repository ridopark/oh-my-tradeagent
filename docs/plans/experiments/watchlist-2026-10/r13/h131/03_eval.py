"""H13.1 evaluation: 8 variants x FIT/TEST, night-cluster bootstrap CIs, studentized max-|t| on TEST, tail stats.
usage: python3 03_eval.py  (reads nights.parquet; writes results.csv, tables.md)"""
import numpy as np, pandas as pd
WL = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl"
H = WL + "/r13/h131"; B = 10000; rng = np.random.default_rng(13)
FIT_END, TEST_START = pd.Timestamp("2025-06-30"), pd.Timestamp("2025-07-01")
fomc = {pd.Timestamp(l.strip()) for l in open("/home/ridopark/src/oh-my-tradeagent/scripts/data/fomc-dates.txt") if l.strip() and not l.startswith("#")}
cpi = {pd.Timestamp(l.strip()) for l in open(f"{H}/cpi_dates.txt") if l.strip() and not l.startswith("#")}
n0 = pd.read_parquet(f"{H}/nights.parquet")
n0["event"] = n0.e.isin(fomc | cpi); n0["half"] = np.where(n0.d <= FIT_END, "FIT", "TEST")
usable = n0[n0.has_entry & n0.has_exit & n0.iv_c.notna() & n0.iv_p.notna()].copy()
lbset = n0[n0.has_entry & n0.iv_c.notna() & n0.iv_p.notna()].copy()   # includes missing-exit nights valued at intrinsic LB

def pnl(df, k=1.0, lb=False):
    Pc_x, Pp_x = (df.Pc_x_lb, df.Pp_x_lb) if lb else (df.Pc_x, df.Pp_x)
    prem = 100 * ((df.Pc_e - k * df.hsc_e) + (df.Pp_e - k * df.hsp_e))
    cost = 100 * ((Pc_x + k * df.hsc_x) + (Pp_x + k * df.hsp_x))
    stock_g = df.shares * (df.S_exit - df.S_entry); stock = stock_g - 1e-4 * df.shares.abs() * (df.S_entry + df.S_exit)
    o = pd.DataFrame(dict(prem=prem, net=prem - cost + stock, opt_net=prem - cost, stock=stock))
    o["ret"] = o.net / o.prem
    gp, gc = 100 * (df.Pc_e + df.Pp_e), 100 * (Pc_x + Pp_x)
    o["long_unhedged"] = gc / gp - 1                      # Muravyev-Ni style: raw long straddle close->open return
    o["long_dh_gross"] = (gc - gp - stock_g) / gp          # long option, short the hedge shares, no costs
    o["short_gross"] = -o.long_dh_gross
    return o

def boot_ci(x):
    x = np.asarray(x); idx = rng.integers(0, len(x), (B, len(x)))
    m = x[idx].mean(1); return np.percentile(m, 2.5), np.percentile(m, 97.5)

def maxdd(net, prem):
    c = np.cumsum(net) / np.max(prem); peak = np.maximum.accumulate(np.concatenate([[0], c])); return float(np.max(peak[1:] - c))

variants = [(s, st, ev) for st in ("straddle", "strangle") for ev in ("all", "exEvent") for s in ("SPY", "QQQ")]
def sel(df, s, st, ev):
    m = (df.s == s) & (df.struct == st)
    if ev == "exEvent": m &= ~df.event
    return df[m]

rows = []; test_series = {}
for s, st, ev in variants:
    for half in ("FIT", "TEST"):
        d = sel(usable, s, st, ev); d = d[d.half == half]; p = pnl(d)
        p2 = pnl(d, k=2.0); dl = sel(lbset, s, st, ev); dl = dl[dl.half == half]; pl = pnl(dl, lb=True)
        r = p.ret.values; lo, hi = boot_ci(r); lo2, hi2 = boot_ci(p2.ret.values); lol, hil = boot_ci(pl.ret.values)
        wk = p.ret[d.weekend.values]; wd = p.ret[~d.weekend.values]
        rows.append(dict(s=s, struct=st, ev=ev, half=half, n=len(r), mean=r.mean(), lo=lo, hi=hi, t=r.mean() / (r.std(ddof=1) / np.sqrt(len(r))),
                         win=(r > 0).mean(), median=np.median(r), worst=r.min(), p5=np.percentile(r, 5), maxdd=maxdd(p.net.values, p.prem.values),
                         prem_med=p.prem.median(), mean_2x=p2.ret.mean(), lo_2x=lo2, hi_2x=hi2, n_lb=len(pl), mean_lb=pl.ret.mean(), lo_lb=lol, hi_lb=hil,
                         short_gross=p.short_gross.mean(), long_unhedged=p.long_unhedged.mean(), long_dh_gross=p.long_dh_gross.mean(),
                         n_wkend=len(wk), mean_wkend=wk.mean(), n_wkday=len(wd), mean_wkday=wd.mean(), worst_date=str(d.d.values[r.argmin()])[:10]))
        if half == "TEST": test_series[(s, st, ev)] = pd.Series(r, index=d.d.values)
res = pd.DataFrame(rows)

# ---- studentized max-|t| across the 8 variants on TEST, joint resampling of TEST session dates
dates = sorted(set().union(*[set(v.index) for v in test_series.values()]))
V = np.full((len(dates), len(variants)), np.nan); di = {d: i for i, d in enumerate(dates)}
for j, k in enumerate(variants):
    for d, r in test_series[k].items(): V[di[d], j] = r
obs_mean = np.nanmean(V, 0); obs_n = np.sum(~np.isnan(V), 0); obs_sd = np.nanstd(V, 0, ddof=1); obs_t = obs_mean / (obs_sd / np.sqrt(obs_n))
maxabs = np.empty(B); maxpos = np.empty(B)
for c0 in range(0, B, 1000):
    idx = rng.integers(0, len(dates), (1000, len(dates))); Vb = V[idx]                      # (b, dates, variants)
    nb = np.sum(~np.isnan(Vb), 1); mb = np.nanmean(Vb, 1); sb = np.nanstd(Vb, 1, ddof=1)
    tb = (mb - obs_mean) / (sb / np.sqrt(nb)); maxabs[c0:c0 + 1000] = np.max(np.abs(tb), 1); maxpos[c0:c0 + 1000] = np.max(tb, 1)
p_two = [(maxabs >= abs(t)).mean() for t in obs_t]; p_one = [(maxpos >= t).mean() for t in obs_t]
for j, (s, st, ev) in enumerate(variants):
    m = (res.s == s) & (res.struct == st) & (res.ev == ev) & (res.half == "TEST"); res.loc[m, "p_maxt_2s"] = p_two[j]; res.loc[m, "p_maxt_1s"] = p_one[j]
fit = res[res.half == "FIT"].set_index(["s", "struct", "ev"]); tst = res[res.half == "TEST"].set_index(["s", "struct", "ev"])
res["PASS"] = ""
for k in fit.index:
    f, t = fit.loc[k], tst.loc[k]
    ok = (f["mean"] > 0) and (f.lo > 0) and (t["mean"] > 0) and (t.lo > 0) and (t.p_maxt_2s < 0.05)
    res.loc[(res.s == k[0]) & (res.struct == k[1]) & (res.ev == k[2]), "PASS"] = "PASS" if ok else "fail"
res.to_csv(f"{H}/results.csv", index=False)

def pct(x): return f"{100 * x:+.2f}%"
L = []
L.append("| variant | half | n | mean ret/prem | 95% CI (nights) | t | max-t p (2s/1s) | win | median | P5 | worst (date) | maxDD/maxprem | med prem $ | verdict |")
L.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for r in res.itertuples():
    mt = f"{r.p_maxt_2s:.3f}/{r.p_maxt_1s:.3f}" if r.half == "TEST" else "-"
    L.append(f"| {r.s} {r.struct} {r.ev} | {r.half} | {r.n} | {pct(r.mean)} | [{pct(r.lo)}, {pct(r.hi)}] | {r.t:+.2f} | {mt} | {100*r.win:.0f}% | {pct(r.median)} | {pct(r.p5)} | {pct(r.worst)} ({r.worst_date}) | {r.maxdd:.2f} | {r.prem_med:.0f} | {r.PASS if r.half=='TEST' else ''} |")
L.append("\n| variant | half | 2x spread mean [CI] | missing-exit@intrinsic-LB n / mean [CI] | short gross (no cost) | long unhedged (M-N raw) | long delta-hedged gross (M-N) | weekend n / mean | weekday n / mean |")
L.append("|---|---|---|---|---|---|---|---|---|")
for r in res.itertuples():
    L.append(f"| {r.s} {r.struct} {r.ev} | {r.half} | {pct(r.mean_2x)} [{pct(r.lo_2x)}, {pct(r.hi_2x)}] | {r.n_lb} / {pct(r.mean_lb)} [{pct(r.lo_lb)}, {pct(r.hi_lb)}] | {pct(r.short_gross)} | {pct(r.long_unhedged)} | {pct(r.long_dh_gross)} | {r.n_wkend} / {pct(r.mean_wkend)} | {r.n_wkday} / {pct(r.mean_wkday)} |")
open(f"{H}/tables.md", "w").write("\n".join(L) + "\n"); print("\n".join(L))
print("\nTEST dates in max-t:", len(dates), "| events excluded (TEST nights, any variant):", int(usable[(usable.half == "TEST") & usable.event].d.nunique()))
