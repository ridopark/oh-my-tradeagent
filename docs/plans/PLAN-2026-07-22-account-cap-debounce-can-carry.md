# PLAN — 2026-07-22 carry the account-cap debounce counter across continue-as-new

Completeness follow-up to #606/#607. The account-cap quote-blip debounce (#606) counts
`consecutiveMtmUnavailableTicks` and fail-closes after `MTM_UNAVAILABLE_TRIP_TICKS` (=2) CONSECUTIVE
unpriceable heartbeats. That counter is **workflow state that is NOT carried across continue-as-new**
(`buildCarryForwardInput` drops it), so a CAN landing in the 1-tick window between the two misses of a
genuine N=2 outage resets the count to 0 → the trip is delayed by one extra heartbeat. Practically
rare (the CAN watermark is thousands of events, so a CAN in that exact 1-tick window is astronomically
unlikely) and fail-SAFE (it only delays a protective trip by ~1 min; never fail-open) — but this PR
closes it for completeness so the debounce is exact across a CAN. Mirrors EXACTLY how `sod_equity`
(v3) was added to the same carry.

**Source:** #606 deferred deviation (a). Single concern, single PR.

## P0 — operator: none (code + contract; no live mutation).

## Replay / contract framing (read first)
- **No `getVersion` marker.** `Workflow.continueAsNew(buildCarryForwardInput())`
  (`AccountKillSwitchWorkflowImpl.java:458`) is UNGATED, exactly as when v3/`sod_equity` was added.
  The `continueAsNew` command is emitted at the same watermark point regardless of its argument; the
  carry INPUT is a payload, not a replay-checked command shape (like an activity input). Adding a
  field to that payload does NOT require a version marker. The `schema_version` stamp is the
  cross-build compat mechanism, NOT a Temporal getVersion.
- **This is a JSON-schema contract bump** (`contract/schemas/account-kill-switch-workflow-input.json`
  → Java POJO regen at build + Python pydantic via `contract/python/regen.sh`). CI job "Python
  (pydantic round-trip + regen drift)" (`ci.yml:186`) fails if the committed pydantic model drifts —
  so run `regen.sh` and COMMIT the regenerated model.

## Phase — carry the debounce counter (contract + orchestrator workflow)
**Anchors (verified by reading main @ 6729685):**

1. `contract/schemas/account-kill-switch-workflow-input.json`
   - `schema_version.maximum` `3` → `4`; extend its `description` (v4 adds
     `consecutive_mtm_unavailable_ticks`).
   - add property `consecutive_mtm_unavailable_ticks`: `{"type":"integer","minimum":0,"description":
     "Carry-forward count of CONSECUTIVE unpriceable-book heartbeats accumulated toward the small-book
     mtm-unavailable fail-close debounce (MTM_UNAVAILABLE_TRIP_TICKS). Carried across continueAsNew so
     a same-day CAN mid-debounce does not reset the count. Absent on fresh bootstrap and when the
     count is 0. v4+."}`. Keep it OUT of `required` (optional; null/absent = 0).
   - update the top-level `description` (append the v4 note, mirroring the v3 sentence).

2. Regen: run `contract/python/regen.sh`; commit the regenerated
   `contract/python/ohmytradeagent_contract/models/account_kill_switch_workflow_input.py`. The Java
   POJO regenerates at build (no committed source).

3. `services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java`
   - `:401` — schema guard `in.getSchemaVersion() > 3L` → `> 4L`.
   - `:424-425` (init, next to the `sodEquity` restore) — restore the counter from input when present:
     `if (in.getConsecutiveMtmUnavailableTicks() != null) { this.consecutiveMtmUnavailableTicks =
     in.getConsecutiveMtmUnavailableTicks(); }`.
   - `:388-397` — REWRITE the "intentionally NOT carried across continue-as-new" comment: it IS now
     carried (stamped v4 only when > 0). State the rolling-deploy discipline (below).
   - `carryForwardInput(...)` static (`:583-610`) — add an `int consecutiveMtmUnavailableTicks`
     param. Set `schema_version` = `consecutiveMtmUnavailableTicks > 0 ? 4L : (sodEquity != null ? 3L
     : 2L)`, and `carry.setConsecutiveMtmUnavailableTicks(...)` ONLY when `> 0`. This mirrors the
     `sod_equity` discipline (only bump the version when the new field is actually carried) so the
     common count==0 CAN keeps stamping v2/v3 and an old pod mid-rollout is never handed a v4 it would
     reject. (count>0 ⟹ sodEquity!=null, since the debounce only runs after threshold resolution — so
     v4 always also carries sod_equity.)
   - `buildCarryForwardInput()` (`:559`) — thread `this.consecutiveMtmUnavailableTicks`.
   - Confirm the reset paths still hold: new-day reset (`:657`), clean-tick reset (`:798`), and
     reset/untrip all zero the counter BEFORE any carry (so a reset CAN carries 0 → v2/v3).

**Tests (TDD):**
- `carryForwardInput` static builder: count>0 → `schema_version==4` AND field carried; count==0 &&
  sod!=null → `schema_version==3`, field absent/null; count==0 && sod==null → `schema_version==2`.
- `run()` init restores the counter from a v4 input (and treats absent as 0).
- schema guard REJECTS `schema_version==5`; ACCEPTS 4.
- **Behavioral (the whole point):** 1 unpriceable tick (count→1, deferred, NO trip) → CAN → 1 more
  unpriceable tick post-CAN → trips `auto:account_mtm_unavailable` (count reaches 2 because it
  survived the CAN). Contrast the pre-fix behavior (would need a 3rd tick). Assert exactly one
  deferred page across the episode (the #607 emit fires on the first defer only, pre-CAN).
- new-day CAN resets to 0 (a v4 carry never leaks a stale mid-outage count into a fresh day).
- **Legacy replay (REQUIRED):** extend `AccountKillSwitchWorkflowImplLegacyReplayTest` — a pre-v4
  (v2/v3) carry history still replays/inits cleanly under the widened guard (no
  `getConsecutiveMtmUnavailableTicks` in old input → treated as 0).

**Verify / success criteria:**
- `contract/python/regen.sh` run + regenerated model committed (CI pydantic-drift job green).
- `mvn -pl contract -pl services/orchestrator -am spotless:apply` then `spotless:check`.
- `mvn -pl services/orchestrator -am test
  -Dtest=AccountKillSwitchWorkflowImplTest,AccountKillSwitchWorkflowImplLegacyReplayTest` green.
- Behavioral assertion above: the CAN-mid-debounce scenario now trips on the 2nd unpriceable tick
  (not the 3rd). `KillSwitchWorkflowImplTest` is a known flake (re-run, don't fix).

## Ship order & gating
Single PR. Contract-schema bump (schema + `regen.sh` + committed pydantic); NO `getVersion` marker
(continueAsNew payload is not replay-command-checked — mirror v3). TDD, spotless on `contract` AND
`services/orchestrator`, operator merge gate (real-money kill-switch path). No `tenants/*.yaml` /
ConfigMap change. Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
