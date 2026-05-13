# Agentic Trading Bot — PRD

> Generated using the [mattpocock `to-prd`](https://github.com/mattpocock/skills) skill (MIT). Implementation details in [`PLAN-agentic-v2.md`](../plans/PLAN-agentic-v2.md).

## Problem Statement

Two problems, both unsolved by today's tooling:

**For individual operators**: a retail intraday trader has to do four jobs simultaneously during the session: (1) morning prep — scan for gappers, news, technical setups (~30 min); (2) live monitoring — track 5–15 candidates across multiple timeframes during 09:30–16:00 ET; (3) risk discipline — size correctly, place stops, force exits on bad days, never carry overnight; (4) end-of-day review. Doing all four manually doesn't scale and is emotionally noisy. Rule-only bots can't reason about news or catalysts. Pure-LLM bots hallucinate prices and lack durability.

**For a platform serving many operators**: each operator needs the full daily loop running on **their** account with **their** chosen strategy mix, fully isolated from every other operator's data, credentials, kill switch, and P&L. Existing trading tooling is either single-user desktop software or proprietary fund infrastructure; nothing slots cleanly into a multi-tenant SaaS shape where strategies are pluggable and tenants are isolated.

This product solves both: a single agentic engine that any operator can plug into, where each operator runs one or more strategies on their own account, and the engine guarantees data, credential, and risk isolation between tenants.

## Solution

A multi-tenant agentic engine where each operator (tenant) runs one or more strategies through the full daily loop:

- **Pre-market**: per `(tenant, strategy)` — produces a ranked watchlist of 5–15 candidates with theses, sourced catalysts, and entry hints, scoped to that strategy's universe and rules.
- **Session**: monitors each candidate, triggers entries through a multi-agent pipeline (Technical + Sentiment → Strategy → Risk → Execution), and manages each position to a forced close before market close.
- **Post-close**: produces a structured daily reflection per `(tenant, strategy)` that feeds the next day's agents as context.

The system is **durable** (resumes from worker crashes without duplicate orders), **explainable** (every decision is traceable to typed agent outputs and sourced data), **safe** (bright-line gates enforce position sizing, spread quality, kill switch, and end-of-day flat in deterministic code the LLM cannot bypass), and **isolated** (every tenant's data, credentials, quotas, kill switch, P&L, and memory are scoped by `tenant_id`; no code path can read across tenants).

**Strategy shape**: most strategies are *configuration variants* of the same six workflows — different agent prompt overrides, risk parameters, universe filters, entry-trigger rules. When a strategy genuinely needs different orchestration (e.g., ORB vs mean-reversion), it can override individual workflows; this is the rare path.

Each operator interacts with running workflows via **queries** (current reasoning, positions, watchlist), **signals** (price / news / risk events), and **updates** (force-close, adjust-stop, kill-switch) — all scoped to their tenant.

## User Stories

1. As an operator, I want a ranked daily watchlist by 09:00 ET, so that I don't have to do morning prep manually.
2. As an operator, I want each watchlist candidate to include a thesis with cited sources, so that I can audit the bot's reasoning before it trades.
3. As an operator, I want the bot to monitor candidates continuously through 09:30–16:00 ET, so that I'm not pinned to a screen during the session.
3a. As an operator, I want decisions made on 5-minute and 15-minute candles (and longer — 30m, 1h within reason) only — never 1-minute — so that LLM latency is comfortably below the decision interval and the engine isn't pretending to be HFT.
4. As an operator, I want entries to fire only after Technical AND Sentiment AND Strategy AND Risk all align, so that no single agent's hallucination can trigger a trade.
5. As an operator, I want every trade capped at 2% of buying power, so that no single bet can blow up the account.
6. As an operator, I want the bot to refuse entries when the bid/ask spread exceeds 0.1% of price, so that I'm not eaten by slippage on illiquid moves.
7. As an operator, I want the bot to skip "buy" signals when high-impact negative news has hit in the last 5 minutes, so that I don't catch a falling knife.
8. As an operator, I want a kill switch I can trip manually — and that auto-trips on a daily-loss threshold — so that I have a single button for "stop now."
9. As an operator, I want all open positions force-closed by 15:55 ET, so that I never carry overnight risk by accident.
10. As an operator, I want to query any running workflow and read its current agent outputs + sources, so that I can audit decisions in real time.
11. As an operator, I want to force-close a specific position via an update, so that I can override the bot without taking it offline.
12. As an operator, I want a structured end-of-day reflection (wins, losses, lessons) appended to a rolling memory file, so that I can review the day quickly.
13. As an operator, I want next-day agents to read the last N days of memory as context, so that the bot demonstrably improves with use.
14. As an operator, I want to swap brokers (Alpaca ↔ Schwab) by configuration, so that I'm not locked in.
15. As an operator, I want every agent's prompt to live in a versioned Markdown file, so that I can iterate on agent behavior without touching code.
16. As an operator, I want to backtest the current agents + prompts over historical sessions, so that I can estimate forward profitability before deploying changes.
17. As an operator, I want backtest runs to produce a P&L distribution across multiple seeds, so that I'm not fooled by LLM noise on a single run.
18. As an operator, I want backtest stability metrics (directional agreement ≥ 80%, P&L CV < 0.3, hit-rate spread ≤ 10 ppts) to gate any promotion to live, so that I never deploy a strategy whose edge lives inside LLM noise.
19. As an operator, I want paper trading as the default and live promotion to require explicit approval, so that I cannot accidentally route an experiment to my real account.
20. As an operator, I want the bot to halt new entries when market data becomes stale (timeframe-aware: > 60s for 5m bars, > 120s for 15m+), so that the bot doesn't trade on phantom prices.
21. As an operator, I want a daily cap on LLM token spend, so that an aberrant news day can't run up an arbitrary bill.
22. As an operator, I want pre-market screening to use a tiered pipeline (cheap deterministic filter → cheap LLM → expensive LLM), so that token cost is bounded by fan-out caps and not by universe size.
23. As an operator, I want every trade traceable to a workflow execution id, so that incident reviews can replay decision history precisely.
24. As an operator, I want order placement idempotent on the broker side, so that crashes and retries can never double-fill.
25. As an operator, I want bright-line safety gates implemented in deterministic workflow code, so that a misbehaving LLM cannot bypass them.
26. As an operator, I want broker-specific quirks (Alpaca `client_order_id`, Schwab OAuth refresh, bracket-order rules) hidden behind a single contract, so that adding a third broker is mechanical.
27. As a developer, I want each safety-critical behavior packaged as a pure function with a stable interface, so that I can unit-test it in isolation without spinning up workflows or brokers.
28. As a developer, I want the market calendar, the bright-line gates, the idempotency layer, and the trading-memory store to each be a single deep module, so that internal complexity (DST, OAuth, atomic writes, etc.) doesn't leak into call sites.
29. As a developer, I want to run a full simulated trading day end-to-end in seconds using Temporal's time-skipping environment, so that I can catch session-level regressions before they hit paper.
30. As an operator, I want stability metrics computed via a single pure function over seeded backtest runs, so that the promotion gate is mechanical and reviewable.
31. As a tenant operator, I want my broker credentials, LLM key, and trading data fully isolated from every other tenant on the platform, so that I can trust running on shared infrastructure.
32. As a tenant operator, I want to run multiple strategies in parallel on my account (e.g., ORB-momentum and mean-reversion side-by-side), each with its own watchlist, risk budget, and memory, so that I can diversify within my account.
33. As a tenant operator, I want each of my strategies to have its own kill switch and daily-loss threshold, so that one bad strategy doesn't halt the others.
34. As a tenant operator, I want my buying power split across my active strategies with a defined allocation policy, so that no single strategy can consume the whole account.
35. As a tenant operator, I want a per-tenant daily LLM-token budget enforced by the platform, so that a runaway agent can't drain my balance.
36. As a tenant operator, I want a tenant-scoped audit log of every decision, signal, update, and order, so that I can review and prove what my bot did.
37. As a tenant operator, I want to define a new strategy as a configuration (prompt overrides, risk params, universe, triggers) without touching code, so that I can iterate without a deploy.
38. As a tenant operator, I want to occasionally define a strategy with custom workflows (when configuration isn't enough), so that genuinely different strategies aren't blocked.
39. As a platform admin, I want to onboard a new tenant by provisioning their credentials, defaults, and quotas through a single configuration path, so that adding tenants is mechanical.
40. As a platform admin, I want to trip a tenant-level kill switch (e.g., on suspicious activity or non-payment), so that I can pause one tenant without affecting others.
41. As a platform admin, I want per-tenant observability (token spend, broker calls, workflow counts, P&L), so that I can support and bill correctly.
42. As a developer, I want every Activity and workflow input to carry `(tenant_id, strategy_id)` from day one, so that tenant scoping is a compile-time concern rather than a refactor.
43. As a developer, I want a single `SecretsResolver` interface for tenant credentials, so that no code path can accidentally load a global broker key.

## Implementation Decisions

### Deep modules (extracted for isolated testability)

Each of these encapsulates significant complexity behind a small, stable interface. They are the unit-test surface of the system.

**BrightLineGates** — pure function. Single entry point:

```
evaluate(proposal, market_state, account_state) -> Allowed | Rejected { reason }
```

Encapsulates the seven safety rules (market-hours, 2% size cap, 0.1% spread cap, 5-min negative-news veto, EOD timer flag, post-failure auto-cancel, daily-loss kill-switch state). Called by the workflow before every order. No I/O, no LLM, no Temporal primitives — entirely testable with fixture inputs.

**OrderIntentJournal** — write-ahead log for orders, with crash-recovery reconciliation. Replaces a plain idempotency store with a journal:

```
record_intent(intent: OrderIntent) -> void          # called BEFORE broker API
place_with_intent(intent: OrderIntent) -> BrokerOrderId
mark_filled(intent_id, fill: Fill) -> void
mark_failed(intent_id, error) -> void
reconcile(broker_open_orders: list[BrokerOrder]) -> list[Discrepancy]
```

Every order is journaled with its idempotency key **before** the broker API is called. On worker restart, `reconcile()` matches journal intents against broker-reported open orders to find orphaned stops, missing fills, or duplicate submissions. Same `idempotency_key` always yields the same `BrokerOrderId`, even after a crash mid-place. Internally: Alpaca uses `client_order_id`; Schwab uses the journal's UUID as a side-mapped key.

This is a strict superset of the simpler "idempotent order store" pattern, learned from a prior production trading system that survived crashes only because the journal existed before the broker did.

**TradingMemoryStore** — append-only log:

```
append(entry: DailyReflection)
load_recent(days: int) -> list[DailyReflection]
```

Internally: atomic temp-file + rename writes, bracketed-tag format (Tauric-derived, Apache-2.0), size-capped rotation that preserves pending entries. Reflection Agent only sees `append`; next-day prompts only see `load_recent`.

**MarketCalendar** — single source of truth for time:

```
is_open(ts) -> bool
next_close(ts) -> datetime
next_open(ts) -> datetime
```

Encapsulates NYSE holidays, half-days, DST, year-end. Used identically by live workflows and the backtest worker. Replaces every ad-hoc "15:55 ET" string in the codebase.

**StabilityMetrics** — pure function over seeded backtest outputs:

```
compute(seeded_runs: list[SessionResult]) -> StabilityReport {
  directional_agreement: float,    # 0.0–1.0
  pnl_cv: float,                   # coefficient of variation
  hit_rate_spread: float           # max - min win-rate across seeds
}
```

The promotion-to-live gate is `directional_agreement >= 0.80 AND pnl_cv < 0.3 AND hit_rate_spread <= 0.10`. Pure function = testable with fixture run-result lists.

**MarketDataCache** — read-only cache surface, **separated** from the streaming subscriber lifecycle:

```
get_bars(symbol, timeframe, lookback) -> list[Bar]      # each carries retrieved_at
get_quote(symbol) -> Quote
fetch_news(symbol, since) -> list[NewsEvent]
```

The websocket subscription lifecycle is **not** part of this module — the cache is just a typed in-memory store with TTL/freshness semantics. Subscriber writes into it; Activities read from it. The split exists so cache reads are unit-testable without a real broker.

**SimBroker** — full broker-port implementation against historical data, used by the backtest worker:

```
# satisfies the same Activity contract the real Alpaca/Schwab adapters satisfy
place_order(intent, as_of) -> Fill | Rejection
get_quote(symbol, as_of) -> Quote
get_position(tenant_id, strategy_id, as_of) -> Position
...
```

This is not a thin data replay — it's a behaviorally-realistic broker simulator: spread-aware fill pricing (`mid − k·spread` for limits), asymmetric re-peg timeouts (stops time out faster than targets), halt detection, dust-sweep with marketable limits at EOD, and partial fills under realistic liquidity. Backtests run against `SimBroker` and must demonstrate **parity** with a known live session before strategy promotion (see Testing Decisions).

**SlippageAwareExits** — order-pricing logic used by `PositionWorkflow` for exits (stops, targets, EOD flatten):

```
compute_exit_order(position, current_quote, exit_kind, time_remaining) -> Order
```

Encapsulates spread-adaptive limit pricing, asymmetric timeouts (target patient, stop urgent), halt detection (no fills attempted during halts), dust-sweep at EOD (marketable limits for sub-100-share residuals). A separate module because the rules are non-trivial and were the source of meaningful real-money slippage in a prior system that didn't have them.

### Multi-tenant deep modules

These exist from Phase 0 so tenant scoping is a compile-time concern, not a later refactor.

**TenantRegistry** — single source of truth for tenant identity and defaults:

```
get(tenant_id) -> TenantConfig {
  display_name, billing_state, default_quotas,
  enabled_brokers, enabled_strategies, kill_switch_state
}
list_active() -> list[TenantConfig]
```

Backed by Postgres or DynamoDB; cached in-process with short TTL.

**StrategyRegistry** — strategy definitions, scoped per tenant:

```
get(tenant_id, strategy_id) -> StrategyConfig {
  kind: "config_variant" | "custom_workflows",
  prompt_overrides,      # which .md files override the defaults
  risk_params,           # size cap, stop logic, etc. — override BrightLineGates
  universe_def,          # which symbols, which filters
  entry_trigger,         # deterministic level break vs LLM-judged vs both
  capital_weight,        # 0.0–1.0; sums to ≤ 1.0 across active strategies
  custom_workflows?      # only set for kind == "custom_workflows"
}
list_active(tenant_id) -> list[StrategyConfig]
```

Strategies are versioned; deploying a strategy edit creates a new version and triggers a backtest before live-promotion.

**SecretsResolver** — single throat for tenant-scoped credentials:

```
resolve(tenant_id, secret_name) -> Secret
```

Backed by Vault / AWS Secrets Manager / KMS in production; local-file fallback only in dev. No code path may load a global broker key; CI guardrail enforces. Audit-logged on every resolve.

**QuotaTracker** — enforces per-tenant resource limits before consumption:

```
try_consume(tenant_id, resource, amount) -> Allowed | Throttled { reason }
```

Resources: LLM tokens (daily), broker API calls (per-minute), concurrent positions, concurrent workflows. Backed by Redis. A throttled response halts the in-flight workflow with a typed `QuotaExceededError`, which is non-retryable.

**CapitalAllocator** — splits a tenant's buying power across their active strategies:

```
allocate(tenant_id, account_balance) -> dict[strategy_id, AllocatedCapital]
```

v0: static allocation from `StrategyConfig.capital_weight`. v1+: dynamic allocation based on recent strategy performance, kept behind a feature flag.

**AuditLogger** — tenant-scoped, append-only event trail:

```
log(tenant_id, event: AuditEvent) -> void
query(tenant_id, time_range, kinds?) -> list[AuditEvent]
```

Every decision, signal, update, order, kill-switch trip, secret-resolve, and quota-throttle is logged. Tenant operators can query their own log; platform admins can query across tenants for support. Storage is append-only and tenant-partitioned.

### Other major modules (orchestration / shells)

- **Cross-language contract** — shared JSON Schema + generated Python and TypeScript bindings for every Activity payload, agent output type, and workflow input. CI gates cross-language drift.
- **Multi-agent pipeline** — six LLM agents (Technical Analyst, Sentiment, Strategy, Risk Manager, Execution, Reflection), each = versioned Markdown system prompt + Pydantic output type + least-privilege tool subset. Loaded at runtime.
- **Workflow orchestrator** — six durable Temporal workflow types: `MarketDataSubscriberWorkflow` (live only), `UniverseScanWorkflow`, `WatchWorkflow`, `TradeWorkflow`, `PositionWorkflow`, `ReflectionWorkflow`. Workflows own sequencing and invoke `BrightLineGates` and `MarketCalendar` directly.
- **Streaming subscriber** — long-running TypeScript worker that owns websocket lifecycle (subscribe, heartbeat, reconnect, REST backfill) and writes into `MarketDataCache`. Integration code by nature.
- **Broker abstraction** — per-broker TypeScript worker on its own task queue, implementing the shared contract; wraps `IdempotentOrderStore` internally.
- **Kill-switch infrastructure** — global per-day flag in an external KV (Redis or DB row). Auto-trips on daily realized loss ≥ threshold; trippable via Update. Effects: halts new entries, signals open positions, suppresses new watch workflows.
- **Backtest harness** — separate Python package, separate TypeScript worker (`market-data-backtest`), separate task queue. Same workflows and agents; `WorkflowEnvironment` time-skipping; `BacktestRunnerWorkflow` fans out N seeded sessions and feeds `StabilityMetrics`.

### Architectural decisions

- **Workflow-as-orchestrator, not LLM-as-orchestrator.** Sequencing is deterministic Python. The LLM cannot decide to skip Risk.
- **LLM is never in the live hot path.** LLMs are used for: pre-market watchlist generation (Universe Scan agents), entry decision synthesis (Strategy agent, called once per entry), risk judgment above bright-lines (Risk Manager, called once per entry), post-close reflection. They are **not** used for continuous monitoring, exit-decision overrides, or any per-bar tight loop. A prior production trading system spent significant time architecting an LLM-debate-as-orchestrator pattern that never integrated — LLM latency and non-determinism made it infeasible inside hot loops. We treat this as a settled architectural principle, not an experiment to repeat.
- **Polyglot at the Activity boundary.** Workflow code is Python; broker and market-data activities are TypeScript. Workflows call by Activity name + task queue.
- **Prompts as data.** Each agent's instructions live in a versioned Markdown file loaded at runtime; not embedded in Python strings.
- **Trade-critical numbers come from tools, never LLM text.** Prices, quantities, balances, positions are typed Activity returns. The LLM cannot fabricate or rewrite them.
- **Source tags on every Sentiment claim.** Numeric assertions without sources are stripped downstream; price-target events older than 24h are dropped (kept only as context).
- **Multi-seed default in backtest.** Single-seed P&L is misleading; reports are distributions across N=3–5 seeds.
- **Relaxed LLM determinism.** `temperature=0` + fixed seed + pinned dated model id is sufficient; bit-exact reproducibility is *not* a goal. "Same general direction" across seeds is the bar.
- **Live and backtest never share LLM cache.** Live writes nothing the backtest reads. Live ↔ backtest separation is enforced by CI guardrail.
- **Pinned model ids.** Production agents pin dated model strings (e.g. `gpt-5-mini-2026-04`), not moving aliases. Backtest uses the same pinned id.
- **Paper trading as default; live promotion requires manual approval.** Paper and live use separate task queues so routing is mechanical, not flag-based.

### Schema decisions

- Every agent returns a Pydantic model: `TechnicalReport`, `SentimentReport`, `TradeProposal`, `RiskDecision`, `ExecutionReport`, `DailyReflection`. No free-form prose on hot paths.
- `DailyWatchlist` carries ranked `WatchlistCandidate` items, each with `score`, `thesis`, `catalysts` (sourced), `entry_hint`, `risk_hint`.
- Time-sensitive Activities (`get_bars`, `get_quote`, `fetch_news`) take an optional `as_of`. Live passes `None` (cache read); backtest passes simulated time (historical lookup).
- Non-retryable error types are typed (`InsufficientFundsError`, `InvalidSymbolError`, `AuthError`) so retry policies act on them precisely.
- `StabilityReport` (above) is the single output shape that the promotion gate reads.

## Testing Decisions

A good test exercises **external behavior, not implementation details**:

- A `BrightLineGates` test asserts "this oversized proposal returns `Rejected{reason='size_cap'}`," not "this internal helper was called."
- An agent test asserts "given fixture inputs, the returned `TechnicalReport` satisfies its Pydantic schema and includes a non-empty `signals` array," not "the prompt contains the word VWAP."
- A workflow test asserts "after the EOD timer fires, all open positions are closed and the kill switch was not tripped," not "the timer Activity was scheduled."

### Required test coverage (v0 ships without these is a no-go)

| Module / behavior | What to test | Why |
|---|---|---|
| **BrightLineGates** | Each gate accepts conforming proposals and rejects breaching ones; combined firing on a proposal that breaches multiple gates returns the highest-priority reason | This is the system's safety floor. Pure function = trivially unit-testable. |
| **`compute_indicators`** | VWAP / ORB / RSI / MA / Volume-Delta math against known fixture bars | Trade signals depend on this being right; deterministic Python. |
| **`prefilter_universe`** | Tier-1 screen filters correctly on liquidity / volatility / gap criteria | Cost of universe scan depends on this narrowing correctly. |
| **MarketCalendar** | Open/close, half-days, DST transitions, holidays, year-end produce correct timestamps; backtest dates produce historically correct calendar info | EOD force-close fails silently if the calendar is wrong; high blast radius. |
| **TradingMemoryStore** | Round-trip a `DailyReflection`; rotation honors size cap; concurrent appends don't corrupt; `load_recent(N)` returns last N days in order; rotation preserves pending entries | Memory feeds tomorrow's decisions; corruption is a slow-moving disaster. |
| **IdempotentOrderStore** | Same `idempotency_key` doesn't double-fill across retries; works correctly for each broker integration | Prevents the worst-case Temporal-replay outcome. |
| **Kill switch** | Trip → new entries blocked, open positions receive `risk_breach` signal, watches stop spawning, all within seconds; reset restores normal | Whole-system safety mechanism. |
| **Agent output-schema validation** | For every agent (`TechnicalReport`, `SentimentReport`, `TradeProposal`, `RiskDecision`, `ExecutionReport`, `DailyReflection`): happy-path fixtures pass Pydantic validation; deliberately malformed outputs are rejected by the workflow | First line of defense against hallucinated structure. Catches breaking-change regressions when prompts are edited. |
| **StabilityMetrics** | Directional agreement, P&L CV, hit-rate spread computed correctly across seeded fixture runs; promotion-gate predicate evaluated correctly | These metrics gate live promotion; their math must be right. |
| **E2E paper session in `WorkflowEnvironment`** | Run a full session day against a small historical-data fixture in time-skipping mode; assert no flat-by-close violation, no double-fill, no kill-switch trip, daily reflection written, memory loaded next day | Catches integration-level regressions across the whole pipeline before they hit paper trading. |
| **Live ↔ backtest parity** | Take a recorded live paper-trading session (one day of orders + decisions). Replay the same day through SimBroker with the same prompts / seed / pinned model. Assert decisions match within stability-metric tolerances (directional agreement ≥ 80%, P&L within tolerance). | A prior trading system spent weeks chasing race conditions and event-ordering bugs that only showed up in production because the backtest didn't actually match live behavior. This test catches drift early. |
| **OrderIntentJournal crash recovery** | Simulate a worker kill between `record_intent` and successful broker submission; on restart, assert reconciliation produces the right state (orphaned intent identified, broker side queried, journal updated). | Crash safety on order placement is non-negotiable — this is the test that proves it. |

### Light testing (covered indirectly)

| Module | Reason |
|---|---|
| **Agent prompt files (`.md`)** | Tested by their outputs (above), not by prompt-text assertions. Prompt edits should not break tests; behavior changes show up in agent-schema or E2E tests. |
| **Streaming subscriber lifecycle** | Best validated by integration tests against a paper broker; unit tests are mostly mocks of websocket libraries. |
| **Reflection prompt quality** | Quality is judgmental; covered by output schema + manual review, not unit tests. |
| **Workflow plumbing** | Tested via the E2E session; per-workflow unit tests would mostly duplicate Activity contract tests. |

### Test conventions

- Workflow + E2E tests use Temporal's `WorkflowEnvironment` for time-skipping and Activity mocking — the same primitive the backtest harness uses, so the test path mirrors the production code path.
- Cross-language contract drift is caught by a CI step that round-trips fixtures through Python and TypeScript bindings.
- The deep-module unit tests must run in milliseconds and require no Temporal, no broker, and no LLM (use fixture inputs). They are the fast-feedback loop during development.
- Agent output-schema tests use recorded fixture LLM responses (small, committable) plus a separate set of malformed-output fixtures to exercise the Pydantic boundary.

## Out of Scope

For v0, the following are explicit non-goals:

- **High-frequency trading or scalping on 1-minute candles.** Decision latency floor is ~5–10s; engine operates on 5m / 15m / 30m / 1h candles only. 1-minute is explicitly out of scope. We don't compete on tick-level speed.
- **Swing / multi-day positional.** All positions flat by close. Overnight risk is out.
- **Asset classes beyond US equities.** No options, futures, FX, or crypto. (Options is the most likely v2 addition.)
- **Pre-market and post-market trading.** Regular session only.
- **Auto-promotion to live trading.** Paper→live always requires human approval.
- **Strategy generation by the LLM.** Agents execute a strategy embedded in their prompts; they don't invent new strategies. Reflection produces lessons (context), not new code.
- **Replicating past live decisions in backtest.** Backtest is forward-looking strategy estimation, not historical-decision replay.
- **Shared LLM cache between live and backtest.** Always separate; live never writes anything the backtest reads.
- **Bit-exact LLM reproducibility.** Relaxed in favor of "directionally stable across seeds."
- **Per-workflow unit tests beyond E2E.** Workflow behavior is covered by the E2E session test + deep-module units; finer-grain workflow tests would duplicate without adding signal.
- **Self-serve tenant signup / billing UI.** v0 onboards tenants via admin-provisioned configuration. Public signup, payment integration, and tenant-management UI are deferred.
- **Cross-tenant strategy marketplace.** Strategies stay private to their owning tenant; no sharing, copying, or selling strategies between tenants in v0.

## Further Notes

### Hallucination risk is treated as financial risk

Financial-text hallucinations (a misread decimal, a fabricated headline, a confused unit) are uniquely expensive in this domain. The architecture treats hallucination as something to *engineer away*, not just monitor:

- Trade-critical numbers always come from tools, never LLM text.
- Sentiment events without source tags are stripped before reaching downstream agents.
- `BrightLineGates` fires correctly even on a fluent LLM hallucination because it's a pure function over tool-sourced inputs.
- `StabilityMetrics` catches the residual: if a strategy's directional agreement across seeds is < 80%, its edge lives in LLM noise rather than the market.

### Why deep modules matter here

Trading systems accumulate complexity in places where bugs are silent and expensive (calendar math, atomic file writes, broker-specific dedup, gate composition). Concentrating that complexity behind small interfaces means:

- The pure-function units (`BrightLineGates`, `StabilityMetrics`, `compute_indicators`) get exhaustive fixture tests cheaply.
- The integration-prone units (`IdempotentOrderStore`, `TradingMemoryStore`, `MarketCalendar`) have a single throat to test against each backend.
- Workflow code stays readable and stays out of the gate-rule business.

### Cost is a first-class concern

A naive universe scan over 1000 names with Sentiment on every name would burn tokens with no signal lift. The tiered pipeline (deterministic prefilter → cheap LLM scoring → expensive LLM analysis) caps fan-out at each tier. A daily token-cost ceiling is enforced and dashboarded; Sentiment outputs are cached by `(symbol, news_window_hash)` so unchanged news doesn't re-spend.

### Observability is durable by construction

Temporal records every Activity invocation and result in workflow history. Combined with the `current_reasoning` query and structured agent outputs, this makes incident review tractable: any trade can be traced backwards through Strategy → bright-line gates → Sentiment + Technical reports → source data, with timestamps. No separate logging stack is required to answer "what did the bot do at 10:42 ET?"

### Multi-tenant + multi-strategy architecture

Both dimensions are baked in from Phase 0 — `(tenant_id, strategy_id)` is present on every workflow input, every Activity payload, every memory entry, every audit event. The slots exist before the code that consumes them does, which is the cheap path to multi-tenant; retrofitting tenant scoping into an existing single-tenant system is famously painful.

Code-path roll-out is sequenced behind the schemas:

1. **Phase 0**: schemas + `TenantRegistry` + `StrategyRegistry` + `SecretsResolver` exist; v0 ships with one tenant and one strategy in deployment configuration. All workflow inputs already carry the IDs.
2. **Mid-phases**: multi-strategy lands within a single tenant (config variants of the same workflows; capital splitting; per-strategy kill switches).
3. **Later phases**: multi-tenant code paths activate (per-tenant quotas, per-tenant secrets resolution at scale, per-tenant audit log, admin-provisioned onboarding). Self-serve onboarding and billing remain out of v0 scope.

The CI guardrail set expands accordingly: in addition to "live ∌ backtest" and "workflows ∌ broker imports," we add "no Activity reads broker credentials except via `SecretsResolver(tenant_id)`."

### Open product questions (capture, do not block)

These remain unresolved and should be answered before the corresponding implementation phase ships:

- Daily LLM-cost budget ceiling (drives Tier-2/Tier-3 fan-out caps).
- Initial universe definition (S&P 500 vs Russell 1000 vs dynamic top-N by dollar volume).
- News provider preference (Polygon vs Finnhub vs Benzinga vs SEC EDGAR direct).
- Reflection scope (summary-only vs propose-rule-changes-behind-human-review).
- Promotion gate specifics (N paper days, paper-vs-backtest tolerance, manual-vs-auto).
- Backtest seed count default (N=3, 5, or 10).
- Operator interface for queries / kill switch (CLI vs Slack bot vs web dashboard vs Temporal UI for v0).
- Deployment shape (local docker-compose vs Temporal Cloud vs self-hosted).
- Tenant secrets backend choice (Vault vs AWS Secrets Manager vs GCP Secret Manager vs other).
- Audit-log retention (per-tenant configurable vs platform default; how long).
- Quota policy defaults (per-tenant daily LLM tokens, broker calls/min, concurrent positions, concurrent workflows).
- Strategy capital allocation policy (static `capital_weight` only in v0, or some dynamic rebalancing?).
- Tenant onboarding flow (admin config file for v0; what shape?).
- When to activate per-tenant code paths (which phase wires `SecretsResolver`, which phase wires `QuotaTracker`, etc.).

### Publication

The `to-prd` skill's default behavior is to publish the PRD to a project issue tracker with the `ready-for-agent` label. This project has no issue tracker configured, so the PRD is written to `PRD.md` at the repo root. When an issue tracker is configured (Open Q: deployment shape), the PRD can be re-published there.
