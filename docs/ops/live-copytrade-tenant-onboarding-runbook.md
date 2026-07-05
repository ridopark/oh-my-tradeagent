# Runbook — Onboarding a live copytrade tenant (P0 cutover)

**Audience:** operator (homelab k3s). **Risk:** REAL MONEY. **Access:** `ssh ridopark@192.168.10.123`.
**Related:** `docs/plans/PLAN-2026-07-04-live-copytrade-fleet-enablement.md`,
`docs/research/homelab-capacity-live-copytrade-scaling-2026-07-04.md`, `docs/ops/fill-listener.md`.

This is the operator sequence to run **multiple live copytrade tenants, each with its own Alpaca
account**, on the single `exec-alpaca-live` pod (shared-account path, Fork B). All the code shipped;
this runbook is flag-flips + data-writes, done off-hours.

> **Golden rules**
> 1. **`exec-alpaca-live` MUST stay `replicas: 1`** (the fill listener is not leader-elected; N pods →
>    N× duplicate fills). Never scale it.
> 2. **Do Part A off-hours (market closed, no open live positions).** Flipping `BROKER_CREDS_SOURCE=db`
>    makes every live tenant — *including `prod_real`* — resolve credentials from the DB. If a tenant has
>    no `broker_credentials` row when it's needed, its orders fail **closed** (safe, but it won't trade).
> 3. **One account ↔ one tenant.** The `V6` unique index + the writer's pre-persist check (R-6.5, live on
>    both exec DBs since 2026-07-05) reject binding an account already held by another tenant. This is a
>    guard, not a substitute for care.
> 4. **Canary via the broker-side 403 block**, never "live + full-size + unblocked" in one action.

---

## Current state (verified 2026-07-05 — re-verify before running)

Already in place (do NOT redo):
- **KEK loaded** — secret `broker-kek` present in `copytrade` ns (crypto precondition met).
- **Paper runs the full DB-creds path** — `exec-alpaca-paper` has `BROKER_CREDS_SOURCE=db`; `staging_paper`
  has a `broker_credentials` row (`PA3FKGPFYPLH`). Paper is the working reference for this whole flow.
- **Operator endpoints live on api-gateway** — `OPERATOR_CREDENTIAL_WRITE_ENABLED=true` (credential write)
  and `OPERATOR_ACTIVATION_ENABLED=true` (activation).
- **R-6.5 `V6` index live on BOTH exec DBs** (`broker_credentials_provider_account_uk`).
- **`exec-alpaca-live`**: `BROKER_IMPL=alpaca-live`, `EXEC_FILL_LISTENER_ENABLED=true`,
  `EXEC_FILL_LISTENER_POLL_ENABLED=true`, account pin `EXPECTED_ALPACA_ACCOUNT_ID=847309116`.

Still DARK on the **live** side (this runbook flips them):
- `exec-alpaca-live`: `BROKER_CREDS_SOURCE` unset → defaults `env` (single-account, ignores per-tenant rows).
- `exec-alpaca-live`: `BROKER_CREDS_DB_LIVE_ENABLED` unset → `false` (a `-live` pod refuses DB creds).
- `exec-alpaca-live`: `EXEC_FILL_LISTENER_PER_TENANT_ENABLED` unset → `false` (one pod-wide socket).
- `orchestrator`: `MULTITENANT_BROKER_ACCOUNTS_ENABLED` unset → `false` (shared-account validator off).

Env-var → Spring property (relaxed binding): `BROKER_CREDS_SOURCE`→`broker.creds.source`,
`BROKER_CREDS_DB_LIVE_ENABLED`→`broker.creds.db.live-enabled`,
`EXEC_FILL_LISTENER_PER_TENANT_ENABLED`→`exec.fill-listener.per-tenant-enabled`,
`MULTITENANT_BROKER_ACCOUNTS_ENABLED`→`multitenant.broker-accounts.enabled`. Confirm against the live
manifests before flipping.

---

## Preconditions (one-time, before the FIRST live tenant)

