# PLAN — 2026-10-04 orb30-paper-sniper

Forward paper test of the frozen ORB30 "A7" 0DTE option-buying rule (research record:
`docs/plans/experiments/0dte-spx-2026-10/orb30_iterations/README.md`, memory note
`reference_0dte_spx_sniping_research_2026_10`). The rule buys a same-day ITM option on the first
breakout of the 09:30–09:59 range between 10:00–12:00, filtered to score==2 days, and exits on the
UNDERLYING (+15 bp target / −30 bp stop / 120-min timeout). Dev backtest +6.0%/trade (t 2.06);
holdout 63% win but −2.7%/trade — **this build exists to settle that disagreement with forward
data, not because the edge is established**. Paper-only, dark on every live tenant, with
pre-registered kill criteria (below). Instruments: SPY (primary; the design+holdout instrument)
and QQQ (secondary; generalization probe — the cross-instrument backtest showed the filter adds
nothing on QQQ, so QQQ is evidence about the recipe, not a profit expectation). A8 calibration
showed premium-based exit proxies kill the dev edge, so the exits here are underlying-anchored —
that is the one new mechanism in PositionWorkflow.

## P0 — Operator (no code)
- Create a dedicated paper tenant `paper_sniper` (admin add-tenant path, paper broker target;
  account-loss cap arms at create per #660). Do NOT reuse `staging_paper` (it mirrors dev copytrade
  signals; mixing experiments corrupts both scorecards).
- Set `capital_weight` small in DOLLARS (it is a dollar base, not a fraction — see
  `reference_capital_weight_is_dollars_not_a_fraction`): $1,500 per strategy (≈ 1–2 contracts).
- After Phases 1–4 deploy: insert two strategy rows (SPY, QQQ) in the `orchestrator` DB
  `tenant_config` with the Phase-1 fields (frozen values below), `tp_ratio=null` (premium exit
  stays off), then Activate. Live-tenant configs are DB-only (tenants ConfigMap is retired); dev
  YAML under `tenants/dev/strategies/` gets a matching `orb-sniper.yaml` in Phase 3 for CI/dev.
- Confirm darkness: the Phase-1 fields are null on every other tenant (null = fully disabled at
  both the session bootstrapper and the PositionWorkflow version gate).

