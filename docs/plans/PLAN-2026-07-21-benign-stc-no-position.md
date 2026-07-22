# PLAN — 2026-07-21 benign STC-no-position Discord message (stop the red "order FAILED" page)

When a copytrade STC (sell-to-close) arrives after the position is **already fully closed**, the
system pages a red `:rotating_light: Copytrade order FAILED — STC (exit)` alert with kind
`OrphanSTC`. This is not a failure — with "taking profit as it comes" scale-outs the author sends
several STCs, and once we're flat the extra ones have nothing to sell (e.g. the prod_real NVDA
2026-07-20 follow-on STC @2.70 at 10:21). The operator wants a **message still sent** to Discord,
but as a **benign informational** note ("STC — no position left"), not a FAILED page.

`OrphanSTC` currently covers three causes, distinguishable by the audit `reason`:
- **A** — no PositionWorkflow found (already closed / never existed) — `handleStc` ~940-948, no `reason` key.
- **B** — PositionWorkflow found (stale Redis cache) but not RUNNING — ~958-968, `reason=position_workflow_not_running`.
- **C** — PositionWorkflow genuinely RUNNING but the `partialExit` dispatch threw — ~1040-1053, `reason=signal_dispatch_failed`.

A and B are the same situation ("no open position, nothing to sell") — the only difference is a
lingering Redis cache entry (the cache is not evicted on close; `findPositionWorkflowId` returns it,
then the running-guard's fresh Temporal describe finds it COMPLETED). **C is a real failure** (a
live position we could not reach to sell) and stays a red page.

**Scope decision (operator-approved 2026-07-21):** downgrade **A + B** to a benign Discord message;
**keep C** as the red failure. The red alert never took any protective action anyway — `recon`
(5-min reconciliation) is the actual phantom safety net, so downgrading A/B's framing loses no
protection.

**Source:** live forensics this session (NVDA 2026-07-20 incident) + OrphanSTC alert-path map.

## P0 — Immediate operational (no code; operator)
- **None.** No open exposure; this is an alert-severity/UX change only.

## Phase 1 — Benign "no open position" STC message for A+B (orchestrator + audit)

**Goal:** STC-when-already-flat (causes A and B) emits a NEW benign audit kind and a YELLOW
informational Discord message; it no longer pages RED. Cause C keeps emitting `OrphanSTC` and keeps
paging RED (unchanged).

**Changes** (anchors — verify by reading before editing):
- `services/audit/src/main/java/com/ohmytradeagent/audit/AuditEventKinds.java` — register a new kind
  `StcNoOpenPosition` in `ALL_KINDS` (line ~155, beside `OrphanSTC`), in **ALL_KINDS only** (no
  lifecycle group), mirroring the benign `PartialExitAlreadyFlat` (line ~234). Required or the
  pre-push `KindRegistryGuardTest` blocks it.
- `services/orchestrator/.../workflows/CopytradeSignalWorkflowImpl.java`:
  - Add `KIND_STC_NO_OPEN_POSITION = "StcNoOpenPosition"` beside `KIND_ORPHAN_STC` (line ~71).
  - **Site A** (~940-948) and **Site B** (~958-968): change the `logAudit(... KIND_ORPHAN_STC ...)`
    to `KIND_STC_NO_OPEN_POSITION`. Preserve the existing subject fields (signal_id, option_symbol,
    attempts / position_workflow_id, reason). **Site C** (~1040-1053) is UNCHANGED — keeps
    `KIND_ORPHAN_STC` + `reason=signal_dispatch_failed`.
  - Replay safety: `logAudit` is an Activity call; the KIND is Activity **input**, not a Temporal
    command shape → not replay-checked on 1.27 → **no `getVersion` gate**. State this in the PR.
- `services/orchestrator/.../alert/StcNoOpenPositionAlerter.java` (NEW) — mirror
  `UnrecognizedStcTailAlerter` exactly (same `@TransactionalEventListener(AFTER_COMMIT,
  fallbackExecution=true)` seam, per-tenant `webhookResolver`, non-blocking). On an audit event of
  kind `StcNoOpenPosition`, post ONE **YELLOW** (`AlertColors.YELLOW`) embed titled e.g.
  `:information_source: Copytrade STC — no position to close (already flat)` with fields: tenant,
  author, symbol (Yahoo-linked via `YahooOptionLink.markdown`), signal_id, and a one-line note
  ("position already fully closed — nothing to sell; no order placed"). Null-safe subject reads.

**Do NOT change** `OrderFailureAlerter` — `OrphanSTC` stays in `DEFAULT_FAILURE_KINDS`, and since
only Site C now emits `OrphanSTC`, C keeps paging RED. `StcNoOpenPosition` is absent from
`DEFAULT_FAILURE_KINDS`, so it never pages RED.

**Pre-check during implementation:** grep for any OTHER consumer of the `"OrphanSTC"` kind (recon,
dashboards, queries) besides `OrderFailureAlerter`. If recon or another reader keys off `OrphanSTC`
for A/B, either keep those consumers working (they should treat "no position" as benign too) or note
the behavior change. Expected: only `OrderFailureAlerter` consumes it.

**Tests (TDD):**
- `services/orchestrator/.../alert/StcNoOpenPositionAlerterTest.java` (NEW): an audit event kind
  `StcNoOpenPosition` posts exactly one YELLOW embed naming the symbol + signal_id; a non-matching
  kind posts nothing; Redis/webhook failure is swallowed (non-blocking), mirroring
  `UnrecognizedStcTailAlerterTest`.
- `OrderFailureAlerter` test (existing or new case): a `StcNoOpenPosition` event does **NOT** page
  (not in failure kinds); an `OrphanSTC` event (Site C, `reason=signal_dispatch_failed`) **still**
  pages RED — proves C is unchanged.
- `AuditEventKindsTest` / `KindRegistryGuardTest`: `StcNoOpenPosition` ∈ `ALL_KINDS`.
- If a `CopytradeSignalWorkflowImpl` handleStc test exists, assert Site A/B now emit
  `StcNoOpenPosition` and Site C still emits `OrphanSTC`.

**Verify / success criteria:**
`mvn -pl services/orchestrator services/audit -am spotless:apply` then
`mvn -pl services/orchestrator -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='StcNoOpenPositionAlerterTest,OrderFailureAlerterTest,AuditEventKindsTest' test`.
Behavioral assertions: (1) an already-flat STC (A/B) produces a YELLOW "no position" Discord embed
and **zero** RED failure embeds; (2) a Site-C dispatch failure still produces the RED
`order FAILED — STC (exit)` embed. Spotless on both touched modules; `KindRegistryGuardTest` passes
on pre-push; no Temporal version gate; no `tenants/*.yaml` change (no ConfigMap drift).

## Ship order & gating
Single phase, single PR (shipping the emit-change without the alerter would leave A/B **silent** —
the operator explicitly wants a message — so the new kind + its alerter must land together). TDD,
spotless on `services/orchestrator` + `services/audit`, operator merge gate (trading-critical
alerting path).

Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
`gh pr edit --body` is broken here — set the PR body at create time.
