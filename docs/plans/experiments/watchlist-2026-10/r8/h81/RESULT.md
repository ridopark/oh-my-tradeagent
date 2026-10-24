# H8.1 VOL SELECTION: RESULT = FAIL

Pre-registration: wl/PREREG8.md. The hypothesis, metric, split and pass rule were not changed. Amendments are listed at the end.

**Verdict.** Neither pre-registered realized/implied metric is positive with a CI excluding 0 in FIT, under either control. The 09:31 to 15:55 ATM straddle has a negative net mean in both halves. In TEST it is -1.03% with a CI that includes 0. Both legs of the pass rule fail on their own. The data give no support for the idea that the author picks names that go on to move more than prior-day option prices imply.

## Exact rules used (fixed before computing outcomes)
- **Listed set:** legs_raw.psv deduped on (d, ticker). That gives 482 pairs over 81 days and 59 tickers: FIT 222, TEST 260.
- **Universe:** the 59 ever-listed tickers plus the S&P 100. Membership is approximate (2025, from memory, GOOG dropped). That is 135 symbols in total; see r8/h81/universe.txt. It is the same list r8/h82 uses.
- **Daily bars:** Alpaca SIP 1Day bars with raw adjustment. "Prev close" is the close on the prior trading day, using SPY's calendar. An observation is used only if the ticker has a bar on that exact prior day.
- **IV:** DoltHub `option_chain` on the prior trading day only (no fallback to older dates). Steps:
  - Expiry = the earliest expiration at least 7 calendar days after the chain date. Expirations up to 37 days out were searched.
  - Rows were pre-filtered server-side to |delta| in [0.3, 0.7] to stay under DoltHub's 1000-row limit.
  - ATM strike = the strike nearest the prior-day Alpaca close, among strikes with vol > 0.
  - ATM IV = mean of the call and put vol at that strike, or the single side if only one exists (57 obs).
  - An observation is dropped if the ATM strike is more than 5% from spot. This never happened.
  - Realized DTE of the IV expiry: median 15 days, range 7 to 37.
- **Implied daily move:** m = IV / sqrt(252).
- **Metric:** y = log(realized / m), for two realized measures:
  - y_cc uses |c/pc - 1|.
  - y_hl uses (h - l)/pc.
  - Realized moves are floored at 1 bp before the log; 68 close-to-close obs hit the floor.
- **Listed minus control:**
  - Cross-section: listed y minus the same-day mean y over universe tickers not listed that day.
  - Within-ticker: listed y minus that ticker's mean y on its non-listed days in the same half.
  - Mean with a day-clustered 95% CI (CR1, t with G-1 degrees of freedom).
- **Pass rule as I applied it:** H8.1's metric passes only if every combination of {y_cc, y_hl} x {cross-section, within-ticker} is > 0 with a CI excluding 0 in both FIT and TEST. The trade must also have net mean > 0 with a CI excluding 0 in TEST. I fixed this reading before seeing the full-sample numbers (see amendment A1).

## Coverage
| | FIT | TEST |
|---|---|---|
| Listed pairs (deduped) | 222 | 260 |
| Listed pairs with valid prior-day IV (used) | 196 (35 tickers, 45 days) | 163 (31 tickers, 35 days) |
| Control obs, cross-section (non-listed universe ticker-days with IV) | 5,622 | 4,060 |
| Control obs, within-ticker (same tickers, non-listed days) | 1,542 | 946 |

**DoltHub gaps:**
- These 18 symbols never appear in the chain over the whole window: QQQ, IWM, GLD, ARM, TSM, AAOI, CRWV, IREN, NBIS, NET, RIOT, RKLB, SKHY, SNOW, SPCX, SPOT, ZS, BK.
- The chain for 2026-09-29 is empty (0 rows for AAPL too), so trade date 09-30 has no IV and is dropped.
- In total, 123 of 482 listed pairs are lost. QQQ alone accounts for 70 of them.
- 10,043 of 11,731 universe ticker-days have IV.

**Survivorship:** the universe is today's approximate S&P 100 plus names chosen because they were listed. Both control comparisons share the same IV source, so coverage gaps hit listed and control obs alike. The 18 missing names are skewed toward high-beta, momentum and ETF names, and that is a real hole in the test (see C1).

