# PLAN — 2026-07-02 Admin "Add Tenant" UI for ridopark@gmail.com

**Goal.** Give the admin `ridopark@gmail.com` a working dashboard UI to **create a tenant, paste
its Alpaca keys, and one-click activate it** — for **both paper and live (real-money)** accounts —
locked to that one email.

**The headline: ~90% of this is already built and dark-gated.** The operator-onboarding stack —
admin-gated UI pages, create-tenant / credential-write / activate-live endpoints, the
`LiveActivation` workflow with the required-config gate + LivePromotion audit record, the
envelope-encrypted `broker_credentials` DB store, the per-tenant fill sockets, the restart-free
`TenantReconcileLoop`, and the `OPERATOR_EMAILS` dashboard allowlist — all ship today behind
default-off flags. This plan does **not** rebuild any of that.

What is genuinely left is:
1. **One code change** — lift the hard `-live` DB-creds refusal so real-money accounts can use the
   DB credential store, behind a new default-off flag (Phase 1).
2. **One recommended hardening PR** — a backend operator allowlist (defense-in-depth), because the
   backend currently trusts any `X-Operator-Id` the dashboard sends (Phase 2).
3. **A carefully-sequenced operator enablement + credential/config source migration** (P0) — the
   dangerous part, because the credential and strategy-config *source* is **pod-global**, so turning
   it on for a new tenant also switches the already-LIVE `prod_real` money account onto the same
   source. This must be rehearsed on the paper pod first.

Source of findings: two code surveys on 2026-07-02 (dashboard auth + admin wiring; onboarding
controllers + credential/enumeration sources), all anchors below re-read at authoring time.

---

## The critical constraint (read first)

`broker.creds.source` (`env` | `file` | `db`) selects **one** credential-source bean for the
**entire exec pod** (`@ConditionalOnProperty`, mutually exclusive:
`EnvFallbackBrokerCredentialSource.java:28`, `FileMountedBrokerCredentialSource.java:62`,
`DbBrokerCredentialSource.java:51`). Likewise `strategy.config.source` (`yaml` | `db`) selects one
registry for the **entire orchestrator** (`YamlStrategyRegistry.java:16`,
`DbStrategyRegistry.java:30`). **There is no per-tenant mixing.**

Consequence: you cannot have `staging_paper` on file-creds and a new tenant on db-creds *on the same
pod*. Flipping either source is **all-or-nothing per pod / per orchestrator**, so **every tenant
already served by that pod must be backfilled into the DB before the flip**, or resolution fails
closed and those tenants stop trading. This is why the live pod (`prod_real`, real money) is the
**last** thing touched, only after the whole flow is proven on the paper pod.

---

## P0 — Operator preconditions & migration (NO code; live-cluster only, ordered)

These are out-of-band cluster actions (homelab is the live target; live-tenant config is not in the
repo). Do them in this order. **Rehearse the entire block on the PAPER pod first (dev +
staging_paper), prove a new paper tenant end-to-end, and only then repeat for the LIVE pod.**

**A. KEK provisioning (both exec pods).** `BrokerCredentialCryptoConfig` loads a base64 32-byte KEK
from `broker.creds.db.kek-path` (`/etc/broker-kek/kek`) and **crashloops the pod at boot if it is
missing/blank/malformed** (`BrokerCredentialCryptoConfig.java:17-37`). Create the `broker-kek`
secret out-of-band and mount it (the mount scaffolding already exists, `52-exec-alpaca-paper.yaml:126-136`)
**before** any `broker.creds.source=db` flip.

**B. Backfill broker credentials into the DB (per exec pod).** For **every** tenant currently
served by the pod (paper pod: `dev`, `staging_paper`; live pod: `prod_real`), write an
envelope-encrypted row into `broker_credentials` via the shipped write path
(`BrokerCredentialWriter`, `broker_credentials` table `V5__broker_credentials.sql`) with a non-blank
`expected_account_id` matching the real Alpaca account.

