# PLAN — 2026-07-03 Self-service copytrade onboarding (arm-on-verify + registry-driven fan-out)

**Goal.** Turn the two manual post-onboarding steps into **zero**, so an operator can add a copytrade
shadow tenant end-to-end in the dashboard:

```
create tenant → paste + verify keys → flip "Enable" (only unlocks after verify)
                                          ↳ A. arms the strategy (enabled=true)
                                          ↳ B. registry fan-out auto-delivers this channel's signals
```

Today two things break "data-only": (1) nothing stops arming a tenant (`enabled=true`) before its
broker keys are verified — fail-closed but noisy, and a real footgun for live; (2) which signals reach
a shadow tenant lives in a hardcoded `SIGNAL_EMIT_ADDITIONAL_TARGETS` env on the sidecar, outside the
tenant data model — so a UI-created tenant never receives signals until an operator edits the env and
rolls the sidecar.

This plan ships two composed features:
- **A — arm-on-verify.** `enabled: false→true` is allowed only when a **verified broker account exists**
  for the tenant. UI gate (onboard step-3) + bypass-proof backend guard.
- **B — registry-driven copytrade fan-out.** The sidecar fans out to **every enabled copytrade tenant
  from the registry** (refreshed on an interval), replacing the env list. Composed with A, flipping
  Enable is the single switch that both arms the strategy and subscribes it to signals.

Source of anchors: code survey 2026-07-03 (all `file:line` below re-read at authoring time).

---

## Key design decision — the cross-service enforcement boundary (read first)

Broker credentials live in **exec's** DB (`broker_credentials`, per-broker-target: `exec_alpaca_paper` /
`exec_alpaca_live`). The `enabled` flip flows **dashboard → api-gateway `StrategyConfigController`
(`services/api-gateway/.../web/StrategyConfigController.java:74-99`) → orchestrator
`StrategyConfigUpdateWorkflow` → `StrategyConfigWriter`
(`services/orchestrator/.../platform/StrategyConfigWriter.java`)**. The orchestrator writer **cannot see
exec's creds** (different service/DB), and `enabled` is classified **SAFE** there (freely writable —
`StrategyConfigWriter.java:365-401`, not in the DANGEROUS list at `:416`).

So the bypass-proof guard lives at the **api-gateway**, which already holds an exec HTTP client +
`EXEC_ADMIN_SHARED_TOKEN` (the credential-write forward). It pre-checks "verified account exists" against
exec **before** starting the update workflow. exec exposes that fact via a new **read** endpoint
(sibling to its write endpoint, same `broker.creds.source=db` + `ExecAdminTokenFilter` gate). The UI gate
(A1) is convenience on top; the api-gateway guard (A2) is the real enforcement.

> **Semantic decision for B (CONFIRMED 2026-07-03).** There is ONE `signal-source-discord` (one
> Discord channel), and all copytrade shadow tenants mirror the same authors from it. So "registry-driven
> fan-out" = **this channel's signals go to every `enabled` copytrade tenant**. If a *second* signal
> source/channel is ever added, this would over-fan and each source would need per-source tenant scoping
> (explicitly OUT OF SCOPE here). The sidecar's own primary tenant is always a target (union with the
> registry list, deduped), so it keeps working even if its own row is briefly disabled.

---

## P0 — Operator preconditions & follow-ups (no code)

- **EXEC_BASE_URL routing gap (carried from the enablement).** api-gateway's `EXEC_BASE_URL` is a single
  value (currently `http://exec-alpaca-paper:8080`, live override). Both A2's verified-account read and
  the existing credential-write forward hit it, so they only reach the **paper** pod. Onboarding/arming a
  **live** tenant needs the forward routed to `exec-alpaca-live` by `broker_target` — a per-target routing
  tweak (not in this plan; note it before the live cutover). This plan targets the **paper** path; live
  arming still also requires the separate live-cutover work.
- **B cutover is a per-cluster operator flag flip** (Phase B3): set `SIGNAL_FANOUT_SOURCE=registry` on
  `signal-source-discord` and retire `SIGNAL_EMIT_ADDITIONAL_TARGETS`. `signal-source-discord` is in the
  deploy `RESTART_ONLY` list (`deploy.yml:268`), so the live env override persists across deploys.
- **NetworkPolicy.** A2 adds an api-gateway→exec read call — already covered by the existing
  `exec-alpaca-paper-allow-api-gateway-internal` NetworkPolicy (allows `app=api-gateway`). The sidecar→
  api-gateway poll (B2) is a new hop; confirm no NetworkPolicy blocks `signal-source-discord →
  api-gateway:8082` before the B3 cutover.

---

## Cross-cutting constraints (apply per phase)

- **No Temporal replay markers needed.** The sidecar is a Temporal **client** (starts workflows), not a
  workflow — dynamic target changes just change which `t-<tenant>/s-<strategy>/sig/<id>` workflows start
  (`emitter.py:45-49`; dedupe is per-target, unaffected). The A-guard is api-gateway pre-workflow +
  activity-impl (`StrategyConfigWriter`), neither of which is replay-checked. No `getVersion`.
