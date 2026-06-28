> [!CAUTION]
> **SUPERSEDED 2026-06-28 by `docs/plans/PLAN-2026-06-28-operator-account-onboarding.md`.**
> This Option-B (dedicated `broker_target` + dedicated single-account exec pod + dedicated
> exec DB *per account*) approach is **REJECTED** because it is **O(N)-code-per-account**: each
> new live account requires a new `broker_target` enum value threaded across ~9 schemas, a new
> exec pod, a new exec DB, and a new BFF/api-gateway routing-map entry — recurring engineering +
> deploy work for *every* account. The superseding plan adopts the **shared-account path** (one
> `broker_target` `alpaca-live` for all live tenants, distinguished by per-tenant
> `broker_account_id` + per-tenant DB-encrypted creds, gated by `multitenant.broker-accounts.enabled`),
> which makes the Nth live account **data-only** (create account → paste keys → attach strategy →
> one-click), with no orchestrator restart and no per-account code/contract change. Do NOT implement
> the phases below; they remain only as a record of the rejected alternative.

---

# PLAN — 2026-06-28 second live Alpaca account (`prod_real2`)

Add a SECOND live Alpaca account as a new tenant `prod_real2` (the operator's own
account), mirroring the same `copytrade-v1` signals as `prod_real`, using **Option B**:
a NEW dedicated `broker_target` + a dedicated single-account exec pod + its own exec DB.
Option A (shared worker / file-creds multi-account) was rejected after 3-agent
adversarial verification because it is blocked on the unfinished P4-c-b per-tenant
account-wide reads.

Source: cross-verified design brief (3-agent adversarial). Anchors below re-confirmed
by reading the code at authoring time. Both naming + mount-model decisions are DECIDED
(see "Chosen design" below) — no open forks remain.

---

## 🔴 Naming/adapter decision (DECIDED) — and why `alpaca2-live` was rejected

The brief's originally-suggested name **`alpaca2-live` does NOT work.** Two independent
hard gates in the verified code reject it, and they are mutually constraining:

1. **Routing-validator regex** — `BrokerTargetValidator.VALID_TARGET`
   (`services/orchestrator/.../workflows/BrokerTargetValidator.java:23`) is
   `^(paper|live|[a-z]+-(paper|live))$`. The provider segment is `[a-z]+` — **letters
   only, no digits**. Tested: `alpaca2-live` → regex **FALSE**. (`alpaca-live-2` also
   FALSE.) `taskQueueFor` (`ExecActivitiesFactory.java:172`) calls `isValid` and throws a
   non-retryable `InvalidBrokerTargetError` for a non-match — the workflow fails fast, no
   order placed.

2. **Adapter/registry provider equality** — every exec activity derives the provider from
   the *request/intent/journal* `broker_target` via `BrokerClientRegistry.providerOf(...)`
   (substring before the FIRST `-`, `BrokerClientRegistry.java:55-61`), then calls
   `AlpacaBrokerClientRegistry.brokerFor(...)`, which **hard-rejects any provider other
   than the literal `"alpaca"`** (`AlpacaBrokerClientRegistry.java:52` `PROVIDER="alpaca"`,
   `:68` throws `InvalidBrokerTargetError` for `!PROVIDER.equals(provider)`). Confirmed at
   ALL five call sites: place `ExecActivitiesImpl.java:88`, cancel `:202`, account-snapshot
   `AccountSnapshotExecActivityImpl.java:44`, pre-trade `PreTradeCheckExecActivityImpl.java:35`,
   portfolio `PortfolioHistoryExecActivityImpl.java:42`. So `alpaca2-live` →
   provider `alpaca2` → registry throws.

   > NOTE the brief also claimed `alpaca2-live` would fail `startsWith("alpaca-")`. That
   > specific check (`AlpacaConfig` / `AlpacaTradeUpdatesStream` / probe, all
   > `'${broker.impl:}'.startsWith('alpaca-')`) keys off the **`broker.impl` (`BROKER_IMPL`)
   > env**, NOT off `broker_target` — so it is NOT in play for target naming. Adapter
   > selection is decoupled from routing (good news, see below); the real blockers are
   > #1 (regex) and #2 (registry provider-equality).

**Hard conclusion:** the regex admits exactly one dash with `env ∈ {paper,live}`, and the
registry (as-is) demands provider == `alpaca`. The ONLY string satisfying BOTH unchanged is
`alpaca-live` — **already used by `prod_real`.** There is therefore no drop-in target name
that is distinct, regex-valid, registry-accepted as-is, and ends in `-live`. A one-line code
change to the registry is required and is taken in Phase 0.

