# PLAN — 2026-07-07 remove `approver_id_2` / retire dual-control (single-operator, self-service)

The system is run by a single operator (and each live tenant is one person managing their own real
money), so every two-person ("dual-control") gate is unsatisfiable — there is no second approver.
This plan removes `approver_id_2` from the kill-switch reset path, retires the now-dead dual-control
live-promotion sign-off, and gives the tenant user self-service control of their own account
daily-loss kill switch. Follow-up to PR #568 (which already relaxed `approver_id_2` to *optional* on
the strategy-reset schema for the one-click activation path).

## Findings (verified by reading)
- **Strategy kill-switch** is already single-operator in practice: the one-click **Activate** button
  (`LiveActivationWorkflow.activateLive`) untrips via #568's `reset_on_activation`. The separate
  dual-control `POST /reset` endpoint still carries `approver_id_2`
  (`KillSwitchController.java:79-97`, `KillSwitchWorkflowImpl.resetValidator:368-378`).
- **Account daily-loss kill-switch** (`WorkflowIds.accountKillswitch(tenantId)`, tenant-scoped) has
  a `reset_account_killswitch` update + `account_killswitch_state` query, but **no HTTP endpoint and
  no UI** — it can only be reset by an internal cascade today. Its `resetValidator`
  (`AccountKillSwitchWorkflowImpl.java:766-781`) requires two distinct approvers.
- **Live-promotion `/approve`** is **vestigial**: `LivePromotionActivitiesImpl.activate:100-107` (the
  Activate button path) already emits the SAME gate-readable `LivePromotionApproved` audit row —
  single-operator, no `approver_id_2` — that the copytrade gate `checkLivePromotion` reads
  (`AuditQueryActivitiesImpl:361-399`, `CopytradeSignalWorkflowImpl:599`). The dual-control
  `POST /promotion/approve` → `KillSwitchWorkflow.recordLivePromotion` → `LivePromotionActivities.approve`
  path has no UI and no other caller. Going live is automatic via Activate.
- **Shared DTO coupling:** `ResetKillSwitchRequest` (with `approver_id_2`) is used by BOTH the
  strategy and account reset updates, so dropping the field forces both workflow impls to change in
  the same PR to compile.

## Decisions (operator, 2026-07-07)
- Remove `approver_id_2` from all three controls (single-operator).
- **Retire** the `/promotion/approve` dual-control endpoint entirely (dead code — Activate supersedes it).
- The **tenant user** manages their **own** account daily-loss kill-switch reset via the tenant
  dashboard ("their own money, their risk") — accepted risk-posture change, no compensating control
  beyond the reset cooldown + audit trail.

## Replay safety (per phase)
- Stripping `approver_id_2` from a validator body or from an audit **subject map** (an activity input
  payload) is NOT a command-shape change and NOT replay-checked — no `Workflow.getVersion` gate
  (constraint 1). Prior audit rows keep their value; only new rows omit it.
- **The one replay-sensitive item** is retiring `KillSwitchWorkflow.recordLivePromotion`
  (`@UpdateMethod` on a long-running, continue-as-new workflow). Removing an update handler that any
  live history has processed breaks replay. Phase 3 gates this: verify on homelab that NO
  `LivePromotionApproved` row was ever written by the dual-control actor
  (`api-gateway:/promotion/approve`) and no `record_live_promotion` update exists in any
  `KillSwitchWorkflow` history. If clean → remove the handler + DTO. If ANY exists → keep the handler
  + `LivePromotionApprovalRequest` DTO as an orphaned no-op (still delete the gateway endpoint), and
  note the residual in the PR.

## P0 — Operator / posture notes (no code)
- Deploy touches **orchestrator** (workflows/activities), **api-gateway** (controllers, header), and
  **tenant-dashboard-bff** + **dashboard** (Phase 2). Exec untouched. No ConfigMap / tenant-YAML /
  flag changes (constraints 3,4,7 N/A). No new audit kinds (constraint 5 N/A) — `LivePromotionApproved`
  stays (emitted by Activate); its subject convention is reconciled in Phase 4.
- After deploy a single operator can reset either kill switch and go live alone; a tenant user can
  reset their own account daily-loss halt. Intended.

