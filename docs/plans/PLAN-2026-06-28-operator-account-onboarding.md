# PLAN — 2026-06-28 Operator account onboarding (data-only Nth live Alpaca account)

**Epic.** Build an **operator-only admin UI** on `oh-my-tradeagent` that makes adding the Nth LIVE
Alpaca account a **DATA-only** action: *create an Alpaca account → paste API key id + secret →
attach a strategy → ONE-CLICK LIVE*, with **NO orchestrator restart** and **NO per-account
code / contract / schema change**. This uses the **SHARED-ACCOUNT path**: a single `broker_target`
`alpaca-live` serves all live tenants, distinguished by per-tenant `broker_account_id` + per-tenant
DB-envelope-encrypted credentials, gated behind `multitenant.broker-accounts.enabled` (dark by
default).

**Supersedes** `docs/plans/PLAN-2026-06-28-second-live-account.md` (Option B: dedicated
`broker_target` + dedicated exec pod + dedicated exec DB *per account*). That approach is rejected
as O(N)-code-per-account. The shared-account path makes the Xth account data-only.

**Relationship to other epics:**
- **`docs/plans/PLAN-multi-tenant-broker-credentials.md`** — this plan is that epic's **live-activation
  milestone**. Fork B (shared exec worker + per-tenant resolver), the envelope-encrypted DB credential
  store (P6), `expected_account_id` identity assertion (P2), and the enforcing LivePromotion gate (P3)
  are **already shipped + dark-gated**. This plan turns those on for live and adds the operator UI/CRUD.
- **`docs/plans/PLAN-2026-06-28-self-registration.md`** — that external-customer epic is a strict
  **superset** of this one. This operator UI is a **prerequisite** of self-registration (same tenant
  CRUD + activation + DB-creds-live machinery, minus the public-isolation hardening). Self-registration
  is **OUT OF SCOPE** here.

**User decisions (fixed):**
- **Operator-only** admin UI (NOT external customers). No public-isolation hardening in this epic.
- **ONE-CLICK LIVE**: pasting keys + attaching a strategy + clicking activates real-money trading with
  **no separate human dual-control approval ceremony**. One-click removes ONLY the human approval
  ceremony — it MUST preserve every *automatic* fail-closed correctness guard (identity binding,
  required-config gate, secret-egress controls, the programmatic LivePromotion record, the canary).

> **Anchors below re-confirmed by reading the code at authoring time (2026-06-28).** Two input-map
> claims were refined against the code: **Phase C** activities pass a `*Request` DTO (not bare params)
> and the impls already resolve per-tenant — so C is verify+document. **Phase F** the enforcing
> LivePromotion gate is ALREADY wired (`CopytradeSignalWorkflowImpl.java:464-488`, NOT advisory) — so F's
> net-new is only the activation endpoint that *emits* the promotion record + the deactivation path.

---

## Out of scope (explicit)

1. **External-customer self-registration** — separate epic (`PLAN-2026-06-28-self-registration.md`).
   No Google-signup demo flow, no admin-subdomain split, no Cloudflare allowlist flip, no public
   tenant-isolation hardening here.
2. **The full leader-elected multi-account fill router (PLAN-multi-tenant-broker-credentials P5).**
   Phase G ships **one `trade_updates` WS socket per live tenant** in a single `replicas:1` pod (DECIDED);
   the leader-elected router that would allow >1 replica is the deferred P5 big-build, OUT OF SCOPE here.
   Until it exists, `exec-alpaca-live` MUST stay `replicas:1` (>1 pod → double fill signals).
3. **Paper + live mixed in one pod.** Single-mode-per-pod stands: `exec-alpaca-paper` and
   `exec-alpaca-live` remain distinct pods. The shared-account path shares *across live tenants on the
   live pod*, not across modes.

---

## P0 — Operator follow-ups (no code; carry these out-of-band)

These are operator/cluster actions, never code phases. They gate, or follow, the code phases.

- **DARK-ship discipline (R-X.1).** The repo default MUST never arm live: `multitenant.broker-accounts.enabled=false`,
  the DB-creds-live opt-in env unset, the UI write/activate flags off. Arming is a per-cluster manual
  override (homelab only), the same model as the Alpaca live migration (re-applying the repo ConfigMap
  reverts to safe).
- **TLS + NetworkPolicy precondition (R-1.2).** Before any DB-creds live opt-in: confirm the
  `api-gateway → exec /internal/broker-credentials` hop is TLS and a `NetworkPolicy` restricts
  `/internal/broker-credentials` to the api-gateway pod only. The secret travels only on that direct
  HTTP body; it must never be reachable from elsewhere in the cluster.
- **KEK / crypto material present on the live cluster.** `BrokerCredentialCryptoConfig` loads + validates
  the KEK at boot; confirm the live exec pod has it before enabling DB creds.
- **`exec-alpaca-live` is a MANUAL roll** — it is NOT in the `build-images` deploy matrix and NOT applied
  by `deploy.yml`. Every phase that changes exec image behavior for live requires an explicit operator
  roll of `exec-alpaca-live` after the merge.
- **Shared manifests are manual `kubectl apply`** — `infra/k8s/40-tenants-config.yaml` (tenants
  ConfigMap), secrets, and Postgres/migration manifests are NOT applied by `deploy.yml`. Any tenant-row
  or schema change for live is an operator `kubectl apply` + a Flyway migration run.
