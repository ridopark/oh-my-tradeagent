# Agentic Trading Bot — Copytrade v0 Plan

> Implementation plan for the copy-trade v0 product described in [`PRD.md`](../prd/PRD.md). The previous multi-agent intraday plan is preserved in [`PLAN-agentic-v2.md`](./PLAN-agentic-v2.md).

## Goal

A Temporal-orchestrated microservice system that mirrors options trades from a vetted Discord channel into one or more tenants' paper (then live) accounts, with deterministic risk gates, idempotent order placement, durable position lifecycle management, and full audit. Multi-tenant and multi-strategy are baked in from Phase 0 at the contract/workflow-ID level; v0 deploys with one tenant (`dev`) and one strategy (`copytrade-v1`).

## Constraints & operating envelope

- **Asset class**: US options only for v0 (BTO/STC on single-leg calls and puts). No equities, no multi-leg, no futures/FX/crypto.
- **Source**: one Discord channel per `(tenant, strategy)` deployment; pluggable source contract is a future enhancement.
- **Strategy horizon**: intraday-to-day-trade. All positions must be flat by EOD timer (15:55 ET default; 15:30 ET for 0DTE expiry-day positions).
- **Data freshness**: signal age veto rejects any post older than `max_signal_age_secs` (default 1800). Broker quotes carry `retrieved_at`; quotes older than 5s for active contracts are refused at the Activity boundary.
- **Paper trading first.** Live broker keys gated behind manual promotion (Phase 7).
- **Multi-tenant isolation.** Every Activity payload, workflow input, audit event scoped by `(tenant_id, strategy_id)`. Workflow IDs prefixed `t-<tenant>/s-<strategy>/...`. CI guardrails enforce.
- **Determinism in workflow code.** No language-time clocks, no random, no I/O. CI AST scan enforces.

## Stack decisions

- **Java 21 LTS** for all backend services; **Spring Boot 3** application framework; **Maven** multi-module build; **jOOQ** for type-safe SQL access.
- **Python 3.12** for the Discord sidecar (Playwright maturity).
- **Temporal Java SDK** for orchestrator + activity workers; **Temporal Python SDK** for the sidecar's `start_workflow` client.
- **Postgres** for journal, registries, audit; **Redis** for quota counters + short-TTL state.
- **Micrometer + Prometheus + OpenTelemetry** for metrics + tracing across all services; sidecar emits OTel via OpenTelemetry Python SDK.
- **Prompts-as-data slot** present in `StrategyConfig` for future agentic phase; unused in v0.

## Architecture

```
[ Discord channel ]
        │
        ▼  Playwright DOM polling, ~1s
┌──────────────────────────┐
│ signal-source-discord    │  Python sidecar, one per (tenant, strategy, channel)
│ - Playwright DOM watcher │
│ - regex parser           │
│ - seen_ids.json (bound)  │
└────────────┬─────────────┘
             │ Temporal client: start_workflow(
             │   CopytradeSignalWorkflow,
             │   payload,
             │   workflow_id="t-{tenant}/s-{strategy}/sig/{signal_id}",
             │   id_reuse_policy=REJECT_DUPLICATE,
             │   task_queue="orchestrator-core")
             ▼
┌─────────────────────────────────────────────────────────────┐
│ Temporal Cluster (server + history + matching + frontend)   │
└──────────┬──────────────────────────────────────────────────┘
           │  workers poll task queues
           ▼
┌────────────────────────────────────────────────────────────────────┐
│ orchestrator-svc (Java, Spring Boot, Temporal Java SDK)            │
│   workflows:                                                        │
│     - CopytradeSignalWorkflow (per signal)                          │
│     - PositionWorkflow (per open position, long-running)            │
│     - BTOEntryTimerWorkflow (per pending BTO)                       │
│     - ReconciliationWorkflow (scheduled)                            │
│     - KillSwitchWorkflow (per (tenant, strategy), session-long)     │
│   task queue: orchestrator-core                                     │
└──┬─────────┬─────────┬─────────┬─────────┬─────────┬───────────────┘
   │         │         │         │         │         │
   ▼         ▼         ▼         ▼         ▼         ▼
┌─────┐  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐
│risk │  │contract│ │ exec-  │ │market- │ │platform│ │audit │
│-svc │  │resolver│ │alpaca  │ │data-svc│ │ -svc   │ │-svc  │
└─────┘  └────────┘ │/tradier│ └────────┘ └────────┘ └──────┘
                    │ /ibkr  │
                    └────────┘

   ┌──────────────────────────────────────────┐
   │ api-gateway (Java, Spring Boot REST)     │  → operator + admin clients
   │   GET /positions, /audit                 │     (kill switch, force close,
   │   POST /killswitch/trip, /force-close    │      status, audit query)
   └──────────────────────────────────────────┘
```

