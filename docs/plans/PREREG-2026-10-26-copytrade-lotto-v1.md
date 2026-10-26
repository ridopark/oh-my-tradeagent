# PREREG — 2026-10-26 copytrade-lotto-v1

Forward, pre-registered paper test of copying TradingTheTrend's #lotto-alerts posts (channel
`880503518878892143`) with strategy `copytrade-lotto-v1` on tenant `paper_lotto` (issue #987, plan
`PLAN-2026-10-26-copytrade-lotto-v1.md`). Frozen by the git tag `copytrade-lotto-forward-v1`, which the
operator creates at go-live; the tag's commit must precede the first fill. **No threshold in this file may
change after the first fill.** Amendments go in `PREREG-2026-10-26-copytrade-lotto-v1-AMENDMENTS.md`, each
dated before the look. This is variant ~352 of the research program (r1–r18 + 9 watchlist rounds).

## Why forward
r18 (`~/research/watchlist-ai/r18/RESULT.md`) scored 24 hand-transcribed trades: win 67%, payoff 1.8,
mean +0.35/$, 95% CI [0.00, +0.80], 89% of the profit in three 0DTE trades. Publication bias and the
transcription cannot be excluded on that history. Only posts mirrored after the freeze can test it.

## Dates
- **Go-live date T0** = `occurred_at` of the first `audit_log` row with `tenant_id='paper_lotto'`,
  `strategy_id='copytrade-lotto-v1'`, `kind='EntryFilled'` (orchestrator DB). It follows the P0-6 flip of
  `LOTTO_DRY_RUN=false`. The operator writes T0 and the account's starting equity E0 (Alpaca
  `GET /v2/account` → `equity` at T0) into the AMENDMENTS file on the go-live day.
- **Single look L** = the first trading day on or after T0 + 6 calendar months. No look at returns before
  L. Interim reads are limited to counts and completeness (sections "Kill" and "Compromised data").

## Sample
- An **entry post** = an `options_chat_message` row (dashboard DB) with `channel_id=880503518878892143`,
  `author_name='TradingTheTrend'`, `deleted_at IS NULL`, `posted_at` in [T0, L), that the phase-1
  `lotto_parser` labels ENTRY or ROLL-open (`python -m ohmytradeagent_sidecar.lotto_main --replay --since T0`).
- Its **strategy record** = the `EntryFilled` row whose `subject->>'signal_id'` is `<message_id>:<k>`, and the
  `PartialExitFilled` rows whose `workflow_id` ends with `/pos/<option_symbol>/<message_id>:<k>`
  (PositionWorkflow id). A `PositionExpired` row for that workflow = exit proceeds 0.
- **Unposted exits** (author never posted an exit before expiry) are marked, for M1 and M3, at the close
  of the 15:30 ET 1-min Alpaca option bar on expiry day (0 if no bar), matching the strategy's
  `force_close_0dte_et: "15:30"` flatten. r18 used the last bar of expiry day; this is the one deliberate
  difference from `score.py`, stated here.
- Entries whose contract expires on or after L are excluded from M1–M3 and counted in the report.

