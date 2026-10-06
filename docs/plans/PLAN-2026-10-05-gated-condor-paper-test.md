# PLAN — 2026-10-05 gated-condor-paper-test

Forward paper test of the **IV-richness-gated afternoon iron condor** — the only rule in the 0DTE
research program (`docs/plans/experiments/0dte-spx-2026-10/`, graph:
`research_graph/graph.json`, memory `reference_0dte_spx_sniping_research_2026_10`) that passed its
pre-registered out-of-sample bar: stress-cost +10.6%/day-of-risk on SPY (t 3.8) and +15.5% on QQQ
(t 4.2), SPX in-sample +9.2% (t 3.1), 9/9 hour×instrument cells gated>ungated (U2), monotone
dose-response in richness (U4, t 5.7), anti-gate ≈ 0. Supported mechanisms: M1 afternoon VRP, M2
richness gate, M3 defined risk, M6 hold-to-settle. Known risk edge (U3): gated FOMC afternoons are
+22% mean but 58% win with a −100% worst → **FOMC statement days are skipped by default**.

**The rule (frozen):** on trading days where ATM-straddle richness at 14:00 ≥ trailing-60-day
median (richness = straddle ÷ (0.7979 · σ₁ₘᵢₙ(09:30→14:00) · √(minutes to close) · spot)): sell an
XSP iron condor — shorts at ±0.15% from spot, wings at ±0.60% (nearest $1 XSP strikes) — as ONE
4-leg net-credit order, hold to cash settlement. No stops, no targets, no premium exits. Max risk
per trade = width − credit, known at entry. Trades ~40% of days (~2/week). Runs as a standalone strategy row (`gated_condor`) on the `staging_paper` tenant — all behavior keys off per-strategy config, nothing tenant-specific in code.

**Why XSP:** cash-settled (SPY/QQQ physical settlement + Alpaca's 15:30 expiry-day handling make
the measured edge untradeable there), $0 exchange fee under 10 contracts, $1 strikes. Alpaca has
no index market data, so spot and richness are computed from SPY×parity and the option chain
itself. **What the test measures:** whether the print-modeled credit is achievable at real NBBO —
fills are the open variable (M7); the gate and structure are already OOS-supported.

**This plan supersedes `PLAN-2026-10-04-orb30-paper-sniper.md`** (A7's evidence is mixed-negative
and its filter failed instrument transfer). No ORB phases are built. The fill-telemetry/mid-walk
content of its Phase 6 moves here (Phase 3).

## P0 — Operator (no code)
- **Deploy `scripts/data/option_quote_collector.py` on the homelab now** (crontab `@reboot` or a
  k8s Deployment; a session cron dies with the session — `reference_prod_real_monitoring`). Every
  market day not collected is unrecoverable NBBO history (feeds U5/U6 and the fill model).
- Verify **XSP index-option entitlement on staging_paper's paper account** (read-only probe with
  that tenant's trade keys; index options live on Alpaca paper since 2026-07-23).
- **Deployment target: a NEW strategy row on the existing `staging_paper` tenant** (operator
  decision 2026-10-05) — strategy_id `gated_condor`, alongside the copytrade-mirror strategy.
  Live-tenant config is DB-only (tenants ConfigMap retired): after Phases 1–4 deploy, insert the
  row with the frozen values + the Phase-1 fields, `capital_weight` = **$1,000** (DOLLARS, per
  strategy, pod-global $100k base trap — `reference_capital_weight_is_dollars_not_a_fraction`;
  set at CREATE since the lever is decrease-only afterwards), `tp_ratio=null`, then Activate.
  Config edits later: re-Activate after every edit (an edit silently halts entries).
- Confirm darkness: Phase-1 fields null on every other strategy row (including staging_paper's
  own copytrade strategy) and every other tenant.
