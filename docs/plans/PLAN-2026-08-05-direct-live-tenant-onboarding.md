# PLAN — 2026-08-05 direct-live-tenant-onboarding

Let an operator onboard a brand-new **LIVE** (real-money) copytrade tenant in one shot from the dashboard onboard page — no paper-first / promotion step. Today a live create 400s because the create path never arms the tenant's account-level loss cap, which the live invariant requires. Source: architecture map (this session) + live-cluster verification.

**Root cause.** `StrategyConfigWriter.create` → `validate` reads the account cap via `TenantRegistry.get(tenantId)` (`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/platform/StrategyConfigWriter.java:430-437`) and calls `StrategyConfigInvariants.validateLiveRequiredGates(cfg, pct, threshold, label)` (`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/bootstrap/StrategyConfigInvariants.java:86`), which rejects a `broker_target`-live create unless `accountCapArmed = account_daily_loss_pct>0 OR account_daily_loss_threshold>0`. The onboard create chain — `dashboard/app/admin/onboard/page.tsx:225` `createTenantAction` → `dashboard/lib/adminOnboarding.ts:32` `createTenant` (POST `/admin/tenants/{tenant}/strategies/{strategy}`) → `services/api-gateway/.../web/CreateTenantController.java:70` (builds `StrategyConfigCreateRequest` at `:100-106`, starts `StrategyConfigCreateWorkflow` at `:121`) → `StrategyConfigCreateWorkflowImpl` → `StrategyConfigCreateActivitiesImpl.java:35` `writer.create(...)` — **only writes `strategy_config`; it never arms the cap.** The one existing cap-write path (`TenantConfigController` → `TenantConfigWriter.update`, `services/orchestrator/.../platform/TenantConfigWriter.java:91`) **cannot** arm a fresh tenant: UPDATE-only (throws `TenantConfigNotFoundException` when `row==null`, `:104-105`) AND tighten-only (add-where-none rejected, `:239-242`). So arming must happen **in the create layer**.

**Fix (agreed).** When a live strategy is created and the tenant has no armed cap yet, **INSERT a `tenant_config` cap row atomically inside `StrategyConfigWriter.create`'s existing transaction, before the strategy INSERT**, using an operator-supplied `account_daily_loss_pct` (default 0.20, floored at `TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT = 0.05`). Idempotent (`ON CONFLICT (tenant_id) DO NOTHING`) so adding a 2nd live strategy to an existing tenant reuses the existing cap. No new Temporal workflow command → no `getVersion` gate.

---

## P0 — Assumptions & operator preconditions (no code)

- **LOAD-BEARING ASSUMPTION (verified live 2026-08-05): the cluster runs `TENANT_CONFIG_SOURCE=db` AND `STRATEGY_CONFIG_SOURCE=db`.** In db-mode the cap is read from the `tenant_config` **table** (`DbTenantRegistry.java:28-30`) and the kill switch enumerates DB tenants (`DbTenantStrategies`, wired at `AccountKillSwitchConfig.java:47-49`), so a **DB-only** new tenant (strategy_config + tenant_config rows) is fully functional with **no tenants-ConfigMap edit**. The repo DEFAULT is yaml-mode (`YamlTenantRegistry` `@ConditionalOnProperty tenant.config.source=yaml matchIfMissing=true`); if the cluster ever reverts to yaml-mode the DB arm is not read and direct onboarding breaks. **This plan asserts db-mode; Phase 5 re-verifies it before any live create.**
- `operator.tenant-create.enabled` is already ON in the live cluster (a live create reaches `validate` → 400, not 404). No flag flip needed to reach the new code.
- **Out of scope:** watchlist-trigger fan-out still needs a per-tenant sidecar env step (advisory already shown at `OnboardForm.tsx:534-542`); copytrade signal fan-out is registry-driven (PLAN-2026-07-03) and needs nothing. This plan is copytrade-only.
- **Builds on (do NOT duplicate):** `CreateTenantController` (PLAN-2026-06-28-operator-account-onboarding Phase I), `DbTenantRegistry`/`DbTenantStrategies` (PLAN-2026-07-08 / PLAN-2026-07-22), self-service fan-out (PLAN-2026-07-03). **Do NOT** reuse `TenantConfigController`/`TenantConfigWriter` to arm — it is UPDATE-only + tighten-only by design.

---

## Phase 1 — Contract: carry the cap on the create request (`contract`)

**Goal:** thread an operator-supplied cap across the gateway→workflow→activity boundary.

