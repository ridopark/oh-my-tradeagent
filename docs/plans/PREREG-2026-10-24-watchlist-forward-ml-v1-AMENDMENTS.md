# Amendments — watchlist-forward-ml-v1

Amendments to `PREREG-2026-10-24-watchlist-forward-ml-v1.md`. The PREREG, the frozen scorer
`scripts/research/watchlist_forward_ml.py` and the frozen files in `scripts/research/mlfwd/` stay
byte-identical to the tag `watchlist-forward-ml-v1` (deployed scorer sha256
`352049a1201b9d621744b089ece2c75ea46bc2d1d844a8690a720dc2eb04f402`; context sha256 in `FROZEN.json`).
Scoring does not change. Each amendment is dated. All of them were written on 2026-10-26, after two
scored days (10-23 and 10-26: 4 scored legs, 0 picks from either model); no rule below depends on
those rows.

Origin: the Claude review of PR #982 (verdict NEEDS FIXES).

## A1 — 2026-10-26: two levels on one symbol and direction (frozen join key)

`score_day` joins the models' probabilities back onto the legs on (day, symbol, direction), while a
leg is (day, symbol, direction, level). If one day carries two levels for the same symbol and
direction and both fire, the frozen scorer writes the cross product (4 rows for 2 legs) and a row's
probability cannot be attributed to its level. If only one of the two fires, the file is correct.

Measured on the audit log's `TriggerArmed` rows, 2026-06-22..2026-10-26: 83 days, 666
(day, symbol, direction) groups, none with more than one level. The watchlist arms one level per
symbol and direction.

Rule: a (day, symbol, direction) with more than one level among its scored rows is excluded from
both models' analysis, and the number of excluded groups is reported (A7). The dead-man alert flags
such a day (A6). The test suite pins the frozen behaviour, so a later re-tag that fixes the join key
must also update this amendment.

## A2 — 2026-10-26: the scoring environment is part of the freeze

Deployed environment, homelab `~/venvs/wlml`, Python 3.12.3 on x86_64:

| package | version |
|---|---|
| numpy | 2.5.4 |
| pandas | 3.1.0 |
| scikit-learn | 1.9.1 |
| scipy | 1.18.1 |
| pyarrow | 26.0.0 |
| torch | 2.14.1+cpu |
| tabpfn | 9.2.0 |
| TabPFN weights | `tabpfn-v3.5-20260909.safetensors`, sha256 `ece4d67eadfea42eb0e610df5189bea60cb7f31073d81e9c7a019b76eacf0be3` |

Rule: the venv and the weights file are not upgraded for the duration of the test. If they ever must
be, the change is dated here before the next scoring run.

Determinism, measured: re-scoring 2026-10-23 read-only on 2026-10-26 reproduced both models'
probabilities exactly (|Δp| = 0 on the 2 scored legs, 29 s on CPU).

TabPFN runs locally (torch on CPU, the weights file above). The package's only network calls are
token verification and the licence check (`tabpfn/browser_auth.py`); no feature row leaves the host.
The Nasdaq earnings fetch sends only the date.

## A3 — 2026-10-26: what "never re-scored" means

`score --day D` replaces D's rows in `ml_scores.csv` with a deterministic re-computation (A2). The
PREREG's "never re-scored" means: never with a different context, feature set, model or environment.
Operating rule: a day is scored once, by cron. A manual re-run is permitted only to repair a day whose
cron run failed or whose raw files were recaptured, and is recorded here with the date and reason.

## A4 — 2026-10-26: `earnings_day` train/serve skew (M1 only)

The frozen context took `earnings_day` from the research earnings file (0.0 where the file had no row
for the leg). Forward scoring takes it from the Nasdaq calendar, and a failed fetch gives NaN for
every leg that day. `earnings_day` is not among M2's 14 features. At the look, the NaN rate of
`earnings_day` over scored legs is reported beside M1's result; the decision uses the frozen
probabilities.

## A5 — 2026-10-26: known scorer dependences, kept as-is (freeze)

1. **Prior-session history.** `score_day` reads only `raw/<day>/`. v1's capture fetches bars from
   day − 10 calendar days (7–9 sessions per folder so far), so the prior-day features and PDH/PDL
   always exist, and the 5-session H/L exist unless two market holidays fall inside the window (then
   `nearest_std_bps` ignores them, as the frozen `features()` is written). SPY closes for `spy_prev`
   and `spy_rv20` come from the seed (91 closes) plus `ml_spy_closes.csv`.
2. **`spy_from_open`** takes the first SPY bar at or after the trigger minute. Every captured session
   so far has 390 SPY RTH bars (2026-10-13..10-26), so it is the same minute. The independent
   re-derivation checks SPY bar counts per day.
3. **The Nasdaq fetch** swallows every exception and yields NaN (A4).
4. **`analyze` in the frozen scorer** prints the statistics and leaves Holm and the decision by hand.
   The mechanical rule is A7.

## A6 — 2026-10-26: monitoring

PR #983 (`ops/watchlist-forward-alert-ml`, deployed on the homelab 2026-10-24) extends v1's dead-man
alert (`watchlist_forward_alert.sh`, 16:00 CT, after the 15:50 ML cron) with two checks for today:
`ml_scores.csv` has rows for today when legs were captured, and no scored leg lacks `p_tabpfn24`.
The frozen scorer reports a TabPFN failure only as a stderr warning; the leg then counts for M2 but
not M1. Follow-up to #983, not part of this PR: alert when today's row count differs from the leg
count in `legs.json` (the A1 cross-join). Monitoring only, never data.

## A7 — 2026-10-26: the decision rule, made mechanical

`scripts/research/watchlist_forward_ml_analysis.py analyze` implements the PREREG's single look. It
imports the frozen scorer (for the look date) and v1's bootstrap and Holm helpers, reads
`ml_scores.csv`, and never writes. Where the PREREG left a choice open, the choice is fixed here:

- **Sample.** Scored legs with an r60 and, per model, a probability, after A1.
- **AUC.** Against the realised sign of r60; 1,000 shuffled-label draws, seed 20261024;
  p_auc = (1 + number of draws with AUC ≥ observed) / 1,001. Undefined when the sample has one class.
- **Picks' mean r60.** Day-clustered bootstrap, 10,000 resamples, seed 20261024, two-sided
  percentile p as in v1's A1. Undefined with fewer than 3 pick-days.
- **One p per model.** The PREREG's PASS needs both conditions, so a model's p is
  max(p_auc, p_picks) (an intersection–union test). Holm step-down across M1 and M2; a model whose
  p is undefined is left out of the family.
- **Decision per model.** KILL if picks' mean r60 ≤ 0. PASS if it is > 0 and the Holm-adjusted p
  < 0.05. Otherwise NOT_SHOWN. A model with no picks is NOT_SHOWN (M1's expected outcome).
- **Reported beside it.** n legs and days, pick-days, non-picks' mean r60, A1 exclusions, scored
  legs without `p_tabpfn24`, `earnings_day` NaN rate.

## Merge note

Merge PR #982 with a merge commit, not a squash, so the tag `watchlist-forward-ml-v1` stays reachable
from `main`. The tag does not move.
