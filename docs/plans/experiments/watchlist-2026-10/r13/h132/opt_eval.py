"""H13.2 step 2c: SPY 0DTE expression. At the 15:30 bar buy the ATM 0DTE call (r_first>0) / put (r_first<0), sell at the
15:55 bar. Price = vw else c. Entry x (1+hs), exit x (1-hs), hs = half_spread.csv row (kb=0.25%, hm=14:00) = 2.04%
(closest bucket to ATM at 15:30; stated choice). Missing 15:55 bar -> intrinsic at the 16:00 spot x (1-hs), flagged.
Missing 15:30 bar -> no trade (flagged). Bars for an OCC on days other than its expiry are discarded (TRAP).
Halves: FIT 2024-02..2025-06, TEST 2025-07..2026-10. Variants: all days, |r_first|>0.3%."""
import gzip, json, glob, numpy as np, pandas as pd
H = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r13/h132"
hs = pd.read_csv(f"{H}/../../half_spread.csv"); HS = float(hs[(hs.kb == "0.25%") & (hs.hm == "14:00")].half_spread.iloc[0])
rng = np.random.default_rng(132)
rows = []
for f in sorted(glob.glob(f"{H}/bars/opt_*.jsonl.gz")):
    with gzip.open(f, "rt") as fh:
        for line in fh:
            b = json.loads(line); rows.append((b["s"], b["t"], b.get("vw"), b["c"], b["v"]))
bars = pd.DataFrame(rows, columns=["occ", "t", "vw", "c", "v"])
bars["t"] = pd.to_datetime(bars.t, utc=True).dt.tz_convert("America/New_York")
bars["date"] = bars.t.dt.strftime("%Y-%m-%d"); bars["hm"] = bars.t.dt.hour * 100 + bars.t.dt.minute
bars["px"] = bars.vw.where(bars.vw.notna() & (bars.vw > 0), bars.c)
occ = pd.read_csv(f"{H}/occ_list.csv", index_col=0); occ.index = occ.index.astype(str)
daily = pd.read_parquet(f"{H}/daily_SPY.parquet"); daily.index = daily.index.astype(str)
print(f"bars total {len(bars)}, contracts with any bar {bars.occ.nunique()}/{len(occ)}; HS={HS:.4f}")
exp = occ.occ.reset_index().rename(columns={"index": "tdate", "date": "tdate"}); m = bars.merge(exp, on="occ")
print("TRAP: bars on non-expiry dates discarded:", (m.date != m.tdate).sum(), "kept:", (m.date == m.tdate).sum())
bars = m[m.date == m.tdate]
recs = []
for d, r in occ.iterrows():
    b = bars[bars.occ == r.occ]
    e = b[b.hm == 1530]; x = b[b.hm == 1555]
    if len(e) == 0:
        recs.append(dict(date=d, occ=r.occ, r_first=r.r_first, status="no_1530_bar")); continue
    entry = e.px.iloc[0]; S16 = daily.loc[d, "c1600"]
    if len(x) == 0:
        exit_ = max(0.0, S16 - r.strike) if r.side == "C" else max(0.0, r.strike - S16); status = "intrinsic_1600"
    else:
        exit_ = x.px.iloc[0]; status = "ok"
    recs.append(dict(date=d, occ=r.occ, r_first=r.r_first, side=r.side, strike=r.strike, spot1530=r.c1530, entry=entry, exit=exit_,
                     spot_move=np.sign(r.r_first) * (S16 / r.c1530 - 1), status=status,
                     ret_net=exit_ * (1 - HS) / (entry * (1 + HS)) - 1, ret_gross=exit_ / entry - 1,
                     ret_1c=(exit_ - 0.01) / (entry + 0.01) - 1))
T = pd.DataFrame(recs).set_index("date"); T.to_csv(f"{H}/opt_trades.csv")
print("status counts:", T.status.value_counts().to_dict())
print("entry premium $: median %.2f p10 %.2f p90 %.2f ; moneyness |S-K| median %.2f" % (T.entry.median(), T.entry.quantile(.1), T.entry.quantile(.9), (T.spot1530 - T.strike).abs().median()))
tr = T[T.status != "no_1530_bar"]

def stats(p, label, half):
    if len(p) < 5: return None
    idx = rng.integers(0, len(p), (5000, len(p))); lo, hi = np.percentile(p.values[idx].mean(1), [2.5, 97.5])
    q = p.quantile([.01, .99]); trim = p[(p > q.iloc[0]) & (p < q.iloc[1])]
    return dict(half=half, variant=label, n=len(p), mean=p.mean(), ci_lo=lo, ci_hi=hi, t=p.mean() / p.std() * np.sqrt(len(p)), win=(p > 0).mean(),
                median=p.median(), p5=p.quantile(.05), worst=p.min(), worst_day=p.idxmin(), best=p.max(), mean_trim1pct=trim.mean(), n_trim=len(trim))
out = []
for half, (a, b) in {"FIT_2024_02_2025_06": ("2024-02-01", "2025-06-30"), "TEST_2025_07_2026_10": ("2025-07-01", "2026-10-31")}.items():
    x = tr.loc[a:b]; xall = T.loc[a:b]
    for lab, thr in [("all", 0.0), ("abs_gt_0.3pct", 0.003)]:
        y = x[x.r_first.abs() > thr]
        out.append(stats(y.ret_net, lab, half))
        out[-1].update(n_intrinsic=int((y.status == "intrinsic_1600").sum()), n_no1530=int((xall[xall.r_first.abs() > thr].status == "no_1530_bar").sum()))
        out.append(stats(y.ret_gross, lab + "_GROSS(hs=0)", half)); out.append(stats(y.ret_1c, lab + "_SENS(hs=$0.01)", half))
        y.ret_net.rename(f"SPY0DTE|{lab}").to_frame().to_parquet(f"{H}/opt_ret_{lab}_{half}.parquet")
R = pd.DataFrame([o for o in out if o]); R.to_csv(f"{H}/opt_results.csv", index=False)
pd.set_option("display.width", 250); print(R.round(4).to_string(index=False))
# concentration: top-5 days' share and by-side
for half, (a, b) in {"FIT": ("2024-02-01", "2025-06-30"), "TEST": ("2025-07-01", "2026-10-31")}.items():
    x = tr.loc[a:b]; print(half, "by side:", x.groupby("side").ret_net.agg(["count", "mean"]).round(4).to_dict(),
                           "| corr(ret_net, signed spot move 15:30->16:00) %.2f" % x.ret_net.corr(x.spot_move))