**Changes** (anchors):
- `contract/schemas/strategy-config-create-request.json` — add optional `account_daily_loss_pct` (`type: number`, `exclusiveMinimum: 0`, `maximum: 1`), OUT of `required` (null = "no cap supplied", preserving today's reject-if-no-cap behavior). This is the DTO the controller builds at `CreateTenantController.java:100-106` and the workflow/activity take (`StrategyConfigCreateWorkflow.java:25`, `StrategyConfigCreateActivities.java:22`).
- Regenerate DTOs (constraint #6): Java POJO auto-generates on build; Python via `contract/python/regen.sh`. Add a Python round-trip test (present + absent→None) in `contract/python/tests/test_round_trip.py`.

**Tests (TDD):** contract round-trip (Java `RoundTripTest` + Python) for `StrategyConfigCreateRequest` with `account_daily_loss_pct` present and absent→null/None.

**Verify:** `mvn -pl contract/java spotless:apply test` + `cd contract/python && bash regen.sh && uv run pytest tests/test_round_trip.py -q`; `git status` clean of regen churn (CI regen-drift guard). No runtime behavior yet.

---

## Phase 2 — Orchestrator: arm the cap atomically on a live create (`orchestrator`, `audit`)

**Goal:** a live `create` with no prior cap arms the `tenant_config` row (in-txn) so the live gate passes; a live create with NO cap supplied still rejects; existing cap never overwritten; paper create unchanged.

**Changes** (anchors):
- `services/orchestrator/.../platform/StrategyConfigWriter.java:222-245` `create(...)` — inside the existing `dsl.transactionResult`, BEFORE `validate(config, tenantId, strategyId)` (`:228`):
  1. Compute **effective cap**: read the current `tenant_config` cap for `tenantId` **on the same transaction connection `tx`** (an in-txn `SELECT account_daily_loss_pct, account_daily_loss_threshold FROM tenant_config WHERE tenant_id=?`), NOT via `tenantRegistry` (its own `DSLContext` would not see the uncommitted INSERT — **the key correctness subtlety**).
  2. If `isLive(config)` (`StrategyConfigInvariants.isLive`) AND no armed existing cap: require the supplied `account_daily_loss_pct` be non-null, `>0`, `<=1`, and `>= TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT` (`0.05`) — else `throw new InvalidConfigException("live create requires account_daily_loss_pct >= 0.05 (…)" )`. Then `INSERT INTO tenant_config (tenant_id, account_daily_loss_pct, updated_by) VALUES (?,?,?) ON CONFLICT (tenant_id) DO NOTHING` on `tx` (version column is NOT NULL DEFAULT — confirm default; mirror `create`'s strategy INSERT style at `:258-268`). Audit `AccountCapArmedOnCreate` (new kind).
  3. Make the **live-gate validation use the effective cap directly** rather than the `tenantRegistry` read: add a `validate` overload / thread the effective `(pct, threshold)` into `StrategyConfigInvariants.validateLiveRequiredGates(config, effectivePct, effectiveThreshold, label)` so it no longer depends on `tenantRegistry.get()` seeing an uncommitted row (the current `:430-437` read is correct for the UPDATE path but not for an in-txn arm — scope the change to the create path only).
- Thread the value: `StrategyConfigCreateActivitiesImpl.java:35` reads `request.getAccountDailyLossPct()` and passes it to a new `writer.create(tenantId, strategyId, config, accountDailyLossPct, actor)` parameter (writer signature change; activity is the only caller besides tests).
- New audit kind `AccountCapArmedOnCreate` registered in `services/audit/.../AuditEventKinds.ALL_KINDS` (constraint #5). Neutral observability (a create-time provisioning event, not a lifecycle/paging kind) — ALL_KINDS only.

**Replay:** the writer change is **inside the existing activity** (no new workflow command); `account_daily_loss_pct` is an activity-INPUT value (not replay-checked). `StrategyConfigCreateWorkflow` is ephemeral. **No `getVersion` gate.**

**Tests (TDD)** — `StrategyConfigWriterTest` (+ `StrategyConfigCreateActivitiesImplTest`):
- **Incident repro:** live config (`broker_target=alpaca-live`) + NO prior `tenant_config` row + `accountDailyLossPct=0.20` → `create` SUCCEEDS, a `tenant_config` row exists with `account_daily_loss_pct=0.20`, `AccountCapArmedOnCreate` audited, `strategy_config` row inserted.
- Live + NO cap supplied (null) → still `InvalidConfigException` (unchanged reject).
- Live + supplied cap `0.02` (below 0.05 floor) → `InvalidConfigException` below-floor.
- Existing armed `tenant_config` (e.g. 0.10) + create a 2nd live strategy with `accountDailyLossPct=0.30` → row **unchanged at 0.10** (ON CONFLICT DO NOTHING), create succeeds on the existing cap.
- Paper create (`alpaca-paper`) → no `tenant_config` write, unchanged.

**Verify:** `mvn -pl services/orchestrator,services/audit -am spotless:apply` then `mvn -pl services/orchestrator,services/audit test`; `KindRegistryGuardTest` green. Behavioral assertion: *"live create, no prior cap, pct=0.20 → tenant_config row armed + strategy_config created; null cap → still rejected."* (`KillSwitchWorkflowImplTest` flake → re-run.)

---

## Phase 3 — api-gateway: accept the cap from the POST body (`api-gateway`)

**Goal:** carry `account_daily_loss_pct` from the HTTP body onto the workflow request.

**Changes** (anchors):
- `services/api-gateway/.../web/TenantCreateRequest.java` — add an optional `account_daily_loss_pct` accessor (record component, `@JsonProperty`), alongside `config()`.
- `services/api-gateway/.../web/CreateTenantController.java:100-106` — `request.setAccountDailyLossPct(body.accountDailyLossPct())` on the `StrategyConfigCreateRequest`. No new validation here (the writer is the authority); a null passes through and the writer rejects a live create with a null cap, exactly as today.

**Tests (TDD):** `CreateTenantControllerTest` — a POST body carrying `account_daily_loss_pct` sets it on the started workflow request; absent → null.

**Verify:** `mvn -pl services/api-gateway -am spotless:apply test`. Behavioral: *"POST with account_daily_loss_pct=0.2 → StrategyConfigCreateRequest.getAccountDailyLossPct()==0.2."*

---

## Phase 4 — Dashboard: collect the cap in the onboard create step (`dashboard`)

**Goal:** a LIVE-mode create step field for the account cap, threaded to the POST.

**Changes** (anchors):
- `dashboard/components/OnboardForm.tsx` create step (`:500-577`) — add a number input `account_daily_loss_pct` shown **only when `live` (`:243`)**, default `0.20`, with helper copy: *"Account-level daily-loss cap (fraction of SOD equity). Required for a live tenant; min 0.05. This is the real kill-switch breaker — the config's daily_loss_threshold is a dead field."* Light client guard mirroring the existing 400s (`:233,254`): if live and blank/<0.05 → block.
- `dashboard/app/admin/onboard/page.tsx:225` `createTenantAction` — read `account_daily_loss_pct` from `formData`, and pass it into `createTenant(...)`.
- `dashboard/lib/adminOnboarding.ts:32` `createTenant` — include `account_daily_loss_pct` in the POST JSON body.

**Tests:** if the dashboard has server-action/unit coverage, assert the field is forwarded; otherwise this is validated E2E in Phase 5. (No Java/Python here.)

**Verify:** dashboard lint/build (`dashboard` CI job). Behavioral: *"Live mode shows the cap input; submitting includes account_daily_loss_pct in the POST body; paper mode hides it."*

---

## Phase 5 — Enablement / verification (operator; no code)

**Goal:** prove direct live onboarding end-to-end without a promotion step.

1. **Re-assert db-mode** on the live orchestrator: `TENANT_CONFIG_SOURCE=db` + `STRATEGY_CONFIG_SOURCE=db` (P0 assumption). If yaml-mode, STOP — the DB arm won't be read.
2. Deploy Phases 1-4 (note: the orchestrator wasn't auto-rolled on unchanged `:latest` before — ensure a `kubectl rollout restart deployment/orchestrator` if the deploy doesn't roll it; see the deploy-gap follow-up).
3. **Canary create ONE live tenant** via the onboard page (live mode, cap 0.20): confirm the create SUCCEEDS (no 400), a `tenant_config` row exists with `account_daily_loss_pct=0.20` + an `AccountCapArmedOnCreate` audit, a `strategy_config` row exists (`enabled=false`), and the account kill switch enumerates the new tenant (`DbTenantStrategies`). Then add its broker keys + activate through the normal steps.

**Rollback:** the arm-on-create only fires on a live create with a supplied cap; to disable, revert Phase 2 (writer no longer arms) — but a paper-first create still works as before, so there is no operational regression to roll back.

---

## Ship order & gating
1. **Phase 1** (contract DTO — pure, no behavior) → 2. **Phase 2** (orchestrator arm — dark until the field is populated; a null cap still rejects, so behavior is unchanged until 3+4) → 3. **Phase 3** (api-gateway forwards the field) → 4. **Phase 4** (dashboard collects it) → 5. **Phase 5** (operator canary).
Each phase: TDD-first incl. the live-create reproduction, `spotless:apply` on every touched module, its own single-concern PR, operator merge gate (trading-critical). `gh pr edit --body` is broken → set the body at create time. Do not touch `.github/workflows/*.yml`.

## Open forks / assumptions to confirm
- **db-mode is load-bearing and out-of-band** (P0). If the team wants direct onboarding to be safe regardless of mode, a follow-up should either (a) make `tenant.config.source=db` the shipped default, or (b) have the arm-on-create ALSO write the tenants ConfigMap for yaml-mode — heavier (ConfigMap-drift guard, constraint #3) and explicitly deferred here.
- **Cap semantics:** this plan arms `account_daily_loss_pct` only (matches the live tenants' 0.20). If an operator prefers an absolute `account_daily_loss_threshold`, add it as an either/or field in Phase 1/4 (the invariant already accepts either) — flagged, not built.
- **`version` column default** on `tenant_config` — confirm it is NOT NULL DEFAULT (1) so the INSERT can omit it (mirror `strategy_config.schema_version`); if not, set `version=1` explicitly in the arm INSERT.
