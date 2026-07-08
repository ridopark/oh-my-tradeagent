# PLAN — 2026-07-08 account-loss-cap → DB + tenant-editable (tighten-only)

Move the account-level daily-loss cap (`account_daily_loss_threshold` / `account_daily_loss_pct`)
off tenant YAML and into the orchestrator DB, then make it tenant-editable from `/config` — but
**tighten-only** (a tenant may make their own cap stricter, never raise or remove it) so they can
never weaken their own account-wide safety halt. Consistency goal: mirror the already-shipped
per-strategy DB-backed + UI-surfaced config path (`strategy_config` table → `DbStrategyRegistry` →
`StrategyConfigWriter` field-class governance → api-gateway write forward → tdbff read + `/config`
UI). Feature request (no incident); source: user brief 2026-07-08.

**The load-bearing replay fact (verified):** `AccountKillSwitchWorkflowImpl` never reads the cap
inline. It reads it through the `TenantConfigActivities` **activity stub** — declared at
`services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java:181-182`, called at `:465`
(`accountDailyLossThreshold`) and `:469` (`accountDailyLossPct`). Because the YAML→DB source swap
lives entirely inside `TenantConfigActivitiesImpl` / a new `DbTenantRegistry`, it changes **no
workflow command shape**: no new timer, no new activity call, no changed `await`. **No
`Workflow.getVersion` gate is required for the source swap** (Temporal 1.27 replay checks only
command type/ordering — see MEMORY `reference_temporal_replay_activity_input`). Every phase below
states its replay posture explicitly.

---

## P0 — Immediate operational (no code; operator) — REAL-MONEY CUTOVER

prod_real trades a live Alpaca account (847309116); its live cap is `account_daily_loss_pct: 0.40`,
stored ONLY in the live `tenants-config` ConfigMap key `prod-tenant.yaml` (mapped to
`prod_real/tenant.yaml`). That value is **not in the repo** (live-cluster-only, per MEMORY
`project_alpaca_live_migration` / `project_staging_paper_tenant_live`). Sequencing is safety-critical
— flipping the read source before the DB row exists makes the cap go **inert** on a live account.

1. **Seed BEFORE flip.** After Phase 1 is deployed (DB source still default `yaml`), confirm the
   boot seed reconciler (Phase 1) back-filled `tenant_config` from the mounted live ConfigMap —
   i.e. `SELECT tenant_id, account_daily_loss_threshold, account_daily_loss_pct, version FROM
   tenant_config` shows a `prod_real` row with `account_daily_loss_pct = 0.40`. If the reconciler
   fork (see Phase 1) is NOT taken, seed the row manually with the same value and `updated_by =
   'operator:cutover'` before step 2.