1. **Market closed / no open live positions** — `prod_real` will briefly be unable to trade during Part A.
2. **KEK present** — `kubectl get secret broker-kek -n copytrade` (already ✅).
3. **TLS + NetworkPolicy on the secret hop** — confirm `api-gateway → exec /internal/broker-credentials`
   is TLS and a `NetworkPolicy` restricts that path to the api-gateway pod only. The api key/secret travels
   only on that direct HTTP body; it must never be reachable elsewhere. **Verify before enabling DB creds.**
4. **`V6` index present on `exec_alpaca_live`** — verify (see Verification §). Already ✅.
5. **No pre-existing cross-tenant duplicate accounts** in `broker_credentials` (else a write is rejected;
   the table is currently near-empty). Verify (see Verification §).
6. **`prod_real`'s API key + secret in hand** — Part A migrates `prod_real` from env creds to a DB row, so
   you need its live keys to write that row.

---

## Part A — one-time: move the live pod to per-tenant DB creds (incl. `prod_real`)

`BROKER_CREDS_SOURCE` is a **single pod-wide selector** — it's `env` (one account) or `db` (per-tenant
rows), not both. So enabling additional live tenants *requires* migrating `prod_real` onto a DB row too.
The credential-write endpoint on the live exec pod only exists once `source=db`, so the order is:
**flip → roll → write `prod_real`'s row immediately → verify** (off-hours closes the trade-less gap).

1. **Flip the live-path flags on `exec-alpaca-live`:**
   ```
   kubectl set env deploy/exec-alpaca-live -n copytrade \
     BROKER_CREDS_SOURCE=db \
     BROKER_CREDS_DB_LIVE_ENABLED=true \
     EXEC_FILL_LISTENER_PER_TENANT_ENABLED=true \
     EXEC_ADMIN_SHARED_TOKEN- ... (from secret exec-admin-credentials; mirror how paper injects it)
   ```
   `EXEC_ADMIN_SHARED_TOKEN` must be injected from the `exec-admin-credentials` secret (as on paper) — the
   write endpoint's `ExecAdminTokenFilter` fail-fasts on the insecure default under the `prod` profile.
   (`kubectl set env` triggers a rollout; `exec-alpaca-live` is out of the deploy matrix, so this manual
   flip is expected.)
2. **Wait for rollout + verify boot:** `kubectl -n copytrade rollout status deploy/exec-alpaca-live`.
   Confirm the log shows Flyway at v6, KEK loaded, and `ExecApplication` started. With `source=db` and an
   empty table, the per-tenant fill listener will log **0 sockets** and `prod_real` order resolution will
   fail closed until step 3 — this is why it's off-hours.
3. **Write `prod_real`'s live credential row IMMEDIATELY** via the api-gateway credential-write path
   (dashboard onboard form, or a direct authenticated `POST` to the `BrokerCredentialController` write
   endpoint — `OPERATOR_CREDENTIAL_WRITE_ENABLED=true`). `validateOnEntry` probes `/v2/account` and pins
   the authenticated account as `expected_account_id`. It **MUST** come back `847309116` — reject otherwise.
4. **Roll `exec-alpaca-live` again** (or wait for its supervisor) so `prod_real`'s per-tenant `trade_updates`
   socket opens. Verify the log now shows `fill-listener started (per-tenant) ... sockets_started=1`.
5. **Verify `prod_real` resolves + trades** — a canary order or the next real signal fills on its own socket
   and reconciles cleanly. `prod_real` is now on the DB path, behavior-equal to before.
6. **(Only when a 2nd live tenant will share `alpaca-live`) enable the shared-account validator** on the
   orchestrator, then roll it:
   ```
   kubectl set env deploy/orchestrator -n copytrade MULTITENANT_BROKER_ACCOUNTS_ENABLED=true
   kubectl -n copytrade rollout status deploy/orchestrator
   ```
   With it on, the validator accepts many live tenants iff each declares a distinct non-blank
   `broker_account_id` (and a single tenant's strategies share one account).

Part A is done once; every subsequent live tenant is just Part B.

---

## Part B — onboard each additional live tenant (repeatable, data-only)

1. **Create the tenant's Alpaca account** and generate its **own** API key id + secret. (One real account
   per tenant — the R-6.5 index enforces it.)