### CHOSEN DESIGN (naming — DECIDED)

- **`broker_target` = `alpacatwo-live`** (provider token `alpacatwo`). Regex-valid
  (`[a-z]+-(paper|live)`), ends in `-live` so `isLive` fires, distinct from `prod_real`'s
  `alpaca-live`.
- **`tenant_id` = `prod_real2`** (unchanged).
- **Exec registry accepted-provider set widened to `{alpaca, alpacatwo}`** (Phase 0) so
  provider `alpacatwo` resolves the Alpaca adapter; the fail-closed throw for any
  genuinely-unknown provider (e.g. `tradier`) is preserved.
- **Task queue** `broker-alpacatwo-live`; **DB** `exec_alpacatwo_live`; **new exec manifest**
  `52c-exec-alpacatwo-live.yaml` keeps `BROKER_IMPL=alpaca-live` (adapter selection) and sets
  `TEMPORAL_TASK_QUEUE=broker-alpacatwo-live` (routing).
- **`alpaca2-live` is REJECTED** (fails the regex AND would route to provider `alpaca2` →
  fail-closed at runtime). Do NOT use it. Reusing `alpaca-live` is ALSO rejected: routing is
  `broker_target`→queue (NOT tenant-keyed), so two pods on `broker-alpaca-live` would
  load-balance → silent wrong-account placement.

**Confirmed decoupled and reused as-is:**
- `BROKER_IMPL` and `TEMPORAL_TASK_QUEUE` are **separate** env vars on the exec pod
  (`52b-exec-alpaca-live.yaml:70-71` and `:84-85`). The worker polls the queue named by
  `TEMPORAL_TASK_QUEUE` literally; `BROKER_IMPL` independently activates the Alpaca adapter.
  So the new pod keeps `BROKER_IMPL=alpaca-live` and sets `TEMPORAL_TASK_QUEUE=broker-alpacatwo-live`.
- `taskQueueFor(t)` = `"broker-" + t` (`ExecActivitiesFactory.java:33,191`) — pure on the
  string, replays reconstruct the queue deterministically.
- `StrategyConfigInvariants.isLive` (`:75-78`) = `value().endsWith("-live")` — any
  `-live`-suffixed target fires the live-safety gates. ✅

### CHOSEN DESIGN (orchestrator tenant mount — DECIDED: operator-only / out-of-band)

The repo `infra/k8s/51-orchestrator.yaml:159-165` mounts the `tenants` ConfigMap volume with
an `items:` projection that lists ONLY the `dev` tenant (`tenant.yaml`→`dev/tenant.yaml`,
`copytrade-v1.yaml`→`dev/strategies/`, `watchlist-trigger-v1.yaml`→`dev/strategies/`). The
LIVE cluster mounts `prod_real` + `staging_paper` via an **out-of-band** volume-`items:`
patch to the running Deployment; re-applying the repo manifest reverts the mount to dev-only
(a known clobber event with a repair runbook). **Therefore mounting `prod_real2` is an
operator-only out-of-band patch to the LIVE orchestrator Deployment** (add the two
`prod_real2` keys exactly like `prod_real`/`staging_paper`), NOT a repo edit. The repo
`51-orchestrator.yaml` STAYS dev-only — there is NO repo phase for it. This is folded into
the P0 operator bucket below (it was a former "Phase 5"; that phase is removed as a repo phase).

---

## P0 — Immediate operational (no code; operator). Do these around/after the code ships.

These carry real-money risk and are NOT repo phases:

