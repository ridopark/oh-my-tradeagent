package com.ohmytradeagent.orchestrator.workflows;

import static com.ohmytradeagent.orchestrator.workflows.CondorSessionWorkflowImpl.subject;
import static com.ohmytradeagent.orchestrator.workflows.CondorSessionWorkflowImpl.workflowNow;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.activities.CondorExecActivity;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorFlattenRequest;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorFlattenResult;
import com.ohmytradeagent.contract.activities.CondorExecActivity.HeldLeg;
import com.ohmytradeagent.contract.activities.CondorMarketActivity;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.domain.CondorSettlement;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds one filled gated condor to cash settlement. Net-new workflow type: NO {@code
 * Workflow.getVersion} gates; the body reads no wall clock or RNG except via {@code Workflow.*} and
 * Activity results.
 *
 * <p>On start it seeds the recon position cache with this workflow's id for each of the four legs
 * (so the OCC-anchored orphan sweep sees them as owned — a condor leg has no OCC journal row). It
 * then waits for {@link #SETTLE_CHECK_ET}; a {@link #forceClose} or a kill-switch {@link
 * #riskBreach} before then flattens all four legs, shorts first ({@code exit_reason} {@code
 * operator_force_close} / {@code killswitch_flatten}). Otherwise, after the close, it prices the
 * expected cash settlement from the settlement spot ({@value #KIND_SETTLED}) and, after the
 * broker's overnight expiry processing, reconciles: any leg the broker still holds at the next
 * session's open is a {@value #KIND_SETTLE_MISMATCH} page (the plan's hard-kill "position past
 * expiry without settlement booked").
 */
public class CondorHoldWorkflowImpl implements CondorHoldWorkflow {

  static final LocalTime SETTLE_CHECK_ET = LocalTime.of(16, 15);

  /** Past the open so the broker's overnight expiry/settlement processing has posted. */
  static final Duration RECONCILE_AFTER_OPEN = Duration.ofMinutes(5);

  /**
   * Explicit flatten/read retry policy: each exec call is attempted at most this many times. The
   * flatten is idempotent per leg (every close is journaled under its own condor- key and a re-run
   * reuses a SUBMITTED/FILLED leg), so this is effectively 3 attempts per leg, shorts covered
   * first. Legs still open after the last attempt → {@value #KIND_FLATTEN_INCOMPLETE} (pages) and
   * the hold stays alive holding the remainder through settlement reconciliation — it never gives
   * up silently and never loops.
   */
  static final int EXEC_MAX_ATTEMPTS = 3;

  static final String EXIT_OPERATOR = "operator_force_close";
  static final String EXIT_KILLSWITCH = "killswitch_flatten";

  static final String KIND_FLATTENED = "CondorFlattened";
  static final String KIND_FLATTEN_INCOMPLETE = "CondorFlattenIncomplete";
  static final String KIND_SETTLED = "CondorSettled";
  static final String KIND_SETTLE_MISMATCH = "CondorSettleMismatch";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  // Best-effort (#901): the recon cache seed must never block or fail the hold — one short attempt,
  // failure logged and ignored (mirrors the watchlist cacheArmedLeg contract).
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(
          PositionLookupActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());
  private final CondorMarketActivity market =
      Workflow.newActivityStub(
          CondorMarketActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofMinutes(1))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  private CondorHoldWorkflowInput input;
  private String exitReason;
  private String exitActor;

  @Override
  public String run(CondorHoldWorkflowInput in) {
    if (in.getSchemaVersion() > CondorHoldWorkflowInput.SCHEMA_VERSION) {
      throw ApplicationFailure.newNonRetryableFailure(
          "CondorHoldWorkflowInput schema_version "
              + in.getSchemaVersion()
              + " is newer than this build",
          "SchemaVersionUnsupported");
    }
    this.input = in;
    String self = Workflow.getInfo().getWorkflowId();
    for (CondorLeg leg : in.getLegs()) {
      try {
        positionLookup.cachePositionMapping(
            in.getTenantId(), in.getStrategyId(), leg.occSymbol(), self);
      } catch (ActivityFailure e) {
        Workflow.getLogger(CondorHoldWorkflowImpl.class)
            .warn("condor pos cache seed failed occ={} err={}", leg.occSymbol(), e.getMessage());
      }
    }

    Duration untilSettle = calendar.durationUntilEodCloseEt(SETTLE_CHECK_ET);
    boolean exitRequested =
        untilSettle != null && untilSettle.compareTo(Duration.ZERO) > 0
            ? Workflow.await(untilSettle, () -> exitReason != null)
            : exitReason != null;
    // A flatten that did not close all four legs (a short not confirmed covered, or the exec call
    // failed — e.g. after the 16:00 expiry close) still falls through to settlement
    // reconciliation, so whatever is left is priced and checked against the broker.
    if (exitRequested && flatten()) {
      return "flattened:" + exitReason;
    }
    return settle(exitRequested);
  }

  @Override
  public void forceClose(String actor) {
    requestExit(EXIT_OPERATOR, actor);
  }

  @Override
  public void riskBreach(RiskBreachPayload payload) {
    requestExit(EXIT_KILLSWITCH, payload == null ? null : payload.getActor());
  }

  private void requestExit(String reason, String actor) {
    if (exitReason == null) {
      exitReason = reason;
      exitActor = actor;
    }
  }

  /** True iff all four legs were closed. */
  private boolean flatten() {
    CondorFlattenResult r;
    try {
      r =
          condorExec()
              .flattenCondor(
                  new CondorFlattenRequest(
                      input.getTenantId(),
                      input.getStrategyId(),
                      input.getBrokerTarget(),
                      input.getAttemptId(),
                      input.getQty(),
                      input.getLegs()));
    } catch (ActivityFailure e) {
      r = new CondorFlattenResult(false, false, "flatten_activity_failed: " + e.getCause());
    }
    logAudit(
        r.shortsCovered() && r.longsClosed() ? KIND_FLATTENED : KIND_FLATTEN_INCOMPLETE,
        subject(
            "exit_reason", exitReason,
            "actor", exitActor,
            "shorts_covered", r.shortsCovered(),
            "longs_closed", r.longsClosed(),
            "reason", r.reason(),
            "legs", occs()));
    return r.shortsCovered() && r.longsClosed();
  }

  /**
   * @param afterPartialFlatten a flatten closed only some legs: price only the legs the broker
   *     still holds, with no credit (the entry credit and the closing fills are booked separately),
   *     so {@code expected_pnl} is the settlement cash of the remainder.
   */
  private String settle(boolean afterPartialFlatten) {
    List<CondorLeg> priced = input.getLegs();
    java.math.BigDecimal credit = input.getCredit();
    if (afterPartialFlatten) {
      List<String> held =
          condorExec().heldCondorLegs(input.getTenantId(), input.getBrokerTarget(), occs()).stream()
              .map(HeldLeg::occSymbol)
              .toList();
      priced = input.getLegs().stream().filter(l -> held.contains(l.occSymbol())).toList();
      credit = java.math.BigDecimal.ZERO;
    }
    Double spot = market.settlementSpot(input.getUnderlying());
    // A missing (no bars) or stale (no strip within the bar-staleness window) settlement spot is
    // NEVER priced as zero: page and mark this settlement unresolved for the operator.
    boolean unresolved = spot == null || !Double.isFinite(spot) || spot <= 0;
    if (unresolved) {
      logAudit(
          KIND_SETTLE_MISMATCH,
          subject("reason", "settlement_spot_unavailable", "settlement_spot", spot));
    } else {
      CondorSettlement.Result s = CondorSettlement.settle(priced, credit, input.getQty(), spot);
      logAudit(
          KIND_SETTLED,
          subject(
              "settlement_spot",
              spot,
              "credit",
              credit,
              "settlement_debit",
              s.debit(),
              "expected_pnl",
              s.pnl(),
              "outcome",
              s.itm() ? "ITM" : "OTM",
              "qty",
              input.getQty(),
              "partial",
              afterPartialFlatten,
              "legs_priced",
              priced.stream().map(CondorLeg::occSymbol).toList()));
    }

    Duration untilOpen = calendar.durationUntilNextRthOpenEt();
    Workflow.sleep(untilOpen.plus(RECONCILE_AFTER_OPEN));
    List<HeldLeg> held =
        condorExec().heldCondorLegs(input.getTenantId(), input.getBrokerTarget(), occs());
    if (!held.isEmpty()) {
      Map<String, Object> heldQty = new LinkedHashMap<>();
      held.forEach(h -> heldQty.put(h.occSymbol(), h.qty()));
      logAudit(
          KIND_SETTLE_MISMATCH, subject("reason", "legs_still_held_after_expiry", "held", heldQty));
      return "settle_mismatch";
    }
    return unresolved ? "settle_unresolved" : "settled";
  }

  private List<String> occs() {
    return input.getLegs().stream().map(CondorLeg::occSymbol).toList();
  }

  private CondorExecActivity condorExec() {
    return Workflow.newActivityStub(
        CondorExecActivity.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(ExecActivitiesFactory.taskQueueFor(input.getBrokerTarget()))
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .setHeartbeatTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder().setMaximumAttempts(EXEC_MAX_ATTEMPTS).build())
            .build());
  }

  private void logAudit(String kind, Map<String, Object> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(input.getTenantId());
    event.setStrategyId(input.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:CondorHoldWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    audit.log(event);
  }
}
