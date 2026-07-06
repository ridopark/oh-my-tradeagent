# PLAN — 2026-07-06 pre-trade-check orchestrator wiring (fix-forward)

Today's `pre_trade_check_enabled=true` cutover on `prod_real` (updated_by
`operator-cutover-pretrade-cash-2026-07-05`, DB flag live since 03:22 UTC) enabled the pre-trade
cash check on the **exec** side (#558) but never wired the **orchestrator** side. Result: every
`prod_real` copytrade BTO fails closed at `RiskActivitiesImpl.assertPreTradeCheckRoutable`
(`services/orchestrator/.../activities/RiskActivitiesImpl.java:603-615`) with non-retryable
`ApplicationFailure` "pre_trade_check enabled … but only the permissive default
PreTradeCheckActivity bean is wired" — thrown *before* `SignalAccepted`, so no order and no
rejection audit. **3 live copytrade signals lost 2026-07-06** (17:18, MSFT 19:26, GOOGL 19:30 UTC);
live copytrade effectively down all day. Watchlist path is unaffected (it never calls the
routability guard). This plan fixes forward: wire the orchestrator so the enabled flag routes the
check to exec, instead of reverting the flag.

## Architecture (verified)
- The real check runs in **exec**: `PreTradeCheckExecActivityImpl` registered on exec's broker task
  queue (`services/exec/.../config/TemporalWorkerConfig.java:57,63`). `exec-alpaca-live` services
  `broker-alpaca-live` (has #558).
- The workflow dispatches to exec via a Temporal stub: `CopytradeSignalWorkflowImpl.dispatchPreTradeCheck`
  → `Workflow.newActivityStub(PreTradeCheckActivity.class, taskQueue=taskQueueFor(broker_target))`
  (`:1149-1157`), i.e. `broker-alpaca-live`. This routing is already correct and already
  version-gated (#112, the `v>=1` branch at `CopytradeSignalWorkflowImpl.java:375-378`).
- The orchestrator's local `PreTradeCheckActivity` bean is **only** the guard's routability marker:
  its sole use is the `instanceof PermissiveDefaultPreTradeCheck` check at `RiskActivitiesImpl.java:607`
  (confirmed — no other use of the injected field). The orchestrator worker does **NOT** register
  `PreTradeCheckActivity` as a local activity (`TemporalWorkerConfig.java:214+`), so the bean is
  never executed locally — it is a pure marker.
- The permissive default is `@ConditionalOnMissingBean`
  (`RiskCollaboratorsConfig.java:73-77`); any other `PreTradeCheckActivity` bean overrides it. Tests
  confirm any non-permissive bean (even a Mockito mock) satisfies the guard
  (`RiskActivitiesAssertionTest.java:44`).

**So the fix is a non-permissive, operator-opt-in `PreTradeCheckActivity` marker bean in the
orchestrator.** No workflow change, no exec change, no broker logic in the orchestrator.

## P0 — Immediate operational (no code; operator) — DECISION REQUIRED
The `prod_real` copytrade DB flag is currently `true` and **broken**: live copytrade stays down
(every BTO fail-closes) until Phase 1 is deployed. Two ways to hold the line until then:
- **(a) Leave flag on, deploy Phase 1 before next market open (13:30 UTC).** Chosen fix-forward
  path. If the deploy slips past open, live copytrade is down for that session.
- **(b) Stopgap revert now, deploy Phase 1, re-enable.** `UPDATE strategy_config SET
  config=jsonb_set(config,'{pre_trade_check_enabled}','false'), version=8, updated_by=
  'stopgap-pretrade-revert-2026-07-06' WHERE tenant_id='prod_real' AND strategy_id='copytrade-v1';`
  restores trading immediately (notional_cap 0.80 already caps sizing ≤ cash — the pre-trade check
  is a backstop), then flip back to `true` (version 9) once Phase 1 is deployed + verified.
- **Recommendation:** (b) as a safety net if Phase 1 won't be deployed-and-verified before the next
  open; otherwise (a). Surface to operator — do not silently pick.
- `watchlist-trigger-v1` flag: leave as-is (its path has no routability guard; the flag is inert
  there). Optional hygiene: set it `false` to avoid implying an active gate.

## Phase 1 — Orchestrator routability marker bean (orchestrator)
**Goal:** when `orchestrator.pre-trade-check.routing-enabled=true`, wire a non-permissive
`PreTradeCheckActivity` bean so `assertPreTradeCheckRoutable` passes and the enabled flag routes the
check to exec. Default off preserves today's fail-closed guard (flag-on + property-off ⇒ still
throws), keeping enablement a deliberate two-key action (DB flag AND orchestrator config).

**Changes** (anchors):
- New class `services/orchestrator/.../activities/RoutablePreTradeCheckActivity.java` — implements
  `PreTradeCheckActivity`, does **NOT** implement `PermissiveDefaultPreTradeCheck`. It is a guard
  marker only (never registered on the worker, never dispatched to — the stub routes to exec). Its
  `preTradeCheck(request)` must **fail closed** if ever invoked directly (return a not-allowed
  `PreTradeCheckResult`, mirroring `PreTradeCheckSentinels.dispatchFailed`), so an accidental local
  invocation can never wave a trade through. Add a class comment stating it is a routability marker
  for cross-service dispatch to `PreTradeCheckExecActivityImpl`.
- `services/orchestrator/.../config/RiskCollaboratorsConfig.java` — add
  `@Bean @ConditionalOnProperty(name="orchestrator.pre-trade-check.routing-enabled", havingValue="true")
  public PreTradeCheckActivity routablePreTradeCheckActivity() { return new RoutablePreTradeCheckActivity(); }`
  above the existing `@ConditionalOnMissingBean permissivePreTradeCheckActivity()` (`:73-77`). When
  the property is true the marker exists → the `@ConditionalOnMissingBean` permissive default is not
  created → `RiskActivitiesImpl` injects the marker → guard passes. When false → permissive default
  → guard fail-closes (unchanged).
- `services/orchestrator/src/main/resources/application.yml` — document the new property with an
  explicit `false` default (IMAGE default; env/ConfigMap overrides it live — constraint 5 note: this
  is a plain Spring property, not an audit kind).

**Replay safety:** none required. `assertPreTradeCheckRoutable` is an *activity* whose result
changes from throw→return; activity results are not replay-checked (only command type/ordering), the
call already sits on the #112-versioned `v>=1` path, and all previously-failed workflows are terminal
(nothing in-flight to diverge). No new `Workflow.getVersion` marker. No `contract/schemas` change.

**Tests (TDD):**
- `RiskActivitiesAssertionTest`: with the `RoutablePreTradeCheckActivity` injected + flag on →
  `assertPreTradeCheckRoutable` returns normally (no throw). With permissive default + flag on →
  still throws `PreTradeCheckMisconfigured` (existing behavior pinned).
- New `RoutablePreTradeCheckActivityTest`: `preTradeCheck(...)` invoked directly returns a
  not-allowed / dispatch-failed result (fail-closed marker contract).
- A Spring `@ConditionalOnProperty` wiring test (context loads with property=true → injected bean is
  `RoutablePreTradeCheckActivity`; property absent → injected bean is the permissive default). Mirror
  existing config-wiring test style if present; else assert via a minimal `ApplicationContextRunner`.
- Reproduction linkage: assert an enabled-config path no longer throws once the marker is wired
  (the incident was the throw at `:608`).

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply && spotless:check` (constraint 2).
- `mvn -pl services/orchestrator -am test` green (`KillSwitchWorkflowImplTest` flake → re-run,
  constraint 8).
- Behavioral assertion: with property on + `pre_trade_check_enabled=true`, a copytrade BTO reaches
  `dispatchPreTradeCheck` (no `PreTradeCheckMisconfigured`); with property off it still fail-closes.

## Phase 2 — Alert on PreTradeCheckMisconfigured / silent entry-workflow failure (orchestrator) — OPTIONAL, follow-up
**Goal:** today's 3 lost signals failed with **no page** — a non-retryable workflow failure before
any audit is invisible. Make `PreTradeCheckMisconfigured` (and, more broadly, a non-retryable
CopytradeSignalWorkflow failure) alert so a misconfiguration can't silently black-hole live entries.
**Changes** (anchors): register the failure with `OrderFailureAlerter`
`DEFAULT_FAILURE_KINDS` + `application.yml` IMAGE default (constraint 5: failure-class kinds that
page also go in `application.yml`, not env), or emit a dedicated audit kind (register in
`AuditEventKinds.ALL_KINDS` or `KindRegistryGuardTest` blocks the push) at the guard before the
throw. **Tests:** the misconfig path produces the alert/audit. Independent PR; not required to
restore trading.

## Ship order & gating
1. **Phase 1** (isolated orchestrator bean + config) → own PR, operator merge gate (trading-critical).
2. **Operator deploy (Phase 1):** set `ORCHESTRATOR_PRE_TRADE_CHECK_ROUTING_ENABLED=true` (or the
   ConfigMap/application.yml equivalent) on the **orchestrator** deployment and roll it
   (`deploy.yml` applies per-service manifests; a ConfigMap/env change is a manual `kubectl set env`
   / `kubectl apply` — constraint 7). Exec is already deployed (`exec-alpaca-live` has #558). Order:
   confirm exec services `broker-alpaca-live` pre-trade check → set orchestrator property → roll
   orchestrator → verify.
3. **Verify E2E (paper rehearsal then live):** on a paper tenant with `pre_trade_check_enabled=true`
   + property on, submit a BTO and confirm `SignalAccepted` + a `PreTradeCheck*` result audit (not
   `PreTradeCheckMisconfigured`); then confirm on `prod_real` that a real BTO gets a cash-check
   result (allow → order, or a clean `PRE_TRADE_CHECK_FAILED` rejection) rather than a workflow crash.
4. **Phase 2** (alerting) → own PR, anytime after.
- **Timing gate:** live copytrade stays down until step 2 is deployed+verified. If that won't land
  before the next market open, execute P0 option (b) (stopgap flag revert) first.
