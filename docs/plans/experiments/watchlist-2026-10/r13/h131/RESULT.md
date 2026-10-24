# H13.1 RESULT — Overnight short ATM straddle/strangle premium on SPY/QQQ 1-DTE (Muravyev & Ni 2020)

**Verdict: FAIL on all 8 variants. Predicted sign (positive short-side net return per $ premium) is not observed in
either half; 7 of 8 TEST point estimates are negative, and the only two FIT variants whose CI excludes 0 do so on the
WRONG side (QQQ straddle all/exEvent, SPY straddle exEvent). Nothing goes to a forward paper test.**

Pre-registration: wl/PREREG13.md §H13.1 (hypothesis, metric, variants, split, pass rule unchanged). Operational choices
fixed before outcomes: wl/r13/h131/AMENDMENTS.md (A1–A10, reproduced at the end). CPI list fixed before outcomes:
wl/r13/h131/cpi_dates.txt (BLS schedule as recalled — bls.gov 403s bots from this host, FRED/ALFRED unreachable; flagged
unverified). FOMC: scripts/data/fomc-dates.txt.

## Headline numbers (net of spread + 1 bp/leg stock cost; ret = net P&L / premium received)

| variant | FIT mean [95% CI] n | TEST mean [95% CI] n | max-|t| fam. p (TEST) | verdict |
|---|---|---|---|---|
| SPY straddle all     | -3.48% [-7.32, +0.12] 346 | -0.54% [-4.14, +2.88] 327 | 0.96 | fail |
| QQQ straddle all     | -4.53% [-8.70, -0.90] 345 | -3.96% [-7.69, -0.42] 324 | 0.08 | fail (wrong sign) |
| SPY straddle exEvent | -4.01% [-8.13, -0.26] 319 | -0.86% [-4.77, +2.77] 302 | 0.89 | fail |
| QQQ straddle exEvent | -5.04% [-9.29, -1.17] 318 | -4.33% [-8.33, -0.46] 299 | 0.07 | fail (wrong sign) |
| SPY strangle all     | -6.44% [-16.29, +1.66] 345 | +1.02% [-6.01, +7.76] 326 | 0.97 | fail |
| QQQ strangle all     | -6.14% [-13.54, +0.23] 343 | -5.34% [-11.78, +0.93] 324 | 0.18 | fail |
| SPY strangle exEvent | -7.47% [-17.73, +0.96] 318 | +0.32% [-7.57, +7.67] 301 | 1.00 | fail |
| QQQ strangle exEvent | -6.95% [-14.90, -0.26] 316 | -5.93% [-12.87, +0.49] 299 | 0.16 | fail |

Why it fails — the economics, not the statistics: the straddle does not decay overnight on average.
- SPY: ATM 1-DTE straddle at 15:55 = 0.760% of spot (FIT) / 0.643% (TEST); the same straddle at 09:35 next session =
  0.768% / 0.624%. QQQ: 1.014% / 0.913% at 15:55 vs 1.028% / 0.918% at 09:35.
- Mean overnight |gap| to 09:35 is 0.46%/0.39% (SPY) and 0.61%/0.60% (QQQ): realized gaps plus the residual 0-DTE time
  value fully replace the theta. Short-side GROSS (no costs, hedged) return: SPY -0.4% FIT / +2.5% TEST; QQQ -1.4% / -0.8%.
- Costs then take ~3-4% of premium per night (two legs x ~1% half-spread x entry+exit). With the floor at 1% the
  Roll estimate binds on 54% of legs (median Roll = 0.87% of price, p90 2.8%). At 2x spread every variant is negative
  in both halves with CIs mostly excluding 0 (see second table).

## Muravyev–Ni comparison (long side, gross; their close->open delta-hedged return is ≈ -1%/day)
| | SPY FIT | SPY TEST | QQQ FIT | QQQ TEST |
|---|---|---|---|---|
| long straddle, unhedged, close->open | +0.76% | -2.69% | +1.19% | +0.93% |
| long straddle, delta-hedged, gross   | +0.39% | -2.51% | +1.43% | +0.76% |
Only SPY/TEST has the paper's sign and it is 1.5 sd from 0 (t ≈ -1.4 before costs); FIT and QQQ have the opposite
sign. The paper's effect (built on SPX options 2004-2015, and strongest for short-dated) does not show up in 2024-26
SPY/QQQ 1-DTE at 15:55->09:35. Plausible reasons: 0/1-DTE ETF premia are now priced for the overnight gap (daily
expiries, massive 0DTE flow), and the paper's "intraday positive / overnight negative" split is a 2004-15 regime fact.

