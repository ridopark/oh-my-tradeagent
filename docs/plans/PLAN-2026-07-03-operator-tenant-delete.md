# PLAN — 2026-07-03 operator tenant-delete

A guarded operator "Delete tenant" action on `/admin/tenants` that completely de-provisions an
already-verified, **dark, never-traded** tenant across every store, and is **structurally incapable**
of touching a live (real-money) tenant. Motivated by `staging-paper-2` — a paper tenant created to
test onboarding (verified account `PA3M4QTHNB95`), left `enabled=false`, 0 orders ever, 0 members;
its footprint = `strategy_config` (orchestrator), `broker_credentials` (exec_alpaca_paper),
`audit_log` recon noise (orchestrator), a per-tenant recon Temporal schedule + kill-switch workflow.
Design sources: risk-manager guardrail policy + java-architect backend architecture map (this session).

Ships **DARK** (every phase flag-gated, default off) like every other operator feature here.

---

## Guardrail policy (the spec every phase must satisfy)

Delete is for **de-provisioning a dark, never-traded tenant** — NOT for winding down a live tenant
(that is a separate manual multi-day flatten/settle operation). The endpoint refuses anything live or
active. Preconditions evaluated in order; first failure → **409** `{blocked_by, detail}`; any
unreadable signal = NOT deletable (fail-closed).

- **P0 — LIVE_BROKER_TARGET (load-bearing):** no `(tenant,strategy)` row has a `broker_target` ending
  `-live`. Unconditional hard block, evaluated first, not overridable. Real money and the delete path
  share no reachable code. This is the single control that makes deleting `prod_real` impossible.
- **P1 — ACTIVE_LIVE_ACTIVATION:** `LivePromotionState` ABSENT or fully deactivated+expired.
- **P2 — STRATEGY_ENABLED:** every strategy of the tenant is `enabled=false` (dark). Forces the
  two-step (disable first, via the existing deactivate/StrategySwitch path, as a prior action).
- **P3 — OPEN_WORKFLOWS:** zero non-terminal `PositionWorkflow` executions for the tenant AND the
  per-tenant recon schedule quiesced. (Deleting config under a running PW orphans it.)
- **P4 — BROKER_NOT_FLAT:** broker reports zero open positions and zero open/pending orders.
- **P5 — HAS_TRADE_HISTORY:** `order_intent_journal` empty (never placed an order). A traded tenant —
  even paper — is deliberately NOT deletable via this button (retention-aware manual op).

Effect on the fleet: `prod_real`→P0, `staging_paper`→P2/P5, `staging-paper-2`→passes all. Exactly the
intended blast radius.

**Confirmation (both):** (1) tenant already dark (P1+P2) from a **prior separate** operator action —
delete refuses to also disable; (2) request body `confirm_tenant_id` must string-equal the path
`{tenant}` (case-sensitive) → else **400 CONFIRM_MISMATCH** before any store is touched.

**Delete vs retain:** DELETE `strategy_config`, `broker_credentials`, `dashboard_user`/`_invite`,
Temporal recon schedule + kill-switch WF. **RETAIN `audit_log`** (hash-chained append-only — write a
`TenantDeleted` tombstone, never row-delete; deleting breaks chain verification for ALL tenants) and
`order_intent_journal` (moot per P5).

**Execution ordering (idempotent, no cross-store txn) — disarm first, so a mid-failure leaves a
*disarmed* tenant, never a half-alive one:**
1. Re-assert P0–P5 at execution time (TOCTOU guard).
2. Disable: `enabled=false` on all strategies (drops it from copytrade fan-out + arm gate). Idempotent.
3. Resolve `broker_target` from config **now** (needed for the schedule id), then delete the recon
   schedule `recon-v2-t-<tenant>-s-<strategy>-<brokerTarget>` (`handle.delete()`; absent = ok).
4. Terminate the kill-switch workflow `…/killswitch` (absent = no-op).
5. Delete `broker_credentials` (exec HTTP DELETE; 0 rows = success).
6. Delete `strategy_config` rows (orchestrator writer, in-txn audit).
7. Delete `dashboard_user` + `dashboard_user_invite` (BFF, new grant).
8. Emit `TenantDeleteCompleted` tombstone into audit_log.

