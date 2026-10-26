"""Robustness (not a prereg variant): does the bar-labelling convention at the 10:00 / 15:30 / 16:00 cutoffs change the
stock result? Alpaca bar t = bar open, so "last bar t<=10:00" closes at 10:01. Alternatives: 09:59 bar (closes 10:00),
15:29 bar, and the 15:59 bar instead of the 16:00 auction bar. Also DST check: count of sessions whose UTC offset is
-4 vs -5 (both must be present, cutoffs are taken in America/New_York)."""
import gzip, json, numpy as np, pandas as pd
WL = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl"
exec(open(f"{WL}/r13/h132/build_daily.py").read().split("for sym in")[0])  # reuse HALF_DAYS/load() without running the main loop
for sym in ["SPY", "QQQ"]:
    d = load(sym)
    print(sym, "UTC offsets present:", d.t.dt.strftime("%z").value_counts().to_dict())
    g = d.groupby("date")
    def last_le(hm): return g.apply(lambda x: x[x.hm <= hm].c.iloc[-1])
    D = pd.DataFrame({"c1000": last_le(1000), "c0959": last_le(959), "c1530": last_le(1530), "c1529": last_le(1529),
                      "c1600": last_le(1600), "c1559": last_le(1559), "t_last": g.hm.max()})
    D.index = pd.to_datetime(D.index); D["prev"] = D.c1600.shift(1); D["prev59"] = D.c1559.shift(1)
    D = D[(D.t_last >= 1530) & D.prev.notna()]
    cfgs = {"prereg(10:00bar,15:30bar,16:00bar)": (D.c1000 / D.prev - 1, D.c1600 / D.c1530 - 1),
            "09:59bar,15:29bar,16:00bar": (D.c0959 / D.prev - 1, D.c1600 / D.c1529 - 1),
            "10:00bar,15:30bar,15:59bar(prev=15:59)": (D.c1000 / D.prev59 - 1, D.c1559 / D.c1530 - 1),
            "09:59bar,15:29bar,15:59bar(prev=15:59)": (D.c0959 / D.prev59 - 1, D.c1559 / D.c1529 - 1)}
    for name, (rf, rl) in cfgs.items():
        pnl = np.sign(rf) * rl - 1e-4
        out = []
        for h, (a, b) in {"FIT": ("2016", "2021"), "TEST": ("2022", "2026")}.items():
            p = pnl.loc[a:b]; p = p[rf.loc[a:b] != 0]; pb = p[rf.loc[a:b].abs() > 0.003]
            x, y = rf.loc[a:b].values, rl.loc[a:b].values; sl = np.polyfit(x, y, 1)[0]
            out.append(f"{h}: slope {sl:+.4f} all {1e4*p.mean():+.2f}bps (n={len(p)}) big {1e4*pb.mean():+.2f}bps (n={len(pb)})")
        print(f"  {name:42s} " + " | ".join(out))
