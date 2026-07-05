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

## Current state

> **✅ Part A executed + verified LIVE on homelab 2026-07-05.** `prod_real` is armed on the per-tenant
> DB-creds path: `exec-alpaca-live` runs `BROKER_CREDS_SOURCE=db` + `BROKER_CREDS_DB_LIVE_ENABLED=true`,
> the `broker-kek` volume is mounted, and `prod_real`'s `broker_credentials` row (`847309116`, v1) is
> written and boot-verified (identity + fill-listener, zero errors; only that row in the live DB; paper
> untouched; 0 order side-effects). **The steps below are the AS-RUN record.** Onboarding a *new* live
> tenant now starts at **Part B**. Re-verify each precondition before any further change.

Already in place (do NOT redo):
- **KEK** — secret `broker-kek` present in `copytrade` ns **AND** mounted at `/etc/broker-kek` on BOTH
  `exec-alpaca-paper` and (as of 2026-07-05) `exec-alpaca-live`. ⚠️ The secret existing is NOT enough — the
  DB-crypto bean reads the *file*, so the target exec pod needs the **volume + mount** (see Part A step 0b).
- **Paper runs the full DB-creds path** — `exec-alpaca-paper` has `BROKER_CREDS_SOURCE=db`; `staging_paper`
  has a `broker_credentials` row (`PA3FKGPFYPLH`). Paper is the working reference for this whole flow.
- **Operator endpoints live on api-gateway** — `OPERATOR_CREDENTIAL_WRITE_ENABLED=true` (credential write)
  and `OPERATOR_ACTIVATION_ENABLED=true` (activation).
- **api-gateway per-target routing (PR #548, merged 2026-07-05)** — credential writes route to the exec pod
  by the tenant's `strategy_config` `broker_target` (`alpaca-live`→`exec-alpaca-live`, `alpaca-paper`→
  `exec-alpaca-paper`), fail-closed (422) if unresolvable. Requires env `EXEC_ALPACA_LIVE_BASE_URL` +
  `EXEC_ALPACA_PAPER_BASE_URL` on api-gateway (set 2026-07-05 — see Part A step 0a). **api-gateway listens on
  `8082`**, not 8080.
- **R-6.5 `V6` index live on BOTH exec DBs** (`broker_credentials_provider_account_uk`).
- **`exec-alpaca-live`**: `BROKER_IMPL=alpaca-live`, `EXEC_FILL_LISTENER_ENABLED=true`,
  `EXEC_FILL_LISTENER_POLL_ENABLED=true`, `EXEC_BOOTSTRAP_TENANT_ID=prod_real` (single-account resolution),
  `EXEC_ADMIN_SHARED_TOKEN` (from secret `exec-admin-credentials`), account pin `847309116`.

Now SET on the **live** side (Part A, 2026-07-05):
- `exec-alpaca-live`: `BROKER_CREDS_SOURCE=db` (per-tenant rows), `BROKER_CREDS_DB_LIVE_ENABLED=true`
  (a `-live` pod now accepts DB creds), `broker-kek` volume mounted, `EXEC_ADMIN_SHARED_TOKEN` injected.

Still DARK — **only needed for a 2ND live tenant** (prod_real resolves via `EXEC_BOOTSTRAP_TENANT_ID`, single
pod-wide socket, so these stayed off for the minimal arming):
- `exec-alpaca-live`: `EXEC_FILL_LISTENER_PER_TENANT_ENABLED` unset → `false` (one pod-wide socket).
- `orchestrator`: `MULTITENANT_BROKER_ACCOUNTS_ENABLED` unset → `false` (shared-account validator off).