**Workflow is the orchestrator.** Sequencing lives in workflow code; no service call graph, no event sagas across services. Activities are dispatched to per-service task queues by name.

## Service inventory

| Service | Lang | Task queue(s) | Owns | Persistent state |
|---|---|---|---|---|
| `signal-source-discord` | Python | (Temporal client only) | Playwright lifecycle; parser; `seen_ids.json`; `storage_state.json`; heartbeat | Local volume |
| `orchestrator-svc` | Java | `orchestrator-core` | All workflows + workflow-local activities | None (Temporal history is the state) |
| `risk-svc` | Java | `risk` | Author whitelist; max-positions count; signal-age veto; killswitch read; size cap | Postgres (config), Redis (counters) |
| `contract-resolver-svc` | Java | `contract` | OCC symbol generation; tradability check; entry quote | None (read-through to broker) |
| `exec-svc-alpaca-paper` | Java | `broker-alpaca-paper` | Place/cancel/status on Alpaca paper | Postgres (`OrderIntentJournal` per broker env) |
| `exec-svc-alpaca-live` | Java | `broker-alpaca-live` | Same, live | Postgres |
| `exec-svc-tradier-paper` | Java | `broker-tradier-paper` | Tradier sandbox | Postgres |
| `exec-svc-tradier-live` | Java | `broker-tradier-live` | Tradier live | Postgres |
| `exec-svc-ibkr-live` | Java | `broker-ibkr-live` | IBKR via TWS API (Java native) | Postgres |
| `market-data-svc` | Java | `market-data` | Streaming option quotes for active contracts | In-memory (Caffeine) |
| `platform-svc` | Java | `platform` | `TenantRegistry`, `StrategyRegistry`, `SecretsResolver`, `QuotaTracker`, `CapitalAllocator`, `MarketCalendar` | Postgres + Vault/AWS-SM |
| `audit-svc` | Java | `audit` | Append-only `audit_log` | Postgres (partitioned by `tenant_id`) |
| `api-gateway` | Java | (none) | Operator REST; translates HTTP to Temporal client calls | None |

Paper + live live on separate task queues so a misconfigured `broker_target` routes to a queue with no worker, not to the wrong account.

## Temporal topology

### Workflow types

| Workflow | Lifetime | Started by | Workflow ID shape |
|---|---|---|---|
| `CopytradeSignalWorkflow` | ≤ 90s | Sidecar | `t-<tenant>/s-<strategy>/sig/<signal_id>` |
| `PositionWorkflow` | minutes-to-hours | `CopytradeSignalWorkflow` on BTO fill | `t-<tenant>/s-<strategy>/pos/<OCC>` |
| `BTOEntryTimerWorkflow` | ≤ TTL (90s paper / 30s live) | `CopytradeSignalWorkflow` | `t-<tenant>/s-<strategy>/btottl/<signal_id>` |
| `ReconciliationWorkflow` | seconds per run | Temporal Schedule (every 5 min + startup) | `t-<tenant>/s-<strategy>/recon/<broker_env>/<run_id>` |
| `KillSwitchWorkflow` | session-long | Temporal Schedule (daily per `(tenant, strategy)`) | `t-<tenant>/s-<strategy>/killswitch/<date>` |

