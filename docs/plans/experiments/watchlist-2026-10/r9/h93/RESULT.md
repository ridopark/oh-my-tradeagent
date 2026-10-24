# H9.3 CATALYST DAY: RESULT = FAIL (the test can't be run in TEST: 0 AGREE legs; FIT has 2)

Pre-registration: `wl/PREREG9.md` (H9.3). I did not change the hypothesis, metric, split or pass rule. I logged the interpretation choices in `r9/h93/DECISIONS.txt` (timestamped 2026-10-23T04:47Z) before reading any r60 or rclose value. Amendments are listed at the end.

**Verdict.** To pass, AGREE-SILENT r60 must be > 0 with a day-clustered 95% CI excluding 0 in both FIT and TEST.
- **TEST:** there are zero real AGREE legs, so the metric is undefined and the rule cannot be met.
- **FIT:** the point estimate is +274 bps, but it rests on **2 legs on 2 days** (TSLA 07-23 BELOW, MSFT 07-30 ABOVE). Its "CI" is not inference (see below).

The sample gives no usable evidence either way. The author's watchlist almost never sets a level on a ticker's earnings-reaction day: 2 of 647 real legs, before any fired/gap filter.

## Data and tagging rules (fixed before outcomes)
- **Earnings dates:** Nasdaq public calendar API, no credentials. One call per weekday from 2026-06-08 to 2026-10-30, saved in `r9/h93/raw/nasdaq_*.json`. This gave 82 reports for the sample tickers (`nasdaq_earnings.csv`).
- **Report timing:** Nasdaq's `time` field is `time-not-supplied` for every past report. Timing therefore comes from the first Benzinga-format results headline ("X Qn Adj. EPS ... Estimate", "Sees Qn", "Preliminary Qn") in Alpaca news. The window is D-1 12:00 ET to D+1 12:00 ET. I used the same remote credential pattern as `wl/fetch.sh` (`code/fetch_news.sh` + `code/fetch_news_remote.py`).
  - All 68 past reports got a news timestamp, and every one fell on the Nasdaq date: 18 pre-open, 50 after-close, 0 intraday.
  - The 14 scheduled reports from 10-27 to 10-30 use Nasdaq's own time field. They only matter for the +-3 day window.
- **Earnings table:** `r9/h93/earnings_table.csv`. Columns: s, nasdaq_date, nasdaq_time, news_ts, news_headline, timing, effective_session, timing_source.
  - effective_session = the first session whose 09:30 open comes after the report.
- **Completeness cross-check (`code/crosscheck.py`):** I pulled Alpaca news for all 55 stock tickers from 06-05 to 10-23 (25,630 headlines). I then looked for any "Qn EPS ... Estimate" results headline that is not within 1 day of a table row. There were 0 unmatched and 68 matched.
- **Tags:**
  - AGREE = effective_session == d, and the report came before d's open or after the previous close.
  - SILENT = no report (Nasdaq date or effective session) within +-3 trading days.
  - Everything else is EXCLUDED.
- **ETFs:** SPY, QQQ, IWM and GLD are tagged SILENT in the PRIMARY run. They are dropped in the stock-only SENSITIVITY run, which does not count toward the verdict.
- **Placebo rows:** kind=placebo, fired=1, gap=0, tagged by their own (s, d).
- **Inference:** day-cluster percentile bootstrap within each half (B=10,000, seed 93). Resamples that contain no AGREE leg are dropped and the count is reported. CR1 day-clustered t is reported for the max-t handoff.

## Counts (fired=1, gap=0)
| half | tag | real legs / days / tickers | placebo legs |
|---|---|---|---|
| FIT | AGREE | **2 / 2 / 2** | 1 |
| FIT | SILENT | 143 / 44 / 34 (stocks only: 77) | 1365 |
| FIT | EXCLUDED | 14 / 9 / 12 | 115 |
| TEST | AGREE | **0 / 0 / 0** | 4 (2 with r60) |
| TEST | SILENT | 145 / 36 / 29 (stocks only: 84) | 1276 |
| TEST | EXCLUDED | 5 / 5 / 4 | 32 |

The base filter is not what removes earnings-day legs. Before the fired/gap filters, AGREE has 2 legs out of 647 real legs, and both are in the base. No gap or unfired leg is AGREE. Earnings-day names are simply almost never on the list. Several names reported AMC on 10-21 (META, TSLA, LRCX, IBM), but none of them has a fired non-gap leg on 10-22.

