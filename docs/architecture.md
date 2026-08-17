# oh-my-tradeagent — Architecture

Three views of what's built today on `main`, cross-referenced against `docs/prd/PRD.md`,
the Temporal code under `services/orchestrator/`, the broker adapters under `services/exec/`,
the schemas under `contract/`, and the k8s manifests under `infra/k8s/`.

- **System view** — components, languages, and the data/control edges between them.
- **Temporal view** — workflows, activities, signals, updates, and the queues they run on.
- **Deployment view** — how the system lands on the homelab k3s cluster.

---

## 1. System architecture

```mermaid
flowchart LR
    %% =========== External sources ===========
    subgraph EXT["External"]
        DISCORD["Discord channel<br/>(vetted authors)"]
        ALPACA["Alpaca REST<br/>paper / live"]
        OPERATOR["Operator<br/>(humans)"]
    end

    %% =========== Sidecar ===========
    subgraph SIDECAR["Signal sidecar (Python 3.12)"]
        SS["signal-source-discord<br/>Playwright + Temporal client"]
    end

    %% =========== Core platform ===========
    subgraph CORE["Java 21 / Spring Boot 3.4 / Temporal SDK 1.27"]
        ORCH["orchestrator-svc<br/>workflows + most activities"]
        EXEC["exec-svc<br/>per (broker, env) deployment<br/>OrderIntentJournal"]
        MD["market-data-svc<br/>premium-stream activity"]
        API["api-gateway-svc<br/>REST: audit / positions /<br/>killswitch / promotion"]
        AUDIT["audit-svc (CLI / CronJob)<br/>Phase 7 ledger derivation"]
    end

    %% =========== Stateful backends ===========
    subgraph DATA["State"]
        TEMPORAL[("Temporal server 1.27<br/>workflow history")]
        PG[("Postgres 16<br/>orchestrator + exec_*<br/>+ temporal schemas")]
        REDIS[("Redis<br/>positionWorkflowId cache")]
    end

    %% =========== Contracts ===========
    subgraph CONTRACT["contract/ (cross-language)"]
        SCHEMA["JSON Schemas<br/>→ Java DTOs (jsonschema2pojo)<br/>→ Python DTOs"]
    end

    %% =========== Tenants ===========
    subgraph TENANTS["tenants/"]
        TYAML["dev/tenant.yaml<br/>strategies/copytrade-v1.yaml<br/>(ConfigMap-mounted)"]
    end

    %% =========== Telemetry ===========
    subgraph TELEM["Observability"]
        PROM["Prometheus"]
        OTEL["OTel Collector"]
    end

    %% ----- edges -----
    DISCORD -- "rendered messages" --> SS
    SS -- "startWorkflow<br/>CopytradeSignalPayload" --> TEMPORAL

    TEMPORAL <-- "poll / complete tasks" --> ORCH
    TEMPORAL <-- "poll exec_<broker>_<env>" --> EXEC
    TEMPORAL <-- "poll market_data" --> MD

    ORCH -- "jOOQ: audit_log,<br/>option_symbol_cache" --> PG
    EXEC -- "jOOQ: order_intent_journal" --> PG
    ORCH -- "positionWorkflowId" --> REDIS

    EXEC -- "place / cancel / status (REST)" --> ALPACA
    MD -- "quotes (REST/stream)" --> ALPACA

    API -- "client.listWorkflows / Update / Query" --> TEMPORAL
    API -- "read audit_log (jOOQ)" --> PG
    AUDIT -- "scan audit_log" --> PG
    OPERATOR -- "HTTP" --> API

    TYAML -. "loaded at startup" .-> ORCH
    SCHEMA -. "compile-time" .-> ORCH
    SCHEMA -. "compile-time" .-> EXEC
    SCHEMA -. "compile-time" .-> MD
    SCHEMA -. "runtime" .-> SS

    ORCH -- "metrics / traces" --> OTEL
    EXEC -- "metrics / traces" --> OTEL
    MD -- "metrics / traces" --> OTEL
    API -- "metrics / traces" --> OTEL
    OTEL --> PROM

    classDef ext fill:#f5e6d3,stroke:#a86b2e,color:#000;
    classDef svc fill:#dde7ff,stroke:#3554a8,color:#000;
    classDef data fill:#e2f5e2,stroke:#2e7a3a,color:#000;
    classDef cfg  fill:#fff4c2,stroke:#a8881e,color:#000;
    class DISCORD,ALPACA,OPERATOR ext;
    class SS,ORCH,EXEC,MD,API,AUDIT svc;
    class TEMPORAL,PG,REDIS data;
    class SCHEMA,TYAML,PROM,OTEL cfg;
```