## H8.1 metric: listed minus control, mean [day-clustered 95% CI]
| Half | Metric | Control | n listed / days | listed mean y | control mean y | diff | 95% CI |
|---|---|---|---|---|---|---|---|
| FIT | y_cc | cross-section | 196 / 45 | -0.688 | -0.762 | **+0.075** | [-0.088, +0.238] |
| FIT | y_cc | within-ticker | 196 / 45 | -0.688 | -0.672 | **-0.016** | [-0.182, +0.150] |
| FIT | y_hl | cross-section | 196 / 45 | 0.127 | 0.135 | **-0.008** | [-0.067, +0.051] |
| FIT | y_hl | within-ticker | 196 / 45 | 0.127 | 0.129 | **-0.002** | [-0.075, +0.070] |
| TEST | y_cc | cross-section | 163 / 35 | -0.773 | -0.839 | **+0.067** | [-0.162, +0.295] |
| TEST | y_cc | within-ticker | 163 / 35 | -0.773 | -0.895 | **+0.123** | [-0.143, +0.389] |
| TEST | y_hl | cross-section | 163 / 35 | 0.102 | 0.129 | **-0.027** | [-0.092, +0.039] |
| TEST | y_hl | within-ticker | 163 / 35 | 0.102 | 0.009 | **+0.093** | [+0.006, +0.180] |

Only one of the 8 cells excludes 0: TEST y_hl within-ticker. That cell is driven by SPY. SPY is listed on almost every day, so its few non-listed TEST days set its control mean. With ETFs (SPY, QQQ, IWM, GLD) removed, the cell becomes +0.035 [-0.049, +0.119]. Its FIT counterpart is -0.002. **Metric leg: FAIL**, because nothing is significant in FIT.

**Sensitivity runs (all in metric_results.csv, none change the verdict):**
- Excluding the 5 listings armed after the open: on 2026-07-01, TSLA, TWLO, MSFT, AMZN and NOW were first armed at 22:55 ET, which is look-ahead if used. FIT is essentially unchanged and TEST is identical.
- Excluding ETFs: FIT y_cc cross-section is +0.110 [-0.048, +0.269], and no cell is significant in either half.

**Diagnostic, not pre-registered:** y_oc = log(|c/o - 1| / m), the open-to-close move only, which is the part actually tradeable after the ~09:21 listing.
- FIT within-ticker: -0.277 [-0.482, -0.072].
- TEST within-ticker: +0.245 [+0.022, +0.467].
- The sign flips between halves, which reads as noise or regime rather than selection skill.

## Straddle trade (1 variant, reported even though the metric failed)
**Rules:**
- **Spot:** open of the 09:31 1m stock bar.
- **Contract:** the earliest expiry at least 2 calendar days out where both the call and put at some strike printed in 09:31–09:35. Strike = the one nearest spot among those. Candidate OCC symbols covered every weekday from d+2 to d+9 and strike grids of 0.5/1/2.5/5/10.
- **Entry:** open of each leg's first bar in 09:31–09:35.
- **Exit:** close of the last bar in 15:30–15:54. If none exists, the last bar of the day up to 15:54 is used (stale exit, 32 trades).
- **Option bars:** requested per trade date with a 13:00–21:00Z window. They were then filtered again so that the ET date equals the trade date (the date-mixing guard).
- **Half-spread:** Roll estimate per leg from that day's 1m closes, sqrt(-cov(dp_t, dp_t-1)) / median price, floored at 1%. Median is 1.0% per leg; the floor binds on both legs in 38% of trades. half_spread.csv was not used, because it only covers SPY/QQQ 0DTE and these trades are at least 2DTE.
- **Return:** net = [C_x(1 - hs_c) + P_x(1 - hs_p)] / [C_e(1 + hs_c) + P_e(1 + hs_p)] - 1.
- **Trades filled:** 452 of 482 pairs. The 30 misses had no paired call+put print in 09:31–09:35, and 13 of them fall on 08-06 to 08-11, where early option prints were sparse.
- DTE distribution: 2d 174, 3d 121, 4d 99, 5–9d 58.

| Set | Half | n | days | mean net | median net | 95% CI (day-clustered) | 95% CI (iid) | win rate | mean gross |
|---|---|---|---|---|---|---|---|---|---|
| all | FIT | 238 | 44 | **-4.99%** | -10.25% | [-8.50%, -1.48%] | [-7.48%, -2.50%] | 28.6% | -1.77% |
| all | TEST | 214 | 36 | **-1.03%** | -6.35% | [-5.63%, +3.57%] | [-3.80%, +1.74%] | 36.0% | +2.01% |
| excl stale exits | TEST | 202 | 36 | -1.29% | -6.35% | [-5.52%, +2.94%] | | 35.6% | +1.54% |
| excl ETFs | TEST | 148 | 36 | -1.42% | -7.18% | [-5.57%, +2.73%] | | 37.2% | +1.92% |

