# H8.2 Attention drift/reversal: RESULT = FAIL (0 of 6 metrics pass)

Pre-registration: `wl/PREREG8.md` (H8.2). The hypothesis, metrics, split and pass rule were not changed. Interpretation choices and data-driven deviations are listed under Amendments.
Code: `r8/h82/h82.py`. Raw output: `r8/h82/out_main.txt` (primary) and `r8/h82/out_keep0701.txt` (sensitivity). Fixed universe: `r8/h82/universe.txt`, written before any returns were computed.

## Setup
- **Listed pairs:** `legs_raw.psv`, de-duplicated on (d, ticker). sign = +1 if the ticker has only ABOVE (call) legs, -1 if only BELOW (put) legs, 0 if both.
- **Split:** FIT is 06-22..08-31 and TEST is 09-01..10-22. FORWARD (10-23 onward) was not touched.
- **Returns:** Alpaca SIP daily bars, adjusted for splits and dividends.
  - oc = C_d/O_d - 1
  - cn = O_{d+1}/C_d - 1
  - c5 = C_{d+5}/C_d - 1 (trading days)
  - Any return whose future bar is missing is dropped, not filled. That removes the last day for cn and the last 5 days for c5.
- **Control:** for each listed day, the equal-weight mean across the fixed universe (59 ever-listed tickers plus the S&P 100) with that day's listed names removed. About 128 names per day.
  - signed metric = sign x (r_i - ctrl_mean_d)
  - unsigned metric = |r_i| - ctrl_mean_d(|r_j|)
- **Inference:** pooled mean over pairs with a day-clustered standard error. The studentized max-|t| over the 6 metrics uses a day-cluster bootstrap with B = 10,000.
- **Pass rule as implemented:** a metric passes only if all three hold:
  1. FIT and TEST estimates have the same sign.
  2. The FIT 95% pointwise CI excludes 0.
  3. The TEST 95% simultaneous CI from the max-t excludes 0.
  - I also show max-t on FIT and pointwise intervals on TEST. Neither changes the verdict.

## Per-half n
| half | listed pairs | days | sign +1 / -1 / 0 | ETF pairs |
|---|---|---|---|---|
| FIT  | 255 | 44 | 133 / 45 / 77 | 72 |
| TEST | 222 (cn 216, c5 194) | 36 (cn 35, c5 31) | 115 / 19 / 88 | 66 |

## Primary results (bp; day-clustered)
FIT:
| h | metric | n | est | se | t | 95% CI | simult. CI (crit 2.88) |
|---|---|---|---|---|---|---|---|
| oc | signed   | 255 | -14.2 | 16.6 | -0.86 | [-46.8, 18.4] | [-62.0, 33.6] |
| oc | unsigned | 255 | -14.0 | 13.3 | -1.06 | [-40.1, 12.0] | [-52.2, 24.1] |
| cn | signed   | 255 | -22.8 | 13.9 | -1.63 | [-50.1, 4.6]  | [-62.8, 17.3] |
| cn | unsigned | 255 | +2.6  | 10.7 | +0.24 | [-18.5, 23.6] | [-28.3, 33.4] |
| c5 | signed   | 255 | -16.5 | 48.5 | -0.34 | [-111.6, 78.6] | [-156.1, 123.0] |
| c5 | unsigned | 255 | +29.5 | 40.1 | +0.74 | [-49.1, 108.0] | [-85.8, 144.7] |

TEST:
| h | metric | n | est | se | t | 95% CI | max-t simult. CI (crit 2.85) | adj p |
|---|---|---|---|---|---|---|---|---|
| oc | signed   | 222 | +9.3  | 15.2 | +0.61 | [-20.4, 39.0] | [-34.0, 52.5] | 0.99 |
| oc | unsigned | 222 | +5.6  | 12.3 | +0.46 | [-18.4, 29.6] | [-29.3, 40.6] | 1.00 |
| cn | signed   | 216 | +16.0 | 11.6 | +1.39 | [-6.7, 38.7]  | [-17.0, 49.0] | 0.67 |
| cn | unsigned | 216 | +7.1  | 8.6  | +0.82 | [-9.8, 24.0]  | [-17.5, 31.7] | 0.95 |
| c5 | signed   | 194 | -23.5 | 56.3 | -0.42 | [-133.9, 86.8] | [-184.2, 137.1] | 1.00 |
| c5 | unsigned | 194 | -9.0  | 39.6 | -0.23 | [-86.6, 68.6] | [-122.0, 104.0] | 1.00 |

Pass rule:
| metric | same sign | FIT CI excl 0 | TEST max-t CI excl 0 | PASS |
|---|---|---|---|---|
| oc signed   | no  | no | no | FAIL |
| oc unsigned | no  | no | no | FAIL |
| cn signed   | no  | no | no | FAIL |
| cn unsigned | yes | no | no | FAIL |
| c5 signed   | yes | no | no | FAIL |
| c5 unsigned | no  | no | no | FAIL |

No interval in either half excludes 0, even pointwise. The largest effect, signed close-to-next-open, flips sign between halves (-22.8 bp in FIT, +16.0 bp in TEST). That is noise, not drift or reversal.

