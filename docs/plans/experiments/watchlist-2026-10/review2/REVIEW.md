# Independent review, round 2 (R1–R7)

All numbers come from my own code in `review2/`:
- `sr_re.py` and `sr_re_eval.py`: an independent re-run of the S/R study (R1/R2).
- `piv5_robust.py`, `brk_strat.py`: S/R robustness checks.
- `r3_check.py`, `r4_drift.py`, `r4_vr.py`: R3 and R4.
- `r5_trail.py`, `r5_bidzero.py`, `r5_legs.py`: R5.
- `alloc/*`: the P&L rebuild (R6/R7), done by a sub-agent and spot-checked by me (`intc.py`).

Units are bps of the underlying. Bootstraps are clustered by day unless stated.

The original `sr_eval.py` tables for `sr_decade.parquet` and `sr_author.parquet` reproduce exactly.

## Verdicts

| Claim | Verdict | One line |
|---|---|---|
| R1 | **WEAKENED** | No definition shows more *bounce*. But D2 volume-profile shows **fewer breaks** than placebo in both decade halves, which passes the pre-registered rule. The effect is tiny. |
| R2 | **HOLDS for direction. WRONG on size.** | Real follow-through measured from the touch is **−5.5 to −6.6 bps**, not 1–2. The "1–2 bps" is measured from the level and includes about +4.3 bps of mechanical offset. |
| R3 | **HOLDS (numbers), inconclusive** | Reproduced. The effective n is 22 days. No code produces it. |
| R4 | **WEAKENED** | Drift reproduces, but the CIs are about ±15 bps. VR has no code; I get 1.15 [0.79, 1.59]. "Cannot add EV" only holds for linear payoffs, not options with theta drag. |
| R5 | **WEAKENED** | The direction reproduces, but no difference is significant. The hold mean rests on one +703% trade. The author doesn't trade 0DTE. |
| R6 | **HOLDS, with a correction** | +$34.18k reproduces. Through 08-19 it is **+$9.3k**, not ~+$8k. A day-clustered CI makes the per-trade mean much wider. |
| R7 | **WEAKENED** | Kelly = 0 is a lower-bound clip. "L dominates drawdown" is true by construction. The OOS split is about 20 signals per half. |

### R1
- **Bounce is near zero everywhere.** Measured from the touch-bar close (`f30`), real-vs-placebo bounce for D0 PDH/PDL, D0 PMH/PML and D4 round is between −2.2 and +2.8 bps.
  - The only CI that excludes 0 is D0_pdhl FIT, at −2.2, and that is less rejection, not more.
  - This holds for both the original ±0.3–1% placebo and my "xday" placebo. The xday placebo uses the same relative distance from price at activation, on a random other day of the same symbol and period.
- **But the break metric fails the claim.** On the original data, D2_vprofile real−placebo P(break) is −1.48pp [−2.3, −0.6] in FIT and −1.37pp [−2.2, −0.6] in TEST.
  - Stratifying by touch time doesn't change it: −1.61 and −1.45, both CIs excluding 0.
  - PREREG4's PASS rule is "bounce30 (or break-continuation) same sign, CI excludes 0 in both halves", so D2 passes on breaks. D3_vwap_static FIT is −2.3pp but not significant in TEST.
- **Pooling hides a PMH/PML effect.** When PMH/PML are split out from PDH/PDL, they break 2–3.6pp less than the xday placebo in all three periods. Against the original placebo it is −2.3pp in FIT only. This is a post-hoc split, so treat it as a hypothesis.
- **Economically it is negligible.** There are about 1.5pp fewer breaks on a ~50% base rate, and no bounce. "S/R gives no tradable rejection edge" stands. "No definition differs from placebo" does not.
- **Day clustering vs (symbol, day) clustering** changes the CIs by less than 0.2 bps, so cross-symbol correlation is not an issue here.
- **Raw prices across splits do no real harm.** Unadjusted prices are confirmed: −75% to −95% opening gaps on all 7 split dates. But the `within(3%)` filter drops stale pre-split levels, so the only loss is a few touches in the first days after each split (counts in `splits.py`). No false touches are created.

### R2
- **Size.** Real `f30` from the touch close is −5.5 [−6.3, −4.7] in FIT, −6.6 [−7.8, −5.3] in TEST and −4.1 [−6.9, −1.6] in 2026. From the next-bar open (`g30`) it is the same, so it is not bid-ask bounce. Placebo `f30` is about 0.
- **Consistency.** The sign is negative for 12/12 symbols and 11/11 years, every time-of-day bucket is −4.7 to −9.2, and the day-level t is −17.
- **Not look-ahead.** The touch index is a stopping time: the pivot is confirmed by bar i+6 and activation is at (idx+7)×5. So E[f30] = 0 under a martingale, and this is real non-martingale behaviour: continuation when price retests a recent 65-minute extreme.
- **Placebo offset bias.** The original b30 (from the level) is `f30` plus the touch offset. The offset is +4.2 to +4.4 bps for real levels and +4.7 to +4.8 for placebos, so the placebo design also biases real−placebo b30 by about −0.5 bps.
- **Break rate** is higher than placebo in all 3 periods in my run (+1.6, +3.5, +3.0pp). In the original run 2026 is not significant.
- **Before believing it,** test it as a stock strategy with costs (see the last section). 5–6 bps gross over 30 minutes is not "nothing" for SPY/QQQ.

