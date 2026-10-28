# PREREG — 2026-10-28 market-stop-shadow-v1

Forward, shadow-only test of a QQQ "market stop" on real copytrade positions. Frozen by the git tag
`market-stop-shadow-v1`; the tag's commit timestamp must precede the first captured session (2026-10-28).
Nothing here or in `scripts/research/market_stop_shadow.py` changes after the tag; a fix is a `-v2`
with its own start date. No order is ever placed: the job only reads fills and market data.

## Why
Round 15 (2026-10-24) tested the rule on 72 historical signals. It narrowly failed its pre-registered
test, and the independent review (2026-10-27) found the failure fragile: it flips to a pass when the
4 manual signals are dropped, and the risk metric (P&L ÷ drawdown) misbehaves when totals are negative.
The rule cut drawdown in both halves but sometimes cuts big winners (INTC 260925C +150% → −15%).
History is too short to settle it, so this collects new data with the flaws fixed.

## Population (one policy)
Every `order_intent_journal` row in `exec_alpaca_live` with tenant `prod_real`, strategy
`copytrade-v1`, state `FILLED`, filled at or after 2026-10-28 09:30 ET, including manual and
operator entries. Each BUY fill is a lot. SELL fills on the same option symbol consume lots FIFO.
Copytrade only buys options, so every lot is long a call or a put.

## Rule (shadow)
- Direction: +1 for calls, −1 for puts.
- Reference: the close of the last QQQ regular-hours 1-min bar that starts before the lot's entry
  minute. For an entry outside regular hours, it is the last regular-hours close before entry.
- Trigger: the first QQQ regular-hours 1-min bar after the entry minute, while the lot still has
  quantity, whose close gives direction × (close / reference − 1) ≤ −0.5%.
- Shadow exit: the remaining quantity is sold at the open of the first option 1-min trade bar that
  starts after the trigger bar, in the same or a later session before expiry. The price is that open
  × (1 − hs). hs is the Roll estimate in dollars from that contract's 1-min closes earlier the same
  day, strictly before the exit bar (at least 4 closes), divided by the fill price, with a 1% floor.
  With fewer than 4 closes, hs = 1%.
- Actual sells before the trigger count as they happened. Actual sells after it are ignored for that lot.
- A lot with quantity left at expiry is worth its intrinsic value at the expiry-day underlying close
  (0 if out of the money), in both arms. A lot still open at the analysis date is marked at the last
  captured option close in both arms.

## Data captured daily (raw, append-only, sha256 manifest)
`<out>/raw/<YYYY-MM-DD>/`: `fills.json` (that day's population fills), `qqq_1m.ndjson.gz`
(QQQ regular-hours 1-min bars), `opt_1m.ndjson.gz` (1-min bars, filtered to that date, for every
option open at any time that day), `und_1d.ndjson.gz` (daily bars for the underlyings of those
options), and `manifest.json`. Raw files are never overwritten.

## Analysis — one look only
- Analysis date: 2027-04-23 (same as watchlist-forward-v1). No interim looks at outcomes; the daily
  job reports counts only.
- Units: real dollars (fill price × quantity × 100), the size the account actually traded.
  P&L is booked on the day each lot closes in that arm.
- PASS when both hold:
  - the shadow's maximum drawdown of cumulative P&L is smaller than the actual's;
  - the shadow total is at least 80% of the actual total when the actual total is positive, or at
    least the actual total when it is ≤ 0.
- KILL when the shadow total is below the actual total and its drawdown is not smaller.
  Anything else is NOT_SHOWN.
- Reported but not decisive: the mean per-lot difference (shadow − actual) / cost with a bootstrap
  CI over lots, the number of triggers, and the winners cut vs losers saved.
- Re-derivation: an independent reviewer recomputes the result from the raw folders without using
  this script.

## What a PASS would mean
Evidence that the stop cut drawdown without giving up most of the P&L on these signals. It would
still need an operator decision and a reviewed implementation before any live use.