## Tail (this is short gamma)
- Worst nights (straddle, ret/premium): 2025-01-24->27 (DeepSeek Monday) SPY -343% / QQQ -376%; 2026-01-16->20
  SPY -189%; 2026-06-22->23 QQQ -192%. Strangles: SPY -1072% / QQQ -797% on 2025-01-27 (premium ~$150, put went to
  intrinsic $10.8 / $16.5). Reconciled against the stock gap (SPY -1.75%, QQQ -3.06%) — the exits are intrinsic, not
  stale prints.
- P5 of nightly returns: straddle -57% to -78%; strangle -104% to -128%. Win rate 59-68%, median +5-7% (straddle) /
  +11-19% (strangle): the classic positive-skew-sold profile with a negative mean.
- Max drawdown of cumulative net P&L per $1 of max premium sold: 2.2-6.3x (worst QQQ TEST 6.0-6.3x).
- Weekend (Fri->Mon, holiday) vs weekday nights: FIT weekend nights are much worse (SPY straddle -9.2% vs -1.9%, QQQ
  -10.3% vs -2.9%, strangles -15 to -20%); TEST SPY weekends +3.6%/+6.5% vs weekdays -1.6%/-0.4%, QQQ weekends -1.3%/-2.2%
  vs weekdays -4.7%/-6.2%. No stable weekend premium either way (n = 67-76 weekend nights per half).
- Event exclusion (FOMC/CPI next day) makes things slightly WORSE, not better (25 TEST nights removed).

## Full tables (all stats; second table = sensitivities and the M-N comparison)
| variant | half | n | mean ret/prem | 95% CI (nights) | t | max-t p (2s/1s) | win | median | P5 | worst (date) | maxDD/maxprem | med prem $ | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SPY straddle all | FIT | 346 | -3.48% | [-7.32%, +0.12%] | -1.82 | - | 61% | +5.52% | -69.72% | -342.59% (2025-01-24) | 3.64 | 362 |  |
| SPY straddle all | TEST | 327 | -0.54% | [-4.14%, +2.88%] | -0.30 | 0.964/0.766 | 64% | +6.91% | -56.54% | -188.62% (2026-01-16) | 2.59 | 404 | fail |
| QQQ straddle all | FIT | 345 | -4.53% | [-8.70%, -0.90%] | -2.32 | - | 64% | +5.34% | -74.51% | -375.97% (2025-01-24) | 5.32 | 421 |  |
| QQQ straddle all | TEST | 324 | -3.96% | [-7.69%, -0.42%] | -2.13 | 0.076/0.997 | 59% | +5.27% | -75.53% | -191.63% (2026-06-22) | 6.01 | 553 | fail |
| SPY straddle exEvent | FIT | 319 | -4.01% | [-8.13%, -0.26%] | -1.98 | - | 61% | +5.39% | -71.83% | -342.59% (2025-01-24) | 3.69 | 352 |  |
| SPY straddle exEvent | TEST | 302 | -0.86% | [-4.77%, +2.77%] | -0.45 | 0.892/0.810 | 64% | +6.92% | -56.81% | -188.62% (2026-01-16) | 2.29 | 396 | fail |
| QQQ straddle exEvent | FIT | 318 | -5.04% | [-9.29%, -1.17%] | -2.43 | - | 64% | +5.29% | -75.84% | -375.97% (2025-01-24) | 5.36 | 407 |  |
| QQQ straddle exEvent | TEST | 299 | -4.33% | [-8.33%, -0.46%] | -2.17 | 0.071/0.998 | 59% | +5.26% | -77.84% | -191.63% (2026-06-22) | 6.21 | 548 | fail |
| SPY strangle all | FIT | 345 | -6.44% | [-16.29%, +1.66%] | -1.43 | - | 65% | +13.41% | -124.86% | -1071.74% (2025-01-24) | 3.36 | 151 |  |
| SPY strangle all | TEST | 326 | +1.02% | [-6.01%, +7.76%] | +0.28 | 0.970/0.547 | 68% | +19.04% | -107.63% | -416.75% (2026-01-16) | 2.53 | 148 | fail |
| QQQ strangle all | FIT | 343 | -6.14% | [-13.54%, +0.23%] | -1.74 | - | 65% | +11.45% | -103.94% | -796.58% (2025-01-24) | 4.73 | 228 |  |
| QQQ strangle all | TEST | 324 | -5.34% | [-11.78%, +0.93%] | -1.67 | 0.181/0.988 | 60% | +11.97% | -124.58% | -307.24% (2026-06-22) | 5.99 | 284 | fail |
| SPY strangle exEvent | FIT | 318 | -7.47% | [-17.73%, +0.96%] | -1.55 | - | 65% | +13.51% | -125.96% | -1071.74% (2025-01-24) | 3.44 | 140 |  |
| SPY strangle exEvent | TEST | 301 | +0.32% | [-7.57%, +7.67%] | +0.08 | 1.000/0.629 | 68% | +19.24% | -109.57% | -416.75% (2026-01-16) | 2.17 | 144 | fail |
| QQQ strangle exEvent | FIT | 316 | -6.95% | [-14.90%, -0.26%] | -1.86 | - | 66% | +11.55% | -105.35% | -796.58% (2025-01-24) | 4.77 | 217 |  |
| QQQ strangle exEvent | TEST | 299 | -5.93% | [-12.87%, +0.49%] | -1.73 | 0.164/0.990 | 59% | +12.15% | -127.62% | -307.24% (2026-06-22) | 6.29 | 278 | fail |

