# H13.2 Market intraday momentum (Gao, Han, Li & Zhou 2018) — RESULT: FAIL (all 6 variants)

Pre-registered in `wl/PREREG13.md` (2026-10-23, before outcomes). Predicted sign POSITIVE. Pass rule: predicted
direction with day-bootstrap 95% CI excluding 0 in BOTH halves, after a studentized max-t across the hypothesis's
6 variants on TEST. Dir: `wl/r13/h132/` (code, fetched bars in `bars/`, csv outputs, `*.out` logs).

## Verdict

| variant (TEST) | n | mean | 95% CI | t | p (max-t adj, 1-sided) | PASS |
|---|---|---|---|---|---|---|
| SPY stock, all days (2022-2026) | 1197 | -1.21 bps/day | [-2.60, +0.21] | -1.69 | 0.9995 | NO |
| SPY stock, \|r_first\|>0.3% | 673 | -1.59 bps | [-3.63, +0.45] | -1.51 | 0.9989 | NO |
| QQQ stock, all days | 1197 | -1.38 bps | [-3.11, +0.32] | -1.60 | 0.9991 | NO |
| QQQ stock, \|r_first\|>0.3% | 827 | -2.18 bps | [-4.32, -0.07] | -2.01 | 0.9999 | NO (wrong sign, CI excludes 0) |
| SPY 0DTE, all days (2025-07..2026-10) | 328 | -2.3% per $ premium | [-12.6%, +8.9%] | -0.42 | 0.94 | NO |
| SPY 0DTE, \|r_first\|>0.3% | 172 | +1.9% | [-13.2%, +18.0%] | +0.24 | 0.77 | NO |

Max observed TEST t = 0.24; bootstrap (B=10000, joint day resampling over the 1197-date union) max-t 95th pct = 2.19.
No variant is positive with CI excluding 0 in either half, so the FIT-half condition fails as well (see below).
Nothing proceeds to a forward paper test.

## 1. Stock replication (SPY, QQQ 1m SIP, 2016-01-05 .. 2026-10-22; 2,695 full sessions each)

Regression r_last = a + b·r_first, Newey-West 5 lags (`stock_regression.csv`):

| sym | half | n | slope | t(NW5) | R² | slope excl. top/bottom 1% \|r_first\| (t) |
|---|---|---|---|---|---|---|
| SPY | FIT 2016-21 | 1498 | +0.014 | +0.35 | 0.0011 | -0.052 (-3.44) |
| SPY | TEST 2022-25 | 994 | -0.012 | -0.77 | 0.0012 | -0.031 (-1.65) |
| SPY | 2026 | 203 | -0.006 | -0.31 | 0.0005 | -0.016 (-0.73) |
| QQQ | FIT 2016-21 | 1498 | +0.019 | +0.56 | 0.0022 | -0.045 (-2.18) |
| QQQ | TEST 2022-25 | 994 | -0.007 | -0.58 | 0.0005 | -0.013 (-0.91) |
| QQQ | 2026 | 203 | +0.015 | +0.90 | 0.0035 | +0.001 (+0.06) |

R² is 0.0005-0.0035 everywhere vs the paper's ≈0.02 (their sample 1993-2013). The small positive FIT slope is carried
entirely by the 2% most extreme first-half-hour days (2020-03 etc.); excluding them the slope is significantly
NEGATIVE in FIT. The post-2016 last half hour mildly reverses the first half hour; it does not continue it.

Sign-following trade, net 1 bp (`stock_trades.csv`; Sharpe = sqrt(252)·mean/sd of daily P&L with 0 on untraded days):

