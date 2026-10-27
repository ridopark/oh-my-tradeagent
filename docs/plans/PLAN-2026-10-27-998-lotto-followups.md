# PLAN — 2026-10-27 issue #998 follow-ups (lotto-signal log noise + two review leftovers)

Issue #998. Three independent, small concerns observed after the copytrade-lotto-v1 rollout (#987). Anchors read on
`main@35263075` (and #999's branch for the OI collector, which is re-landing on main). One PR per phase; none touches a
trading path; no Temporal command shapes change; no schema, no audit kinds, no tenant YAML.

## P0 — Operator (none)

## Phase 1 — Throttle the zero-target warning (services/signal-source-discord)
**Goal:** with no tenant enabled, `lotto-signal` stops logging a WARNING every poll (12/min observed on the homelab).
**Changes (anchors):**
- `ohmytradeagent_sidecar/lotto_emit.py:236-242` `_warn_if_no_targets(phase)` — log at WARNING on the first zero-target
  observation and on the TRANSITION into zero targets (after `update_targets` applied a non-empty set), then throttle
  repeats the way `lotto_main.py:188` `_failure` does (1st, 3rd, every 60th consecutive); log an INFO "targets known again"
  on the transition out. Keep the boot-phase warning unthrottled (it fires once per boot).
- No change to `update_targets`, the guard, or emission.
**Tests (TDD):** `tests/test_lotto_main.py`: `test_zero_target_warning_is_throttled` (60 polls at zero targets → warnings at
polls 1, 3, 60 only), `test_zero_target_transition_logs_again` (targets → [] → warning fires again on the first poll after
the transition; [] → targets logs INFO). Keep `test_zero_targets_emits_nothing_and_warns` green.
**Verify:** `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q` (369+ green). Rolls the live
signal watcher (shared image) — merge outside RTH.

## Phase 2 — Dead-man alert: ML row count must equal the leg count (scripts/research)
**Goal:** the 16:00 CT alert catches a partial ML scoring day, not only an empty one.
**Changes (anchors):**
- `scripts/research/watchlist_forward_alert.sh:60-66` — after `mlrows` is computed, when `mlrows -gt 0 && mlrows -ne nlegs`
  add a problem `"ML forward scorer wrote $mlrows rows for $today but $nlegs legs were captured"`. Legs that trigger before
  minute 5 are logged UNSCORED but still get a row (PREREG ml-v1), so row count == leg count is the invariant; confirm by
  reading `scripts/research/watchlist_forward_ml.py`'s writer before asserting it, and if a leg class legitimately writes no
  row, compare against that count instead and say so in the comment.
- Fixture: `scripts/research/tests/test_watchlist_forward_alert.sh` (from #983) gains a case with 3 legs / 2 ML rows → alert
  text contains "2 rows" and "3 legs"; and 3/3 → no problem.
**Verify:** `bash -n scripts/research/watchlist_forward_alert.sh && bash scripts/research/tests/test_watchlist_forward_alert.sh`
(DRY_RUN fixtures via `WF_TODAY`/`WF_DOW`). Deploy = operator copy to `~/scripts/watchlist_forward_alert.sh` on the homelab
(backup first; the deployed copy is `ed7c2fe0…`).

## Phase 3 — OI collector: never replace a good day with an empty response (scripts/data) — AFTER #999 merges
**Goal:** a transient empty `/v2/options/contracts` response does not overwrite that day's snapshot.
**Changes (anchors, on #999's file `scripts/data/option_oi_collector.py`):**
- `main()` loop (`rows = oi_rows(contracts(...))` … `os.replace(path + ".tmp", path)` at ~:58-65): when `len(rows) == 0`,
  do NOT write/replace; count it as a failure (`failed += 1`, stderr line "0 contracts returned, keeping existing file") so
  the run exits 1. Also `try/finally` unlink of the `.tmp` on a failed write.
**Tests (TDD):** `scripts/data/tests/test_option_quote_collector.py` (`OiRowsTest` lives there): `test_oi_main_zero_rows_keeps_file_and_exits_1`
(stub `contracts` to return [] after a first good run → file unchanged, exit code 1) and `test_oi_tmp_removed_on_write_error`.
**Verify:** `python3 -m unittest discover -s scripts/data/tests`. Deploy = operator copy to `~/scripts/option_oi_collector.py`
(backup; cron 06:30 CT).

## Ship order & gating
Phase 1 and Phase 2 in parallel now (independent modules). Phase 3 only after #999 is on `main` (never stack on an unmerged
branch — the #993/#985 merge-target trap). Each: TDD, own PR from a fresh branch off `origin/main`, bot review via
`/pr-review-loop` (merges on READY). Commit trailer:
```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DHs1FCuPZMK4KCuUkyDAn4
```
Final PR carries `Closes #998`.