### Signals (durable, async)

| Signal | Target workflow | Purpose |
|---|---|---|
| `fill_received` | `CopytradeSignalWorkflow` (BTO) | Broker fill listener → wake up |
| `ttl_expired` | `CopytradeSignalWorkflow` (BTO) | `BTOEntryTimerWorkflow` reports timeout |
| `partial_exit` | `PositionWorkflow` | From STC `CopytradeSignalWorkflow` |
| `arm_chandelier` | `PositionWorkflow` | From STC (first partial) |
| `chandelier_tick` | `PositionWorkflow` | From `market-data-svc` subscription |
| `risk_breach` | `PositionWorkflow`, `CopytradeSignalWorkflow` | Kill switch trip cascade |
| `reconcile_orphan` | `PositionWorkflow` | Reconciliation found discrepancy |

### Queries (synchronous, non-mutating)

| Query | Target | Returns |
|---|---|---|
| `position_state` | `PositionWorkflow` | `{contract, qty, avg_entry, remaining_frac, in_flight_exit_signal_ids, last_signal_at}` |
| `pending_orders` | `PositionWorkflow` or `CopytradeSignalWorkflow` | List of open broker order IDs |
| `signal_trace` | `CopytradeSignalWorkflow` | Risk decision, contract resolved, order intent, fill outcome |
| `killswitch_state` | `KillSwitchWorkflow` | `{tripped, reason, tripped_at}` |

### Updates (synchronous, validated state changes)

| Update | Target | Validation | Effect |
|---|---|---|---|
| `force_close` | `PositionWorkflow` | Caller authorized | Immediate full exit; returns final order id |
| `adjust_trail` | `PositionWorkflow` | New giveback pct in bounds | Update CHANDELIER giveback |
| `trip_killswitch` | `KillSwitchWorkflow` | Caller authorized | Trip; cascade `risk_breach` signals to children |
| `reset_killswitch` | `KillSwitchWorkflow` | Caller authorized | Reset to normal |

## End-to-end flows

### BTO flow

```
[Discord] DOM change
   │
   ▼
[signal-source-discord]
   parse_message(content) → [ParsedSignal(BTO, NVDA, 5/16, 140, C, 2.30, "open")]
   for each parsed_i:
     signal_id  = f"{message_id}:{i}"
     workflow_id = f"t-{TENANT}/s-{STRATEGY}/sig/{signal_id}"
     temporal_client.start_workflow(
       "CopytradeSignalWorkflow",
       payload=CopytradeSignalPayload(...),
       id=workflow_id,
       id_reuse_policy=REJECT_DUPLICATE,
       task_queue="orchestrator-core")
   │
   ▼
[CopytradeSignalWorkflow] (Java, orchestrator-svc)
  1. audit.log(SignalReceived)                              [audit queue]
  2. cfg = platform.strategy_get(tenant, strategy)          [platform queue]
  3. decision = risk.check_entry(payload, cfg)              [risk queue]
       - author in cfg.author_whitelist
       - age = now - payload.posted_at ≤ cfg.max_signal_age_secs
       - killswitch.query("state").tripped == false
       - count_running_position_workflows(tenant, strategy) < cfg.max_positions
     if not Allowed: audit.log(SignalRejected); return
  4. contract = contract_resolver.resolve(
       ticker, expiry, strike, right, cfg.broker_target)    [contract queue]
       → OCC symbol, bid, ask, mid
  5. qty = sizing.from_cfg(cfg, payload.price)
  6. intent_key = workflow_id + ":entry"
     broker_order_id = exec.place_order(
       intent_key, contract, BUY, qty,
       limit=payload.price or marketable_mid)               [broker-<target> queue]
       exec-svc internally:
         a. journal.record_intent(intent_key, ...)
         b. broker.place_order(client_order_id=intent_key, ...)
         c. journal.mark_submitted(broker_order_id)
  7. start_child BTOEntryTimerWorkflow(
       id="t-<tenant>/s-<strategy>/btottl/<signal_id>",
       ttl_secs=cfg.pending_ttl_{paper|live}_secs,
       parent_workflow_id=this)
  8. workflow.await(fill_received OR ttl_expired)
  9a. on fill:
        start_child PositionWorkflow(
          id="t-<tenant>/s-<strategy>/pos/<OCC>",
          parent_close_policy=ABANDON,
          input=PositionWorkflowInput(contract, qty, entry_premium, source_signal_id))
        audit.log(EntryFilled); return
  9b. on TTL expiry:
        exec.cancel_order(broker_order_id)
        audit.log(EntryExpired); return
```