- **Live-tenant YAMLs are out-of-band today.** `staging_paper` / `prod_real` strategy YAMLs are NOT in the
  repo (only `tenants/dev/*`). As tenants migrate from the ConfigMap mount toward DB rows, the dev mount
  STAYS for dev; live tenants become DB rows written via the UI. Do not commit live-tenant YAML.
- **Broker-side 403 block (R-6.7).** Adding a tenant must NOT be "live + full-size + 403-unblocked in one
  irreversible action." The 403-unblock at Alpaca remains an operator step, folded into the canary
  (Phase F/G): a newly-armed account runs capped first, and only after clean canary fills does the
  operator lift any remaining broker-side block.
- **Flip the dark gates last,** strictly after the code phases that make them safe are merged + verified
  (see Ship Order). `multitenant.broker-accounts.enabled=true` only after Phase B (+C confirmed).
- **Cleanup / re-confirm** after the coupled lift (Phase E): re-probe each live tenant's
  `expected_account_id` matches its authenticated account before lifting the broker-side block.

---

## Cross-cutting CI / replay / deploy constraints (apply per phase)

- **Replay safety.** Recon + any cron-tick reconcile loop are **fresh per tick** → **NO `getVersion`**.
  Flags, DB-cred reads/writes, CRUD endpoints, and UI are **outside workflow code** → **NO `getVersion`**.
  The ONLY workflow-history surface in this epic is the *already-shipped* LivePromotion gate
  (`VERSION_LIVE_PROMOTION_GATE` at `CopytradeSignalWorkflowImpl.java:464`) — Phase F adds NO new command
  to that workflow (it writes the promotion row from outside), so **no new marker**. Phase B changes an
  Activity *contract signature* but recon is cron-fresh, so **no marker** (only a cross-service deploy
  order — exec before/with orchestrator + contract POJO regen).
- **Spotless per module.** Run `mvn -pl <module> -am spotless:apply` then `spotless:check` on EVERY
  touched Java module: `contract`, `services/orchestrator`, `services/exec`, `services/api-gateway`,
  `services/tenant-dashboard-bff`. The impl env skips spotless; CI fails on it otherwise.
- **Contract schema regen.** A change to `contract/schemas/*.json` regenerates Java POJO + Python pydantic;
  the Python round-trip drift check enforces consistency. Optional fields go OUT of `required` (null =
  disabled). Phase B may touch a contract POJO; Phase I may add request DTOs.
- **Tenant ConfigMap drift guard.** If any phase edits `tenants/dev/*` it MUST re-sync
  `infra/k8s/40-tenants-config.yaml` and run `scripts/check-tenants-configmap-drift.py` or the
  `k8s (kubeconform)` check fails. The migration to DB-sourced tenant enumeration (Phase A) keeps the dev
  mount intact — it is additive, not a removal of the ConfigMap path.
- **New audit kinds** (Phase F/I add/activate/rotate/deactivate/delete) MUST be registered in
  `services/audit/.../AuditEventKinds.ALL_KINDS` or the pre-push `KindRegistryGuardTest` blocks the push.
- **Flaky `KillSwitchWorkflowImplTest`** — re-run, do not fix.
- **PR mechanics.** `gh pr edit --body` is broken (deprecated projectCards) → set body at create time or
  `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`. Never touch `.github/workflows/*.yml`. Commit trailer
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Use `Closes #<n>` only when an
  issue exists.
- **Homelab deploy mechanics.** `build-images` is full-matrix; `deploy.yml` is diff-scoped to per-service
  manifests; `exec-alpaca-live` is a MANUAL roll (out of matrix); tenants/secrets/postgres manifests are
  manual `kubectl apply`. Inter-phase QA targets `ssh ridopark@192.168.10.123` (k3s).

---

## The four HARD GATES (rubric) — where each is enforced

| Gate | Rule (fail-closed) | Enforced in |
|---|---|---|
| **R-2.1 Account-identity binding** | On click, pasted keys probed against `/v2/account` BEFORE persistence; the AUTHENTICATED account number becomes the immutable per-tenant `expected_account_id` (operator never free-types it); probed number surfaced read-only for visual confirm before arming. | Phase I (form + read-back), reuses `BrokerCredentialWriter.validateOnEntry` (`BrokerCredentialWriter.java:143`). |
| **R-3.1 The coupled lift** | Removing the `-live` refusal (`DbBrokerCredentialSource.java:94-102`, `BrokerCredentialWriter.java:130-137`) MUST be the SAME change that makes a live resolve **require a non-blank `expected_account_id`** (today blank = paper no-op at `DbBrokerCredentialSource.java:132`). This lift is the **LAST** code merged (R-X.2). | Phase E. |
| **R-3.4 Live-required config gate** | One-click refuses (server-side, fail-closed) unless `daily_loss_threshold>0`, notional cap set, `capital_source=account_cash` (NOT static $100k), kill switch armable — VERIFIED at activation, not assumed from YAML. | Phase F (activation endpoint) + boot validator. |
| **R-1.2 No secret egress** | `api_key_id`/`secret` never reach a log, `audit_log`, `order_intent_journal`, or Temporal history; secret travels ONLY on the direct api-gateway→exec HTTP body. TLS + NetworkPolicy is a PRECONDITION of any DB-creds live opt-in. | P0 (TLS/NetworkPolicy) + Phase E/I (write path already secret-safe). |

