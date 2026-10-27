# Pre-registration round 8 (2026-10-22, before outcomes): what the author's TICKER CHOICE reveals
Ledger before: ~280 variants + 9 S/R defs; 5 crowd hypotheses failed (round 7).
Universe: listed (day, ticker) pairs from audit TriggerArmed (legs_raw.psv; dedupe on (d,ticker)), 2026-06-22..10-22.
Split: FIT 06-22..08-31, TEST 09-01..10-22. FORWARD (final arbiter): 10-23 onward, logged, untouched.
Controls: the same tickers on days they are NOT listed (within-ticker), and other tickers from a fixed liquid
universe (the 59 tickers that ever appeared + S&P 100 members) on the same day (cross-section). Day-clustered CIs.
H8.1 VOL SELECTION: on listed days, realized move (|close/prev close -1| and high-low range/prev close) exceeds the
  implied daily move (prior-day EOD ATM IV/sqrt(252), DoltHub option_chain) by more than on control days.
  Metric: log(realized/implied), listed minus control. Trade if it passes: buy ATM straddle (nearest expiry >=2 days)
  at 09:31 at trade price*(1+half-spread), sell 15:55 at trade price*(1-half-spread) (Alpaca option bars); 1 variant.
  PASS: metric >0 CI excl 0 in FIT and TEST, and straddle net mean >0 CI excl 0 in TEST.
H8.2 ATTENTION DRIFT/REVERSAL: signed by leg direction (calls=+1, puts=-1; both legs -> 0/unsigned), listed tickers'
  returns open->close, close->next open, close->+5d close, minus same-day cross-sectional control mean. 3 horizons
  x {signed, unsigned-abs} = 6 metrics. PASS: same sign, CI excl 0 in FIT and TEST, studentized over the 6.
H8.3 MARKET BIAS: daily bias = (#put legs - #call legs)/#legs on non-SPY/QQQ tickers; predicts SPY and QQQ
  open->close and close->next close (2 und x 2 horizons = 4). Spearman + sign-test; PASS as above.
Costs for any equity trade: 1 bp ETF, 3 bp single names round trip.
