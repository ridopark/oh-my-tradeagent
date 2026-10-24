You are an independent quantitative reviewer. Your job is to BREAK the findings below, not confirm them.
Work only inside this directory (read files, run python with `python3 -I`). Do NOT touch any database,
network service, or file outside this directory; write your outputs only to ./review/.

Context: a Discord author posts daily "watchlist" levels (ticker, ABOVE/BELOW, price level) premarket.
We tested whether trading these levels has an edge using Alpaca 1-min SIP bars.
Files:
- PREREG.md, PREREG2.md: pre-registrations (and one amendment). Check we followed them.
- legs_raw.psv: the levels (d|workflow|ts|ticker|dir|level|mode|mult); dedupe on (d,ticker,dir,level).
- rth_1m.parquet: RTH 1-min bars (s,t[NY tz],d,o,h,l,c,v), 2026-06-15..10-22. stock_1m.jsonl.gz incl. extended hours.
- event_study.py / analyze.py / events.parquet: round-1 level-cross event study vs placebo.
- grid.py / grid.parquet: round-1 64-variant exit grid.
- levels_re.py / levels_features.parquet: attempt to reconstruct levels from PDH/PDL/PMH/PML/weekly/round numbers.
- hyp.py, run2026.py, run2026_on.py, evalh.py, hyp2026_all.parquet, ledger_real2026.csv: round-2 hypotheses
  (a break+vol filter, b touch&reject fade, c retest, d anticipation, e gap-through) x triple-barrier + overnight exits,
  with a White-style reality check.
- run_decade.py, decade/hyp_*.parquet: same hypotheses on generic premarket-high/low levels 2016-2025 (may be partial).
- author/: LLM-extracted record of the author's own trades (trades.csv, roundtrips.csv, wl_legs_vs_chat.csv).

Claims to attack:
C1. An intraday first 1-min close through an author level shows no continuation (60m ~ -5 bps both halves), same as placebo.
C2. 0/64 round-1 exit variants positive in both halves; best-on-FIT is -10 bps on TEST.
C3. Author levels are NOT rule-reconstructable (44% within 0.15% of a standard level vs 33% placebo; best rule 33%).
C4. Round-2: no hypothesis survives the reality check on 2026 author levels (min RC p 0.58 intraday);
    only candidate is gap-through held to next close (+95 FIT / +168 TEST bps, n=9/7, RC p 0.061).
C5. MDE on 2026 data is ~25-40 bps underlying; option spread cost (~84 bps of premium) is only a few bps of underlying.
C6. Author's posted alert log: +7% mean on 10 closed, -11% including 2 never-closed expired-worthless alerts.

Look specifically for: look-ahead bias (e.g., using info not known at entry; vol filter; prior close; premarket
window), sign errors for BELOW legs (MFE/MAE, barrier), same-bar stop/target ordering, placebo construction flaws
(e.g., placebo gap rate 50% vs real 4% — does that bias C1?), bootstrap/reality-check implementation errors
(is the White RC correctly demeaned/studentized? is p=0.061 meaningful given n=16?), survivorship, timezone/DST
errors, duplicated legs, the MDE arithmetic, the cost-unit claim in C5, and anything in the author extraction that
looks over-confident. Re-compute at least C1, C2 and C4's key numbers independently with your own code.

Deliver ./review/REVIEW.md: for each claim C1-C6 a verdict (HOLDS / WEAKENED / WRONG) with evidence,
a list of bugs found (file:line, impact, whether it changes a conclusion), and what you'd test next.
Be terse and specific.