**Notes on the edges**

- The sidecar never calls a broker directly and never holds dedupe state for correctness —
  Temporal `WorkflowIDReusePolicy=REJECT_DUPLICATE` on `t-{tenant}/s-{strategy}/sig/{signal_id}` is
  the dedupe boundary (`docs/prd/PRD.md` §SignalDedupe).
- `orchestrator-svc` runs **most** activities (risk, audit, contract, strategy, market-calendar,
  daily-pnl, reconciliation-metrics, killswitch-cascade, live-promotion); `exec-svc` and
  `market-data-svc` host theirs on their own task queues and poll Temporal independently.
- `api-gateway-svc` carries no Temporal worker — it's a thin REST front for Temporal client +
  jOOQ reads on `audit_log`.

---

## 2. Temporal workflow view

```mermaid
flowchart TB
    %% ============ Triggers ============
    SIDECAR[/"Sidecar (Python)<br/>startWorkflow per parsed signal"/]
    BOOT[/"Orchestrator startup<br/>KillSwitchBootstrapper +<br/>ReconciliationScheduleBootstrapper"/]
    SCHED[/"Temporal Schedule<br/>every 5 min"/]
    OPS[/"api-gateway → Update / Query<br/>(forceClose, trip, reset, approve)"/]

    %% ============ Workflows ============
    subgraph WF["Workflows"]
        CSW["CopytradeSignalWorkflow<br/>id: t/{t}/s/{s}/sig/{signal_id}<br/>signals: onFill, riskBreach"]
        POS["PositionWorkflow<br/>id: t/{t}/s/{s}/pos/{contract}<br/>signals: onFill, partialExit,<br/>armChandelier, chandelierTick, riskBreach<br/>update: forceClose<br/>query: state"]
        KSW["KillSwitchWorkflow<br/>id: t/{t}/s/{s}/killswitch<br/>updates: trip, reset<br/>query: killswitchState"]
        REC["ReconciliationWorkflow<br/>id: t/{t}/s/{s}/recon/{broker}<br/>(scheduled, pure)"]
    end

    %% ============ Activities (grouped) ============
    subgraph ACT_ORCH["Activities on orchestrator task queue"]
        A_RISK["RiskActivities<br/>checkEntry, assertPreTradeCheckRoutable"]
        A_CONTRACT["ContractActivities.resolve"]
        A_STRAT["StrategyActivities.get"]
        A_CAL["MarketCalendarActivities"]
        A_AUDIT["AuditActivities.log"]
        A_PNL["DailyPnlActivities.computeRealizedPnl"]
        A_LOOK["PositionLookupActivities"]
        A_RECMET["ReconciliationMetricsActivities"]
        A_CASC["KillSwitchCascadeActivities.cascadeRiskBreach"]
        A_PROMO["LivePromotionActivities.approve"]
    end

    subgraph ACT_EXEC["exec_{broker}_{env} task queue"]
        A_EXEC["ExecActivities<br/>placeOrder, cancelOrder, getOrderStatus<br/>+ reconciliation list-open-orders"]
    end

    subgraph ACT_MD["market_data task queue"]
        A_MD["SubscribePremiumActivity"]
    end

    %% ============ Edges ============
    SIDECAR --> CSW
    BOOT --> KSW
    BOOT --> SCHED
    SCHED --> REC
    OPS -. "Update / Query" .-> POS
    OPS -. "Update / Query" .-> KSW
    OPS -. "Update" .-> CSW

    %% CSW flow
    CSW -->|"1. resolve contract"| A_CONTRACT
    CSW -->|"2. load strategy"| A_STRAT
    CSW -->|"3. pre-trade gates"| A_RISK
    A_RISK -. "queryKillswitchState" .-> KSW
    CSW -->|"4. audit"| A_AUDIT
    CSW -->|"5. BTO placeOrder"| A_EXEC
    A_EXEC -. "onFill signal" .-> CSW
    CSW -->|"6. start / find PositionWF"| A_LOOK
    A_LOOK -. "signal armChandelier" .-> POS
    CSW -->|"7. STC placeOrder<br/>on subsequent signal"| A_EXEC

    %% Position flow
    POS -->|"subscribe premium"| A_MD
    A_MD -. "chandelierTick signals" .-> POS
    POS -->|"partial / full STC"| A_EXEC
    A_EXEC -. "onFill signal" .-> POS
    POS -->|"EOD / expiry timer"| A_CAL
    POS -->|"audit every transition"| A_AUDIT

    %% Reconciliation
    REC -->|"dump journal"| A_EXEC
    REC -->|"list broker open orders"| A_EXEC
    REC -->|"orphan metrics"| A_RECMET

    %% KillSwitch cascade
    KSW -. "on trip" .-> A_CASC
    A_CASC -. "riskBreach signals" .-> POS
    A_CASC -. "riskBreach signals" .-> CSW

    %% Daily PnL
    POS -->|"on close"| A_PNL
    A_PNL -. "auto-trip if daily_loss ≥ threshold" .-> KSW

    %% Live promotion
    OPS -. "approve" .-> A_PROMO

    classDef wf fill:#dde7ff,stroke:#3554a8,color:#000;
    classDef act fill:#fff4c2,stroke:#a8881e,color:#000;
    classDef act2 fill:#f5d6e6,stroke:#a83576,color:#000;
    classDef act3 fill:#d6f5e6,stroke:#2e8a5a,color:#000;
    class CSW,POS,KSW,REC wf;
    class A_RISK,A_CONTRACT,A_STRAT,A_CAL,A_AUDIT,A_PNL,A_LOOK,A_RECMET,A_CASC,A_PROMO act;
    class A_EXEC act2;
    class A_MD act3;
```

