# Agentic Trading Bot — Copytrade v0 PRD

> Copy-trade is the v0 product. The earlier multi-agent intraday engine is preserved in [`PRD-agentic-v2.md`](./PRD-agentic-v2.md) and remains the eventual direction.

## Problem Statement

Retail options traders who follow vetted Discord authors lose edge in three places:

1. **Latency to entry**: by the time a follower reads the BTO line, picks the contract, and submits, the author's price is gone — often by 5-15% on liquid options, more on illiquid.
2. **Missed or late exits**: STC partials are usually announced with vague language ("trimming half here", "out") that the human reader has to translate to a fraction; followers either over-hold (the most expensive failure mode) or oversell. Trailing-stop discipline gets dropped under pressure.
3. **Inconsistent sizing and risk**: position sizes drift by feel rather than rule; one bad fill cluster destroys the week's profits because there was no max-positions cap or daily-loss kill switch.

Existing copy-trade tooling is single-user, has no audit trail, no broker-credential isolation, and no way to run multiple followers (one per tenant or per strategy variant) on shared infrastructure. The proven reference for this pattern ([`oh-my-opentrade/services/discord-copytrade`](../oh-my-opentrade/services/discord-copytrade) + `copytrade_v1` strategy) is a Go monolith — fine for one operator, not for a SaaS shape.

## Solution

A Temporal-orchestrated microservice system that:

- **Watches** one vetted Discord channel via a Playwright sidecar (Python, mirroring the reference).
- **Parses** each BTO/STC/AVG line into a typed signal and starts a `CopytradeSignalWorkflow` keyed by `signal_id`. Temporal dedupes via `WorkflowIDReusePolicy=REJECT_DUPLICATE` — no shared-secret HTTP gateway, no in-memory dedupe map.
- **Enforces deterministic gates** (author whitelist, max positions, signal-age veto, kill switch, position-size cap) as in-process Activities in the orchestrator before any broker call.
- **Resolves the OCC contract symbol** and submits an idempotent option order through a broker-specific `exec-svc` worker, journaling the intent in Postgres **before** the broker API is hit so crash recovery has a single source of truth.
- **Manages the position lifecycle** in a durable `PositionWorkflow` (one per open position) that converts subsequent STC signals to partial/full closes via Temporal Signals, arms a CHANDELIER_TRAIL on first partial, and force-flattens at EOD / option expiry.
- **Isolates tenants** at the workflow ID prefix and at every Activity boundary: `(tenant_id, strategy_id)` is required on every payload, every audit event, every credential resolve.
- **Audits everything**: append-only event log per tenant, queryable via api-gateway.

The system is **durable** (Temporal replays workflows after crashes), **idempotent** (3-layer dedupe: Temporal `workflow_id` → `OrderIntentJournal` → broker `client_order_id`; sidecar in-memory LRU is a cost-only optimization, not a correctness layer), **explainable** (every order traces back through workflow history to a specific Discord line + author), and **operator-overridable** (`force_close` Update with dual-control on live, `trip_killswitch` Update, `pause_entries` Update, dual-control `reset_killswitch` Update).

## User Stories

