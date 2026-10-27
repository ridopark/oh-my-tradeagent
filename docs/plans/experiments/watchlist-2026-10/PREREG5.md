# Pre-registration round 5 (2026-10-22, before outcomes): is convexity cheap?
Ledger before: ~180 variants + 9 S/R definitions.
Direct test = realized EV per $1 of premium (the market price IS the implied tail probability; EV<0 <=> convexity overpriced).
Universe: SPY, QQQ 0DTE, 2024-02-01..2026-10-22 (Alpaca option trade bars). Entry times {10:00, 12:00, 14:00};
OTM distance k in {0.25%, 0.5%, 1.0%} of spot, calls and puts (strike = ceil/floor of spot*(1+-k), $1 strikes).
Entry price = 1m bar close at entry minute (bar VWAP-ish trade price) * (1 + half-spread), half-spread measured from the
collector (2026-10-06..) median by (k bucket, entry time). Exit = intrinsic at 15:59 close (ITM: minus half-spread).
Also 'hold-to-target' variant: sell at first 1m bar where bar high >= 3x entry (fill at 3x) else intrinsic at close.
Variants: 2 und x 3 times x 3 k x 2 rights x 2 exits = 72. FIT 2024-02..2025-06, TEST 2025-07..2026-10.
Regimes (ex-ante only): prior-20d realized vol tercile (from SPY/QQQ 1m closes), opening gap |gap|> 0.5% vs not.
Report EV per $1, median, P(payoff>=3x), CI (day bootstrap), studentized max-t over all 72 on TEST.
Breakeven decomposition: required move to breakeven at entry vs realized frequency of reaching it by close.

## Bug fix 1 (after first results): option bars were not filtered to the entry date (OCC 0DTE symbols trade days
before expiry), so entry and the 3x path mixed days. Fixed: bars restricted to entry date, RTH (m<=389). Variant count unchanged.