- **Spotless per touched Java module** (`api-gateway`, `exec`): `mvn -pl <module> -am spotless:apply` then `:check`.
- **No contract-schema change.** A2's exec read and B1's fan-out endpoint return plain JSON `Map`s
  (like the BFF admin reads) — no `strategy-config.json` edit, no pydantic regen. Keep them out of the
  codegen path.
- **Python sidecar:** pytest coverage for new parsing/refresh; the sidecar has a test suite under
  `services/signal-source-discord`.
- **deploy.yml `RESTART_ONLY`:** already includes `signal-source-discord` and `api-gateway`; new env
  (B2 flag) is a preserved live override.

---

## Phase A1 — Onboard wizard "Enable" step, gated on the verified account (`dashboard`)

**Goal:** the operator can arm the tenant from the wizard, and the control only unlocks after step-2
verification returns the account.

**Changes** (anchors):
- `dashboard/components/OnboardForm.tsx` (2-step form, `:81-84`; step-2 result carries `brokerAccountId`
  at `:8`) — add **step 3 "Enable strategy"**: disabled until the in-session step-2 result has a non-blank
  `brokerAccountId`; on click posts `enabled: true` for the (tenant, strategy).
- `dashboard/lib/adminOnboarding.ts` (`:32-66` create, `:92-138` credential) — add an `enableStrategy()`
  server action that POSTs the config-write to the api-gateway strategy-config route (operator-scoped,
  `X-Operator-Id`), mirroring the existing operator POST helpers.
- `dashboard/components/StrategySwitch.tsx:11-43` (the standalone on/off toggle, `writeEnabled` gate) —
  extend so the switch is also disabled when no verified account exists for the tenant (source: the
  admin-read `account_masked` already surfaced by `AdminTenantsController`), with a "verify broker keys
  first" affordance. Keeps the settings-page toggle consistent with the wizard.

**Tests:** dashboard verify = `tsc --noEmit` + `next build` (no test harness). Assert step-3 disabled
with no `brokerAccountId`, enabled once present; StrategySwitch disabled when `account_masked` absent.

**Verify / success criteria:** in the wizard, "Enable" is inert until keys verify, then flips `enabled`
via the existing write path. UI-only; the write still passes through A2's guard once shipped.

---

## Phase A2 — Bypass-proof "arm requires verified account" guard (`exec` + `api-gateway`)

**Goal:** reject `enabled: false→true` at the backend when no verified broker account exists — so it can't
be bypassed by calling the API directly.

**Changes** (anchors):
- **exec read endpoint** — new `GET /internal/broker-credentials/{tenant}/account?provider=alpaca` in a
  controller sibling to the write path, gated identically (`broker.creds.source=db` +
  `ExecAdminTokenFilter`). Returns `{verified:true, account:"<expected_account_id>"}` when a row exists,
  else `{verified:false}` / 404. Reads only the non-secret `expected_account_id` (never ciphertext) —
  mirror `BrokerCredentialStatusReader.java:35-51`.