Partial failure → 207/500 with `completed_steps[]` + `failed_step`, **re-runnable** (every step treats
"already gone" as success; no compensation/rollback).

**Audit events:** `TenantDeleteRequested` (operator_id, tenant, confirm_tenant_id, precondition
snapshot, flag_state, request_id), `TenantDeleteCompleted` (deleted_stores[] with row counts,
retained_stores[], temporal_schedule_deleted, duration_ms), `TenantDeleteBlocked` (blocked_by, detail),
`TenantDeleteStepFailed` (failed_step, completed_steps[], error). Register new kinds in
`AuditEventKinds.ALL_KINDS`.

## P0 — Immediate operational (no code; operator, AFTER the feature ships + cuts over)
- Delete `staging-paper-2` via the new button (its whole reason to exist). Until then it stays dark
  and harmless (empty recon cycles only).

---

## Phase 1 — exec: broker_credentials delete + endpoint (exec)
**Goal:** exec can delete a tenant's stored (envelope-encrypted) broker credentials, dark-gated.
**Changes** (anchors):
- `services/exec/src/main/java/com/ohmytradeagent/exec/broker/alpaca/BrokerCredentialWriter.java:245`
  — add `delete(tenantId, provider)` → `DELETE FROM broker_credentials WHERE tenant_id=? AND
  provider=?`, idempotent (0 rows = success), returns rows-deleted.
- `services/exec/src/main/java/com/ohmytradeagent/exec/web/BrokerCredentialAdminController.java:42` —
  add `DELETE /internal/broker-credentials` (body/params `{tenant_id, provider}`), behind a new dark
  gate `broker.credentials.delete.enabled` (mirror the existing write gate in `ExecAdminTokenFilter`).
  Service-token gated (existing filter). Never logs key material.
**Tests (TDD):** `BrokerCredentialWriterTest` delete removes only the matching row + is idempotent
(delete-absent = 0, no throw); `BrokerCredentialAdminControllerTest` DELETE 200 + dark 404 + wrong
token 401.
**Verify:** `mvn -pl services/exec -am spotless:apply` + `mvn -pl services/exec test`. Behavioral: a
seeded (tenant,provider) row is gone after DELETE; a second DELETE returns success with 0 rows.