| variant | half | 2x spread mean [CI] | missing-exit@intrinsic-LB n / mean [CI] | short gross (no cost) | long unhedged (M-N raw) | long delta-hedged gross (M-N) | weekend n / mean | weekday n / mean |
|---|---|---|---|---|---|---|---|---|
| SPY straddle all | FIT | -6.50% [-10.59%, -2.89%] | 349 / -3.07% [-7.14%, +0.53%] | -0.39% | +0.76% | +0.39% | 76 / -9.19% | 270 / -1.88% |
| SPY straddle all | TEST | -3.53% [-7.26%, -0.08%] | 327 / -0.54% [-4.16%, +2.88%] | +2.51% | -2.69% | -2.51% | 68 / +3.57% | 259 / -1.62% |
| QQQ straddle all | FIT | -7.65% [-11.89%, -3.86%] | 348 / -4.07% [-8.13%, -0.46%] | -1.43% | +1.19% | +1.43% | 75 / -10.30% | 270 / -2.93% |
| QQQ straddle all | TEST | -7.20% [-11.11%, -3.52%] | 326 / -3.80% [-7.55%, -0.27%] | -0.76% | +0.93% | +0.76% | 67 / -1.30% | 257 / -4.66% |
| SPY straddle exEvent | FIT | -7.06% [-11.42%, -3.13%] | 322 / -3.56% [-7.73%, +0.24%] | -0.88% | +1.21% | +0.88% | 76 / -9.19% | 243 / -2.39% |
| SPY straddle exEvent | TEST | -3.91% [-7.89%, -0.13%] | 302 / -0.86% [-4.82%, +2.80%] | +2.24% | -2.45% | -2.24% | 68 / +3.57% | 234 / -2.15% |
| QQQ straddle exEvent | FIT | -8.19% [-12.75%, -4.15%] | 321 / -4.53% [-8.93%, -0.70%] | -1.89% | +1.59% | +1.89% | 75 / -10.30% | 243 / -3.41% |
| QQQ straddle exEvent | TEST | -7.61% [-11.79%, -3.66%] | 301 / -4.15% [-8.11%, -0.47%] | -1.09% | +1.30% | +1.09% | 67 / -1.30% | 232 / -5.21% |
| SPY strangle all | FIT | -11.21% [-21.79%, -2.73%] | 347 / -5.95% [-15.41%, +2.06%] | -1.63% | +2.90% | +1.63% | 76 / -19.93% | 269 / -2.63% |
| SPY strangle all | TEST | -2.97% [-10.42%, +3.96%] | 326 / +1.02% [-6.19%, +7.87%] | +5.19% | -5.62% | -5.19% | 68 / +6.47% | 258 / -0.42% |
| QQQ strangle all | FIT | -10.23% [-18.10%, -3.65%] | 346 / -5.26% [-12.66%, +1.17%] | -2.16% | +2.33% | +2.16% | 73 / -15.32% | 270 / -3.66% |
| QQQ strangle all | TEST | -9.36% [-15.77%, -3.09%] | 325 / -5.27% [-11.68%, +0.66%] | -1.40% | +1.34% | +1.40% | 67 / -2.24% | 257 / -6.15% |
| SPY strangle exEvent | FIT | -12.39% [-23.72%, -3.22%] | 320 / -6.94% [-17.30%, +1.65%] | -2.51% | +3.78% | +2.51% | 76 / -19.93% | 242 / -3.56% |
| SPY strangle exEvent | TEST | -3.78% [-12.00%, +3.78%] | 301 / +0.32% [-7.61%, +7.57%] | +4.61% | -4.89% | -4.61% | 68 / +6.47% | 233 / -1.48% |
| QQQ strangle exEvent | FIT | -11.12% [-19.84%, -4.03%] | 319 / -5.99% [-13.98%, +0.77%] | -2.88% | +3.10% | +2.88% | 73 / -15.32% | 243 / -4.43% |
| QQQ strangle exEvent | TEST | -10.00% [-17.14%, -3.18%] | 300 / -5.84% [-12.65%, +0.55%] | -1.94% | +1.95% | +1.94% | 67 / -2.24% | 232 / -7.00% |


