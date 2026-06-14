# Plan — Per-tenant mode + credentials via a SHARED worker + per-tenant resolver (Fork B)

> Operator chose **Fork B** (shared exec worker resolving per-tenant credentials at request time) +
> the **full foundation incl. governance**, over the specialists' Fork-A recommendation. Tradeoffs
> accepted: larger blast radius + a multi-account fill router to build. This plan therefore sequences
> **safety-first** — the account-identity assertion and the enforcing governance gate SHIP BEFORE the
> resolver/fill-router ever serve a live account, so B never runs unguarded on real money.

## Goal

A shared exec worker (per provider, e.g. one `exec-alpaca` pod) serves many tenants. Each order
resolves the tenant's OWN credentials at request time; each tenant independently picks mode
(paper/live). Multiple live tenants run on isolated accounts through one worker.

## Why B is risky here (the constraints the plan must close)

1. **Mis-binding is invisible today.** `OrderIntent` carries no `account_number` and no gate asserts
   one; `placeOrder` POSTs to whatever the injected keys authenticate. A resolver bug (wrong lookup,
   cache-key collision, race) would fill the wrong tenant's live account, and **every existing gate
   keys on the intent's own `(tenant,strategy)` and trusts the routing** — none would catch it.
2. **The fill-listener is single-account, single-pod, non-leader-elected** (`AlpacaTradeUpdatesStream`,
   `replicas:1`). A shared worker needs a **leader-elected multi-account fill router** — one
   authenticated WS per live account, multiplexed — or fills are missed. This is the largest build
   item and is on the money path.
3. **Secret exposure concentrates.** A shared worker that can read every tenant's live keys means one
   compromised/buggy pod exposes all of them. `envFrom` is all-or-nothing — B needs a scoped secret
   store with per-tenant policy, not mass env injection.