## Phase 2 — orchestrator: strategy_config delete + Temporal teardown workflow (orchestrator, contract)
**Goal:** an orchestrator-owned durable teardown: delete strategy_config (audit-chained), delete the
recon schedule, terminate the kill-switch WF — as a retryable `TenantDeleteWorkflow`.
**Changes** (anchors):
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/platform/StrategyConfigWriter.java:206`
  — add `delete(tenantId, strategyId, actor)`: `DELETE FROM strategy_config WHERE tenant_id=? AND
  strategy_id=?` inside `dsl.transaction(...)` with an in-txn audit event (mirror create's audit at
  `:231`). Idempotent.
- New `TenantDeleteWorkflow` + activities (mirror `LiveActivationWorkflowImpl`): activities for
  (a) resolve broker_target + delete recon schedule via
  `ReconciliationScheduleBootstrapper`-style `scheduleClient.getHandle(id).delete()` (id grammar
  `recon-v2-t-<tenant>-s-<strategy>-<brokerTarget>`, `ReconciliationScheduleBootstrapper.java:276`;
  not-found = ok, `:254`), (b) terminate kill-switch WF `WorkflowIds…/killswitch`
  (`contract/java/.../identity/WorkflowIds.java:17`), (c) `StrategyConfigWriter.delete`. NEW workflow
  (not a change to a running one) → **no replay version gate needed**; but the workflow itself must be
  deterministic + each activity idempotent.
  **Ordering trap:** resolve `broker_target` from config BEFORE deleting the config row, else the
  schedule id is uncomputable → zombie schedule (`ReconciliationScheduleBootstrapper.java:264`).
- `services/audit/.../AuditEventKinds.java` — register `TenantDeleteRequested/Completed/Blocked/
  StepFailed` + `TenantDeleted` (else `KindRegistryGuardTest` blocks the push).
**Tests (TDD):** `StrategyConfigWriterTest` delete removes the row + writes the audit event + is
idempotent; `TenantDeleteWorkflowImplTest` (TestWorkflowEnvironment) drives the happy path + each
activity idempotent (schedule-absent, WF-absent, config-absent all succeed); ordering test proving
broker_target is resolved before config delete.
**Verify:** `mvn -pl services/orchestrator,services/audit,contract/java -am spotless:apply` + module
tests. Behavioral: after the workflow, strategy_config row gone, schedule deleted, kill-switch WF
terminated, `TenantDeleted` in audit_log.

## Phase 3 — dashboard DB: DELETE grant + BFF delete repo + operator endpoint (tenant-dashboard-bff)
**Goal:** the BFF can delete a tenant's dashboard_user/invite rows as an operator action (the only
store api-gateway can't reach directly).
**Changes** (anchors):
- New migration `services/tenant-dashboard-bff/src/main/resources/db/dashboard/
  V7__grant_delete_dashboard_writer.sql` — `GRANT DELETE ON dashboard_user, dashboard_user_invite TO
  dashboard_writer;` (never edit shipped V5). Additive.
- `services/tenant-dashboard-bff/src/main/java/com/ohmytradeagent/tdbff/invites/InviteWriterRepository.java`
  (or a sibling writer repo) — add `deleteTenantIdentities(tenantId)`: in one tx `DELETE FROM
  dashboard_user WHERE tenant_id=?` + `DELETE FROM dashboard_user_invite WHERE tenant_id=?`, returns
  counts. Idempotent.
- New BFF operator route `POST /api/admin/tenant-delete/dashboard-rows` (or `DELETE
  /api/admin/tenants/{tenant}/dashboard-rows`) — dark-gated `operator.tenant-delete.enabled` AND
  `dashboard.writer.enabled`; the always-on `ServiceTokenFilter` bearer-gates it (do NOT add to
  shouldNotFilter); `requireAllowlistedOperator`. Body/path `{tenant}`.
**Tests (TDD):** `DashboardWriterMigrationIT` — writer can now DELETE dashboard_user + invite (and
still the read-only role cannot); repo test deletes both tables by tenant + idempotent; WebMvc slice
for the route (200, dark 404, 401 no-bearer, 403 non-allowlisted).
**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply` + module test; the IT runs in CI
(`RUN_DB_ITS`). Behavioral: seeded member+invite rows for a tenant are gone after the call; second call
succeeds with 0 rows.

## Phase 4 — api-gateway: TenantDeleteController + guards + orchestration + audit (api-gateway)
**Goal:** the operator entrypoint that enforces P0–P5 + confirm, then orchestrates steps 2–8.
**Changes** (anchors):
- New `TenantDeleteController` (mirror
  `services/api-gateway/.../web/CreateTenantController.java:50` gating + `:77`
  `requireAllowlistedOperator`) — `POST /admin/tenants/{tenant}/delete` body `{confirm_tenant_id}`.
  Dark gate `@ConditionalOnProperty("operator.tenant-delete.enabled")`.
- **Guards (read current state; fail-closed):** P0/P2 via `StrategyConfigReader`
  (`services/api-gateway/.../web/StrategyConfigReader.java:41` — list all strategies for the tenant,
  check no `-live` broker_target + all `enabled=false`); P1 via live-promotion read; P3 via the
  `PositionsController` SA query (`PositionsController.java:66` — Running PositionWorkflows) + recon
  schedule presence; P4 via a broker flat/positions check (reuse exec/broker read hop); P5 via an
  order_intent_journal count (exec read hop). `confirm_tenant_id` exact-match → 400 else.
- **Orchestration:** on all-pass → disable (existing `StrategyConfigWriter.update` path / strategy
  toggle) → start `TenantDeleteWorkflow` (Phase 2) → call exec `DELETE /internal/broker-credentials`
  (Phase 1, via `BrokerCredentialForwardService.java:168` hop) → call BFF dashboard-rows delete
  (Phase 3) → emit `TenantDeleteCompleted`. Each step idempotent; partial failure → 207 + payload.
  Emit `TenantDeleteRequested`/`Blocked`/`StepFailed` per the policy.
- `services/api-gateway/.../security/ServiceTokenFilter.java:45` — OR `operator.tenant-delete.enabled`
  into the `@ConditionalOnExpression` (path prefix `/admin/tenants/` already covered at `:56`). Do NOT
  add to `shouldNotFilter`.
