# Pre-registration round 4 (2026-10-22, before outcomes): does S/R carry information; can a trail add value
Ledger before this round: ~150 variants (R1 64, R2 140 incl. both datasets, R3 6).
## Step 1 — level-reaction study (no P&L)
Universe: decade 12 symbols (FIT 2016-2021, TEST 2022-2025); 2026 59 symbols as a 3rd check.
Definitions (all knowable at touch time):
 D0 baseline PDH/PDL/PMH/PML | D1 swing pivots: daily (k=2, last 10 confirmed), 1h (k=3, last 5 sessions), 5m intraday (k=6, confirmed)
 D2 volume profile from 1m bars (volume at bar typical price, 0.1% bins): prior-day POC + top-3 HVN; prior 5-day composite POC
 D3 VWAP: running session VWAP, prior-day VWAP, week-anchored VWAP | D4 round numbers (step by price)
 D5 author's drawn lines (2026 sample only, if extractable) | OI strikes DROPPED: DoltHub option_chain has no open interest.
Touch: from 09:45, prior 1m close >0.1% on the near side, bar high/low within 0.05% of level; first touch per level per session;
 levels within 0.1% merged per definition. Outcome: bounce_h = side-signed move AWAY from level at h=15,30 min; P(break) = close
 >0.2% through within 30m. Placebo: same session, level*(1+u), u~U(+-[0.3%,1.0%]), same touch logic.
PASS: real-placebo bounce30 (or break-continuation) same sign with 95% day-clustered CI excluding 0 in BOTH decade halves.
## Step 2 — trail
2a drift: post-trigger drift curve + variance ratio (1m returns, 30/60m) on the 647 author legs vs placebo. If no persistent
 drift, state that a trail cannot add expected value.
2b trail on option BID path (collector SPY/QQQ, 2026-10-06..): author triggers, strike per his rule, 0DTE and next expiry.
 Trails {10,20,30}% of peak bid and {1,2,3}x contract's prior-30m stdev of 1m mid changes. Control: same contract/day, random entry
 time. Exit at trail or 15:55. + trade-bar version on all names (flagged: no bid). Variants: 6 trails x 2 expiries = 12.
