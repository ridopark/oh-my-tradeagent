# PREREG — 2026-10-24 watchlist-forward-ml-v1

A second, separately frozen forward hypothesis on the watchlist, scored daily beside `watchlist-forward-v1`
(whose rules and files are untouched). Frozen by the git tag `watchlist-forward-ml-v1`; nothing here or in
`scripts/research/watchlist_forward_ml.py` changes after the tag.

## Hypothesis
Round 14 (2026-10-23/24) found that models trained on the 219 triggered watchlist legs (2026-06-22..10-22) show
no out-of-sample direction skill except a gradient-boosted model whose apparent edge (AUC 0.61, +37 bps on its picks)
did not replicate on generic levels. The only way to settle whether anything in those models is real is to freeze
them and score legs that did not exist when they were built.

**M1 (TabPFN-3.5, 24 features).** In-context model; its "training set" is the frozen file `ml_context_v1.parquet`
(219 legs, labels through 2026-10-22, sha256 in `FROZEN.json`). Historical walk-forward on those legs, leak-free:
AUC 0.49 (null 0.47 ± 0.05), zero picks — i.e. no support; it runs as the stronger-model control.
**M2 (gradient boosting, 14 features).** Refit deterministically from the same context at each run
(`GradientBoostingClassifier`, 150 trees, depth 2, lr 0.05, subsample 0.8, seed 0). Historical walk-forward: AUC 0.61
(null 0.50 ± 0.07), 44 picks at +37 bps — the claim under test; it failed to replicate on 21k generic premarket-level
breaks (round 14), so the prior is that this is a regime fit. Predicted sign for both: positive on picks.

A feature leak found while building this (`spy_prev` used the same day's SPY close in the round-14 table) is fixed
here: it is the prior session's return. The 23 other features reproduce the round-14 table exactly.

## Sample and scoring
- Legs and outcomes come from the v1 raw folders (`<out>/raw/<date>/legs.json`, `stock_1m.ndjson.gz`), using v1's
  trigger and exclusion rules (first 1-min close through the level; gap legs and legs armed after 09:30 excluded)
  and v1's r60 (return to the first bar ≥ trigger + 60 min, signed in the leg direction).
- Features are computed from data at or before the trigger bar's close only, with the same formulas as the
  training table (`watchlist_forward_ml.py:features()`; verified to reproduce the frozen context on 10-21/10-22).
  `flow_z` is logged when ≥ 15 non-zero prior sessions of v1's 10:35 flow capture exist, but is NOT a model input
  (the frozen context was built without it). `earnings_day` is best-effort from the Nasdaq calendar; missing → NaN.
- A pick = model probability > 0.55. Both models are deterministic given the frozen files; the day's probabilities
  are written before the analysis date and never re-scored.

## Analysis — one look, 2027-04-23 (same date as v1)
Per model: (i) AUC of the probability against the realised sign of r60 over all scored legs, with a 1,000-draw
shuffled-label null; (ii) mean r60 of picks with a day-clustered 95% bootstrap CI; (iii) picks minus non-picks.
PASS = picks' mean r60 > 0 with CI excluding 0 AND AUC above the null's 95th percentile, after Holm across M1/M2.
KILL = picks' mean r60 ≤ 0. Expected power at ~125 sessions: ~60 picks → MDE ≈ 45 bps; a FAIL rules out large
effects only. A PASS leads to a frozen paper test on broker fills, never directly to capital.
Completeness and re-derivation rules are v1's.
