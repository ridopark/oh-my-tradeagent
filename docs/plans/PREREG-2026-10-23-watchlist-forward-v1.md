# PREREG — 2026-10-23 watchlist-forward-v1

Forward, pre-registered test of the TradingTheTrend daily watchlist. Frozen by the git tag
`watchlist-forward-v1`; the tag's commit timestamp must precede every scored day. Nothing in this
file or in `scripts/research/watchlist_forward.py` changes after the tag. A fix found later is a new
version (`-v2`) with its own start date; v1 days are never re-scored under v2 rules.

## Why forward
Nine historical rounds (2026-10-22/23; ~300 variants, two independent reviews) found no edge.
The binding limit is sample size: ~4 tradeable legs/day, sd(r60) ≈ 123 bps, so the 90-session history
detects only ≥19 bps across all legs and ≥40–60 bps on subsets. Re-mining that history cannot prove
anything; only data collected after this freeze can.

## Data captured daily (raw, append-only, sha256 manifest per day)
`<out>/raw/<YYYY-MM-DD>/`:
- `legs.json` — every `TriggerArmed` audit row that day (ticker, direction, level, armed-at), plus
  the `watchlist_daily` row (ideas with strikes, `source_message_id`, post time decoded from the
  Discord snowflake).
- `stock_1m.ndjson.gz` — Alpaca SIP 1-min bars (raw prices) 04:00–20:00 ET for listed tickers + SPY,
  covering that day and the 10 calendar days before it (for prior-session and prior-5-session H/L).
- `flow.ndjson.gz` — captured at 10:35 ET while that day's contracts are still listed: per ticker ×
  minute 09:30–10:29, summed call and put option volume over the nearest 2 expiries ≥ that day with
  strikes within ±5% of the 09:30 open, for every ticker armed in the trailing 90 calendar days
  (the F3 baseline universe). No backfill: F3 tags start once a ticker has 15 non-zero sessions.
- `manifest.json` — sha256 of each file.

## Sample definition (all hypotheses)
- A leg = one deduped (date, ticker, direction, level) `TriggerArmed` row.
- **Excluded:** legs armed at/after 09:30 ET that day (the 2026-07-01 leak class); legs whose first
  RTH 1-min bar OPENS beyond the level (gap); legs that never trigger.
- **Trigger:** first 1-min bar (09:30–15:59 ET) whose CLOSE is beyond the level. Entry = that close.
- **Outcomes**, signed in the leg direction: r60 = return to the first bar ≥ trigger+60 min
  (missing if the session ends first); rclose = return to the 15:55 bar.

## Hypotheses (exactly three)
**F1 Level break (base).** Placebo arm: for every leg, four placebo levels at level × (1 ± 0.005)
and level × (1 ± 0.010), same direction, same rules. Metric: mean r60(real) − mean r60(placebo).
History reference: −6 / −5 bps (FIT/TEST), indistinguishable from placebo.

**F2 The author's written rules** (his "HOW TO READ THE WATCHLIST" post; round-3 variant
`m5|skip30=False`). Entry: first 5-min bar close beyond the level whose previous 5-min close was on
the near side. Stop: a 5-min close back through the level. Target: the nearest of {prior-day H/L,
premarket H/L, prior-5-session H/L, round number (step 1 if <$100, 5 if <$500 or SPY/QQQ, else 10)}
at least 0.15% beyond entry; exit at the target when touched (stop checked first within a bar).
Else exit at the 15:55 close. Cost 1 bp round trip (SPY/QQQ), 3 bp (single names).
Metric: mean net return per trade. History reference: TEST +9.1 gross / −0.9 net bps.

**F3 Early option flow.** Legs from F1 with trigger ≥ 09:45. Flow = call − put volume from 09:30 to
min(trigger, 10:30); z vs the same ticker's trailing 20 sessions of the same window (needs ≥15
non-zero sessions, else untagged). AGREE: z ≥ +1 in the leg's direction (calls for ABOVE, puts for
BELOW, sign-adjusted); DISAGREE: z ≤ −1; SILENT otherwise. Metric: mean r60 AGREE − SILENT.
History reference: +12.4 / +5.1 bps, CIs crossing 0.

## Analysis — one look only
- **Analysis date: 2027-04-23** (≈125 sessions). No interim looks at outcomes. The daily job may
  report completeness and counts, never returns.
- Inference: day-clustered percentile bootstrap, 10,000 resamples, seed 20261023.
- **PASS** (per hypothesis): metric > 0 and the 95% CI excludes 0 after Holm correction across F1–F3;
  for F3 additionally AGREE − DISAGREE > 0 (point estimate).
- **KILL:** metric ≤ 0. Anything between = "not shown"; it is not extended.
- Expected power at 125 sessions: F1 detects ≈ 15 bps (all legs); F2 ≈ 20 bps; F3 (≈23% AGREE) ≈ 45 bps.
  A FAIL therefore rules out large effects only.
- **Completeness:** a trading day = a day with SPY 1-min bars. Every trading day from 2026-10-23 to
  the analysis date must have a raw folder or a written reason. If > 5% are unexplained, the
  evaluation is reported as compromised.
- **Re-derivation:** at the analysis date an independent reviewer recomputes F1–F3 from the raw
  folders without using `watchlist_forward.py`, and both sets of numbers are reported.

## What a PASS would mean
Evidence that the rule had a positive expectation over the window. It still needs a paper test on broker fills
before any capital, because these outcomes are underlying returns, not option P&L.