## Results, PRIMARY (ETFs = SILENT), bps
| metric | half | real AGREE mean (n) | real SILENT mean (n) | AGREE-SILENT [95% CI] | t_CR1 | placebo AGREE (n) | real AGREE - placebo AGREE [CI] | placebo AGREE-SILENT [CI] |
|---|---|---|---|---|---|---|---|---|
| r60 | FIT | +269.2 (2) | -4.7 (139) | +273.9 [+148.2, +399.3]* | +3.32* | +254.2 (1) | +15.0 [-98.6, +128.6] | +251.3 [+240.7, +260.7]* |
| r60 | TEST | n/a (0) | -3.6 (142) | **undefined** | n/a | -223.6 (2) | n/a | -227.3 [-371.1, -83.8]* |
| rclose | FIT | +294.1 (2) | -3.4 (143) | +297.5 [+161.0, +432.3]* | +3.48* | +198.1 (1) | +96.1 [-19.9, +212.0] | +193.9 [+178.4, +208.5]* |
| rclose | TEST | n/a (0) | +4.0 (145) | **undefined** | n/a | -185.0 (4) | n/a | -197.6 [-234.7, -108.5]* |

\* These cells are not real inference. Each AGREE arm has only 1 to 4 legs on 1 to 4 days, so the percentile bootstrap just re-weights those few values and gives intervals that look tight but mean nothing. In FIT, 1,298 of 10,000 resamples contained no AGREE day and were dropped. A 2-cluster CR1 t is meaningless.

For scale: with sd(r60) about 123 bps, n=2 has a standard error of about 87 bps, so the 80%-power MDE is about 245 bps.

**SENSITIVITY (stocks only):**
- FIT r60 AGREE-SILENT is +275.4 [+143.1, +407.5]*, n=2 vs 75.
- FIT rclose is +298.1 [+149.6, +446.2]*.
- TEST is undefined.
- The verdict is the same.

**The 2 real AGREE legs (FIT):**
- 2026-07-23 TSLA BELOW (after an AMC EPS miss): fire_min 0, r60 +383, rclose +410.
- 2026-07-30 MSFT ABOVE (after an AMC beat): fire_min 3, r60 +156, rclose +178.

Both continued, which fits the mechanism, but two observations are an anecdote. The placebo AGREE rows split by half: the 1 FIT row is +254, and the 2 TEST rows with r60 average -224. That shows how large the sign noise is at this n.

## Pass rule
- AGREE-SILENT r60 > 0 with CI excluding 0 in FIT: nominally yes, but with n=2 it is not interpretable.
- The same condition in TEST: **no, because n=0 makes it undefined.**
- AGREE-DISAGREE: does not apply to H9.3 (pre-registered).
- **H9.3 = FAIL.**

**Max-t handoff:** the H9.3 TEST metric is undefined, so it adds nothing to the studentized max-t over the 3 AGREE-SILENT metrics. For the joint resampling, the per-leg tags are in `r9/h93/tagged_legs.parquet`, which has real and placebo rows with s, d, half, tag, etf and all outcomes.

**Forward:** nothing survives, so there is nothing to freeze. Earnings-reaction-day legs make up about 0.3% of legs (2 of 647 in 81 sessions). Forward accrual would be roughly 1 AGREE leg every 40 sessions, so the effect cannot be tested on any practical horizon. A shadow scorer for H9.3 is not worth building. If it were revisited, it would need a different population, such as all universe tickers on their reaction day, not watchlist legs.

## Amendments and deviations (none change hypothesis, metric, split or pass rule)
- **A1:** Nasdaq gives no time for past reports, so report timing comes from the timestamps of Alpaca/Benzinga results headlines. Logged as D2 before outcomes. The source for each date is in the `timing_source` column.
- **A2:** rth_1m.parquet starts on 06-15, but the +-3 day window reaches back to 06-10. The session calendar is therefore weekdays minus 06-19, 07-03 and 09-07. It matches the SPY sessions exactly where the two overlap (asserted in code).
- **A3:** Nasdaq returned `rows: null` for two trading Mondays, 07-06 and 10-05, including on a retry. The full-window news cross-check found no unmatched results headline, so I treated those days as having no sample-ticker reporters.
- **A4:** Bootstrap resamples with an empty AGREE or comparison arm are dropped and counted. Logged as D7 before outcomes.
- **A5:** SKHY 07-29 is timed from Korean preliminary results released at 03:27 to 08:12 ET (before the open), so it is pre_open. It has no fired leg on 07-29, so it does not affect any number.

## Files
- `r9/h93/RESULT.md` (this file)
- `r9/h93/DECISIONS.txt`
- `r9/h93/out.txt` (raw output)
- `r9/h93/earnings_table.csv`
- `r9/h93/nasdaq_earnings.csv`
- `r9/h93/tagged_legs.parquet`
- `r9/h93/news_queries.txt` and `news_queries_full.txt`
- `r9/h93/raw/` (Nasdaq JSON, `news.jsonl.gz`, `news_full.jsonl.gz`)
- `r9/h93/code/`:
  - `fetch_nasdaq.sh`, `build_nasdaq.py`
  - `fetch_news.sh`, `fetch_news_remote.py`
  - `earnings_table.py`, `crosscheck.py`
  - `h93.py`