### STC flow

```
[signal-source-discord] parses STC line, starts workflow as above
   │
   ▼
[CopytradeSignalWorkflow]
  1. audit.log
  2. cfg = platform.strategy_get
  3. risk.check_exit (cheaper: killswitch + author whitelist only)
  4. contract = contract_resolver.resolve_occ(ticker, expiry, strike, right)
  5. fraction = KeywordPartialMatcher.match(payload.tail, cfg.partial_fractions)
                .orElse(cfg.default_stc_fraction)
  6. position_id = f"t-{tenant}/s-{strategy}/pos/{OCC}"
     if not position_exists(position_id):
        # STC arrived before BTO filled. Buffer up to 90s.
        for _ in range(9):
           workflow.sleep(10s)
           if position_exists(position_id): break
        else:
           audit.log(OrphanSTC); return
     temporal.signal_workflow(position_id, "partial_exit",
       {signal_id, fraction, reason=matched_keyword or "default",
        author, raw_line, ref_premium=payload.price})
     if first_stc_for_position and cfg.trail_on_partial:
        temporal.signal_workflow(position_id, "arm_chandelier",
          {peak_premium=payload.price, giveback_pct=cfg.trail_giveback_pct})
  7. audit.log(ExitRequested); return
   │
   ▼
[PositionWorkflow] (already running)
  signal handler partial_exit:
    if signal_id in processed_exit_signal_ids: return     # idempotent
    if exit_in_flight: audit.log(ExitRejected); return
    processed_exit_signal_ids.add(signal_id)
    exit_in_flight = true
    qty_to_close = ceil(remaining_qty * fraction)
    intent_key = f"{workflow_id}:exit:{signal_id}"
    exec.place_order(intent_key, contract, SELL_TO_CLOSE, qty_to_close,
                     limit=ref_premium or marketable)
    await fill_received
    remaining_qty -= filled_qty
    exit_in_flight = false
    if remaining_qty / original_qty < 0.005:
       complete_workflow

  signal handler arm_chandelier:
    if trailing_armed: return
    peak_premium = payload.peak_premium
    giveback     = payload.giveback_pct
    trailing_armed = true
    market_data.subscribe_premium(contract)   # async; fires chandelier_tick signals

  signal handler chandelier_tick:
    if not trailing_armed: return
    if tick.premium > peak_premium: peak_premium = tick.premium
    if tick.premium < peak_premium * (1 - giveback):
       trigger_full_exit("chandelier_trail")
```

### Reconciliation flow

```
[Temporal Schedule] every 5 min + on orchestrator-svc startup
   → start ReconciliationWorkflow(tenant, strategy, broker_env)
   │
   ▼
[ReconciliationWorkflow]
  1. journal     = exec.journal_dump_open(tenant, strategy)   [broker-<env> queue]
  2. broker_open = exec.broker_list_open_orders()
  3. for entry in journal:
       if entry not in broker_open and entry.submitted_at < now - 5min:
          audit.log(JournalOrphan); mark expired or retry
  4. for order in broker_open:
       if order.client_order_id not in journal:
          audit.log(BrokerOrphan); notify api-gateway   # do not auto-cancel
  5. for filled in broker.recent_fills:
       if journal[filled.client_order_id].state != Filled:
          journal.mark_filled(...)
          signal_workflow(position_id, "reconcile_orphan", ...)
  6. audit.log(ReconciliationCompleted, {discrepancies: N})
```

