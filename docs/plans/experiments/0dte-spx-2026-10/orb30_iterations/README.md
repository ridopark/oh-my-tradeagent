# ORB30 iteration log (2026-10-04)

**Goal set by the operator:** a 0DTE strategy that wins at least 55% of the time.

**Success criteria (fixed before iterating):**
- win rate ≥ 55%;
- stress-cost mean > 0;
- dev t ≥ 2, with both halves positive;
- then the same in a LOCKED holdout, 2026-04-01..2026-10-02.

DEV = 2024-03..2026-03 for options. Filter selection used SPY FIT 2016-20 only; TEST was 2021-26Q1.

| Iter | Change | Result |
|---|---|---|
| IT1-2 | 15:00 call credit spread (abandoned at operator request) | lucky cell, neighbors negative |
| A1 | ORB30 baseline, SPY 2016-26Q1: +X before −X | 48.7-51.2%, a coin flip |
| A2 | 6 single filters (gap, range, volume, strength, VWAP, timing) | none ≥ 55% in both periods; effects of 2-4 pts |
| A3 | Score = #{weak breakout bar, wide OR vs 20d, breakout ≥ 20 min after 10:00, gap ≥ 42.5 bp}; FIT-terciles | score==2: 55-59% both periods, 60m ret > 0 in all 11 years. Non-monotonic: score 3/4 not better (warning) |
| A4 | options ATM/OTM1, symmetric ±20/30 bp, 60m | +9%/trade (t 1.9) but win 44-48%; 60m timeouts lose to theta |
| A5 | ITM10, 120m | win 50%, +7.6% |
| A6 | asymmetric exits on SPY: T15/S30, T20/S30 | score2 win 61-66% vs ALL 56-64%; EV > 0 both periods |
| A7 | options score2, ITM10, T15/S30, 120m | **DEV PASS:** win 67.7%, +6.0% (t 2.06), halves +6.2/+5.8, stress +3.9% |
| HOLDOUT | frozen A7 rule, 41 trades | **win 63.4% (PASS), mean −2.7% base / −4.6% stress (FAIL)**; t −0.38. Underlying: score2 win 63.4% vs ALL 64.5%, so the filter added NOTHING in holdout; the win rate comes from exit geometry (T < S) |

**Verdict:** the ≥55% win rate is reproducible, but it comes from the target being closer than the stop, not from an edge.
- Profitability is unconfirmed in the holdout.
- n = 41 is too small to separate the dev +6% from 0.
- The holdout is now spent. Further iteration on this data would be fitting.
- Next valid evidence: a forward paper test of the frozen rule.

## Cross-instrument test (fresh out-of-sample)
**Setup:**
- The frozen score rule was applied to QQQ, IWM and TQQQ on 1-min data, 2016-2026.
- Thresholds were re-derived from each instrument's own 2016-20 terciles, using the same recipe as SPY.
- QQQ and IWM were never used in design.
- Output: `results-cross-instrument-2026-10-04.txt`.

**Result:** score==2 helps ONLY on SPY, the design instrument.
- QQQ: ≈ ALL.
- IWM: WORSE than ALL in every period (±20 bp hit 41-47% vs 47-51%).
- TQQQ: ≈ or worse.

So the SPY filter was an overfit pattern, not a market effect.

**Win rate:** T15/S30 wins 60-69% everywhere, even unfiltered. That is exit geometry, with EV ≈ 0.

**Options replay:** skipped. Zero underlying EV minus spread and theta can't be positive.

**0DTE availability (Alpaca, checked 2026-10-04):**
- Daily: SPX/SPXW, XSP, SPY, QQQ.
- IWM: M/W/F in 2024 → daily by 2026.
- DIA and leveraged ETFs (TQQQ, SPXL, UPRO, SQQQ, TNA, QLD, SSO): Fridays only.
- TQQQ/SOXL: M/W/F in 2026.
