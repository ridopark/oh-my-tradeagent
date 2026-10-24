"""H13.2 step 0: daily series for SPY/QQQ from 1m SIP bars (decade jsonl.gz, UTC, incl. extended hours)
plus rth_1m.parquet (2026-06-15.., America/New_York, RTH only, no 16:00 bar).
Cutoffs follow PREREG13 / task text literally: close(10:00) = close of last bar with t<=10:00 ET (Alpaca bar t is
the bar OPEN, so this bar closes at ~10:01); close(15:30) = last bar t<=15:30; close(16:00) = last bar t<=16:00
(decade: the 16:00 bar, which holds the closing-auction print; rth_1m: the 15:59 bar). prev close = prior session's
close(16:00). Sessions without a bar at t>=15:30 (half days) are dropped (but still supply prev close).
AMENDMENT (logged): the decade feed carries extended-hours bars, so a 13:00-close session still has bars at 15:30;
half days are therefore identified from the NYSE early-close calendar (21 dates 2016-2025, each verified to have
afternoon/morning volume ratio < 0.4 vs > 0.5 on normal days) and the 13:00 bar is treated as their close.
"""
HALF_DAYS = {"2016-11-25","2017-07-03","2017-11-24","2018-07-03","2018-11-23","2018-12-24","2019-07-03","2019-11-29",
             "2019-12-24","2020-11-27","2020-12-24","2021-11-26","2022-11-25","2023-07-03","2023-11-24","2024-07-03",
             "2024-11-29","2024-12-24","2025-07-03","2025-11-28","2025-12-24"}
import sys, gzip, json
import pandas as pd, numpy as np

WL = "/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl"
OUT = f"{WL}/r13/h132"

def load(sym):
    rows = []
    with gzip.open(f"{WL}/decade/{sym}.jsonl.gz", "rt") as f:
        for line in f:
            b = json.loads(line)
            rows.append((b["t"], b["o"], b["h"], b["l"], b["c"], b["v"]))
    d = pd.DataFrame(rows, columns=["t", "o", "h", "l", "c", "v"])
    d["t"] = pd.to_datetime(d["t"], utc=True).dt.tz_convert("America/New_York")
    r = pd.read_parquet(f"{WL}/rth_1m.parquet")
    r = r[r.s == sym][["t", "o", "h", "l", "c", "v"]]
    r["t"] = r["t"].dt.tz_convert("America/New_York")
    d = pd.concat([d[d.t < r.t.min()], r], ignore_index=True).sort_values("t")
    hm = d.t.dt.hour * 100 + d.t.dt.minute
    d = d[(hm >= 930) & (hm <= 1600)].copy()          # RTH incl. the 16:00 closing-print bar
    d["hm"] = hm[d.index]
    d["date"] = d.t.dt.date
    half = d.date.astype(str).isin(HALF_DAYS)
    d = d[~half | (d.hm <= 1300)]                      # half day: keep only bars up to the 13:00 closing bar
    return d

def daily(d):
    g = d.groupby("date")
    out = pd.DataFrame({
        "c1000": g.apply(lambda x: x[x.hm <= 1000].c.iloc[-1] if (x.hm <= 1000).any() else np.nan),
        "c1530": g.apply(lambda x: x[x.hm <= 1530].c.iloc[-1] if (x.hm <= 1530).any() else np.nan),
        "c1600": g.apply(lambda x: x.c.iloc[-1]),
        "t_last": g.apply(lambda x: x.hm.iloc[-1]),
        "t_first": g.apply(lambda x: x.hm.iloc[0]),
        "c1559": g.apply(lambda x: x[x.hm == 1559].c.iloc[-1] if (x.hm == 1559).any() else np.nan),
        "v_first": g.apply(lambda x: x[x.hm <= 1000].v.sum()),
        "v_day": g.v.sum(),
        "nbars": g.size(),
    })
    out["prev_close"] = out.c1600.shift(1)
    out["prev_date"] = pd.Series(out.index, index=out.index).shift(1)
    out["gap_days"] = (pd.to_datetime(out.index) - pd.to_datetime(out.prev_date)).dt.days
    return out

for sym in ["SPY", "QQQ"]:
    d = load(sym)
    dd = daily(d)
    full = dd[(dd.t_last >= 1530) & dd.c1000.notna() & dd.prev_close.notna()].copy()
    full["r_first"] = full.c1000 / full.prev_close - 1
    full["r_last"] = full.c1600 / full.c1530 - 1
    full["r_mid"] = full.c1530 / full.c1000 - 1
    # sanity
    print(sym, "sessions", len(dd), "kept", len(full), full.index.min(), full.index.max())
    print(" dropped (no 15:30+ bar / half days):", (dd.t_last < 1530).sum(), list(dd.index[dd.t_last < 1530])[:12])
    print(" t_last distribution:", dd.t_last.value_counts().head(4).to_dict())
    print(" t_first distribution:", dd.t_first.value_counts().head(3).to_dict())
    print(" nbars<380:", (dd.nbars < 380).sum())
    oj = (full.c1600 / full.c1559 - 1).abs()
    print(" |16:00 bar vs 15:59 bar| close: median %.2e p99 %.2e max %.2e (n=%d)" % (oj.median(), oj.quantile(.99), oj.max(), oj.notna().sum()))
    ov = (full.prev_close / full.c1600.shift(1) - 1)
    jump = (full.c1000 / full.prev_close - 1).abs()
    print(" max |r_first| %.4f on %s; |overnight gap|>8%%: %d" % (jump.max(), jump.idxmax(), ((full.prev_close/ full.c1600.shift(1)).sub(1).abs()>0.08).sum()))
    print(" gap_days>4:", full[full.gap_days > 4][["gap_days"]].T.to_dict())
    full.to_parquet(f"{OUT}/daily_{sym}.parquet")