### R3
- 114 real swing touches reduce to 118 (symbol, day) clusters across both line types, and only **22 distinct days**. The CIs are b30 [−8.7, +16] and break [−18, +1.4]pp, so the test can only detect very large effects. "Not significant" should not be read as "no effect".
- **No script in the folder produces `sr_author.parquet`.** There is also no level column, so possible look-ahead can't be checked: 16 of 185 real touches fall on a post day *before* that day's post. They are fine only if the same level was posted on an earlier day.
- The LLM's confidence labels are uninformative (523 of 527 are "high"), and 86 lines are `paren_unverified`.

### R4
- **Drift reproduces.** For real fired, non-gap legs (n=309, 80 days): r5 −2.2, r30 −4.2, r60 −3.2, rclose −11.8. All CIs are about ±10–25 bps, so "no drift" means "|drift| below about 15 bps can't be detected".
- **VR has no code anywhere.** My computation (squared 30m/60m return ÷ summed squared 1m returns, day-clustered):

  | | VR30 | VR60 |
  |---|---|---|
  | Real | 1.15 [0.79, 1.59] | 1.04 [0.80, 1.27] |
  | Placebo | 1.08 | 1.15 |

  These intervals can't rule out real trending or mean reversion.
- **The inference is too strong.** "No drift ⇒ a trail can't add EV" is optional stopping, which applies to a linear P&L. An option held to expiry has negative drift: the median hold in R5 is −33% to −73%. Any rule that exits early can then beat holding in expectation. R5 is the right test, but it is underpowered.

### R5
I re-simulated independently with no `i<30` filter, so all 26 triggers are included. I used both the original strike and an ATM strike, first and last quote in each minute, and a time-matched random control (±30 min, same contract).

| | Original strike | ATM strike |
|---|---|---|
| Hold mean | +0.19 [−0.49, +0.97] | +0.19 [−0.41, +0.86] |
| Hold median | −0.73 | −0.33 |
| trail10 − hold | −0.23 [−1.05, +0.43] | −0.24 [−0.93, +0.33] |
| trail20 − hold | −0.19 [−1.03, +0.49] | −0.21 [−0.91, +0.39] |
| trail30 − hold | −0.29 [−1.13, +0.38] | −0.31 [−0.98, +0.28] |

- No trail differs significantly from holding.
- **One trade carries the hold mean.** In the original 18 trades, a single +703% trade (SPY 769P on 10-16) produces the whole positive mean; without it the mean is about −24%. The top-2 trades are 1.9–2.4× the total.
- **The random control is noise.** Time-matched random entries have hold means of +0.9 to +1.4, far above the author's +0.19. So "no trail beats random" carries no information.
- **Wrong instrument.** The author's SPY/QQQ trades in `author/build_trades.py` (9/28 780c, 10/02 770c, 10/12 750p) were opened 3–6 days before expiry. The collector has only 0DTE quotes, so the pre-registered next-expiry arm (6 of 12 variants) silently produced nothing. R5 tests an instrument he doesn't trade.
- Entry at ask and exit at bid is symmetric and correct. The median spread is 0.7–0.9%.

### R6 (sub-agent rebuild, checked by me)
- **The total reproduces.** A FIFO rebuild from the fills gives +$34,179 vs the claimed +$34,181. The 126 closed trades sum to $33,109; the other $1,072 comes from 4 positions that are still partly open.
- **Through 08-19:** sells alone are +$35,522, and expired leftover lots are −$26,216, which nets to **+$9.3k**.
- **The sub-agent's upside is wrong.** It argued the INTC 08-03 95C lots (−$13.4k) were "broker-confirmed flat" before expiry, so the true figure could be up to +$22.7k. The live lots did leave the account by unknown means. But the paper twin of the same order sold at **$0.01** at 14:15 ET, and INTC's high that day was 91.68 against a 95 strike. So the 0 mark is right to within about $0.5k.
- **Still unexplained:** META 07-27 590P (−$10.8k) and two NVDA lots (−$2.0k). The audit log goes silent after the last partial sale.
- **The per-trade CI is too narrow.** It reproduces at [−4.7%, +15.1%] when trades are resampled individually. But the four accounts copy the same signals (return correlation 0.97 between the two main accounts):

  | Resampling unit | Mean return CI | Total $ CI | P(total < 0) |
  |---|---|---|---|
  | Trade | [−4.7%, +15.1%] | | |
  | Exit day | [−11.8%, +23.2%] | [−$69k, +$145k] | 0.27 |
  | Signal | [−10.9%, +22.1%] | | 0.27 |

