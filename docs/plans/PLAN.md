# Agentic Trading Bot — Copytrade v0 Plan

> Implementation plan for the copy-trade v0 product described in [`PRD.md`](../prd/PRD.md). The previous multi-agent intraday plan is preserved in [`PLAN-agentic-v2.md`](./PLAN-agentic-v2.md).

## Goal

A Temporal-orchestrated microservice system that mirrors options trades from a vetted Discord channel into one or more tenants' paper (then live) accounts, with deterministic risk gates, idempotent order placement, durable position lifecycle management, and full audit. Multi-tenant and multi-strategy are baked in from Phase 0 at the contract/workflow-ID level; v0 deploys with one tenant (`dev`) and one strategy (`copytrade-v1`).

## Constraints & operating envelope

- **Asset class**: US options only for v0 (BTO/STC on single-leg calls and puts). No equities, no multi-leg, no futures/FX/crypto.
- **Source**: one Discord channel per `(tenant, strategy)` deployment; pluggable source contract is a future enhancement.
- **Strategy horizon**: intraday-to-day-trade. All positions must be flat by EOD timer. Issue #15 (quant-analyst review): the prior `15:55 ET default; 15:30 ET for 0DTE` schedule pulled exits into the gamma/theta/liquidity collapse zone — by 15:50 ET on 0DTE the bot was paying half the remaining premium just to cross the spread, and pin risk on ITM contracts was real. Revised timer table: 0DTE force-flat at **15:00 ET** (14:45 ET for SPX/NDX-style names), a hard **15:25 ET** cancel-all-resting-orders sweep, and non-0DTE EOD at **15:45 ET**. The bot will NEVER hold an ITM 0DTE option past 15:30 ET regardless of author activity. Per-strategy overrides via `force_close_0dte_et` and `force_close_eod_et` in `StrategyConfig`.
- **Data freshness**: signal age veto rejects any post older than the per-side cap — `max_signal_age_bto_secs` (default 30s, BTO/AVG) and `max_signal_age_stc_secs` (default 60s, STC). Any value above 120s on either field is an explicit per-strategy override that should be reviewed. Issue #3 replaced the previous unified `max_signal_age_secs` (default 1800s) because a 30-min acceptance window on 0DTE / near-term options produces systematic adverse selection. A second-stage `BTO_PRICE_MOVED` gate also rejects BTO when live bid/ask (mid) has moved more than `bto_price_move_reject_pct` (default 10%) from `payload.price` since `posted_at` regardless of age (documented spec; market-data quote-fetch wiring lands separately). Broker quotes carry `retrieved_at`; quotes older than 5s for active contracts are refused at the Activity boundary.
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
│ signal-source-discord    │  Python sidecar, replica >= 1 per (tenant, strategy, channel)
│ - Playwright DOM watcher │
│ - regex parser           │
│ - in-memory dedupe LRU   │  (cost-only; correctness via Temporal workflow_id)
└────────────┬─────────────┘
             │ Temporal client: start_workflow(
             │   CopytradeSignalWorkflow,
             │   payload,
             │   workflow_id="t-{tenant}/s-{strategy}/sig/{signal_id}",
             │   id_reuse_policy=REJECT_DUPLICATE,
             │   task_queue="orchestrator-core",
             │   search_attributes={TenantStrategy: "t-{tenant}/s-{strategy}"})
             ▼