> **Identity-gate caveat (risk review C3).** Under `source=db`, the boot probe
> (`AlpacaAccountIdentityProbe.java:90-104`) warms only the pod's **bootstrap** tenant and
> **soft-boots (skips warm-up) if that row isn't written yet** — so "verify identity via the boot log
> before flipping" is only a real pre-roll assertion when the live pod's `EXEC_BOOTSTRAP_TENANT_ID`
> equals the live tenant (`prod_real`) AND its row (with `expected_account_id`) is backfilled before
> the roll (a mismatch then crashloops boot). Otherwise identity is enforced **lazily at the first
> order** via `registry.build()` → `verify()` — still fail-closed (the broker is published only after
> `verify()` passes, so no order hits a wrong account), but the boot-log signal is void. **Either**
> set `EXEC_BOOTSTRAP_TENANT_ID`=the live tenant + backfill its row before the roll, **or** treat the
> P0/G capped canary's first fill as the identity gate. Do not rely on boot-log verification as the
> sole pre-roll check.

**C. Backfill strategy_config DB rows (orchestrator).** Before flipping `strategy.config.source=db`,
every tenant currently resolved from the YAML mount must have a **complete** `strategy_config` row
(full config JSONB, not just the `(tenant_id, strategy_id)` key) — the DB source drives both
enumeration (`TenantReconcileLoop` → `registry.list()`) and config-value resolution. A partial
backfill = existing tenants lose their config and stop trading. Diff the produced rows against
`tenants/dev/strategies/*.yaml` + the live-cluster `staging_paper`/`prod_real` YAMLs.

**D. Creds-hop hardening (before the LIVE flip only).** Confirm the
`api-gateway → exec /internal/broker-credentials` hop is TLS and a `NetworkPolicy` restricts that
route to the api-gateway pod only. The plaintext secret transits that HTTP body once on write; it
must be unreachable elsewhere.

**E. Enablement flag flips (operator; per-cluster override, repo default stays off).** After A–C for
a pod, set (via `kubectl set env` / patched Deployment — Spring relaxed-binding maps the env names):
- **dashboard pod:** `OPERATOR_EMAILS=ridopark@gmail.com`, `OPERATOR_TENANT_CREATE_ENABLED=true`,
  `OPERATOR_CREDENTIAL_WRITE_ENABLED=true`, `OPERATOR_ACTIVATION_ENABLED=true`
  (`dashboard/lib/operator.ts:11`, `dashboard/app/admin/*`).
- **api-gateway pod:** `OPERATOR_TENANT_CREATE_ENABLED=true`, `OPERATOR_CREDENTIAL_WRITE_ENABLED=true`,
  `OPERATOR_ACTIVATION_ENABLED=true`, `OPERATOR_ALLOWLIST=ridopark@gmail.com` (Phase 2 backend gate —
  fail-closed: unset = deny-all = every admin route 403s), and a strong `API_GATEWAY_SHARED_TOKEN`
  (the `ServiceTokenFilter` bearer, `application.yml:34-70`; fail-fast on the default token under `prod`).
- **tenant-dashboard-bff pod:** `OPERATOR_ADMIN_READ_ENABLED=true`, `OPERATOR_ALLOWLIST=ridopark@gmail.com`
  (independent config from the gateway — must be set here too or admin-read 403s), matching `BFF_SHARED_TOKEN`.
- **exec pod:** `broker.creds.source=db` (after A+B).
- **orchestrator:** `strategy.config.source=db` (after C) and `multitenant.broker-accounts.enabled=true`
  (`CrossTenantBrokerTargetBootstrapper.java:40`) so >1 tenant may share one `broker_target`.

> **Allowlist-sync (risk review C5).** The backend `OPERATOR_ALLOWLIST` (api-gateway + bff, both
> pods) and the dashboard `OPERATOR_EMAILS` must name the SAME operator(s) — a drift where the
> dashboard admits an email the backend rejects yields a confusing "UI visible, every action 403s"
> state. Keep them in lockstep. Note `X-Approver-Id-2` (dual-approval on promotion/killswitch) is
> deliberately NOT covered by the Phase 2 allowlist and remains header-trusted (out of scope).

**F. `exec-alpaca-live` is a MANUAL roll** — it is not in the deploy matrix; the Phase 1 image +
its `broker.creds.source=db` + `broker.creds.db.live-enabled=true` flip require an explicit operator
roll after merge. Keep `exec-alpaca-live` at `replicas:1` (the leader-elected fill router is out of
scope; >1 replica = double fills).

**G. Live canary.** A newly-activated live account runs **capped first** (min-contract), and the
broker-side 403 unblock at Alpaca is folded into the canary — never "live + full-size + unblocked"
in one irreversible action. Deactivate path is the shipped `LiveDeactivationWorkflow`.