1. As an operator, I want a vetted Discord author's BTO post to fire a paper option order on my account within 5 seconds of the post, so that I don't lose entry edge.
2. As an operator, I want only authors on my whitelist to produce orders, so that a guest poster cannot accidentally trigger a trade.
3. As an operator, I want each BTO to specify exact `(ticker, expiry, strike, right, limit)` resolved from the post, with no LLM in the contract-resolution path, so that fabricated prices are physically impossible.
4. As an operator, I want STC posts with "half out" / "trim" / "out" keywords to close 50% / 33% / 100% of the remaining position (with a configurable keyword→fraction table), so that the bot mirrors author intent precisely.
5. As an operator, I want every BTO to expire after a configurable TTL (e.g. 90s paper, 30s live) and cancel any un-filled portion, so that stale entry attempts don't sit in the book.
6. As an operator, I want the first STC partial on a position to optionally arm a CHANDELIER_TRAIL on the remaining quantity, so that I capture more on runners without depending on the author posting every exit.
7. As an operator, I want a per-`(tenant, strategy)` kill switch that I can trip manually or that auto-trips on daily realized loss ≥ threshold, so that I have one button for "halt now."
8. As an operator, I want every open position force-closed by 15:55 ET (or 15:30 ET on 0DTE expiry) regardless of author activity, so that I never carry uncontrolled risk into close.
9. As an operator, I want the system to refuse any signal older than its per-side cap — `max_signal_age_bto_secs` (default 30) for BTO/AVG and `max_signal_age_stc_secs` (default 60) for STC — so that a sidecar restart doesn't replay stale posts and so that BTO doesn't fill into adverse selection on 0DTE / near-term contracts whose premium can move 50-80% in 30 minutes. Any value above 120s on either field is an explicit per-strategy override. Issue #3 (Phase 2a hardening) replaced the previous unified `max_signal_age_secs` (default 1800) with these per-side defaults; the legacy field remains in the contract only for backward-compatible deserialization of older audit records.
10. As an operator, I want every parsed signal, risk decision, order intent, broker order, fill, and exit recorded in a tenant-scoped audit log, so that I can review or prove what the bot did.
11. As an operator, I want to query any running `PositionWorkflow` for current state (size, avg cost, in-flight exits, last signal), so that I can audit decisions in real time.
12a. As an operator, I want to force-close a specific position via a `force_close` Update on its `PositionWorkflow`, with two-operator approval on live broker targets, so that I can override the bot without taking it offline.
12b. As an operator, I want to pause new entries on a `(tenant, strategy)` without force-closing existing positions via a `pause_entries` Update on `KillSwitchWorkflow`, so that I can stop the bleed mid-day while letting in-flight winners run their STC/trail plan.
13. As an operator, I want broker credentials resolved per-tenant via `SecretsResolver`, so that no code path can accidentally load a global key.
14. As an operator, I want orders idempotent at three layers (Temporal `workflow_id` REJECT_DUPLICATE, `OrderIntentJournal`, broker `client_order_id`), so that crashes between any two of them cannot double-fill. The sidecar's in-memory LRU is a cost optimization, not a correctness layer, so the sidecar can run with replica >= 1 for HA.
15. As an operator, I want `ReconciliationWorkflow` to run on worker startup and every 5 minutes, comparing `OrderIntentJournal` to broker open orders, so that orphan orders (journal-no-broker, broker-no-journal, filled-but-not-acked) are surfaced quickly.
16. As an operator, I want to add a second tenant without touching code, so that onboarding is mechanical.
17. As an operator, I want per-tenant broker-call and concurrent-position quotas enforced by `QuotaTracker`, so that one tenant cannot starve another. (LLM-token slots exist in the contract for the future agentic phase; unused in v0.)
18. As an operator, I want paper trading as the default and live broker promotion to require explicit operator sign-off, so that I cannot accidentally route an experiment to my real account.
19. As an operator, I want the partial-fraction keyword table and author whitelist to live in per-strategy YAML, so that I can iterate without a redeploy. (Hot-reload of strategy config is a future enhancement.)
20. As a developer, I want every Activity input typed as a Pydantic / Java DTO generated from a shared JSON Schema, so that cross-language drift is caught in CI.
21. As a developer, I want `OrderIntentJournal`, `MarketCalendar`, `SecretsResolver`, `TenantRegistry`, `StrategyRegistry`, `QuotaTracker`, `KeywordPartialMatcher`, and `AuditLogger` each as deep modules behind small interfaces, so that internal complexity stays out of workflow code.
22. As a developer, I want the orchestrator's workflow code free of language-time I/O calls (no `System.currentTimeMillis()`, no `new Random()`, no `java.io.*` / `java.net.*` imports), validated by a CI AST scan, so that determinism is mechanical.

## Implementation Decisions

### Stack

- **Java 21 LTS** for all backend services; **Spring Boot 3** application framework; **Maven** multi-module build; **jOOQ** for type-safe SQL.
- **Python 3.12** for the Discord sidecar (Playwright maturity).
- **Temporal Java SDK** for orchestrator and activity workers; **Temporal Python SDK** for the sidecar's `start_workflow` client.
- **Postgres** for `OrderIntentJournal`, `TenantRegistry`, `StrategyRegistry`, `AuditLogger`. **Redis** for `QuotaTracker` counters and short-TTL state.
- **Vault** or **AWS Secrets Manager** behind a `SecretsResolver` Activity interface; local-file fallback in dev only.
- **Micrometer + Prometheus + OpenTelemetry** for metrics + tracing across all Java services; Python sidecar emits OTel via OpenTelemetry Python SDK.

