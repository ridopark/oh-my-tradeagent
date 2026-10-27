"""Daily series, stats, tail correlation, allocation. Reads roundtrips.csv + raw/events.csv; prints markdown."""
import sys
import cvxpy as cp
import numpy as np, pandas as pd
from scipy import stats as sst

OUT = sys.argv[1]
RNG = np.random.default_rng(7)
LIVE = ["prod_real", "prod-kipark", "prod-jinchul", "prod-soonwon"]
pd.set_option("display.width", 250)

ev = pd.read_csv(f"{OUT}/raw/events.csv", parse_dates=["date"])
rt = pd.read_csv(f"{OUT}/roundtrips.csv", parse_dates=["exit_date", "entry_date"])
ev = ev[ev.tenant_id != "dev"]  # pre-live test tenant (harness BTOs), excluded from all series
rt_c = rt[rt.status.str.startswith("CLOSED") & (rt.tenant_id != "dev")]
SHORT = {"copytrade-v1": "CT", "watchlist-trigger-v1": "WL", "gated_condor": "CONDOR"}


def series_defs():
    d = {}
    for (t, s), _ in ev.groupby(["tenant_id", "strategy_id"]):
        d[f"{SHORT[s]}:{t}"] = (ev.tenant_id == t) & (ev.strategy_id == s), (rt_c.tenant_id == t) & (rt_c.strategy_id == s)
    d["CT:pooled_live"] = (ev.strategy_id == "copytrade-v1") & ev.tenant_id.isin(LIVE), \
        (rt_c.strategy_id == "copytrade-v1") & rt_c.tenant_id.isin(LIVE)
    d["CT:pooled_all"] = ev.strategy_id == "copytrade-v1", rt_c.strategy_id == "copytrade-v1"
    return d


def daily(mask):
    g = ev[mask].groupby("date").agg(pnl=("pnl", "sum"), cost=("cost", "sum"), n_events=("pnl", "size"))
    g["ret"] = g.pnl / g.cost
    return g


def max_dd_dollars(p):
    c = p.cumsum()
    return (c - np.maximum.accumulate(np.r_[0, c.values])[1:]).min()


def stats_block(name, dly, trades):
    tr = trades.sort_values("exit_date")
    h = len(tr) // 2
    out = dict(series=name, first=dly.index.min().date(), last=dly.index.max().date(), n_trades=len(tr),
               n_days=len(dly), total_pnl=dly.pnl.sum(), mean_trade=tr.realized_pnl.mean(),
               median_trade=tr.realized_pnl.median(), mean_trade_pct=tr.pct_of_premium.mean(),
               median_trade_pct=tr.pct_of_premium.median(), win_rate=(tr.realized_pnl > 0).mean(),
               skew_trade_pct=sst.skew(tr.pct_of_premium) if len(tr) > 2 else np.nan,
               skew_daily=sst.skew(dly.pnl) if len(dly) > 2 else np.nan, worst_day=dly.pnl.min(),
               worst_day_date=dly.pnl.idxmin().date(), max_dd=max_dd_dollars(dly.pnl),
               pooled_ret_on_cost=dly.pnl.sum() / dly.cost.sum())
    for lab, part in [("H1", tr.iloc[:h]), ("H2", tr.iloc[h:])]:
        out[f"{lab}_n"] = len(part)
        out[f"{lab}_sum"] = part.realized_pnl.sum()
        out[f"{lab}_ret_on_cost"] = part.realized_pnl.sum() / part.realized_cost.sum() if len(part) else np.nan
        out[f"{lab}_win"] = (part.realized_pnl > 0).mean() if len(part) else np.nan
    return out


# ---------------- Tasks 1, 3, 4 ----------------
defs = series_defs()
rows, daily_long = [], []
for name, (em, rm) in defs.items():
    d = daily(em)
    rows.append(stats_block(name, d, rt_c[rm]))
    daily_long.append(d.reset_index().assign(series=name))
S = pd.DataFrame(rows).set_index("series")
DL = pd.concat(daily_long)[["date", "series", "pnl", "cost", "ret", "n_events"]]
DL.to_csv(f"{OUT}/daily.csv", index=False)

inv = rt[rt.tenant_id.notna()].groupby(["tenant_id", "strategy_id", "status"]).agg(
    n=("eid", "size"), first_entry=("entry_date", "min"), last_exit=("exit_date", "max"),
    realized=("realized_pnl", "sum")).reset_index()

print("## STATS\n")
fmt = S.copy()
for c in fmt.columns:
    if fmt[c].dtype.kind == "f":
        fmt[c] = fmt[c].round(3 if ("pct" in c or "win" in c or "skew" in c or "ret" in c) else 0)
print(fmt.T.to_markdown())
print("\n## INVENTORY\n")
print(inv.to_markdown(index=False))

