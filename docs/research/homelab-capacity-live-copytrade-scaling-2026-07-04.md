# Homelab Capacity & the Path to 5–10 Live Copytrades

**Researched:** 2026-07-04
**Question:** How many tenants and strategies can the homelab k3s cluster run, and specifically — what does it take to run **5–10 live copytrade tenants**?
**Verdict (short):** Capacity is a non-issue — the node is 8% CPU / 64% mem with 3 tenants, and tenants/strategies are *config rows, not pods* (Fork B). The gate for 5–10 **live** copytrades is **not hardware**; it's the multi-account live-credential epic (`PLAN-2026-06-28-operator-account-onboarding`), which is ~90% shipped but **dark**. With per-tenant Alpaca accounts, one `exec-alpaca-live` pod handles 5–10 live tenants comfortably (each account has its own 200 req/min limit and its own fill socket). The blockers are **Phases B/E/F/G + operator P0 (KEK/TLS) + flag flips**, not compute.

---

## 1. Live cluster snapshot (measured 2026-07-04)

Single-node k3s, host `homelab` (`ssh ridopark@192.168.10.123`):

| Resource | Capacity | Requests (reserved) | Limits | Live usage |
|---|---|---|---|---|
| CPU | **8 vCPU** | 2170m (27%) | 12500m (156% overcommit) | **711m (8%)** |
| Memory | **16 GiB** | 5276Mi (33%) | 12678Mi (80%) | **10.1 GiB (64%)** |
| Pods | 110 max | — | — | 37 running (12 in `copytrade`) |

**Current tenants/strategies (live, from `strategy_config` in the `orchestrator` DB — `STRATEGY_CONFIG_SOURCE=db`):**

| tenant_id | strategy_id | broker_target | enabled |
|---|---|---|---|
| acme | copytrade-v1 | alpaca-paper | true |
| prod_real | copytrade-v1 | alpaca-live | (armed) |
| prod_real | watchlist-trigger-v1 | alpaca-live | true |
| staging_paper | copytrade-v1 | alpaca-paper | true |
| staging_paper | watchlist-trigger-v1 | alpaca-paper | true |

(`dev` exists in the `tenants-config` ConfigMap seed but not in the live DB.) So **3 tenants × 2 strategies × 2 broker targets**, running at 8% CPU.

**DB connections:** 55 / 100. Pools are **fixed-size per pod** (`orchestrator_runtime=10`, `exec_*=10` each, `bff_readonly` 1–4) — they do **not** grow with tenant count.

---

## 2. Why capacity is not the constraint (Fork B)

The system is "Fork B: shared worker + per-tenant resolver." A tenant or strategy is a **config row, not a pod**. Adding one creates only:

- an in-memory `TenantConfig`/`StrategyConfig` (~5–10 KB),
- one long-lived Temporal kill-switch workflow,
- one 5-min reconciliation schedule (`ReconciliationScheduleBootstrapper`).

It does **not** add pods, and the shared infra is all singletons (`orchestrator`, `market-data`, `api-gateway`, one `exec-*` per broker target). Confirmed live:

- `exec-alpaca-live` runs a **single-socket** fill listener today (`AlpacaTradeUpdatesStream` — `fill-listener started (single-socket)`), shared by all live tenants on that broker.
- exec resolves per-tenant identity through a **registry** keyed `BrokerKey[tenantId=…, provider=alpaca]` (seen at boot: `broker account identity verified … registry key BrokerKey[tenantId=prod_real, provider=alpaca]`).

**Fixed infra cost:** ~950m CPU, ~2.5–3.5 GiB RAM. **Marginal cost per tenant/strategy:** effectively free (KB of RAM, a handful of Temporal tasks per 5 min).

---

## 3. Ceilings that *do* exist, in priority order

