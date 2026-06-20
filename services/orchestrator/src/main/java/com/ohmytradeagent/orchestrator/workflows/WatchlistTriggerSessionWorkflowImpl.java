package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.ArmDecision;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.WatchlistEntryDecider;
import com.ohmytradeagent.orchestrator.activities.WatchlistTriggerActivities;
import com.ohmytradeagent.orchestrator.activities.WatchlistTriggerLeg;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent watchlist-trigger session. Net-new workflow type, so it carries NO {@code
 * Workflow.getVersion} gates. Determinism: the body reads no wall clock and no RNG except via
 * {@code Workflow.*} helpers and Activity results; there are no signal handlers.
 *
 * <p>Flow: (1) Phase-7 enable gate — {@code enabled==false} arms nothing and returns; (2) parse the
 * watchlist in an Activity (keeps the regex/strike-validation off the deterministic path); (3) one
 * {@code dispatchAccountSnapshot} -> {@link ArmContext}; (4) for each leg up to {@link
 * #MAX_FANOUT_LEGS}, run {@link WatchlistEntryDecider#evaluateWatchlistEntry} and, when armed,
 * start a child {@link WatchlistTriggerWorkflow} (the parent NEVER pre-judges the live cross — a
 * leg already past its level is armed normally and the child enforces the cross); (5) at the EOD
 * timer, cancel every un-fired child, TOLERATING an already-terminal child so one completed child
 * never crashes the parent.
 */
public class WatchlistTriggerSessionWorkflowImpl implements WatchlistTriggerSessionWorkflow {

  /**
   * Fan-out cap: a daily watchlist is ~8 legs; this bounds a pathological/garbage parse so the
   * session never starts an unbounded burst of children. Exceeding it audits and arms only the
   * first {@code MAX_FANOUT_LEGS} legs.
   */
  static final int MAX_FANOUT_LEGS = 64;

  private static final String KIND_SESSION_DISABLED = "WatchlistSessionDisabled";
  private static final String KIND_SESSION_STARTED = "WatchlistSessionStarted";
  private static final String KIND_LEG_SKIPPED = "WatchlistLegSkipped";
  private static final String KIND_LEG_ARM_REJECTED = "WatchlistLegArmRejected";
  private static final String KIND_LEG_ARMED = "WatchlistLegArmed";
  private static final String KIND_FANOUT_CAP_EXCEEDED = "WatchlistFanoutCapExceeded";
  private static final String KIND_SESSION_EOD = "WatchlistSessionEod";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final WatchlistTriggerActivities parser =
      Workflow.newActivityStub(WatchlistTriggerActivities.class, DEFAULT_OPTIONS);
  private final WatchlistEntryDecider decider =
      Workflow.newActivityStub(WatchlistEntryDecider.class, DEFAULT_OPTIONS);

  @Override
  public String run(WatchlistTriggerSessionWorkflowInput input) {
    WatchlistMirrorPayload source = input.getSource();
    StrategyConfig config = input.getConfig();

    // (1) Phase-7 watchlist-side enable gate: explicit false arms NOTHING. Absent/null/true
    // proceed.
    if (Boolean.FALSE.equals(config.getEnabled())) {
      logAudit(source, KIND_SESSION_DISABLED, subject("reason", "strategy_disabled"));
      return summary(0, 0, false);
    }

    // (2) Parse + map in an Activity (off the deterministic workflow path).
    List<WatchlistTriggerLeg> legs = parser.parseWatchlistTriggers(source);

    // (3) One account snapshot -> ArmContext (cash + et_date) shared across every leg's arm gate.
    BigDecimal cash = dispatchAccountSnapshot(source, config);
    ArmContext armCtx = new ArmContext().withEtDate(source.getEtDate()).withCash(cash);

    logAudit(source, KIND_SESSION_STARTED, subject("legs", legs.size(), "cap", MAX_FANOUT_LEGS));

    int armed = 0;
    int skipped = 0;
    int considered = 0;
    List<String> startedChildIds = new ArrayList<>();

    for (WatchlistTriggerLeg leg : legs) {
      // Malformed strike/right -> skip THIS leg + audit (fail at arm time, before any fire).
      // Skipped BEFORE the cap check so malformed legs never consume a cap slot.
      if (!leg.armable()) {
        skipped++;
        logAudit(
            source,
            KIND_LEG_SKIPPED,
            subject(
                "ticker", leg.getTicker(),
                "right", leg.getRightLabel(),
                "reason", leg.getSkipReason()));
        continue;
      }

      if (considered >= MAX_FANOUT_LEGS) {
        logAudit(
            source,
            KIND_FANOUT_CAP_EXCEEDED,
            subject("cap", MAX_FANOUT_LEGS, "total_legs", legs.size()));
        break;
      }
      considered++;

      WatchlistTriggerPayload payload = leg.getPayload();
      ArmDecision decision = decider.evaluateWatchlistEntry(payload, armCtx);
      if (!Boolean.TRUE.equals(decision.getArm())) {
        skipped++;
        logAudit(
            source,
            KIND_LEG_ARM_REJECTED,
            subject(
                "ticker", payload.getTicker(),
                "right", payload.getRight().value(),
                "reason", decision.getReason()));
        continue;
      }

      String childId = childWorkflowId(payload);
      startChild(childId, payload, config, decision.getSizeMultiplier());
      startedChildIds.add(childId);
      armed++;
      logAudit(
          source,
          KIND_LEG_ARMED,
          subject(
              "ticker", payload.getTicker(),
              "right", payload.getRight().value(),
              "child_workflow_id", childId,
              "size_multiplier", decision.getSizeMultiplier()));
    }

    // (5) EOD sweep: wait for the calendar-supplied close, then cancel un-fired children.
    boolean eod = awaitEodAndCancel(source, startedChildIds);
    return summary(armed, skipped, eod);
  }

  /**
   * Starts the child {@link WatchlistTriggerWorkflow} async with {@code REJECT_DUPLICATE}
   * (idempotent re-arm: a same-day re-post collides on the deterministic child id) and {@code
   * PARENT_CLOSE_POLICY_ABANDON} so a child that out-lives the parent's EOD cancel still completes
   * its own fail-closed path.
   */
  private void startChild(
      String childId,
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      BigDecimal sizeMultiplier) {
    Map<String, Object> sa = new LinkedHashMap<>();
    sa.put(
        "TenantStrategy",
        WorkflowIds.tenantStrategy(payload.getTenantId(), payload.getStrategyId()));

    ChildWorkflowOptions opts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId(childId)
            .setWorkflowIdReusePolicy(
                io.temporal.api.enums.v1.WorkflowIdReusePolicy
                    .WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .setSearchAttributes(sa)
            .build();
    WatchlistTriggerWorkflow child =
        Workflow.newChildWorkflowStub(WatchlistTriggerWorkflow.class, opts);
    WatchlistTriggerWorkflowInput childInput =
        new WatchlistTriggerWorkflowInput(payload, config, sizeMultiplier);

    Async.function(child::run, childInput);
    // Block only until the child is actually started (so its id exists for the EOD cancel) — NOT
    // until it completes; the session must own its own EOD timer independently of the children.
    Workflow.getWorkflowExecution(child).get();
  }

  /**
   * Arms the EOD timer ({@code MarketCalendarActivities.durationUntilEodEt}); when it fires,
   * signals {@code cancel} to every started child. Each cancel is wrapped so an already-terminal
   * child (one that fired/skipped/cancelled on its own) cannot crash the parent — mirrors the
   * STC-running guard in {@code CopytradeSignalWorkflowImpl}, which narrowly catches {@link
   * SignalExternalWorkflowException} / {@link ApplicationFailure} around a single external signal.
   */
  private boolean awaitEodAndCancel(WatchlistMirrorPayload source, List<String> childIds) {
    Duration eodIn = calendar.durationUntilEodEt();
    if (eodIn == null || eodIn.isZero() || eodIn.isNegative()) {
      // Already at/after close: cancel immediately.
      cancelChildren(source, childIds);
      return true;
    }
    Workflow.newTimer(eodIn).get();
    cancelChildren(source, childIds);
    return true;
  }

  private void cancelChildren(WatchlistMirrorPayload source, List<String> childIds) {
    int cancelled = 0;
    int alreadyTerminal = 0;
    for (String childId : childIds) {
      ExternalWorkflowStub stub = Workflow.newUntypedExternalWorkflowStub(childId);
      try {
        stub.signal("cancel");
        cancelled++;
      } catch (SignalExternalWorkflowException | ApplicationFailure e) {
        // Child already completed (fired/skipped/cancelled) before the EOD sweep — tolerate it.
        alreadyTerminal++;
      }
    }
    logAudit(
        source,
        KIND_SESSION_EOD,
        subject(
            "children", childIds.size(),
            "cancelled", cancelled,
            "already_terminal", alreadyTerminal));
  }

  /** Child id: {@code t-{tenant}/s-{strategy}/wl/{et_date}/{ticker}/{C|P}}. */
  private static String childWorkflowId(WatchlistTriggerPayload p) {
    return WorkflowIds.tenantStrategy(p.getTenantId(), p.getStrategyId())
        + "/wl/"
        + p.getEtDate()
        + "/"
        + p.getTicker()
        + "/"
        + p.getRight().value();
  }

  /**
   * Account cash for the arm context, routed to {@code broker-<target>}. Fail-soft: a null
   * broker_target / result / cash yields {@code null} so the decider sees "no snapshot" rather than
   * a fabricated zero — the per-leg arm gate decides what to do with absent cash.
   */
  private BigDecimal dispatchAccountSnapshot(WatchlistMirrorPayload source, StrategyConfig config) {
    if (config.getBrokerTarget() == null) {
      return null;
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
    AccountSnapshotRequest request = new AccountSnapshotRequest();
    request.setSchemaVersion(1L);
    request.setBrokerTarget(
        AccountSnapshotRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    request.setTenantId(source.getTenantId());
    request.setCorrelationId(source.getSourceMessageId());
    AccountSnapshotResult result = accountStub.accountSnapshot(request);
    return result == null ? null : result.getCash();
  }

  private static String summary(int armed, int skipped, boolean eod) {
    return "armed=" + armed + ";skipped=" + skipped + ";eod=" + eod;
  }

  private void logAudit(WatchlistMirrorPayload source, String kind, Map<String, Object> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(source.getTenantId());
    event.setStrategyId(source.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:WatchlistTriggerSessionWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(source.getSourceMessageId());
    audit.log(event);
  }

  private static Map<String, Object> subject(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
