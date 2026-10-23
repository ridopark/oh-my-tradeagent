# Amendments — watchlist-forward-v1

Amendments to `PREREG-2026-10-23-watchlist-forward-v1.md`. The PREREG and the frozen scorer
`scripts/research/watchlist_forward.py` stay byte-identical to the tag `watchlist-forward-v1`
(deployed sha256 `a339f119448bd76cf846be31f8ca104ebc5b3e1545a2adce1663041aa0f7ff62`). Capture and
scoring do not change. Each amendment is dated, and every one here was written before any outcome
existed. The first capture is 2026-10-23 at 10:35 ET.

## A1 — 2026-10-23 08:30 ET: the decision rule implemented in code; completeness hardened

Origin: the Claude review of PR #979 (verdict NEEDS FIXES). The frozen `analyze` printed separate
AGREE and SILENT intervals and left the Holm correction "by hand". The frozen `check` counted a day
whose only file was the 10:35 flow capture as captured.

New module `scripts/research/watchlist_forward_analysis.py` replaces the frozen `check` and
`analyze`. It imports the frozen module and never modifies it. It makes the PREREG's rules mechanical
as follows. Where the PREREG left a choice open, the choice is fixed here:

- **Bootstrap.** Day-clustered, 10,000 resamples, seed 20261023 (as in the PREREG). Each statistic is
  recomputed inside every resample. A resample where a statistic is undefined (for example no AGREE
  leg) is dropped.
- **p-value.** Two-sided percentile bootstrap: p = 2·min(share of draws ≤ 0, share ≥ 0), capped at 1.
- **"95% CI excludes 0 after Holm correction across F1–F3"** means: Holm step-down applied to the
  three p-values, and the Holm-adjusted p < 0.05. A hypothesis with no defined statistic is left out
  of the Holm family.
- **F1** = mean r60 of fired real legs − mean r60 of fired placebo legs, both computed within each
  resample of days. Pairing is at the **day** level, not leg level. Placebo levels are generated
  for every leg armed before the open, including real legs that gap or never trigger. Each arm keeps
  only its own fired rows. This is what the frozen scorer produces, and it is stated here because
  the PREREG's "for every leg" did not say it.
- **F2** = mean `f2_net` over real legs with an F2 entry.
- **F3** = mean r60 AGREE − mean r60 SILENT, both computed within each resample. The gate
  "AGREE − DISAGREE > 0" uses the point estimate on the full sample. If there is no DISAGREE leg,
  the gate is not met.
- **Decision.** KILL if the metric ≤ 0. PASS if the metric > 0, the Holm-adjusted p < 0.05, and (F3
  only) the gate holds. Otherwise NOT_SHOWN.
- **Completeness.** A trading day counts as captured only when its `manifest.json` lists both
  `legs.json` and `stock_1m.ndjson.gz`. `explained_gaps.json` is a `{date: written reason}` map, and
  a list or an empty reason is rejected. The > 5% rule is unchanged.

## A2 — 2026-10-23 08:30 ET: known v1 scoring limitations, kept as-is (freeze)

The same review found these in the frozen scorer. Changing them would change v1 scores, so they
stay. Their effect on the decision is bounded as follows.

1. **`rclose` for a trigger after 15:55 ET.** It is computed from a bar before the entry. `rclose`
   is not used by F1–F3, so the decision is unaffected. Any `rclose` summary excludes `trig_m > 385`.
2. **`r60` has no staleness bound.** It is the first bar ≥ trigger + 60 min, however late. The
   independent re-derivation at the analysis date also reports r60 restricted to bars within
   trigger + 60..65 min, as a sensitivity. The decision uses the frozen r60.
3. **`psql` output split on `|`, and `armed_at` parsed by offset text.** Both were checked before
   the first capture:
   - every `watchlist_daily.ideas` element has only the keys ticker, direction, level, strike and
     right, and none of the 6 rows contains `|`;
   - the database session time zone is `Etc/UTC`.
   
   Neither can break capture as things stand. If either ever does, the day's end-of-day files can be
   recaptured later, because TriggerArmed rows and Alpaca bars persist. The 10:35 flow file cannot be
   recaptured. Any such day is recorded in `explained_gaps.json` with its reason.
4. **No overwrite guard on raw files.** Operating rule: a captured day's capture is never re-run.
   A recapture after a failure writes only files that are missing.

## A3 — 2026-10-23 08:30 ET: monitoring

The deployed dead-man alert (`watchlist_forward_alert.sh`, PR #980) calls the frozen `check`. It
also treats today's `manifest.json` as evidence of the end-of-day capture, but a flow-only day
writes that file too. So until the alert script switches to `watchlist_forward_analysis.py check`
and tests for `legs.json` + `stock_1m.ndjson.gz`, a failed 15:40 capture would not alert. That fix
belongs in #980 and needs an operator-approved redeploy. It changes monitoring only, never data.

## Merge note
Merge PR #979 with a merge commit, not a squash, so the tag `watchlist-forward-v1` stays reachable
from `main`. The tag does not move.