> ⚠️ **These live-pod settings are LIVE-ONLY overrides, NOT in any repo manifest** (`broker-kek` volume,
> `BROKER_CREDS_SOURCE=db`, `BROKER_CREDS_DB_LIVE_ENABLED`, `EXEC_ADMIN_SHARED_TOKEN`; and on api-gateway the
> two `EXEC_ALPACA_*_BASE_URL` vars were `set env`, not `apply`'d). `exec-alpaca-live` is excluded from the CI
> deploy matrix, so CI won't revert them — but a **manual `kubectl apply` of an exec-live/api-gateway
> manifest would wipe them and break DB-creds resolution**. Same footgun class as the alpaca-live env
> override. Re-apply by hand if the pod spec is ever reset.

Env-var → Spring property (relaxed binding): `BROKER_CREDS_SOURCE`→`broker.creds.source`,
`BROKER_CREDS_DB_LIVE_ENABLED`→`broker.creds.db.live-enabled`,
`EXEC_FILL_LISTENER_PER_TENANT_ENABLED`→`exec.fill-listener.per-tenant-enabled`,
`MULTITENANT_BROKER_ACCOUNTS_ENABLED`→`multitenant.broker-accounts.enabled`. Confirm against the live
manifests before flipping.

---

## Preconditions (one-time, before the FIRST live tenant)

1. **Market closed / no open live positions** — `prod_real` will briefly be unable to trade during Part A.
2. **KEK present AND mounted on the target pod** — `kubectl get secret broker-kek -n copytrade` (secret ✅),
   **and** the `broker-kek` volume mounted at `/etc/broker-kek` on `exec-alpaca-live` (Part A step 0b —
   done 2026-07-05; the secret alone is not enough, the crypto bean reads the file).
3. **api-gateway per-target routing in place** — `EXEC_ALPACA_{LIVE,PAPER}_BASE_URL` set on api-gateway
   (Part A step 0a); without it a live-tenant write fails closed (422). Done 2026-07-05.
4. **TLS + NetworkPolicy on the secret hop** — confirm `api-gateway → exec /internal/broker-credentials`
   is restricted by `NetworkPolicy` to the api-gateway pod only (`exec-alpaca-live-allow-api-gateway-internal`
   ✅). The api key/secret travels only on that direct HTTP body; it must never be reachable elsewhere.
5. **`V6` index present on `exec_alpaca_live`** — verify (see Verification §). Already ✅.
6. **No pre-existing cross-tenant duplicate accounts** in `broker_credentials` (else a write is rejected;
   the table is currently near-empty). Verify (see Verification §).
7. **`prod_real`'s API key + secret in hand** — Part A migrates `prod_real` from env creds to a DB row, so
   you need its live keys to write that row. (Reusable from secret `alpaca-credentials-live`.)

---

## Part A — one-time: move the live pod to per-tenant DB creds (incl. `prod_real`)

> **AS-RUN 2026-07-05** — this is the exact sequence executed. `BROKER_CREDS_SOURCE` is a **single pod-wide
> selector** (`env` = one account, `db` = per-tenant rows, never both), so arming any live tenant *requires*
> migrating `prod_real` onto a DB row too. Two ordering facts that bit us: **(i)** the exec write endpoint
> `POST /internal/broker-credentials` is `@ConditionalOnProperty(broker.creds.source=db)` — it **404s until
> `source=db`**, so "write-first" is impossible; you must flip source FIRST. **(ii)** the DB-crypto bean
> reads the KEK *file*, so the live pod needs the `broker-kek` **volume mounted** or the write 502s. Net
> order: **route → mount KEK → flip → roll → write `prod_real` immediately → clean roll → verify**. Off-hours
> closes the brief trade-less gap (unresolved creds fail closed; safe).

**Step 0a — api-gateway per-target routing (once, PR #548).** deploy.yml's `RESTART_ONLY` list SKIPS
`kubectl apply` for api-gateway (it carries operator overrides), so the merged manifest env does NOT land on
its own. Add the two target URLs **additively** (a full `apply -f 54-api-gateway.yaml` would WIPE the 5
operator overrides — `STRATEGY_CONFIG_WRITE_ENABLED`, `OPERATOR_CREDENTIAL_WRITE_ENABLED`,
`OPERATOR_ACTIVATION_ENABLED`, `OPERATOR_ALLOWLIST`, `EXEC_ADMIN_SHARED_TOKEN`):
```
kubectl set env deploy/api-gateway -n copytrade \
  EXEC_ALPACA_LIVE_BASE_URL=http://exec-alpaca-live:8080 \
  EXEC_ALPACA_PAPER_BASE_URL=http://exec-alpaca-paper:8080
kubectl -n copytrade rollout status deploy/api-gateway
```
Verify both vars present AND the 5 operator overrides survived. Confirm the resolver: `prod_real`'s
`strategy_config.config->>'broker_target'` is a single distinct `alpaca-live`.

**Step 0b — mount the `broker-kek` secret on `exec-alpaca-live`** (paper already has it; live ran env-creds
so it was never added — without it the DB-crypto bean throws `broker KEK file not found at /etc/broker-kek/kek`
and the write 502s). Mirror paper's volume exactly:
```
kubectl patch deploy exec-alpaca-live -n copytrade --type=strategic -p '{"spec":{"template":{"spec":{
  "volumes":[{"name":"broker-kek","secret":{"secretName":"broker-kek","defaultMode":420,"optional":true,
    "items":[{"key":"kek","path":"kek"}]}}],
  "containers":[{"name":"exec-alpaca-live","volumeMounts":[{"name":"broker-kek","mountPath":"/etc/broker-kek",
    "readOnly":true}]}]}}}}'
kubectl -n copytrade rollout status deploy/exec-alpaca-live
# confirm: kubectl exec deploy/exec-alpaca-live -c exec-alpaca-live -- test -f /etc/broker-kek/kek
```

1. **Flip the live-path creds flags on `exec-alpaca-live`** (inject the admin token from the SAME secret
   api-gateway uses, so the tokens match; keep per-tenant sockets OFF for the single-tenant arming):
   ```
   kubectl set env deploy/exec-alpaca-live -n copytrade \
     --from=secret/exec-admin-credentials --keys=EXEC_ADMIN_SHARED_TOKEN
   kubectl set env deploy/exec-alpaca-live -n copytrade BROKER_CREDS_DB_LIVE_ENABLED=true
   kubectl set env deploy/exec-alpaca-live -n copytrade BROKER_CREDS_SOURCE=db
   ```
   `ExecAdminTokenFilter` fail-fasts on the insecure default under the `prod` profile, so the token is
   required. (`exec-alpaca-live` is out of the deploy matrix — this manual flip is expected and durable.)
2. **Wait for rollout + verify boot:** `kubectl -n copytrade rollout status deploy/exec-alpaca-live`.
   With `source=db` and no row yet, `prod_real` resolution logs `BrokerCredentialsUnavailable` and fails
   closed until step 3 — expected, this is why it's off-hours.
3. **Write `prod_real`'s live credential row IMMEDIATELY** via the api-gateway **operator** endpoint
   `POST /admin/tenants/prod_real/broker-credentials` (routed to `exec-alpaca-live` by step 0a). Auth +
   body (secrets from the `alpaca-credentials-live` secret — NEVER echo them; e.g. pipe the body via stdin
   and reference `$API_GATEWAY_SHARED_TOKEN` from the pod env):
   - Headers: `Authorization: Bearer <API_GATEWAY_SHARED_TOKEN>`, `X-Operator-Id: ridopark@gmail.com`
     (must be in `OPERATOR_ALLOWLIST`), `Content-Type: application/json`.
   - Body: `{tenant_id: prod_real, provider: alpaca, api_key_id, api_secret_key,
     base_url: https://api.alpaca.markets, ws_url: wss://api.alpaca.markets/stream,
     declared_account_id: 847309116, expected_version: 0}` (`expected_version=0` for a fresh insert;
     `correlation_id` optional).
   - The writer probes `/v2/account`, pins `expected_account_id`, and returns the read-back. It **MUST**
     come back `{"version":1,"broker_account_id":"847309116"}` — reject/investigate any other account.
     (A `502 credential_write_failed` here means it reached exec but the writer threw — check exec-live logs
     for the cause, e.g. the KEK-not-found from skipping step 0b.)
4. **Clean-roll `exec-alpaca-live`** so the fill socket authenticates with the now-present DB creds:
   `kubectl -n copytrade rollout restart deploy/exec-alpaca-live`. (It auto-recovers via registry warm-up
   without a roll, but a clean roll makes the boot deterministic.) Verify the boot log shows
   `broker account identity verified ... account=847309116 ... registry warm-up (tenant=prod_real)` and
   `fill-listener started (single-socket) ws_url=wss://api.alpaca.markets/stream`, with **no**
   `BrokerCredentialsUnavailable` / `ERROR` after boot.
5. **Verify `prod_real` resolves + trades** — only the `prod_real/847309116` row exists in `exec_alpaca_live`
   (no leak), paper untouched, `replicas: 1`, and no order intents fired during the window. `prod_real` is
   now on the DB path, **behavior-equal to before** (this swapped the creds *source* env→db for the SAME
   account; positions/recon unchanged).
6. **(Only when a 2nd live tenant will share `alpaca-live`) enable per-tenant sockets + the shared-account
   validator**, then roll each pod:
   ```
   kubectl set env deploy/exec-alpaca-live -n copytrade EXEC_FILL_LISTENER_PER_TENANT_ENABLED=true
   kubectl set env deploy/orchestrator     -n copytrade MULTITENANT_BROKER_ACCOUNTS_ENABLED=true
   kubectl -n copytrade rollout status deploy/exec-alpaca-live
   kubectl -n copytrade rollout status deploy/orchestrator
   ```
   With per-tenant sockets on, the live pod opens one `trade_updates` socket per live tenant
   (`sockets_started` increments); the validator accepts many live tenants iff each declares a distinct
   non-blank `broker_account_id` (a single tenant's strategies share one account). Not needed for
   `prod_real` alone — it resolves via `EXEC_BOOTSTRAP_TENANT_ID` on the single pod-wide socket.

Part A is done once (✅ 2026-07-05); every subsequent live tenant is just Part B.

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
