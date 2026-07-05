# PLAN — 2026-07-04 Live copytrade fleet enablement (5–10 live tenants, per-tenant accounts)

**Goal.** Enable **5–10 live copytrade tenants, each with its own Alpaca account**, on the single
`exec-alpaca-live` pod (`replicas:1`), under the shared-account path (Fork B: shared exec worker +
per-tenant resolver).

**Source epic:** `docs/plans/PLAN-2026-06-28-operator-account-onboarding.md` (Phases A–I).
**Research:** `docs/research/homelab-capacity-live-copytrade-scaling-2026-07-04.md`.

> **Reality check — re-confirmed by reading the code at authoring time (2026-07-04).** Most of the
> epic's B/E/F/G build **has already shipped** since 2026-06-28. The line anchors in the epic are
> stale. Verified current state below. **The remaining code gap is ONE item: the R-6.5 cross-tenant
> `expected_account_id` uniqueness constraint.** Everything else that gates 5–10 live copytrades is
> **operator cutover** (flag flips + credential onboarding + a manual `exec-alpaca-live` roll). This
> plan is deliberately small because the code is nearly done — do NOT rebuild shipped machinery.

## What already shipped (do NOT re-implement)

| Epic phase | State (verified 2026-07-04) | Anchor |
|---|---|---|
| **B** per-tenant recon read | **SHIPPED** — `brokerListOpenOrders(tenantId, strategyId)` resolves `broker(tenantId)`; workflow threads tenant/strategy | `ReconciliationExecActivityImpl.java:64-68`, `ReconciliationWorkflowImpl.java:189` |
| **E** coupled lift (refusal → flag) | **SHIPPED as flag-gated** — `-live` refusal is now `if (live && !liveEnabled)` (flag `broker.creds.db.live-enabled`, default false) on BOTH read + write; non-blank `expected_account_id` **live seal enforced** on read + write | read: `DbBrokerCredentialSource.java:106-113` (refusal), `:152-159` (seal); write: `BrokerCredentialWriter.java:148-155` (refusal), `:162-168` (seal) |
| **E** R-6.5 account uniqueness | **NOT SHIPPED — the one code gap.** `broker_credentials` has `PRIMARY KEY (tenant_id, provider)` and no uniqueness on `expected_account_id`; writer UPSERTs with no duplicate-account check | `services/exec/src/main/resources/db/exec/V5__broker_credentials.sql:25`; `BrokerCredentialWriter.java:319-325` |
| **F** activation gate + LivePromotion emit + deactivation | **SHIPPED** — full fail-closed gate (not-live → daily-loss/notional → `capital_source==account_cash` → kill-switch armable → fresh account probe) then emits `LivePromotionApproved`; deactivation trips kill switch + voids promotion; endpoint `POST /admin/tenants/{t}/strategies/{s}/activate-live` gated `operator.activation.enabled` | `LiveActivationWorkflowImpl.java:62-151`; `ActivationController.java:33-55,73-136` |
| **G** per-tenant WS fill sockets | **SHIPPED behind flag** — `if (props.perTenantEnabled())` enumerates `liveTenants` and opens one supervised socket per live tenant with per-tenant dedup | `AlpacaTradeUpdatesStream.java:108,145,160,184`; flag `exec.fill-listener.per-tenant-enabled` (`application.yml:80`, env `EXEC_FILL_LISTENER_PER_TENANT_ENABLED`) |
| **H** shared-account validator | **SHIPPED machinery** — flag `multitenant.broker-accounts.enabled` (default false) switches to `ownerBySharedBrokerAccounts` | `CrossTenantBrokerTargetBootstrapper.java:40` |

**Net:** one code phase (uniqueness) + one verification phase (prove the shipped path works end-to-end
with ≥2 distinct live accounts) + operator cutover. Phase A (restart-free onboarding) is **deferred** —
not required when onboarding a batch of tenants and doing a single orchestrator+exec roll (see Deferred).

---

## P0 — Operator cutover (NO code; homelab-only, out-of-band)

Gates/follows the code phases. Repo defaults MUST stay dark (R-X.1).

1. **KEK + TLS/NetworkPolicy preconditions** (epic P0): confirm `BrokerCredentialCryptoConfig` loads the
   KEK on the live exec pod; confirm the `api-gateway → exec /internal/broker-credentials` hop is TLS and
   a `NetworkPolicy` restricts that path to the api-gateway pod. **Precondition of any DB-creds live opt-in.**