### Kill switch flow

```
Daily Schedule → start KillSwitchWorkflow("t-<tenant>/s-<strategy>/killswitch/<date>")

KillSwitchWorkflow state: {tripped: false, reason: "", tripped_at: null}

Internal timer activity (runs every 60s):
   pnl = platform.daily_pnl(tenant, strategy)
   if pnl < -cfg.daily_loss_threshold:
      self.update("trip_killswitch", {reason="auto:daily_loss", value=pnl})

trip_killswitch Update handler:
   if not state.tripped:
      state.tripped = true; state.reason = ...; state.tripped_at = workflow.now()
      audit.log(KillSwitchTripped)
      for wf_id in list_running_workflows(prefix="t-<tenant>/s-<strategy>/"):
         signal_workflow(wf_id, "risk_breach", {reason})

risk-svc reads state via KillSwitchWorkflow.query("killswitch_state") on each entry.
```

## Cross-cutting concerns

- **Idempotency layers** (4): sidecar `seen_ids.json` → Temporal `workflow_id` REJECT_DUPLICATE → `OrderIntentJournal` `intent_key` → broker `client_order_id`. Each layer protects a specific crash window; their composition gives "exactly one order per Discord line, ever."
- **Multi-tenant scoping**: `(tenant_id, strategy_id)` on every payload + workflow ID prefix. CI guardrails:
  - `check_no_global_secrets.py` — credentials only via `SecretsResolver(tenant_id, ...)`.
  - `check_workflow_determinism.py` — workflow classes do not import `java.io.*`, `java.net.*`, `java.time.Clock`, `Math.random`, or call `System.currentTimeMillis()` / `Instant.now()` / `LocalDateTime.now()`.
  - `check_no_cross_service_imports.py` — services do not depend on each other except via `contract/java`.
  - `check_contract_drift.py` — JSON Schema → Python/Java round-trip on fixtures.
- **Secrets**: `SecretsResolver` Activity is the only path. Audit-logged on every resolve. Backed by Vault / AWS Secrets Manager in prod; local-file fallback in dev only.
- **Audit**: Every state-changing Activity emits `audit.log(tenant, event)`. Append-only Postgres table partitioned by `tenant_id`, indexed on `(tenant_id, occurred_at, event_kind)`. Queryable via api-gateway with tenant-scoped auth.
- **Market calendar**: `platform.market_is_open`, `platform.next_close`, `platform.is_expiry`. Single source of truth. `PositionWorkflow` registers two timers at construction (EOD 15:55 ET; expiry 15:30 ET on 0DTE).
- **Event timestamp consistency**: each event carries `occurred_at` set by originator. Broker sets fill timestamps; sidecar sets `posted_at`; workflow sets `decided_at`. Mixed timestamp sources produced "FillReceived-before-OrderSubmitted" race conditions in a prior trading system; we avoid that here by ownership.
- **Observability**:
  - Micrometer meters in every Java service; exported to Prometheus via Spring Actuator `/actuator/prometheus`.
  - OpenTelemetry traces span sidecar → Temporal → workflow → activities. Trace context propagated via Temporal's interceptor and OTel context propagation in Python.
  - Per-service health endpoints (Spring Actuator `/actuator/health`).
  - Temporal UI as the workflow-history viewer; api-gateway exposes a `/positions` view for operator-facing status.
- **Retries**: per-Activity retry policies; non-retryable types (`InsufficientFundsError`, `InvalidContractError`, `AuthError`, `QuotaExceededError`, `KillSwitchActiveError`) short-circuit.
- **Rate limits**: per-broker worker concurrency caps (Alpaca 200/min, Tradier 120/min, IBKR ~50/sec but with separate order rate-limiting).
- **Live / paper separation**: separate Temporal task queues; CI guardrail blocks code paths that route paper to live.
- **PositionWorkflow versioning**: `Workflow.getVersion(...)` checkpoints around any logic that may change across orchestrator-svc redeploys (exit ladder math, trail evaluation). New code paths run only on new positions; existing positions complete on old logic.