Plus: **R-3.3** (Phase F — emit a fresh `LivePromotionApproved` programmatically so the existing
order-time gate at `CopytradeSignalWorkflowImpl.java:464` keeps firing; deactivation → next live BTO sees
ABSENT/STALE → fails closed), **R-3.5** (Phase F/G canary — capped first exposure as the non-interactive
substitute for the removed second pair of eyes), **R-5.1/R-5.2** (Phase F/I — immutable hash-chained audit
of add/activate/rotate/deactivate/delete with the REAL authenticated operator identity, zero key
material), **R-6.5** (Phase E/I — cross-tenant `expected_account_id` UNIQUENESS for live rows),
**R-6.7** (P0/Phase G — fold the 403-block interaction into the canary, never one irreversible action),
**R-X.1** (all phases — ships dark; per-cluster opt-in only).

---

## Phase 0 — Tenant-enumeration source-of-truth (DECIDED, ~0 code)

**DECIDED:** Enumeration source-of-truth = **`SELECT DISTINCT tenant_id, strategy_id FROM strategy_config`**.
**NO new `tenants` table.** This is the chosen design for Phases A, G, and I.

**Confirmed gap (read at authoring):** There is **NO `tenants` table** — migrations run V1–V6 and the
newest, `V5__strategy_config.sql`, creates `strategy_config` with `PRIMARY KEY (tenant_id, strategy_id)`
(`services/orchestrator/src/main/resources/db/migration/V5__strategy_config.sql:12-20`). Tenants are
enumerated ONLY by `TenantStrategyScanner.scan(Path)` over the ConfigMap mount
(`TenantStrategyScanner.java:24`, returns `TenantStrategy(tenantId, strategyId)`). `StrategyRegistry`
exposes only `get(tenantId, strategyId)` — **point-lookup, no list** (`StrategyRegistry.java:6`).
`DbStrategyRegistry` likewise only `get(...)` (`DbStrategyRegistry.java:50`). `strategy_config` IS already
the runtime config source in prod (`STRATEGY_CONFIG_SOURCE=db`).