## Phase 1 — Kill-switch reset → single-operator (orchestrator, api-gateway, contract)
**Goal:** `reset_killswitch` and `reset_account_killswitch` require only `approver_id_1`; drop
`approver_id_2` from the shared DTO, both validators, both audit subjects, and the strategy `/reset`
HTTP path.
**Changes** (anchors):
- `contract/schemas/reset-killswitch-request.json` — delete the `approver_id_2` property; rewrite the
  description (single-operator; `required` already just `schema_version`, `approver_id_1`). Regen
  Java POJO + Python pydantic (`contract/python/regen.sh`).
- `services/orchestrator/.../KillSwitchWorkflowImpl.java` — `resetValidator:368-378` remove the
  a2/`approver_id_2_required`/`approvers_must_differ` checks (keep `not_tripped` +
  `approver_id_1_required`); `reset:386-395` drop the `"approver_id_2"` subject entry. Leave
  `resetOnActivation` untouched (already single-operator).
- `services/orchestrator/.../AccountKillSwitchWorkflowImpl.java` — same edits at `resetValidator:766-781`
  and `reset:792-805`.
- `services/api-gateway/.../KillSwitchController.java:79-97` — stop reading `X-Approver-Id-2` / calling
  `setApproverId2` (single-operator reset). Leave `TenantContext.approverId2()` in place until Phase 4.
**Tests (TDD):** `KillSwitchWorkflowImplTest` + `AccountKillSwitchWorkflowImplTest`: reset with ONLY
`approver_id_1` now succeeds (untrips, arms cooldown, emits `KillSwitchResetApproved`), subject has no
`approver_id_2`; remove the `approver_id_2_required` / `approvers_must_differ` expectation tests.
`KillSwitchControllerTest`: `POST /reset` without `X-Approver-Id-2` forwards a valid reset.
**Verify:** `mvn -pl services/orchestrator,services/api-gateway,contract/java -am spotless:apply` +
`spotless:check` + module `test` (`KillSwitchWorkflowImplTest` flake → re-run, constraint 8);
`contract/python` round-trip green (constraint 6). Behavioral: a `reset_killswitch` update with only
`approver_id_1` untrips (no `approver_id_2_required` throw); subject omits `approver_id_2`.

## Phase 2 — Tenant self-service: account daily-loss kill-switch reset (tenant-dashboard-bff, dashboard)
**Goal:** the tenant user can view and reset their own account daily-loss kill switch from the tenant
dashboard.
**Changes** (anchors):
- New `services/tenant-dashboard-bff/.../web/AccountKillSwitchController.java` — `GET` state +
  `POST` reset for the caller's tenant only. Resolve tenant from `TenantContext` (fail-closed 401 on
  missing `X-Tenant-Id`; NEVER fall back to `dev`). Address the workflow via
  `WorkflowIds.accountKillswitch(tenant)` on the existing `WorkflowClient` bean
  (`TemporalClientConfig`): `stub.query("account_killswitch_state", KillSwitchState.class)` and
  `stub.update("reset_account_killswitch", Void.class, req)` with `approver_id_1 = "tenant:" + <member>`
  (single-operator, post-Phase-1 DTO). Mirror `api-gateway KillSwitchController` shape.
- `dashboard/` — a tenant-facing button + server action (mirror `ActivateButton.tsx` /
  `StrategySwitch.tsx`: `useTransition`, FormData, server action → tdbff), shown only when the account
  switch is tripped. Reuse the `writeEnabled` dark-launch gate pattern.
