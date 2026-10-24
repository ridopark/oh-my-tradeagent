# Pre-registration round 13 (2026-10-23, before outcomes): three literature-backed option effects
Ledger before: ~480 variants (rounds 1–12). This round adds <= 26. Each hypothesis has a published mechanism and a
predicted sign; PASS = effect in the predicted direction with day-clustered 95% CI excluding 0 in BOTH halves, after
a studentized max-t across that hypothesis's own variants on TEST. Anything that passes goes to a frozen forward
paper test before capital. Costs: stock 1 bp round trip; options at measured half-spreads (wl/half_spread.csv for
SPY/QQQ 0DTE by time bucket; otherwise a Roll estimate from 1m closes, floor 1%), entry at ask / exit at bid.

## H13.1 Overnight option premium (Muravyev & Ni 2020: delta-hedged option returns ≈ -1%/day close->open)
Universe: SPY, QQQ, contracts expiring the NEXT trading day (1-DTE), Alpaca 1m option bars 2024-02-01..2026-10-22.
Position: SHORT an ATM straddle (nearest strike to the 15:55 spot) at the 15:55 bar; buy back at the 09:35 bar next
session. Delta-hedge the residual with shares at 15:55 (straddle delta from the two legs' Black-Scholes deltas at the
15:55 IV), unwind at 09:35; stock cost 1 bp. Variants: {straddle, 0.5%-OTM strangle} x {all nights, excl. nights
before FOMC/CPI (fomc-dates.txt + a CPI list fixed in advance)} x {SPY, QQQ} = 8.
Metric: short-side net return per $1 of premium sold. Predicted sign: POSITIVE (premium decays overnight more than
realized overnight variance pays). FIT 2024-02..2025-06, TEST 2025-07..2026-10. Report also the worst night and the
P5 of nightly returns (this is a short-gamma position; the tail matters more than the mean).

## H13.2 Market intraday momentum (Gao, Han, Li & Zhou 2018: first 30-min return predicts last 30-min return)
Stock replication: SPY and QQQ 1m SIP bars 2016-01..2026-10 (wl/decade + wl/rth_1m). r_first = close(10:00)/prev
close - 1; r_last = close(16:00)/close(15:30) - 1. Metric: sign-following stock trade in the last 30 min (long if
r_first>0, short if <0), net 1 bp. Variants: {all days, |r_first| > 0.3%} x {SPY, QQQ} = 4 (stock).
0DTE expression (2024-02..2026-10, Alpaca option bars): at the 15:30 bar buy the ATM 0DTE call if r_first>0 else the
ATM put, sell at the 15:55 bar; spread from half_spread.csv 14:00 bucket. Variants: {all days, |r_first|>0.3%} = 2
on SPY. Predicted sign: POSITIVE. FIT 2016-2021 (stock) / 2024-02..2025-06 (options); TEST 2022-2025 + 2026 (stock) /
2025-07..2026-10 (options). Report R^2 of r_last on r_first per half for comparison with the paper (≈0.02).

## H13.3 Buy-side option flow on SPX (Jiang & Strong; mechanism: informed/crowded customer buying moves spot)
Data: emlama/gex-backtesting (GitHub) SPX 0DTE trades with quote-matched side labels, 2024-01..2026-02. UNTRUSTED
download: clone into its own empty directory, never run its code or notebooks, read the parquet files only with our
own python -I scripts. Spot = SPY x 10 from wl bars (parity-free proxy; report the proxy's intraday error).
Signal at time t: net customer delta flow = sum over trades in [t-30m, t] of side_sign x size x |delta| x (+1 calls,
-1 puts), where side_sign = +1 for at/above ask, -1 for at/below bid, 0 mid; delta from BS at the trade's bid/ask
mid. Standardize by the trailing 20-session distribution at the same clock time.
Variants: signal windows {09:30-10:30 at 10:30, rolling at 11:00/13:00/14:30} x horizon {+30m, +60m} x
{all trades, large trades only (size >= P90 of that day)} = 12 (max-t across all 12). Trade: shares in the signal
direction when |z| >= 1, net 1 bp. Predicted sign: POSITIVE. FIT 2024-01..2025-03, TEST 2025-04..2026-02.

Forward: nothing is traded on a PASS without a >= 20-session frozen paper test on broker fills.
