# H9.1 AUTHOR CONSENSUS: RESULT = FAIL (and the test is badly underpowered: n_AGREE = 1 per half)

Pre-registration: wl/PREREG9.md (H9.1). Code: r9/h91/h91.py. Raw signals: r9/h91/bto_signals.psv (a SELECT-only pull
of audit_log SignalReceived rows with action=BTO, one row per signal_id, earliest occurrence kept). Full output: out.txt
(primary run) and out_incl_manual.txt (sensitivity run).

## Data
- Base: events.parquet kind=real, fired=1, gap=0 gives 309 legs over 80 days (FIT 159 / TEST 150).
- Placebo: kind=placebo, fired=1, gap=0 gives 2,793 legs (FIT 1,481 / TEST 1,312). Each placebo leg is tagged with its own d and fire_min.
- Trigger time = d 09:30 America/New_York + fire_min. Signal time = posted_at (UTC). Window = [trigger-24h, trigger].
- Independent BTO signals (TradingTheTrend excluded), 25 total: TB22 7, Lars 5, beendoubleyou 5, sloth legooman NR100 5,
  Shakira T 2, Donald T 1. For comparison, TradingTheTrend posted 98 BTOs, which were excluded.

## Amendments and deviations (all made without looking at outcomes, except A4, which only describes what the data contained)
- A1: Operator hand entries are excluded from the primary run. These are 7 BTOs whose signal_id starts with `manual:` or
  whose author is `tenant:*` or `ridopark`. They are not a copytrade author, and they may themselves come from the watchlist
  (there is a Buy-from-watchlist UI, #976), so they are not independent. The sensitivity run includes them. It leaves the
  real-leg tags unchanged and adds 1 placebo AGREE leg to each half.
- A2: The pre-registration does not cover a window that holds both a same-direction and an opposite-direction BTO. I coded
  that case as a separate BOTH tag. It never occurred.
- A3: 52 SignalReceived rows have a null author and a null action. The BTO filter drops them.
- A4: No real leg is DISAGREE in either half, so AGREE-DISAGREE cannot be computed. The rule requires that contrast to
  be > 0, so with no DISAGREE legs that leg of the rule is not met.

## Tag counts (real legs)
| half | AGREE | DISAGREE | SILENT |
|---|---|---|---|
| FIT  | 1 | 0 | 158 |
| TEST | 1 | 0 | 149 |

Placebo legs: FIT AGREE 8 / DISAGREE 5 / SILENT 1,468; TEST AGREE 4 / DISAGREE 2 / SILENT 1,306.

## Mean return per tag (bps, signed in the leg direction)
| half | tag | n | days | r60 | rclose |
|---|---|---|---|---|---|
| FIT  | AGREE  | 1   | 1  | -17.0 | -54.0 |
| FIT  | SILENT | 158 | 44 | -6.2  | -6.7 |
| TEST | AGREE  | 1   | 1  | -12.2 | +21.1 |
| TEST | SILENT | 149 | 36 | -4.5  | +1.7 |

## Contrasts (bps, 95% CI from resampling days, B=4000)
| half | metric | AGREE-SILENT | AGREE-DISAGREE |
|---|---|---|---|
| FIT  | r60    | -10.8 [-26.6, +5.8]  | n/a (no DISAGREE) |
| FIT  | rclose | -47.3 [-72.8, -22.9] | n/a |
| TEST | r60    | -7.7 [-29.0, +9.3]   | n/a |
| TEST | rclose | +19.4 [-8.5, +43.9]  | n/a |

These CIs do not mean much. With one AGREE leg on one day, a resample has an AGREE value only when it draws that day, so
the interval mostly reflects the spread of the SILENT mean around a single fixed number. That is why FIT rclose looks
"significant": the one leg sits below the SILENT distribution. It is not evidence.

## Placebo comparison
- Placebo AGREE-SILENT, r60: FIT -31.6 [-88.0, +18.3] (8 legs on 3 days); TEST -30.9 [-65.8, +4.5] (4 legs on 2 days).
- Placebo AGREE-DISAGREE, r60: FIT -17.9; TEST -81.3.
- Real AGREE minus placebo AGREE, r60: FIT +12.5, TEST +15.1. Difference-in-differences of (A-S): FIT +20.8, TEST +23.2.
- Read: the real AGREE leg beat the placebo AGREE legs only because the placebo AGREE legs were even more negative.
  Placebo legs carry no watchlist information, so a consensus effect would show up as real AGREE > SILENT. Both real
  AGREE legs are below SILENT. Real rclose differences against placebo flip sign between halves (FIT -221, TEST +78).

## Pass rule (r60)
- FIT: AGREE-SILENT = -10.8. It is not > 0 and its CI includes 0, so this part fails. AGREE-DISAGREE is not computable,
  so that part fails too.
- TEST: AGREE-SILENT = -7.7. It is not > 0 and its CI includes 0, so this part fails. AGREE-DISAGREE is not computable,
  so that part fails too.
- **H9.1: FAIL.** The sensitivity run with operator entries included gives the identical real-leg result and also FAILs.

## AGREE legs and who posted them (real)
| half | d | ticker | dir | fire_min | author | r60 bps | rclose bps |
|---|---|---|---|---|---|---|---|
| FIT  | 2026-07-01 | MSFT | ABOVE | 97  | TB22 | -17.0 | -54.0 |
| TEST | 2026-09-21 | AMZN | ABOVE | 181 | TB22 | -12.2 | +21.1 |

Per author: TB22 2 AGREE legs (1 per half). Lars, beendoubleyou, sloth, Shakira T and Donald T: 0 AGREE legs.

## Honest bottom line
- The independent copytrade authors are nearly silent. They posted 25 BTOs in 5 months, and most are on tickers
  outside the watchlist (PG, PFE, BAC, HAL) or outside the 24h window. As a result, 2 of 309 legs (0.6%) get a
  non-SILENT tag.
- The pre-registered power note puts the MDE at about 50 bps for a 15% subset. A 0.6% subset is far below anything
  testable. So this result is "uninformative, and failed as specified", not "consensus refuted".
- The two observations we do have point the wrong way on r60: both AGREE legs are below SILENT.
- Forward use: at about 0.5 tagged legs per month, a shadow scorer would need years to reach n ≈ 30. Do not freeze H9.1 as
  a survivor. If it is logged at all, label it "unsupported, n=2".
