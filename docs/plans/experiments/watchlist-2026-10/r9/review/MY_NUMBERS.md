# Independent rebuild, round 9 (written BEFORE reading any r9 RESULT/code/parquet)
Code: REV/code/{rfetch.py,fetch.sh,base.py,stats.py,h91.py,common.py,h92.py}. Raw: audit_log (SELECT), Alpaca SIP 1m/1D stock bars, Alpaca 1m/1D option bars, Nasdaq earnings calendar.

## Legs
- TriggerArmed: 719 rows (staging_paper 647, prod_real 72), dedupe on (ET date, ticker, dir, trigger) gives 647 legs over 81 dates.
- The 2026-07-01 legs (5: TSLA/TWLO/MSFT/AMZN/NOW, all ABOVE) were armed at 22:55:37 ET, after the session. They are NOT a copy of the 07-02 digest (07-02 has its own 09:22 digest with different levels, e.g. TSLA 438 vs 425). My preferred treatment is to EXCLUDE them, because the levels were not knowable in real time for the 07-01 session. I report both.

## 1. Base sample (first RTH 1m close beyond level; gap = 09:30 bar OPEN strictly beyond; entry = that close; r60 = last close at or before entry+60m, truncated at 16:00 for 8 legs)
| | n | days | mean r60 | sd r60 | sd rclose | day-clustered SE | 2.8*clSE (all/30%/15%) | iid 2.8*sd/sqrt(n) |
|---|---|---|---|---|---|---|---|---|
| ALL incl 07-01 | 309 | 80 | -5.8 | 121.9 | 172.9 | 6.30 | 17.6 / 32.2 / 45.5 | 19.4 / 35.4 / 50.1 |
| ALL excl 07-01 | 306 | 79 | -6.0 | 122.4 | 173.7 | 6.36 | 17.8 / 32.5 / 45.9 | 19.6 |
| FIT 06-22..08-31 | 159 | 44 | -6.8 | 122.2 | 171.2 | 8.36 | 23.4 / 42.8 / 60.5 | 27.1 |
| TEST 09-01..10-22 | 150 | 36 | -4.7 | 121.9 | 175.0 | 9.61 | 26.9 / 49.1 / 69.4 | 27.9 |
Status of the 647 legs: fired 309, nofire 322, gap 16 (FIT 9 / TEST 7). Fired legs with trigger at or after 09:45: 172 (FIT 93 / TEST 79).
The 3 fired 07-01 legs (TSLA, MSFT, AMZN) are needed to reach n=309/80 days.
MDE note: the pass rule needs an AGREE-SILENT DIFFERENCE inside EACH half. With an AGREE share of about 25% of the roughly 90 legs per half that H9.2 uses, SE is about 122*sqrt(1/22+1/65), roughly 30 bps, so the MDE80 is about 80-95 bps per half, not 19/35/50.

## 2. H9.1 (25 independent BTOs after excluding TradingTheTrend, manual:* sid, tenant:* authors, and the manual 'ridopark' DRAM entry; dedupe on signal_id)
Window [-24h, trigger] on posted_at (same result using received_at):
FIT: AGREE 1, DISAGREE 0, SILENT 158. TEST: AGREE 1, DISAGREE 0, SILENT 149.
AGREE legs: 2026-07-01 MSFT ABOVE (TB22 MSFT C 09:45; leg trigger 11:08; r60 -17.0) and 2026-09-21 AMZN ABOVE (TB22 AMZN C 09:42; trigger 12:32; r60 -12.2).
The ONLY FIT AGREE is a 07-01 leg (armed after the session). With 07-01 excluded, FIT AGREE = 0.

## 3. H9.2 on my own random sample (n=40 of the 172 legs triggered at or after 09:45, pandas sample random_state=20261023, drawn on leg keys only)
Method: expiries discovered from Alpaca daily option bars (the contracts endpoint returns 401 for data keys). Nearest 2 listed expiries on or after the session. Strikes on a 0.5 grid within +-5% of the session's official open. 1m bars fetched only for 13:30-14:30Z of that session, and re-filtered to bars whose ET date equals the session (0 off-date bars). Window = bars with start in [09:30, min(trigger, 10:30)). Trailing 20 sessions use the same clock window, their own spot, and their own nearest 2 expiries. z uses sd with ddof=1.
Tags: FIT AGREE 2 / DISAGREE 2 / SILENT 13; TEST AGREE 3 / DISAGREE 3 / SILENT 17. AGREE share 12.5%, DISAGREE share 12.5%.
Day-clustered contrasts (bps):
- FIT AGREE-SILENT r60 +40.1 [-9.3,+89.6]; TEST +27.6 [-5.1,+60.2]; pooled +32.5 [+3.7,+61.3] (n=5 AGREE)
- AGREE-DISAGREE r60: FIT +19.8, TEST +29.0
- rclose AGREE-SILENT: FIT -17.4, TEST +71.2
Per-leg tags are in my_h92_tags_outcomes.csv. Sample composition: SPY 13, QQQ 14, other 13.

## 4. H9.3 (Nasdaq calendar 06-10..10-30. Times are 'not-supplied' for past reports, so BMO/AMC is inferred from the larger overnight gap. Checked robustness by allowing BOTH the report day and the next day)
Legs (of 647) on an earnings reaction day: 2, TSLA 2026-07-23 BELOW (fired, FIT) and MSFT 2026-07-30 ABOVE (fired, FIT). TEST: 0.
Near misses that are correctly NOT reaction days: AMZN x2 and AAPL (gap leg) on 07-30, which is the report day itself with an AMC release; TSLA 10-21 is the report day with an AMC release.
SILENT (no reaction day within +-3 trading days) excludes 27 legs (16 fired).