## Repo layout

```
oh-my-tradeagent/
├── pom.xml                              # Maven root POM; modules: contract/java, services/*
├── contract/
│   ├── schemas/                         # JSON Schema source of truth
│   │   ├── copytrade-signal-payload.json
│   │   ├── order-intent.json
│   │   ├── strategy-config.json
│   │   ├── audit-event.json
│   │   └── ...
│   ├── java/                            # generated Java DTOs (Jackson)
│   │   ├── pom.xml
│   │   └── src/main/java/com/ohmytradeagent/contract/...
│   └── python/                          # generated pydantic models
│       ├── pyproject.toml
│       └── ohmytradeagent_contract/...
├── services/
│   ├── signal-source-discord/           # Python sidecar
│   │   ├── pyproject.toml
│   │   ├── Dockerfile
│   │   └── ohmytradeagent_sidecar/
│   │       ├── parser.py
│   │       ├── watcher.py
│   │       ├── emit.py                  # wraps temporal_client.start_workflow
│   │       └── test_parser.py
│   ├── orchestrator/                    # Java, Spring Boot, Temporal worker
│   │   ├── pom.xml
│   │   └── src/main/java/com/ohmytradeagent/orchestrator/
│   │       ├── workflows/               # CopytradeSignalWorkflow, PositionWorkflow, ...
│   │       ├── activities/              # workflow-local activities only
│   │       └── OrchestratorApplication.java
│   ├── risk/
│   ├── contract-resolver/
│   ├── exec-alpaca/
│   ├── exec-tradier/
│   ├── exec-ibkr/                       # later phase
│   ├── market-data/
│   ├── platform/
│   ├── audit/
│   └── api-gateway/
├── tenants/                             # admin-provisioned YAML
│   └── dev/
│       ├── tenant.yaml
│       └── strategies/
│           └── copytrade-v1.yaml        # author whitelist, partial fractions, TTLs, max positions, broker target
├── infra/
│   ├── docker-compose.yml               # temporal + postgres + redis + all services
│   ├── prometheus.yml
│   └── otel-collector.yaml
└── ci/
    ├── check_no_cross_service_imports.py
    ├── check_workflow_determinism.py
    ├── check_no_global_secrets.py
    └── check_contract_drift.py
```

**Maven multi-module**: root `pom.xml` declares `contract/java` and each `services/<svc>` as modules. Shared dependencies (Spring Boot, Temporal SDK, Micrometer, OTel, jOOQ) are managed via `<dependencyManagement>` in the root POM. Each Java service has its own `pom.xml` and `Dockerfile`.

Python pieces (`contract/python`, `services/signal-source-discord`) are not Maven modules; built separately via `uv` or `pip-tools` and their own `Dockerfile`. CI builds Maven + Python in parallel.

## Phased delivery