4. **The #323 invariant must be replaced.** "One tenant per `broker_target`" is violated by design
   under B (many tenants share a queue). It must become "one tenant per `(broker_target, account)`"
   plus a sound cross-tenant notional-cap basis (the validator's real concern).

## Phases — safety rails BEFORE the resolver touches live

### P1 — Thread `tenant_id` to the broker boundary (Java-only, no regen)
`OrderIntent` already has `tenant_id` (`order-intent.json:27`); `PlaceOrderRequest` drops it
(`PlaceOrderRequest.java:5`). Add `tenantId`; pass `intent.getTenantId()` in
`ExecActivitiesImpl.placeOrder`; surface to MDC + audit. Temporal Activity contract unchanged.
Prerequisite for credential selection AND the account assertion. **No behavior change.**

### P2 — Account-identity assertion + required-gate validator + WS-URL guard (the HARD prerequisites)
These must land and be proven **before** any resolver runs on live.
- **`expected_account_id` pinned per tenant** (new field in tenant/strategy config). The risk review
  notes there is NO account-identity field anywhere today.
- **Per-order fail-closed assertion** in the exec path: fetch `getAccount().accountNumber()` (already
  wired, currently unused — `AccountSnapshotExecActivityImpl:40-43`) and reject non-retryably if it
  ≠ the tenant's `expected_account_id`. This is the control that makes B's mis-bind detectable.
- **Config-load validator (fail-closed at boot)**: a `*-live` tenant with null `daily_loss_threshold`
  (absolute $ — must be sized to the account), `pre_trade_check_enabled` (PDT/buying-power OFF unless
  true), or `notional_cap_pct_of_capital_base` does not boot. For live, "opt-in" → "required".
- **Extend the coherence guard to `EXEC_FILL_LISTENER_WS_URL`** (the cutover bug, generalized).

### P3 — Enforcing LivePromotion gate (governance)
Make the advisory flow a precondition the trading path checks: refuse `*-live` dispatch for a
`(tenant,strategy)` without a valid, non-stale dual-control `LivePromotionApproved`
(`LivePromotionActivities`, `PromotionController`). Promotion-time verification: account_number
confirmed against a live probe, options level sufficient, funded balance non-trivial, required gates
(P2) set + sized. Going live is NEVER a self-service YAML edit. Boot guard sibling to
`CrossTenantBrokerTargetValidator`.

### P4 — Per-tenant credential resolver (the shared-worker core; contract regen likely)
- Per-tenant credentials as **file-mounted, scoped** secrets (or external store w/ per-tenant
  policy) — NOT mass `envFrom`. Naming `broker-creds-<tenant>-<provider>`.
- A `BrokerClientRegistry` keyed on `(tenantId, provider)`: `computeIfAbsent` builds + caches a
  `RestClient` per account (never per call); the **per-resolution account assertion** from P2 runs on
  first use and fail-closes a mismatch before any order. Refactor `AlpacaPaperBroker` /
  `AlpacaConfig` from one boot `RestClient` to registry-resolved.
- Per-request **mode** assertion: resolved cred env (paper/live) matches the intent's `broker_target`
  suffix.
- **Replace #323** with a `(broker_target, account)`-keyed invariant + reworked notional-cap basis
  (risk-manager owns the cap-basis change). Generalize `broker_target` / `BrokerTargetValidator` to
  admit many tenants per provider+env (JSON Schema Java+**Python regen**; verify regex w/ qa-inspector).

### P5 — Leader-elected multi-account fill router (the big build)
A shared worker maintains one authenticated trade-updates WS **per live account**, leader-elected so
`replicas:1` is no longer the only safe topology, routing each fill to the right `(tenant,strategy)`
PositionWorkflow. Until this ships, live fills for a shared worker degrade to the 30s REST poller —
acceptable as an interim ONLY with the poller proven per-account. This is the component that most
justified Fork A; budget it accordingly.

### P6 — Secret-store hardening + templated manifests + RBAC
Scoped secret fetch (Vault/cloud SM w/ per-tenant IAM), rotation runbook, CI secret-scanner
(chat-pasted-key class), and templated deploy wiring. ServiceAccount scoping so the shared worker
cannot read secrets beyond policy.

## Sequencing rule (non-negotiable on the money path)
**No resolver (P4) or shared fill-router (P5) serves a `*-live` account until P2 (account assertion)
and P3 (enforcing gate) are merged and verified.** A shared worker MAY serve multiple PAPER tenants
earlier (low blast radius) to de-risk the resolver before live.

## Execution
Multi-PR epic. Each money-path phase (P2, P4, P5) gets the java-architect + risk-manager consult +
TDD + `/code-review` treatment, shipped one PR at a time via `/execute-plan`, with the operator in the
loop between phases. P1 is the safe starting point (foundational, no behavior change).

---

# Part B — Tenant self-service web UI (configure own config + see status/broker/account)

Goal: a tenant logs into the existing dashboard and can (1) **see** its status/broker/account at a
glance and (2) **configure** its own strategy — **self-explanatory and easy to use**, and SAFE on the
real-money path. Design informed by how established bots/brokers do it (3Commas, Cryptohopper, Coinrule,
Alpaca OAuth) — see "UX principles" below.

## What already exists (reuse — verified)

A mature read-only tenant stack is live: a **Next.js 14 dashboard** (`dashboard/`, Auth.js social
login → `dashboard_user` Postgres table maps identity→`tenant_id`), a **tenant-scoped BFF**
(`services/tenant-dashboard-bff`, `X-Tenant-Id` + service-token, off-ingress) exposing read-only
`GET /api/{positions,trades,orders,portfolio}` (portfolio already calls `AccountSnapshotWorkflow` for
live equity). Tenant isolation, auth, path-safety, data aggregation are PROVEN. **Net-new:** a config
read/write API, broker connection, mode switching, and the UI pages.

## UX principles (from the research — keep it self-explanatory)

- **Connect, don't paste keys.** Use **Alpaca OAuth 2.0** ("Connect Alpaca" one-click). Alpaca's
  `env` param scopes the grant to **paper or live** explicitly; once connected we hold a per-tenant
  OAuth token, never raw API keys — the security win (no key handling in our UI/BFF) AND the
  easy-onboarding win. API-key entry stays a write-only fallback for brokers without OAuth.
- **Paper and live are distinct ENVIRONMENTS, not a soft toggle.** Visually separated (color/badge),
  with a deliberate, governance-gated switch to live (LivePromotion, Part A P3) — never a one-tap
  flip. Highest-stakes action → progressive disclosure + confirmation.
- **Onboarding checklist for an un-configured tenant.** Step cards (Connect broker → Pick mode → Set
  risk limits → Enable strategy), each name + one-line description + action button, checkmark when
  done. No portfolio data until connected (show guidance instead).
- **Connection-health + account card** front-and-center: connected broker, account number (masked),
  paper/live badge, balance, options level, kill-switch state, "last synced".
- **Progressive disclosure for config.** Show the few that matter (capital_weight, max_positions,
  daily_loss_threshold, author_whitelist) with sensible defaults + inline validation; fold the ~20
  advanced knobs under "Advanced". Dangerous settings (mode, broker) are separated and gated.

## How this reshapes Part A (credentials)

**OAuth tokens become the per-tenant credential** the P4 resolver resolves (a Bearer token per
`(tenant, env)`), instead of/in addition to API-key secrets — cleaner, removes raw-key handling. The
P2 account-identity assertion still applies (assert the OAuth-resolved account == the tenant's
expected account). Registering an Alpaca OAuth app + Alpaca's **review for third-party live trading**
is a prerequisite for OAuth-based live (lead time — decide early).

## The hard part: config is boot-only today

Strategy config lives in the `tenants-config` ConfigMap, read at pod boot (no hot-reload by design).
Self-service editing needs a **mutable config store + reload path** — see decision #4.

## Phases (Part B)

- **UI-P1 — Status/account read page (MVP, low risk, shippable NOW).** New BFF read endpoints:
  strategy config (YAML→JSON), kill-switch state (today api-gateway-only), broker/account/mode/options
  level. New Next.js "Status" page: connection-health card + onboarding checklist + account/positions/
  PnL (reuse existing endpoints). Read-only — no new write surface.
- **UI-P2 — Broker connection via OAuth.** Register the Alpaca OAuth app; "Connect Alpaca" (env-scoped
  paper/live); store the per-tenant token in the scoped secret store; surface connection state.
- **UI-P3 — Config-edit backend + governance.** Config read/write API + server-side schema/range
  validation (reuse the contract schema); **safe fields self-service (validated + audited), dangerous
  fields (mode→live, broker, credentials) through the dual-control gate**; reload per decision #4.
- **UI-P4 — Config-edit UI.** Settings page: progressive disclosure, defaults, inline validation, the
  gated mode/broker section, confirmation + clear paper/live separation.
- **UI-P5 — Onboarding polish.** Empty states, guided first-run, health alerts, masked secrets, a
  config-change history/audit view.

Sequencing: UI-P1 is independent and shippable now. UI-P2/P3 depend on Part A — same safety rule:
**no live broker connection or mode→live through the UI until Part A P2 + P3 are merged.** Paper
self-service can land earlier.

---

## Open decisions
1. Secret store for P6: scoped k8s Secrets + RBAC + file mounts (simplest) vs. an external manager
   (Vault / cloud SM) — affects P4/P6 and where UI-P2 OAuth tokens land.
2. Interim fill detection for the first shared *live* worker: REST-poller-only until P5, or hold live
   on a dedicated pod (Fork-A-style) until the router exists. (Recommended: keep live on a dedicated
   pod until P5; let the shared worker handle paper first.)
3. **DECIDED → tenant-entered API keys** (not OAuth). Faster (no Alpaca app review). Mitigations now
   REQUIRED (see below). Part A P4 resolves a per-tenant API key/secret; UI-P2 is a key-entry form.
4. **DECIDED → DB-backed runtime config the orchestrator reloads** (not git/ConfigMap). Faster UX.
   New surface now REQUIRED (see below). Gates UI-P3/P4 and adds Part A P0 (config store).
5. First PR: continue **Part A P1** (in flight) and/or start **UI-P1** (read-only status page,
   independent + immediately useful) now?

## Implications of the locked decisions (must-handle)

**From #3 (tenant-entered API keys) — re-raises the secret-handling risk OAuth would have removed:**
- Keys are **write-only**: never returned by any API, never rendered (masked `••••1234`), never
  logged, never put in an audit subject or error message. Add a CI secret-scanner + a review rule.
- **Encrypted at rest** (envelope-encrypted DB column or a scoped per-tenant k8s Secret written by the
  config service), TLS in transit, scoped so the resolver pod reads only what it needs.
- **Validate on entry**: when a tenant submits keys, immediately call `/v2/account` and capture
  `account_number` + mode + options level → pin `expected_account_id` (Part A P2) AND give instant
  "Connected to 847…, paper, $5k, options L3" feedback (the self-explanatory UX). Reject keys that
  don't authenticate.
- **Mode comes from the key pair** (paper vs live keys + base-url). The Part A P2 per-order
  account-identity assertion is now the load-bearing guard against a wrong/mis-entered key set
  trading the wrong account — it is a hard blocker, not defense-in-depth.

**From #4 (DB-backed runtime config) — new Part A P0 + replay care:**
- **Part A P0 — config store + reload** (new foundational phase): a `strategy_config` table (seeded
  from the current `tenants/` tree), a write path with optimistic concurrency + a `ConfigChanged`
  audit event, and an orchestrator **reload** mechanism. The git `tenants/` tree becomes seed/defaults;
  the DB becomes the runtime source of truth for editable fields.
- **Temporal replay determinism (critical):** a running workflow must read config **deterministically**
  — snapshot at workflow start or fetch via an Activity, NEVER re-read DB mid-workflow in a way that
  diverges on replay. New-config takes effect on the NEXT signal/workflow, not retroactively on
  in-flight ones. Bake this into the reload design; consult risk-manager + java-architect.
- **Everything that reads the tenants tree must move to the DB** (or a DB-synced view): the
  `CrossTenantBrokerTargetValidator` + KillSwitch/Reconciliation bootstrappers (boot), the BFF
  `YamlStrategyRegistry`/`TenantStrategyResolver` (per-request), and the signal path. Migration work.
- **Dangerous fields still gated:** mode→live, broker_target, and credentials route through the
  dual-control gate even though the store is now a DB — the DB write for those fields is the lock the
  approval opens, same as Part A P3.