### Deep modules (extracted for isolated testability)

**Parser** (Python, sidecar-local) — the per-line regex from the reference:

```
(BTO|STC|AVG) TICKER M/D[/YY] STRIKE(C|P) [@] PRICE [tail]
```

Returns `list[ParsedSignal]`. Unit-tested against 40+ adversarial cases ported from `oh-my-opentrade/services/discord-copytrade/test_parser.py`.

**SignalDedupe** — replaced. The reference's in-process map (`backend/internal/adapters/http/copytrade_handler.go`) is gone; Temporal `WorkflowIDReusePolicy=REJECT_DUPLICATE` on `workflow_id = "t-{tenant}/s-{strategy}/sig/{signal_id}"` is the dedupe, durable across all process restarts.

**OrderIntentJournal** (Java, jOOQ; one table per `exec-svc` deployment):

```
record_intent(intent: OrderIntent) -> void          # called BEFORE broker API
place_with_intent(intent: OrderIntent) -> BrokerOrderId
mark_filled(intent_id, fill: Fill) -> void
mark_failed(intent_id, error) -> void
reconcile(broker_open_orders: list[BrokerOrder]) -> list[Discrepancy]
```

Lifted from the reference (`oh-my-opentrade/backend/internal/domain/order_intent_journal.go`). Each broker's `exec-svc` owns its own journal table; `client_order_id` (Alpaca) or side-mapped UUID (Tradier/IBKR) maps to the intent.

**MarketCalendar** (Java, `platform-svc`) — `is_open(ts)`, `next_close(ts)`, `next_open(ts)`, `is_expiry(ts, contract)`. NYSE holidays, half-days, DST, year-end. Single source of truth for every EOD timer.

**KeywordPartialMatcher** (Java, `orchestrator-svc`) — pure function:

```
match(tail: String, table: List<KeywordFraction>) -> Optional<Fraction>
```

Longest-keyword-first matching so "all out" wins over "out". Table is per-strategy YAML, sorted at config load. Logic identical to the reference's `parsePartialFractions` + matcher loop.

**TenantRegistry / StrategyRegistry / SecretsResolver / QuotaTracker / CapitalAllocator / AuditLogger** — scoped to copy-trade's needs:

- `TenantRegistry.get(tenant_id) -> TenantConfig`
- `StrategyRegistry.get(tenant_id, strategy_id) -> StrategyConfig { author_whitelist, partial_fractions, default_stc_fraction, max_positions, pending_ttl_paper_secs, pending_ttl_live_secs, trail_on_partial, trail_giveback_pct, max_signal_age_bto_secs, max_signal_age_stc_secs, bto_price_move_reject_pct, broker_target, contracts_per_signal }`
- `SecretsResolver.resolve(tenant_id, secret_name) -> Secret`
- `QuotaTracker.try_consume(tenant_id, resource, amount) -> Allowed | Throttled`
- `CapitalAllocator.allocate(tenant_id, balance) -> Map<StrategyId, AllocatedCapital>` (static `capital_weight` for v0)
- `AuditLogger.log(tenant_id, event) -> void`

### Service inventory (6 Java + 1 Python sidecar)

| Service | Language | Responsibility |
|---|---|---|
| `signal-source-discord` | Python | Playwright DOM watcher, parser, Temporal client (`start_workflow`). Replica >= 1 per `(tenant, strategy, channel)`; in-memory dedupe LRU is cost-only. |
| `orchestrator-svc` | Java | All Temporal workflows + in-process Activities (risk gates, contract resolution, keyword matcher). |
| `exec-svc-{alpaca,tradier,ibkr}` | Java | One per broker target; wraps `OrderIntentJournal`; runs on broker-specific task queue. |
| `market-data-svc` | Java | Streaming option quotes for `PositionWorkflow.arm_chandelier`. |
| `platform-svc` | Java | `TenantRegistry`, `StrategyRegistry`, `SecretsResolver`, `QuotaTracker`, `CapitalAllocator`, `MarketCalendar` Activities. |
| `audit-svc` | Java | Append-only event log per `(tenant, strategy)`. |
| `api-gateway` | Java | Operator REST: kill switch, force close, status, audit query. Translates HTTP to Temporal Signals/Queries/Updates. |