# ---------------- Task 5: tail correlation ----------------
bdays = pd.bdate_range("2026-06-16", "2026-10-22")


def on_cal(name, col="pnl"):
    d = DL[DL.series == name].set_index("date")[col]
    return d.reindex(bdays).fillna(0.0)


def tail_block(ct_name):
    ct = DL[DL.series == ct_name].set_index("date").pnl
    thr = ct.quantile(0.20)
    worst = ct[ct <= thr].index
    lines = [f"\n### Tail: {ct_name} worst 20% of ITS trading days (n={len(worst)} days, P&L <= ${thr:,.0f})\n"]
    for other in ["WL:staging_paper", "CONDOR:staging_paper", "CT:staging_paper"]:
        if other == ct_name:
            continue
        o = DL[DL.series == other].set_index("date").pnl
        traded = o.reindex(worst).dropna()
        allwl = o.reindex(worst).fillna(0)
        lines.append(f"- {other}: traded on {len(traded)}/{len(worst)} of those days; P&L on them "
                     f"sum ${traded.sum():,.0f}, mean ${traded.mean() if len(traded) else float('nan'):,.0f}, "
                     f"neg {int((traded < 0).sum())}/{len(traded)}. Unconditional mean on its own trading days "
                     f"${o.mean():,.0f}. (zero-filled mean over the {len(worst)} tail days: ${allwl.mean():,.0f})")
    return "\n".join(lines)


def corr_block(a, b):
    da = DL[DL.series == a].set_index("date")
    db = DL[DL.series == b].set_index("date")
    both = da.join(db, how="inner", lsuffix="_a", rsuffix="_b")
    out = f"- {a} vs {b}: overlapping trading days n={len(both)}"
    if len(both) >= 3:
        out += (f"; Pearson($)={both.pnl_a.corr(both.pnl_b):.2f}, Spearman($)={both.pnl_a.corr(both.pnl_b, method='spearman'):.2f}, "
                f"Pearson(ret)={both.ret_a.corr(both.ret_b):.2f}")
    za, zb = on_cal(a), on_cal(b)
    act = (za != 0) | (zb != 0)
    out += f"; zero-filled business-day Pearson over {int(act.sum())} days either traded = {za[act].corr(zb[act]):.2f}"
    return out


print("\n## TAIL / CORRELATION\n")
for ct in ["CT:pooled_live", "CT:pooled_all", "CT:staging_paper"]:
    print(tail_block(ct))
print("\n### Plain correlations of daily realized P&L\n")
for a, b in [("CT:pooled_live", "WL:staging_paper"), ("CT:pooled_all", "WL:staging_paper"),
             ("CT:staging_paper", "WL:staging_paper"), ("CT:pooled_live", "CONDOR:staging_paper"),
             ("WL:staging_paper", "CONDOR:staging_paper"), ("CT:prod_real", "CT:prod-kipark"),
             ("CT:prod_real", "CT:staging_paper")]:
    print(corr_block(a, b))

# ---------------- Task 6: allocation ----------------


def kelly_f(r):
    """argmax_f mean(log(1+f r)), f in [0,1]; r >= -1 so f<=1 keeps wealth >= 0."""
    if (r >= 0).all():
        return 1.0
    grid = np.linspace(0, 0.999, 1000)
    g = np.log1p(np.outer(grid, r)).mean(axis=1)
    return grid[np.argmax(g)]


def kelly_boot(r, B=2000):
    fs = np.array([kelly_f(RNG.choice(r, len(r))) for _ in range(B)])
    return kelly_f(r), np.percentile(fs, [5, 50, 95])


def cvar(x, a=0.95):
    q = np.quantile(x, 1 - a)
    return -x[x <= q].mean()


def min_cvar(R, a=0.95):
    T, n = R.shape
    w, z, v = cp.Variable(n), cp.Variable(T), cp.Variable()
    loss = -R @ w
    prob = cp.Problem(cp.Minimize(v + cp.sum(z) / ((1 - a) * T)), [z >= 0, z >= loss - v, w >= 0, cp.sum(w) == 1])
    prob.solve(solver="CLARABEL")
    return np.clip(w.value, 0, None) / np.clip(w.value, 0, None).sum()


def equity_dd(r):
    eq = np.cumprod(1 + r)
    return (eq / np.maximum.accumulate(np.r_[1, eq])[1:] - 1).min()