**Implications baked into the rest of the plan:**
- **(a)** Phase A's registry enumeration method is a `SELECT DISTINCT tenant_id, strategy_id FROM
  strategy_config` query — no FK, no second source to keep consistent.
- **(b)** Phase I's "create tenant" is **really "INSERT the strategy_config row(s) for a new
  `tenant_id`"** — there is NO separate tenant entity to create. A tenant "exists" exactly when it has at
  least one strategy_config row.
- **(c)** Phase I's "list all accounts" admin view derives from the SAME DISTINCT query.
  `DbStrategyConfigReader` is point-lookup today → add a list method backed by the DISTINCT query.
- **(d)** Tenant-level metadata (display name / created_by / status) has **NO storage column** under this
  choice. Operator attribution comes from the **`audit_log` hash-chain (R-5.2)**, not a column. "Status"
  (active / blocked / live) is **DERIVED** from existing signals — `strategy_config.enabled`, the presence
  of a fresh `LivePromotionApproved` record, and the broker-403 state — never a new column.

**Follow-up rule (do NOT silently widen scope):** If a later phase genuinely needs a persisted
status/display field, **flag it as a follow-up** for the lead — do NOT silently add a `tenants` table.

**Verify / success criteria:** This decision is recorded here and in Phase A's PR description. Phases A/G/I
implement against the DISTINCT source; no `tenants`-table migration appears in any PR.
---

## Phase A — Runtime tenant enumeration + reconcile loop (NET-NEW, biggest) — `orchestrator`

**Goal:** Make tenant onboarding **restart-free**: a new strategy_config row (written by the UI) is picked
up by a periodic reconcile loop that diffs desired vs running per-tenant kill-switches + reconciliation
schedules and invokes the existing **idempotent** bootstrappers — with no orchestrator restart.

**Confirmed current state:** All per-tenant bootstrappers are ALREADY idempotent — `KillSwitchBootstrapper`
(REJECT_DUPLICATE), `ReconciliationScheduleBootstrapper` (swallows `AlreadyRunning`),
`StrategyConfigSeedReconciler` (`ON CONFLICT DO NOTHING`). What is missing is (1) an enumeration method
and (2) a loop that calls them on a tick instead of only at boot.

**Changes (anchors):**
- `StrategyRegistry.java:6` — add `List<TenantStrategy> list()` (or `Set<TenantStrategy>`) alongside `get`.
- `DbStrategyRegistry.java:50` — implement `list()` as `SELECT DISTINCT tenant_id, strategy_id FROM
  strategy_config` (the Phase-0 DECIDED source — no `tenants` table). Reuse the existing `DSLContext`.
- New `@Scheduled` reconcile bean in `services/orchestrator/.../bootstrap/` (e.g.
  `TenantReconcileLoop`) — on a fixed delay, call `registry.list()`, diff against running kill-switches /
  recon schedules, and invoke the SAME idempotent bootstrapper logic the boot path uses. **A `@Scheduled`
  bean is NOT workflow code → NO `getVersion`.** (Alternative: a Temporal cron workflow whose tick is
  also fresh → no marker either. Prefer `@Scheduled` for KISS unless the lead wants the cron's
  observability.)
- Runtime INSERT of strategy_config rows is the UI's job (Phase I) — Phase A only *reads*.

**Replay:** none (scheduler/bootstrappers are outside workflow history). **Spotless:** `orchestrator`.
**ConfigMap drift:** none (additive; the dev ConfigMap mount stays as the dev enumeration source — the
loop is a superset that also covers DB rows).

**Tests (TDD):**
- `DbStrategyRegistryListTest` — seed two tenants × strategies; `list()` returns exactly the distinct set.
- `TenantReconcileLoopTest` — given a running set {A} and desired {A,B}, the loop bootstraps B's kill-switch
  + recon schedule and is a **no-op on a second tick** (idempotent); given desired == running, zero calls
  mutate state.
- A test proving a NEWLY-INSERTED strategy_config row (simulating the UI write) is reconciled on the next
  tick **without a restart**.

**Verify / success criteria:** `mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test`.
Behavioral: insert a strategy_config row at runtime in a test harness → within one tick the tenant's
kill-switch + recon schedule exist; second tick changes nothing.

---

## Phase B — Per-tenant reconciliation read (NET-NEW, ~3 lines) — `contract` + `exec` + `orchestrator`

**Goal:** Close the only recon method that is tenant-blind so reconciliation reads the correct account
under the shared-account path.

**Confirmed gap:** `ReconciliationExecActivity.brokerListOpenOrders()` (`ReconciliationExecActivity.java:27`)
is the ONLY recon contract method lacking `(tenantId, strategyId)`. The impl hardcodes the account-level
sentinel: `broker(BrokerClientRegistry.ACCOUNT_LEVEL).listOpenOrders()`
(`ReconciliationExecActivityImpl.java:56-58`). The workflow already threads tenant elsewhere
(`ReconciliationWorkflowImpl.java:187` passes `in.getTenantId()` to `journalDumpOpen`) but the open-orders
call at `:188` drops it.

**Changes (anchors):**
- `ReconciliationExecActivity.java:27` — `brokerListOpenOrders(String tenantId, String strategyId)`.
- `ReconciliationExecActivityImpl.java:56-58` — resolve `broker(tenantId)` (matching
  `brokerListOpenPositions` at `:65`) instead of the hardcoded `ACCOUNT_LEVEL`.
- `ReconciliationWorkflowImpl.java:188` — pass `in.getTenantId(), in.getStrategyId()`.
- Update `ReconciliationExecActivityImplIT` accordingly.

**Replay:** **NO `getVersion`** — recon is cron-fresh per tick; the Activity-contract signature change is
not a running-workflow command-shape change. **Cross-service deploy order:** ship/roll **exec before or
with orchestrator**; regenerate the contract POJO. **Spotless:** `contract`, `exec`, `orchestrator`.

**Tests (TDD):**
- `ReconciliationExecActivityImplIT` — `brokerListOpenOrders(tenantId, strategyId)` resolves the
  tenant's account (assert the registry is asked for `tenantId`, not `ACCOUNT_LEVEL`).
- `ReconciliationWorkflowImpl` test — the open-orders call receives the workflow input's tenant/strategy.

**Verify / success criteria:** `mvn -pl contract,services/exec,services/orchestrator -am spotless:apply`
then the exec + orchestrator module tests. Behavioral: a recon tick for tenant B lists B's open orders, not
the account-level account's.

---

## Phase C — Verify AccountSnapshot + PreTradeCheck thread per-tenant (VERIFY/DOC, ~0 code)

**Goal:** Confirm and document that the two remaining account-level reads already resolve per-tenant, so no
code change is needed (and so a future reader does not re-discover this).

**Confirmed current state (read at authoring):** Both activities pass a **request DTO**, not bare params:
`AccountSnapshotActivity.accountSnapshot(AccountSnapshotRequest)` (`AccountSnapshotActivity.java:29`) and
`PreTradeCheckActivity.preTradeCheck(PreTradeCheckRequest)` (`PreTradeCheckActivity.java:26`). The impls
ALREADY resolve the broker via `BrokerClientRegistry` keyed on the request's `tenant_id`
(`AccountSnapshotExecActivityImpl` P4-c-b comment + per-tenant resolution at `:36-38`;
`PreTradeCheckExecActivityImpl` P4-a comment at `:17-18`), falling back to `ACCOUNT_LEVEL` only when
`tenant_id` is null/blank. Under env-fallback creds both paths resolve the same single account, so behavior
is preserved until per-tenant DB creds are active (Phase E).

**Net-new = none, IF the request DTOs carry `tenant_id` from the caller.** The verify step is:
confirm `AccountSnapshotRequest` and `PreTradeCheckRequest` are populated with the real `tenant_id` at
every call site (signal path, recon, BFF portfolio). If a caller leaves `tenant_id` blank for a live
tenant, that is a one-line fix folded into this phase.

**Replay:** none. **Spotless:** only if the verify surfaces a one-line caller fix (then `orchestrator`).

**Tests (TDD / evidence):** an assertion-style test or documented evidence that for a live tenant the
request DTO's `tenant_id` is non-blank at the call site, so the impl resolves the tenant's own account.

**Verify / success criteria:** Documented evidence in the PR body (the `:36-38` / `:17-18` resolution paths +
the DTO population call sites). No behavior change. If a blank-tenant caller is found, a test that the live
tenant's request carries its `tenant_id`.

---

## Phase D — Per-tenant boot-probe warming (PARTIAL, low priority) — `exec`

**Goal:** Optionally eager-warm the account-identity probe for runtime-added tenants. **Low priority — LAZY
first-resolve already works.**

**Confirmed current state:** `AlpacaAccountIdentityProbe` warms only `EXEC_BOOTSTRAP_TENANT_ID`. For
runtime-added tenants, the LAZY first-resolve verification already runs `BrokerAccountIdentityVerifier` on
first use (the registry build runs it, under the db soft-boot carve-out), so a new tenant's first order is
verified. Eager warming is purely a latency optimization for the first order.

**Changes (anchors):** extend the warm set in `AlpacaAccountIdentityProbe` to iterate the Phase-A
`registry.list()` for the live pod, OR explicitly DEFER (recommended) — leaving lazy first-resolve as the
behavior. **Recommend DEFER**; document that lazy verification is sufficient.

**Replay:** none. **Spotless:** `exec` (only if implemented).

**Tests (TDD):** if implemented, a test that a runtime-added tenant is in the warmed set; otherwise a test
documenting that first-resolve verification fires for an un-warmed tenant.

**Verify / success criteria:** Either deferred-with-rationale in the PR body, or `mvn -pl services/exec -am test`
green with the warming test. This phase MUST NOT block E/F/H.

---

## Phase E — The coupled lift: enable DB creds on live + require non-blank `expected_account_id` (PARTIAL) — `exec`

**Goal (R-3.1, the coupled lift — MUST be the LAST code merged):** In ONE change, remove the `-live` DB-creds
refusal AND make a live resolve fail closed unless the row has a non-blank `expected_account_id`.

**Confirmed current state:** The refusal is at `DbBrokerCredentialSource.java:94-102` (`if (live) throw
unavailable(...)`) and `BrokerCredentialWriter.java:130-137` (`if (live) throw IllegalStateException(...)`).
The blank-tolerance is at `DbBrokerCredentialSource.java:132`
(`String expected = row.get(EXPECTED_ACCOUNT_ID) == null ? "" : ...`) — blank is a deliberate paper no-op
today. Envelope encryption (AES-GCM DEK under KEK, AAD-bound at `:135`), the `expected_account_id` column,
`validateOnEntry` (`BrokerCredentialWriter.java:143`), and the per-order `AccountMismatchError` cross-check
ALL already exist.

**Changes (anchors):**
- `DbBrokerCredentialSource.java:94-102` — remove the unconditional `-live` refusal.
- `BrokerCredentialWriter.java:130-137` — remove the unconditional `-live` write refusal.
- `DbBrokerCredentialSource.java:132` — for a **live** resolve, treat blank `expected_account_id` as a
  **fail-closed error** (throw `unavailable("live credential row missing expected_account_id ...")`),
  not the paper no-op. Tighten the per-call cross-check's "blank = skip" relaxation so it never skips for
  live.
- **R-6.5 cross-tenant uniqueness** for live rows: enforce that no two live tenants bind the same
  `expected_account_id` (a unique constraint on `(provider, expected_account_id)` for live rows in the
  `broker_credentials` table migration, or a guarded check in the writer). Prevents double-sizing one real
  account.

**Replay:** none (credential read/write is outside workflow code). **Spotless:** `exec`. **Operator:**
manual `exec-alpaca-live` roll after merge (out of deploy matrix); KEK + TLS + NetworkPolicy preconditions
(P0) already in place. **Migration** for the uniqueness constraint = manual `kubectl apply` + Flyway.

**Tests (TDD):**
- `DbBrokerCredentialSource` test — a **live** resolve with blank `expected_account_id` throws fail-closed
  (NOT the paper no-op); with a non-blank value it resolves.
- `DbBrokerCredentialSource` test — a live resolve no longer throws the blanket `-live` refusal.
- `BrokerCredentialWriter` test — a live write succeeds (refusal gone) AND still runs `validateOnEntry`.
- Uniqueness test — binding the same authenticated account to a second live tenant is rejected (R-6.5).

**Verify / success criteria:** `mvn -pl services/exec -am spotless:apply && mvn -pl services/exec -am test`.
Behavioral: a live tenant with a non-blank pinned account resolves + the per-order assertion passes for a
matching account and fail-closes a mismatch; blank live row = hard fail.

---

## Phase F — One-click activation: emit the LivePromotion record + deactivation + required-config gate + canary (NET-NEW) — `orchestrator` + `api-gateway` + `audit`

**Goal (R-3.3, R-3.4, R-3.5, R-6.7, R-5.x):** A server-side activation endpoint that, on one click,
**programmatically emits a fresh `LivePromotionApproved` record** (machine-attributed to the authenticated
operator + the verified account id) so the **already-wired** order-time gate keeps firing — gated behind a
fail-closed required-config check and an automatic canary.

**Confirmed current state:** The enforcing gate is ALREADY live, not advisory:
`CopytradeSignalWorkflowImpl.java:464-488` reads `Workflow.getVersion(VERSION_LIVE_PROMOTION_GATE, ...)`
unconditionally, and for a live config refuses the order (no `placeOrder`, no PositionWorkflow, emits
`LivePromotionMissing`) when `auditQuery.checkLivePromotion(tenant, strategy, broker_target, notStaleSince)`
is not `VALID`. So Phase F does NOT modify this workflow — it makes the activation endpoint WRITE the row the
gate looks up.

**Changes:**
- New activation endpoint in `services/api-gateway` (e.g. `POST /admin/tenants/{tenant}/strategies/{strategy}/activate-live`),
  dark-gated behind a new flag (default off), operator-auth required.
- The endpoint, **server-side fail-closed (R-3.4)**, refuses unless VERIFIED-at-activation:
  `daily_loss_threshold > 0`, notional cap set, `capital_source == account_cash` (NOT static $100k), kill
  switch armable. These are read from the live strategy_config + a fresh account probe, NOT assumed from
  YAML.
- On pass, **emit `LivePromotionApproved` (R-3.3)** for `(tenant, strategy, broker_target)` via the
  existing `LivePromotionActivities`/promotion write path, machine-attributed to the authenticated operator
  + the `expected_account_id` probed in Phase I. The existing gate at `:464` then returns `VALID`.
- **Deactivation path:** an endpoint that invalidates the promotion (delete/expire the row). Result: the
  next live BTO sees ABSENT/STALE at `:475` → fails closed. No code change to the workflow.
- **Canary (R-3.5, R-6.7):** the activation arms the first live session with **floored `min_contracts` /
  a low notional cap**, auto-lifted after N clean fills or T hours. This is the non-interactive substitute
  for the removed second pair of eyes, and the safe interaction with the broker-side 403 block (operator
  lifts the block only after clean canary fills).
- **Audit (R-5.1/R-5.2):** register new hash-chained audit kinds for add/activate/rotate/deactivate/delete
  in `AuditEventKinds.ALL_KINDS`, carrying the REAL authenticated operator identity and ZERO key material.

**Replay:** **NO new `getVersion`** — Phase F writes the promotion row from OUTSIDE the workflow; the
workflow gate is unchanged. **Spotless:** `orchestrator`, `api-gateway`. **Audit kinds:** MUST register or
`KindRegistryGuardTest` blocks the push.

**Tests (TDD):**
- Activation refuses (fail-closed) when `daily_loss_threshold<=0`, notional cap unset, `capital_source`
  is static $100k, or kill switch not armable — each its own case.
- Activation on a valid config writes a fresh `LivePromotionApproved`; an end-to-end test that the
  signal-path gate then returns `VALID` and a live BTO is allowed (and is CAPPED to canary sizing).
- Deactivation → next live BTO sees ABSENT/STALE → `LivePromotionMissing`, no `placeOrder`.
- Audit test: activate/deactivate write hash-chained rows with the operator identity and **no key bytes**.

**Verify / success criteria:** `mvn -pl services/orchestrator,services/api-gateway,services/audit -am spotless:apply`
then those module tests. Behavioral: one-click on a compliant tenant arms a CAPPED live session with a valid
promotion; one-click on a non-compliant tenant is refused server-side; deactivate fails the next live order
closed.

---

## Phase G — Multi-account fills: one trade_updates WS socket per live tenant (NET-NEW; full router DEFERRED) — `exec`

**Goal:** Real-time fill parity for EVERY live tenant under the shared-account path by opening one
authenticated Alpaca `trade_updates` WebSocket per live tenant — NOT the 30s-poller interim.

**DECIDED (fork resolved):** **Per-tenant WS sockets** (real-time parity), NOT the poller interim. The
`FillPoller` (already multi-account-correct per `(tenantId, brokerTarget)`, 30s) remains as the
belt-and-suspenders fallback; the dispatcher dedup makes WS + poller double-delivery a no-op.

**Cost confirmation:** the Alpaca `trade_updates` stream is part of the **FREE Trading API**
(`wss://api.alpaca.markets/stream`, authenticated per-account key/secret) — NO paid market-data
subscription. N sockets carry **zero incremental Alpaca cost**.