## Robustness (none rescues it)
- **Within-ticker control** (raw returns minus the same ticker's mean on its non-listed days in the same half; tickers need at least 5 off days, which drops SPY/QQQ in TEST, giving n = 190):
  - FIT: oc signed -13.9 [-48.1, 20.3]; cn signed -33.3 [-64.1, -2.4]; c5 signed -41.1 [-144, 62]; unsigned all straddle 0.
  - TEST: oc signed +5.9 [-33.4, 45.2]; cn signed +24.1 [-8.3, 56.4]; c5 signed -34.6 [-167, 98]; unsigned all straddle 0.
  - Signed cn is pointwise significant in FIT and then reverses sign in TEST, which is the same pattern as the primary result.
- **Signed metrics on sign != 0 pairs only** (excluding the "both legs" names): FIT oc -20.4, cn -32.6, c5 -23.7; TEST oc +15.4, cn +26.6, c5 -37.4. All CIs include 0, and oc/cn flip sign again.
- **ISO-week clusters** (because c5 windows overlap): only FIT cn signed excludes 0 ([-43.5, -2.0]). TEST is +16.0 [-12.9, 45.0], opposite sign. With only 8–11 clusters these intervals are themselves unreliable.
- **Keeping 07-01** (see A1): FIT n = 260 over 45 days. Estimates move by 2 bp or less. Verdict unchanged: 0 of 6 pass.

## Tradeable version
None qualifies, because nothing passed. For reference only, the best TEST signed leg net of costs (listed leg 3 bp single name / 1 bp ETF round trip, plus 3 bp round trip for a control basket) on sign != 0 pairs:
- oc: +9.4 bp [-39.8, 58.6]
- cn: +20.7 bp [-17.2, 58.6]
- c5: -43.3 bp [-218, 131]

The cn figure has the opposite sign in FIT, so it is not a candidate.

## Leakage check
- 714 of 719 legs were armed between 09:12 and 09:29 ET, before the open, so open-to-close on the listing day is a clean outcome.
- The exception is 07-01: all 5 legs (TSLA, TWLO, MSFT, AMZN, NOW) were armed at 22:55 ET, after the close. Selection on that day cannot be shown to precede the outcome, so it is excluded (A1).
- Prior-day moves are not used as outcomes anywhere.
- The control mean for each day excludes that day's listed names.

## Amendments (each logged with its reason; none changed after looking at outcomes, except A1's sensitivity run, which was done to show A1 does not matter)
- **A1 (data, leakage):** 07-01 is excluded because its legs were armed after the close. The run with 07-01 kept is reported, and the verdict is unchanged.
- **A2 (data):** I used `adjustment=all` daily bars, via a copy of `fetch_remote.py` in `r8/h82/`, instead of `raw`.
  - Reason: CRWD had a 4:1 split in the window (raw/adjusted close ratio 0.25), and HON had a corporate action (ratio 1.045). Raw bars would create fake returns of about -75%.
  - Daily opens and closes were checked against `rth_1m` 09:30 opens and last closes: median difference 0, p99 0.39% for opens and 0.21% for closes.
- **A3 (data):** BK returned no bars from Alpaca, so the universe is 135 names. GOOG was dropped as a duplicate share class of GOOGL when the list was written, before any returns were computed.
- **A4 (interpretation):** for the signed metrics, "both legs -> 0" was applied literally: those pairs enter with value 0. The alternative (sign != 0 pairs only) is shown above, and the verdict is unchanged.
- **A5 (interpretation of "studentized over the 6"):** the max-t is applied on TEST, as the brief directed. FIT uses pointwise CIs, which is the more lenient choice. The FIT max-t is also shown, and the verdict is unchanged.

## Caveats
- **Low power.** One TEST standard error is about 12–15 bp for oc and cn and about 40–56 bp for c5. With the max-t critical value of about 2.85, the minimum detectable effects are roughly 35–45 bp per day (oc/cn) and roughly 120–160 bp over 5 days. Smaller attention effects cannot be ruled out. The result rules out large, stable effects; it does not show the effect is zero.
- **Unsigned metric is not vol-matched.** About 30% of listed pairs are ETFs (mostly SPY/QQQ, listed almost daily), while the control set includes high-volatility small caps (AAOI, IREN, RIOT, and others). The median unsigned excess is strongly negative (for example oc FIT -77 bp), even though the mean is about 0. The within-ticker control removes this confound and is also null.
- **c5 windows overlap** across consecutive listing days. Day clusters understate dependence there; the week-cluster check is reported.
- **Approximate S&P 100 membership.** The list was written from memory of roughly 2025 membership. It was fixed before computing outcomes, so it is not selected on outcomes, but it is not the exact constituent list.
- **Few puts in TEST.** Only 19 TEST pairs have sign -1, so the TEST signed metric is mostly a long-call-names test.
- **Small sample.** 80 listing days over about 4 months, in one market regime. FORWARD (10-23 onward) remains the final arbiter but is moot now that the pre-registered test failed.