- **Provision live creds Secret** `alpaca-credentials-live2` (operator's own account):
  `APCA_API_KEY_ID`, `APCA_API_SECRET_KEY`, `APCA_API_BASE_URL=https://api.alpaca.markets`.
  Kept distinct from `alpaca-credentials-live` so account A's keys never reach account B's pod.
- **Account starts 403-blocked at the broker** until the operator explicitly unblocks it —
  same posture `prod_real` shipped with. Do NOT lift the broker block until the e2e
  verification phase (Phase 5) has proven isolation + live-gate firing on the live cluster.
- **`prod_real2` tenant + strategy YAML is out-of-band** (live-cluster-only, like
  `prod_real`/`staging_paper`; NOT in the repo `tenants/dev/*`, NOT in repo
  `40-tenants-config.yaml`). Set the `prod_real2` keys directly on the cluster's
  `tenants-config` ConfigMap via `kubectl` — re-applying repo manifests would clobber them.
  The `prod_real2` `copytrade-v1` strategy YAML MUST include the live-required gate fields
  (per `LiveRequiredGateValidator`):
  - `broker_target: alpacatwo-live`
  - `daily_loss_threshold` > 0
  - `notional_cap_pct_of_capital_base` non-null
  …else orchestrator boot fail-closes for this tenant. (Phase 5 proves the gate fires by
  attempting boot WITHOUT the loss-cap and asserting fail-closed.)
- **Orchestrator tenant MOUNT is out-of-band (DECIDED — not a repo phase).** Patch the LIVE
  orchestrator Deployment's `tenants` ConfigMap volume `items:` to add the two `prod_real2`
  keys, e.g. `prod2-tenant.yaml`→`prod_real2/tenant.yaml` and
  `prod2-copytrade-v1.yaml`→`prod_real2/strategies/copytrade-v1.yaml`, exactly as
  `prod_real`/`staging_paper` are mounted today.
  ⚠️ **Re-applying repo `51-orchestrator.yaml` reverts the mount to dev-only** (drops
  `prod_real` + `staging_paper` + `prod_real2`) — the same known clobber caveat that already
  applies to `prod_real`; cross-ref the orchestrator-mount repair runbook before any
  `kubectl apply` of `51-orchestrator.yaml`. The out-of-band ConfigMap KEY names (above) and
  the Deployment `items:` key references MUST agree.
- **Manual exec rollout**: the new exec pod (like `exec-alpaca-live`) is NOT in the
  `deploy.yml` SERVICES matrix → operator `kubectl apply` of the new manifest by hand at
  cutover; verify the running image digest per-pod after rollout.
- **Manual DB create + Flyway**: on the existing homelab volume the `10-postgres.yaml` init
  script does NOT re-fire — the operator must `CREATE DATABASE exec_alpacatwo_live` by hand
  (psql) + run Flyway migrations against it. (Repo `10-postgres.yaml` edit in Phase 3 only
  covers fresh-volume clusters.)
- **Per-tenant Discord webhook** for `prod_real2` injected into the `discord-alert-credentials`
  Secret's `ALERT_DISCORD_WEBHOOK_URLS` map (out-of-band) — else its broker-rejection alerts
  cross-route to another tenant's channel via the global fallback (known footgun).
- **Live-promotion dual-control** `LivePromotionApproved` for `prod_real2` (the approval
  schema's `broker_target` is a FREE string — `live-promotion-approval-request.json` — so it
  accepts the new value with NO schema change). Operator + second approver execute at cutover.

---

## Phase 0 — Widen the exec registry accepted-provider set + lock the name (exec; gating phase)
**Module(s):** exec (+ a unit test), no infra. Lands the chosen `alpacatwo-live` name as a
test-enforced constant; every downstream phase references it.

**Goal:** Make provider `alpacatwo` resolve the Alpaca adapter (keeping the fail-closed throw
for unknown providers), encode the naming decision as a failing-then-passing test.

**Changes (anchors):**
- `services/exec/.../broker/alpaca/AlpacaBrokerClientRegistry.java:52,68` — replace the literal
  `PROVIDER.equals(provider)` gate with an accepted-provider set
  `Set.of("alpaca", "alpacatwo")` — minimum surgical change; KEEP the non-retryable
  `InvalidBrokerTargetError` throw for any provider outside the set (the fail-closed contract
  must survive). No other file derives the adapter from `broker_target`, so this is the only
  code seam blocking provider `alpacatwo`.
- No `broker.impl` change: the new pod keeps `BROKER_IMPL=alpaca-live` (adapter selection),
  confirmed independent of `broker_target`.

**Replay-safety:** NONE needed. `taskQueueFor` and `providerOf` are pure string functions;
this phase only widens an accepted-provider set in a stateless activity-side registry. No
workflow command shape changes. No `getVersion` gate.

**Tests (TDD):**
- `AlpacaBrokerClientRegistryTest`: add `brokerFor_acceptsAlpacatwoProvider_resolvesAlpacaAdapter`
  (provider `alpacatwo` resolves a client, does NOT throw) and keep/confirm
  `brokerFor_rejectsUnknownProvider_nonRetryable` (e.g. `tradier` still throws
  `InvalidBrokerTargetError`).
- A pure-function unit asserting `BrokerClientRegistry.providerOf("alpacatwo-live") == "alpacatwo"`
  and `BrokerTargetValidator.isValid("alpacatwo-live") == true` and
  `isValid("alpaca2-live") == false` (locks the corrected naming decision in code).

**Verify / success criteria:**
- `mvn -pl services/exec -am spotless:apply && mvn -pl services/exec -am test` green.
- New provider-alias test fails on `main` (proves the gate was real) and passes after the edit.

---

## Phase 1 — Contract: add `alpacatwo-live` to the closed `broker_target` enum (contract module)
**Goal:** Add the new value to every closed enum schema + regenerate Java/Python + update the
hard-coded Python guard test. Backward-compatible value addition.

**Changes (anchors):** add `"alpacatwo-live"` to the inline `broker_target` enum in the **9
schemas that carry the closed enum** (each currently lists the same 10 values):
- `contract/schemas/strategy-config.json`
- `contract/schemas/order-intent.json`
- `contract/schemas/pre-trade-check-request.json`
- `contract/schemas/account-snapshot-request.json`
- `contract/schemas/position-snapshot-request.json`
- `contract/schemas/portfolio-history-request.json`
- `contract/schemas/journal-entry.json`
- `contract/schemas/reconciliation-workflow-input.json`
- `contract/schemas/position-workflow-input.json`

> Do NOT touch the FREE-STRING `broker_target` schemas — they accept the new value with no
> edit: `account-kill-switch-workflow-input.json`, `live-promotion-approval-request.json`,
> `audit-event.json`, `adoption-workflow-input.json`, `broker-credential-audit-request.json`,
> `broker-position.json`, `portfolio-history-result.json`. (Verified: only the 9 above carry
> the inline 10-value enum.)

- Regenerate Java DTOs via jsonschema2pojo (`contract/java/pom.xml`); NEVER hand-edit
  `target/generated-sources`.
- Regenerate Python models AND update the single-source enum
  `contract/python/ohmytradeagent_contract/types/broker_target.py` — add member
  `alpacatwo_live = "alpacatwo-live"`.
- Update the hard-coded guard test
  `contract/python/tests/test_broker_target_single_source.py::test_canonical_enum_has_expected_members`
  — add `"alpacatwo-live"` to the asserted member set (it hard-asserts the EXACT set; the
  build fails otherwise).

**Replay-safety:** SAFE, NO `getVersion`. Pure additive value to a closed enum — existing
`prod_real` workflows carry `alpaca-live` and are untouched; no running history references the
new value. Temporal 1.27 replay checks command type/ordering, not payload value sets, and no
command shape changes here. CONFIRMED replay-safe. (This is the backward-compat claim the
brief asked to confirm — confirmed.)

**Tests (TDD):**
- Python: extend `test_canonical_enum_has_expected_members` (above) — red before, green after.
- A Java round-trip test (or existing contract round-trip) deserializing an `OrderIntent`/
  `StrategyConfig` with `broker_target: alpacatwo-live` to prove the regenerated enum admits it.

**Verify / success criteria:**
- `mvn -pl contract/java -am spotless:apply && mvn -pl contract/java -am verify` green
  (generated POJO contains `ALPACATWO_LIVE`).
- `cd contract/python && pytest tests/test_broker_target_single_source.py` green; the Python
  round-trip/drift check green.
- Contract module is the FIRST to land + pass CI before any infra (per ship order).

---

## Phase 2 — BFF: route `alpacatwo-live` to its own exec datasource (tenant-dashboard-bff)
**Goal:** The dashboard/BFF reads `prod_real2`'s `order_intent_journal` from the new exec DB,
not 404 and not the wrong book.

**Changes (anchors):**
- `services/tenant-dashboard-bff/.../config/DataSourceConfig.java` — add a new datasource +
  `@Qualifier("execAlpacatwoLiveDsl") DSLContext` bean pointing at the new DB
  (`exec_alpacatwo_live`), mirroring the existing `execAlpacaLiveDsl` wiring.
- `services/tenant-dashboard-bff/.../config/BrokerDataSourceRouter.java:20-24` — extend the
  ctor to inject `execAlpacatwoLiveDsl` and add `"alpacatwo-live"` to the `Map.of(...)`
  (currently the 2-entry `alpaca-paper`/`alpaca-live` map). Unknown target still 404s
  (`BrokerNotConfiguredException`), so behavior for other targets is unchanged.

**Replay-safety:** N/A — BFF is a read-side HTTP service, no Temporal workflow code.

**Tests (TDD):**
- `BrokerDataSourceRouterTest`: `dslFor("alpacatwo-live")` returns the new DSL (not throw);
  `isConfigured("alpacatwo-live") == true`; an unknown target still throws.

**Verify / success criteria:**
- `mvn -pl services/tenant-dashboard-bff -am spotless:apply && mvn -pl services/tenant-dashboard-bff -am test`
  green. Depends on Phase 1 only insofar as the BFF datasource config needs the DB to exist at
  runtime (Phase 3 / operator) — the unit test mocks the DSL, so it lands independently.

---

## Phase 3 — Exec DB definition: add `exec_alpacatwo_live` to the init list (infra, repo)
**Goal:** A new dedicated exec DB for full journal isolation (per-`broker_target`-DB convention;
rows are `tenant_id`-scoped but a separate DB matches the existing 8-DB pattern and keeps the
live book's idempotency keys from ever colliding with another book's).

**Changes (anchors):**
- `infra/k8s/10-postgres.yaml:41-48` (the `create_db_if_missing` block) — add
  `create_db_if_missing exec_alpacatwo_live` alongside `exec_alpaca_live`.

**Replay-safety:** N/A (infra manifest).

**Operator notes (CRITICAL — fold into the phase):**
- `10-postgres.yaml` is NOT applied by `deploy.yml`. The init script only fires on a FRESH data
  volume; the existing homelab volume will NOT re-run it. → Operator follow-up (P0):
  `psql -c 'CREATE DATABASE exec_alpacatwo_live'` by hand + run Flyway migrations against
  `EXEC_DB_URL=jdbc:postgresql://postgres:5432/exec_alpacatwo_live`.
- jOOQ regen for the new datasource if BFF's jOOQ generation is per-DB (confirm against the BFF
  build; the schema is identical to `exec_alpaca_live`, so codegen output should match — verify
  no per-DB jOOQ package divergence is required).