| Phase | Scope | Done when |
|---|---|---|
| **0. Skeleton + contract** | Maven root POM with `contract-java` module and a placeholder `audit` service. JSON Schema for `CopytradeSignalPayload` + `AuditEvent`. Generated Java DTOs + pydantic models. docker-compose with Temporal + Postgres + Redis + Prometheus + OTel collector. CI runs (Spotless / Checkstyle / `mvn verify` / contract round-trip). | `mvn verify` green; `docker compose up` brings up the stack; a hand-crafted `temporal workflow start CopytradeSignalWorkflow` on a placeholder workflow audit-logs the payload with `tenant_id=dev, strategy_id=copytrade-v1`. |
| **0b. Platform foundations** | `platform-svc` with `TenantRegistry`, `StrategyRegistry`, `SecretsResolver` (local-file backend), `QuotaTracker` (Redis skeleton — returns Allowed), `MarketCalendar`, `CapitalAllocator` (static `capital_weight`). Seed `tenants/dev/tenant.yaml` + `tenants/dev/strategies/copytrade-v1.yaml`. `audit-svc` append-only writes wired through jOOQ. CI guardrail `check_no_global_secrets.py` added. | Workflow can call `platform.strategy_get(tenant, strategy)` and receive `StrategyConfig` parsed from YAML; `SecretsResolver(tenant, "dummy")` returns the seed secret; `audit_log` writes a row to Postgres. CI blocks a PR that imports a credential outside `SecretsResolver`. |
| **1. Sidecar → Temporal** | Port `services/discord-copytrade` from oh-my-opentrade. Replace HTTP `emit.py` with Temporal Python SDK `client.start_workflow`. Workflow body is a no-op that audit-logs the payload. Bootstrap container preserved for one-time Discord login. Parser unit tests ported. | A Discord post triggers a workflow with `workflow_id = "t-dev/s-copytrade-v1/sig/<message_id>:<i>"`. Re-posting the same message produces `WorkflowAlreadyExists` (durable dedupe verified). Parser tests pass (40+ adversarial cases). |
| **2. Risk + contract resolver + exec paper (no exits yet)** | `risk-svc` with author whitelist + signal-age veto + killswitch read (returns false for now) + max-positions count via Temporal `listWorkflowExecutions`. `contract-resolver-svc` with OCC symbol generation + entry quote against chosen paper broker (Tradier sandbox recommended). `exec-svc-{broker}-paper` with `OrderIntentJournal` (jOOQ + Postgres) + place_order + cancel_order + get_order_status. Full BTO path wired in `CopytradeSignalWorkflow`. | A vetted BTO on Discord produces a paper option order with no duplicates under simulated `orchestrator-svc` crash + restart between journal write and broker call. Journal idempotency tests run in CI against `Testcontainers` Postgres. |
| **3. PositionWorkflow + STC exits + BTO TTL** | `PositionWorkflow` (Java) with `partial_exit` signal handler, EOD/expiry timers, signal-id dedupe set, in-flight exit guard. `BTOEntryTimerWorkflow`. `CopytradeSignalWorkflow` STC branch with `KeywordPartialMatcher` + position lookup + signal dispatch. | One paper position: BTO → filled (PositionWorkflow starts) → STC "half out" → 50% closes → STC "out" → remainder closes → workflow completes. `TestWorkflowEnvironment` E2E test green. EOD timer at simulated 15:55 ET force-flattens. |
| **4. Trailing exit (CHANDELIER_TRAIL)** | `market-data-svc` streaming option quotes via broker WS; `subscribe_premium` Activity that fires `chandelier_tick` Signals into `PositionWorkflow`. `PositionWorkflow.arm_chandelier` handler + tick evaluation. First-partial-arms logic in STC flow. | After arm via simulated STC, an injected quote sequence reaching peak then giving back `cfg.trail_giveback_pct` triggers a full exit. |
| **5. Kill switch + reconciliation + api-gateway** | `KillSwitchWorkflow` (daily Temporal Schedule) with `trip_killswitch` / `reset_killswitch` Updates, `risk_breach` cascade via `listWorkflowExecutions` + `signalWorkflow`. Auto-trip activity on daily-loss threshold. `ReconciliationWorkflow` (Temporal Schedule every 5 min + startup). `api-gateway` with `GET /positions`, `POST /killswitch/trip`, `POST /positions/:id/force-close`, `GET /audit`. | Tripping killswitch via REST halts new entries and force-closes open positions within 5s. Recon catches a manually-induced journal-broker mismatch and surfaces it via audit. |
| **6. Multi-tenant production** | `SecretsResolver` Vault / AWS-SM adapter. `QuotaTracker` Redis enforcement at scale (real broker call counters, per-tenant concurrent-position caps). Per-tenant audit-log queries. Second tenant onboarded via `tenants/<id>/`. CI guardrail expanded to enforce tenant scoping on every Activity. | A second tenant runs side-by-side on isolated broker creds and isolated audit; quota exhaustion produces clean `QuotaExceededError` halts; cross-tenant queries return empty. |
| **7. Live broker promotion** | `exec-svc-tradier-live` or `exec-svc-ibkr-live` wired with separate task queue; manual promotion flow (operator sign-off + N paper days of green metrics); per-strategy capital allocator producing live `contracts_per_signal`; live-only alerting (PagerDuty / Slack). | First tenant + strategy promoted under small-size constraints; live trade executes successfully; rollback path tested (revert `broker_target` in YAML; recon catches any in-flight live order). |