def block_boot_dd(R, w_lev, horizon=252, block=5, B=5000):
    T = len(R)
    nb = int(np.ceil(horizon / block))
    starts = RNG.integers(0, T - block + 1, size=(B, nb))
    idx = (starts[:, :, None] + np.arange(block)).reshape(B, -1)[:, :horizon]
    pr = np.clip(R[idx] @ w_lev, -1, None)
    eq = np.cumprod(1 + pr, axis=1)
    dd = (eq / np.maximum.accumulate(np.concatenate([np.ones((B, 1)), eq], axis=1), axis=1)[:, 1:] - 1).min(axis=1)
    return (dd <= -0.30).mean(), (dd <= -0.50).mean(), np.median(dd)


def allocation(ct_name, wl_name="WL:staging_paper"):
    ct_ev, wl_ev = DL[DL.series == ct_name], DL[DL.series == wl_name]
    lo = max(ct_ev.date.min(), wl_ev.date.min())
    hi = min(ct_ev.date.max(), wl_ev.date.max())
    cal = pd.bdate_range(lo, hi)
    R = pd.DataFrame({"CT": on_cal(ct_name, "ret").reindex(cal).fillna(0),
                      "WL": on_cal(wl_name, "ret").reindex(cal).fillna(0)})
    mid = cal[len(cal) // 2]
    fit, test = R[R.index < mid], R[R.index >= mid]
    act_fit = int(((fit != 0).any(axis=1)).sum())
    act_test = int(((test != 0).any(axis=1)).sum())
    # Kelly from per-trade return-on-premium, trades exiting in the FIT window only
    _, rm_ct = defs[ct_name]
    _, rm_wl = defs[wl_name]
    tr_ct = rt_c[rm_ct & (rt_c.exit_date < mid) & (rt_c.exit_date >= lo)].pct_of_premium.values
    tr_wl = rt_c[rm_wl & (rt_c.exit_date < mid) & (rt_c.exit_date >= lo)].pct_of_premium.values
    k_ct, ci_ct = kelly_boot(tr_ct)
    k_wl, ci_wl = kelly_boot(tr_wl)
    w_cvar = min_cvar(fit.values)
    schemes = {"equal_weight": np.array([0.5, 0.5]), "copytrade_only": np.array([1.0, 0.0]),
               "min_CVaR95": w_cvar}
    lines = [f"\n### Allocation: {ct_name} + {wl_name}\n",
             f"Overlap calendar {lo.date()} -> {hi.date()} = {len(cal)} business days; split at {mid.date()}. "
             f"FIT {len(fit)} bdays ({act_fit} with any realized P&L), TEST {len(test)} bdays ({act_test} active). "
             f"Daily input = realized P&L / cost basis of lots realized that day; 0 on no-trade days.\n",
             f"- Kelly (per-trade return on premium, FIT trades only): CT n={len(tr_ct)} f*={k_ct:.3f} "
             f"(bootstrap 5/50/95%: {ci_ct[0]:.2f}/{ci_ct[1]:.2f}/{ci_ct[2]:.2f}); "
             f"WL n={len(tr_wl)} f*={k_wl:.3f} ({ci_wl[0]:.2f}/{ci_wl[1]:.2f}/{ci_wl[2]:.2f})",
             f"- min-CVaR95 weights fitted on FIT: CT={w_cvar[0]:.3f}, WL={w_cvar[1]:.3f}\n",
             "| scheme | L (equity fraction per day) | CT exp | WL exp | OOS mean/day | OOS CVaR95/day | OOS maxDD | OOS total | P(DD>=30%) 1y | P(DD>=50%) 1y | median 1y maxDD |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    rows = []
    for nm, w in schemes.items():
        for L in [0.05, 0.10, 0.25, 0.50]:
            rows.append((nm, L, w * L))
    for frac in [0.25, 0.5]:
        rows.append((f"kelly_{frac}", np.nan, np.array([frac * k_ct, frac * k_wl])))
    for nm, L, wl in rows:
        pr = np.clip(test.values @ wl, -1, None)
        p30, p50, mdd = block_boot_dd(R.values, wl)
        lines.append(f"| {nm} | {'' if np.isnan(L) else L} | {wl[0]:.3f} | {wl[1]:.3f} | {pr.mean():+.4f} | {cvar(pr):.4f} | "
                     f"{equity_dd(pr):.1%} | {np.prod(1 + pr) - 1:+.1%} | {p30:.1%} | {p50:.1%} | {mdd:.1%} |")
    fitstats = (f"\nFIT-window per-day stats (unlevered): CT mean {fit.CT.mean():+.4f}, WL mean {fit.WL.mean():+.4f}, "
                f"corr {fit.CT.corr(fit.WL):.2f}; TEST: CT mean {test.CT.mean():+.4f}, WL mean {test.WL.mean():+.4f}, "
                f"corr {test.CT.corr(test.WL):.2f}")
    return "\n".join(lines) + fitstats


print("\n## ALLOCATION\n")
for ct in ["CT:pooled_live", "CT:staging_paper"]:
    print(allocation(ct))