**Verify / success criteria:**
- `kubeconform`/`k8s` CI check green on the edited manifest.
- (Operator, on cluster) `\l` shows `exec_alpacatwo_live`; Flyway `info` shows all migrations
  applied; `order_intent_journal` table present + empty.

---

## Phase 4 — Exec deployment manifest for the new live pod (infra, repo)
**Goal:** A dedicated single-account exec pod polling `broker-alpacatwo-live`, own live creds,
own expected account-id, own DB; fill-listener single-pod constraint preserved.

**Changes (anchors):** new file `infra/k8s/52c-exec-alpacatwo-live.yaml`, cloned from
`52b-exec-alpaca-live.yaml`, changing ONLY:
- Service/Deployment/label name → `exec-alpacatwo-live`.
- `TEMPORAL_TASK_QUEUE: broker-alpacatwo-live` (was `broker-alpaca-live`) — this is what makes
  the pod its own routing target. (`:71`)
- `EXEC_DB_URL: jdbc:postgresql://postgres:5432/exec_alpacatwo_live` (`:73`).
- `BROKER_IMPL: alpaca-live` — UNCHANGED (adapter selection is decoupled from routing;
  confirmed Phase 0). (`:84-85`)
- `envFrom.secretRef.name: alpaca-credentials-live2` (the operator's-account creds; `:62-63`).
- `EXPECTED_ALPACA_ACCOUNT_ID: "<operator-account-id>"` (NOT `847309116`; the live-safety probe
  crashloops fail-closed if the keys authenticate a different account; `:90-91`).
- `EXEC_FILL_LISTENER_*` kept as-is (single-pod listener, live WS URL
  `wss://api.alpaca.markets/stream`; `:95-106`) — `replicas: 1` MUST stay (`:31`).
- Alert env (`ALERT_DISCORD_WEBHOOK_URL[S]`) kept; the per-tenant URL for `prod_real2` is an
  operator secret entry (P0), not committed.

**Replay-safety:** N/A (infra). But note: until this pod is running, an OrderIntent with
`broker_target=alpacatwo-live` will hang at the activity StartToCloseTimeout (no worker polls
`broker-alpacatwo-live`) — so DO NOT route any `prod_real2` signal until the pod is live
(enforced by the live-promotion gate + the broker 403-block; Phase 5 verifies).

**Operator notes:** NOT in the `deploy.yml` SERVICES matrix → manual `kubectl apply` at
cutover; `build-images` is full-matrix so the image is built, but the rollout is by hand.
Verify the running pod's image digest matches the freshly-built tag per-pod after apply.

**Verify / success criteria:**
- `kubeconform`/`k8s` CI green on the new manifest.
- (Operator) pod `Running`; `AlpacaAccountIdentityProbe` log confirms the authenticated account
  == `EXPECTED_ALPACA_ACCOUNT_ID` (else crashloop = correct fail-closed); worker logs show it
  polling `broker-alpacatwo-live`.

---

## Phase 5 — End-to-end verification (test phase; gates the broker-unblock)
**Goal:** Prove a mirrored `copytrade-v1` signal sizes/fills/journals on `prod_real2`'s account
in ISOLATION from `prod_real`, and that the new target's live gates actually fire. Run on the
live cluster as the operator AFTER the P0 follow-ups (creds, DB+Flyway, out-of-band tenant YAML
+ mount patch, alert map) are in place.

**Behavioral assertions (the success criteria):**
1. **Routing isolation:** a `copytrade-v1` signal mirrored to `prod_real2` produces an
   `OrderIntent` with `broker_target=alpacatwo-live`, routes to queue `broker-alpacatwo-live`,
   and is served by the `exec-alpacatwo-live` pod → fill lands in `exec_alpacatwo_live`'s
   `order_intent_journal`, and `prod_real`'s `exec_alpaca_live` journal shows NO corresponding
   row (and vice-versa). Confirms no queue load-balancing / cross-account placement.
2. **Account-identity fail-closed:** the new pod, if mis-keyed, crashloops (probe). Verify the
   running pod authenticated the operator's account, not `847309116`.
3. **Live-gate fires (isLive proof):** attempt orchestrator boot for `prod_real2` with the
   `daily_loss_threshold` / `notional_cap_pct_of_capital_base` REMOVED → boot must fail-closed
   (`LiveRequiredGateValidator`), proving `isLive("alpacatwo-live")==true`. Restore fields →
   boots. This is the explicit "prove the gates aren't silently bypassed" check.
4. **Alert routing:** a forced broker rejection on `prod_real2` lands in `prod_real2`'s Discord
   channel, not another tenant's.

**Verify:** run on the homelab live cluster as the operator, with the broker account STILL
403-blocked for live order acceptance during dry checks; lift the broker 403 ONLY after 1–3
pass. (Mirror the `prod_real` go-live posture.)

---

## Ship order & gating

1. **Phase 0** (exec registry provider-alias `{alpaca, alpacatwo}` + naming guard test) —
   lands the name decision as a constant.
2. **Phase 1** (contract enum) — must land + pass CI BEFORE any infra (regenerates DTOs the
   whole tree depends on; backward-compatible, no `getVersion`).
3. **Phase 2** (BFF router/datasource) — independent unit-tested PR.
4. **Phase 3** (postgres DB-init manifest) — repo edit; operator creates the DB by hand on the
   existing volume.
5. **Phase 4** (new exec manifest) — repo edit; operator `kubectl apply` (manual roll, not in
   deploy matrix) + per-pod digest verify.
6. **P0 operator follow-ups** (creds Secret, out-of-band `prod_real2` tenant/strategy YAML with
   live-gate fields, out-of-band orchestrator-mount `items:` patch — DO NOT re-apply repo
   `51-orchestrator.yaml`, alert map, DB+Flyway, live-promotion dual-control) — out-of-band,
   around cutover.
7. **Phase 5** (e2e verification) — gates lifting the broker 403-block.

Each phase: TDD, `spotless:apply` on EVERY touched Java module (Phase 0 exec; Phase 1
contract/java; Phase 2 tenant-dashboard-bff), own single-concern PR, operator merge gate
(trading-critical). `gh pr edit --body` is broken here → set PR body at create time or
`gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`. `KillSwitchWorkflowImplTest` is flaky →
re-run, don't fix. Never touch `.github/workflows/*.yml`.

---

## Out of scope (explicit)

- **Option A** — shared-worker / file-creds multi-account in one pod (blocked on unfinished
  P4-c-b per-tenant account-wide reads). NOT pursued here.
- **HA / multi-replica fill router** (P5) — the new pod stays `replicas: 1`; leader-elected
  multi-pod fill listening is a separate follow-up (`docs/ops/fill-listener.md`).
- **Any repo edit to `51-orchestrator.yaml` / `40-tenants-config.yaml` for `prod_real2`** —
  the orchestrator mount and the `prod_real2` tenant YAML are LIVE-cluster-only out-of-band
  (same posture as `prod_real`/`staging_paper`); the repo manifests STAY dev-only.
- Any change to `prod_real`'s existing `alpaca-live` target/pod/DB — untouched and unaffected.