2. **Migrate `prod_real` from env-creds → DB-creds FIRST.** Flipping `broker.creds.source=db` means EVERY
   live tenant (including the existing `prod_real`) resolves from `broker_credentials`, and per-tenant fill
   sockets enumerate `liveTenants(provider)` from that table. So `prod_real` must have a `broker_credentials`
   row **before** the flip, and its probed `expected_account_id` MUST equal `847309116`. **Re-probe and
   verify before flipping** or `prod_real` loses its fill socket / fails closed.
3. **Flip the dark flags** on the homelab live pods (per-cluster override; repo default stays false), in
   this order, only AFTER Phase 1 merged + Phase 2 green:
   - exec-alpaca-live: `broker.creds.source=db`, `broker.creds.db.live-enabled=true`,
     `EXEC_FILL_LISTENER_PER_TENANT_ENABLED=true` (fill-listener is already `enabled=true` on live).
   - orchestrator: `multitenant.broker-accounts.enabled=true` (safe — Phase B shipped).
   - api-gateway: `operator.activation.enabled=true` (activation endpoint) + the credential-write flag
     (`broker.credentials.write.enabled`) for onboarding.
4. **Onboard each live tenant (data-only):** write its `broker_credentials` row via the credential-write
   path (`validateOnEntry` probes `/v2/account`, pins the authenticated `expected_account_id`); insert its
   `strategy_config` row with a distinct non-blank `broker_account_id` and live-required config
   (`daily_loss_threshold>0`, notional cap, `capital_source=account_cash`).
5. **`exec-alpaca-live` MANUAL roll** (out of `deploy.yml` matrix) after the flag flips / each new-tenant
   batch — the per-tenant sockets enumerate at startup, so a new live tenant's socket opens on the next roll.
6. **Activate each tenant** via `activate-live` (writes the fresh `LivePromotionApproved`; the order-time
   gate then returns `VALID`).
7. **Canary via the broker-side 403 block** (R-6.7, operator decision 2026-06-28: the 403-unblock is the
   first-exposure throttle, NOT an app canary): keep a newly-armed account 403-blocked at Alpaca, watch the
   first clean fills, then lift the block. Never "live + full-size + unblocked" in one irreversible action.
8. **`exec-alpaca-live` MUST stay `replicas:1`** (load-bearing invariant — the fill listener is not
   leader-elected; 2 pods → 2×N sockets → double fill signals). Do not scale it.
9. **Before applying V6 (the account-uniqueness index): confirm no pre-existing cross-tenant duplicate
   accounts** (code-review finding). V6 is a plain `CREATE UNIQUE INDEX`; if `broker_credentials` already
   held two rows for different tenants sharing one non-blank `(provider, expected_account_id)`, the Flyway
   apply would abort and the exec pod would crashloop. This is trivially safe today — the table is DARK
   (`broker.creds.source=env` on every cluster), so it is empty. Run
   `SELECT provider, expected_account_id, count(*) FROM broker_credentials WHERE expected_account_id ~ '[^[:space:]]' GROUP BY 1,2 HAVING count(*) > 1;`
   and confirm zero rows before applying V6 on `exec_alpaca_live` (and `exec_alpaca_paper`).
10. **Paper accounts are ALSO constrained** by V6 (uniqueness keys on the account being non-blank, not on
    the pod being live — it is a per-broker-target invariant). This is fine today (paper tenants have
    distinct accounts). If a future **shared-paper-demo** model (self-registration epic) needs two tenants
    on ONE paper account, that epic must use a distinct approach — it cannot bind the same paper account to
    two tenant rows on the paper exec DB.

---

## Cross-cutting CI / replay / deploy constraints (apply per phase)

- **Replay safety.** No workflow-history changes in this plan → **NO `getVersion`**. Phase 1 is a
  migration + writer guard (outside workflow code). Phase 2 is tests only.
- **Spotless per module.** `mvn -pl services/exec -am spotless:apply` then `spotless:check` on every
  touched module. The impl env skips spotless; CI fails on it otherwise.
- **Exec migrations live at `db/exec`** (namespaced so they don't collide with orchestrator's `db/migration`).
  Next free version = **V6**. Migration apply on the live exec DB is a **manual `kubectl`/Flyway step**
  (exec-alpaca-live is out of the deploy matrix).
- **PG16 least-privilege footgun** (`reference_pg_where_needs_select`): a partial-unique-index *enforcement*
  needs no role SELECT, but a pre-persist `SELECT … WHERE expected_account_id=?` guard in the writer would
  need column SELECT for the exec DB writer role, and the existing `ON CONFLICT` UPSERT
  (`BrokerCredentialWriter.java:319-325`) already relies on SELECT on the conflict columns. Verify grants if
  adding the writer-side check.
