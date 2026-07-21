# PLAN — 2026-07-21 kill-switch alert clarity (data-blip trip ≠ loss breach; sign the P&L)

Phase 1 of PLAN-2026-07-21-account-cap-failclose-and-silent-inactive, split out as its own shippable
PR. On 2026-07-21 the prod_real account cap tripped `auto:account_mtm_unavailable` (a fail-closed
*data-availability* trip on a transient quote miss) on a **profitable** day, and the repeating
`AccountKillSwitchStillHolding` page showed an unsigned `MTM 1551.0` — which reads exactly like a
$1,551 loss when it is in fact a **+$1,551 unrealized gain**. Both the trip framing and the unsigned
number caused a benign event to look like a loss breach. This phase fixes the *alerting only* — no
trip logic changes.

## P0 — operator: none (alerting-only change).

## Phase 1 — Distinguish MTM-unavailable from a loss breach + sign the P&L (orchestrator alerter)
**Goal:** an `auto:account_mtm_unavailable` trip must NOT read as a loss-cap breach, and `open_mtm`
must render as **signed unrealized P&L** (green when ≥0).

**Changes** (anchors — verify by reading before editing):
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/alert/KillSwitchAlerter.java`
  (and/or whichever alerter renders `KillSwitchTripped` + `AccountKillSwitchStillHolding` embeds):
  branch the embed on the trip `reason`:
  - `auto:account_mtm_unavailable` → a distinct **fail-safe-halt** framing (e.g. "Account cap tripped
    — account value temporarily unreadable (fail-safe halt), NOT a loss"), color YELLOW, not the RED
    loss-breach framing.
  - `auto:account_daily_loss` (real breach) → the existing loss-breach RED framing, unchanged.
- The `AccountKillSwitchStillHolding` re-page subject carries `open_mtm` (computed
  `(bid−entry)×qty×100`, i.e. **unrealized P&L**, in `AccountKillSwitchWorkflowImpl.emitStillHolding`
  ~:633-681). Render it in the alert as **signed unrealized P&L** with a clear label ("unrealized P&L
  +$1,496", green ≥0 / red <0) instead of a bare `MTM 1551.0`. If a sign/label needs a subject field,
  add it to the `emitStillHolding` subject — that is an **activity-input payload (replay-safe, no
  version gate)**; do not add any new workflow command.

**Replay safety:** NONE required — the alerter is an out-of-workflow `@TransactionalEventListener`
consumer; any `emitStillHolding` subject-field addition is activity input, not a Temporal command
shape (1.27 replay ignores activity inputs). Do NOT add a `getVersion` gate.

**Tests (TDD, named):** in the alerter's test class —
- `mtmUnavailableTrip_rendersFailSafeFraming_notLoss`: a `KillSwitchTripped` with
  `reason=auto:account_mtm_unavailable` → YELLOW fail-safe framing, no "loss"/"breach" wording.
- `dailyLossTrip_rendersBreachFraming`: `reason=auto:account_daily_loss` → existing RED breach framing.
- `stillHolding_positiveMtm_rendersSignedGain`: an `AccountKillSwitchStillHolding` with
  `open_mtm=1551.0` → renders "unrealized P&L +$1,551" (signed, green), not a bare "MTM 1551.0".
- `stillHolding_negativeMtm_rendersSignedLoss`: negative open_mtm → signed red loss.

**Verify / success criteria:** `mvn -pl services/orchestrator -am spotless:apply` +
`mvn -pl services/orchestrator -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<AlerterTest> test`.
Behavioral assertion: the 2026-07-21 event shapes render as "fail-safe halt, unrealized P&L +$1,551"
(green), with zero "loss"/"breach" wording on the MTM-unavailable path. Spotless clean on
`services/orchestrator`. No new audit KIND (unless the alerter needs one — then register in
`AuditEventKinds.ALL_KINDS`; prefer reusing existing kinds). No `tenants/*.yaml` change (no ConfigMap
drift). No Temporal version gate.

## Ship order & gating
Single phase, single PR. TDD, spotless on `services/orchestrator`, operator merge gate
(trading-critical alerting path). Sibling phases (Phase 2 debounce — version-gated; Phase 3 RED
"cap-not-protecting" escalation) ship separately from
PLAN-2026-07-21-account-cap-failclose-and-silent-inactive.

Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
