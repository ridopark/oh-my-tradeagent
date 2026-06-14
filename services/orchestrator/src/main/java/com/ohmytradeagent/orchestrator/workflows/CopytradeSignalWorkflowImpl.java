package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AccountSnapshotMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionStatus;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import com.ohmytradeagent.orchestrator.domain.BtoPricing;
import com.ohmytradeagent.orchestrator.domain.BtoPricing.PricedLimit;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.KeywordPartialMatcher;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.domain.StrategyConfigs;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class CopytradeSignalWorkflowImpl implements CopytradeSignalWorkflow {

  private static final String KIND_SIGNAL_RECEIVED = "SignalReceived";
  private static final String KIND_SIGNAL_ACCEPTED = "SignalAccepted";
  private static final String KIND_SIGNAL_REJECTED = "SignalRejected";
  private static final String KIND_ORDER_SUBMITTED = "OrderSubmitted";
  private static final String KIND_ORDER_CANCEL_REQUESTED = "OrderCancelRequested";
  private static final String KIND_ORDER_CANCELLED = "OrderCancelled";
  private static final String KIND_ORDER_CANCEL_FAILED = "OrderCancelFailed";
  private static final String KIND_ENTRY_EXPIRED = "EntryExpired";
  private static final String KIND_ENTRY_FILLED = "EntryFilled";
  private static final String KIND_EXIT_REQUESTED = "ExitRequested";
  private static final String KIND_ORPHAN_STC = "OrphanSTC";
  private static final String KIND_AVG_SKIPPED = "AvgSkipped";
  // Phase 4: distinct from PositionWorkflow's "ChandelierArmed" — this audit is the dispatch
  // (parent-side); the apply (child-side) emits its own ChandelierArmed when the subscribe
  // activity succeeds. Both useful for forensics.
  private static final String KIND_CHANDELIER_ARM_REQUESTED = "ChandelierArmRequested";
  // Phase 5: kill-switch cascade short-circuit audit.
  private static final String KIND_SIGNAL_ABORTED_BY_RISK_BREACH = "SignalAbortedByRiskBreach";
  // P3-a (multi-tenant-broker-credentials): emitted when a LIVE BTO is refused at the
  // live-promotion gate (no VALID LivePromotionApproved row for the broker_target). Registered in
  // AuditEventKinds.ALL_KINDS + SOFT_TERMINAL_CLOSE_KINDS (refused before any broker activity).
  private static final String KIND_LIVE_PROMOTION_MISSING = "LivePromotionMissing";

  private static final String REASON_TTL_EXPIRED = "ttl_expired";
  // Issue: orphan-STC alerting. handleStc's stale Redis lookup can return a DEAD (terminal)
  // PositionWorkflow id; these reason codes tag the OrphanSTC audit emitted instead of crashing.
  private static final String REASON_POSITION_WF_NOT_RUNNING = "position_workflow_not_running";
  private static final String REASON_SIGNAL_DISPATCH_FAILED = "signal_dispatch_failed";
  private static final String VERSION_POSITION_HANDOFF = "position-handoff";
  private static final String VERSION_RISK_BREACH = "risk-breach-v1";
  // Issue #165 phase 2: gate the FILLED branch in handleTtlExpired so pre-fix replay histories
  // deterministically take the legacy CANCELLED/else paths. Mirrors VERSION_POSITION_HANDOFF.
  private static final String VERSION_TTL_FILLED_ADOPTION = "ttl-filled-adoption-v1";
  // Issue #274: gate the FILLED branch in the BTO risk-breach abort so pre-fix replay histories
  // deterministically take the legacy audit-and-abort path. When a breach wakes the fill-await
  // before the async onFill signal lands but the broker has already filled, exec.cancelOrder
  // reconciles broker truth and returns state=FILLED; the v>=1 branch adopts the lot via
  // handleCancelOnFilled instead of discarding the result and orphaning. Mirrors
  // VERSION_TTL_FILLED_ADOPTION.
  private static final String VERSION_BREACH_FILLED_ADOPTION = "breach-filled-adoption-v1";
  // Issue #112: Gate the 3-activity pre-trade dispatch (assertPreTradeCheckRoutable →
  // dispatchPreTradeCheck → checkEntryWithLimit(payload, config, preTradeResult, limit))
  // introduced in PR #111 and tightened in #198 to thread the slip-adjusted limit through.
  // Pre-#111 in-flight CopytradeSignalWorkflow executions had a single checkEntry(payload, config)
  // call; the v=DEFAULT_VERSION branch preserves that shape via the legacy 3-arg checkEntry
  // overload with null preTradeResult so replays of legacy histories remain deterministic. The
  // v>=1 branch dispatches the pre-trade check; the inner VERSION_CHECK_ENTRY_WITH_LIMIT gate
  // then routes to checkEntryWithLimit (CheckEntryWithLimit Activity type) for new executions so
  // notional-cap + buying-power gates see the slip-adjusted limit rather than the mirror price.
  // Retires the deploy-time drain mitigation documented in #111.
  private static final String VERSION_PRE_TRADE_DISPATCH = "pre-trade-dispatch-v2";

  // In-flight v>=1 workflows recorded a CheckEntry activity-task call before this gate was added.
  // After deploy, the new code scheduling CheckEntryWithLimit would mismatch the recorded history
  // and trip a Temporal non-determinism error. v=DEFAULT_VERSION (open workflows) keeps the legacy
  // checkEntry call; v>=1 (new workflows) routes to checkEntryWithLimit with the slip-adjusted
  // limit so notional-cap + BP gates see the max-acceptable cost.
  private static final String VERSION_CHECK_ENTRY_WITH_LIMIT = "check-entry-with-limit";

  // Issue #203: gate the new child.onFill(fill) call that forwards the BTO fill to the child
  // PositionWorkflow so its v=1 first-fill await wakes. In-flight CopytradeSignalWorkflow
  // executions
  // that already passed through startPositionWorkflow without this call must keep replaying without
  // the extra signal command; new executions (v>=1) forward the fill so the child confirms entry.
  private static final String VERSION_FORWARD_BTO_FILL = "forward-bto-fill-v1";

  // Issue #276: gate the option_symbol field added to the EntryFilled audit subject (both the
  // happy-path fill branch and the cancel-on-filled recovery). The DailyPnl realized-P&L consumer
  // groups FIFO by option_symbol so each exited contract realizes against its OWN symbol's basis;
  // emitting the key on EntryFilled supplies the producer-side correlation key. Replay-gated so
  // pre-change CopytradeSignalWorkflow histories reproduce the legacy subject exactly (no
  // option_symbol key) and stay deterministic; only new executions (v>=1) emit it. Mirrors
  // VERSION_TTL_FILLED_ADOPTION / VERSION_BREACH_FILLED_ADOPTION.
  private static final String VERSION_ENTRY_FILLED_OPTION_SYMBOL = "entry-filled-option-symbol-v1";

  // Gate the new account-snapshot dispatch + the 5-arg checkEntryWithLimit overload that
  // threads the broker-supplied CASH (the #323 cost-basis capital-base component, not net-liq
  // equity) into the notional-cap gate. In-flight v>=1 workflows recorded
  // a 4-arg CheckEntryWithLimit call (and no AccountSnapshot activity-task) before this landed;
  // replaying them must keep the legacy path or Temporal trips a non-determinism error.
  // v=DEFAULT_VERSION replays the prior 4-arg call (cash from the PortfolioSnapshot seam); v>=1
  // dispatches AccountSnapshot and passes the cash down. The marker string is a Temporal replay
  // identifier and must NOT be renamed even though the threaded value is now cash, not equity.
  private static final String VERSION_ACCOUNT_EQUITY_DISPATCH = "account-equity-dispatch-v1";

  // Issue: orphan-STC alerting. Gate the two STC running-guards (preventive: check
  // isPositionWorkflowRunning before ExitRequested+dispatch; defense-in-depth: catch
  // SignalExternalWorkflowException around partialExit) behind a single marker so v=0 in-flight
  // handleStc replays are BYTE-IDENTICAL. v=DEFAULT_VERSION: the preventive guard short-circuits
  // via
  // && (no isPositionWorkflowRunning activity scheduled) and the dispatch takes the bare-signal
  // branch — identical command stream. v>=1: both guards active so a stale Redis mapping to a dead
  // PositionWorkflow emits OrphanSTC instead of crashing the CopytradeSignalWorkflow.
  private static final String VERSION_STC_RUNNING_GUARD = "stc-running-guard-v1";

  // P3-a (multi-tenant-broker-credentials): gate the LIVE-only live-promotion dispatch check added
  // in handleBto (after the SignalAccepted audit, before newIntent/placeOrder). v=DEFAULT_VERSION
  // (legacy in-flight histories) is byte-identical to the pre-P3a path — the new branch is
  // reachable
  // only at v>=1, and the paper path (!isLive) schedules ZERO verify activities at any version.
  private static final String VERSION_LIVE_PROMOTION_GATE = "live-promotion-gate-v1";

  // P3-a: the live-promotion staleness window. A LivePromotionApproved older than this is refused
  // as
  // STALE. Hardcoded constant by design — making it config-tunable is P3-b, NOT this phase.
  private static final java.time.Duration LIVE_PROMOTION_TTL = java.time.Duration.ofDays(30);

  /** Used when StrategyConfig.pending_ttl_paper_secs is null. */
  static final long DEFAULT_PENDING_TTL_PAPER_SECS = 90L;

  /** Used when StrategyConfig.default_stc_fraction is null. */
  static final double DEFAULT_STC_FRACTION = 0.5;

  /**
   * Legacy default task queue used when a workflow runs before {@code cfg.broker_target} is
   * resolved (e.g. when the early reject path fires before {@code strategy.get} is called) or by
   * tests that don't go through the factory. Phase 2c.2 routes by {@code cfg.broker_target} via
   * {@link ExecActivitiesFactory}; this constant is retained for back-compat with the existing test
   * scaffolding that registers a single broker worker on this queue.
   */
  static final String EXEC_TASK_QUEUE_ALPACA_PAPER = "broker-alpaca-paper";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  // P3-a: the live-promotion safety-gate verify. On the orchestrator-core queue (DEFAULT_OPTIONS),
  // same as the other read-side stubs.
  private final AuditQueryActivities auditQuery =
      Workflow.newActivityStub(AuditQueryActivities.class, DEFAULT_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, DEFAULT_OPTIONS);
  private final RiskActivities risk =
      Workflow.newActivityStub(RiskActivities.class, DEFAULT_OPTIONS);
  private final ContractActivities contract =
      Workflow.newActivityStub(ContractActivities.class, DEFAULT_OPTIONS);
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(PositionLookupActivities.class, DEFAULT_OPTIONS);
  private final AccountSnapshotMetricsActivities accountSnapshotMetrics =
      Workflow.newActivityStub(AccountSnapshotMetricsActivities.class, DEFAULT_OPTIONS);

  /**
   * Phase 2c.2: built lazily inside {@link #handleBto} / {@link #handleStc} from the loaded {@code
   * StrategyConfig.broker_target}, so a paper BTO with {@code broker_target=alpaca-paper} routes to
   * {@code broker-alpaca-paper}. Determinism: factory input comes from a deterministic Activity
   * lookup, so replays rebuild the same stub.
   */
  private ExecActivities exec;

  private FillSignalPayload fillEvent;
  private boolean riskBreachReceived;
  private String riskBreachReason;
  private String riskBreachActor;

  @Override
  public void onFill(FillSignalPayload event) {
    this.fillEvent = event;
  }

  @Override
  public void riskBreach(RiskBreachPayload payload) {
    int v = Workflow.getVersion(VERSION_RISK_BREACH, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // Signal handlers only set flags; the main path checks them at await/dispatch points.
    this.riskBreachReceived = true;
    this.riskBreachReason = payload.getReason();
    this.riskBreachActor = payload.getActor();
  }

  @Override
  public String process(CopytradeSignalPayload payload) {
    // Issue #308: enrich the SignalReceived subject with the parsed signal fields so the Discord
    // signal-feed mirror (SignalFeedAlerter) can render a "received" message at the fastest point —
    // before any risk gates. Carries action/ticker/expiry/strike/right/price/author/posted_at from
    // the CopytradeSignalPayload. Enums are stored as their wire string; dates/decimals are stored
    // as deterministic String renderings so the JSONB subject is replay-stable and self-describing.
    logAudit(payload, KIND_SIGNAL_RECEIVED, receivedSubject(payload));

    StrategyConfig config = strategy.get(payload.getTenantId(), payload.getStrategyId());

    // Phase 2c.2: route exec Activities to broker-<broker_target>. The factory throws a
    // non-retryable ApplicationFailure on invalid targets; let it propagate so the workflow
    // fails fast (an unroutable broker_target is a config bug, not a transient error).
    this.exec = ExecActivitiesFactory.forTarget(config.getBrokerTarget().value());

    switch (payload.getAction()) {
      case BTO:
        return handleBto(payload, config);
      case STC:
        return handleStc(payload, config);
      case AVG:
        return handleAvg(payload, config);
      default:
        return payload.getSignalId();
    }
  }

  private String handleBto(CopytradeSignalPayload payload, StrategyConfig config) {
    // Computed once at the top: the same limit feeds pre-trade notional, sizing, and the
    // OrderIntent/audit subject — guarantees those three views agree on max-acceptable cost.
    PricedLimit priced = BtoPricing.computeBtoLimit(payload, config);
    // Issue #112: Version gate retires the PR #111 deploy-time-drain mitigation. Pre-#111
    // in-flight workflows replay through the v=DEFAULT_VERSION branch (single checkEntry with
    // null preTradeResult); new executions take v>=1 and run the full assert → dispatch →
    // checkEntry sequence.
    int preTradeDispatchVersion =
        Workflow.getVersion(VERSION_PRE_TRADE_DISPATCH, Workflow.DEFAULT_VERSION, 1);
    RiskDecision decision;
    if (preTradeDispatchVersion == Workflow.DEFAULT_VERSION) {
      // Legacy pre-#111 path: single checkEntry call with null preTradeResult. Reachable only by
      // CopytradeSignalWorkflow executions that began before the pre-trade-dispatch-v2 patch was
      // deployed. New executions take the v>=1 branch.
      decision = risk.checkEntry(payload, config, null);
    } else {
      // Skip the assertion round-trip when the gate is off; the Activity itself short-circuits but
      // the dispatch cost is paid regardless.
      if (Boolean.TRUE.equals(config.getPreTradeCheckEnabled())) {
        risk.assertPreTradeCheckRoutable(config);
      }
      PreTradeCheckResult preTradeResult = dispatchPreTradeCheck(payload, config, priced.limit());
      int checkEntryWithLimitVersion =
          Workflow.getVersion(VERSION_CHECK_ENTRY_WITH_LIMIT, Workflow.DEFAULT_VERSION, 1);
      if (checkEntryWithLimitVersion == Workflow.DEFAULT_VERSION) {
        // In-flight v>=1 workflows whose history recorded a CheckEntry call before this gate
        // landed.
        decision = risk.checkEntry(payload, config, preTradeResult);
      } else {
        // Dispatch the broker-<broker_target> account read and thread the equity into
        // the notional-cap gate. Replay-gated: in-flight v>=1 histories that recorded the 4-arg
        // CheckEntryWithLimit call keep the legacy path (equity from the PortfolioSnapshot seam).
        int accountEquityVersion =
            Workflow.getVersion(VERSION_ACCOUNT_EQUITY_DISPATCH, Workflow.DEFAULT_VERSION, 1);
        if (accountEquityVersion == Workflow.DEFAULT_VERSION) {
          decision =
              risk.checkEntryWithLimit(payload, config, preTradeResult, priced.limit(), null);
        } else {
          BigDecimal accountCash = dispatchAccountSnapshot(payload, config);
          decision =
              risk.checkEntryWithLimit(
                  payload, config, preTradeResult, priced.limit(), accountCash);
        }
      }
    }
    if (!decision.allowed()) {
      Map<String, Object> rejectSubject =
          subject(
              "signal_id", payload.getSignalId(),
              "reason_code", decision.reason().name(),
              "outcome", "REJECTED");
      if (decision.detail() != null) {
        rejectSubject.put("reason_detail", decision.detail());
      }
      logAudit(payload, KIND_SIGNAL_REJECTED, rejectSubject);
      return payload.getSignalId();
    }

    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));

    BigDecimal capital =
        strategy.capitalForStrategy(payload.getTenantId(), payload.getStrategyId());
    long contracts = Sizing.computeContracts(payload, config, capital, priced.limit());

    logAudit(
        payload,
        KIND_SIGNAL_ACCEPTED,
        subject(
            "signal_id", payload.getSignalId(),
            "option_symbol", resolved.optionSymbol(),
            "contracts", contracts,
            "ref_premium", payload.getPrice()));

    // P3-a (multi-tenant-broker-credentials): the LIVE-only live-promotion dispatch gate. A real-
    // money BTO may only place an order when a fresh (not-stale) LivePromotionApproved row exists
    // for (tenant_id, strategy_id, broker_target). The verify fails CLOSED — ABSENT/STALE/VERIFY_
    // ERROR all refuse the order (no placeOrder, no PositionWorkflow), emit LivePromotionMissing,
    // and return the signal_id (the same fail-closed return shape as the reject path).
    //
    // Replay: getVersion is read UNCONDITIONALLY (before the isLive test). The new branch is
    // reachable only at gateV>=1; for DEFAULT_VERSION (legacy in-flight histories) the path is
    // byte-identical (only the marker is added). The paper path (!isLive) schedules ZERO verify
    // activities and emits NO new command at any version. Staleness uses Workflow.currentTimeMillis
    // (NOT OffsetDateTime.now) so the window is deterministic across replays.
    int gateV = Workflow.getVersion(VERSION_LIVE_PROMOTION_GATE, Workflow.DEFAULT_VERSION, 1);
    if (gateV >= 1 && StrategyConfigInvariants.isLive(config)) {
      long sinceMillis = Workflow.currentTimeMillis() - LIVE_PROMOTION_TTL.toMillis();
      OffsetDateTime notStaleSince =
          OffsetDateTime.ofInstant(Instant.ofEpochMilli(sinceMillis), ZoneOffset.UTC);
      LivePromotionStatus status =
          auditQuery.checkLivePromotion(
              payload.getTenantId(),
              payload.getStrategyId(),
              config.getBrokerTarget().value(),
              notStaleSince);
      if (status != LivePromotionStatus.VALID) {
        logAudit(
            payload,
            KIND_LIVE_PROMOTION_MISSING,
            subject(
                "signal_id", payload.getSignalId(),
                "tenant_id", payload.getTenantId(),
                "strategy_id", payload.getStrategyId(),
                "broker_target", config.getBrokerTarget().value(),
                "reason", status.name().toLowerCase(java.util.Locale.ROOT),
                "outcome", "REJECTED"));
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
    }

    String intentKey = Workflow.getInfo().getWorkflowId() + ":entry";
    OrderIntent intent = newIntent(payload, config, resolved, contracts, intentKey, priced.limit());
    OrderIntentResult placed = exec.placeOrder(intent);

    logAudit(
        payload,
        KIND_ORDER_SUBMITTED,
        subject(
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "option_symbol", resolved.optionSymbol(),
            "side", "BUY",
            "qty", contracts,
            "broker_target", config.getBrokerTarget().value(),
            "limit_price_strategy", priced.strategy().wireKey()));

    long ttlSecs = pendingTtlSecs(config);
    // Phase 5: also wake on risk_breach so the cascade can short-circuit the BTO.
    boolean filled =
        Workflow.await(Duration.ofSeconds(ttlSecs), () -> fillEvent != null || riskBreachReceived);

    if (riskBreachReceived && fillEvent == null) {
      // Cascade arrived before the onFill signal landed — but the broker may have already filled
      // (the async onFill races the breach and can lose). Issue #274: reconcile broker truth via
      // cancelOrder, which returns state=FILLED with broker-confirmed filled_qty/avg_fill_price on
      // a cancel-on-filled race (ExecActivitiesImpl ALREADY_FILLED → markFilled), and adopt the
      // filled lot through the existing handleCancelOnFilled → startPositionWorkflow recovery (the
      // TTL-expiry path already does this) instead of discarding the result and orphaning.
      //
      // Versioned for replay determinism. The legacy (v=DEFAULT_VERSION) branch preserves the
      // pre-fix command order EXACTLY — auditRiskBreachAbort (Log) before cancelOrder (CancelOrder)
      // — so replays of in-flight pre-fix histories that already took this branch match their
      // recorded ActivityTaskScheduled sequence. The new (v>=1) branch reorders to capture the
      // cancel result first, which is safe because it only ever runs against histories minted with
      // the version marker present.
      int breachAdoptionVersion =
          Workflow.getVersion(VERSION_BREACH_FILLED_ADOPTION, Workflow.DEFAULT_VERSION, 1);
      if (breachAdoptionVersion == Workflow.DEFAULT_VERSION) {
        // Legacy path: audit-and-abort, best-effort cancel (result discarded), unchanged ordering.
        auditRiskBreachAbort(payload, "bto_pre_fill", intentKey);
        try {
          exec.cancelOrder(intentKey);
        } catch (RuntimeException ignored) {
          // Best-effort: reconciliation closes any orphan broker order.
        }
        return payload.getSignalId();
      }
      OrderIntentResult cancelResult;
      try {
        cancelResult = exec.cancelOrder(intentKey);
      } catch (RuntimeException ignored) {
        // Best-effort cancel failed: audit-and-abort; reconciliation closes any orphan broker
        // order.
        auditRiskBreachAbort(payload, "bto_pre_fill", intentKey);
        return payload.getSignalId();
      }
      if (cancelResult.getState() == OrderIntentResult.State.FILLED) {
        handleCancelOnFilled(payload, config, resolved, cancelResult);
        return payload.getSignalId();
      }
      // Genuinely cancelled (or any non-FILLED state): keep audit-and-abort.
      auditRiskBreachAbort(payload, "bto_pre_fill", intentKey);
      return payload.getSignalId();
    }

    if (filled) {
      Map<String, Object> entrySubject =
          subject(
              "signal_id", payload.getSignalId(),
              "intent_key", placed.getIntentKey(),
              "broker_order_id", fillEvent.getBrokerOrderId(),
              "filled_qty", fillEvent.getFilledQty(),
              "avg_fill_price", fillEvent.getAvgFillPrice(),
              "outcome", "FILLED");
      // Issue #276: emit the per-symbol correlation key for the DailyPnl FIFO grouping, gated so
      // legacy replay histories reproduce the old subject (no option_symbol) deterministically.
      if (Workflow.getVersion(VERSION_ENTRY_FILLED_OPTION_SYMBOL, Workflow.DEFAULT_VERSION, 1)
          >= 1) {
        entrySubject.put("option_symbol", resolved.optionSymbol());
      }
      logAudit(payload, KIND_ENTRY_FILLED, entrySubject);

      // Phase 3: start PositionWorkflow + cache OCC → workflow_id mapping. Versioned so
      // Phase 2b workflows in flight on replay don't attempt to spawn a child.
      int v = Workflow.getVersion(VERSION_POSITION_HANDOFF, Workflow.DEFAULT_VERSION, 1);
      if (v >= 1) {
        startPositionWorkflow(payload, config, resolved, fillEvent);
      }
      return payload.getSignalId();
    }

    handleTtlExpired(payload, config, resolved, placed, intentKey, ttlSecs);
    return payload.getSignalId();
  }

  private void auditRiskBreachAbort(
      CopytradeSignalPayload payload, String stage, String intentKey) {
    Map<String, Object> s =
        subject(
            "signal_id",
            payload.getSignalId(),
            "stage",
            stage,
            "reason",
            riskBreachReason == null ? "" : riskBreachReason,
            "actor",
            riskBreachActor == null ? "" : riskBreachActor);
    if (intentKey != null) {
      s.put("intent_key", intentKey);
    }
    logAudit(payload, KIND_SIGNAL_ABORTED_BY_RISK_BREACH, s);
  }

  private void startPositionWorkflow(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      FillSignalPayload fill) {
    String tenant = payload.getTenantId();
    String strategyId = payload.getStrategyId();
    String posWfId =
        WorkflowIds.position(tenant, strategyId, resolved.optionSymbol(), payload.getSignalId());

    Map<String, Object> sa = new HashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenant, strategyId));
    sa.put("ContractSymbol", resolved.optionSymbol());

    ChildWorkflowOptions opts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId(posWfId)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .setSearchAttributes(sa)
            .build();
    PositionWorkflow child = Workflow.newChildWorkflowStub(PositionWorkflow.class, opts);

    PositionWorkflowInput posInput = new PositionWorkflowInput();
    posInput.setSchemaVersion(1L);
    posInput.setTenantId(tenant);
    posInput.setStrategyId(strategyId);
    posInput.setEntrySignalId(payload.getSignalId());
    posInput.setContractSymbol(resolved.optionSymbol());
    posInput.setQty(fill.getFilledQty());
    posInput.setEntryPremium(
        fill.getAvgFillPrice() != null ? fill.getAvgFillPrice() : payload.getPrice());
    posInput.setSourceSignalWorkflowId(Workflow.getInfo().getWorkflowId());
    // Phase 2c.2: carry broker_target so the child routes its exit/flatten Activities to the
    // same broker-<value> queue as the parent's entry.
    posInput.setBrokerTarget(
        PositionWorkflowInput.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    // Issue #202: carry eod_force_flatten so copytrade positions (and any future strategy that
    // mirrors an external author) skip the 15:55 ET EOD timer in PositionWorkflowImpl. Null is
    // passed through unchanged; PositionWorkflowImpl treats null as the default-true policy.
    posInput.setEodForceFlatten(config.getEodForceFlatten());
    // Issue #15: carry force_close_0dte_et so the child's 0DTE expiry-close timer fires at the
    // per-strategy time. Null/absent passes through unchanged; PositionWorkflowImpl defaults to
    // 15:30.
    posInput.setForceClose0dteEt(config.getForceClose0dteEt());
    // Issue #205: carry min_partial_qty_behavior so PositionWorkflow's runner-quantum gate can
    // decide between SKIP (default) and FULL_CLOSE when a partial signal would round to zero
    // contracts. Null/absent passes through unchanged; PositionWorkflowImpl treats null as SKIP.
    if (config.getMinPartialQtyBehavior() != null) {
      posInput.setMinPartialQtyBehavior(
          PositionWorkflowInput.MinPartialQtyBehavior.fromValue(
              config.getMinPartialQtyBehavior().value()));
    }
    // Issue #212: carry per-strategy first-fill / exit-fill TTLs so PositionWorkflowImpl's
    // bounded awaits (#203 entry-fill, #204 exit-fill) use the configured value selected by
    // broker_target instead of the hardcoded 90s constants. Paper broker_targets receive
    // pending_ttl_paper_secs; live broker_targets receive pending_ttl_live_secs; the StrategyConfig
    // fallback to 90L lives in {@link #selectPendingTtlSecs}. Both fields are passed regardless of
    // null-ness so the child sees a deterministic value once it crosses the VERSION_TTL_FROM_INPUT
    // v>=1 gate (the child still handles a null input field defensively for replays of
    // PositionWorkflowInput payloads minted by a pre-#212 parent).
    long ttlSecsForChild = selectPendingTtlSecs(config);
    posInput.setFirstFillTtlSecs(ttlSecsForChild);
    posInput.setExitFillTtlSecs(ttlSecsForChild);
    // Plan-2A R-AA-5: carry the bounded-flatten exit floors so PositionWorkflow's scheduled flatten
    // (R-AA-3, next chunk) can anchor a bounded LIMIT instead of a market dump. All three pass
    // through verbatim (null preserved); PositionWorkflowImpl treats null as the marketable
    // fail-safe fallback. Not consumed in workflow logic yet — plumb + default only.
    posInput.setExitFloorAbs(config.getExitFloorAbs());
    posInput.setExitFloorPct(config.getExitFloorPct());
    posInput.setExpiryDayFloor(config.getExpiryDayFloor());
    // Plan-2B R-AB-1/R-AB-2: carry the guaranteed-flatten lead + bounded stepped-reprice tunables
    // so
    // PositionWorkflow arms the multi-day expiry-lead timer (R-AB-1) and walks the bounded exit
    // reprice (R-AB-2). All pass through verbatim (null preserved → in-code defaults in the child:
    // flatten_lead_minutes 30, exit_reprice_steps 3, exit_reprice_tick 0.05). Consumption is
    // version-gated in the child so in-flight pre-2B workflows replay deterministically.
    posInput.setFlattenLeadMinutes(config.getFlattenLeadMinutes());
    posInput.setExitRepriceSteps(config.getExitRepriceSteps());
    posInput.setExitRepriceTick(config.getExitRepriceTick());

    Async.function(child::run, posInput);
    // Wait until the child is durably scheduled before returning.
    Workflow.getWorkflowExecution(child).get();

    // Issue #203: forward the BTO fill into the child so its v=1 first-fill await gate wakes and
    // PositionEntered fires with the real filled qty. Gated by a dedicated version so in-flight
    // workflows that already executed startPositionWorkflow without this command preserve their
    // recorded history on replay.
    int forwardFill = Workflow.getVersion(VERSION_FORWARD_BTO_FILL, Workflow.DEFAULT_VERSION, 1);
    if (forwardFill >= 1) {
      child.onFill(fill);
    }

    positionLookup.cachePositionMapping(tenant, strategyId, resolved.optionSymbol(), posWfId);
  }

  private String handleStc(CopytradeSignalPayload payload, StrategyConfig config) {
    if (riskBreachReceived) {
      auditRiskBreachAbort(payload, "stc_pre_resolve", null);
      return payload.getSignalId();
    }
    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));
    String tenant = payload.getTenantId();
    String strategyId = payload.getStrategyId();
    String occ = resolved.optionSymbol();

    long bufferSecs = pendingTtlSecs(config);
    int maxAttempts = (int) Math.max(1L, bufferSecs / 10L);
    String positionId = positionLookup.findPositionWorkflowId(tenant, strategyId, occ);
    int attempts = 0;
    while (positionId == null && attempts < maxAttempts && !riskBreachReceived) {
      // Use await-with-timeout so a co-arriving risk_breach signal wakes the loop early.
      Workflow.await(Duration.ofSeconds(10), () -> riskBreachReceived);
      if (riskBreachReceived) {
        break;
      }
      positionId = positionLookup.findPositionWorkflowId(tenant, strategyId, occ);
      attempts++;
    }
    if (riskBreachReceived) {
      auditRiskBreachAbort(payload, "stc_pre_dispatch", null);
      return payload.getSignalId();
    }
    if (positionId == null) {
      logAudit(
          payload,
          KIND_ORPHAN_STC,
          subject(
              "signal_id", payload.getSignalId(),
              "option_symbol", occ,
              "attempts", attempts));
      return payload.getSignalId();
    }

    int stcGuardVersion =
        Workflow.getVersion(VERSION_STC_RUNNING_GUARD, Workflow.DEFAULT_VERSION, 1);

    // Change point A (preventive): a stale Redis mapping can return a non-null but DEAD (terminal)
    // PositionWorkflow id. Verify it's RUNNING before emitting ExitRequested or signalling it. The
    // && short-circuit guarantees v=0 schedules NO isPositionWorkflowRunning activity (the v=0
    // command stream stays byte-identical).
    if (stcGuardVersion >= 1 && !positionLookup.isPositionWorkflowRunning(positionId)) {
      logAudit(
          payload,
          KIND_ORPHAN_STC,
          subject(
              "signal_id", payload.getSignalId(),
              "option_symbol", occ,
              "position_workflow_id", positionId,
              "reason", REASON_POSITION_WF_NOT_RUNNING));
      return payload.getSignalId();
    }

    double fraction =
        KeywordPartialMatcher.match(
            payload.getTail(),
            toDoubleMap(config.getPartialFractions()),
            defaultStcFraction(config));

    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(tenant);
    req.setStrategyId(strategyId);
    req.setSignalId(payload.getSignalId());
    req.setPositionWorkflowId(positionId);
    req.setFraction(BigDecimal.valueOf(fraction));
    req.setRefPremium(payload.getPrice());
    req.setReason("stc_signal");
    req.setAuthor(payload.getAuthor());
    req.setRawLine(payload.getRawLine());
    req.setOccurredAt(workflowNow());

    // Audit BEFORE dispatch so the intent is durably recorded even if the target workflow has
    // already closed (race) — reconciliation in Phase 5 reads these to detect orphan STCs.
    logAudit(
        payload,
        KIND_EXIT_REQUESTED,
        subject(
            "signal_id", payload.getSignalId(),
            "option_symbol", occ,
            "position_workflow_id", positionId,
            "fraction", fraction));

    ExternalWorkflowStub stub = Workflow.newUntypedExternalWorkflowStub(positionId);
    // Change point B (defense-in-depth): even past the running-guard the target can die between the
    // guard and the signal (TOCTOU). v=0 keeps the bare single command (byte-identical replay);
    // v>=1
    // catches the dispatch failure and emits OrphanSTC instead of crashing.
    //
    // The catch is narrow by construction: the try wraps ONLY the single stub.signal command, so
    // the
    // only exception that can originate here is a signal-external-workflow dispatch failure. The
    // Temporal Java SDK 1.27 surfaces a NOT_FOUND/terminal target as
    // io.temporal.failure.ApplicationFailure (type SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_*),
    // converted from the server Failure proto by DataConverter.failureToException — NOT as
    // SignalExternalWorkflowException (the SDK only constructs that type on the cancel-external
    // path
    // in this version). We catch both so the production crash is actually prevented; we
    // deliberately
    // do NOT catch bare RuntimeException so genuine bugs still fail the workflow loudly.
    if (stcGuardVersion == Workflow.DEFAULT_VERSION) {
      stub.signal("partialExit", req);
    } else {
      try {
        stub.signal("partialExit", req);
      } catch (SignalExternalWorkflowException | ApplicationFailure e) {
        logAudit(
            payload,
            KIND_ORPHAN_STC,
            subject(
                "signal_id", payload.getSignalId(),
                "option_symbol", occ,
                "position_workflow_id", positionId,
                "reason", REASON_SIGNAL_DISPATCH_FAILED,
                "error", String.valueOf(e.getMessage())));
        return payload.getSignalId();
      }
    }

    // Phase 4: arm CHANDELIER_TRAIL when the strategy opts in.
    if (Boolean.TRUE.equals(config.getTrailOnPartial())) {
      ArmChandelierPayload arm = new ArmChandelierPayload();
      arm.setSchemaVersion(1L);
      arm.setTenantId(tenant);
      arm.setStrategyId(strategyId);
      arm.setPositionWorkflowId(positionId);
      arm.setSourceSignalId(payload.getSignalId());
      arm.setPeakPremium(payload.getPrice());
      arm.setGivebackPct(config.getTrailGivebackPct());
      stub.signal("armChandelier", arm);
      logAudit(
          payload,
          KIND_CHANDELIER_ARM_REQUESTED,
          subject(
              "signal_id", payload.getSignalId(),
              "position_workflow_id", positionId,
              "peak_premium", payload.getPrice(),
              "giveback_pct", config.getTrailGivebackPct()));
    }

    return payload.getSignalId();
  }

  private String handleAvg(CopytradeSignalPayload payload, StrategyConfig config) {
    if (Boolean.TRUE.equals(config.getSkipAvg())) {
      logAudit(
          payload,
          KIND_AVG_SKIPPED,
          subject("signal_id", payload.getSignalId(), "note", "skip_avg_true"));
      return payload.getSignalId();
    }
    // Phase 3 does not act on AVG when not skipped — Phase 5+ resolves Open Question #10 further.
    logAudit(
        payload,
        KIND_AVG_SKIPPED,
        subject("signal_id", payload.getSignalId(), "note", "avg_not_implemented"));
    return payload.getSignalId();
  }

  private void handleTtlExpired(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      OrderIntentResult placed,
      String intentKey,
      long ttlSecs) {
    logAudit(
        payload,
        KIND_ORDER_CANCEL_REQUESTED,
        subject(
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "reason", REASON_TTL_EXPIRED));

    OrderIntentResult cancelResult = exec.cancelOrder(intentKey);

    // Issue #165 phase 2: when the broker filled inside the TTL/cancel race, the exec sidecar
    // now reconciles the journal to FILLED and returns the broker-confirmed fill detail. Adopt
    // the orphan position by spawning the PositionWorkflow instead of emitting EntryExpired.
    // Versioned so replays of pre-fix histories deterministically take the legacy paths below.
    int adoptionVersion =
        Workflow.getVersion(VERSION_TTL_FILLED_ADOPTION, Workflow.DEFAULT_VERSION, 1);
    if (adoptionVersion >= 1 && cancelResult.getState() == OrderIntentResult.State.FILLED) {
      handleCancelOnFilled(payload, config, resolved, cancelResult);
      return;
    }

    if (cancelResult.getState() == OrderIntentResult.State.CANCELLED) {
      logAudit(
          payload,
          KIND_ORDER_CANCELLED,
          subject(
              "intent_key", placed.getIntentKey(),
              "broker_order_id", placed.getBrokerOrderId(),
              "reason", REASON_TTL_EXPIRED));
    } else {
      // Issue #165 phase 2: drop the `orphan_position_until_phase_3` note value. The orphan case
      // is now either recovered by the FILLED branch above or detected by Phase 3 reconciliation.
      // Keep the `note` key so audit consumers don't break.
      logAudit(
          payload,
          KIND_ORDER_CANCEL_FAILED,
          subject(
              "intent_key", placed.getIntentKey(),
              "broker_order_id", placed.getBrokerOrderId(),
              "broker_reason", cancelResult.getLastError(),
              "severity", "ERROR",
              "note", "cancel_failed"));
    }

    logAudit(
        payload,
        KIND_ENTRY_EXPIRED,
        subject(
            "signal_id", payload.getSignalId(),
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "ttl_secs", ttlSecs,
            "outcome", "EXPIRED"));
  }

  /**
   * Issue #165 phase 2: recover from the cancel-on-filled race by synthesising a {@link
   * FillSignalPayload} from the broker-confirmed cancel result, emitting {@code EntryFilled} with a
   * {@code recovery=cancel_on_filled} marker, and spawning the missing PositionWorkflow. Mirrors
   * the happy-path fill branch's audit + child-workflow handoff so subsequent STCs route to {@code
   * partialExit} rather than producing {@code OrphanSTC}.
   */
  private void handleCancelOnFilled(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      OrderIntentResult cancelResult) {
    long filledQty = cancelResult.getFilledQty() != null ? cancelResult.getFilledQty() : 0L;
    BigDecimal avgFillPrice =
        cancelResult.getAvgFillPrice() != null
            ? cancelResult.getAvgFillPrice()
            : payload.getPrice();
    FillSignalPayload synth =
        new FillSignalPayload()
            .withBrokerOrderId(cancelResult.getBrokerOrderId())
            .withFilledQty(filledQty)
            .withAvgFillPrice(avgFillPrice)
            .withFilledAt(workflowNow());

    Map<String, Object> recoverySubject =
        subject(
            "signal_id", payload.getSignalId(),
            "intent_key", cancelResult.getIntentKey(),
            "broker_order_id", synth.getBrokerOrderId(),
            "filled_qty", synth.getFilledQty(),
            "avg_fill_price", synth.getAvgFillPrice(),
            "outcome", "FILLED",
            "recovery", "cancel_on_filled");
    // Issue #276: same option_symbol correlation key as the happy-path fill, same replay gate so
    // the cancel-on-filled recovery audit also groups per symbol in DailyPnl for new executions.
    if (Workflow.getVersion(VERSION_ENTRY_FILLED_OPTION_SYMBOL, Workflow.DEFAULT_VERSION, 1) >= 1) {
      recoverySubject.put("option_symbol", resolved.optionSymbol());
    }
    logAudit(payload, KIND_ENTRY_FILLED, recoverySubject);

    startPositionWorkflow(payload, config, resolved, synth);
  }

  /**
   * Dispatches the cross-service {@code PreTradeCheckActivity}. Returns {@code null} when the gate
   * is disabled — the downstream {@code checkPreTradeCheck} branch short-circuits in that case.
   *
   * <p>Fail-closed semantics: any exception (after Temporal's own retries) is converted to a
   * sentinel {@link PreTradeCheckResult} with {@code allowed=false} and {@code
   * rejectReason="dispatch_failed:<ExceptionSimpleName>"}. {@code RiskActivitiesImpl.checkEntry}
   * then surfaces {@code PRE_TRADE_CHECK_FAILED} via the existing {@code allowed=false} branch.
   *
   * <p>Determinism: the request is built from {@code payload + config + Sizing.CONTRACT_MULTIPLIER}
   * only — no clock reads, no random IDs (the correlation id is the deterministic {@code
   * signalId}). Safe to call inside the workflow body.
   */
  private PreTradeCheckResult dispatchPreTradeCheck(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal estimatedLimitPrice) {
    if (!Boolean.TRUE.equals(config.getPreTradeCheckEnabled())) {
      return null;
    }
    if (config.getBrokerTarget() == null) {
      return PreTradeCheckSentinels.dispatchFailed("NullBrokerTarget");
    }
    // Bound retries so a persistently-failing pre-trade endpoint surfaces as the dispatch-failed
    // sentinel within the workflow's TTL window rather than retrying forever. 3 attempts mirrors
    // the kill-switch read tolerance — enough to absorb a transient broker hiccup, short enough
    // that a real outage fails closed quickly.
    //
    // Schedule-to-close envelope: start-to-close (15s) × maxAttempts (3) = 45s of pure run time,
    // plus exponential-backoff jitter between attempts can push the wall-clock total past 45s
    // before the sentinel is produced. A literal 60s schedule-to-close caps the worst case so
    // the fail-closed latency is explicit and predictable vs the workflow TTL (issue #115).
    PreTradeCheckActivity preTradeStub =
        Workflow.newActivityStub(
            PreTradeCheckActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(config.getBrokerTarget().value()))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    PreTradeCheckRequest request = buildPreTradeCheckRequest(payload, config, estimatedLimitPrice);
    try {
      return preTradeStub.preTradeCheck(request);
    } catch (CanceledFailure cf) {
      throw cf;
    } catch (Exception e) {
      return PreTradeCheckSentinels.dispatchFailed(e.getClass().getSimpleName());
    }
  }

  /**
   * Builds the {@link PreTradeCheckRequest} the workflow dispatches to exec-svc. {@code
   * estimated_notional} is the 1-contract floor computed against the slip-adjusted limit
   * (max-acceptable cost) so the risk-svc cap sees the realistic worst-case rather than the
   * optimistic mirror. The workflow sizes down later if needed. {@code correlation_id} is the
   * deterministic {@code signal_id} so audit traces stitch end-to-end.
   */
  private static PreTradeCheckRequest buildPreTradeCheckRequest(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal estimatedLimitPrice) {
    PreTradeCheckRequest r = new PreTradeCheckRequest();
    r.setSchemaVersion(1L);
    r.setTenantId(payload.getTenantId());
    r.setStrategyId(payload.getStrategyId());
    r.setBrokerTarget(
        PreTradeCheckRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    r.setOptionSymbol(payload.getTicker());
    r.setSide(PreTradeCheckRequest.Side.BUY);
    Long minContracts = config.getMinContracts();
    r.setQty(minContracts == null ? 1L : Math.max(1L, minContracts));
    BigDecimal limit = estimatedLimitPrice == null ? BigDecimal.ZERO : estimatedLimitPrice;
    r.setEstimatedNotional(limit.multiply(Sizing.CONTRACT_MULTIPLIER));
    r.setCorrelationId(payload.getSignalId());
    return r;
  }

  /**
   * Dispatches the cross-service {@code AccountSnapshotActivity} over the {@code
   * broker-<broker_target>} task queue and returns the account's <b>cash</b> balance — the cash
   * component of the notional-cap gate's MTM-stable cost-basis capital base ({@code cash +
   * sum_open_notional}, issue #323). Returns {@code null} when the notional-cap gate is disabled
   * (BOTH {@code notional_cap_pct_of_capital_base} and {@code notional_cap_pct_of_equity} null, per
   * {@link StrategyConfigs#notionalCapConfigured}) so the cross-service round-trip only fires when
   * the strategy enabled the gate.
   *
   * <p>Fail-closed semantics: any exception (after Temporal's own retries), a null/blank {@code
   * broker_target}, or a null result/cash yields {@code BigDecimal.ZERO}. The downstream {@code
   * checkNotionalCap} gate rejects on a zero/missing capital base, so a broker outage (or a
   * pre-#323 producer that omits {@code cash}) rejects entries rather than passing an unbounded cap
   * — mirroring {@code dispatchPreTradeCheck}.
   *
   * <p>Determinism: the request is built from {@code config.broker_target + signal_id} only — no
   * clock reads, no random IDs. Safe to call inside the workflow body. The account figures are
   * account-level, so the request carries no tenant/strategy. Reading {@code getCash()} instead of
   * {@code getEquity()} is a field read on the same activity result — no history-shape change, so
   * it stays within the existing {@code VERSION_ACCOUNT_EQUITY_DISPATCH} gate.
   */
  private BigDecimal dispatchAccountSnapshot(
      CopytradeSignalPayload payload, StrategyConfig config) {
    // Enablement mirrors RiskActivitiesImpl#resolveNotionalCapPct (#336): the cap (and so the cash
    // dispatch) is on when EITHER the canonical notional_cap_pct_of_capital_base OR the deprecated
    // notional_cap_pct_of_equity is set. Pre-#336 this tested equity-only, so a config that set
    // ONLY
    // the new canonical field (the migration end-state) skipped the dispatch and checkNotionalCap
    // rejected every BTO with cash_unavailable. Sharing StrategyConfigs.notionalCapConfigured keeps
    // guard and resolver in lockstep.
    //
    // Replay-safe by construction (no new Workflow.getVersion marker needed): `config` reaches this
    // method as the deterministically recorded result of the strategy.get() Activity (read once in
    // process(), threaded down — never re-resolved here on replay). A history recorded before #336
    // physically cannot carry notional_cap_pct_of_capital_base (the field did not exist when that
    // Activity result was serialized), so for any pre-existing execution capBase is null on replay
    // and this guard reduces to the old equity-only decision — the command stream is unchanged. The
    // new branch is reachable only by executions whose history was recorded post-#336.
    if (!StrategyConfigs.notionalCapConfigured(config)) {
      return null;
    }
    if (config.getBrokerTarget() == null) {
      return BigDecimal.ZERO;
    }
    AccountSnapshotActivity accountStub =
        Workflow.newActivityStub(
            AccountSnapshotActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(config.getBrokerTarget().value()))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    AccountSnapshotRequest request = buildAccountSnapshotRequest(payload, config);
    try {
      AccountSnapshotResult result = accountStub.accountSnapshot(request);
      return result == null || result.getCash() == null ? BigDecimal.ZERO : result.getCash();
    } catch (CanceledFailure cf) {
      throw cf;
    } catch (Exception e) {
      // Fail-closed dispatch failure (#323): emit a diagnostic warn + the symmetric
      // accountsnapshot_dispatch_failures_total counter (mirrors the #329 openpositions
      // value-failures counter) so a persistent broker outage that drives the cash term to the ZERO
      // sentinel is distinguishable in metrics from a legitimate zero-cash account. The counter
      // lives in an Activity (replay-safe; MeterRegistry cannot be touched from the deterministic
      // workflow body); the emit is wrapped non-fatal so a metrics outage cannot change the
      // fail-closed ZERO outcome below.
      Workflow.getLogger(CopytradeSignalWorkflowImpl.class)
          .warn(
              "accountSnapshot dispatch failed; failing closed to ZERO cash broker_target={} err={}",
              config.getBrokerTarget().value(),
              e.getMessage(),
              e);
      try {
        accountSnapshotMetrics.recordDispatchFailure(config.getBrokerTarget().value());
      } catch (RuntimeException metricsError) {
        if (metricsError instanceof CanceledFailure cf) throw cf;
        // observability-only; never let a metrics failure flip the fail-closed outcome.
      }
      return BigDecimal.ZERO;
    }
  }

  /**
   * Builds the {@link AccountSnapshotRequest} the workflow dispatches to exec-svc. Equity is
   * account-level (tenant/strategy-independent), so the request is keyed solely on {@code
   * broker_target}; {@code correlation_id} is the deterministic {@code signal_id} so audit traces
   * stitch end-to-end.
   */
  private static AccountSnapshotRequest buildAccountSnapshotRequest(
      CopytradeSignalPayload payload, StrategyConfig config) {
    AccountSnapshotRequest r = new AccountSnapshotRequest();
    r.setSchemaVersion(1L);
    r.setBrokerTarget(
        AccountSnapshotRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    r.setCorrelationId(payload.getSignalId());
    return r;
  }

  private long pendingTtlSecs(StrategyConfig config) {
    Long configured = config.getPendingTtlPaperSecs();
    return configured != null ? configured : DEFAULT_PENDING_TTL_PAPER_SECS;
  }

  /**
   * Issue #212: selects the per-strategy pending TTL value to forward into PositionWorkflowInput's
   * first_fill_ttl_secs / exit_fill_ttl_secs. Inspects {@code config.broker_target.value()}: a
   * "live" substring uses {@code pending_ttl_live_secs}; otherwise (any "paper" or unknown variant)
   * uses {@code pending_ttl_paper_secs}. Falls back to {@link #DEFAULT_PENDING_TTL_PAPER_SECS}
   * (90L) when the selected StrategyConfig field is null/absent. The substring match keeps the
   * helper open to future broker_target enum additions (e.g. {@code ibkr-paper}, {@code
   * tradier-live}) without re-touching this method.
   */
  long selectPendingTtlSecs(StrategyConfig config) {
    String target = config.getBrokerTarget() != null ? config.getBrokerTarget().value() : "";
    boolean isLive = target.contains("live");
    Long configured = isLive ? config.getPendingTtlLiveSecs() : config.getPendingTtlPaperSecs();
    return configured != null ? configured : DEFAULT_PENDING_TTL_PAPER_SECS;
  }

  private double defaultStcFraction(StrategyConfig config) {
    BigDecimal configured = config.getDefaultStcFraction();
    return configured != null ? configured.doubleValue() : DEFAULT_STC_FRACTION;
  }

  private static Map<String, Double> toDoubleMap(Map<String, BigDecimal> in) {
    if (in == null || in.isEmpty()) {
      return Map.of();
    }
    Map<String, Double> out = new LinkedHashMap<>(in.size());
    for (Map.Entry<String, BigDecimal> e : in.entrySet()) {
      if (e.getValue() != null) {
        out.put(e.getKey(), e.getValue().doubleValue());
      }
    }
    return out;
  }

  private OrderIntent newIntent(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      long contracts,
      String intentKey,
      BigDecimal limitPrice) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(payload.getTenantId());
    i.setStrategyId(payload.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId(payload.getSignalId());
    i.setBrokerTarget(OrderIntent.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    i.setOptionSymbol(resolved.optionSymbol());
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(contracts);
    i.setLimitPrice(limitPrice);
    i.setRecordedAt(workflowNow());
    return i;
  }

  private void logAudit(CopytradeSignalPayload payload, String kind, Map<String, Object> subject) {
    audit.log(auditEvent(payload, kind, subject));
  }

  /**
   * Issue #308: builds the enriched {@code SignalReceived} subject. Beyond the original {@code
   * signal_id}, it carries the parsed signal fields the feed mirror renders. Each value is rendered
   * to a deterministic, null-safe String (enums to their wire value; date/decimal to {@code
   * toString()}) so the JSONB subject is replay-stable regardless of Jackson type handling.
   */
  private static Map<String, Object> receivedSubject(CopytradeSignalPayload payload) {
    return subject(
        "signal_id", payload.getSignalId(),
        "action", str(payload.getAction()),
        "ticker", str(payload.getTicker()),
        "expiry", str(payload.getExpiry()),
        "strike", str(payload.getStrike()),
        "right", str(payload.getRight()),
        "price", str(payload.getPrice()),
        "author", str(payload.getAuthor()),
        "posted_at", str(payload.getPostedAt()));
  }

  /** Null-safe stringifier used to render the enriched SignalReceived subject deterministically. */
  private static String str(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof CopytradeSignalPayload.Action action) {
      return action.value();
    }
    if (value instanceof CopytradeSignalPayload.Right right) {
      return right.value();
    }
    return String.valueOf(value);
  }

  /** Builds an insertion-ordered subject map from alternating key/value varargs. */
  private static Map<String, Object> subject(Object... kv) {
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException("subject() requires an even number of key/value args");
    }
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private AuditEvent auditEvent(
      CopytradeSignalPayload payload, String kind, Map<String, ?> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(payload.getTenantId());
    event.setStrategyId(payload.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:CopytradeSignalWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(payload.getSignalId());
    return event;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