- **PR mechanics.** `gh pr edit --body` is broken → set body at create time or
  `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`. Never touch `.github/workflows/*.yml`. Commit trailer
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Flaky `KillSwitchWorkflowImplTest`** — re-run, do not fix.
- **No tenant ConfigMap drift** — this plan touches no `tenants/dev/*` YAML (live tenants are DB rows).

---

## Phase 1 — R-6.5 cross-tenant `expected_account_id` uniqueness (exec) — THE ONE CODE GAP

**Goal:** Make it impossible for two live tenants to bind the same real Alpaca account (which would
double-size one account). Today `broker_credentials` keys only on `(tenant_id, provider)` and the writer
UPSERTs with no account-collision check.

**Changes (anchors):**
- **New `services/exec/src/main/resources/db/exec/V6__broker_credentials_account_uniqueness.sql`** — a
  **partial** unique index that constrains only non-blank (i.e. live-bound) rows, so multiple paper rows
  with blank/null `expected_account_id` still coexist:
  ```sql
  CREATE UNIQUE INDEX broker_credentials_provider_account_uk
    ON broker_credentials (provider, expected_account_id)
    WHERE expected_account_id IS NOT NULL AND expected_account_id <> '';
  ```
- **Pre-persist guard** in `BrokerCredentialWriter.save` (before the UPSERT at
  `BrokerCredentialWriter.java:319`): if a *different* `tenant_id` already holds this `(provider,
  expected_account_id)`, reject with a typed, operator-facing error (maps to 409/422) carrying NO key
  material. **The index is the authoritative fail-closed guard** (survives concurrent writes); the writer
  check turns the same collision into a clean message instead of a raw constraint violation.

**Decision (fixed by operator 2026-07-04): index + writer check (defense-in-depth).** Both ship in this
phase.
- The **partial unique index** is the race-proof hard guarantee.
- The **writer pre-persist check** costs one `SELECT DISTINCT tenant_id … WHERE provider=? AND
  expected_account_id=?`. **Verify the exec DB writer role has column SELECT on `expected_account_id`**
  before relying on it (PG16 footgun above); the existing `ON CONFLICT` UPSERT already needs SELECT on the
  conflict columns, so the grant is likely present — confirm in the migration/IT.

**Replay:** none. **Spotless:** `exec`. **Operator:** manual Flyway apply of V6 on `exec_alpaca_live`
(+ `exec_alpaca_paper` for parity) — shared-manifest / out-of-matrix step.

**Tests (TDD):**
- Migration/repo test: two rows with the SAME non-blank `(provider, expected_account_id)` but different
  `tenant_id` → the second INSERT fails at the index; two paper rows with blank `expected_account_id` → both
  succeed.
- `BrokerCredentialWriterTest` (writer check): a live save binding an account already held by ANOTHER tenant
  is rejected with the typed error, nothing persisted; re-saving the SAME tenant's own row (rotation)
  succeeds; a distinct account for a new tenant succeeds.
- Grant test / IT: the pre-persist `SELECT` on `expected_account_id` runs under the exec writer role without
  a permission error (guards the PG16 column-SELECT footgun).

**Verify / success criteria:** `mvn -pl services/exec -am spotless:apply && mvn -pl services/exec -am test`.
Behavioral: binding account `847309116` to a second live tenant is rejected with a clean 409 (writer check)
AND cannot be inserted directly (index); a distinct account per tenant is accepted; paper (blank-account)
rows are unaffected.

---

## Phase 2 — Multi-live-tenant end-to-end coverage (exec + orchestrator) — VERIFICATION, low-risk

**Goal:** Prove the *already-shipped* live path works with **≥2 distinct live accounts** before the operator
flips real-money flags — so the cutover is de-risked, not discovered live. This phase adds tests only (and
any one-line caller fix they surface); it ships no behavior change.

**Coverage to add (each its own named test, reusing existing harnesses):**
- **DB-creds live resolve** (`DbBrokerCredentialSource`): with `broker.creds.db.live-enabled=true`, two
  tenants with distinct non-blank `expected_account_id` each resolve their own row; a blank live row fails
  closed (guards the seal at `:152-159` under the flipped flag).