**H. Cross-tenant live-account uniqueness (manual gate until the code follow-up ships).** There is
**no enforcement today** that two tenants can't point at the SAME live Alpaca account — `broker_credentials`
has `PRIMARY KEY (tenant_id, provider)` and **no unique constraint on `expected_account_id`**
(`services/exec/.../db/exec/V5__broker_credentials.sql`), and `CrossTenantBrokerTargetValidator` only
checks `strategy_config.broker_account_id` at boot behind the dark `multitenant.broker-accounts.enabled`
flag — it never reads `broker_credentials.expected_account_id`. Two live rows on the same account =
double-trading one account. **Until the follow-up lands, the operator MUST manually verify at backfill
time that each live tenant's `expected_account_id` is unique.** Code follow-up (separate PR, belongs
with the `CrossTenantBrokerTargetValidator` lineage / P4-c-b hardening): either a Flyway partial unique
index on `(provider, expected_account_id) WHERE expected_account_id <> ''`, or a pre-persist
cross-tenant read in the writer. Do not enable a 2nd live tenant before this gate (manual or coded) holds.

---

## Phase 1 — Lift the `-live` DB-creds refusal, behind a new default-off flag (`exec`)

**Goal:** allow a `-live` exec pod to serve DB-sourced credentials (required for real-money tenants
via the UI), without changing paper behavior and without arming live by default.

