You are an independent quantitative reviewer. Your job is to BREAK the findings below, not confirm them.
Work only inside this directory. Run python with `python3 -I` (or alloc/venv/bin/python for cvxpy). Do NOT access any
database, ssh, or network; do NOT run alloc/build_roundtrips.py (it queries production) — use its saved outputs
(alloc/raw/*.csv, alloc/roundtrips.csv, alloc/daily.csv). Write outputs only to ./review2/.
An earlier review of rounds 1-3 is in review/REVIEW.md (context only; don't redo it).

Files: PREREG4.md, PREREG5.md; sr_react.py, sr_eval.py, sr_decade.parquet, sr_author.parquet (level-reaction study);
authorlines/lines.csv, charts.csv (author's drawn lines read from chart images by an LLM agent);
events.parquet + rth_1m.parquet (post-trigger drift); trail_bid.py, trail_bid.parquet, oq/ (collector option bid/ask
snapshots), half_spread.csv; alloc/ (realized P&L from broker fills: roundtrips.csv, daily.csv, raw/analysis.md,
analyze.py, build_roundtrips.py for method only). decade/*.jsonl.gz = 1m SIP bars 2016-2026 (12 symbols).

Claims to attack:
R1. No S/R definition (PDH/PDL/PMH/PML, daily/1h pivots, volume-profile nodes, prior-day/weekly VWAP, round numbers)
    produces more rejection than placebo levels 0.3-1% away on the same day, 2016-2025 + H1 2026.
R2. 5m intraday pivots break MORE than placebo in all 3 periods; absolute follow-through only 1-2 bps.
R3. Author's drawn swing lines: +3 bps bounce / -8pp breaks vs placebo, not significant (n=114).
R4. Post-trigger drift -2..-8 bps, variance ratio 1.07 => trailing stop cannot add expected value.
R5. On option BID path (18 0DTE author trades), 10% trail mean -4% vs hold +17% (median -69%); no trail beats random entries.
R6. Live copytrade realized +$34.2k/126 RT 06-29..10-22 but per-trade mean CI [-4.6%,+14.8%], top-3 trades > total;
    the earlier +$35k (thru 08-19) omitted -$26k of expired residual lots -> ~+$8k.
R7. No allocation scheme is robust OOS; Kelly fit = 0 for copytrade; sizing L (equity per day) dominates drawdown risk.

Look for: look-ahead (pivot confirmation timing, VWAP, volume profile using same-day data), placebo design flaws
(placebo levels drawn 0.3-1% away sit at different distance from current price than real levels -> different touch
timing/conditioning; is "bounce" comparable?), touch definition artifacts, clustering/independence (many levels per day,
per symbol; is the bootstrap cluster right?), the decade fetch being raw (unadjusted) prices across splits (NVDA, TSLA,
AAPL, GOOGL, AMZN splits!) — does that break PDH/pivots/round numbers around split dates?, the trail sim's strike rule
vs the author's real strikes, entry at ask vs exit at bid symmetry, the random-entry control, P&L reconstruction logic
(double counting, residual-lot marking, re-keyed exits), and whether R6/R7 conclusions follow from n.
Re-compute R1/R2 for at least two definitions and R5 independently.
Deliver ./review2/REVIEW.md: verdict per claim (HOLDS / WEAKENED / WRONG) with evidence, bugs table (file:line,
impact, changes-conclusion?), and what to test next. Terse and specific.