- **Per-tenant fill sockets** (`AlpacaTradeUpdatesStream`, extend `AlpacaTradeUpdatesStreamTest`'s Phase-G
  case at `:301`): with `per-tenant-enabled=true` and 2 live tenants enumerated, two sockets open, each
  authenticating with its own resolved creds; a fill on tenant B routes to B's PositionWorkflow; a WS+poller
  double-delivery is a no-op.
- **Per-tenant reconciliation** (`ReconciliationExecActivityImpl`): a recon tick for tenant B lists B's open
  orders via `broker(B)`, not `ACCOUNT_LEVEL` (locks in Phase B under multi-account).
- **Shared-account validator** (`CrossTenantBrokerTargetBootstrapper`/validator, likely existing): flag-on
  boot with 2 distinct-account live tenants passes; same-account or blank fails closed.
- **Activation gate** (`LiveActivationWorkflowImpl`): each refusal branch (not-live, config, capital_source,
  killswitch, account-probe) and the ACTIVATED path with a probed account for a second tenant.
- **Verify C (no blank-tenant call sites):** confirm `AccountSnapshotRequest`/`PreTradeCheckRequest` carry a
  non-blank `tenant_id` for live tenants at every call site; a blank one is a one-line fix folded here.

**Replay:** none. **Spotless:** `exec`, `orchestrator` (only if a caller fix lands).

**Verify / success criteria:** `mvn -pl services/exec,services/orchestrator -am spotless:apply` + those
module tests green. Behavioral: the full live path (resolve → socket → fill route → recon → activate) is
proven for two distinct live accounts in tests, with no real-money flag flipped.

---

## Deferred (explicit — not required for 5–10 live copytrades)

- **Epic Phase A (runtime tenant enumeration + reconcile loop).** Nice-to-have "restart-free" onboarding.
  For 5–10 tenants set up as a batch, a single orchestrator + `exec-alpaca-live` roll after inserting the
  rows is acceptable (the roll is already required for new fill sockets). Ship A later if per-tenant
  onboarding cadence makes rolls painful. **Not a blocker here.**
- **Epic Phase I (admin UI / CRUD).** The credential-write + strategy-config write paths already exist
  (API-level). The dashboard onboarding UI is a convenience; onboarding can be done via the existing
  endpoints for the initial fleet. **Out of scope for enablement; track separately.**
- **Leader-elected multi-account fill router** (PLAN-multi-tenant-broker-credentials P5). Only needed to run
  `exec-alpaca-live` at `replicas>1` (HA). Out of scope; keep `replicas:1`.

---

## Ship order & gating

```
Phase 1 (V6 account-uniqueness migration + writer guard)   ── one PR, exec, TDD
   └─> Phase 2 (multi-live-tenant e2e coverage)            ── one PR, tests only, de-risks cutover
        ─────────── P0 preconditions (KEK, TLS/NetworkPolicy) in place ───────────
        P0 operator cutover:
          1. apply V6 on exec_alpaca_live (+ paper)
          2. write prod_real broker_credentials row, verify expected_account_id == 847309116
          3. flip flags (creds.source=db, db.live-enabled, per-tenant-enabled, broker-accounts.enabled,
             operator.activation.enabled, credentials.write.enabled)
          4. exec-alpaca-live MANUAL roll  (replicas:1 — DO NOT scale)
          5. onboard + activate each new live tenant (distinct account); canary via 403-block, then lift
```

Strict gating rules:
1. **Nothing arms live until Phase 1 merged + Phase 2 green AND P0 (KEK/TLS/NetworkPolicy) holds** (R-X.1).
2. **`prod_real` gets a verified `broker_credentials` row BEFORE `broker.creds.source=db` is flipped** — the
   flip re-sources every live tenant (incl. prod_real) to the DB and to per-tenant sockets.
3. **`exec-alpaca-live` stays `replicas:1`** through all of this (load-bearing invariant).
4. **The broker-side 403 unblock is the canary** — never live + full-size + unblocked in one action (R-6.7).
5. Each phase = one single-concern PR, TDD-first, `spotless:apply` on every touched module, operator merge
   gate (trading-critical), `exec-alpaca-live` manual roll where live exec behavior changes.

## Phase classification

| Phase | Classification | Module(s) |
|---|---|---|
| P0 | **Operator cutover** (no code) | homelab live pods |
| 1 | **NET-NEW** (V6 partial-unique index + writer pre-persist check) — the only unshipped code | exec |
| 2 | **VERIFICATION** (tests only; de-risks the live cutover) | exec, orchestrator |
| A/I/P5 | **DEFERRED** (not required for 5–10 live copytrades) | — |
</content>