- **api-gateway guard** — `StrategyConfigController.java:74-99`: when the incoming config sets
  `enabled=true` AND the stored/prior value was not-true, call the exec read endpoint (existing exec
  client + `EXEC_ADMIN_SHARED_TOKEN`, routed by the config's `broker_target`) BEFORE starting the update
  workflow; if `verified=false` → reject **422** (new `REJECTED_UNVERIFIED_ACCOUNT` outcome, mapped like
  the existing `REJECTED_DANGEROUS`→403 at `:139-159`). No verified account ⇒ no arm.
- Scope note: only the `false/absent → true` transition is gated; disabling (`true→false`) and all other
  SAFE edits are unaffected.

**Tests (TDD):**
- exec: read endpoint returns `verified:true`+account for an existing row; `verified:false` when absent;
  401 without the admin token; dark (404) when `broker.creds.source!=db`.
- api-gateway: `enabled=true` with a verified account → workflow starts (2xx); with none → 422, workflow
  NOT started; `enabled=false` and non-enable edits → unaffected; the exec-call routes by `broker_target`.

**Verify / success criteria:** `mvn -pl services/exec,services/api-gateway -am spotless:apply` + module
tests. Behavioral assertion: a direct API call setting `enabled=true` for a tenant with no
`broker_credentials` row is rejected 422 and no `StrategyConfigUpdateWorkflow` runs. Spotless clean on both.

---

## Phase B1 — Copytrade fan-out registry endpoint (`api-gateway`)

**Goal:** expose the set of enabled copytrade tenants for the sidecar to consume.

**Changes** (anchors):
- New `GET /internal/copytrade-fanout-targets` on api-gateway (service-token auth — NOT operator-scoped;
  the sidecar is a service, not an operator). api-gateway already reads the orchestrator DB
  (`API_GATEWAY_DB_URL=.../orchestrator`), which holds `strategy_config`.
- Query: `SELECT tenant_id, strategy_id FROM strategy_config WHERE strategy_id = :copytrade AND
  (config->>'enabled') IS DISTINCT FROM 'false'` (enabled true **or absent** — matches the schema default
  `true` at `contract/schemas/strategy-config.json:344-348` and the runtime gate
  `CopytradeSignalWorkflowImpl:321`). Returns `[{tenant_id, strategy_id}, ...]` as a plain JSON list.
  Strategy id is a parameter/config (default `copytrade-v1`), not hardcoded.

**Tests (TDD):** returns enabled + `enabled`-absent tenants; excludes `enabled:false`; excludes non-
copytrade strategies; auth-gated (401 without service token). Use an in-memory/mock DSL like the existing
api-gateway controller tests.

**Verify / success criteria:** `mvn -pl services/api-gateway -am spotless:apply` + tests. Endpoint returns
exactly the enabled copytrade `(tenant, strategy)` set. Additive/dark (no consumer yet). Spotless clean.

---

## Phase B2 — Sidecar registry-driven fan-out with refresh (`signal-source-discord`)

**Goal:** the sidecar derives its fan-out targets from B1's endpoint on an interval, replacing the env list.

**Changes** (anchors):
- `main.py:95-97,144-152` + `watcher.py:97-100,154-174` — introduce a `SIGNAL_FANOUT_SOURCE` env
  (`env` | `registry`, default **`env`** so it's dark). In `registry` mode: add an httpx client that polls
  `GET /internal/copytrade-fanout-targets` (base URL + service token via env) every
  `SIGNAL_FANOUT_REFRESH_SECS` (default e.g. 60), and atomically swaps `self._targets` =
  **union(primary, registry list)** deduped. In `env` mode: today's behavior, byte-identical.
- Thread-safety: the refresh must not tear the emit loop's iteration (`watcher.py:164`) — snapshot the
  target list per signal, or guard with a lock. Fail-safe: a failed/empty poll **keeps the last good list**
  (never drops to zero → never silently stops delivering); log + alert on repeated failures.
- The sidecar gains its FIRST backend HTTP dependency (today it's Temporal-only, `emitter.py:126-144`) —
  keep it isolated (a small client module) and non-fatal to signal emission.

**Tests (pytest):** `env` mode unchanged; `registry` mode builds targets from a mocked endpoint; refresh
picks up an added enabled tenant without restart; a failing poll retains the last good set (no drop-to-
empty); union-with-primary + dedupe correct.

**Verify / success criteria:** sidecar test suite green; with `SIGNAL_FANOUT_SOURCE=env` behavior is
identical to today. In `registry` mode against a stub, a newly-`enabled` tenant appears in the fan-out
within one refresh interval with no restart.

---

## Phase B3 — Cutover (operator; no code)

- Confirm sidecar→api-gateway:8082 reachable (NetworkPolicy). Set `SIGNAL_FANOUT_SOURCE=registry` +
  the endpoint URL/token on `signal-source-discord`; verify the fan-out matches the enabled copytrade
  tenants; then **remove** `SIGNAL_EMIT_ADDITIONAL_TARGETS`. Live-only overrides preserved by
  `RESTART_ONLY`. (Note: `WATCHLIST_MIRROR_ADDITIONAL_TARGETS` — `main.py:59-71` — is a SEPARATE fan-out
  and is out of scope; it keeps its env until a parallel follow-up.)

---

## Ship order & gating

```
A1 (UI enable-step)          ── self-contained dashboard; immediate UX win
A2 (backend arm-guard)       ── exec read + api-gateway 422; the bypass-proof enforcement
   → A1+A2 together = "cannot arm what you haven't verified" (ship A2 before trusting it for live)
B1 (fan-out endpoint)        ── additive/dark; no consumer
   └─> B2 (sidecar registry mode, flag default=env)   ── dark until flipped
          └─> B3 (operator cutover: flip flag, retire env)
```

Rules: each phase = one single-concern PR, TDD-first, `spotless:apply` on every touched Java module,
operator merge gate (trading-adjacent). A and B are independent and can proceed in parallel; within each,
respect the arrows. Nothing changes live behavior until B3 (B stays `env`-default) and until an operator
sets the flags — repo defaults keep both features dark.

## What this plan does NOT do

- Per-broker-target routing of the api-gateway→exec hop (needed for **live** arming/onboarding) — carried
  as a P0 note, belongs with the live cutover.
- Multi-signal-source tenant scoping (registry fan-out assumes one channel → all enabled copytrade tenants).
- The watchlist mirror fan-out (`WATCHLIST_MIRROR_ADDITIONAL_TARGETS`) — separate, parallel follow-up.
- Any change to the live-promotion/activation ceremony (that gate is orthogonal to `enabled`).