**Trade leg: FAIL.** The TEST net mean is negative and its CI includes 0. FIT is significantly negative. The returns are right-skewed (median around -6% to -10%, a few +80% to +157% days), which is the usual long-gamma payoff profile. The best days were spot-checked against daily bars and are real: TSLA on 07-02 fell 8.1% intraday, and SPY/QQQ/META on 10-16 sold off. TEST gross is +2.0% before spread, but the CI is wide and costs remove it.

## Pass/fail per the pre-registered rule
| Condition | Result |
|---|---|
| Metric > 0 with CI excluding 0 in FIT | FAIL (0 of 4 cells) |
| Metric > 0 with CI excluding 0 in TEST | FAIL (1 of 4 cells; ETF-driven, disappears without ETFs) |
| Straddle net mean > 0 with CI excluding 0 in TEST | FAIL (-1.03%, CI [-5.6%, +3.6%]) |
| **H8.1** | **FAIL** |

## Amendments (data-driven deviations, each with a reason)
- **A1 (interpretation, fixed before full-sample outcomes):** The prereg names two realized measures and two controls but not how to combine them. I required all four combinations to pass (a conjunction, which is conservative). Under any single-cell reading H8.1 still fails, because no cell passes in both halves. Disclosure: one pipeline smoke test ran on the first 14 chain dates (FIT only, 49 listed obs) before all the data had arrived. No rule was changed after it.
- **A2:** Rows were pre-filtered to |delta| in [0.3, 0.7] and expirations within 37 days, because of DoltHub's 1000-row cap and timeouts on the original min-expiry query. The nearest-strike pick was never more than 5% from spot, so the filter did not bind on the ATM choice.
- **A3:** Close-to-close |ret| is floored at 1 bp before the log (68 obs), because log(0) is undefined.
- **A4:** Two universe ticker-days with |ret| > 40% were dropped as probable raw-price split or data errors: CRWD on 07-02 (-75%) and MRNA on 08-19 (+177%). Neither was listed.
- **A5:** Trade timing details the prereg left open: the entry window is 09:31–09:35 using the first bar's open; the exit is the last close in 15:30–15:54, with a stale fallback for 32 trades. Excluding stale exits does not change anything.
- **A6:** The prereg's "nearest expiry >= 2 days" is read as calendar days. A trade needs both legs at the same strike to have printed in the entry window. This is known at entry, so there is no look-ahead.

## Caveats and look-ahead checks
- **No same-day IV.** The IV chain date is strictly the prior SPY trading day, merged on the ticker's own prior-bar date. 09-29 is missing, so 09-30 is dropped rather than filled with older data.
- **Option date-mixing.** Bars were requested in a window bounded to the trade date and then re-filtered so the ET date equals the trade date. The expiry and strike are parsed from the OCC symbol, not inferred.
- **Listing timing.** 477 of 482 first arms are before 09:30 ET (mostly around 09:21). So the close-to-close metric includes the overnight gap the author could already see premarket. If anything this biases the pre-registered metric toward passing, and it still fails. The 09:31 straddle and y_oc are free of this.
- **C1 (coverage).** QQQ (70 listed pairs), ARM, TSM, CRWV, NBIS, IREN and others have no DoltHub IV, so the metric covers 74% of listed pairs. The straddle leg uses Alpaca bars and covers 94%; it does not depend on DoltHub and fails on its own.
- **Event days.** The IV comes from an expiry about 15 days out, so dividing by sqrt(252) understates the implied move on earnings or event days. That would favour "listed > implied" if the author picks event names, yet the metric is still null. The short-dated straddle prices events directly.
- **Costs.** The Roll estimator, floored at 1%, may understate real spreads on illiquid 2–4DTE single-name options, and fills used the trade price of the first and last prints, not quotes. The true net is likely worse, which only strengthens the FAIL.

## Files (all in /tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r8/h81/)
- **Code:** fetch_iv.py, gen_occ.py, fetch_opt.sh, fetch_opt_remote.py, straddle.py, straddle_stats.py, h81.py
- **Data:** daily.jsonl.gz, chain/*.json (87 dates), opt_bars.jsonl.gz, panel.parquet, straddle_trades.csv, straddle_spots.csv, metric_results.csv, h81_out.txt
- **Unused:** chain_v1/ holds 3 dates from an aborted first fetch with a wider delta window.