- `services/api-gateway/src/main/resources/application.yml` `operator:` block — add
  `tenant-delete.enabled: ${OPERATOR_TENANT_DELETE_ENABLED:false}`.
**Tests (TDD):** controller tests for EACH precondition returning the right 409 `blocked_by`
(P0 live-target block is the critical one — a `-live` tenant is rejected with zero teardown), confirm
mismatch → 400, happy path orchestrates the calls in order, non-allowlisted → 403, dark → 404, service
token missing → 401. Mock the exec/orchestrator/BFF hops.
**Verify:** `mvn -pl services/api-gateway -am spotless:apply` + module test. Behavioral: a synthetic
`-live` tenant → 409 LIVE_BROKER_TARGET, no downstream call made; a dark paper tenant with a matching
confirm id → orchestration fires all steps.

## Phase 5 — dashboard UI: delete button + type-to-confirm modal (dashboard)
**Goal:** the operator UI, dark-gated, wired to Phase 4.
**Changes** (anchors):
- `dashboard/app/admin/tenants/page.tsx` — a per-tenant "Delete" action (only rendered for
  `mode !== 'live'` tenants AND when all its strategies are dark — belt-and-suspenders with the
  server P0/P2; a live row shows no delete affordance at all) behind `OPERATOR_TENANT_DELETE_ENABLED`.
- New `dashboard/components/DeleteTenantButton.tsx` — a client modal requiring the operator to type
  the exact tenant id to enable the confirm; server action re-verifies operator + posts to the
  api-gateway `POST /admin/tenants/{tenant}/delete` (via a `lib/adminBff.ts`/`adminActivation.ts`-style
  server client), then `revalidatePath` + coarse result banner (mirror `ActivateButton`).
**Tests (TDD):** component test — confirm button disabled until typed id === tenant id; the live-row
guard hides the button. `tsc`/`next build` clean.
**Verify:** `cd dashboard && npm run typecheck && npm run build`. Behavioral: paper dark row shows a
Delete affordance gated behind an exact-id-typed confirm; live row shows none.

---

## Ship order & gating
1. **Phase 1 (exec)**, **Phase 2 (orchestrator)**, **Phase 3 (bff)** are independent — ship in any
   order (each its own PR, dark). 2. **Phase 4 (api-gateway)** depends on 1+2+3 endpoints existing. 3.
   **Phase 5 (dashboard)** depends on 4. Each: TDD, `spotless:apply` on every touched module, own PR,
   operator merge gate.

## P0 / operator follow-ups (no code)
- Flip flags on homelab: `BROKER_CREDENTIALS_DELETE_ENABLED` (exec), `OPERATOR_TENANT_DELETE_ENABLED`
  (api-gateway + dashboard + bff), `dashboard.writer.enabled` already on. Apply the `V7` migration
  (BFF Flyway on deploy). Roll exec/api-gateway/bff/dashboard.
- Then **delete `staging-paper-2`** via the button; verify all stores clean + `TenantDeleted`
  tombstone in audit_log + the recon loop no longer references it.
- Deploy notes: `deploy.yml` applies per-service manifests only; the flag env are live `kubectl set
  env` overrides (RESTART_ONLY), not repo manifests. Exec-alpaca-live is a manual roll (do NOT need it
  for this paper feature, but the exec code change lands in the image).

## Constraint checklist folded in
- **Spotless per module** (Phase 1/2/3/4 touch Java — apply on every module edited).
- **New audit kinds** registered in `AuditEventKinds.ALL_KINDS` (Phase 2) or `KindRegistryGuardTest`
  blocks the push.
- **No replay version gate** needed — `TenantDeleteWorkflow` is NEW, not a change to a running
  workflow's command shape; existing histories are untouched.
- **audit_log hash-chain** — tombstone only, never row-delete (Phase 2 + Phase 4).
- **`gh pr edit --body` broken** here — set PR body at create time.
- **RUN_DB_ITS** — the Phase 3 dashboard grant IT runs in CI, not locally (WSL Docker).
- **Live-tenant safety (P0)** is the acceptance bar: no phase may make a `-live` tenant reachable by
  the teardown.