**Key invariants the diagram encodes**

- `RiskActivities.checkEntry` reads `KillSwitchWorkflow` state via Query — it does not hold local
  kill-switch state. That's why the dotted line goes from `A_RISK` back to `KSW`.
- `exec-svc` is deployed once per `(broker, env)` and polls a queue named
  `exec_{broker}_{env}` (e.g. `exec_alpaca_paper`). The orchestrator picks the queue from
  `StrategyConfig.broker_target`, which is how a strategy gets routed paper-vs-live.
- `OrderIntentJournal` writes happen **before** the broker call inside the exec activity
  (`PRD.md` §OrderIntentJournal). The diagram simplifies that into one arrow.
- Trip cascade is async-by-design: `cascadeRiskBreach` fires `riskBreach` signals to every
  affected `PositionWorkflow` and `CopytradeSignalWorkflow`, which decide locally what to do.

---

## 3. Deployment topology (homelab k3s)

```mermaid
flowchart TB
    subgraph DEV["Developer workstation"]
        GH["GitHub Actions<br/>ci.yml / build-images.yml /<br/>deploy.yml / k8s-drift.yml"]
        REG[("Container registry")]
    end

    subgraph K3S["k3s single-node — ssh ridopark@192.168.10.123"]
        direction TB
        subgraph NS["namespace: copytrade"]
            direction TB
            INGRESS[["Traefik Ingress"]]

            subgraph APP["Application deployments"]
                D_ORCH["orchestrator (Deployment)"]
                D_EXEC_PAPER["exec-alpaca-paper (Deployment)<br/>env BROKER_TARGET=paper"]
                D_MD["market-data (Deployment)"]
                D_API["api-gateway (Deployment)"]
                D_SIG["signal-source-discord<br/>(Deployment + PVC)"]
            end

            subgraph STATE["Stateful"]
                S_PG[("postgres (StatefulSet)<br/>DBs: orchestrator,<br/>exec_alpaca_paper, ...,<br/>temporal, temporal_visibility")]
                S_RD[("redis (Deployment + Service)")]
            end

            subgraph TMP["Temporal cluster"]
                T_FE[["temporal-frontend"]]
                T_HIST[["temporal-history"]]
                T_MATCH[["temporal-matching"]]
                T_WK[["temporal-worker"]]
                T_UI[["temporal-ui"]]
            end

            subgraph CFG["ConfigMaps / Cron"]
                CM_TEN["40-tenants-config<br/>(mounted to orchestrator)"]
                CJ_AUDIT["audit-completeness-check<br/>(daily CronJob)"]
            end

            subgraph OBS["Observability"]
                O_PROM["prometheus"]
                O_OTEL["otel-collector"]
            end

            subgraph SA["RBAC"]
                CISA["ci-readonly ServiceAccount<br/>(used by GH Actions for drift)"]
            end
        end
    end

    subgraph EXTNET["LAN egress"]
        ALPACA_API[/"api.alpaca.markets<br/>(paper today, live gated)"/]
        DISCORD_API[/"discord.com<br/>(headless browser)"/]
    end

    GH -- "build & push" --> REG
    GH -- "kubectl apply" --> K3S
    REG -. "pull" .-> APP

    INGRESS --> D_API

    D_ORCH --> T_FE
    D_EXEC_PAPER --> T_FE
    D_MD --> T_FE
    D_API --> T_FE
    D_SIG --> T_FE

    T_FE --> T_HIST
    T_FE --> T_MATCH
    T_HIST --> S_PG
    T_MATCH --> S_PG
    T_WK --> S_PG
    T_UI --> T_FE

    D_ORCH --> S_PG
    D_ORCH --> S_RD
    D_EXEC_PAPER --> S_PG
    D_API --> S_PG
    CJ_AUDIT --> S_PG

    CM_TEN -. "/config/tenants" .-> D_ORCH

    D_EXEC_PAPER -- "REST" --> ALPACA_API
    D_MD -- "REST" --> ALPACA_API
    D_SIG -- "Playwright" --> DISCORD_API

    D_ORCH --> O_OTEL
    D_EXEC_PAPER --> O_OTEL
    D_MD --> O_OTEL
    D_API --> O_OTEL
    O_OTEL --> O_PROM

    GH -. "drift check (read-only)" .-> CISA

    classDef ext fill:#f5e6d3,stroke:#a86b2e,color:#000;
    classDef svc fill:#dde7ff,stroke:#3554a8,color:#000;
    classDef data fill:#e2f5e2,stroke:#2e7a3a,color:#000;
    classDef tmp fill:#f0d6ff,stroke:#7a3aa8,color:#000;
    classDef cfg fill:#fff4c2,stroke:#a8881e,color:#000;
    class ALPACA_API,DISCORD_API,GH,REG ext;
    class D_ORCH,D_EXEC_PAPER,D_MD,D_API,D_SIG svc;
    class S_PG,S_RD data;
    class T_FE,T_HIST,T_MATCH,T_WK,T_UI tmp;
    class CM_TEN,CJ_AUDIT,O_PROM,O_OTEL,CISA,INGRESS cfg;
```