**Frozen parameters (from the committed backtest; not tunable during the test)**
SPY: strength_max=0.04, rng_rel_min=1.074, minutes_min=20, gap_bp_min=42.5, score_required=2.
QQQ: strength_max=0.039, rng_rel_min=1.073, minutes_min=20, gap_bp_min=51.7, score_required=2.
Both: itm_offset_pct=0.15 (≈ ITM10 on SPX), underlying_target_bp=15, underlying_stop_bp=30,
underlying_hold_max_secs=7200, entry window 10:00–12:00 ET, one fire per tenant+strategy per day,
flat backstop = existing `force_close_0dte_et` (15:00 default; positions naturally exit ≤ ~14:00,
far before Alpaca's 15:30 expiry-day handling).

## Phase 1 — StrategyConfig fields (contract)
**Goal:** carry the ORB sniper config; null/absent = feature fully off.
**Changes (anchors):**
- `contract/schemas/strategy-config.json` — add optional fields (NOT in `required`):
  `orb_score_strength_max` (number), `orb_score_rng_rel_min` (number), `orb_score_minutes_min`
  (integer), `orb_score_gap_bp_min` (number), `orb_score_required` (integer),
  `orb_itm_offset_pct` (number), `underlying_target_bp` (number), `underlying_stop_bp` (number),
  `underlying_hold_max_secs` (integer). Description on each must state: forward-test field, dark
  unless set, and that `underlying_*` is consumed by PositionWorkflow behind a version gate.
**Constraint notes folded in:** schema edit regenerates THREE artifacts — Java POJO, Python
pydantic model, dashboard field manifest via `scripts/gen-config-field-manifest.py` + CI drift job
(`reference_schema_edit_regenerates_three_artifacts`); regenerate, never hand-edit. Contract
round-trip tests + pytest, not module-scoped verify.
**Tests (TDD):** contract round-trip test naming the new fields; pydantic model accepts/serializes
nulls.
**Verify:** `mvn -pl contract/java -am spotless:apply` then full contract tests + `pytest
contract/python`; `/config` editor renders the new fields from the regenerated manifest (schema-
driven, #647) — no dashboard hand-edit.

## Phase 2 — ORB features activity (market-data)
**Goal:** one activity that returns everything the session and fire-decider need, keeping all
fetching off the deterministic path.
**Changes (anchors):**
- New `OrbFeaturesActivity` (+impl) in `services/market-data/.../provider/alpaca/`, task queue
  `market-data` (mirror `SubscribeEquityActivity` / `GetOptionQuoteActivity` registration).
  Input `(ticker, etDate)`; output: `orHigh`, `orLow` (from 1-min SIP bars 09:30–09:59),
  `prevClose`, `rngRel` (today's OR ÷ mean OR of prior 20 sessions), `gapBp`
  (|open/prevClose−1|·1e4), plus `latestBarClose(ticker)` support for fire-time strength — either
  same activity with a mode flag or a second small method on the same interface (keep one
  interface, two methods; KISS).
**Tests (TDD):** unit tests with a stubbed Alpaca client: known bar fixtures → exact orHigh/orLow/
rngRel/gapBp (use 2–3 days from the committed backtest fixtures so Java and the Python research
code agree to the basis point).
**Verify:** `mvn -pl services/market-data -am spotless:apply` + module tests. No workflow changes
in this phase.

## Phase 3 — OrbSessionWorkflow + fire-time score decider (orchestrator; net-new workflow)
**Goal:** compute the opening range daily, arm two watchlist-style legs, enforce score==2 and
one-trade-per-day at fire time.
**Changes (anchors):**
- New `OrbSessionWorkflowImpl` modeled line-for-line on
  `services/orchestrator/.../workflows/WatchlistTriggerSessionWorkflowImpl.java` (net-new workflow
  type ⇒ NO getVersion gates; same determinism discipline, no signal handlers). Flow: enabled gate
  (Phase-1 fields present) → market-open/half-day check via `MarketCalendarActivities` →
  `OrbFeaturesActivity` → build two `WatchlistTriggerPayload`s (ABOVE at orHigh / BELOW at orLow,
  `EntryMode.BREAKOUT`, 0DTE expiry, strike = ITM by `orb_itm_offset_pct` from the trigger level
  rounded to the $1 grid, right=CALL/PUT) → start two child `WatchlistTriggerWorkflow`s (existing
  child-start path in the session impl) → 12:00 ET timer → cancel un-fired children (reuse the
  session's EOD cancel path incl. terminal-child tolerance).
- New `OrbSessionScheduleBootstrapper` mirroring
  `services/orchestrator/.../bootstrap/ReconciliationScheduleBootstrapper.java`: one Temporal
  Schedule per enabled tenant+strategy, firing 10:00:30 ET each trading day (session re-checks the
  calendar; skip-if-closed).
- Score filter: route inside the existing `TriggerFireDecider` activity impl (fire-path activity
  already exists — `services/orchestrator/.../activities/TriggerFireDecider.java:17` — so NO
  command-shape change to `WatchlistTriggerWorkflowImpl`). When the strategy's Phase-1 fields are
  set: compute minutes_after (fire time vs 10:00 ET), strength ((fireBarClose−orHigh)/(orHigh−orLow)
  or mirror for BELOW, via `latestBarClose`), rng_rel and gap_bp (carried from the session through
  `ArmContext` — it is documented "additively versionable"), score them against the frozen
  thresholds, and REJECT unless score == `orb_score_required`. Also reject when a fill already
  exists today for this tenant+strategy (journal/audit lookup inside the activity — enforces max
  one trade/day across both legs without new workflow coordination). Every rejection audits
  `TriggerFireRejected` with the full feature vector as payload — that IS the shadow log of
  unfiltered signals.
**Behavioral deviation to document in the class javadoc:** backtest fired on the first 1-MIN CLOSE
outside the range; `EntryStateMachine` fires on tick cross per its BREAKOUT semantics — the
fire-decider's strength feature uses the latest completed 1-min bar, and the deviation is part of
what the forward test measures. Strike is fixed at arm from the trigger level (fire price ≈ level
except gap-throughs); same note.
**Tests (TDD):** `OrbSessionWorkflowImplTest` (TestWorkflowEnvironment): arms exactly 2 legs with
the computed strikes; disabled config arms nothing; 12:00 cancels un-fired legs; half-day skips.
`OrbFireDeciderTest`: golden vectors exported from the committed Python backtest (≥6 days: accept,
each single-feature reject, double-fire reject) — Java decision must equal the Python label.
**Verify:** `mvn -pl services/orchestrator -am spotless:apply` + module tests. New audit kinds, if
any beyond the existing Trigger* kinds, MUST be registered in
`services/audit/.../AuditEventKinds.ALL_KINDS` (pre-push `KindRegistryGuardTest`). Add
`tenants/dev/strategies/orb-sniper.yaml` (dev only; live tenants are DB-only — operator step).
Flaky `KillSwitchWorkflowImplTest`: re-run, don't fix.

## Phase 4 — Underlying-anchored exit in PositionWorkflow (riskiest; LAST)
**Goal:** exit the option when the UNDERLYING hits +target_bp / −stop_bp from the fill-time anchor,
or after hold_max_secs — the A7 exits that survive calibration (A8 showed premium proxies don't).
**Changes (anchors):**
- `services/orchestrator/.../workflows/PositionWorkflowImpl.java` — mirror the existing opt-in
  watchlist premium exit exactly: the enablement pattern at `:1837` (exit armed on first fill only
  when config field non-null), level fields like `:1221–1236`, the arming/subscription method shape
  at `:3455–3491`. New commands (SubscribeEquity activity call on the `market-data` queue, the
  `equityTick` signal handler's buffer drain in the main loop, the hold_max timer, exit branch) are
  ALL behind ONE new gate `Workflow.getVersion("underlying-exit-v1", DEFAULT_VERSION, 1)` read once
  at stable scope, AND runtime-inert when `underlying_target_bp == null` — a copytrade position
  never sets it, so existing histories replay byte-identically and live tenants see zero behavior
  change even at v≥1.
- Anchor price = underlying price at first entry fill: new optional field on
  `PositionWorkflowInput` populated by `WatchlistTriggerWorkflowImpl` from its last entry-side
  equity tick at fire (input payloads are not replay-checked —
  `reference_temporal_replay_activity_input` — but the SENDING side adds no new commands either;
  confirm by reading the child-start call site before implementing).
- Exit uses the EXISTING flatten path with new `exit_reason` values `underlying_target` /
  `underlying_stop` / `underlying_timeout` (reason strings, not new audit kinds, if the current
  exit audit carries reason as a field — verify against `AuditEventKinds`; if a new kind is
  unavoidable, register it in `ALL_KINDS` + decide pager class in `OrderFailureAlerter`
  DEFAULT_FAILURE_KINDS + the IMAGE `application.yml`, not env).
- Equity-feed silence: copy the watchlist silence-watchdog pattern
  (`WatchlistTriggerWorkflowImpl` `VERSION_EQUITY_SILENCE_WATCHDOG`, kind `TriggerFeedSilent`):
  no tick for 5 min while the exit is armed → flatten (`underlying_feed_lost`) — this test must NOT
  inherit the known market-data-restart orphan gap
  (`project_premium_subscription_lost_on_restart`).
- **Display state goes on `trailingState` or a new query — NEVER widen `positionState`**
  (`reference_positionstate_query_widening_hazard`: 3 fail-closed consumers, no ignoreUnknown; a
  widened positionState has halted+flattened a live account before).
**Tests (TDD):**
- Replay test: a recorded pre-change history replays unchanged (v=DEFAULT path).
- `PositionWorkflowImplUnderlyingExitTest`: fill → ticks cross +15 bp ⇒ flatten reason
  `underlying_target`; −30 bp ⇒ `underlying_stop`; neither within hold_max ⇒ `underlying_timeout`;
  `underlying_target_bp=null` ⇒ no equity subscription, no timers (copytrade regression);
  silence 5 min ⇒ flatten `underlying_feed_lost`.
- Update-race hygiene: no new Updates added, but follow `reference_update_before_run_race` test
  patterns (await input assignment before asserting).
**Verify:** `mvn -pl services/orchestrator -am spotless:apply` + module tests + the replay test.
Behavioral assertion tied to the research: on the golden day fixture, entry at the backtest's fire
minute with the recorded tick path exits at the same minute ± 1 bar as the Python replay.

## Phase 5 — Forward scorecard (read-only; scripts)
**Goal:** the pre-registered evaluation is executable, not vibes.
**Changes:** `scripts/research/orb30_forward_report.py` — read-only SELECT against homelab
(`order_intent_journal` in `exec_alpaca_paper`, `audit_log` in `orchestrator` — map per
`reference_homelab_prod_debug_map`), grouped by tenant_id (shared exec DB —
`project_prod_kipark_second_live_tenant`). Emits per-trade rows (entry/exit premium, underlying
anchor, exit reason, slippage vs the tick-anchored theoretical fill) + the criteria check below.
**Verify:** run against staging data; unit test the criteria math on synthetic rows.

## Phase 6 — Fill-quality telemetry, then mid-walk entries (exec; paper-gated)
**Goal:** measure, then minimize, the cost that decides every 0DTE verdict (~60% of retail losses
are spread; the research record shows fills are the open variable for every surviving strategy).
**6a — telemetry first (pure instrumentation, ships alone):**
- `services/exec/.../broker/OptionsBroker.java` / `AlpacaPaperBroker.java` — at placeOrder, also
  fetch the live NBBO for the OCC (data API; read-only) and journal `{bid, ask, mid, limit}`
  alongside the intent in `JooqOrderIntentJournal`; on terminal fill, journal
  `slippage_vs_mid = fill − mid_at_submit` (sign by side). New journal columns via a V-migration;
  no workflow changes, no new audit kinds.
**6b — mid-walk ladder (behind a per-strategy config flag, paper tenants only at first):**
- New `MidWalkExecutor` in `services/exec/.../broker/`: submit limit at mid rounded to tick; if
  unfilled after N seconds, cancel/replace one tick toward the far side; stop at a marketable cap
  (cross the spread) or the existing entry TTL. Branch on `cancelOrder == CANCELLED` exactly
  (`reference_exec_order_state_hides_failures`: SUBMITTED can mean cancel-REJECTED = possibly
  filled) and re-read `getOrderStatus` before each replace so a fill during the race is never
  double-sent.
**Tests (TDD):** ladder unit tests with a stubbed broker (fill at step k; cancel-rejected-because-
filled race; TTL exhaustion); journal migration round-trip.
**Verify:** `mvn -pl services/exec -am spotless:apply` + module tests. Success criterion for the
forward test: median |slippage_vs_mid| reported per leg; the pre-registered kill bound (1.5% of
premium) now comes from measured data instead of assumption.

## Pre-registered kill / continue criteria (frozen now)
Evaluation at the LATER of 25 filled trades or 60 trading days, pooled SPY+QQQ, A7-filtered only:
- **Kill** if mean net return/trade ≤ 0, OR >30% of fired signals failed to fill within the entry
  TTL, OR median per-leg slippage vs tick-anchored theoretical exceeds 1.5% of premium.
- **Continue 3 more months** if mean > 0 AND win ≥ 55%; else operator decision with the scorecard.
- **Immediate hard kill (any time):** any position alive past 14:45 ET; any single-day loss > 3×
  median entry premium; any order on a non-paper tenant (this plan grants NO real-money path — that
  requires a new plan and operator sign-off).
- SPY is the primary series; QQQ is reported separately and cannot rescue a SPY kill.

## Ship order & gating
1. **Phase 1** (contract; isolated) → 2. **Phase 2** (market-data; isolated) → 3. **Phase 3**
(orchestrator, net-new workflow — no replay risk) → 4. **Phase 4** (PositionWorkflow version-gated
change — riskiest, last) → 5. **Phase 5** (scripts) → **P0 operator steps** (tenant, DB rows,
Activate).
Each phase: TDD first, `spotless:apply` on every touched module, single-concern PR, operator merge
gate (trading-critical path: PositionWorkflow + exec-adjacent). PR bodies set at create time
(`gh pr edit --body` is broken — use `gh api -X PATCH` if needed). Never touch
`.github/workflows/*.yml`. Mutant/test verification uses `mvn test`, never bare `surefire:test`.