| sym | half | variant | n | mean bps | 95% CI | Sharpe | win | mean excl. top/bottom 1% days |
|---|---|---|---|---|---|---|---|---|
| SPY | FIT | all | 1495 | -2.02 | [-3.60, -0.42] | -1.02 | 0.44 | -2.66 |
| SPY | FIT | \|r_f\|>0.3% | 745 | -3.22 | [-5.91, -0.54] | -0.94 | 0.44 | -3.82 |
| SPY | TEST 22-25 | all | 994 | -1.15 | [-2.74, +0.52] | -0.69 | 0.47 | -1.14 |
| SPY | TEST 22-25 | \|r_f\|>0.3% | 555 | -1.56 | [-4.03, +0.82] | -0.64 | 0.47 | -1.50 |
| SPY | 2026 | all | 203 | -1.53 | [-3.66, +0.75] | -1.52 | 0.44 | -1.58 |
| SPY | 2026 | \|r_f\|>0.3% | 118 | -1.70 | [-4.77, +1.47] | -1.20 | 0.44 | -1.91 |
| QQQ | FIT | all | 1492 | -1.59 | [-3.31, +0.25] | -0.72 | 0.47 | -1.99 |
| QQQ | FIT | \|r_f\|>0.3% | 910 | -2.42 | [-5.06, +0.32] | -0.72 | 0.45 | -3.02 |
| QQQ | TEST 22-25 | all | 994 | -1.89 | [-3.81, +0.07] | -0.97 | 0.48 | -1.82 |
| QQQ | TEST 22-25 | \|r_f\|>0.3% | 682 | -2.62 | [-5.09, -0.20] | -1.06 | 0.47 | -2.63 |
| QQQ | 2026 | all | 203 | +1.11 | [-1.95, +4.25] | +0.77 | 0.49 | +0.80 |
| QQQ | 2026 | \|r_f\|>0.3% | 145 | -0.09 | [-4.01, +3.99] | -0.05 | 0.50 | -0.61 |

Gross of the 1 bp cost the trade is still ≈ -0.2 to -2 bps/day: the direction is wrong, not just the cost.
Year by year (`stock_eval.out`): SPY all-days mean is negative in 10 of 11 years (only 2020 positive, +1.6 bps);
QQQ negative in 8 of 11. Not concentrated — trimming the top/bottom 1% of P&L days makes it slightly worse.

Paper's conditioning, descriptive only (TEST_ALL 2022-26, net 1 bp): SPY top-tercile |r_first| -3.5 bps
(CI [-6.5, -0.5]); SPY high first-half-hour volume (> trailing 60-session median) -3.3 bps ([-5.4, -1.2]);
both conditions -4.5 bps ([-7.9, -1.0]). QQQ: -1.6 / -1.0 / -0.4 bps, CIs include 0. The paper's "stronger on
volatile/high-volume days" shows up here as stronger REVERSAL on those days.

## 2. SPY 0DTE expression (2024-02-01 .. 2026-10-22; `opt_results.csv`, `opt_trades.csv`)

678 trade days; 678/678 contracts have bars; 2 days skipped for a missing 15:30 bar (2024-02-02, 2024-08-08);
0 days needed the intrinsic-at-16:00 fallback. Spread: half_spread.csv row kb=0.25%, hm=14:00 → hs = 2.04% of
price (median entry premium $0.52 → $0.011 per side, i.e. a penny-wide market; a fixed $0.01 half-spread gives the
same answer, see `_SENS` rows). Price = vw else c.

| half | variant | n | mean net ret / $ premium | 95% CI | win | median | P5 | worst (day) | mean excl. top/bottom 1% |
|---|---|---|---|---|---|---|---|---|---|
| FIT 2024-02..2025-06 | all | 348 | +2.6% | [-7.4%, +13.3%] | 0.38 | -26% | -93% | -98.6% (2025-05-08) | -1.7% |
| FIT | \|r_f\|>0.3% | 185 | +2.9% | [-12.1%, +18.9%] | 0.37 | -31% | -95% | -98.6% | -1.2% |
| TEST 2025-07..2026-10 | all | 328 | -2.3% | [-12.6%, +8.9%] | 0.36 | -31% | -92% | -96.4% (2026-01-13) | -7.3% |
| TEST | \|r_f\|>0.3% | 172 | +1.9% | [-13.2%, +18.0%] | 0.39 | -29% | -91% | -95.3% (2025-12-02) | -2.1% |