**Confirmed current state:** `AlpacaTradeUpdatesStream` is single-account / pod-wide / tenant-blind: its WS
auth frame uses the single injected `alpacaProps.apiKeyId()/apiSecretKey()`
(`AlpacaTradeUpdatesStream.java:184-196`), `replicas:1`, not leader-elected. Dedup + routing are already
tenant-aware/idempotent (`FillDispatcherImpl.markFilled` conditional + `resolveWorkflowId` attributes the
tenant from the journal row) — so they need **NO change**.

**Changes (anchors):**
- `AlpacaTradeUpdatesStream.java` — enumerate live tenants via the Phase-A `registry.list()` (Phase G
  **DEPENDS on Phase A**) and open **one authenticated `trade_updates` socket per live tenant**, resolving
  each tenant's `ws_url` + creds via `BrokerClientRegistry` / `BrokerCredentialSource` instead of the
  injected pod-wide `AlpacaProperties`. The auth frame at `:184-196` reads the per-tenant resolved creds,
  not `alpacaProps`.
- Dedup/routing: **no change** (`FillDispatcherImpl.markFilled` + `resolveWorkflowId`).

**HARD single-pod invariant (reassert):** N sockets DEEPEN the `replicas:1` / not-leader-elected
constraint — 2 pods → 2N sockets → DOUBLE fill signals per account. Until the deferred leader-elected
router (PLAN-multi-tenant-broker-credentials P5, OUT OF SCOPE) exists, **`exec-alpaca-live` MUST stay
`replicas:1`** — call this out in the manifest + the PR as a load-bearing invariant, not an incidental
topology.

