# PLAN — 2026-07-25 /live per-position "Force exit" button

Give the operator a one-click, audited, broker-truth-aware way to exit (or clear a phantom)
a position from the dashboard /live page — the human-in-the-loop answer to "recon deliberately
won't auto-terminate a workflow" (terminating a real position on a stale broker read would orphan
it; recon only auto-*adopts*). The button drives `PositionWorkflow.force_close`, an **existing,
tested** Temporal `@UpdateMethod` (cancels any in-flight exit → places a marketable-to-bid SELL for
the remaining qty; `ForceCloseNoop` when qty==0). **No orchestrator/workflow change → no Temporal
replay concern** — this is purely additive plumbing (a BFF endpoint + a UI button) onto a method
that already ships.

**Phantom handling (verified):** `force_close` on a broker-flat phantom (PW `remainingQty>0`, broker
0) → MARKET SELL → Alpaca 422 → exec confirms `/v2/positions` flat → `state=CANCELLED` →
`handleBenignAlreadyFlatExit` zeroes qty → `PositionClosed` → **workflow terminates, phantom cleared**
(`ExecActivitiesImpl.java:145-164`, `PositionWorkflowImpl.java:2981-2985`). So the one button covers
both real exits and phantom cleanup; it never enters the flatten-fail retry loop (that needs an
*accepted-but-unfilled* resting order, impossible for a rejected MARKET sell).

Source: session forensics + agent path-map. Related: [[reference_live_ops_phantom_and_promotion]],
[[project_killswitch_reset_exposure]] (the operator-action-from-/live precedent).

## P0 — Operator follow-ups (no code)
- After both phases ship + verify: flip the dark-launch flags — the BFF `positions.force-close.write-enabled`
  and the dashboard `FORCE_EXIT_WRITE_ENABLED` — via `kubectl set env` (live-only, survives deploys).
- Real-money gate: the first live use should be an intentional operator test on a small/known position
  (or a confirmed phantom), watched end-to-end (`positionState` → `PositionClosed`).

## Phase 1 — BFF force-close endpoint (tenant-dashboard-bff) — TRADING-CRITICAL
**Goal:** a tenant-scoped, dark-gated `POST /api/positions/force-close` that calls the existing
`force_close` Update, mirroring the kill-switch reset (BFF → Temporal directly; the /live view is a
tenant view with no reliable `X-Operator-Id`, so BFF-direct is the consistent path, not api-gateway).

**Changes** (anchors):
- `services/tenant-dashboard-bff/.../web/PositionsController.java` — add `@PostMapping("/force-close")`
  taking `{workflow_id, reason}`:
  - resolve tenant **fail-closed** from `X-Tenant-Id` (mirror `AccountKillSwitchController.java:107-167`).
  - **Cross-tenant guard (defense-in-depth):** reject 403 unless `workflow_id` starts with
    `WorkflowIds.tenantStrategy(tenant, strategy) + "/"` (mirror the api-gateway guard,
    `api-gateway/.../PositionsController.java:95-99`) — a tenant can only force-close its own PWs.
  - build `ForceCloseRequest{schemaVersion=1, operatorId="tenant:"+tenant, reason}` (validator requires
    non-blank operator_id + reason).
  - `workflowClient.newUntypedWorkflowStub(workflowId).update("force_close", ForceCloseResult.class, fr)`;
    map `ACCEPTED → 202` else 200; return `{status, exit_signal_id}` from the result.
  - **Dark-launch flag** `positions.force-close.write-enabled` (default false → the route 404s / returns
    disabled), exactly like `account-killswitch.reset.write-enabled` (`AccountKillSwitchController.java:66-74`).
- No contract change (`ForceCloseRequest`/`ForceCloseResult` exist). No orchestrator change → **no version gate.**

**Tests (TDD, WebMvcTest, mirror `AccountKillSwitchController*Test`):**
- `forceClose_flagOn_accepted_returns202` — mocked `WorkflowClient.update` returns ACCEPTED → 202 + exit_signal_id.
- `forceClose_missingTenantHeader_failClosed` — no `X-Tenant-Id` → 400/403, no update call.
- `forceClose_crossTenantWorkflowId_rejected403` — a `workflow_id` for another tenant → 403, `verifyNoInteractions(workflowClient)`.
- `forceClose_flagOff_routeDisabled` — flag false → 404 (bean absent) / disabled.
- `forceClose_blankReason_rejected400` — validator/`@RequestBody` guard.

**Verify / success criteria:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply` then
`mvn -pl services/tenant-dashboard-bff -am test`. Behavioral: flag-off ⇒ no route; flag-on + own-tenant
workflow_id ⇒ `update("force_close", …)` called with `operatorId="tenant:<t>"`; cross-tenant id ⇒ 403 before
any Temporal call. **Constraints:** spotless (bff module); **no Temporal replay** (force_close pre-exists);
**operator merge gate (real-money exit capability).** Ships FIRST, dark.

## Phase 2 — Dashboard "Force exit" button (dashboard) — non-trading-critical, dark
**Goal:** a per-position button on the /live Holdings table → the Phase-1 endpoint, with an inline
confirm (no modal component exists in the repo) and a broker-truth hint.

**Changes** (anchors):
- `dashboard/lib/bff.ts` — add `forcePositionExit(workflowId, reason)` via `bffPost` (mirror
  `resetAccountKillSwitch`, `:118-140`), typed `{ok, status, exitSignalId?}` result; `AdminReadDisabledError`-style
  handling of the flag-off 404.
- `dashboard/app/live/page.tsx` — a `"use server"` `forceExitAction` (mirror `resetKillSwitchAction`,
  `status/page.tsx:34-60`: re-verify session, call `forcePositionExit`, `revalidatePath`) + an `actions`
  column on the Holdings `DataTable` (`:152-163`): `{ key:"actions", render:(_,row)=><ForceExitButton
  workflowId={row.workflow_id} symbol={row.contract} qty={row.remaining_qty} hasBrokerMark={row.current_mark!=null} action={forceExitAction}/> }`.
- `dashboard/components/ForceExitButton.tsx` — new client island modeled on `AccountKillSwitchReset.tsx`:
  `useTransition`, an **inline two-click confirm** ("Force exit" → "Confirm — sells at market"), shows
  symbol + qty, and a **phantom hint** when `hasBrokerMark===false` ("broker shows no position — this will
  clear the tracking"). Calls `forceExitAction(workflowId, reason)`.
- **UI dark flag** `FORCE_EXIT_WRITE_ENABLED` gates the column/button visibility, paired with the Phase-1
  server flag (button hidden until both on).

**Tests / verify:** repo has no dashboard test framework → `cd dashboard && npx tsc --noEmit && npm run build`;
render a throwaway `app/obcheck/page.tsx` client component with mock props (per the dashboard-verify pattern)
to eyeball the button + confirm states + phantom hint. **Dark by default** (flag off ⇒ column hidden ⇒
byte-identical /live).

## Ship order & gating
1. **Phase 1** (BFF endpoint, dark flag off) — own PR, **operator merge gate (real-money)**.
2. **Phase 2** (dashboard, dark flag off) — own PR.
3. **Operator:** deploy both → flip `positions.force-close.write-enabled` + `FORCE_EXIT_WRITE_ENABLED` →
   verified operator test-exit.
Each: spotless (Java) / tsc+build (dashboard), single-concern PR, dark-launch flag so nothing is reachable
until the operator flips it. No workflow/replay change in either phase.
