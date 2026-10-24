# Build earnings_table.csv: Nasdaq date + timing from first Alpaca/Benzinga results headline -> effective session E.
import pandas as pd, numpy as np
H = ".."
WL = "../../.."
# NYSE sessions: weekdays minus 2026 holidays in range; checked against SPY sessions in rth_1m.parquet
spy = set(pd.read_parquet(f"{WL}/rth_1m.parquet", columns=["s", "d"]).query("s=='SPY'").d.astype(str))
sess = [x.strftime("%Y-%m-%d") for x in pd.bdate_range("2026-06-01", "2026-10-30")]
sess = [x for x in sess if x not in {"2026-06-19", "2026-07-03", "2026-09-07"}]
assert spy == {x for x in sess if min(spy) <= x <= max(spy)}, "calendar mismatch"
nq = pd.read_csv(f"{H}/nasdaq_earnings.csv", dtype=str)
n = pd.read_json(f"{H}/raw/news.jsonl.gz", lines=True, compression="gzip", dtype={"D": str})
n["et"] = pd.to_datetime(n.created_at, utc=True).dt.tz_convert("America/New_York")
pat = r"Q[1-4] (?:\d{4} )?(?:Adj\. |GAAP )?(?:EPS|Sales|Revenue) |Sees Q[1-4]|Preliminary Q[1-4]"
bad = r"Preview|Ahead|Expect|Likely|Can It|Will |Forecasters|Transcript|How To|Repeat"
m = n[n.headline.str.contains(pat) & ~n.headline.str.contains(bad)].sort_values("et")
rows = []
for r in nq.itertuples():
    g = m[(m.q == r.s) & (m.D == r.D)]
    rec = dict(s=r.s, nasdaq_date=r.D, nasdaq_time=r.nasdaq_time, fq=r.fq)
    if len(g):
        t = g.iloc[0].et
        rec.update(news_ts=t.strftime("%Y-%m-%d %H:%M"), news_headline=g.iloc[0].headline[:140], timing_source="alpaca_news(benzinga)")
        day, hm = t.strftime("%Y-%m-%d"), t.strftime("%H:%M")
        if hm >= "16:00": timing = "after_close"; E = next(x for x in sess if x > day)
        elif hm < "09:30": timing = "pre_open"; E = next(x for x in sess if x >= day)
        else: timing = "intraday"; E = next(x for x in sess if x > day)
        rec["news_vs_nasdaq"] = "same_day" if day == r.D else f"news_day={day}"
    elif r.nasdaq_time in ("time-after-hours", "time-pre-market"):
        timing = "after_close" if r.nasdaq_time == "time-after-hours" else "pre_open"
        E = next(x for x in sess if (x > r.D if timing == "after_close" else x >= r.D))
        rec.update(timing_source="nasdaq_scheduled")
    else:
        timing, E = "unknown", None
        rec.update(timing_source="none")
    rec.update(timing=timing, effective_session=E)
    rows.append(rec)
t = pd.DataFrame(rows)
t.to_csv(f"{H}/earnings_table.csv", index=False)
print(t[["s", "nasdaq_date", "timing", "effective_session", "timing_source", "news_vs_nasdaq"]].to_string())