Gross (hs=0) means are +7%/+7% (FIT) and +2%/+6% (TEST), all CIs include 0. The option P&L is 0.65-0.72 correlated
with the signed 15:30→16:00 spot move, i.e. it is just a levered version of the (null) stock trade plus theta: on the
59% of TEST days where spot moves < 0.1% in the last half hour the long option loses 21% on average. Top-5 TEST days
(+635%, +466%, +455%, +404%, +373%) carry the mean; the trimmed mean is negative in both halves and both variants.

## Skeptic's checks

- **Timezone/DST**: all bars converted UTC→America/New_York; both offsets present (708k bars at -04:00, 350k at
  -05:00 per symbol). Cutoff convention: PREREG says "last bar with t ≤ 10:00"; Alpaca labels bars by OPEN, so that bar
  closes at 10:01 (same for 15:30→15:31). `sens_cutoffs.py` reruns with the 09:59/15:29 bars and with the 15:59 bar
  instead of the 16:00 auction bar: all 4 conventions give negative FIT and TEST means for both ETFs (SPY TEST -1.0 to
  -1.4 bps all-days; QQQ -1.2 to -1.9). Conclusion is convention-independent.
- **Prior close / holidays**: prev close = prior SESSION's last bar ≤ 16:00 (so Fri→Mon, holiday gaps and the day after
  a 13:00 half day all use the true last print). No session gap > 4 calendar days; no |overnight gap| > 8%; SPY/QQQ
  have no splits in range (max |r_first| = 9.1% on 2020-03-16, a real day).
- **Half days** (AMENDMENT, logged): the decade feed carries extended-hours bars, so a 13:00-close session still has
  bars at 15:30 and the "drop sessions without a 15:30+ bar" rule drops nothing. Half days were taken from the NYSE
  early-close calendar (21 dates 2016-2025, each confirmed to have a depressed afternoon volume ratio) and dropped;
  their 13:00 bar supplies the next day's prev close. 2026 has no early close through 10-22.
- **Concentration**: reported with and without top/bottom 1% of days (stock: trimming makes it more negative;
  options: trimming flips the small positive means negative). Yearly table in `stock_eval.out`.
- **OCC trap**: 722,809 of 991,710 fetched bars were on dates other than the contract's expiry and were discarded.
- **Pass-rule interpretation** (stated before outcomes in `stock_eval.py`): stock TEST for the pass rule = 2022-2026
  combined ("TEST 2022-2025 + 2026"); 2022-25 and 2026 are also reported separately above. Makes no difference.

## Interpretation

The Gao-Han-Li-Zhou effect is absent in SPY/QQQ after 2016 under this replication: R² ≈ 0.001 vs 0.02, slopes within
noise and negative once the 2% most extreme days are removed, and the sign-following trade loses 1-3 bps/day before
costs are even a factor. The 0DTE expression inherits the null directional signal and adds theta bleed (win rate
36-39%, median -26% to -31% per trade) with a fat right tail that produces a zero-mean lottery, not an edge.
Ledger: +6 variants, 0 pass (running total ≈ 486).

## Files

- `build_daily.py` → `daily_SPY.parquet`, `daily_QQQ.parquet` (prev_close, c1000, c1530, c1600, v_first, r_first, r_last)
- `stock_eval.py` → `stock_regression.csv`, `stock_trades.csv`, `stock_pnl_*.parquet`, `stock_eval.out`
- `sens_cutoffs.py` → `sens_cutoffs.out`
- `make_occ.py` → `occ_list.csv`, `occ_YYYY-MM.txt`; `fetch_months.sh` (wl/fetch.sh monthly) → `bars/opt_YYYY-MM.jsonl.gz` (33 files, 16 MB), `fetch.log`
- `opt_eval.py` → `opt_trades.csv`, `opt_results.csv`, `opt_ret_*.parquet`, `opt_eval.out`
- `maxt.py` → `maxt_test.csv`, `maxt.out`