Risk gates and contract resolution were originally split into `risk-svc` and `contract-resolver-svc`. Both were stateless except for a single Postgres cache and added a task-queue hop per invocation; they were folded into `orchestrator-svc` after architect review (Issue #8). The Java packages stay separate so they can be promoted to standalone services later if real load asymmetry appears.

### Architectural decisions

- **Workflow-as-orchestrator, not service-call-graph orchestrator.** Sequencing lives in Temporal workflow code (Java), not in distributed event sagas or HTTP chains. The Risk → Place gate is in workflow code; a misbehaving service cannot bypass it.
- **No LLM in copy-trade hot path.** The reference is fully deterministic; we keep it that way. LLM slots exist in `StrategyConfig` for the future agentic phase; unset in v0.
- **Polyglot at the Temporal boundary.** Sidecar (Python) and orchestrator (Java) communicate via JSON-over-Temporal-wire, with DTOs generated from `contract/schemas/*.json` for both languages. No HTTP between sidecar and orchestrator.
- **Idempotency in 3 layers** (Temporal `workflow_id` REJECT_DUPLICATE, `OrderIntentJournal`, broker `client_order_id`) because no single layer covers all crash points. The sidecar's in-memory LRU is an optional cost optimization, not a correctness layer; sidecars run with replica >= 1.
- **Advanced Visibility + custom Search Attributes** on the Temporal cluster from Phase 0. `TenantStrategy` (Keyword) on every workflow; `ContractSymbol` (Keyword) on `PositionWorkflow`. All cross-workflow listing (max-positions, killswitch fan-out, STC dispatch, operator REST) uses these SAs; `WorkflowId STARTS_WITH` is not used because Temporal's SQL Visibility does not support it on the system `WorkflowId` field.
- **Workflow-input schema versioning is explicit.** Every workflow-input DTO carries a `schema_version` integer field. Workers reject inputs whose `schema_version` is newer than their build, forcing rollback rather than ambiguous replay.
- **Paper as default; live promotion requires manual approval.** Paper and live use separate Temporal task queues so a misconfigured broker target routes to a queue with no worker, not to the wrong account.
- **Pinned broker SDK versions and contract schema versions.** Bumping either is a deliberate operation, not a passive drift.
- **PositionWorkflow versioning is explicit.** `Workflow.getVersion(...)` is wrapped around every change-point in long-lived workflows so in-flight positions complete on the code they started with.

### Schema decisions

- Every signal, decision, order intent, fill, exit, audit event has a Pydantic/Java DTO generated from JSON Schema in `contract/schemas/`.
- Every workflow-input DTO carries an integer `schema_version` field. Workers compare to their compiled-in version and reject newer inputs (forces orchestrator-svc rollback) rather than guessing through replay.
- Non-retryable error types are typed (`InsufficientFundsError`, `InvalidContractError`, `AuthError`, `QuotaExceededError`, `KillSwitchActiveError`, `KillSwitchUnavailableError`) so Temporal retry policies act on them precisely.
- Time-sensitive fields (`posted_at`, `submitted_at`, `filled_at`, `decided_at`) are RFC3339 UTC with explicit source-of-truth ownership: broker sets fill timestamps, sidecar sets `posted_at` from Discord DOM, workflow sets `decided_at`.

## Testing Decisions

A good test exercises **external behavior, not implementation details**.

### Required test coverage (v0 ships without these is a no-go)

| Module / behavior | What to test |
|---|---|
| **Parser** (Python, sidecar) | 40+ fixtures ported from reference `test_parser.py`; adversarial inputs (whitespace, case, missing @, extra tokens, multi-line); year wrap-around on `M/D` without year. |
| **Signal dedupe** | Temporal `WorkflowIDReusePolicy=REJECT` on duplicate `signal_id` returns `WorkflowExecutionAlreadyStartedFailure`; first start succeeds and audits. Same `signal_id` reposted after sidecar restart still dedupes. |
| **Risk gates** | Each gate accepts conforming proposals and rejects breaching ones; combined firing returns highest-priority reason; author not in whitelist returns `Rejected{reason='author_not_allowed'}`; BTO/AVG older than `max_signal_age_bto_secs` and STC older than `max_signal_age_stc_secs` rejected with `SIGNAL_TOO_OLD`; BTO whose live bid/ask (mid) has moved more than `bto_price_move_reject_pct` from `payload.price` since `posted_at` rejected with `BTO_PRICE_MOVED` regardless of age (Issue #3 secondary gate; market-data quote fetch lands separately). |
| **OrderIntentJournal** | `record_intent` + simulated crash before `place_with_intent` → reconciliation surfaces orphan; `record_intent` + successful submit + crash + restart → same `idempotency_key` does not double-submit. |
| **PositionWorkflow lifecycle** | BTO fill → position open; STC partial signal → partial close + remaining qty correct; second STC → full close when remaining < 0.5%; EOD timer at 15:55 ET force-closes; option-expiry timer force-closes 0DTE positions at 15:30 ET. |
| **CHANDELIER_TRAIL arm + fire** | First partial STC arms with `peak_premium = author_ref`; injected quote tick at `peak * (1 - giveback_pct)` fires full exit. |
| **Kill switch** | Trip → new `CopytradeSignalWorkflow`s reject in risk check; all `PositionWorkflow`s receive `risk_breach` Signal and force-close; reset restores normal flow. |
| **Reconciliation** | Journal-no-broker → retry or audit-and-expire; broker-no-journal → page operator (no auto-cancel); fill-not-journaled → signal `PositionWorkflow.reconcile_orphan`. |
| **Keyword matcher** | "all out" matches before "out"; partial fractions sorted longest-first; missing keyword falls back to `default_stc_fraction`. |
| **Multi-tenant isolation** | Workflow ID prefix enforced; Activity reading credentials without `SecretsResolver(tenant_id)` fails CI guardrail. **Runtime isolation (Issue #20 — CI grep alone is theatrical):** (a) Postgres RLS enabled on all tenant-scoped tables, with per-tenant DB roles assumed inside `SecretsResolver`; a cross-tenant `SELECT` issued under tenant A's DB role against a tenant B row returns zero rows because the database refuses to read it (RLS-blocked at the engine, not application-filtered). (b) `TenantContext` middleware in the Java common module wraps every Activity entry point and binds the active `tenant_id`; a query that touches a row outside the bound `tenant_id` fails the Activity and emits a `tenant_context_violation` audit event — that assertion failure is the tripwire, not the CI grep. (c) Phase 6 ships with one-tenant-per-exec-worker pools (see PLAN §794) so cross-tenant blast radius is bounded by deployment topology. |
| **E2E paper session in `TestWorkflowEnvironment`** | A fixture posts a sequence of BTO + partial STCs + full STC against a `SimBroker` Activity; assert no double-fill, all positions flat by close, audit log has expected event sequence. |

### Light testing (covered indirectly)

| Module | Reason |
|---|---|
| Sidecar Playwright DOM extraction | Best validated by integration tests against a snapshot HTML fixture; selectors will drift with Discord rewrites and are not stable unit-test surfaces. |
| Spring Boot wiring | Tested via the E2E session; per-controller unit tests would duplicate Activity contract tests. |
| jOOQ queries | Tested by `OrderIntentJournal` + `AuditLogger` integration tests against a real Postgres in `Testcontainers`. |

### Test conventions

- Workflow + E2E tests use Temporal's `TestWorkflowEnvironment` for time-skipping and Activity mocking — the same primitive the production code uses for replay.
- Cross-language contract drift is caught by a CI step that round-trips fixtures through Python and Java bindings.
- Deep-module unit tests run in milliseconds and require no Temporal, no broker, no network.
- Integration tests use `Testcontainers` for Postgres + Redis + Temporal dev server.

## Out of Scope

For v0, the following are explicit non-goals:

- **Multi-agent intraday pipeline** (Technical/Sentiment/Strategy/Risk/Execution/Reflection). Preserved in [`PRD-agentic-v2.md`](./PRD-agentic-v2.md) as the eventual direction; not built in v0.
- **Universe scan, watchlist, daily reflection memory.** Same archive.
- **Backtest stability metrics, multi-seed runs, SimBroker live-parity tests.** Agentic-phase concerns; v0 includes only `Testcontainers` integration tests and a basic `SimBroker` Activity for `TestWorkflowEnvironment` use.
- **Equities-only intraday strategies.** v0 is options copy-trade from Discord.
- **Non-Discord sources** (Twitter, Telegram, broker copy-trade APIs). A pluggable source contract is a future enhancement; v0 is Discord-only by deployment shape (one sidecar container per source).
- **Self-serve tenant signup / billing UI.** Admin-provisioned YAML in `tenants/<id>/`.
- **Cross-tenant strategy marketplace.**
- **Hot-reload of `StrategyConfig`.** v0 requires a sidecar/orchestrator restart to pick up new whitelist or fraction table.
- **Authoring custom workflows per strategy.** v0 ships one workflow set for `copytrade_v1`; the `kind: custom_workflows` slot from the agentic plan stays in `StrategyConfig` but is rejected by the orchestrator.
- **Multi-leg options strategies** (spreads, condors, butterflies). Single-leg calls and puts only.

## Further Notes

### Idempotency is treated as financial risk

Double-firing an option order in copy-trade is uniquely expensive: options are leveraged, sizes are small, and a duplicate can flip the position from "what the author posted" to "double-sized losing trade I didn't intend." The 3-layer idempotency is not paranoia; it accounts for the fact that any single layer has a non-zero failure mode under crash + retry. Each layer was added because the layer below it was insufficient to cover a specific real-world failure mode in the reference system. The reference used a fourth, sidecar-local `seen_ids.json` layer, but the architect review (Issue #10) found it redundant once Temporal's durable `WorkflowIDReusePolicy=REJECT_DUPLICATE` is in place and showed it forced the sidecar to be a singleton — we keep an in-memory LRU as a cost-only optimization so the sidecar can run replicated.

### Why Temporal, specifically

The reference is a Go monolith with an in-process event bus and an in-memory dedupe map. Splitting it into services without Temporal would require either Kafka + sagas + an outbox table (which we'd build by hand) or HTTP retries + a custom state DB. Temporal collapses durable workflow state, retry policy, signals/queries/updates, schedules, and deduplication into one engine. The cost is one extra cluster (Temporal + its Postgres) to operate; the benefit is removing approximately five custom systems we'd otherwise build.

### Why Java as the primary language

The strongest argument is broker fit: IBKR's TWS API is canonically Java + C++, and IBKR is the most viable option-trading broker for live deployment. Tradier and Alpaca have Java SDKs that work well. Temporal Java SDK is first-class (Uber, Stripe, etc. run heavy Temporal workloads on it). Spring Boot 3 + jOOQ + Micrometer + OTel is a well-trodden stack with strong tooling. Polyglot stays narrow: Python for Playwright (where the Python port is materially more mature than Java's), everything else Java.

### Multi-tenant from day 0

`(tenant_id, strategy_id)` is on every workflow input, every Activity payload, every audit event from Phase 0. Retrofitting tenant scoping into a single-tenant system is famously painful; the slots exist before the code that consumes them does. v0 ships with one tenant (`dev`) and one strategy (`copytrade-v1`) in YAML; onboarding a second tenant is a config change, not a code change.

### Open product questions (capture, do not block)

- Paper broker for options: Tradier sandbox (recommended; full chains, OK fill simulation), Alpaca options paper (newer, less proven), or IBKR paper (best fidelity, painful setup). Drives Phase 2 broker SDK choice.
- Discord auth: `storage_state.json` refresh cadence; alerting when session invalidates.
- Reconciliation cadence: 5 min default; live options may want 60s.
- Market-data dependency for v0: defer CHANDELIER_TRAIL to Phase 4 (no market-data on critical path Phases 2-3) or include from Phase 3?
- `PositionWorkflow` versioning policy: `Workflow.getVersion` at every change-point, vs blue/green orchestrator deploys waiting for positions to drain.
- Audit-log retention: per-tenant configurable or platform default; 30/90/365 days.
- Quota policy defaults: initial values for broker calls/min, concurrent positions, concurrent workflows per tenant.
- Sizing policy v0: static `contracts_per_signal` per strategy, or capital-weight-derived (`qty = floor(allocation / (price * 100))`). Static is simpler; capital-weight is closer to the reference.
- OCC-symbol generation source of truth: build from `(ticker, expiry, strike, right)` deterministically in the orchestrator's in-process `contract.resolve` Activity, vs query broker. Recommend deterministic + cross-check against broker's list-contracts endpoint.
- AVG handling: reference skips by default (`skip_avg=true`). Same here unless overridden per strategy.
- Same-author re-BTO on a held contract: reference treats `Pending`/`Open` as a separate position. Same here, or merge?
- Hot-reload of `StrategyConfig`: v0 requires restart; v1+ may add a `platform.strategy_subscribe` push channel.