## Data integrity (the things the task said to be skeptical about)
- Bars: 513,822 1m bars, 5,461 contracts, one request per session (D 15:00 ET -> D+1 09:50 ET) so bars from other
  dates cannot leak in. Explicit check: 104 bars fell outside their night's {D, D+1} — all on **2024-06-01, a
  Saturday** (the NYSE industry-wide T+1 test session), attached to 2024-06-03 expiries. Test prints; dropped. SPY/QQQ
  option bars run to 16:14 (options close 16:15), as expected.
- Expiry = next stock session for every contract (asserted). Half-days have no 15:55 bar and are skipped as entry days.
- Coverage: 2,732 rows (683 sessions x 2 ETFs x 2 structures) -> 2,680 usable. Dropped: 2024-02-02 (no option bars at
  all in Alpaca that day, so 02-01 has no exit and 02-02 no entry), 2024-02-23 and 2024-03-14 exits, half-days
  (07-03, 11-29, 12-24 x2 years) with no option prints after 13:00, and 12 scattered single legs. Entry taken from the
  exact 15:55 bar in all but 2 rows (15:50-15:55 fallback); exit from the exact 09:35 bar in all but 11 rows (09:36-09:45
  fallback, listed in the run log). Nothing was ever filled with the entry price. Robustness row "missing exit at
  intrinsic lower bound" (optimistic for the short) adds <=3 nights per variant and moves means by <0.5 pt — the
  drop policy is not what produces the fail.
- 15:55 vw staleness: the entry bar has median 50 trades (p5 = 14) on straddle legs; |vw/c - 1| median 1.8%, p90 5%
  (that is intra-minute price movement on $0.5-3 options, not staleness). The 09:35 exit bar is the first full minute
  after the opening rotation; its stress-night prices equal intrinsic, consistent with real quotes.
- IV back-out succeeded on every row (median 1-DTE ATM IV: SPY 13-14%, QQQ 19%). Hedge: median |shares| 3.5, max 16.5
  (ATM straddle delta ≈ 0); hedged vs unhedged gross std 0.334 vs 0.332, corr(option P&L, -hedge P&L) = -0.04, i.e.
  the hedge is immaterial at this moneyness and the result is the same unhedged.
- Spread model: Roll half-spread from the contract's own 1m closes (within-date lag-1 autocov), floored at
  max(1% of price, $0.005). Binding at the floor on 54% of legs. For SPY ATM at ~$2 that is a $0.02 half-spread, in
  line with the real $0.01-0.03 book; the 2x row (which turns every variant decisively negative) brackets it.
- Stock cost: 1 bp per leg on hedge notional (median $2.1k) — < $0.50/night vs $150-550 premium; immaterial.

## Multiplicity
Round-13 ledger: 8 variants here (ledger now ~488). Studentized max-|t| across the 8 on TEST with joint resampling of
the 327 TEST session dates: smallest family-wise p = 0.07 (QQQ straddle exEvent) and it is on the NEGATIVE side
(one-sided p for the predicted positive sign = 0.998). No variant is close to passing in the predicted direction.