| # | Constraint | Measured now | What it caps |
|---|---|---|---|
| 1 | **Watchlist Chromium memory** — `signal-source-discord` runs one Chromium, N tabs | **812 MB / 1 GiB limit** with 2 watchlist tabs | ~3–4 **watchlist-trigger** tenants before OOM. **Irrelevant to copytrade** (copytrade doesn't render Chromium). Node has headroom to bump the 1Gi limit. |
| 2 | **Single-pod fill listener** per broker target — not leader-elected, `replicas: 1` hard invariant (`AlpacaTradeUpdatesStream.java:51`) | 1 pod each | No HA and no >1 pod; but **fine at 5–10 live tenants** — one pod opens 5–10 sockets. Only blocks *multi-pod* scale-out (deferred leader-elected router). |
| 3 | **Alpaca 200 req/min** | recon = 5-min cadence, low rate | Shared **only in the single-account model**. With **per-tenant accounts, each account gets its own 200/min** → not a shared ceiling. |
| 4 | Postgres connections | 55/100, fixed per pod | Soft; fine to dozens of instances. |
| 5 | Node memory | 64% used, limits 80% | Fine **as long as no new pods** — new broker *targets* add pods, new tenants/strategies do not. |

**Bottom line on capacity:** paper scales to **~20–30 (tenant×strategy) instances** on this node before anything soft (orchestrator recon dispatch, Postgres) even warms up. Live is gated by architecture, not compute (§4).

---

## 4. The 5–10 live copytrade goal

### 4a. Which model? Per-tenant accounts, not one shared account

Two ways to run multiple live copytrades:

- **Shared account** (all live tenants → the one real-money account `847309116`): works with today's `env`-mode single-socket single-account setup, **but is unsafe for independent copytrades** — multiple tenants double-size the same account, positions on the same symbol collide, and fills can't be attributed per tenant (orphan ambiguity). Not recommended.
- **Per-tenant accounts** (each live tenant its own Alpaca account + keys): the correct model for 5–10 independent copytrades. Each gets its own `expected_account_id` pin, its own 200 req/min budget, its own fill socket, and clean per-account reconciliation. **This is what the operator-onboarding epic builds.**

### 4b. Is per-tenant live supported today? Architecture yes, **flags dark**

~90% is shipped but deliberately fail-closed off:

**Shipped:**
- `BrokerCredentialSource` seam with three impls selected by `broker.creds.source` (`env` default | `file` | `db`) — `services/exec/.../broker/BrokerCredentialSource.java`.
- `DbBrokerCredentialSource` — envelope-encrypted (`AES-GCM DEK under KEK`) `broker_credentials` table, AAD-bound to `tenant‖provider‖expected_account_id‖kek_version`.
- `BrokerCredentialWriter.validateOnEntry` — probes `/v2/account`, captures immutable `expected_account_id`.
- Per-call `AccountMismatchError` cross-check (`AlpacaBrokerClientRegistry.java:128-150`).
- **Per-tenant multi-socket fill listener** — one WS socket per live tenant, tenant-scoped dedup (`AlpacaTradeUpdatesStream.java:108-187`), behind `exec.fill-listener.per-tenant.enabled` (default **false**).
- Per-tenant reconciliation *and* `FillPoller` already resolve broker by `tenantId`.

**Blocking (all fail-closed by design):**
- `broker.creds.db.live-enabled = false` → `-live` pod **refuses** DB creds (`DbBrokerCredentialSource.java:106-112`).
- `exec.fill-listener.per-tenant.enabled = false` → real-time multi-account fills dark (falls back to 30s REST poller).
- `multitenant.broker-accounts.enabled = false` (repo default).
- **Phase B** — one recon read still hardcoded to `ACCOUNT_LEVEL` (per-tenant threading pending).
- **Phase E** — the "coupled lift": remove the `-live` DB-creds refusal, require non-blank `expected_account_id`, add a `(provider, expected_account_id)` **uniqueness constraint** (prevents two tenants double-binding one account — **not enforced today**).
- **Phase F** — one-click activation endpoint + required-config gate (daily-loss, capital-source) + canary + `LivePromotion` emit.

The `replicas: 1` single-pod invariant is **fine** at 5–10 tenants — leader-election is only needed for HA / >1 pod, which is out of scope at this scale.

### 4c. Go-live checklist for 5–10 live copytrades

Ordered, per `PLAN-2026-06-28-operator-account-onboarding.md` (ship order A → B/C → H → G → F → E):

1. **Operator P0** — KEK loaded on the live cluster, TLS/NetworkPolicy in place, manual `exec-alpaca-live` roll. *(precondition)*
2. **Ship Phase B** — thread `tenantId` into the last tenant-blind recon read. *(small)*
3. **Ship Phase G** — per-tenant WS sockets; set `exec.fill-listener.per-tenant.enabled=true`.
4. **Ship Phase F** — activation endpoint + required-config gate + canary.
5. **Ship Phase E (LAST)** — remove `-live` DB-creds refusal, require non-blank `expected_account_id`, add the `(provider, expected_account_id)` uniqueness constraint.
6. **Flag flips (Phase H):** `broker.creds.source=db`, `broker.creds.db.live-enabled=true`, `multitenant.broker-accounts.enabled=true`.
7. **Keep `exec-alpaca-live` at `replicas: 1`** (hard invariant).
8. **Per tenant:** paste Alpaca keys via the credential writer (auto-probes + pins `expected_account_id`), declare `broker_account_id` in the strategy config, arm via the activation gate.

**Capacity cost of all this at 5–10 live tenants:** ~5–10 extra WS sockets + ~5–10 recon schedules on the *existing* orchestrator/exec pods. Negligible against 8 vCPU / 16 GiB at 8% CPU. **No new pods, no node upsize.**

---

## 5. Summary table

| Scenario | Viable on homelab today? | Real gate |
|---|---|---|
| 5–10 **paper** copytrades | ✅ Yes, now | none (config rows) |
| 5–10 **live** copytrades, **per-tenant accounts** | ⚠️ Architecture ready, **flags dark** | Phases B/E/F/G + P0 (KEK/TLS) + flag flips — **not compute** |
| 5–10 **live** copytrades, **one shared account** | ⚠️ Runs today but **unsafe** | double-sizing + orphan attribution; not recommended |
| 3–4+ **watchlist** tenants | ⚠️ | Chromium 1Gi limit (bump it) — separate from copytrade |
| >1 exec pod / HA fills | ❌ | deferred leader-elected fill router (out of scope) |

**Recommendation for the 5–10 live copytrade goal:** the homelab is nowhere near a hardware wall — treat this as a **feature-completion + operator-cutover** task (finish the operator-onboarding epic's Phases B/E/F/G, load the KEK, flip the flags), not a scaling/hardware task. Keep the single `exec-alpaca-live` pod at `replicas: 1`; it carries 5–10 per-tenant fill sockets without issue.

---

## Sources
- Live cluster: `kubectl top/describe node homelab`, `strategy_config` (orchestrator DB), pod env, exec-alpaca-live boot logs — measured 2026-07-04.
- Code: `services/exec/.../broker/BrokerCredentialSource.java`, `.../alpaca/DbBrokerCredentialSource.java` (`:106-112`, `:152-159`), `.../alpaca/AlpacaTradeUpdatesStream.java` (`:51`, `:108-187`), `.../broker/AlpacaBrokerClientRegistry.java:128-150`, `ReconciliationScheduleBootstrapper.java`, `contract/.../identity/WorkflowIds.java`.
- Plans: `docs/plans/PLAN-2026-06-28-operator-account-onboarding.md`, `docs/plans/PLAN-2026-07-03-self-service-copytrade-onboarding.md`, `docs/plans/PLAN-multi-tenant-broker-credentials.md`, `docs/ops/fill-listener.md`.
</content>
</invoke>