**What this view omits intentionally**

- Only `exec-alpaca-paper` is drawn. The manifest set is structured so additional
  `(broker, env)` deployments (e.g. `exec-alpaca-live`, `exec-tradier-paper`) are added by
  copying `52-exec-alpaca-paper.yaml` and pointing it at a separate Postgres DB on the same
  StatefulSet. Live promotion is gated by `LivePromotionActivities.approve` (Phase 7).
- The Temporal-cluster boxes are drawn because Temporal is still the runtime topology, but it
  runs in the **`temporal` namespace**, not `copytrade`. The old in-`copytrade` manifests
  (`30-temporal.yaml`, `31-temporal-bootstrap.yaml`) were deprecated in Phase 5b.E and deleted
  on 2026-08-17; nothing in `infra/k8s/` creates a Temporal cluster today.
- Local-dev `infra/docker-compose.yml` is not shown; it mirrors the k8s view minus
  ingress + RBAC.

---

## Reading order for new contributors

1. `docs/prd/PRD.md` — why this shape, not a monolith.
2. Diagram §1 above — what services exist.
3. Diagram §2 — the workflow that actually executes a copytrade signal end-to-end.
4. `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/CopytradeSignalWorkflow.java` —
   the single best file to anchor §2 in real code.
5. Diagram §3 — how it lands on the homelab.
