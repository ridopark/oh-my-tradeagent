# Pre-registration round 6 (2026-10-22, before outcomes): 5m-pivot continuation as an EQUITY strategy
Origin: reviewer-2 finding (f30 from touch close -5.5/-6.6/-4.1 bps through recent 5m pivots, all symbols/years).
Ledger before: ~250 variants (+9 S/R defs). This round adds 6.
Universe: 12 symbols (SPY QQQ NVDA AMZN TSLA AAPL INTC PLTR MSFT META GOOGL MU), 1m SIP RTH bars.
Periods: FIT 2016-2021, TEST 2022-2025, HOLDOUT 2026-01-02..2026-10-22 (decade file to 06-12 + rth_1m after).
Level L (pivot arm): 5m-bar pivot high/low with width k each side, k in {3,6,12}; active from close of the
  confirming bar (pivot bar + k), same session only.
Level L (control arm, same k): highest high / lowest low of the 1m bars in [t-(2k+1)*5-30, t-30] min, i.e. a
  recent extreme of the same age profile that need not be a pivot.
Signal: from 09:45 to 15:24, 1m bar j touches L from the near side (prev close >0.1% away, high/low within 0.05%).
  Resistance touch -> LONG, support touch -> SHORT (trade the break-through).
Entry: OPEN of bar j+1. Exit: CLOSE of bar j+30 (30 minutes). One position per symbol at a time; each level used once.
Costs (round trip): SPY/QQQ 1.0 bp; single names 3.0 bp. Report gross and net.
Stats: per-trade mean, day-clustered 95% CI, per-day Sharpe (annualized, days with trades; and per calendar day),
  per-symbol and per-year sign counts, studentized max-t over the 6 variants on TEST.
PASS: net mean > 0 with CI excluding 0 in FIT and TEST, and > 0 in HOLDOUT; pivot arm must beat control arm
  to claim "pivots" specifically (else report as generic recent-extreme momentum).

## Finding during execution: the motivating effect was a look-ahead artifact
sr_react.py (and reviewer's sr_re.py) dedupe 5m pivot levels within 0.1% using the WHOLE session's pivot list, so a
pivot that forms LATER near an earlier level (= price reversed there) removes that earlier level -> drops reversal
touches. Continuation from touch close: no-merge +2.0, causal-merge +2.4, future-aware merge +6.0 bps (4 symbols,
~20k touches). Only D1_5m is affected (all other definitions' levels are known pre-session).