2. **Insert the tenant's `strategy_config` row(s)** (orchestrator DB, the live config source
   `STRATEGY_CONFIG_SOURCE=db`) with, at minimum:
   - `broker_target: alpaca-live`
   - `broker_account_id: "<that account>"` (distinct, non-blank)
   - `capital_source: account_cash` (NOT static $100k), `daily_loss_threshold > 0`, a notional cap set,
     kill switch armable — these are re-checked at activation (step 5) and fail closed if missing.
3. **Write the tenant's broker credential** via the api-gateway write path. `validateOnEntry` probes the
   account and pins `expected_account_id`; the R-6.5 index + writer check **reject** a key that authenticates
   an account already bound to another tenant (clean 409). Confirm the read-back account matches.
4. **Pick up the new tenant** (restart-free onboarding = deferred epic Phase A):
   - Roll `orchestrator` so it bootstraps the tenant's recon schedule + kill switch.
   - Roll `exec-alpaca-live` so its per-tenant fill socket for the new tenant opens
     (`sockets_started` increments). Batch multiple onboards, then one roll of each.
5. **Activate** the tenant (fail-closed required-config gate + `LivePromotionApproved` emit):
   `POST /admin/tenants/{tenant}/strategies/{strategy}/activate-live` (api-gateway,
   `OPERATOR_ACTIVATION_ENABLED=true`). A non-compliant config is refused server-side with the reason
   (`REJECTED_CONFIG` / `REJECTED_CAPITAL_SOURCE` / `REJECTED_KILLSWITCH` / `REJECTED_ACCOUNT`).
6. **Canary, then lift the block** — keep the new account **403-blocked at Alpaca**, watch the first clean
   fills reconcile on its own socket, *then* lift the broker-side block. Never full-size + unblocked in one
   step.

---

## Rollback / deactivate

- **Deactivate one tenant:** `POST /admin/tenants/{tenant}/strategies/{strategy}/deactivate-live` — voids
  the `LivePromotionApproved` row (next live BTO sees ABSENT/STALE → fails closed, no new entries) **and**
  trips the kill switch (halts in-flight/open management). Open positions are still managed to exit.
- **Full revert of the live path:** set `BROKER_CREDS_SOURCE=env` on `exec-alpaca-live` and roll — reverts
  to the single-account (`prod_real` only) env-creds behavior. (Leaves the DB rows in place, inert.)
- **Emergency:** trip the account/strategy kill switch, or patch the tenant's `strategy_config` to disabled.

---

## Verification commands

```
# V6 index present on the live exec DB
kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d exec_alpaca_live -tAc \
  "select indexdef from pg_indexes where indexname='broker_credentials_provider_account_uk';"

# No cross-tenant duplicate accounts (must return no rows)
kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d exec_alpaca_live -tAc \
  "select expected_account_id, count(distinct tenant_id) from broker_credentials \
   where expected_account_id ~ '[^[:space:]]' group by 1 having count(distinct tenant_id) > 1;"

# Per-tenant fill sockets opened (after Part A / a new tenant)
kubectl logs -n copytrade deploy/exec-alpaca-live | grep -E 'fill-listener started|sockets_started|account identity verified'

# Each live tenant's declared account (orchestrator config)
kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d orchestrator -tAc \
  "select tenant_id, strategy_id, config->>'broker_account_id' from strategy_config \
   where config->>'broker_target'='alpaca-live' order by 1,2;"
```

---

## Notes / deferred

- **Restart-free onboarding (epic Phase A) is deferred** — that's why steps B-4 roll the orchestrator +
  exec. For a batch of tenants, insert all rows, then one roll of each. Ship Phase A if onboarding cadence
  makes the rolls painful.
- **Admin UI (epic Phase I) is deferred** — onboarding uses the existing credential-write + activation
  endpoints (and the existing onboard form) directly.
- **`>1` exec-alpaca-live replica / HA fills** requires the deferred leader-elected fill router
  (PLAN-multi-tenant-broker-credentials P5). Until then, `replicas: 1` is load-bearing.
- **Paper accounts are also unique-per-tenant** (the R-6.5 index is per-broker-target). A future
  shared-paper-demo model must use a distinct approach.
</content>