## Metrics (every metric is a mean per $1 of entry premium; equal weight per entry)
**M1 — source stability (the operator's bar).** His-exit return per entry under r18 `score.py` rules:
fractions few 0.2 / some 0.25 / partial 0.33 / half 0.5 / most 0.75 of what remains, rest = all, sequential
on the posted exit prices; unposted remainder per "Sample". Inputs: the forward posts transcribed into the
`trades.csv` format (`~/research/watchlist-ai/r18/trades.csv` columns), bars via Alpaca 1-min option bars.
- **PASS** iff `n_M1 ≥ 12` AND `win_rate ≥ 0.55` AND `payoff ≥ 1.2` AND `ci_lo ≤ 0.8 AND ci_hi ≥ 0`,
  where payoff = mean(winning returns) / |mean(losing returns)| and [ci_lo, ci_hi] is the bootstrap 95%
  CI of the mean. Else **FAIL** (the source has changed: `ci_hi < 0`, or win or payoff below the floor).
- M1 is a consistency check against the r18 band, not a proof of edge: a CI that overlaps [0, 0.8] is
  compatible with a zero mean.

**M2 — strategy P&L (the issue's bar).** For each strategy record: `r_i = (Σ exit proceeds − entry cost) /
entry cost`, entry cost = `filled_qty × avg_fill_price × 100` from `EntryFilled`, proceeds =
`Σ qty_filled × avg_fill_price × 100` over its `PartialExitFilled` rows. Cross-check every fill against
`order_intent_journal` (`exec_alpaca_paper` DB, `tenant_id='paper_lotto'`, `state='FILLED'`,
`filled_qty`, `avg_fill_price`); a mismatch is reported, the audit value is used.
- **PASS** iff `n_M2 ≥ 12` AND `mean(r_i) > 0`. The bootstrap 95% CI is always reported: `ci_lo > 0` is
  labelled "shown", `ci_hi < 0` "refuted", otherwise "not shown". Also report dollar-weighted
  `Σ(proceeds − cost) / Σ cost` and `Σ(proceeds − cost) / E0`.

**M3 — rule-matched reference (decision F3).** The same forward posts rescored first-exit-full-close:
`r_i = (first posted exit price − posted entry) / posted entry`; no posted exit → the 15:30 ET mark above.
r18 rescore reference: n=23, win 87%, payoff 0.82, mean +0.235/$. Report M3 on all entries and on the M2
subset, and **M2 − M3 on the M2 subset = execution slippage per $** (mirror lag, limit-then-reprice exits,
`max_slippage_*` rejections). No pass bar; it explains M2.

## Inference and power
- Bootstrap: resample entries i.i.d. with replacement, 10,000 resamples, `numpy.random.default_rng(20261026)`,
  percentile 2.5 / 97.5. Same seed for M1, M2, M3.
- Per-trade sd ≈ 1.0 (r18). n for 80% power to detect +0.235 at α=0.05 is (2.8·1.0/0.235)² ≈ 142 entries;
  the author posts ≈ 3/month, so 6 months yields ≈ 18. **The look is underpowered by construction.** It
  can detect a large effect (|mean| ≳ +0.7 at n≈18, roughly 2.8·1.0/√18) in either direction. It cannot
  show "no effect": a PASS with "not shown" or a non-PASS with a CI spanning 0 is "carry to the next look"
  (L + 6 months, same rules), not a verdict.
- **Minimum n for any verdict: 12 entries** (`n_M2 ≥ 12`). Below 12 the result is "underpowered, carried",
  never a FAIL.

## Kill conditions (stop the paper test early; checked on every trading day by count only)
- **K1 drawdown.** Alpaca `GET /v2/account` → `equity < 0.75 × E0` on any day.
- **K2 oversized fill.** Any `audit_log` row, `tenant_id='paper_lotto'`, `kind='EntryFilled'`, with
  `(subject->>'filled_qty')::numeric * (subject->>'avg_fill_price')::numeric * 100 > 0.05 * E0`.
- **K3 stale mirror.** Three consecutive `SignalRejected` rows (ordered by `occurred_at`, no
  `SignalAccepted` between) with `subject->>'reason_code' = 'SIGNAL_TOO_OLD'`.
- **K4 false entry.** An `EntryFilled` whose `subject->>'option_symbol'` is not the contract in
  `options_chat_message.content` for `message_id = split_part(subject->>'signal_id', ':', 1)::bigint`.
  The operator compares every fill to its post within one trading day.
A kill ends the test; what was collected is reported at L under the same rules, labelled "killed on
<date>, <condition>".

## Compromised-data conditions (reported at L; the metric is still computed and labelled)
- **C1 mirror gap.** `max(ingested_at)` for the channel more than 2 trading days behind a post the operator
  can see in Discord, or restarts of the lotto mirror pod (`kubectl -n copytrade get pod -l app=discord-lotto-mirror`)
  on a day whose row count is 0. Each gap day is listed with its reason.
- **C2 missing entries.** `(entry posts − EntryFilled rows) / entry posts > 0.05`, counting every
  `SignalRejected` reason and every entry with no audit row at all. M2 is then "compromised"; M1 is not.
- **C3 manual order.** Any `audit_log` row for `paper_lotto` with `kind IN ('ForceCloseRequested',
  'OperatorTrimRequested')`, or any Alpaca order (`GET /v2/orders?status=all&after=T0`) whose
  `client_order_id` is not in `order_intent_journal`.

## What a result means
M1 PASS + M2 PASS "shown" = the source held and the copy made money at the chosen size; still paper, still
~18 trades. M1 FAIL = the author's edge changed; M2 is moot. M1 PASS + M2 negative with M3 positive =
execution, not source, is the problem. Nothing here authorises real money.