## Open questions

1. **Paper broker for options.** Tradier sandbox (recommended; full chains, OK fill simulation), Alpaca options paper (newer, less proven for sandbox), or IBKR paper (best fidelity, painful onboarding). Drives Phase 2 broker SDK choice.
2. **Discord session refresh cadence.** When does `storage_state.json` invalidate, and how do we alert on it before the next post?
3. **Reconciliation cadence.** 5 min default; do live options need 60s?
4. **Market-data dependency in v0.** Defer trailing to Phase 4 (no market-data on Phase 2-3 critical path), or include from Phase 3? Recommend deferring.
5. **PositionWorkflow versioning policy.** `Workflow.getVersion` at every change-point, vs blue/green orchestrator deploys waiting for positions to drain. Different tradeoffs; both supported by Temporal.
6. **Audit-log retention.** Per-tenant configurable or single platform default; 30 / 90 / 365 days.
7. **Quota policy defaults.** Initial values for broker calls/min, concurrent positions, concurrent workflows per tenant.
8. **Sizing policy v0.** Static `contracts_per_signal` per strategy (simpler), or capital-weight-derived (`qty = floor(allocation / (price * 100))`, closer to the reference). Recommend static for Phase 2-3, capital-weight by Phase 6.
9. **OCC-symbol generation source of truth.** Build deterministically from `(ticker, expiry, strike, right)` in `contract-resolver-svc`, vs query broker's list-contracts endpoint. Recommend deterministic + cross-check against broker.
10. **AVG handling.** Reference skips by default (`skip_avg=true`). Same here unless overridden per strategy.
11. **Same-author re-BTO on a held contract.** Reference treats Pending/Open as a separate position. Same here, or merge into existing `PositionWorkflow`?
12. **Hot-reload of StrategyConfig.** v0 requires restart; v1+ may add a `platform.strategy_subscribe` push channel.
13. **Listing running workflows by prefix.** Temporal supports `listWorkflowExecutions` with a search-attribute filter. Phase 2 needs `TenantId` + `StrategyId` as search attributes registered on the cluster — confirm cluster supports custom search attributes (Temporal Cloud yes; self-hosted requires Elasticsearch).

## Prior art

- **`oh-my-opentrade/services/discord-copytrade`** — Python sidecar pattern; we copy parser + DOM extraction + state-dir layout verbatim.
- **`oh-my-opentrade/backend/internal/app/strategy/builtin/copytrade_v1.go`** — Go strategy with partial-fractions table, BTO TTL sweep, CHANDELIER_TRAIL arm, full-close tolerance. Workflow logic in our `CopytradeSignalWorkflow` + `PositionWorkflow` is structurally equivalent.
- **`oh-my-opentrade/backend/internal/domain/order_intent_journal.go`** — write-ahead log pattern; we port to jOOQ + Postgres per `exec-svc`.
- **`oh-my-opentrade/backend/internal/adapters/http/copytrade_handler.go`** — the HTTP shared-secret + dedupe-map pattern this design *replaces* (Temporal `WorkflowIDReusePolicy=REJECT_DUPLICATE` collapses both into one mechanism).
- **Temporal Java SDK samples** — `temporal-java-samples` repo; canonical pattern for `@WorkflowInterface` + `@SignalMethod` + `@QueryMethod` + `@UpdateMethod` and `TestWorkflowEnvironment`-based testing.
