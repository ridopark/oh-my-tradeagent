# Agentic Trading Bot — Plan

## Goal
A durable, broker-agnostic, **multi-tenant** intraday trading engine. Each tenant runs one or more **strategies** on their own account, fully isolated from other tenants in data, credentials, kill switch, quotas, and audit. Every trading day, per `(tenant, strategy)`, a scheduled universe scan produces a ranked watchlist; long-running Watch workflows monitor those candidates during market hours and trigger trades through the agent pipeline (Technical Analyst + Sentiment → Strategy → Risk → Execution). All positions are flat by market close. After close, a Reflection agent reviews the day per `(tenant, strategy)` and appends structured lessons to a persistent `trading_memory.md` that next-day agents read as context. Multi-tenant and multi-strategy are **baked in from Phase 0** at the schema/contract level; v0 deploys with one tenant and one strategy, but the slots exist everywhere.

## Constraints & operating envelope

- **Strategy horizon**: **Intraday only** for v0 (entries during regular session, all flat by close). **Not HFT, not scalping.** LLM reasoning has a ~5–10s floor per decision, which is fine because the engine operates on **5-minute and 15-minute candles (and above — 30m / 1h within reason)**. **1-minute candles are explicitly out of scope.**
- **Data freshness**: timeframe-aware. A bar is considered fresh if `now - retrieved_at < 60s` for 5m candles and `< 120s` for 15m+. Stale data is dropped at the Activity boundary; agents never see it.
- **Hallucination is a financial risk**, not just a quality issue:
  - Every agent returns **structured output** (Pydantic / JSON schema) — no free-form numbers on hot paths.
  - **Trade-critical numbers** (prices, qty, balances, positions) come from broker/market-data Activities, never from LLM-extracted text.
  - Every Sentiment claim carries a **source tag** (URL, filing ID, retrieved_at). Sources older than 24h are dropped for price-target signals (kept only as context).
  - **Bright-line deterministic checks** in workflow code as a backstop to the Risk Agent (Risk's LLM judgment is *additional*, not the only safety layer).
- **Paper trading first.** Live keys gated behind explicit promotion (final phase).
- **Multi-tenant SaaS isolation.** Every Activity payload, workflow input, memory entry, and audit event is scoped by `(tenant_id, strategy_id)`. No code path may read across tenants. CI guardrails enforce the boundary.
- **Strategy = config variant by default.** Most strategies override prompts (.md), risk params, universe filter, and entry trigger over the shared workflow set. A small `kind: custom_workflows` escape hatch exists for strategies that genuinely need different orchestration.
- **Tenant code paths roll out behind tenant schemas.** Phase 0 ships full IDs in contracts + the four foundational tenant deep modules (`TenantRegistry`, `StrategyRegistry`, `SecretsResolver`, `QuotaTracker` skeleton). Multi-strategy code paths activate mid-phases; full per-tenant quotas/audit at scale come later.

## Stack decisions
- **Python**: orchestrator Workflows + agents via **OpenAI Agents SDK + Temporal** (GA, Python-only).
- **TypeScript**: broker & market-data Activities.
- **Polyglot at the Activity boundary** — workflows call Activities by name + task queue; payloads JSON against a shared contract.
- **Prompts as data**: each agent's instructions live in a versioned `.md` file (`agents/<agent>.md`), loaded at runtime into the system message. Prompt edits don't require code changes.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│             Python Worker (task queue: agent-core)              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  TradeWorkflow  (durable, deterministic orchestrator)      │  │
│  │                                                             │  │
│  │   ┌────────────┐  ┌────────────┐    (run in parallel)      │  │
│  │   │ Technical  │  │ Sentiment  │                            │  │
│  │   │  Analyst   │  │   Agent    │                            │  │
│  │   └─────┬──────┘  └─────┬──────┘                            │  │
│  │         └────────┬───────┘                                  │  │
│  │                  ▼                                          │  │
│  │   workflow: 5-min negative-news veto (deterministic)        │  │
│  │                  ▼                                          │  │
│  │          ┌──────────────┐                                    │  │
│  │          │  Strategy    │  synthesize → TradeProposal        │  │
│  │          │  Agent (PM)  │                                    │  │
│  │          └──────┬───────┘                                    │  │
│  │                 ▼                                            │  │
│  │  workflow: bright-line checks (2% size, spread, hours…)     │  │
│  │                 ▼                                            │  │
│  │          ┌──────────────┐                                    │  │
│  │          │ Risk Manager │  judgment layer → RiskDecision     │  │
│  │          └──────┬───────┘                                    │  │
│  │                 ▼ (handoff)                                   │  │
│  │          ┌──────────────┐                                    │  │
│  │          │  Execution   │  submit & monitor                  │  │
│  │          └──────────────┘                                    │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  After close: ReflectionWorkflow → trading_memory.md             │
└────────────────────────┬────────────────────────────────────────┘
                         │  Activities by NAME, routed by task queue
   ┌───────────────┬─────┴────────┬──────────────────────────────┐
   ▼               ▼              ▼                              ▼
┌────────────┐ ┌────────────┐ ┌────────────┐         ┌────────────────┐
│ broker-    │ │ broker-    │ │ broker-    │   ...   │ market-data,   │
│ alpaca-    │ │ alpaca-    │ │ schwab-    │         │ news, filings, │
│ paper      │ │ live       │ │ paper      │         │ social (TS)    │
└────────────┘ └────────────┘ └────────────┘         └────────────────┘
```

**Workflow is the orchestrator, not an LLM.** Risk Manager cannot be skipped because the Risk → Execution gate is in deterministic workflow code — a prompt-injected or hallucinating PM cannot bypass it.

## Multi-agent design

**Workflow-orchestrated agents, not SDK-orchestrated.** Every agent runs as a workflow-invoked Activity. We deliberately do **not** use the OpenAI Agents SDK's `agent-as-tool` or `handoff` primitives — sequencing lives in deterministic workflow code so the Risk gate and bright-line checks cannot be bypassed by a prompt-injected or hallucinating LLM.

| OpenAI Agents SDK feature | Used here? | Why / why not |
|---|---|---|
| `Agent(instructions=..., tools=..., output_type=...)` | ✅ Yes | Each agent is one `Agent` instance with its own prompt, tools, and Pydantic output type |
| Structured outputs via `output_type` | ✅ Yes | Every agent returns a typed Pydantic model; no free-form numbers on hot paths |
| **Agent-as-tool** (one Agent invokes another) | ❌ No | The workflow fans out Technical+Sentiment and feeds outputs to Strategy. Letting an LLM choose whether to call Risk would be unsafe |
| **Handoff** (control transfers between Agents) | ❌ No | Same reason — workflow code routes between agents, not the LLM |
| **Guardrails** (SDK input/output validators) | △ Concept | We use Pydantic + workflow-level deterministic checks (range, source-tag presence, data freshness, bright-lines). Not the SDK's `Guardrail` class specifically |

### Agents

| Agent | Role (intraday-focused) | Tools (Activities) | Output schema | Prompt file |
|---|---|---|---|---|
| **Technical Analyst** | VWAP, ORB, Volume Delta on **5m / 15m / 30m / 1h** candles only (no 1m). Identify confluence across timeframes. Reject bars exceeding the timeframe-aware freshness budget. | `get_bars`, `compute_indicators` | `TechnicalReport` (sentiment_score [-1, 1], confidence_interval, signals[]) | `agents/technical_analyst.md` |
| **Sentiment** | Flash news, earnings, Fed announcements, social volume. Drop price-target updates older than 24h. | `fetch_news`, `fetch_sec_filing`, `score_sentiment`, `fetch_social_pulse` | `SentimentReport` (score, top_events[] with source + retrieved_at + impact) | `agents/sentiment.md` |
| **Strategy (PM)** | Synthesize `TechnicalReport` + `SentimentReport` → `TradeProposal`. Reasoning only — never reads raw numbers. Reports arrive as typed inputs from the workflow, NOT as agent-tool calls. | `get_position`, `get_balance` | `TradeProposal` | `agents/strategy.md` |
| **Risk Manager** | Judgment layer above bright-lines: correlation, concentration, drawdown trend, spread quality. | `get_position`, `get_balance`, `get_quote`, `read_kill_switch`, `cancel_pending_orders` | `RiskDecision` (approve/deny + flags) | `agents/risk_manager.md` |
| **Execution** | Submit & monitor; escalate partials/timeouts; report failures (triggers workflow-level cancel-all-for-ticker). | `place_order`, `cancel_order`, `get_order_status` | `ExecutionReport` (status, fills, errors) | `agents/execution.md` |
| **Reflection** | Runs after close. Reads day's TradeWorkflow histories + realized P&L. Writes structured lessons to `trading_memory.md`. | `read_workflow_history`, `read_daily_pnl`, `append_trading_memory` | `DailyReflection` | `agents/reflection.md` |

Each agent gets **only the tools it needs** — least-privilege. Sentiment cannot place orders; Execution cannot read news; Reflection is append-only on memory.

### Agent prompts as Markdown (AGENTS.md / system-prompt.md pattern)

Each `agents/<agent>.md` *is* the agent's system prompt — versioned, reviewable, diffable. Loaded at runtime by a thin Python wrapper:

```python
# agents/loader.py
def load_agent(name: str) -> str:
    return (AGENTS_DIR / f"{name}.md").read_text()

technical_agent = Agent(
    name="technical_analyst",
    instructions=load_agent("technical_analyst"),
    tools=[get_bars, compute_indicators],
    output_type=TechnicalReport,
)
```

Benefits:
- Prompt edits ≠ code changes; prompt diffs are clean to review.
- Easy A/B by swapping the file (e.g., `technical_analyst.v2.md`).
- The `.md` files are the source of truth for agent behavior — Activity wrappers stay thin.
- Inspired by HKUDS/AI-Trader's `skills/*/SKILL.md` structure (which we studied but did not copy due to licensing — see `references/README.md`). Most other agentic frameworks keep prompts as Python strings; we chose Markdown files for review-ability.

### Bright-line safety gates (deterministic, never LLM)

Some checks are bright-lines and live in workflow code, **never** trusted to LLM judgment:

| Check | Where | Action on breach |
|---|---|---|
| Bar `retrieved_at` lag exceeds timeframe-aware budget (60s for 5m, 120s for 15m+) | Activity boundary | Drop bar, retry; persistent failure → abort decision |
| Position size > 2% buying power | Workflow (pre-Risk) | Reject TradeProposal before Risk sees it |
| Spread > 0.1% of asset price | Workflow (pre-Execution) | Force limit-only; if still no fill in N seconds, abort |
| 5-min negative-news veto: technical BUY but high-impact negative news within last 5 min | Workflow (post-Strategy) | Override to NEUTRAL, skip Execution |
| Daily realized loss ≥ kill-switch threshold | Kill-switch Activity | Halt all new entries; signal open positions to close |
| Execution Activity reports failure | Workflow | Auto-cancel all pending orders for that ticker |
| EOD timer (15:55 ET default) | PositionWorkflow | Force-close any open position |

The Risk Agent operates **above** this layer (correlation, concentration, drawdown trend).

### Kill switch

A global per-day flag in an external KV (Redis or a Postgres row), read via `read_kill_switch` Activity, written via `set_kill_switch`. Checked at the top of every decision cycle. Triggers:
- **Automatic**: daily realized loss ≥ threshold.
- **Manual**: admin Update on a dedicated `KillSwitchWorkflow` (or direct admin tool).

Effects when tripped:
- No new `TradeWorkflow` starts.
- Running `PositionWorkflow`s receive a `risk_breach` Signal → close immediately.
- `UniverseScanWorkflow` skips spawning new `WatchWorkflow`s until reset.

### Persistent memory & end-of-day reflection (tauric-style)

`trading_memory.md` is a rolling append-only log the bot writes to itself. Format adapted from [TauricResearch/TradingAgents](https://github.com/TauricResearch/TradingAgents) (Apache-2.0); bracketed tag line + `DECISION:` / `REFLECTION:` sections, separated by an HTML-comment delimiter (LLMs cannot emit it, so it's safe to split on).

```markdown
[2026-05-12 | aggregate | day_summary | +0.42% | n/a | n/a]

DECISION:
3 entries taken (NVDA, AAPL, AMD). Theme: ORB breakouts on tech gappers.
Regime: trend day, low realized vol after 10:30.

REFLECTION:
The 09:35 ORB framework worked on names with > 1.5x relative volume; AAPL
broke ORB but had below-average volume and faded — kept us in too long.
Specific lesson: require relative volume ≥ 1.5x in the first 5 minutes
before taking ORB entries.

<!-- ENTRY_END -->
```

Tag fields: `[date | ticker_or_aggregate | rating | raw_return | alpha_vs_benchmark | holding_window]`. Pending entries use `| pending]` until the outcome is known; the `ReflectionWorkflow` rewrites them with returns after close.

Lifecycle:
1. **EOD trigger**: `ReflectionWorkflow` scheduled at e.g. 16:30 ET.
2. **Inputs**: reads completed `TradeWorkflow` + `PositionWorkflow` summaries via Temporal History; realized P&L from broker.
3. **Output**: Reflection Agent emits `DailyReflection`, appended to `trading_memory.md`.
4. **Next morning**: `UniverseScanWorkflow` and Strategy Agent load the last N days of memory as additional system context.

Storage backend: file on disk for v0 (simple, diff-friendly, version-controllable). Migrate to object store / DB later if multi-instance.

### Hallucination mitigations per agent

- **Technical Analyst**: indicators computed deterministically in Python from bars; the LLM only interprets `compute_indicators` output. Stale bars dropped before the agent ever sees them. Only 5m/15m/30m/1h timeframes are exposed to the agent — never 1m, which we don't operate on.
- **Sentiment**: every event must include `source: { type, id, url, retrieved_at }`. Events without sources are stripped; price-target events older than 24h are dropped.
- **Strategy (PM)**: never reads raw numbers from news; only consumes structured `TechnicalReport` and `SentimentReport`. Cannot fabricate prices.
- **Risk Manager**: reads position/balance from broker Activities, never LLM memory. Bright-lines in workflow code, judgment in agent.
- **Execution**: order params are typed inputs — LLM cannot rewrite price/qty. Its job is monitoring and follow-up actions only.
- **Reflection**: writes are append-only via Activity with size cap; cannot rewrite or delete prior memory.

### How each agent maps to Temporal

- Each agent runs inside one Temporal Activity (durable, retryable). The Activity wraps an OpenAI Agents SDK `Runner.run()` call with the agent's loaded `.md` prompt, tools list, and Pydantic output type.
- **Workflow code** owns sequencing: it invokes Activities in order (or in parallel), inspects outputs, applies bright-line gates, and decides what to call next.
- Technical + Sentiment Activities run **in parallel** via `asyncio.gather` (cuts latency budget). Strategy is invoked next with both reports as typed inputs.
- The Strategy → Risk → Execution sequence is plain workflow Python — no SDK handoff. State transitions are durable across worker restarts because workflow history records each Activity result.
- Crash mid-flight: Temporal replays history. No duplicate agent invocations, no duplicate orders (idempotency key on `place_order`).

### A concrete flow (one trade decision)

```
WatchWorkflow detects entry trigger
    │
    ▼
TradeWorkflow starts
    │
    ▼
Workflow: input guardrail + kill-switch check
    │
    ▼
parallel: Technical Analyst  ◄── Activity (LLM + bars/indicators)
          Sentiment Agent    ◄── Activity (LLM + news/filings/social)
    │
    ▼
Workflow: 5-min negative-news veto
    │   ── if vetoed, log + abort
    │
    ▼
Strategy Agent           ◄── Activity (→ TradeProposal)
    │
    ▼
Workflow: bright-line checks (2% size, spread, hours…)
    │   ── if breached, abort
    │
    ▼
Risk Manager Agent       ◄── Activity (judgment → RiskDecision)
    │
    ├─ deny  → log + notify; workflow ends
    └─ approve
        │
        ▼ (handoff)
    Execution Agent      ◄── Activity
        │
        ▼
    place_order          ◄── Activity on broker-{alpaca|schwab}-{paper|live}
        │   on failure: workflow auto-cancels all pending for ticker
        ▼
    PositionWorkflow starts (manages exits, EOD force-close)
```

## Workflow lifetime, Signals & Queries

### Six workflow types

| Workflow | Lifetime | Purpose |
|---|---|---|
| `MarketDataSubscriberWorkflow` | one trading day (**live mode only**) | Owns websocket subscriptions; heartbeats; refills cache on stream gaps. One per data provider. Backtest mode has no subscriber — historical store reads are on-demand. |
| `UniverseScanWorkflow` | minutes | Scheduled daily pre-market (e.g., 08:00 ET). Tiered screen → `DailyWatchlist`. Spawns one `WatchWorkflow` per candidate. |
| `WatchWorkflow` | one trading day | One per candidate symbol. Monitors price/news; triggers entry. Spawns child `TradeWorkflow` on signal. Self-terminates at market close. |
| `TradeWorkflow` | seconds–minutes | One-shot entry: Technical+Sentiment → Strategy → Risk → Execution → end. Spawns `PositionWorkflow` on fill. |
| `PositionWorkflow` | minutes–hours (intraday) | Owns one open position. Manages exits (trailing stops, profit targets, EOD timer). **Mandatory flat-by-close.** |
| `ReflectionWorkflow` | minutes | Scheduled at e.g. 16:30 ET. Reviews day's trades + P&L; Reflection Agent appends to `trading_memory.md`. |
| `ReconciliationWorkflow` | seconds (per run) | Runs at worker startup and on a recurring schedule (e.g. every 5 min). Reads `OrderIntentJournal` + queries broker for open orders; flags discrepancies (orphaned stops, missing fills, duplicate submissions). Lifted from prior trading-system experience: crash mid-place between `journal.record_intent` and `broker.place_order` produces orphan state that only reconciliation can resolve. |

### Signals — asynchronous inputs to a running workflow

Durable messages from outside that change a workflow's behavior without restarting it. Signal handlers run in deterministic workflow code; they set a flag the main loop picks up.

| Signal | Target | Purpose |
|---|---|---|
| `price_update` | Position, Watch | Push from market-data subscriber |
| `news_event` | Position, Watch | Breaking news matched to symbol → trigger re-analysis |
| `risk_breach` | Position, Watch | Kill-switch trip or external risk system → exit |
| `user_command` | any | "close position", "pause", "adjust stop" |

### Queries — synchronous reads of workflow state

Free, non-mutating, don't appear in workflow history.

| Query | Returns |
|---|---|
| `current_reasoning` | Last Technical / Sentiment / Strategy outputs (with sources) |
| `position_state` | Size, avg cost, unrealized P&L |
| `pending_orders` | Open orders + fill status |
| `last_decision_trace` | Timestamps + agent activity IDs of last decision cycle |
| `daily_watchlist` | The day's `DailyWatchlist` (from `UniverseScanWorkflow`) |

### Updates — synchronous, validated state changes

| Update | Validation | Effect |
|---|---|---|
| `force_close` | Caller authorized? | Triggers exit; returns final order id |
| `adjust_stop` | New stop within bounds? | Updates stop level |
| `pause` | — | Halts new decisions; existing orders unaffected |
| `set_kill_switch` | Caller authorized? | Trips global kill switch |

### Long-running execution patterns

- **Continue-as-new**: not needed for intraday workflows (lifetime ≤ one session).
- **Heartbeats** in streaming market-data Activities so the workflow detects stalled subscriptions.
- **Timers** drive periodic re-evaluation inside Watch/Position workflows: **re-evaluate at the close of each 5-min bar OR on signal**. (No sub-bar polling — re-eval is aligned to bar boundaries on the shortest active timeframe.)
- **End-of-day force-close**: every `PositionWorkflow` registers a timer at 15:55 ET (default). Non-negotiable for intraday.

## Market-data streaming & cache

The hot path can't afford REST polling. A naive `get_bars` over 100 symbols would be ~10s of wall time per Tier-2 scan; per-minute Watch re-evals would crush the rate limit. So **market-data Activities read from a worker-local cache populated by streaming subscriptions**, not from the broker REST API directly.

```
┌────────────────────────────────────────────────────────────────┐
│  marketdata-stream worker  (TS, long-running per session)      │
│                                                                │
│   subscriptions (websocket):                                   │
│     • bars stream     (universe + watchlist)                   │
│     • quotes stream                                            │
│     • news firehose                                            │
│                                                                │
│   in-memory store:                                             │
│     • latest_bars[symbol][timeframe]  (rolling window)         │
│     • latest_quote[symbol]                                     │
│     • recent_news[symbol]             (rolling 24h)            │
│                                                                │
│   activities (read from memory, NOT API):                      │
│     get_bars()   → ~1 ms                                       │
│     get_quote()  → ~1 ms                                       │
│     fetch_news() → ~1 ms                                       │
│                                                                │
│   freshness: every cached datum carries retrieved_at.          │
│              Caller drops anything older than 30s.             │
│                                                                │
│   gaps: on stream stall → REST backfill → resume.              │
└────────────────────────────────────────────────────────────────┘
```

A long-running `MarketDataSubscriberWorkflow` owns the lifecycle: subscribes at market open, heartbeats throughout the session, manages reconnects, and terminates after close. The Activity contract is unchanged — only the implementation behind it.

**Non-streamable data** (SEC filings, historical bars for context, fundamentals) still uses REST, but cached by `(symbol, time_bucket)` in a shared store (Redis or in-worker LRU). `UniverseScanWorkflow` warms these for the day at startup.

## Daily universe scan & candidate selection

`UniverseScanWorkflow` is a **Temporal Scheduled** workflow that fires daily pre-market (e.g., 08:00 ET) and produces a ranked `DailyWatchlist`.

### Why tiered

Running Sentiment on every symbol in a 1000-name universe would burn tokens with most of the work wasted on names that never qualify. The pipeline narrows aggressively at the cheapest layer first.

```
Universe (~1000–3000 symbols)
    │
    ▼
[Tier 1] Deterministic prefilter (no LLM)              ← cheap, ~100ms
    • liquidity (avg $-volume ≥ threshold)
    • volatility (ATR / recent range ≥ threshold)
    • optional: pre-market gap / volume leaders
    • optional: borrowable (if shorting)
    → ~100 symbols
    │
    ▼
[Tier 2] Technical Analyst — parallel per symbol       ← cheap LLM × N
    • compute_indicators (deterministic) → features
    • LLM scores setup quality (structured output)
    → top ~30 setups
    │
    ▼
[Tier 3] Sentiment Agent — parallel per top setup      ← expensive LLM × small N
    • fetch_news, fetch_sec_filing, fetch_social_pulse
    • LLM produces thesis + catalysts with source tags
    → top 5–15 candidates with thesis
    │
    ▼
DailyWatchlist (persisted as workflow output)
    │
    └── spawn one WatchWorkflow per candidate
```

Each tier runs as Activities parallelized via workflow-level `asyncio.gather`. Token spend is bounded by Tier-2/Tier-3 fan-out caps, not universe size.

### Universe definition (configurable)

- **Static list** (e.g., S&P 500 or curated 1000) — simplest start.
- **Dynamic** (`get_universe(criteria)`) — top-N by 30-day dollar volume, optionally filtered by sector/market-cap/options-eligible.
- **Pre-market overlay** — union the static universe with `get_premarket_movers()` so today's catalysts (gappers) get screened even if they're not in the base list.

### DailyWatchlist output

```python
class WatchlistCandidate(BaseModel):
    symbol: str
    score: float                       # composite Tier 2 + Tier 3
    thesis: str                        # short, LLM-generated
    catalysts: list[SourcedClaim]      # each with source + retrieved_at
    entry_hint: EntrySetup             # level + trigger type (ORB, VWAP-reclaim, …)
    risk_hint: RiskHint                # suggested stop distance, max size hint
    generated_at: datetime

class DailyWatchlist(BaseModel):
    trade_date: date
    candidates: list[WatchlistCandidate]   # already ranked
    universe_size: int
    tier_counts: dict[str, int]            # for observability
```

## Activity contract (v0)

Single shared schema (lives in `contract/`, generated for both Python and TS):

| Activity | Purpose |
|---|---|
| `get_quote(symbol, as_of=None)` | Latest bid/ask. `as_of` set in backtest, `None` in live (= cache read) |
| `get_bars(symbol, timeframe, lookback, as_of=None)` | OHLCV; returns `retrieved_at` so caller can drop stale bars. Reads from streaming cache in live, historical store in backtest |
| `get_position(symbol)` | Current holding |
| `get_balance()` | Cash, buying power |
| `place_order(idempotency_key, …)` | Submit order |
| `cancel_order(broker_order_id)` | Cancel one |
| `cancel_pending_orders(symbol)` | Cancel all pending for ticker (used on Execution failure) |
| `get_order_status(broker_order_id)` | Poll fill |
| `get_capabilities()` | Feature flags (fractional, options, OCO, …) |
| `fetch_news(symbol, since, as_of=None)` | News articles (source metadata required). Reads from news firehose cache in live, historical news store in backtest |
| `fetch_sec_filing(symbol, type)` | SEC filing text + metadata |
| `fetch_social_pulse(symbol)` | Social volume + age-stamped chatter |
| `score_sentiment(text)` | Deterministic sentiment score |
| `compute_indicators(bars, indicators[])` | VWAP, ORB, RSI, MAs, vol-delta — deterministic math |
| `get_universe(criteria)` | Universe of symbols |
| `get_premarket_movers()` | Top gappers / pre-market volume leaders |
| `prefilter_universe(symbols, criteria)` | Deterministic Tier-1 screen |
| `market_is_open(ts)` | True if regular session at `ts` (handles holidays, half-days, DST) |
| `next_market_close(ts)` | Next regular-session close after `ts` (e.g. 16:00 ET, 13:00 ET on half-days) |
| `next_market_open(ts)` | Next regular-session open after `ts` |
| `read_kill_switch()` | Returns current kill-switch state (active/inactive, reason) |
| `set_kill_switch(active, reason)` | Trip / reset |
| `read_daily_pnl(date)` | Realized P&L for a trading day |
| `read_workflow_history(workflow_id)` | Compact summary of a TradeWorkflow's decisions + outcome |
| `append_trading_memory(entry)` | Append-only write to `trading_memory.md` (size-capped) |
| `load_trading_memory(days)` | Read last N days of memory for context injection |

Every Activity above implicitly takes `(tenant_id, strategy_id)` as the first two parameters where they affect tenant-scoped state (orders, positions, balance, memory, kill switch, P&L, watchlist, audit). The contract carries them as required fields on every payload. Read-only market-data activities (`get_bars`, `get_quote`, `fetch_news`, `compute_indicators`) and universe utilities (`get_universe`, `get_premarket_movers`, `prefilter_universe`, `market_is_open` family) do not need tenant scoping — they read shared, non-sensitive data.

**Tenant + strategy Activities** (new in this contract version):

| Activity | Purpose |
|---|---|
| `tenant_registry_get(tenant_id)` | Returns `TenantConfig` (display name, billing state, default quotas, enabled brokers, kill-switch state) |
| `strategy_registry_get(tenant_id, strategy_id)` | Returns `StrategyConfig` (kind, prompt overrides, risk params, universe def, entry trigger, capital weight, optional custom workflows) |
| `strategy_registry_list_active(tenant_id)` | Active strategies for a tenant |
| `secrets_resolve(tenant_id, secret_name)` | Returns a `Secret`; audit-logged on every call. Only path to broker / LLM credentials |
| `quota_try_consume(tenant_id, resource, amount)` | `Allowed` or `Throttled{reason}`. Resources: `llm_tokens_daily`, `broker_calls_minute`, `concurrent_positions`, `concurrent_workflows` |
| `capital_allocate(tenant_id, balance)` | Splits balance across active strategies per `capital_weight` |
| `audit_log(tenant_id, event)` | Append tenant-scoped audit event (decision, signal, update, order, kill-switch trip, secret resolve, quota throttle) |
| `audit_query(tenant_id, time_range, kinds?)` | Read tenant's own audit trail |

## Repo layout

```
oh-my-tradeagent/
├── contract/                         # source of truth for cross-language types
│   ├── schemas/                      # JSON Schema files
│   ├── python/                       # generated Pydantic models
│   └── ts/                           # generated Zod / TS types
├── platform/                         # multi-tenant foundations
│   ├── tenant_registry/              # TenantRegistry deep module
│   ├── strategy_registry/            # StrategyRegistry + strategy versioning
│   ├── secrets_resolver/             # SecretsResolver (Vault / AWS SM adapters)
│   ├── quota_tracker/                # QuotaTracker (Redis-backed)
│   ├── capital_allocator/            # CapitalAllocator (static v0)
│   └── audit_logger/                 # AuditLogger (append-only, partitioned)
├── tenants/                          # per-tenant config (admin-provisioned in v0)
│   └── <tenant_id>/                  # tenant config file + enabled-strategies list
│       ├── tenant.yaml
│       └── strategies/
│           └── <strategy_id>.yaml    # prompt overrides, risk params, universe, triggers
├── orchestrator/                     # Python
│   ├── workflows/                    # NO broker imports; every input carries (tenant_id, strategy_id)
│   │   ├── universe_scan.py
│   │   ├── watch.py
│   │   ├── trade.py
│   │   ├── position.py
│   │   └── reflection.py
│   ├── agents/                       # *.md = source of truth for prompts
│   │   ├── technical_analyst.md
│   │   ├── technical_analyst.py      # thin Activity wrapper
│   │   ├── sentiment.md
│   │   ├── sentiment.py
│   │   ├── strategy.md
│   │   ├── strategy.py
│   │   ├── risk_manager.md
│   │   ├── risk_manager.py
│   │   ├── execution.md
│   │   ├── execution.py
│   │   ├── reflection.md
│   │   ├── reflection.py
│   │   └── loader.py
│   ├── activities/                   # non-agent activities (kill switch, memory I/O, etc.)
│   ├── memory/
│   │   └── trading_memory.md         # rolling reflection log
│   └── worker.py
├── brokers/
│   ├── alpaca/                       # TS worker
│   │   ├── activities/
│   │   ├── client.ts
│   │   └── worker.ts
│   └── schwab/                       # TS worker (OAuth, token refresh)
│       ├── activities/
│       ├── client.ts
│       └── worker.ts
├── marketdata-stream/                # TS worker — streaming subscriber + cached reads
│   ├── subscriber.ts                 # websocket lifecycle
│   ├── store.ts                      # in-memory cache (bars, quotes, news)
│   └── activities.ts                 # get_bars / get_quote / fetch_news
├── marketdata-rest/                  # TS worker — non-streamable (filings, fundamentals)
├── backtest/                         # everything specific to backtest mode
│   ├── historical_store/             # Parquet / SQLite of historical bars / quotes / news
│   ├── activities/                   # TS worker — same contract, historical backend
│   ├── runner/                       # Python — multi-seed orchestrator + result aggregation
│   └── metrics/                      # directional-agreement, P&L CV, hit-rate spread
├── infra/
│   ├── docker-compose.yml            # local Temporal dev server + Redis (kill switch)
│   └── secrets.example.env
├── ci/
│   ├── check_no_broker_imports.py    # guardrail: workflows ∌ broker imports
│   ├── check_no_backtest_imports.py  # guardrail: live code ∌ backtest imports
│   └── check_no_global_secrets.py    # guardrail: credentials only via SecretsResolver(tenant_id)
└── scripts/
    └── run_local.sh
```

## Task queue & worker layout

| Task queue | Worker | Responsibility |
|---|---|---|
| `agent-core` | Python | Workflows + all agent invocations (live) |
| `agent-core-backtest` | Python | Same workflows + agents, but BacktestConfig in input; no broker imports |
| `broker-alpaca-paper` / `-live` | TS | Alpaca activities |
| `broker-schwab-paper` / `-live` | TS | Schwab activities |
| `market-data-stream` | TS | Live streaming subscriber + cached reads (bars, quotes, news) |
| `market-data-rest` | TS | Live non-streamable (filings, fundamentals); REST + LRU cache |
| `market-data-backtest` | TS | Historical store reader; same Activity contract, reads from Parquet/SQLite |

Separate paper/live queues = mechanical safety; you can't accidentally route a backtest to production.

## Cross-cutting concerns

- **Prompts as data**: agent system prompts live in `agents/*.md`, loaded at runtime. Prompt edits are diff-reviewable independent of code changes.
- **Persistent memory**: `trading_memory.md` is append-only, written by `ReflectionWorkflow`, read by next-day agents.
- **Kill switch**: global per-day flag in an external KV (Redis or DB row), checked by every decision workflow at top of cycle.
- **Data freshness**: every market-data Activity returns `retrieved_at`; workflows reject data exceeding the timeframe-aware lag budget (60s for 5m bars, 120s for 15m+).
- **Structured outputs everywhere**: every agent returns a Pydantic model.
- **Idempotency + order journal**: every order goes through `OrderIntentJournal.record_intent` BEFORE the broker API is called, then `place_with_intent` for the submission. `idempotency_key = f"{workflow_id}:order:{step}"`. Alpaca uses `client_order_id`; Schwab uses the journal's UUID as a side-mapped key. `ReconciliationWorkflow` matches the journal against broker open orders on startup and recurring schedule to catch orphans (crash between record + place).
- **Event timestamp consistency**: every event (order, fill, signal, news, bar) carries a single `occurred_at` field, set by the originator (broker for fills, market-data subscriber for bars, workflow for decisions). Downstream code never invents timestamps. Source-of-truth discipline lifted from a prior trading system where mixed timestamp sources produced "FillReceived-before-OrderSubmitted" race conditions in backtest that took many commits to chase down.
- **Auth/secrets**: env vars early; abstraction so we can swap to Vault/AWS SM later. Schwab needs a scheduled token-refresh Activity.
- **Retries**: per-Activity policies; non-retryable error types in the contract (`InsufficientFundsError`, `InvalidSymbolError`, `AuthError`).
- **Rate limits**: worker concurrency caps per broker task queue (Alpaca 200/min, Schwab ~120/min).
- **Token cost control**: hard caps on Tier-2 and Tier-3 fan-out; cache Sentiment outputs by `(symbol, news_window_hash)` — if no new news, reuse yesterday's thesis.
- **Observability**: Temporal UI + OpenAI Agents SDK tracing + structured logs.
- **Risk guardrails**: bright-lines in workflow code (see Bright-line safety gates table); Risk Agent is judgment layer on top.
- **Live / backtest separation**: `live/` and `backtest/` are separate Python packages. CI guardrail enforces "live code never imports backtest, and vice-versa." Only the Activity *contract* is shared.
- **Tenant isolation (mechanical)**: every workflow input, every Activity payload, every memory entry, every audit event carries `(tenant_id, strategy_id)`. Workflow IDs include the tenant prefix (e.g., `tenant-acme/strategy-orb/trade-2026-05-11-NVDA-1`). Cross-tenant reads are physically impossible: code paths that need tenant data must take `tenant_id` as a parameter, and `SecretsResolver` is the only path to credentials. CI guardrail: "no Activity reads broker credentials except via `SecretsResolver(tenant_id)`."
- **Per-tenant kill switch**: the kill-switch flag is keyed by `(tenant_id, strategy_id)`. Tripping one strategy does not affect another, even within the same tenant. A platform-admin tenant-level trip halts all strategies for that tenant.
- **Per-tenant quotas**: every LLM call, every broker call, every new workflow start passes through `QuotaTracker.try_consume(tenant_id, …)`. Quota exhaustion is a non-retryable typed error that halts the in-flight workflow cleanly.
- **Tenant-scoped audit**: every decision, signal, update, order, kill-switch trip, secrets-resolve, and quota-throttle goes through `AuditLogger.log(tenant_id, …)`. Append-only, tenant-partitioned, queryable by the tenant.
- **Model version pinning**: production agents pin a dated model ID (e.g., `gpt-5-mini-2026-04`), not a moving alias. Backtest runs against the same pinned ID so test-time and prod-time behavior match.
- **Market calendar**: a single source of truth (NYSE calendar via `pandas_market_calendars` or equivalent), exposed through `market_is_open` / `next_market_close` / `next_market_open` Activities. All timer math (15:55 ET EOD close, 09:30 ET open, half-days, DST shifts, holidays) goes through this. Timezone-aware throughout; never bare-clock arithmetic. Backtest worker reads the same calendar for historical dates.

## Backtest mode

Backtests run the **same workflows, agents, and prompts** as live, just over historical data and on a separate task queue. The goal is to estimate future profitability before risking capital — not to replay past live decisions.

### Three pillars

**1. Historical market data via the same Activity contract.** The `market-data-backtest` TS worker reads from a local Parquet/SQLite store and implements the identical `get_bars`/`get_quote`/`fetch_news` signatures. Time-sensitive Activities take an `as_of` parameter (live passes `None` → cache read; backtest passes simulated time → historical lookup).

**2. Simulated time via `WorkflowEnvironment` time-skipping.** Workflows use `workflow.now()` and `workflow.sleep()` exactly as in live. In backtest, the Temporal test environment advances simulated time when no tasks are pending, so a `PositionWorkflow` that "waits until 15:55 ET" consumes zero wall-clock time.

**3. LLM via `temperature=0` + fixed `seed` + pinned model ID.** No record/replay cache. Bit-exact reproducibility is *not* a goal; "general direction of trades is stable across runs" is. Model-version drift is accepted as a known variance source.

### Multi-seed runs (the honest default)

Every backtest configuration runs **N seeds in parallel** (N=3–5 to start) and reports a distribution, not a number. A single-seed result is misleading.

A `BacktestRunnerWorkflow` starts N child workflows with seeds `[0, 1, 2, …]` and aggregates outputs.

### Stability metrics (gates on "is this strategy ready to consider live?")

| Metric | What it catches | Target |
|---|---|---|
| **Directional agreement** across seeds — % of decisions matching in side (BUY / SELL / SKIP) | Strategies whose decisions flip on LLM noise | ≥ 80% |
| **P&L coefficient of variation** across seeds | Profitability is luck-driven | < 0.3 |
| **Hit-rate spread** (max − min win rate across seeds) | Brittle edge | ≤ 10 percentage points |

If `directional_agreement < 80%`, the strategy's edge lives inside LLM noise rather than in the market — fix the strategy before considering it. Tightening the prompts, adding more deterministic features to the inputs, or constraining position sizes are typical remedies.

### What is explicitly NOT included

- **No record/replay cache.** No SQLite of LLM responses. No `--record` flag. (We considered it; rejected as unnecessary given relaxed determinism.)
- **No shared cache between live and backtest.** Live never writes anything the backtest reads.
- **No "approximate live replay."** The backtest does not try to reproduce a past live trading session — it estimates what the current strategy *would* do on historical data.

### Backtest workflow shape

```
BacktestRunnerWorkflow(config)
    │
    ├── (parallel) BacktestSessionWorkflow(seed=0, date=2026-04-15, …)
    ├── (parallel) BacktestSessionWorkflow(seed=1, date=2026-04-15, …)
    └── (parallel) BacktestSessionWorkflow(seed=2, date=2026-04-15, …)

each BacktestSessionWorkflow:
  • runs UniverseScanWorkflow as child (same code) with BacktestConfig
  • child workflows for Watch / Trade / Position spawn as in live
  • clock is the WorkflowEnvironment's simulated clock
  • activities route to market-data-backtest task queue
  • ReflectionWorkflow runs at simulated EOD; reflections written to a per-run memory file

aggregation:
  • realized P&L per session
  • directional-agreement matrix across seeds
  • CV of P&L, hit-rate spread
  • per-symbol decision diff (when a prompt changes)
```

## Phased delivery

| Phase | Scope | Done when |
|---|---|---|
| **0a. Skeleton** | Repo layout, cross-language contract (every payload has `tenant_id` + `strategy_id` as required fields), docker-compose for Temporal + Redis + Postgres, agent loader (`load_agent("name") → .md`), "hello world" workflow that accepts and echoes `(tenant_id, strategy_id)` — IDs are hardcoded constants at this stage, no registry lookups yet. CI runs (lint, type-check, contract round-trip). | `infra/docker-compose up` brings up Temporal/Redis/Postgres locally; Python workflow invoked with `(tenant_id="dev", strategy_id="default")` logs `hello tenant=dev strategy=default`; `load_agent` returns a real prompt string; CI passes |
| **0b. Platform foundations (tenant modules)** | Add the six platform deep modules: `TenantRegistry` (Postgres-backed), `StrategyRegistry` (Postgres-backed with version slot), `SecretsResolver` (local-file backend for dev), `QuotaTracker` (Redis-backed skeleton — `try_consume` returns `Allowed` until policy is wired), `CapitalAllocator` (static `capital_weight`), `AuditLogger` (append-only, tenant-partitioned). Provision a seed tenant + strategy via `tenants/dev/tenant.yaml` + `tenants/dev/strategies/default.yaml`. CI guardrail `check_no_global_secrets.py` added. | Seed tenant + strategy round-trip through registries; `SecretsResolver(tenant_id, "dummy")` returns the provisioned dev secret; `QuotaTracker.try_consume(tenant_id, "llm_tokens_daily", 100)` returns `Allowed`; `AuditLogger` captures the workflow start with `tenant_id`; CI guardrail blocks a PR that imports a credential outside `SecretsResolver` |
| **1. Read-only Alpaca** | TS worker + `get_balance` on `broker-alpaca-paper`; REST-only quote/bar as starting point | Python workflow fetches a real quote via Activity |
| **1.5. Market-data streaming worker** | `marketdata-stream` TS worker: websocket subscription + in-memory cache + cached `get_quote`/`get_bars` Activities; `MarketDataSubscriberWorkflow` lifecycle + heartbeat | Workflow reads bars from cache in ~1 ms; stale bars (>30s) correctly dropped |
| **2. Technical Analyst** | Agent driven by `technical_analyst.md` + `compute_indicators` (VWAP/ORB/RSI/MAs/vol-delta) | Workflow produces `TechnicalReport` on 5m/15m/30m bars; 1m timeframe rejected if requested |
| **3. Sentiment Agent** | News/filings/social Activities + Agent driven by `sentiment.md` with source + 24h-age enforcement | Single-symbol `SentimentReport` with all events sourced + age-tagged |
| **4. UniverseScanWorkflow (Tier 1+2)** | Scheduled daily scan: `get_universe` + `prefilter_universe` + parallel Technical Analyst → ranked top-30 | Daily schedule produces a top-30 list against the paper universe |
| **5. UniverseScanWorkflow (Tier 3)** | Parallel Sentiment over top-30 → `DailyWatchlist` of 5–15 candidates with theses + sourced catalysts | Watchlist persisted; viewable via `daily_watchlist` Query |
| **6. WatchWorkflow** | One per candidate, started by scan; Signals for `price_update`/`news_event`; spawns child `TradeWorkflow` on entry trigger | Watch fires a paper entry purely from signals; self-terminates at market close |
| **7. Strategy + Execution path** | Strategy Agent (`strategy.md`) + Execution Agent (`execution.md`) + `place_order` w/ idempotency. **All bright-line workflow gates wired up before any order can be placed**: market-hours check (via `market_is_open`), 2% position-size cap, 0.1% spread check, 5-min negative-news veto, auto-cancel-pending on Execution failure. | `TradeWorkflow` places a paper order initiated by `WatchWorkflow`; any oversized / wide-spread / vetoed / out-of-hours order is blocked deterministically before reaching the broker |
| **8. Risk Manager + kill switch** | Risk Manager Agent (`risk_manager.md`) as judgment layer above the bright-lines; Redis-backed `read_kill_switch` / `set_kill_switch`; daily-loss auto-trip wired to `read_daily_pnl` | Risk denies a marginal proposal that the bright-lines would have let through; kill-switch trip halts all new entries and signals open positions to close |
| **9. PositionWorkflow + EOD flat** | Trailing stop / profit target / 15:55 ET force-close timer; `force_close` Update; `current_reasoning` Query; **`SlippageAwareExits` deep module** for exit pricing (spread-adaptive limits, asymmetric timeouts, halt detection, EOD dust-sweep with marketable limits) | Paper position opens during session and is force-closed before 16:00 ET; exit orders use spread-aware pricing rather than blind market orders; sub-100-share dust gets swept clean at EOD |
| **10. ReflectionWorkflow + trading_memory.md** | Scheduled post-close; reads workflow history + P&L; Reflection Agent appends `DailyReflection`; next-day agents load N days via `load_trading_memory` | After a paper-trading day, memory has a structured entry; next day's agents reference it |
| **11. Schwab broker** | TS worker, OAuth refresh activity, contract parity tests. **Caveat**: Schwab Trader API's sandbox is more limited than Alpaca paper (no full order-fill simulation, partial market-data coverage). Treat Phase 11 as live-only integration; paper-mode E2E testing stays on Alpaca. Confirm developer-account access (Open Q #3) before starting. | Same workflows route real (small) orders to Schwab live, gated by promotion controls from Phase 13 |
| **12. Backtest mode** | `market-data-backtest` TS worker (Parquet/SQLite historical store, same contract); `as_of` plumbed through all time-sensitive Activities; `WorkflowEnvironment` time-skipping wired up; `BacktestRunnerWorkflow` runs N-seed sessions in parallel; stability metrics (directional agreement, P&L CV, hit-rate spread) computed; **`SimBroker` deep module** that implements the full broker port against historical data with realistic fill/slippage/halt simulation (no thin replay); **live↔backtest parity test** asserts that replaying a recorded paper-trading day through SimBroker matches the live decisions within stability-metric tolerances | A single trading day from history replays end-to-end at multi-seed, producing a P&L distribution + stability dashboard; live↔backtest parity test passes on a recorded session |
| **13. Multi-strategy within tenant** | Wire `CapitalAllocator` (static `capital_weight`); per-strategy kill switches; per-strategy memory; ability to run 2+ strategies in parallel for one tenant | One seed tenant runs ORB-momentum + mean-reversion strategies side-by-side in paper, each with isolated watchlist / positions / memory / kill switch |
| **14. Tenant onboarding (admin-provisioned)** | Tenant config schema; `tenants/<tenant_id>/` provisioning; production `SecretsResolver` backend (Vault / AWS SM); `QuotaTracker` enforcement at scale; per-tenant audit-log queries | A second tenant is onboarded via config; their workflows are credential-isolated from the first; quota exhaustion produces clean `QuotaExceededError` halts |
| **15. Live hardening** | Alerting, paper→live promotion gate, tenant-scoped kill-switch ops UI, per-tenant token-cost + workflow dashboards, strategy-version promotion gates (require green backtest) | Ready to point at live keys for the first promoted tenant + strategy |

## Prior art (worth studying before Phase 0)

- **HKUDS/AI-Trader** — fully automated agent-native trading platform. Modular markdown-based skill instructions (`/skills/ai4trade/SKILL.md`). Closest match to our `agents/*.md` pattern.
- **tauricresearch/TradingAgents** — reflection-loop pattern where agents write their own markdown logs to "learn" from yesterday's intraday mistakes. Our `ReflectionWorkflow` + `trading_memory.md` are modeled on this.
- **enving/TradeAgent** — Python-heavy but well-structured Alpaca prompts. Reference for Execution Agent and order-handling wording.
- **Dust.tt** — production-scale Temporal agentic workflows (millions of activities/day, financial data retrieval included). Reference for worker/queue scaling.
- **TiMi (Trade in Minutes)** — rationality-driven trading agents with temporal constraints (T₁, T₂) for execution-interval and risk control.
- **Durable AI Agent tutorial repos** — canonical pattern wrapping OpenAI SDK calls inside Temporal Activities; foundation for our agent loop.

## Open questions

1. **Asset class scope** — equities only for v0, or options too? (Affects contract complexity significantly.)
2. **Strategy paradigm** — discretionary (LLM picks symbols and trades) vs LLM-assisted (rule-based strategy emits candidates, LLM agents refine/gate)?
3. **Schwab access** — do you already have a Schwab developer account + approved app? Their onboarding is slow.
4. **Deploy target** — Temporal Cloud, self-hosted cluster, or local docker-compose for now?
5. **Single account or multi-account** from day one?
6. **News/filings/social data sources** — preferences (e.g., Polygon, Finnhub, Benzinga, SEC EDGAR direct, X firehose)?
7. **Universe definition** — curated list (S&P 500 / Russell 1000), dynamic top-N by dollar volume, or sector-focused?
8. **Tier-3 budget** — hard cap on Sentiment runs per day (token-cost ceiling)?
9. **Entry-trigger style** — deterministic level-breaks from Technical Analyst's `entry_hint`, LLM-judged per bar, or both?
10. **Kill-switch backend** — Redis, Postgres row, or simpler (e.g., file)? Affects multi-instance behavior.
11. **`trading_memory.md` storage** — committed to repo (transparent, slow), or persisted to S3/DB (faster, opaque)?
12. **Reflection scope** — only summarize the day, or also propose rule changes (which would need a human-review gate before agents adopt them)?
13. **Historical-data source for backtest** — Alpaca's historical API (free, equities only, depth limited), Polygon/Databento (paid, deeper history + news), or custom Parquet from a self-managed archive?
14. **Backtest stability thresholds** — confirm initial values (directional-agreement ≥ 80%, P&L CV < 0.3, hit-rate spread ≤ 10 ppts) or set them differently? These gate strategy promotion to live.
15. **Number of seeds for default backtest runs** — N=3 (cheaper), N=5 (more honest distribution), N=10 (best signal, 10× LLM cost first run)?
16. **Strategy promotion gate** — what gates paper→live? E.g. "≥ 20 paper trading days, paper-vs-backtest P&L within 1σ, backtest stability metrics all green, manual sign-off." Auto-promote on metric pass, or always manual?
17. **Tenant secrets backend** — Vault, AWS Secrets Manager, GCP Secret Manager, or other? Drives `SecretsResolver` adapter choice.
18. **Audit-log retention** — per-tenant configurable, or single platform default? How long?
19. **Quota policy defaults** — initial values for daily LLM tokens, broker calls/min, concurrent positions, concurrent workflows per tenant?
20. **Tenant onboarding shape in v0** — YAML config files in `tenants/<id>/` committed to repo, or a one-off CLI tool, or a JSON config in a Postgres `tenants` table?
21. **Capital allocation dynamics** — static `capital_weight` only in v0, or some dynamic rebalancing based on recent strategy performance (behind a feature flag)?
22. **Tenant-level kill switch scope** — platform admin trips kill switch on a tenant. Does it close positions instantly, or block new entries only and wait for existing to flatten naturally?
23. **Custom-workflow strategies** — when a strategy needs `kind: custom_workflows`, where do those workflow definitions live and how are they reviewed before live?
24. **Multi-broker failover** — currently we have multi-broker *support* (Alpaca + Schwab as separate task queues) but not *failover*. If Alpaca rate-limits or has a partial outage, does the engine route to Schwab automatically, halt cleanly via kill switch, or something in between? (Lifted from a prior trading system that supported two brokers but still halted on the primary's outage.)
25. **Multi-timeframe regime detection** — should `compute_indicators` produce a TREND/BALANCE/REVERSAL regime classification on 5m/15m bars that the Strategy agent can use as a filter? Empirically useful in a prior system; not strictly necessary at our timeframes.
26. **Dark-pool / institutional flow signal** — a prior system found late-session dark-pool buy-ratio Z-scores predictive (IC≈−0.039, t≈−3.92) and used them as a cross-strategy gate. Worth adding to the Sentiment agent's inputs in a later phase, but adds a data-source dependency. Decide before that phase.