## What would change my mind
A gross (pre-cost) positive short return in BOTH halves with CI excluding 0 would be needed before costs even matter;
the gross numbers are -1.4%..+2.5% with t < 1.5 in every cell. The 2x-spread and intrinsic-LB sensitivities move in the
wrong direction or not at all. I would not re-test this on SPY/QQQ 1-DTE; if anything, the data say 1-DTE overnight
premium is fairly priced and the short pays the spread for nothing. A different, un-preregistered mechanism (e.g. the
paper's INTRADAY positive leg) is a separate hypothesis and is not claimed here.

## Files (all under wl/r13/h131/)
- RESULT.md (this), AMENDMENTS.md, cpi_dates.txt, results.csv, tables.md, nights.parquet, sessions.parquet
- 01_sessions.py (spot table + OCC list + jobs), 02_build_nights.py (bars -> nights, diagnostics), 03_eval.py (stats)
- fetch_jobs.sh / fetch_remote_jobs.py (per-session Alpaca fetch, same creds path as wl/fetch.sh), jobs.txt
- bars/h131_1dte_bars.jsonl.gz (513,822 bars, 9.7 MB), bars/fetch.log

## Amendments (verbatim from AMENDMENTS.md, written before outcomes)
below pin down things the prereg left open or that the data forced, written down before the first P&L was looked at.

A1. Fetch tooling. One Alpaca option-bars request per session (D 15:00 ET .. D+1 09:50 ET, the 8 contracts of that
    night) via fetch_jobs.sh/fetch_remote_jobs.py (same creds path as wl/fetch.sh). A date-range fetch over all
    ~5,400 contracts would also return every pre-expiry listing day (tens of millions of bars). No analytic effect;
    the window also guarantees no bars from other dates can leak in (checked anyway in the code).
A2. Stock hedge cost: 1 bp of share notional on EACH leg (open at 15:55, unwind at 09:35) as instructed by the caller
    — 2 bp round trip, i.e. more conservative than the prereg's "1 bp round trip". Immaterial (hedge notional is a
    few $k vs ~$300-700 premium).
A3. Missing option bars (never filled with the entry price):
    - Entry leg: needs the D 15:55 bar (vw if present and >0, else c). If absent, use the LATEST bar in 15:50..15:55;
      if none, the night is dropped for that variant. Count reported.
    - Exit leg: needs the D+1 09:35 bar (vw else c). If absent, use the EARLIEST bar in 09:35..09:45; if none, the
      night is dropped for that variant (primary). Robustness row: value a missing exit leg at max(intrinsic at the
      09:35 spot, 0) plus the half-spread (a LOWER bound on the buy-back, i.e. optimistic for the short) to bound
      the drop-bias. Nights with no bars at all on D+1 (e.g. 2024-02-02 is entirely absent from Alpaca) are dropped
      and counted.
A4. Half-spread per leg: Roll estimate from that contract's own 1m closes in the fetched window (D 15:00-16:14 and
    D+1 09:30-09:49; lag-1 autocovariance of price changes within each date, never across the overnight gap):
    roll_half = sqrt(-cov) if cov < 0 else 0. Dollar half-spread applied = max(roll_half, 1% x leg price at that
    time, $0.005 = half a penny tick). Entry legs sold at price - hs, exit legs bought at price + hs. Sensitivity: 2x.
A5. IV / delta. Straddle: ONE IV solving BS_call + BS_put = straddle price at 15:55 (r = 4%, q = 0, T = calendar
    time from D 15:55 ET to expiry D+1 16:00 ET, in years/365). Strangle: the prereg's "15:55 IV" is ambiguous for
    two different strikes, so each leg's delta uses the IV backed out of that leg's own price (standard skew-aware
    delta). Hedge shares = (delta_call + delta_put) x 100 per spread, opened at the stock 15:55 close, unwound at
    the 09:35 close.
A6. Spot. "15:55 spot" = the stock 15:55 1m bar CLOSE (the 2026-06-15+ parquet has no vw, so c is used throughout
    for consistency). ATM strike = round(spot) ($1 grid); strangle strikes = round(spot x 1.005) call,
    round(spot x 0.995) put.
A7. Metric denominator: premium actually received = sum over legs of (price - hs) x 100.
A8. Event exclusion: drop a night if D+1 is in fomc-dates.txt or cpi_dates.txt (the prereg's "nights before
    FOMC/CPI release days"). CPI list: cpi_dates.txt, fixed from the BLS schedule as recalled (bls.gov blocks bots
    from this host; FRED/ALFRED unreachable) — flagged as unverified there.
A9. Pass rule operationalization. Nights are the clusters (no overlap, so cluster bootstrap = resampling nights).
    10,000 percentile-bootstrap resamples of the mean, seed 13. FIT: mean > 0 and 95% CI excludes 0. TEST: mean > 0,
    95% CI excludes 0, AND studentized max-|t| family-wise p < 0.05 across the 8 variants (joint resampling of
    TEST session dates so SPY/QQQ and all/ex-event variants of the same night move together). One-sided max-t
    p-values are reported alongside but the two-sided one is the gate (consistent with "CI excludes 0").
A10. Half-days (13:00 close) have no 15:55 bar and are skipped as entry days automatically; the following session
    is still a valid exit day for the previous night.