**Changes** (anchors) — the `-live` refusal exists on BOTH the read and write paths (the original
epic's "Phase E" bundled both); lift both behind the SAME flag `broker.creds.db.live-enabled`
(`@Value("${broker.creds.db.live-enabled:false}")`, default `false`, dark). Live onboarding needs
both: the write path to persist the pasted keys, the read path to serve them at order time.
- **Read source** — `services/exec/.../alpaca/DbBrokerCredentialSource.java:97` — unconditional
  `if (live) throw unavailable(...)` → `if (live && !liveEnabled)`. When served on a live pod,
  **fail closed if `expected_account_id` is blank**, placed AFTER `resolveTenant()` so it also covers
  the `ACCOUNT_LEVEL` path (risk C1). Mirror `FileMountedBrokerCredentialSource.java:110-121`.
- **Write path** — `services/exec/.../alpaca/BrokerCredentialWriter.java:130` — unconditional
  `if (live) throw IllegalStateException(...)` → `if (live && !liveEnabled)`. When writing on a live
  pod, **re-establish the blank-`expected_account_id`-for-live rejection** that the old unconditional
  refusal subsumed (the writer already validates identity on entry via `BrokerAccountIdentityVerifier`,
  which no-ops on a blank expected id). Same flag, same fail-closed posture.
- No Temporal command-shape change → **no `getVersion` marker** needed (exec activity-impl code).
- **Cross-tenant `expected_account_id` uniqueness** (no two tenants → same live account): investigated
  as part of the write lift; enforced/deferred per that finding (see the PR).

**Tests (TDD):** for BOTH `DbBrokerCredentialSourceTest` (read) and the `BrokerCredentialWriter`
test (write):
- `-live` pod + `live-enabled=false`/unset → still refuses (byte-identical to today); read reads no
  row, write does no probe/persist.
- `-live` pod + `live-enabled=true` + non-blank `expected_account_id` → read resolves the row / write
  validates identity + persists.
- `-live` pod + `live-enabled=true` + **blank/null** `expected_account_id` → fails closed (throws);
  read also covers the `ACCOUNT_LEVEL` sentinel path (risk C1/C2); write persists nothing.
- paper path unchanged (both).

**Verify / success criteria:** `mvn -pl services/exec -am spotless:apply && mvn -pl services/exec test`.
Behavioral assertion: with the repo default (`live-enabled` unset) an `alpaca-live` pod refuses DB
creds exactly as today; only the explicit per-cluster flip serves them, and only with a bound account
id. Spotless on `services/exec`.

---

## Phase 2 — Backend operator allowlist (defense-in-depth) (`api-gateway` + `tenant-dashboard-bff`) — CONFIRMED (ship now)

**Goal:** stop the backend trusting *any* well-formed `X-Operator-Id`. Today authorization lives
only in the dashboard (`admin/layout.tsx:16` + server actions); the api-gateway / bff controllers
check the header is *present and well-formed* but **not that it is allowlisted**
(`api-gateway/.../web/TenantContext.java:91-97`, `tdbff/.../web/TenantContext.java:41-47`). With
real money reachable through these routes, the backend must independently reject a non-allowlisted
operator, so a token leak alone is not sufficient to onboard/activate.

**Decision (fixed 2026-07-02):** ship this now, not as a fast-follow — the scope is real-money live.
The live 403 (P0/G) MUST NOT be lifted until this is merged + deployed.

**Changes** (anchors):
- `services/api-gateway/.../web/TenantContext.java` — after `operatorId()` format validation, check
  membership against an `OPERATOR_ALLOWLIST` (`@Value`, comma-separated, empty = deny-all for the
  admin routes); throw 403 on miss. Apply the same in `tdbff/.../web/TenantContext.java`.
- Keep it a pure authz addition on the already-dark admin routes; no change to tenant-scoped
  read paths.

**Tests (TDD):** allowlisted email → 2xx; well-formed non-allowlisted email → 403; empty allowlist →
403 on every admin route; header absent → existing 400 unchanged.

**Verify / success criteria:**
`mvn -pl services/api-gateway,services/tenant-dashboard-bff -am spotless:apply` +
`mvn -pl services/api-gateway,services/tenant-dashboard-bff test`. Behavioral assertion: a valid
bearer token + a non-allowlisted `X-Operator-Id` cannot create/activate a tenant. Set
`OPERATOR_ALLOWLIST=ridopark@gmail.com` alongside the P0/E flag flips. Spotless on both modules.

---

## Phase 3 — Wire the operator flags into repo config as documented `${ENV:false}` defaults (`api-gateway`) — OPTIONAL hygiene

**Goal:** make the two flags that exist only as `@ConditionalOnProperty` (no `application.yml`
entry) discoverable and explicit, so the enablement in P0/E is auditable rather than implicit.

**Changes:** add `operator.tenant-create.enabled: ${OPERATOR_TENANT_CREATE_ENABLED:false}` and
`operator.credential-write.enabled: ${OPERATOR_CREDENTIAL_WRITE_ENABLED:false}` to
`services/api-gateway/src/main/resources/application.yml` (alongside the already-present
`operator.activation.enabled`, `strategy.config.write.enabled`). Default-false = no behavior change.

**Verify:** `mvn -pl services/api-gateway -am spotless:apply` + context loads; both flags still
default off in the repo (grep the manifests to confirm none set true). Pure config; no test logic.

---

## Ship order & gating

```
Phase 1 (exec -live DB-creds lift, dark)         ── isolated, inert until flag flip; merge first
   └─> Phase 2 (backend operator allowlist)       ── merge before any live 403 unblock (P0/G)
   └─> Phase 3 (flag config hygiene, optional)     ── parallel, trivial
   ───────── code merged + deployed ─────────
P0 rehearsal on the PAPER pod (KEK → backfill creds+config → flip flags → OPERATOR_EMAILS)
   → prove: ridopark signs in, /admin visible; create a NEW paper tenant; paste paper keys
     (UI echoes the resolved broker_account_id); one-click activate; it enumerates + trades paper;
     existing dev + staging_paper unaffected.
   ───────── paper proven ─────────
P0 on the LIVE pod (KEK on exec-alpaca-live → backfill prod_real creds → TLS/NetworkPolicy →
   broker.creds.db.live-enabled=true → manual exec-alpaca-live roll → canary-capped first fill →
   403 unblock folded into canary).   ── LAST, real money
```

Strict rules:
1. **Nothing arms live until Phase 1 (+ Phase 2) are merged/deployed AND P0/A–D hold for the live
   pod.** Repo defaults stay off; every flip is a per-cluster operator override (re-applying repo
   manifests reverts to safe).
2. **The credential/config source flip is all-or-nothing per pod** — backfill EVERY existing tenant
   on the pod first (P0/B, P0/C). Rehearse on paper before touching the `prod_real` live pod.
3. Each code phase = one single-concern PR, TDD-first, `spotless:apply` on every touched module,
   operator merge gate (trading-critical). `exec-alpaca-live` is a manual roll wherever exec live
   behavior changes.
4. The broker-side 403 unblock is part of the canary (P0/G), never a standalone "unblock everything"
   step.

---

## What this plan explicitly does NOT do

- Rebuild any onboarding UI/controller/workflow — all shipped, this is enable + one lift.
- The leader-elected multi-account fill router (keeps `exec-alpaca-live` at `replicas:1`).
- External-customer self-registration / public isolation (separate epic).
- Commit any live-tenant (`staging_paper`/`prod_real`) config to the repo — those stay out-of-band.