┌─────────────────────────────────────────────────────────────┐
│ Temporal Cluster (server + history + matching + frontend)   │
│   Advanced Visibility (Postgres 12+) — required from Phase 0│
│   custom Search Attributes: TenantStrategy, ContractSymbol  │
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
│     - KillSwitchWorkflow (per (tenant, strategy), long-running)     │
│   in-process activities (folded from risk-svc + contract-resolver):│
│     - risk.check_entry / check_exit                                 │
│       sub-gates: author_whitelist, signal_age, kill_switch,         │
│         max_positions, notional_cap_pct_of_equity,                  │
│         same_underlying_count, sector_concentration_cap,            │
│         daily_trade_count, drawdown_velocity_threshold,             │
│         pre_trade_check (cross-svc to exec-svc, Issue #6)           │
│     - contract.resolve / lookup (option_symbol_cache in Postgres)   │
│     - keyword_matcher.match                                         │
│   task queue: orchestrator-core                                     │
└──┬─────────┬─────────┬─────────┬───────────────────────────────────┘
   │         │         │         │
   ▼         ▼         ▼         ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐
│ exec-  │ │market- │ │platform│ │audit │
│alpaca  │ │data-svc│ │ -svc   │ │-svc  │
│/tradier│ └────────┘ └────────┘ └──────┘
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
| `signal-source-discord` | Python | (Temporal client only) | Playwright lifecycle; parser; `storage_state.json`; heartbeat; in-memory dedupe LRU (cost-only) | Local volume (cookies / heartbeat); replica >= 1 supported |
| `orchestrator-svc` | Java | `orchestrator-core` | All workflows + workflow-local activities (risk gates, contract resolution, keyword matcher) | Postgres (`option_symbol_cache`) for contract resolver |
| `exec-svc` | Java | `broker-<provider>-<env>` (e.g. `broker-alpaca-paper`) | Generic broker worker — one image, deployed once per `<provider>-<env>` pair. Loads the configured provider adapter (`alpaca`, `tradier`, `ibkr`, `schwab`) at startup. Place/cancel/status on the selected broker. | Postgres (`OrderIntentJournal` per broker env) |
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
| `PositionWorkflow` | minutes-to-hours | `CopytradeSignalWorkflow` on BTO fill | `t-<tenant>/s-<strategy>/pos/<OCC>/<entry_signal_id>` (entry_signal_id disambiguates same-day re-BTO on a closed contract) |
| `BTOEntryTimerWorkflow` | ≤ TTL (90s paper / 30s live) | `CopytradeSignalWorkflow` | `t-<tenant>/s-<strategy>/btottl/<signal_id>` |
| `ReconciliationWorkflow` | seconds per run | Temporal Schedule (every 5 min + startup) | `t-<tenant>/s-<strategy>/recon/<broker_env>/<run_id>` |
| `KillSwitchWorkflow` | long-running; `continueAsNew` after each market close | One-time bootstrap per `(tenant, strategy)`; survives DST/holidays/Schedule pauses | `t-<tenant>/s-<strategy>/killswitch` |

### Search attributes (required from Phase 0)

Temporal's standard SQL Visibility does **not** support `STARTS_WITH` on `WorkflowId` — only exact `=` / `!=` / `IN`. To filter by `(tenant, strategy)` or by contract symbol, the cluster runs with **Advanced Visibility** (Postgres 12+ adv-visibility schema; Elasticsearch not required) and we register two custom Search Attributes at Phase 0:

| Search attribute | Type | Set on | Used by |
|---|---|---|---|
| `TenantStrategy` | Keyword | Every workflow start (`"t-<tenant>/s-<strategy>"`) | `risk.count_running_positions`, `KillSwitchWorkflow` `risk_breach` cascade, `api-gateway` `GET /positions`, audit queries |
| `ContractSymbol` | Keyword | `PositionWorkflow` start (OCC symbol) | STC dispatch — find the active `PositionWorkflow` for a contract |

`listWorkflowExecutions(...)` is always filtered on these SAs plus `ExecutionStatus`, never on `WorkflowId` prefix. STC's OCC→active-position lookup is `TenantStrategy = "t-<t>/s-<s>" AND ContractSymbol = "<OCC>" AND ExecutionStatus = "Running"`, returning ≤ 1 workflow ID. For hot-path reads, the same OCC→workflow-ID mapping is cached in Redis (TTL = 1 trading day) so STC handling does not depend on Visibility latency.

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

Each Update has two stages: a synchronous **Validator** (must be deterministic, no I/O) and an async **Handler**. Callers choose a `WaitPolicy`: `Admitted` (returns once accepted into the workflow's queue), `Accepted` (returns after Validator passes), or `Completed` (returns after Handler finishes). `api-gateway` translates HTTP semantics from this column.

| Update | Target | Validator | WaitPolicy (default) | Handler effect | In-flight handling |
|---|---|---|---|---|---|
| `force_close` | `PositionWorkflow` | Caller is authorized operator | `Completed` (HTTP 200 with final order id) | Sets `force_exit=true`; cancels any in-flight exit, places marketable-to-bid for full remaining qty | If `exit_in_flight`, cancel current order then place force-exit; new exits/STCs rejected until force completes |
| `adjust_trail` | `PositionWorkflow` | New giveback pct in `(0, 0.5]`; trail armed | `Accepted` | Updates `giveback_pct` for subsequent `chandelier_tick` evals | No interaction with in-flight orders |
| `trip_killswitch` | `KillSwitchWorkflow` | Caller is authorized; not already tripped | `Accepted` (HTTP 202 immediately; cascade is async) | Sets `tripped=true`; fan-out `risk_breach` signals to all running workflows for `(tenant, strategy)` via `TenantStrategy` SA query | New `CopytradeSignalWorkflow`s fail-closed on `killswitch_state` query |
| `reset_killswitch` | `KillSwitchWorkflow` | Caller is authorized; `tripped == true`; dual-control approver IDs distinct (Phase 5 — Issue #21) | `Completed` | Sets `tripped=false`; writes `KillSwitchResetApproved` audit event with both approver IDs | None |

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
[CopytradeSignalWorkflow] (Java, orchestrator-svc, queue=orchestrator-core, search_attrs={TenantStrategy})
  1. audit.log(SignalReceived)                              [audit queue]
  2. cfg = platform.strategy_get(tenant, strategy)          [platform queue]
  3. decision = risk.check_entry(payload, cfg)              [in-process activity]
       - author in cfg.author_whitelist
       - age = now - payload.posted_at ≤ (BTO/AVG: cfg.max_signal_age_bto_secs; STC: cfg.max_signal_age_stc_secs)
       - (BTO only, Issue #3 secondary gate, doc-spec) |live_mid - payload.price| / payload.price ≤ cfg.bto_price_move_reject_pct  → else SIGNAL_REJECTED{BTO_PRICE_MOVED}
       - killswitch.query("killswitch_state").tripped == false  (workflow-not-found → fail-closed)
       - count_running_position_workflows: listWorkflowExecutions(
           WorkflowType='PositionWorkflow' AND
           TenantStrategy='t-<t>/s-<s>' AND
           ExecutionStatus='Running') < cfg.max_positions
       - (Issue #6 portfolio gates, each opt-in via cfg)
           sum_open_notional + new_notional ≤ cfg.notional_cap_pct_of_equity * equity
                                                            → NOTIONAL_CAP_EXCEEDED
           count(open positions whose underlying == payload.ticker) < cfg.same_underlying_count
                                                            → SAME_UNDERLYING_LIMIT
           count(open positions in resolve_sector(payload.ticker, cfg.sector_overrides))
             < cfg.sector_concentration_cap (sector 'unknown' is exempt)
                                                            → SECTOR_CONCENTRATION_EXCEEDED
           audit_log.count(SignalAccepted today, action=BTO) < cfg.daily_trade_count
                                                            → DAILY_TRADE_COUNT_EXCEEDED
           trailing_minute_mtm_loss_rate < cfg.drawdown_velocity_threshold
                                                            → DRAWDOWN_VELOCITY_EXCEEDED
           if cfg.pre_trade_check_enabled:
             exec-svc pre_trade_check Activity (broker-<broker_target>) returns
               (allowed, buying_power, pdt_status, margin_sufficient)
             reject when allowed=false OR buying_power < est_notional
               OR pdt_status='BLOCKED' OR margin_sufficient=false
               OR Activity throws (fail-closed)             → PRE_TRADE_CHECK_FAILED
     if not Allowed: audit.log(SignalRejected); return
  4. contract = contract.resolve(                           [in-process activity]
       ticker, expiry, strike, right, cfg.broker_target)
       → OCC symbol, bid, ask, mid (cached in option_symbol_cache)
  5. allocation = capital_allocator.for_strategy(tenant, strategy) * cfg.capital_weight
     qty        = clamp(floor(allocation / (payload.price * 100)),
                        cfg.min_contracts, cfg.max_contracts)
  6. intent_key = workflow_id + ":entry"
     # Issue #4: BTO pricing ladder. The initial limit is capped by BOTH a
     # fractional and an absolute slippage allowance, and never crosses
     # through the current ask. Verbatim formula from the issue:
     #   limit = min(ask, payload.price + max_slippage_abs, payload.price * (1 + max_slippage_pct))
     # The pseudocode below builds the ladder by appending only the cap
     # terms that are actually configured — None values must NEVER be
     # passed into arithmetic. With both caps unset, len(limit_terms)==1
     # and the fallthrough branch applies (`limit = payload.price OR
     # marketable_mid`).
     limit_terms = [ask]
     if cfg.max_slippage_abs is not None:
       limit_terms.append(payload.price + cfg.max_slippage_abs)
     if cfg.max_slippage_pct is not None:
       limit_terms.append(payload.price * (1 + cfg.max_slippage_pct))
     limit = min(limit_terms) if len(limit_terms) > 1 \
                              else (payload.price or marketable_mid)
     broker_order_id = exec.place_order(
       intent_key, contract, BUY, qty,
       limit=limit)   [broker-<target> queue]
       exec-svc internally:
         a. journal.record_intent(intent_key, ...)
         b. broker.place_order(client_order_id=intent_key, ...)
         c. journal.mark_submitted(broker_order_id)
  7. start_child BTOEntryTimerWorkflow(
       id="t-<tenant>/s-<strategy>/btottl/<signal_id>",
       ttl_secs=cfg.pending_ttl_{paper|live}_secs,
       parent_workflow_id=this)
  8. workflow.await(fill_received OR repeg_due OR ttl_expired)
     # Issue #4: single re-peg policy. After cfg.repeg_after_ms (default
     # unset → no re-peg), if still unfilled, cancel the current limit and
     # re-submit ONCE at the slippage-capped ceiling against a
     # FRESHLY-FETCHED quote (NOT the step-4 ask):
     #   ask_at_repeg = contract.refresh_quote(contract).ask
     #   limit = ladder(ask_at_repeg, cfg)    # same None-aware ladder
     #                                        # as step 6
     # The slippage caps remain anchored to payload.price (not the new
     # ask) so a runaway premium cannot lift the limit past the operator-
     # configured tolerance — only the ask term in min() is refreshed.
     # Rationale: options premiums move 20-50%+ on the re-peg horizon;
     # re-using the stale step-4 ask would silently re-cross the ladder
     # on a moved market.
     # After that single re-peg the next gate is ttl_expired only —
     # there is no second re-peg in v0.
  9a. on fill:
        start_child PositionWorkflow(
          id="t-<tenant>/s-<strategy>/pos/<OCC>/<signal_id>",   # entry_signal_id disambiguates re-BTO
          search_attrs={TenantStrategy: "t-<t>/s-<s>",
                        ContractSymbol: "<OCC>"},
          parent_close_policy=ABANDON,
          input=PositionWorkflowInput(contract, qty, entry_premium, source_signal_id))
        # Cache OCC -> workflow_id in Redis (TTL = 1 trading day) for hot-path STC lookup.
        redis.setex(f"pos:t-<t>/s-<s>:<OCC>", 86400, position_workflow_id)
        audit.log(EntryFilled); return
  9b. on TTL expiry (unfilled-limit failure, Issue #4):
        exec.cancel_order(broker_order_id)
        # Unfilled BTO is a failure event, NOT a silent timeout:
        #   - audit.log(EntryExpired, {reason: "bto_unfilled", limit, ask,
        #              payload_price, max_slippage_abs, max_slippage_pct,
        #              repeg_count})
        #   - signal_workflow(killswitch_workflow_id, "bto_unfilled",
        #              {signal_id, contract, payload_price, ask_at_expiry})
        # so operators see the timeout in the audit stream and the kill
        # switch can count consecutive unfilled BTOs.
        return
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
  4. contract = contract.resolve_occ(ticker, expiry, strike, right)   # in-process
  5. fraction = KeywordPartialMatcher.match(payload.tail, cfg.partial_fractions)
                .orElse(cfg.default_stc_fraction)
  6. # Find active PositionWorkflow for this OCC. Hot path: Redis cache.
     #                                              Fallback: Visibility query.
     position_id = redis.get(f"pos:t-<t>/s-<s>:{OCC}")
     if not position_id:
        results = listWorkflowExecutions(
          WorkflowType='PositionWorkflow' AND
          TenantStrategy='t-<t>/s-<s>' AND
          ContractSymbol='{OCC}' AND
          ExecutionStatus='Running')
        position_id = results.first()?.workflow_id
     if not position_id:
        # STC arrived before BTO filled. Buffer up to 90s, re-check cache + Visibility.
        for _ in range(9):
           workflow.sleep(10s)
           position_id = redis.get(...) or listWorkflowExecutions(...).first()
           if position_id: break
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
    # Issue #4: STC pricing ladder. Verbatim from the issue:
    #   limit = max(bid, ref_premium - giveback)
    # where `giveback` is sourced from cfg.trail_giveback_pct *
    # ref_premium (re-using the Phase 4 trailing-stop knob as the STC
    # giveback when no separate field is configured). The max() guard
    # protects against `ref_premium < current bid` — the silent edge-loss
    # case from Issue #4: if the author's quoted exit is below the live
    # bid, we MUST anchor to bid rather than throw away free premium.
    giveback = ref_premium * (cfg.trail_giveback_pct or 0)
    exec.place_order(intent_key, contract, SELL_TO_CLOSE, qty_to_close,
                     limit=max(bid, ref_premium - giveback))
    # Issue #4: single re-peg policy, mirroring BTO step 8. After
    # cfg.repeg_after_ms (default unset → no re-peg), if still unfilled,
    # cancel the current limit and re-submit ONCE against a
    # FRESHLY-FETCHED quote:
    #   bid_at_repeg = contract.refresh_quote(contract).bid
    #   limit = max(bid_at_repeg, ref_premium - giveback)
    # The max() guard still protects against ref_premium < bid_at_repeg.
    # The giveback term remains anchored to the original ref_premium so a
    # runaway bid cannot strand exits — only the bid floor is refreshed.
    # After this single re-peg the next gate is exit_ttl_expired only;
    # there is no second re-peg in v0 (multi-step walk is out of scope).
    await fill_received OR repeg_due OR exit_ttl_expired
    if exit_ttl_expired (unfilled-limit failure, Issue #4):
       exec.cancel_order(broker_order_id)
       # Same failure-event treatment as BTO:
       #   - audit.log(ExitExpired, {reason: "stc_unfilled", limit, bid,
       #              ref_premium, giveback, repeg_count})
       #   - signal_workflow(killswitch_workflow_id, "stc_unfilled",
       #              {signal_id, contract, ref_premium, bid_at_expiry})
       # so operators see late/missing exits in the audit stream rather
       # than as a silent no-op.
       exit_in_flight = false
       return
    remaining_qty -= filled_qty
    exit_in_flight = false
    if remaining_qty / original_qty < 0.005:
       complete_workflow

  signal handler arm_chandelier:
    if trailing_armed: return
    # Issue #14 (quant-analyst review): `peak` is the running max of the
    # mid (NOT last trade, NOT bid — last trade is stale on thin options
    # and bid is the firing reference, not the high-water reference)
    # over a 5-10 second window so a single bad print cannot bias it.
    # `payload.peak_premium` from the partial-exit moment seeds the
    # window; subsequent ticks roll it forward.
    peak_premium = payload.peak_premium       # mid at arm-time (window seed)
    giveback     = payload.giveback_pct
    sub_threshold_streak = 0                  # debounce counter; see chandelier_tick
    trailing_armed = true
    market_data.subscribe_premium(contract)   # async; fires chandelier_tick signals

  signal handler chandelier_tick:
    if not trailing_armed: return
    # Issue #14 (quant-analyst review): disarm in the final
    # cfg.trail_disarm_minutes_before_close minutes (default 30) before
    # market close — by then theta giveback dominates real momentum and
    # the trail becomes a noise-driven flush. The EOD timer at 15:55 ET
    # handles the exit instead. Disarming silently is intentional: once
    # disarmed, ticks keep flowing (cheap) but never fire.
    if now >= market_close - cfg.trail_disarm_minutes_before_close minutes:
       trailing_armed = false
       return
    # market-data-svc populates tick.premium with the mid (bid+ask)/2
    # smoothed over a 5-10s window — last-trade is stale on thin options.
    # PositionWorkflow only retains the running max here; it does not
    # recompute the mid.
    if tick.premium > peak_premium: peak_premium = tick.premium
    # Issue #14: fire requires cfg.trail_debounce_ticks consecutive ticks
    # (default 2) below `peak_premium * (1 - giveback)` so a single bad
    # print cannot trigger an exit. A tick at-or-above threshold resets
    # the streak.
    if tick.premium < peak_premium * (1 - giveback):
       sub_threshold_streak += 1
       if sub_threshold_streak >= (cfg.trail_debounce_ticks or 2):
          # Issue #14: on fire, route MARKETABLE-TO-BID (limit = bid,
          # crossing the spread to take liquidity NOW) rather than the
          # passive `limit = ref_premium` used in author-driven STC
          # (the patient ladder is wrong when the trail just tripped —
          # asymmetric urgency: protect the gain before further giveback).
          trailing_armed = false   # guard against re-entry before fill lands
          trigger_full_exit("chandelier_trail", order_style="marketable_to_bid")
    else:
       sub_threshold_streak = 0
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

The auto-trip evaluator combines two independent loss signals so options
positions cannot bleed 80% mark-to-market between fills without halting:

- **Realized PnL** — `platform.daily_pnl(tenant, strategy, day)` against
  `cfg.daily_loss_threshold` (sum of EntryFilled/ExitFilled premia from
  audit_log; cumulative since session open).
- **Unrealized MTM** — `market-data-svc.daily_unrealized_pnl(tenant,
  strategy, day)` against `cfg.daily_unrealized_loss_threshold`. Computed
  as Σ (current_premium − entry_premium) × qty × 100 across open
  PositionWorkflows, refreshed by the market-data feed; the timer below
  re-evaluates every 60s during market hours.

Auto-trip fires on the **more conservative** of the two signals — i.e.,
whichever threshold breaches first triggers the cascade. Each threshold
is independently configurable; setting either to null disables that arm
of the trigger. At least one must be set in production.

```
One-time bootstrap per (tenant, strategy):
   start KillSwitchWorkflow("t-<tenant>/s-<strategy>/killswitch",
     search_attrs={TenantStrategy: "t-<tenant>/s-<strategy>"})

KillSwitchWorkflow (long-running, NOT a daily Schedule — survives DST/holidays/pauses):
  state = {tripped: false, reason: "", tripped_at: null, trading_day: today}

  loop:
    # Internal timer fires every 60s during market hours
    workflow.sleep(60s)
    if not platform.market_is_open(now):
       if now > today.market_close:
          # Reset daily counters; preserve manual-trip state across days only if operator chose to.
          state.trading_day = next_market_open_date(now)
          # continueAsNew keeps history bounded and refreshes search attributes
          continueAsNew(state)
          return
       continue

    # Realized arm — closed P&L bound (cumulative, since session open).
    realized = platform.daily_pnl(tenant, strategy, state.trading_day)
    if cfg.daily_loss_threshold is not null and realized < -cfg.daily_loss_threshold:
       self.update("trip_killswitch",
                   {reason="auto:daily_realized_loss", value=realized})
       continue

    # Unrealized arm — MTM drawdown across open PositionWorkflows. Without
    # this, held options can lose 80% MTM (the dominant failure mode for
    # 0DTE / earnings) before any realized exit fires.
    unrealized = market_data.daily_unrealized_pnl(tenant, strategy,
                                                  state.trading_day)
    if cfg.daily_unrealized_loss_threshold is not null and \
       unrealized < -cfg.daily_unrealized_loss_threshold:
       self.update("trip_killswitch",
                   {reason="auto:daily_unrealized_loss", value=unrealized})

  trip_killswitch Update handler:
    if not state.tripped:
       state.tripped = true; state.reason = ...; state.tripped_at = workflow.now()
       audit.log(KillSwitchTripped, {reason, realized_pnl, unrealized_pnl})
       # Cascade via Search Attribute, not workflow-ID prefix
       for wf in listWorkflowExecutions(
            TenantStrategy='t-<tenant>/s-<strategy>' AND
            ExecutionStatus='Running'):
          if wf.workflow_id != self.workflow_id:
             signal_workflow(wf.workflow_id, "risk_breach", {reason})

risk.check_entry reads state via KillSwitchWorkflow.query("killswitch_state").
On workflow-not-found (e.g. cluster restart before bootstrap), the `risk.check_entry` Activity fails CLOSED — entries rejected with `kill_switch_unavailable` until the workflow is started.

If market-data-svc is unavailable (`daily_unrealized_pnl` raises or
returns null), the unrealized arm fails CLOSED for the affected
evaluation cycle — the workflow logs an `audit.log(KillSwitchDegraded,
{reason:"market_data_unavailable"})` event and continues evaluating the
realized arm, while `risk.check_entry` rejects new entries with
`kill_switch_unavailable` until market-data recovers. Silent suppression
of the unrealized arm is never permitted.
```

#### Reset SOP

A tripped kill switch is an explicit safety state. Resetting it requires
**dual-control** human approval — no single operator and no automated
process may reset it.

- **Who authorizes.** Two distinct operators with the `killswitch:reset`
  role in `platform-svc`'s RBAC. The primary requests the reset; the
  secondary independently approves. The two operator identities MUST be
  distinct — same-user resets are rejected by `reset_killswitch` (see
  Phase 5 — Issue #21).
- **Evidence required.** Before requesting the reset the primary
  operator attaches: (1) the original trip's `KillSwitchTripped` audit
  event ID, (2) a written root-cause statement (free text, captured on
  the request), (3) a link or doc reference describing the remediation
  (closed positions, broker reconciliation completed, market-data feed
  restored, etc.), and (4) the resumption decision (continue trading
  vs. drain to flat). All four fields are required by the api-gateway
  `POST /killswitch/reset` request body; missing fields produce a 400.
- **Dual-control approval flow.** Primary calls `POST /killswitch/reset`
  with the evidence above; the request enters a `pending_secondary`
  state. Secondary calls `POST /killswitch/reset/approve` referencing
  the pending request ID. The `reset_killswitch` Update fires only when
  both approvals are recorded with distinct operator identities; if the
  secondary identity matches the primary's, the Update is rejected and
  the pending state is dropped. Pending requests expire after 30 minutes
  with an `audit.log(KillSwitchResetExpired)` event.
- **Audit event.** On successful reset the workflow writes a
  `KillSwitchResetApproved` audit event whose `subject` carries
  `{trip_event_id, root_cause, remediation_ref, resumption_decision,
  approver_primary, approver_secondary, requested_at, approved_at}`.
  Both approver identities are persisted in the event — never collapsed
  into a single field — so post-hoc audit can verify dual-control was
  actually enforced rather than logged after the fact.
- **Post-reset behaviour.** `cfg.reset_cooldown_secs` (if configured)
  blocks new entries with `KILL_SWITCH_COOLING_DOWN` for the cooldown
  window after a successful reset, closing the signal-backlog stampede
  vector.

## Cross-cutting concerns

- **Idempotency layers** (3): Temporal `workflow_id` REJECT_DUPLICATE → `OrderIntentJournal` `intent_key` → broker `client_order_id`. Each layer protects a specific crash window; their composition gives "exactly one order per Discord line, ever." The sidecar maintains an in-memory dedupe LRU as a cost optimization (suppresses duplicate `start_workflow` RPCs across DOM polls); it is **not** a correctness layer, so the sidecar can run with replica >= 1 for HA.
- **Workflow visibility**: self-hosted Temporal with **Advanced Visibility on Postgres 12+** (no Elasticsearch). Two custom Search Attributes (`TenantStrategy` and `ContractSymbol`) are registered at Phase 0 and stamped on every workflow start; all cross-workflow listing (max-positions check, killswitch fan-out, STC dispatch, `api-gateway` `GET /positions`) goes through `listWorkflowExecutions` filtered on these SAs plus `ExecutionStatus`. `WorkflowId STARTS_WITH` is **not** used — Temporal's SQL Visibility does not support it for the system `WorkflowId` field even on advanced visibility. Adequate to ~1M open workflows per namespace; ES revisitable only if ad-hoc audit text-search is later required.
- **Multi-tenant scoping**: `(tenant_id, strategy_id)` on every payload + workflow ID prefix. CI guardrails:
  - `check_no_global_secrets.py` — credentials only via `SecretsResolver(tenant_id, ...)`.
  - `check_workflow_determinism.py` — workflow classes do not import `java.io.*`, `java.net.*`, `java.time.Clock`, `Math.random`, or call `System.currentTimeMillis()` / `Instant.now()` / `LocalDateTime.now()`.
  - `check_no_cross_service_imports.py` — services do not depend on each other except via `contract/java`.
  - `check_contract_drift.py` — JSON Schema → Python/Java round-trip on fixtures.
- **Secrets**: `SecretsResolver` Activity is the only path. Audit-logged on every resolve. Backed by Vault / AWS Secrets Manager in prod; local-file fallback in dev only.
- **Audit**: Every state-changing Activity emits `audit.log(tenant, event)`. Append-only Postgres table partitioned by `tenant_id`, indexed on `(tenant_id, occurred_at, event_kind)`. Queryable via api-gateway with tenant-scoped auth.
- **Market calendar**: `platform.market_is_open`, `platform.next_close`, `platform.is_expiry`. Single source of truth. `PositionWorkflow` registers two timers at construction sourced from `StrategyConfig` (defaults: EOD `force_close_eod_et` 15:45 ET; expiry `force_close_0dte_et` 15:00 ET on 0DTE — 14:45 ET for SPX/NDX-style underlyings). A hard 15:25 ET cancel-all-resting-orders sweep also runs to clear unfilled exits before the final liquidity collapse. The bot will NEVER hold an ITM 0DTE option past 15:30 ET regardless of author activity (Issue #15 — quant-analyst review: by 15:30 ET ATM/ITM 0DTE gamma/theta and the bid have collapsed enough that author exit signals are no longer fillable at fair value).
- **Event timestamp consistency**: each event carries `occurred_at` set by originator. Broker sets fill timestamps; sidecar sets `posted_at`; workflow sets `decided_at`. Mixed timestamp sources produced "FillReceived-before-OrderSubmitted" race conditions in a prior trading system; we avoid that here by ownership.
- **Observability**:
  - Micrometer meters in every Java service; exported to Prometheus via Spring Actuator `/actuator/prometheus`.
  - OpenTelemetry traces span sidecar → Temporal → workflow → activities. Trace context propagated via Temporal's interceptor and OTel context propagation in Python.
  - Per-service health endpoints (Spring Actuator `/actuator/health`).
  - Temporal UI as the workflow-history viewer; api-gateway exposes a `/positions` view for operator-facing status.
- **Portfolio-level risk gates (Issue #6)**: `risk.check_entry` runs six opt-in portfolio-level sub-gates after the per-order ones. `notional_cap_pct_of_equity` bounds (sum of open-position notional + new notional) against equity. `same_underlying_count` caps concurrent positions on a single underlying. `sector_concentration_cap` caps concurrent positions in a sector (tickers resolve via per-strategy `sector_overrides`; unmapped tickers map to `unknown` and are exempt). `daily_trade_count` caps accepted BTOs per UTC trading day (counted from `audit_log` SignalAccepted events). `drawdown_velocity_threshold` (per-minute MTM loss rate) is an intraday rate-of-loss circuit breaker complementing the cumulative `daily_loss_threshold`. `pre_trade_check` is a cross-service Activity routed to `broker-<broker_target>`; the broker reports buying_power / PDT status / margin sufficiency, and risk-svc rejects when `allowed=false`, `buying_power < estimated_notional`, `pdt_status='BLOCKED'`, or `margin_sufficient=false`. Each gate is strictly opt-in (null config disables); a missing collaborator (zero equity, throwing pre-trade check) fails closed rather than silently passing. New `RejectionReason` codes: `NOTIONAL_CAP_EXCEEDED`, `SAME_UNDERLYING_LIMIT`, `SECTOR_CONCENTRATION_EXCEEDED`, `DAILY_TRADE_COUNT_EXCEEDED`, `DRAWDOWN_VELOCITY_EXCEEDED`, `PRE_TRADE_CHECK_FAILED`.
- **Retries**: per-Activity retry policies; non-retryable types (`InsufficientFundsError`, `InvalidContractError`, `AuthError`, `QuotaExceededError`, `KillSwitchActiveError`) short-circuit.
- **Rate limits**: per-broker worker concurrency caps (Alpaca 200/min, Tradier 120/min, IBKR ~50/sec but with separate order rate-limiting).
- **Live / paper separation**: separate Temporal task queues; CI guardrail blocks code paths that route paper to live.
- **Workflow input schema versioning**: every workflow-input DTO carries a `schema_version` integer field. Workers reject inputs whose `schema_version` is newer than the running build (forces orchestrator-svc rollback rather than ambiguous replay). The runbook for orchestrator-svc redeploy with running long-lived `PositionWorkflow`s lives at `docs/ops/orchestrator-redeploy.md` and offers two paths: (a) drain — pause new `start_child PositionWorkflow` calls, wait for existing to complete, deploy; (b) pin — bundle a versioned `contract/java` jar with each orchestrator release so both old-version and new-version workers can replay any in-flight workflow.
- **PositionWorkflow code versioning**: `Workflow.getVersion(...)` checkpoints around any logic that may change across orchestrator-svc redeploys (exit ladder math, trail evaluation). New code paths run only on new positions; existing positions complete on old logic. Combined with `schema_version` above, this gives a documented "drain or pin" choice rather than ad-hoc replay surprises.

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
│   │       ├── activities/              # in-process: risk gates, contract resolver, keyword matcher
│   │       └── OrchestratorApplication.java
│   ├── exec/                            # generic broker worker — adapters under broker/<provider>/
│   │   └── src/main/java/com/ohmytradeagent/exec/
│   │       └── broker/{stub,alpaca,tradier,ibkr,schwab}/   # one adapter per provider
│   ├── market-data/                     # adapters under marketdata/<provider>/ (Phase 2c.2)
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
| **0. Skeleton + contract** | Maven root POM with `contract-java` module and a placeholder `audit` service. JSON Schema for `CopytradeSignalPayload` + `AuditEvent` + `PositionWorkflowInput`, each carrying `schema_version`. Generated Java DTOs + pydantic models. docker-compose with Temporal + Postgres + Redis + Prometheus + OTel collector. **Temporal cluster configured for Advanced Visibility (Postgres 12+) and bootstrapped with custom Search Attributes `TenantStrategy` (Keyword) and `ContractSymbol` (Keyword).** CI runs (Spotless / Checkstyle / `mvn verify` / contract round-trip). | `mvn verify` green; `docker compose up` brings up the stack; `temporal operator search-attribute list` shows `TenantStrategy` and `ContractSymbol`; a hand-crafted `temporal workflow start CopytradeSignalWorkflow` with both SAs set audit-logs the payload and is discoverable via `listWorkflowExecutions(TenantStrategy='t-dev/s-copytrade-v1')`. |
| **0b. Platform foundations** | `platform-svc` with `TenantRegistry`, `StrategyRegistry`, `SecretsResolver` (local-file backend), `QuotaTracker` (Redis skeleton — returns Allowed), `MarketCalendar`, `CapitalAllocator` (static `capital_weight`). Seed `tenants/dev/tenant.yaml` + `tenants/dev/strategies/copytrade-v1.yaml`. `audit-svc` append-only writes wired through jOOQ. CI guardrail `check_no_global_secrets.py` added. | Workflow can call `platform.strategy_get(tenant, strategy)` and receive `StrategyConfig` parsed from YAML; `SecretsResolver(tenant, "dummy")` returns the seed secret; `audit_log` writes a row to Postgres. CI blocks a PR that imports a credential outside `SecretsResolver`. |
| **1. Sidecar → Temporal** | Port `services/discord-copytrade` from oh-my-opentrade. Replace HTTP `emit.py` with Temporal Python SDK `client.start_workflow` that sets `TenantStrategy` Search Attribute on every start. Workflow body is a no-op that audit-logs the payload. Bootstrap container preserved for one-time Discord login. Sidecar uses in-memory LRU only (no `seen_ids.json`); supports replica >= 1. Parser unit tests ported. | A Discord post triggers a workflow with `workflow_id = "t-dev/s-copytrade-v1/sig/<message_id>:<i>"` and `TenantStrategy='t-dev/s-copytrade-v1'`. Re-posting the same message produces `WorkflowAlreadyExists` (durable dedupe verified). Two sidecar replicas running concurrently do not produce duplicate workflows. Parser tests pass (40+ adversarial cases). |
| **2a. Risk + contract resolver (in-process; no broker)** | In-process `risk.check_entry` Activity (author whitelist + signal-age veto + killswitch read (returns false for now) + max-positions count via `listWorkflowExecutions(TenantStrategy=..., WorkflowType='PositionWorkflow', ExecutionStatus='Running')`). In-process `contract.resolve` Activity with deterministic OCC symbol generation + (deferred) broker `lookup` cross-check + `option_symbol_cache` (Postgres). Capital-weight sizing wired. No order placement yet. | A vetted BTO routes through risk → contract → audit-log only; rejected BTOs (bad author / stale / over max-positions) produce typed audit events; no `OrderIntent` is created. |
| **2b. Exec paper (no exits yet)** | `exec-svc` skeleton with `OrderIntentJournal` (jOOQ + Postgres), `OptionsBroker` port, `StubBroker` adapter, `place_order` / `cancel_order` / `get_order_status` Activity contract. Full BTO path wired in `CopytradeSignalWorkflow`; BTO TTL handled inline (no separate timer workflow yet). Originally shipped as `exec-tradier-paper-svc`; renamed/generalized in Phase 2c. | A vetted BTO produces a paper option order with no duplicates under simulated `orchestrator-svc` crash + restart between journal write and broker call. Journal idempotency tests run in CI against `Testcontainers` Postgres. |
| **2c. Broker adapter pattern + Alpaca paper** | Generalize `exec-tradier-paper-svc` → `exec-svc` (provider-agnostic image). Add `OptionsBroker` adapters under `broker/<provider>/`: ship `alpaca/AlpacaPaperBroker` (REST → `paper-api.alpaca.markets`) as the first real adapter. Parallel `MarketDataProvider` port + `alpaca/AlpacaMarketData` (REST quote + WebSocket stream) in `market-data-svc`. `broker_target` config key shape becomes `<provider>-<env>` (e.g. `alpaca-paper`). Tradier / IBKR / Schwab adapters land as follow-ups (2c.x). | A paper BTO under tenant `dev` with `broker_target=alpaca-paper` produces a real order at Alpaca paper sandbox with the journaled `client_order_id`; STC partial via `arm_chandelier` arms a real Alpaca WS premium stream and fires `chandelier_tick` when the premium gives back `trail_giveback_pct`. Per-adapter contract tests run in CI against mocked HTTP backends. |
| **3. PositionWorkflow + STC exits + BTO TTL** | `PositionWorkflow` (Java) with `partial_exit` signal handler, EOD/expiry timers, signal-id dedupe set, in-flight exit guard. `BTOEntryTimerWorkflow`. `CopytradeSignalWorkflow` STC branch with `KeywordPartialMatcher` + Redis-cached OCC→workflow_id lookup with Visibility fallback + signal dispatch. `ContractSymbol` SA set on every `PositionWorkflow`. | One paper position: BTO → filled (PositionWorkflow starts; cache populated) → STC "half out" → 50% closes → STC "out" → remainder closes → workflow completes. `TestWorkflowEnvironment` E2E test green. EOD timer at simulated 15:55 ET force-flattens. STC dispatched after orchestrator restart (cache cold) still finds the position via Visibility. |
| **4. Trailing exit (CHANDELIER_TRAIL)** | `market-data-svc` streaming option quotes via broker WS; `subscribe_premium` Activity that fires `chandelier_tick` Signals into `PositionWorkflow`. `PositionWorkflow.arm_chandelier` handler + tick evaluation. First-partial-arms logic in STC flow. Issue #14 (quant-analyst review): `peak` is the running max of the mid over a 5-10s window (single-tick filter), fire requires `cfg.trail_debounce_ticks` (default 2) consecutive sub-threshold ticks, on fire the order is marketable-to-bid (not the patient `limit=ref_premium` used in author-driven STC), and the trail disarms in the final `cfg.trail_disarm_minutes_before_close` (default 30) minutes before close so the EOD timer handles the exit. | After arm via simulated STC, an injected quote sequence reaching peak then giving back `cfg.trail_giveback_pct` over `cfg.trail_debounce_ticks` consecutive ticks triggers a marketable-to-bid full exit; a single bad print does NOT trigger; a tick sequence arriving inside the disarm window does NOT trigger. |
| **5. Kill switch + reconciliation + api-gateway** | `KillSwitchWorkflow` (long-running, `continueAsNew` daily) with `trip_killswitch` / `reset_killswitch` Updates, `risk_breach` cascade via `listWorkflowExecutions(TenantStrategy=...)` + `signalWorkflow`. Auto-trip activity on daily-loss threshold. `risk.check_entry` fails closed on missing kill-switch workflow. `ReconciliationWorkflow` (Temporal Schedule every 5 min + startup). `api-gateway` with `GET /positions`, `POST /killswitch/trip`, `POST /positions/:id/force-close`, `GET /audit`. | Tripping killswitch via REST halts new entries and force-closes open positions within 5s. Recon catches a manually-induced journal-broker mismatch and surfaces it via audit. Restarting Temporal cluster without the kill-switch workflow blocks all new entries with `kill_switch_unavailable`. |
| **5b. Production topology + ops** | k8s manifests (or Nomad / docker-swarm, TBD); CI/CD pipeline (image build + push + deploy gates); on-call runbooks for `orchestrator-redeploy`, `discord-session-expired`, `temporal-cluster-down`, `kill-switch-stuck`, `journal-broker-mismatch`; secret-rotation policy (Vault transit / AWS-SM versioning); audit-log retention policy. Blue/green or drain-then-pin redeploy support for orchestrator-svc with running `PositionWorkflow`s. | A green redeploy of orchestrator-svc with 3+ running `PositionWorkflow`s completes with zero workflow stalls; runbook drills pass for at least 3 of the 5 documented incidents. |
| **5b.E. Consolidate Temporal cluster** | The homelab currently runs two independent Temporal clusters: `temporal/temporal-frontend` (shared, used by the `apps/cookbook-agentic-loop` workload) and `copytrade/temporal` (spun up by 5b.A for copy-trade only). Consolidate onto the shared cluster: register a Temporal namespace `copytrade` on `temporal/temporal-frontend`, repoint copy-trade services (`orchestrator-svc`, `exec-svc-*`, `audit-svc`, `market-data-svc`, `api-gateway`, `signal-source-discord`) via `TEMPORAL_TARGET=temporal-frontend.temporal.svc.cluster.local:7233` + `TEMPORAL_NAMESPACE=copytrade`, migrate the reconciliation Schedule, drain in-flight workflows, then tear down the in-`copytrade` Temporal Deployment/StatefulSet + its Postgres database. Adds Ingress for the consolidated UI (already at `http://temporal.192.168.10.123.nip.io`, just dropdown-switch to namespace `copytrade`). Bootstrap of the `copytrade` Temporal namespace (re-registering `TenantStrategy` + `ContractSymbol` Search Attributes) is captured in a script under `scripts/ops/`. | A synthetic BTO routed via `scripts/harness/inject_synthetic_bto.py` lands a workflow on `temporal/temporal-frontend` namespace `copytrade` and completes through PlaceOrder against Alpaca paper. `kubectl -n copytrade get statefulset` shows no `temporal-*` resources. `temporal operator search-attribute list --namespace copytrade` shows both custom SAs. The reconciliation Schedule fires from the consolidated cluster within 5 minutes of cutover. |
| **6. Multi-tenant production** | `SecretsResolver` Vault / AWS-SM adapter. `QuotaTracker` Redis enforcement at scale (real broker call counters, per-tenant concurrent-position caps). Per-tenant audit-log queries. Second tenant onboarded via `tenants/<id>/`. CI guardrail expanded to enforce tenant scoping on every Activity. **IP/Advisers Act gate (blocker before second tenant; Issue #5):** before onboarding any tenant beyond `dev` (or any non-personal use), the following must be on file: (a) written, signed redistribution license from the Discord author covering the trade signals being consumed; (b) securities counsel review of investment-adviser registration triggers (state-level Advisers Act, federal IA-39, available exemptions) with a written opinion on whether the operating entity must register; (c) documented entity structure and the disclosures shown to tenants. **Legal/license workstream owner:** TBD (must be assigned before this gate clears). **Securities counsel:** TBD (must be selected and engaged before this gate clears). | A second tenant runs side-by-side on isolated broker creds and isolated audit; quota exhaustion produces clean `QuotaExceededError` halts; cross-tenant queries return empty. **AND** the IP/Advisers Act gate above is closed: signed redistribution license filed, counsel opinion on file, owner + counsel recorded in this row (replacing the TBD placeholders), entity structure + tenant disclosures documented. |
| **7. Live broker promotion** | Wire the chosen provider's live adapter (`alpaca-live`, `tradier-live`, `ibkr-live`, or `schwab-live`) on its own task queue; manual promotion flow (operator sign-off + quantified paper metrics — see Issue #23); per-strategy capital allocator producing live `contracts_per_signal`; live-only alerting (PagerDuty / Slack). | First tenant + strategy promoted under small-size constraints; live trade executes successfully; rollback path tested (revert `broker_target` in YAML to the paper variant; recon catches any in-flight live order). |

## Open questions

1. **Paper broker for options.** **Revised (2026-05-15): Alpaca paper first; adapter pattern across providers.** The earlier (2026-05-13) resolution picked Tradier sandbox for the v0 default. After Phase 5b shipped with only the in-memory `StubBroker`, the user changed direction: the system uses an **adapter pattern** so any of Alpaca, Tradier, IBKR, or Charles Schwab can be plugged in. **Alpaca paper is the default first concrete adapter** because the operator prioritises it; Tradier sandbox, IBKR (via TWS), and Schwab follow as additional adapters as they're needed. Per-provider paper/live split stays for safety (one task queue per `<provider>-<env>` combination), but the service code consolidates: **one generic `exec-svc` image** that loads the configured provider adapter, deployed once per `<provider>-<env>` pair. The market-data layer gets a parallel `MarketDataProvider` port with the same provider list — a tenant's `broker_target` picks both the broker and the market-data adapter coherently. Known Alpaca paper quirks: paper trading uses `paper-api.alpaca.markets`; options paper is a separate gate from equities; fill quality is sim-driven (treat as correctness harness, not fill-quality harness — same caveat as Tradier).
2. **Discord session refresh cadence.** When does `storage_state.json` invalidate, and how do we alert on it before the next post?
3. **Reconciliation cadence.** 5 min default; do live options need 60s?
4. **Market-data dependency in v0.** Defer trailing to Phase 4 (no market-data on Phase 2-3 critical path), or include from Phase 3? Recommend deferring.
5. **PositionWorkflow versioning policy.** `Workflow.getVersion` at every change-point, vs blue/green orchestrator deploys waiting for positions to drain. Different tradeoffs; both supported by Temporal.
6. **Audit-log retention.** Per-tenant configurable or single platform default; 30 / 90 / 365 days.
7. **Quota policy defaults.** Initial values for broker calls/min, concurrent positions, concurrent workflows per tenant.
8. **Sizing policy v0.** **Resolved (2026-05-13): capital-weight from Phase 2** with floor + cap. Formula: `allocation = capital_per_strategy * cfg.capital_weight; qty = clamp(floor(allocation / (price * 100)), cfg.min_contracts, cfg.max_contracts)`. Defaults for paper: `min_contracts=1`, `max_contracts=5`. `CapitalAllocator` from Phase 0b is already on the path; deferring to Phase 6 was rejected because static `contracts_per_signal=1` skews capital footprint ~10× across signal price ranges (correctness, not polish). `strategy-config.json` schema gains `capital_weight`, `min_contracts`, `max_contracts`.
9. **OCC-symbol generation source of truth.** **Resolved (2026-05-13): deterministic generation + broker `lookup` cross-check, cached.** `contract-resolver-svc` builds the canonical OCC symbol from `(ticker, expiry, strike, right)`, verifies via broker `lookup` (catches corporate-action suffixes like `AAPL1`), and caches the mapping in Postgres `option_symbol_cache` until next expiry. On generated-vs-broker mismatch: `audit.log(SymbolDriftDetected, {generated, broker_returned})`, use broker's symbol for the order, populate cache.
10. **AVG handling.** Reference skips by default (`skip_avg=true`). Same here unless overridden per strategy.
11. **Same-author re-BTO on a held contract.** **Resolved (2026-05-13): each entry gets its own `PositionWorkflow`.** Workflow ID is `t-<t>/s-<s>/pos/<OCC>/<entry_signal_id>` (entry_signal_id disambiguates so `REJECT_DUPLICATE` does not block a same-day re-BTO on a contract that previously closed). STC dispatch finds the active position via `TenantStrategy + ContractSymbol + ExecutionStatus=Running` Visibility query (Redis-cached for hot path). If multiple positions are open on the same OCC, STC signals the oldest by start time. Mirrors the reference's Pending/Open separation.
12. **Hot-reload of StrategyConfig.** v0 requires restart; v1+ may add a `platform.strategy_subscribe` push channel.
13. **Listing running workflows by prefix.** **Resolved (2026-05-13, revised after architect review): self-hosted Temporal with Advanced Visibility on Postgres 12+ (no Elasticsearch), plus two custom Search Attributes (`TenantStrategy` Keyword, `ContractSymbol` Keyword) registered at Phase 0.** Initial design used `WorkflowId STARTS_WITH 't-<t>/s-<s>/...'` (Issue #1) but Temporal's SQL Visibility does **not** support `STARTS_WITH` on the system `WorkflowId` field, even on advanced visibility. SAs are exact-match Keyword queries that work on both standard and advanced visibility for Keyword type; we use advanced visibility specifically so `listWorkflowExecutions` supports rich queries and pagination at scale. STC dispatch additionally caches OCC→workflow_id in Redis to keep the hot path off Visibility latency.

## Prior art

- **`oh-my-opentrade/services/discord-copytrade`** — Python sidecar pattern; we copy parser + DOM extraction + state-dir layout verbatim.
- **`oh-my-opentrade/backend/internal/app/strategy/builtin/copytrade_v1.go`** — Go strategy with partial-fractions table, BTO TTL sweep, CHANDELIER_TRAIL arm, full-close tolerance. Workflow logic in our `CopytradeSignalWorkflow` + `PositionWorkflow` is structurally equivalent.
- **`oh-my-opentrade/backend/internal/domain/order_intent_journal.go`** — write-ahead log pattern; we port to jOOQ + Postgres per `exec-svc`.
- **`oh-my-opentrade/backend/internal/adapters/http/copytrade_handler.go`** — the HTTP shared-secret + dedupe-map pattern this design *replaces* (Temporal `WorkflowIDReusePolicy=REJECT_DUPLICATE` collapses both into one mechanism).
- **Temporal Java SDK samples** — `temporal-java-samples` repo; canonical pattern for `@WorkflowInterface` + `@SignalMethod` + `@QueryMethod` + `@UpdateMethod` and `TestWorkflowEnvironment`-based testing.