2. **Flip the source** (Phase 1's cutover, real-money): set orchestrator env
   `TENANT_CONFIG_SOURCE=db` (property `tenant.config.source=db`) on the homelab orchestrator
   deployment and roll. This is a live `kubectl set env` / manifest edit — NOT applied by `deploy.yml`
   for shared config; treat as an explicit operator step.
3. **Verify the cap still trips at the DB value.** After the roll, confirm the account KS heartbeat
   resolves the DB-sourced 0.40 (audit `account-kill-switch` heartbeat log at the DB value; or a
   controlled staging_paper drill first). Do NOT remove the ConfigMap `account_daily_loss_pct` line
   until this is confirmed — keep it as a fallback for one full session.
4. **ConfigMap cleanup is live-only + LAST.** Removing `account_daily_loss_pct` from the live
   `prod-tenant.yaml` is an operator edit of a live-cluster-only file (not a repo phase). Do it only
   after step 3 holds for a session; re-applying the repo `40-tenants-config.yaml` never touches
   prod_real (dev-only in repo), so there is no repo drift to sync for prod_real.
5. **Write path stays dark until Phase 3 sign-off.** Do not set `tenant.config.write.enabled=true`
   (Phase 3 api-gateway route) or the dashboard `TENANT_CONFIG_WRITE_ENABLED` flag until the
   risk-manager has signed off on the tighten-only monotonicity rule (Phase 3).

---

## Phase 1 — DB-backed account-cap read, source-selected (orchestrator; replay-safe)

**Goal:** `tenant_config` DB table + `DbTenantRegistry` + a `tenant.config.source` property that
selects Db vs Yaml (default `yaml`), so `TenantConfigActivitiesImpl` reads the account cap from the
DB when enabled — with **zero workflow change**.

**Replay posture:** N/A for the workflow. The cap read is entirely behind the
`TenantConfigActivities` activity boundary (`AccountKillSwitchWorkflowImpl.java:181-182,465,469`);
swapping the `TenantRegistry` bean under `TenantConfigActivitiesImpl` (which calls `registry.get(...)`
at `TenantConfigActivitiesImpl.java:45,50`) changes no command type/order. **No `getVersion`.** State
this in the PR body so review does not ask for a marker.

**Changes** (anchors):
- **NEW** `services/orchestrator/src/main/resources/db/migration/V8__tenant_config.sql` — next free
  Flyway version in that dir (current head is `V7__strategy_config_delete_grant.sql`). Create
  `tenant_config`: `tenant_id VARCHAR(64) PRIMARY KEY`, `account_daily_loss_threshold NUMERIC`
  (nullable = absolute cap disabled), `account_daily_loss_pct NUMERIC` (nullable = pct cap disabled),
  `version BIGINT NOT NULL DEFAULT 1`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_by
  VARCHAR(128) NOT NULL`. Mirror `V5__strategy_config.sql:12-29` shape and its least-privilege grant
  style: `GRANT SELECT, INSERT ON tenant_config TO orchestrator_runtime;` (INSERT for the boot seed
  reconciler; UPDATE is deliberately withheld here and added in Phase 3, exactly as V5→V6 did for
  `strategy_config`).
- **NEW** `services/orchestrator/.../platform/DbTenantRegistry.java` — `implements TenantRegistry`,
  `@Component @ConditionalOnProperty(name = "tenant.config.source", havingValue = "db")`. Mirror
  `DbStrategyRegistry.java:29-89`: `SELECT account_daily_loss_threshold, account_daily_loss_pct FROM
  tenant_config WHERE tenant_id = ?`; map the two NUMERIC columns into a `TenantConfig` via its
  setters (reuse `TenantConfig.setAccountDailyLossPct`'s `(0,1]` range guard at
  `TenantConfig.java:59-67`). A missing row returns a default `TenantConfig` (null threshold → cap
  disabled/inert), matching `YamlTenantRegistry.get`'s missing-file semantics
  (`YamlTenantRegistry.java:29-31`) so the reader swap is transparent to `TenantConfigActivitiesImpl`.
- **EDIT** `services/orchestrator/.../platform/YamlTenantRegistry.java:17` — annotate `@Component
  @ConditionalOnProperty(name = "tenant.config.source", havingValue = "yaml", matchIfMissing = true)`
  and add a `@Value("${orchestrator.tenants-dir:tenants}")` constructor arg (mirror
  `YamlStrategyRegistry.java:15-24`), so bean selection is property-driven exactly like the strategy
  path. Default (property unset) = Yaml, so this is a deliberate opt-in cutover.
- **EDIT** `services/orchestrator/.../config/AccountKillSwitchConfig.java:39-43` — DELETE the
  unconditional `@Bean public TenantRegistry tenantRegistry(...)` factory (it hard-wires
  `YamlTenantRegistry` and would shadow the conditional beans). The `tenantConfigActivities` bean at
  `:45-51` keeps taking `TenantRegistry` by injection — it now resolves to whichever conditional bean
  is active. Confirm no orphaned import (`YamlTenantRegistry`, `Path`) remains; remove only imports
  YOUR edit orphans (CLAUDE.md §3).
- **NEW** `services/orchestrator/.../bootstrap/TenantConfigSeedReconciler.java` — **[FORK, see below]**
  boot `ApplicationRunner`, `@Profile("!test")`, `@Order(Ordered.HIGHEST_PRECEDENCE)`, mirroring
  `StrategyConfigSeedReconciler.java:41-58`. Self-constructs a `YamlTenantRegistry` over
  `orchestrator.tenants-dir`, enumerates tenants (reuse the `TenantStrategyScanner`/`ScannerTenantStrategies`
  tenant enumeration already used for the account KS), and `INSERT ... ON CONFLICT (tenant_id) DO
  NOTHING` the YAML cap values — insert-if-absent, non-destructive, idempotent. This is what warms
  the DB from the live ConfigMap before P0 step 2's flip.

**FORK to resolve (flag for lead / risk-manager):** seed mechanism.
- **Option A (recommended, mirrors precedent):** ship `TenantConfigSeedReconciler` in Phase 1. It
  runs at every boot, insert-if-absent, so the live pod auto-seeds prod_real's 0.40 from the live
  ConfigMap before the source flip — zero manual SQL on real money. Matches the strategy "P0a seeds,
  P0b flips" sequence.
- **Option B:** no reconciler; P0 step 1 seeds the prod_real row by hand-run SQL. Fewer moving parts
  in the code PR, but puts a manual INSERT on the real-money critical path.
- Default if unresolved: **Option A** (cost of guessing wrong is low — it is insert-if-absent and
  inert while source=yaml).

**Tests (TDD):**
- `DbTenantRegistryIT` (RUN_DB_ITS / Testcontainers, mirror `DbStrategyConfigReaderIT`): seed a
  `tenant_config` row (`account_daily_loss_pct = 0.40`) → `registry.get("t").getAccountDailyLossPct()`
  equals `0.40`; missing row → default `TenantConfig` (null threshold), no throw.
- `TenantConfigActivitiesImplTest` — with a stub `TenantRegistry` returning the DB-sourced config,
  `accountDailyLossThreshold` / `accountDailyLossPct` return the seeded values (proves the activity is
  source-agnostic).
- `AccountKillSwitchWorkflowImplTest` (existing) — add/confirm a case that the heartbeat trips at the
  activity-returned threshold; unchanged behavior proves the source swap is invisible to the workflow.
- `TenantConfigSeedReconcilerIT` (if Option A) — boot against an empty table with a YAML fixture
  carrying `account_daily_loss_pct: 0.40` → one row seeded; second run → still one row, untouched
  (idempotent).
- **PG16 grant gotcha (MEMORY `reference_pg_where_needs_select`):** the writer role added in Phase 3
  does `UPDATE ... WHERE tenant_id = ?` and (Phase 3) `ON CONFLICT` — those need column-level
  `SELECT` on the read columns. `orchestrator_runtime` already gets table `SELECT` here in V8, so
  Phase 1 is safe; Phase 3 must NOT switch to a no-SELECT writer role without re-granting. Note it in
  Phase 3.

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am
  spotless:check test` green (re-run `KillSwitchWorkflowImplTest` if it flakes — MEMORY
  `feedback_spotless_precommit`; do not "fix" it).
- Behavioral: with `tenant.config.source=db` and a seeded `tenant_config` row
  `account_daily_loss_pct=0.40`, the account KS heartbeat resolves an effective threshold of
  `0.40 × sodEquity` (same value the YAML path produced); with the property unset, the YAML path is
  byte-identical to today.
- **Spotless:** orchestrator module only. **No contract schema change** (cap is not in
  `strategy-config.json`; it is `TenantConfig`, a plain POJO). **No new audit kind** in Phase 1
  (read-only + seed; the seed can reuse a coarse log line, no audit event). **ConfigMap drift:** none
  — Phase 1 touches no `tenants/dev/*` file, so `check-tenants-configmap-drift.py` is unaffected.

---

## Phase 2 — Surface the account cap in the read path + `/live` card (tdbff + dashboard)

**Goal:** expose the (read-only) account cap so `/config` and `/live` can display the real
account-wide cap. Today `/live` shows only per-strategy `daily_loss_threshold`
(`dashboard/app/live/page.tsx:54-56`, via `strategyDailyLossLimits(...)`) — it cannot show the
account cap. This phase adds the read; NO write (that is Phase 3), so it is independently mergeable
and carries no tighten-only risk.

**Replay posture:** N/A — no orchestrator workflow code touched.

**Changes** (anchors):
- **NEW** `services/tenant-dashboard-bff/.../platform/TenantConfigReader.java` — read-only accessor
  over the `orchestratorDsl` for `tenant_config`, mirroring `DbStrategyConfigReader.java:22-43`
  (fail-soft: missing row → null cap, never throws; the `tenant_config` table is not in the BFF's
  generated jOOQ, so use `DSL.field`/`DSL.table` string refs exactly as that reader does). Returns
  `{ account_daily_loss_threshold, account_daily_loss_pct, version }`.
- **DECISION (low-cost, default chosen):** surface it as a NEW endpoint `GET /api/tenant-config`
  (new `TenantConfigController` in tdbff, mirror `StrategyConfigController.java:18-42`) rather than
  folding into the per-strategy `/api/strategy-config` response — the account cap is tenant-scoped,
  not per-strategy, so a separate resource keeps the field-class/version model clean for the Phase 3
  writer. Include a `field_classes` block marking the two cap fields `EXPOSURE` (tighten-only) so the
  UI reuses the existing badge model.
- **EDIT** `dashboard/lib/bff.ts` (or wherever `getStrategyConfig` lives — implementer confirms the
  BFF client module) — add a `getTenantConfig()` fetch mirroring `getStrategyConfig`.
- **EDIT** `dashboard/app/live/page.tsx:54-58` — after resolving `dailyLossLimits`, also fetch the
  account cap and render it in the header as the account-wide cap (read-only), alongside the existing
  per-strategy limits. Keep the existing per-strategy display; this ADDS the account line.
- **EDIT** `dashboard/app/config/page.tsx` — render an account-cap section (read-only in Phase 2)
  reusing the existing `CLASS_BADGE.EXPOSURE` "tighten-only" badge (`page.tsx:80-84`) and
  `FieldValue` read-only rendering. No form/input yet (write is Phase 3, still `WRITE_ENABLED`-dark).

**Tests (TDD):**
- `TenantConfigReaderIT` (RUN_DB_ITS) — seeded row returns the cap; absent row returns nulls, no throw.
- `TenantConfigControllerWebMvcTest` + a disabled/deny variant, mirroring
  `StrategyConfigControllerWebMvcTest` / its disabled twin — GET returns `field_classes` with the two
  cap fields under `EXPOSURE`, and the tenant's cap values; cross-tenant isolation via `TenantContext`.
- Dashboard: extend the existing `/live` and `/config` component/render tests to assert the account
  cap line renders read-only when present and is absent (no crash) when the cap is null.

**Verify / success criteria:**
- `mvn -pl services/tenant-dashboard-bff -am spotless:apply && ... spotless:check test` green;
  dashboard `npm run build` + tests green.
- Behavioral: with a seeded prod_real cap, `/live` shows an account-wide cap line and `/config` shows
  the account cap with a read-only `tighten-only` badge; with the cap null, both render without the
  line and without error.
- **Spotless:** tdbff module. **No new audit kind** (read-only). **No ConfigMap drift.** **No
  contract schema change** (BFF returns a plain map, like `StrategyConfigController`).

---

## Phase 3 — Tenant tighten-only write (orchestrator writer + workflow + api-gateway + UI)

**Goal:** let a tenant LOWER their own account cap (stricter) from `/config`, server-enforced
tighten-only — never raise, never remove. Mirrors the per-strategy write path end-to-end. **Ships
dark** (two independent flags, like the strategy write path) and does NOT go live until risk-manager
sign-off (below).

> **RISK-MANAGER SIGN-OFF REQUIRED (this is the "tenant edits their own safety control" concern).**
> Today the account cap is operator-owned YAML. This phase hands the tenant a write handle on a
> real-money halt. Note the deliberate risk re-classification: for `strategy_config`,
> `daily_loss_threshold` is **DANGEROUS / hard-block** (must equal stored — `StrategyConfigWriter.java:459-464`)
> precisely because a runtime change disarms the live loss circuit-breaker. Here we intentionally make
> the account cap **EXPOSURE / tighten-only** instead — strictly safer edits (lower cap = earlier
> halt) are allowed, everything else rejected. The monotonicity rule and its server-side location
> (below) are the sign-off artifact. This mirrors the reset-threshold immutability rationale in the
> flatten/killswitch plan: a safety control may only be made stricter at runtime, and never removed.

**Monotonicity rule (server-enforced, authoritative — NOT UI-only):**
- `account_daily_loss_threshold` (absolute $): stored `null` → any value REJECTED (adding a cap where
  none existed is not "tightening" an existing one — and would need operator provenance); stored
  non-null → `next` must be `≤ stored` and non-null (may not be removed).
- `account_daily_loss_pct`: same rule — stored non-null → `next ≤ stored`, non-null; stored `null` →
  set REJECTED. Range `(0,1]` still enforced by `TenantConfig.setAccountDailyLossPct` on parse.
- Reuse the exact `requireNotIncreased` semantics from `StrategyConfigWriter.java:522-545` (works for
  `BigDecimal` via `compareTo`; `stored==null` guarded; `next==null` while `stored!=null` rejected as
  "dropping a cap is not a tightening"). This is the load-bearing precedent — replicate, don't invent.

**Replay posture:** the new `TenantConfigUpdateWorkflow` is a **net-new workflow type** — no existing
history to break, no `getVersion` needed (identical rationale to
`StrategyConfigUpdateWorkflowImpl.java:10-22`: single-step, no timers, no non-determinism). State it
in the PR.

**Changes** (anchors):
- **NEW** `services/orchestrator/src/main/resources/db/migration/V9__tenant_config_update_grant.sql`
  — `GRANT UPDATE ON tenant_config TO orchestrator_runtime;` mirroring `V6__strategy_config_update_grant.sql`.
  **PG16 gotcha (MEMORY `reference_pg_where_needs_select`):** the CAS `UPDATE ... WHERE tenant_id = ?
  AND version = ?` reads `tenant_id`/`version`; `orchestrator_runtime` already holds table `SELECT`
  from V8, so no separate column-SELECT grant is needed — but if the writer is ever moved to a
  no-SELECT least-priv role, add `GRANT SELECT(tenant_id, version) ...`. Note it in the migration
  comment.
- **NEW** `services/orchestrator/.../platform/TenantConfigWriter.java` — `@Component`, a
  compare-and-set `update(tenantId, newThreshold, newPct, expectedVersion, actor)` in
  `dsl.transactionResult`, mirroring `StrategyConfigWriter.update` (`StrategyConfigWriter.java:96-201`):
  load stored row → tighten-only field-class check (above) → `UPDATE ... SET
  account_daily_loss_threshold=?, account_daily_loss_pct=?, version = version + 1, updated_at=now(),
  updated_by=? WHERE tenant_id=? AND version=?` → 0 rows ⇒ `OptimisticLockException` → audit last-in-txn
  via the hash-chain `AuditActivities.log` (NEVER INSERT `audit_log` directly). Reuse the writer's
  exception types (`OptimisticLockException`, `DangerousFieldChangeRejected`).
- **NEW audit kind** `AccountLossCapChanged` — register in
  `services/audit/.../AuditEventKinds.ALL_KINDS` (as a **neutral** kind — not an entry/exit/terminal
  group; it rides alongside `TenantConfigChanged` at `AuditEventKinds.java:445-446`) or the pre-push
  `KindRegistryGuardTest` blocks the push. Emit it from `TenantConfigWriter` with subject
  `{tenant_id, actor, source:"tenant-cap-write", prior:{threshold,pct}, current:{threshold,pct},
  old_version, new_version}`, redact nothing (no credential fields). **DECISION (flag):** new kind vs
  reuse `TenantConfigChanged` — chose a new kind because `TenantConfigChanged` is strategy-keyed
  (correlation `tenant/strategy`) and the account cap is tenant-scoped; a distinct kind keeps the
  ledger re-deriver's grouping unambiguous. Low-cost either way; default = new kind.
- **NEW** `TenantConfigUpdateWorkflow` + `Impl` + `TenantConfigUpdateActivities` + `Impl` — single-step,
  mirror `StrategyConfigUpdateWorkflowImpl.java` (bounded retry `maximumAttempts=3`, orchestrator-core
  queue, coarse outcomes returned not thrown). Contract DTOs `TenantConfigUpdateRequest` /
  `TenantConfigUpdateResult` (outcomes: `UPDATED`, `REJECTED_STALE_VERSION`, `REJECTED_TIGHTEN_ONLY`,
  `REJECTED_INVALID`, `NOT_FOUND`). **Contract note:** these are new hand-written request/result
  types alongside the strategy ones — NOT a change to `strategy-config.json`, so no Python-model
  regen. If they are added to a JSON schema instead, the build + Python round-trip drift check apply.
- **NEW** `services/api-gateway/.../web/TenantConfigController.java` — `POST /tenant-config`,
  `@ConditionalOnProperty(name = "tenant.config.write.enabled", havingValue = "true")` (dark by
  construction — route 404s until an operator opts in; NO repo manifest sets it). Mirror
  `api-gateway/.../StrategyConfigController.java:52-169`: strict `X-Tenant-Id`, cross-tenant guard
  (`body.tenant_id == X-Tenant-Id` else 403), start `TenantConfigUpdateWorkflow` REJECT_DUPLICATE on a
  correlation-keyed workflow id, 30s run timeout, `WorkflowException → 503` (never report unknown as
  success). Map outcomes: `REJECTED_TIGHTEN_ONLY → 403`, `REJECTED_STALE_VERSION → 409`,
  `REJECTED_INVALID → 400`, `NOT_FOUND → 404`.
- **EDIT** `dashboard/lib/apiGateway.ts` — add `postTenantConfig(...)` mirroring `postStrategyConfig`
  (used at `config/page.tsx:8,241`).
- **EDIT** `dashboard/app/config/page.tsx` — turn the Phase 2 read-only account-cap section into a
  form gated by a NEW `TENANT_CONFIG_WRITE_ENABLED === "true"` flag (independent of the existing
  `STRATEGY_CONFIG_WRITE_ENABLED` at `page.tsx:18`). Reuse `SubmitButton`, the `EXPOSURE`/tighten-only
  badge, the `?saved=1` / `?error=<status>` coarse banner (`page.tsx:160-179`), and a server action
  mirroring `saveConfig` (`page.tsx:185-251`) that recomputes `expected_version` from a fresh read
  and forwards via `postTenantConfig`. Client-side tighten hint is UX-only; the server is
  authoritative.

**Tests (TDD):**
- `TenantConfigWriterIT` (RUN_DB_ITS) — the sign-off behavioral assertions:
  - stored `pct=0.40`, PUT `pct=0.60` → `REJECTED_TIGHTEN_ONLY` (raise rejected), row unchanged, no
    audit event.
  - stored `pct=0.40`, PUT `pct=0.30` → `UPDATED`, `version` bumped, one `AccountLossCapChanged`
    audit event with prior 0.40 / current 0.30.
  - stored `pct=0.40`, PUT `pct=null` (remove) → `REJECTED_TIGHTEN_ONLY` (dropping a cap is not a
    tightening).
  - stored `threshold=null`, PUT `threshold=2500` → `REJECTED_TIGHTEN_ONLY` (adding an absent cap).
  - stale `expected_version` → `REJECTED_STALE_VERSION` (409), no write.
  - `pct=40` (typo) → `REJECTED_INVALID` (range guard `(0,1]`).
- `TenantConfigController` api-gateway WebMvc test + disabled twin — enabled route maps the outcomes
  to 200/403/409/400/404; disabled (flag unset) → 404; cross-tenant body → 403.
- `KindRegistryGuardTest` (audit) — passes with `AccountLossCapChanged` registered.
- Dashboard: `/config` account-cap form renders only when `TENANT_CONFIG_WRITE_ENABLED=true`;
  server action posts the recomputed version; 403 banner shows "not allowed (dangerous/tighten-only)".

**Verify / success criteria:**
- `mvn -pl services/orchestrator,services/api-gateway,services/audit -am spotless:apply` then
  `spotless:check test` per module green; dashboard build + tests green. (Cross-module note, MEMORY
  `feedback_cross_module_exec_ctor_and_spotless`: run spotless on EVERY touched module — orchestrator,
  api-gateway, audit, contract if a DTO lands there.)
- **Behavioral (the incident-equivalent proof):** a tenant PUT raising `account_daily_loss_pct` from
  0.40 → 0.60 returns **403 `REJECTED_TIGHTEN_ONLY`** and leaves the stored row and the live account
  KS threshold unchanged; lowering to 0.30 returns **200**, bumps `version`, writes one
  `AccountLossCapChanged` audit event, and the next account-KS heartbeat trips at `0.30 × sodEquity`.
- **New audit kind** registered (else pre-push guard blocks). **No `strategy-config.json` change.**
  **No ConfigMap drift.** Route + UI **dark** until the two flags flip (operator, post sign-off).

---

## Ship order & gating

Risk order (isolated/low-blast-radius first; the real-money source cutover and the tenant write
handle last). Each phase: TDD, `spotless:apply` on every touched module, its own single-concern PR,
operator merge gate (trading-critical).

1. **Phase 1** (orchestrator, replay-safe read swap; ships with `tenant.config.source` default
   `yaml` → behavior-neutral until the P0 flip). Merge + deploy.
2. **P0 operator cutover** (seed-verify → flip `TENANT_CONFIG_SOURCE=db` → verify cap trips at DB
   value → later live-only ConfigMap cleanup). Real money — sequence per the P0 section; do not flip
   before the `prod_real` DB row is confirmed.
3. **Phase 2** (tdbff + dashboard read surface + `/live` card). No workflow, no write — independently
   mergeable; can land in parallel with the P0 cutover since it is read-only.
4. **Phase 3** (tighten-only write: orchestrator writer/workflow + api-gateway route + audit kind +
   UI form). Ships **dark** (two flags). **Gate: risk-manager sign-off on the monotonicity rule +
   its server-side enforcement location before flipping `tenant.config.write.enabled` and
   `TENANT_CONFIG_WRITE_ENABLED`.**

**Unresolved forks for the lead / risk-manager:**
- **F1 (Phase 1):** seed reconciler (Option A, recommended) vs manual SQL seed (Option B). Default A.
- **F2 (Phase 3):** new `AccountLossCapChanged` audit kind (default) vs reuse strategy-keyed
  `TenantConfigChanged`. Default new kind.
- **F3 (Phase 3, sign-off):** confirm the account cap's runtime posture is EXPOSURE/tighten-only
  (not DANGEROUS/hard-block like the per-strategy `daily_loss_threshold`). This is the deliberate,
  sign-off-bearing divergence from the strategy precedent.