**Tests (TDD):** `AccountKillSwitchControllerWebMvcTest` — reset for the authenticated tenant issues
the `reset_account_killswitch` update; a missing/blank `X-Tenant-Id` → 401 (no cross-tenant reset);
state query maps `account_killswitch_state`. Dashboard: server-action test if the harness supports it,
else a component render test for tripped/not-tripped.
**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply && spotless:check` + module
`test`; dashboard `npm run lint`/`build`. Behavioral: tenant A cannot reset tenant B's switch (401);
a tripped account switch, after the tenant clicks reset, reports not-tripped via the state query.
**No DTO/schema change** (reuses the Phase-1 single-operator `ResetKillSwitchRequest`) — so this phase
must ship AFTER Phase 1.

## Phase 3 — Retire the dead `/promotion/approve` dual-control path (api-gateway, orchestrator, contract)
**Goal:** delete the vestigial dual-control sign-off; going live stays automatic via Activate.
**Pre-req (replay gate — do FIRST):** on homelab, confirm zero historical use:
`SELECT count(*) FROM audit_log WHERE kind='LivePromotionApproved' AND subject->>'approver_id_2' IS NOT NULL;`
(and/or actor `api-gateway:/promotion/approve`) AND no `record_live_promotion` update in any
`KillSwitchWorkflow` history. Clean → full removal below. Not clean → keep the workflow update handler
+ `LivePromotionApprovalRequest` DTO as an orphaned no-op, still delete the gateway endpoint, and note
the residual.
**Changes** (anchors, full-removal path):
- Delete `services/api-gateway/.../web/PromotionController.java` (`POST /approve`).
- `services/orchestrator/.../activities/LivePromotionActivitiesImpl.java` — remove `approve()` (`:50-76`)
  + its `validate()` a2 branch; KEEP `activate()`/`deactivate()` (the live single-operator path).
- `services/orchestrator/.../workflows/KillSwitchWorkflow.java` + `KillSwitchWorkflowImpl.java` — remove
  `recordLivePromotionValidator` (`:454-469`) + `recordLivePromotion` (`:472+`) @UpdateMethods.
- `contract/schemas/live-promotion-approval-request.json` — delete (no remaining consumer); drop the
  generated Java/Python `LivePromotionApprovalRequest`. Regen.
**Tests (TDD):** delete `PromotionControllerTest`, `LivePromotionApprovalIT`, `AuditQueryLivePromotionIT`,
and the `approve()`/dual-control cases in `LivePromotionActivitiesImplTest`. KEEP the `activate()` /
`checkLivePromotion` gate tests (the live path). Grep gate: no source ref to `PromotionController` /
`recordLivePromotion` / `LivePromotionApprovalRequest` outside `target/`.
**Verify:** `mvn -pl services/api-gateway,services/orchestrator,contract/java -am spotless:apply &&
spotless:check` + module `test`; `contract/python` round-trip. Behavioral: the Activate flow still
emits a `LivePromotionApproved` row and a copytrade BTO still passes `checkLivePromotion` (regression
that Activate — not `/approve` — is the live source).

## Phase 4 — Remove orphaned `X-Approver-Id-2` plumbing + reconcile docs (api-gateway, contract-docs)
**Goal:** delete the dead header extractor and stale dual-control conventions. No behavior change
(both callers gone in Phases 1 + 3).
**Changes** (anchors):
- `services/api-gateway/.../web/TenantContext.java` — remove `HEADER_APPROVER_2` (`:26`),
  `approverId2()` (`:166`), scope-note javadoc (`:151`). Grep must show zero source refs.
- `contract/schemas/audit-event.json` — edit the `KillSwitchResetApproved` + `LivePromotionApproved`
  descriptions to drop "two distinct approvers"; state single-operator (`LivePromotionApproved` now
  carries `operator_id` + `activation_mode` from `activate()`). Regen + round-trip (description-only).
- `docs/ops/live-promotion-rollback.md` §Sign-off recording — update SOP to single-operator / Activate.
**Tests (TDD):** remove any `approverId2` case in `TenantContextTest`. Grep gate:
`grep -rn 'approverId2\|approver_id_2\|Approver-Id-2' services contract --include=*.java --include=*.json | grep -v /target/`
returns nothing.
**Verify:** `mvn -pl services/api-gateway,contract/java -am spotless:apply && spotless:check` + module
`test`; `contract/python` round-trip. No `approverId2` refs outside generated `target/`.

## Ship order & gating
1. **Phase 1** (kill-switch resets → single-operator) — own PR, operator merge gate. Deploy: roll
   orchestrator + api-gateway; verify a single-operator reset untrips.
2. **Phase 2** (tenant self-service account reset) — own PR, AFTER Phase 1 (reuses the new DTO).
   Deploy: roll tenant-dashboard-bff + dashboard.
3. **Phase 3** (retire `/promotion/approve`) — own PR; run the replay pre-req query first. Deploy:
   roll api-gateway + orchestrator; regression-verify Activate still promotes.
4. **Phase 4** (header + docs cleanup) — own PR, LAST (depends on Phases 1 + 3 removing both callers).
- Each phase: TDD, `spotless:apply` on every touched module, contract regen + round-trip, own PR.
- `risk-manager` sign-off requested on Phase 2 (tenant-controlled account loss-cap reset) before merge.