- **Concentration:** the top-3 trades sum to $34,454, more than the total. Without the top-3 the total is −$1.3k.
- **What n supports:** about 35–60 independent bets. That can show a heavy right tail, but it cannot tell a +5% edge from zero.

### R7
- **Kelly = 0 is a clip, not an estimate.** The search runs over f ∈ [0, 0.999] (`analyze.py:137-143`). The unconstrained optimum is −0.06 and the bootstrap upper bound is 0.44.
  - The sub-agent's point that the sign flips without the INTC lots does not hold, because those losses are real (see R6).
- **"L dominates drawdown" is built in.** Drawdown is computed from returns × (weights × L) with weights summing to 1, so it must rise with L.
- **The OOS split is about 40 vs 41 days** (about 20 signals per half), with a regime flip: −0.3% return on cost in the first half, +16.9% in the second. Every scheme was positive OOS on the pooled series. "Not robust" really means "can't be identified at this n".

## Bugs

| file:line | bug | impact | changes conclusion? |
|---|---|---|---|
| sr_react.py:22 | Bounce measured from the level, not the entry. Mechanical offset ≈ +4.3 bps; placebo offset is about 0.5 bps larger. | R2 absolute size understated ~4×. Real−placebo b30 biased about −0.5 bps. | **Yes, for R2's size** |
| sr_react.py:57-60 | 1h pivots computed inside each session (7 hourly bars, k=3), so only bar 3 can qualify. PREREG asks for pivots over 5 sessions. | D1_1h is really "midday session extreme", n=1956. | Yes for D1_1h only; not tested as specified |
| sr_react.py:87 | Placebo levels are not excluded from other real levels; e.g. a PDH placebo can land on PMH. | Dilutes the contrast toward 0. | No (lowers power) |
| sr_react.py:28 | `hash(sym)` seed is randomized per interpreter. | Placebos are not reproducible across runs; the saved parquet is. | No |
| sr_react.py:51 / sr_eval | PDH/PDL and PMH/PML pooled as D0. | Hides a PMH/PML break-suppression signal (post-hoc). | Weakens R1 |
| sr_eval.py:110 | Clusters by (symbol, day), not day. | CIs change by less than 0.2 bps. | No |
| (missing) | No code for `sr_author.parquet` or the variance ratio. | R3 and R4's VR can't be audited. | Unverifiable |
| trail_bid.py:166 | The `i<30` lookback filter (needed only for vol trails) is applied to every trail. | Drops 8/26 triggers, including all those firing before ~10:00. | No (my n=26 gives the same picture) |
| trail_bid.py:164 | Random control is uniform 10:00–15:00, not time-matched, and drawn over all 26 contracts (130 rows vs 18×5). | Control isn't comparable; time-matched control is noise (+0.9 to +1.4). | Removes the "no trail beats random" claim |
| trail_bid.py:157 | Next-expiry arm: the collector has 0DTE only. | 6 of 12 pre-registered variants are empty, silently. | The author's real DTE is untested |
| trail_bid.py:135 | Quotes with bid ≤ 0 are dropped, so the hold exit becomes the last positive bid. | 1/18 paths; impact about 0. | No |
| build_roundtrips.py:149-153 | Leftover lots past expiry with no expiry event are marked −100% even when "broker-confirmed flat". | INTC is about right (paper fill $0.01). META −$10.8k and NVDA −$2.0k are unverified. | No for INTC; open for META |
| analyze.py:137-143 | Kelly search bounded at 0. | Reports 0 where the optimum is about −0.06. | Wording only |
| analyze.py:146,217 | Trade-level bootstrap ignores account copies; per-trade Kelly f used as daily exposure. | CIs too narrow; units mismatched. | Weakens R6/R7 precision claims |

Housekeeping: I mistakenly wrote one duplicate script outside the workspace, at `/tmp/tmp/claude-1000/.../wl/review2/splits.py`. Deleting it needed approval I didn't have, so please delete it.

## What to test next
1. **5m-pivot continuation as an equity strategy.** Treat it as new pre-registered work:
   - Rules: enter at the next-bar open in the break direction on a pivot retest, exit at +30m, with SPY/QQQ spread and fees.
   - Report: per-day Sharpe; overlap handling (clusters of touches); FIT→TEST→2026.
   - Variants: pivot width k ∈ {3, 6, 12}.
   - Check: whether it is just "new N-minute high" momentum, by comparing against rolling-max levels that are not pivots.
2. **Report every S/R outcome from the entry price.** Re-state R1/R2 with `f30`/`g30`, include the break metric in the PASS table, and separate PMH/PML from PDH/PDL (pre-register it this time).
3. **Re-run R5 on the author's real instrument (3–6 DTE),** once the collector stores non-0DTE quotes. Use a time-matched control and report trail−hold with a paired CI. At least 60–100 triggers are needed to tell a ±20% difference apart.
4. **Find the actual exits of the META 07-27 590P and NVDA leftover lots** from the broker account activity (not the system journal), then recompute the through-08-19 figure.
5. **Commit the missing generators** (author-line touches with the level and post timestamp per touch; variance ratio) so R3 and R4 can be audited.