**Replay:** none. **Spotless:** `exec`. **Operator:** `exec-alpaca-live` manual roll after merge (out of
deploy matrix); the `replicas:1` invariant is reasserted in the live deployment manifest.

**Tests (TDD):**
- A test that the stream opens one socket per live tenant from `registry.list()`, each authenticating with
  that tenant's resolved creds (NOT `alpacaProps`).
- A fill on tenant B's socket routes to B's PositionWorkflow (`resolveWorkflowId` from B's journal row).
- A WS + poller double-delivery for the same fill is a no-op (`markFilled` conditional).

**Verify / success criteria:** `mvn -pl services/exec -am spotless:apply && mvn -pl services/exec -am test`.
Behavioral: a fill on any live tenant is reflected in real time on its own socket and is NOT double-counted;
`exec-alpaca-live` manifest pins `replicas:1`.

---

## Phase H — Flip `multitenant.broker-accounts.enabled` (SHIPPED machinery; per-cluster opt-in) — operator + `orchestrator` config

**Goal:** Allow arbitrarily many live tenants on the shared `alpaca-live` `broker_target`, each with a
distinct non-blank `broker_account_id`.

**Confirmed current state:** The flag is read at `CrossTenantBrokerTargetBootstrapper.java:40`
(`@Value("${multitenant.broker-accounts.enabled:false}")`, **dark by default**). When true, the validator
switches to `CrossTenantBrokerTargetValidator.ownerBySharedBrokerAccounts` (`:117`), which already supports
many tenants iff each declares a distinct non-blank `broker_account_id` and a single tenant's strategies
share one account (`:114-141`).

**Changes:** No new code (the machinery is shipped). This is a **per-cluster opt-in** (homelab override),
NOT a repo default. **SAFE TO FLIP only AFTER Phase B is merged (+ Phase C confirmed)** so all account-level
reads resolve per-tenant.

**Replay:** none. **Operator:** set the flag on the homelab orchestrator (env/ConfigMap override, manual
`kubectl apply`); the repo default stays `false`.

**Tests (TDD):** validator test (likely existing) — with the flag on, two live tenants with distinct
`broker_account_id` boot; two tenants with the SAME `broker_account_id` fail closed; a blank
`broker_account_id` on a live tenant fails closed.

**Verify / success criteria:** Flag-on boot with ≥2 distinct-account live tenants passes; same-account or
blank fails the boot guard. Behavioral on homelab: enabling the flag does not reject the existing live
tenant set.

---

## Phase I — Tenant CRUD API + admin UI pages (NET-NEW) — `tenant-dashboard-bff` + `api-gateway` + `dashboard`

**Goal:** The operator-facing surface: create tenant, paste keys (probe-validated, account read back),
attach a strategy, list all tenants/accounts, and the one-click activate button.

**Confirmed current state (reuse — do NOT rebuild):** The credential write path is SHIPPED end-to-end:
dashboard → api-gateway `BrokerCredentialController /broker-credentials` (dark-gated
`broker.credentials.write.enabled`, cross-tenant guard, rate-limit/lockout) → exec
`BrokerCredentialAdminController /internal/broker-credentials` → `BrokerCredentialWriter` →
`broker_credentials` table. StrategyConfig read/write is SHIPPED (`StrategyConfigController` BFF +
api-gateway, `StrategyConfigWriter` CAS). **Net-new** is: a create-tenant endpoint (= INSERT the first
strategy_config row for a new `tenant_id`; NO separate tenant entity, per the Phase-0 DECISION), the
activate/one-click endpoint (Phase F), a **list-all-tenants admin read** (`DbStrategyConfigReader` is
point-lookup only — add a list method backed by the DISTINCT query / Phase-A `registry.list()`), and the
dashboard pages (onboarding form, account list, activate button — UI-P1..P5, none shipped).

**Changes:**
- **Create-tenant endpoint** (BFF + api-gateway): "create" = INSERT the strategy_config row(s) for a new
  `tenant_id` (Phase-0 DECISION — NO `tenants` table, no separate entity).
- **List-all-tenants admin read** (BFF + api-gateway) backed by the `SELECT DISTINCT tenant_id, strategy_id`
  list method, surfacing per-tenant: broker, masked account number, paper/live badge, kill-switch state,
  last synced. **Status is DERIVED** (`strategy_config.enabled` + fresh `LivePromotionApproved` presence +
  broker-403 state), NOT a stored column; **operator attribution comes from the `audit_log` chain**, not a
  `created_by` column.
- **Onboarding form (R-2.1):** operator pastes `api_key_id` + `secret`; the form calls the SHIPPED
  credential write path which runs `validateOnEntry` (`BrokerCredentialWriter.java:143`) → probes
  `/v2/account` BEFORE persistence → returns the AUTHENTICATED account number. The UI **surfaces that
  number read-only** for visual confirmation; the operator NEVER free-types it; it becomes the immutable
  `expected_account_id`. Reject keys that don't authenticate.
- **Activate button** → the Phase F one-click endpoint (required-config gate + LivePromotion emit). The
  first-live-exposure throttle is the broker-side 403 block (operator lifts it after watching clean fills),
  NOT an app canary (operator decision 2026-06-28).
- **Activation TTL display (R-UI, real-money ONLY):** for each LIVE tenant, the dashboard MUST clearly
  surface the live-activation state and its expiry — e.g. "Live · activation valid until `<expires_at>`
  (re-activate by then)" with a countdown/at-risk badge as it nears the `LIVE_PROMOTION_TTL` window (30d).
  The TTL is a real-money dead-man's-switch ONLY: paper tenants never hit the promotion gate, so they show
  no activation/TTL — render a plain "Paper" badge with NO expiry. Distinguish the two unmistakably so an
  operator never mistakes a paper tenant for a live-armed one. Source the expiry from the newest
  `LivePromotionApproved` `occurred_at` + `LIVE_PROMOTION_TTL`; a STALE/DEACTIVATED/ABSENT live tenant shows
  "not armed — will not place new live entries" (open positions still managed).
- **R-1.2 no secret egress:** keys are write-only — never returned by any API, never rendered (mask
  `••••1234`), never logged, never an audit subject. (The shipped write path already enforces this; the UI
  MUST NOT echo them.)
- All write/activate surfaces dark-gated (default off); operator-auth required.

**Replay:** none (HTTP + UI). **Spotless:** `tenant-dashboard-bff`, `api-gateway`. **Contract:** new request
DTOs regenerate POJO + pydantic if added to `contract/schemas/*.json`.

**Tests (TDD):**
- BFF/api-gateway: create-tenant, list-all-tenants (returns masked account + badge), and the cross-tenant
  guard (operator scope) — each tested.
- Onboarding: a bad key set is rejected (probe fails, nothing persisted); a good set returns the
  authenticated account, persisted as `expected_account_id`, masked on read-back.
- Secret-egress test: no endpoint returns key material; no log line / audit row contains it.
- Dashboard component tests for the onboarding form (read-back of probed account), account list, and the
  gated activate button.

**Verify / success criteria:** `mvn -pl services/tenant-dashboard-bff,services/api-gateway -am spotless:apply`
+ those module tests + the dashboard test suite. Behavioral: operator pastes keys → sees "Connected to
84xxx, live, $X, options L3" read-only → attaches strategy → clicks activate → a compliant tenant arms a
CAPPED live session; a non-compliant tenant is refused with a clear server-side reason.

---

## Ship order & gating (risk order — coupled lift + activation LAST)

```
Phase 0 (DECIDED: DISTINCT-from-strategy_config; per-tenant WS sockets)
   └─> Phase A (enumeration + reconcile loop)        ── unblocks restart-free + G + I
          ├─> Phase B (per-tenant recon read)        ── parallel
          ├─> Phase C (verify AccountSnapshot/PreTradeCheck)  ── parallel
          └─> Phase D (boot-probe warming, DEFER)    ── parallel, low priority, non-blocking
   Phase B (+ C confirmed) ──> Phase H (flip multitenant.broker-accounts.enabled)  [operator opt-in]
   Phase A ──> Phase G (one trade_updates WS socket per live tenant; needs the enumeration source)
   ─────────── identity/gates/canary/TLS all in place + tested (R-X.2) ───────────
   Phase F (activation endpoint + LivePromotion emit + required-config gate + canary)
   Phase E (THE COUPLED LIFT — remove -live refusal + require non-blank expected_account_id)  ── LAST CODE
   Phase I (tenant CRUD + admin UI)   ── ships dark; wires to F's activation; safe to build alongside,
                                          but its write/activate flags flip only after E + F merged
```

Strict gating rules:
1. **Nothing arms live until Phase E + Phase F are merged AND the P0 TLS/NetworkPolicy/KEK preconditions
   hold.** Until then every gate is dark (R-X.1).
2. **Phase E (the coupled lift) is the LAST code phase merged** — only after identity binding (I/R-2.1),
   the required-config gate + LivePromotion emit + canary (F), `expected_account_id` uniqueness (E/R-6.5),
   and TLS/NetworkPolicy (P0) are in place + tested. (R-3.1 / R-X.2.)
3. **Phase H flag flips only after Phase B merged + Phase C confirmed**, as a per-cluster operator override
   (repo default stays `false`).
4. **The broker-side 403 unblock is folded into the canary (Phase F/G)** — never "live + full-size +
   unblocked" in one irreversible action (R-6.7).
5. Each phase = one single-concern PR, TDD-first, `spotless:apply` on every touched module, operator merge
   gate (trading-critical), `exec-alpaca-live` manual roll where exec live behavior changes.

---

## Phase classification

| Phase | Classification | Module(s) |
|---|---|---|
| 0 | **DECIDED** (≈0 code) — DISTINCT-from-strategy_config; per-tenant WS sockets | — |
| A | **NET-NEW** (biggest) | orchestrator |
| B | **NET-NEW** (~3 lines + tests) | contract, exec, orchestrator |
| C | **Mostly-shipped — VERIFY/DOC** (resolution already per-tenant) | exec/orchestrator (verify) |
| D | **PARTIAL — recommend DEFER** (lazy first-resolve already works) | exec |
| E | **PARTIAL** (refusal lift + assertion; crypto/column/validate-on-entry shipped) | exec |
| F | **NET-NEW** (enforcing gate ALREADY wired; emit-record + activate + canary + deactivate new) | orchestrator, api-gateway, audit |
| G | **NET-NEW** (per-tenant WS sockets; FillPoller already multi-account as fallback; dedup/routing shipped; full leader-elected router DEFERRED; `replicas:1` reasserted) | exec |
| H | **SHIPPED machinery** (validator shared-mode shipped; just flip the dark flag, operator) | orchestrator config (operator) |
| I | **NET-NEW UI + PARTIAL API** (cred + strategy write paths shipped; create-tenant/list-all/UI new) | tenant-dashboard-bff, api-gateway, dashboard |