- **Shared-tenant interactions (acknowledge before enabling):**
  - The **account-level loss cap** on staging_paper is ARMED (#767) and shared with the mirror
    strategy. A cap trip from EITHER strategy force-flattens BOTH. Phase 4 must verify condor
    combo marks can't false-trip it (see the cap task there); until verified, the condor does not
    Activate.
  - **One-click Deactivate / kill switch is tenant-level** and will market-flatten the condor's 4
    legs mid-hold — acceptable (defined risk, pays exit spread once) but it contaminates that
    day's scorecard row; the scorecard flags kill-switch exits.
  - FOMC calendar: refresh `scripts/data/fomc-dates.txt` when the Fed publishes next year's
    schedule (~June).
## Phase 1 — StrategyConfig fields (contract)
**Goal:** carry the condor config; null/absent = fully dark.
**Changes (anchors):** `contract/schemas/strategy-config.json` — optional fields (NOT in
`required`): `condor_entry_et` (HH:MM, pattern as `force_close_0dte_et` at :248),
`condor_short_offset_pct`, `condor_wing_offset_pct`, `richness_gate_lookback_days`,
`richness_gate_min_quantile` (0.5 = the pre-registered median gate), `condor_skip_event_days`
(bool, default-true semantics documented), `condor_hold_to_settle` (bool; documents that the
condor's own workflow — not PositionWorkflow — owns the lifecycle, so `force_close_0dte_et` does
not apply to it).
**Constraints:** regenerate the THREE artifacts (Java POJO, Python model, dashboard manifest via
`scripts/gen-config-field-manifest.py`); run contract round-trip tests + pytest
(`reference_schema_edit_regenerates_three_artifacts`).
**Tests/verify:** round-trip tests naming each field; `mvn -pl contract/java -am spotless:apply`
+ contract tests + `pytest contract/python`.

## Phase 2 — Richness + chain activities (market-data)
**Goal:** everything non-deterministic the session needs, as activities on the `market-data` queue
(registration mirrors `SubscribeEquityActivity` / `GetOptionQuoteActivity`).
**Changes:** new `CondorMarketActivity` with:
- `evaluateRichness(underlying, entryEt, lookbackDays)` → {richness, trailingQuantile, gate}:
  today's ATM straddle (XSP chain quotes — live NBBO, better than the backtest's prints), σ from
  SPY 1-min bars 09:30→entry, trailing distribution recomputed from SPXW/XSP option bars + SPY
  bars (quotes don't backfill; bars do — same basis as the backtest).
- `resolveCondorLegs(underlying, shortOffsetPct, wingOffsetPct)` → 4 OCC symbols + NBBO per leg +
  net-credit mid (spot via put-call parity on the ATM pair — the backtest's `spx_level.py` method,
  intraday IQR 0.0005).
**Tests:** stubbed-client fixtures from 2–3 committed backtest days: richness matches the Python
value to 4 decimals; leg resolution matches the backtest's strikes.
**Verify:** `mvn -pl services/market-data -am spotless:apply` + module tests.

## Phase 3 — Multi-leg orders + fill telemetry (exec)
**Goal:** place a 4-leg net-credit order with a mid-walk ladder, and journal fill quality — the
test's primary measurement.
**Changes (anchors):**
- `services/exec/.../broker/OptionsBroker.java` — add `placeMlegOrder(PlaceMlegOrderRequest)`
  (legs[4], net-credit limit, tif=day; idempotent on `client_order_id` like `placeOrder`, per the
  port's documented contract) + implement in `broker/alpaca/AlpacaPaperBroker.java` (Alpaca mleg
  order class; limit/market only, day/gtc — confirmed in research) and `broker/stub/StubBroker.java`.
- `journal/JooqOrderIntentJournal.java` + V-migration: one combo row + per-leg fill rows; columns
  `nbbo_bid/ask/mid` per leg at submit and `slippage_vs_mid` on fill.
- `MidWalkExecutor` in `broker/`: submit at net-credit mid rounded to tick; unfilled after 10s →
  cancel/replace one tick lower (less credit); **abandon** below (model_credit − 2 ticks) or at
  entry_et+10min, journaling `CondorEntryAbandoned` with the full quote ladder — an abandoned
  attempt is fill-model DATA, not a failure. Branch cancels on `Outcome.CANCELLED` /
  `ALREADY_FILLED` exactly (the port's 3-state cancel; never treat FAILED as flat —
  `reference_exec_order_state_hides_failures`), and re-read `getOrderStatus` before every replace.
**Tests (TDD):** stub-broker ladder tests (fill at step k; ALREADY_FILLED mid-walk race; abandon
path journals quotes); migration round-trip; idempotency on repeated client_order_id.
**Verify:** `mvn -pl services/exec -am spotless:apply` + module tests. New failure-class audit
kinds (if any) → `AuditEventKinds.ALL_KINDS` + `OrderFailureAlerter` DEFAULT_FAILURE_KINDS +
image `application.yml` (not env).

## Phase 4 — CondorSessionWorkflow + CondorHoldWorkflow (orchestrator; net-new, no version gates)
**Goal:** daily gate→entry→hold→settle lifecycle, owned by NEW workflow types —
**PositionWorkflow is not touched** (the superseded plan's riskiest phase is eliminated).
**Changes (anchors):**
- `CondorSessionWorkflowImpl` modeled on `WatchlistTriggerSessionWorkflowImpl` (same determinism
  discipline, no signal handlers): started per tenant+strategy by a `CondorScheduleBootstrapper`
  (mirror `bootstrap/ReconciliationScheduleBootstrapper.java`) at entry_et−10min each trading day.
  Flow: enabled gate → `MarketCalendarActivities` closed/half-day skip → FOMC skip (activity reads
  `scripts/data/fomc-dates.txt` resource; audits `CondorEventSkip`) → `evaluateRichness` → audit
  `CondorGateEvaluated` {richness, quantile, gate} EVERY day (the shadow log) → if gated-in:
  `resolveCondorLegs` → Phase-3 mid-walk entry → on fill, start child `CondorHoldWorkflow`.
- `CondorHoldWorkflowImpl`: holds the 4 legs to expiry; a `forceClose` signal lets the operator
  flatten (4 market orders via existing exec activities); after 16:15 ET runs a settlement
  reconciliation activity (broker positions + account activities → booked cash settlement vs
  intrinsic-at-close; mismatch → audit `CondorSettleMismatch`, page). Seed the Redis position
  cache on start (recon false-orphan gap — mirror `VERSION_POSITION_CACHE`'s activity, no gate
  needed in a net-new type).
- **Account-cap interaction (BLOCKING task — shared cap with the live copytrade mirror on
  staging_paper):** the cap charges `(bid − entryPremium)` for long positions
  (`project_account_cap_crossday_and_reset_enabled`); a short 4-leg combo's marks could false-trip
  the SHARED cap and force-flatten the mirror strategy's positions too. Write the test that feeds
  condor-shaped positions through the cap mark BEFORE enabling; if the cap cannot represent
  defined-risk combos, EXCLUDE condor lots from the cap mark (paper-only) with a follow-up issue
  before any real-money plan. The condor strategy row is not Activated until this is verified.
- **Tenant kill-switch coverage:** a kill-switch force-flatten must close all 4 legs (shorts
  covered — never leave a naked short by closing longs first; flatten as 4 concurrent market
  orders or shorts-first). Add a CondorHoldWorkflow test for the kill-switch path; scorecard tags
  these exits `killswitch_flatten`.
- Recon: ensure the OCC-anchored orphan sweep (#432-435) tolerates the 4 condor legs (adopted-lot
  check must see the combo's legs as owned; add the workflow id mapping for each leg).
**Tests (TDD):** TestWorkflowEnvironment — gated-out day places nothing but audits the gate;
FOMC day skips; gated-in day resolves legs and calls mleg entry; abandon path ends the session
cleanly; hold workflow settles ITM and OTM fixtures correctly; forceClose flattens 4 legs.
**Verify:** `mvn -pl services/orchestrator -am spotless:apply` + module tests (re-run flaky
`KillSwitchWorkflowImplTest` if it trips). Dev YAML `tenants/dev/strategies/condor-paper.yaml`
added (dev only; live tenants DB-only).

## Phase 5 — Scorecard (read-only scripts)
`scripts/research/condor_forward_report.py`: SELECT from `exec_alpaca_paper.order_intent_journal`
(+ `orchestrator.audit_log`), grouped by tenant_id (shared exec DB) AND strategy: staging_paper
also journals the copytrade mirror, so condor intents MUST be attributable — every condor
`client_order_id`/intent_key carries a `condor-` prefix and every condor audit event carries
`strategy_id=gated_condor` (make this a Phase-3/4 requirement, not a scorecard-side regex hope).
Per attempt: gate value,
model credit, achieved credit, per-leg slippage_vs_mid, settlement P&L, and the criteria check
below. Also joins collector NBBO (U5) once deployed. Unit-test the criteria math.

## Pre-registered success / kill criteria (frozen now)
Backtest expectation on the tradeable vehicle: ≈ +9% of max risk per gated trade (SPX in-sample,
stress costs). Evaluate at the later of **25 gated fills or 90 calendar days** (~2 gated
signals/week ⇒ ~12 weeks):
- **Kill** if mean net return/max-risk ≤ 0; or median achieved credit < model credit − $0.04
  (4 ticks across 4 XSP legs); or abandon rate > 40% of gated attempts; or any settlement mismatch
  unresolved > 1 trading day.
- **Hard kill immediately:** any naked leg (partial combo fill unhedged > 5 min); any order on a
  non-paper tenant; any position past expiry without settlement booked; any condor-attributed
  account-cap trip that flattens the copytrade mirror's positions (shared-tenant interference —
  operator review before re-enabling).
- **Continue/extend** if mean > 0 and fill telemetry within the model; **real money requires a new
  plan + operator sign-off** — this plan grants no path to it.
- Secondary (reported, never decisive): U5 print-model vs NBBO comparison; U6 gate from the XSP
  chain directly vs the SPY-proxy gate.

## Ship order & gating
1. Phase 1 (contract) → 2. Phase 2 (market-data) → 3. Phase 3 (exec mleg — biggest new surface,
but stub-tested and paper-only) → 4. Phase 4 (net-new workflows) → 5. Phase 5 (scripts) → P0
operator enablement. Each phase: TDD first, `spotless:apply` on every touched module,
single-concern PR, operator merge gate (trading-critical). `gh pr edit --body` is broken — set
bodies at create or `gh api -X PATCH`. Never touch `.github/workflows/*.yml`. `mvn test`, never
bare `surefire:test`.
