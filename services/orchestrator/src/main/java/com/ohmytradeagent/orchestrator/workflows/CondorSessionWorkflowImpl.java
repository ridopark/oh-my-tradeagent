package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.CondorExecActivity;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorEntryRequest;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorEntryResult;
import com.ohmytradeagent.contract.activities.CondorMarketActivity;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLegsResult;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.RichnessResult;
import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.CondorDayActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gated-condor daily session. Net-new workflow type, so it carries NO {@code Workflow.getVersion}
 * gates. Determinism mirrors {@link WatchlistTriggerSessionWorkflowImpl}: the body reads no wall
 * clock and no RNG except via {@code Workflow.*} helpers and Activity results; no signal handlers.
 *
 * <p>Flow: load the CURRENT strategy config → dark/disabled/non-paper → market closed (broker
 * trading calendar) → half-day / FOMC ({@link CondorDayActivities}; audits {@value
 * #KIND_EVENT_SKIP}) → sleep to {@code condor_entry_et} → {@code evaluateRichness} → audit {@value
 * #KIND_GATE_EVALUATED} EVERY evaluated day (the shadow log) → gated in: kill-switch halt check →
 * {@code resolveCondorLegs} → mid-walk entry → on a fill, start the child {@link
 * CondorHoldWorkflow}.
 */
public class CondorSessionWorkflowImpl implements CondorSessionWorkflow {

  /** The frozen rule's vehicle: cash-settled XSP. */
  static final String UNDERLYING = "XSP";

  /** One condor per gated day for the paper test (XSP: $0 exchange fee under 10 contracts). */
  static final long QTY = 1L;

  /** Alpaca's mleg limit-price increment. */
  static final BigDecimal TICK = new BigDecimal("0.01");

  /**
   * The richness gate prices today's straddle from the entry-minute's last 1-min BAR, which is
   * published shortly after the minute closes; evaluate this long after {@code condor_entry_et} so
   * the bar is there (the data used is still strictly before the entry minute).
   */
  static final Duration BAR_SETTLE_DELAY = Duration.ofSeconds(30);

  /** How far past {@code condor_entry_et} a session may still evaluate and enter. */
  static final Duration MAX_LATE_START = Duration.ofMinutes(5);

  /** The ladder sends no rung after this long; 10s rungs → a handful of ticks at most. */
  static final Duration WALK_WINDOW = Duration.ofMinutes(2);

  static final String KIND_EVENT_SKIP = "CondorEventSkip";
  static final String KIND_GATE_EVALUATED = "CondorGateEvaluated";
  static final String KIND_ENTRY_ABANDONED = "CondorEntryAbandoned";
  static final String KIND_ENTRY_HALTED = "CondorEntryHalted";
  static final String KIND_ENTRY_FILLED = "CondorEntryFilled";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final CondorDayActivities condorDay =
      Workflow.newActivityStub(CondorDayActivities.class, DEFAULT_OPTIONS);
  private final RiskActivities risk =
      Workflow.newActivityStub(
          RiskActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(15))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());
  // Richness walks ~60-120 trailing days of bars; leg resolution is one chain read. Both are
  // reads, so a bounded retry is safe.
  private final CondorMarketActivity market =
      Workflow.newActivityStub(
          CondorMarketActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofMinutes(3))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
              .build());

  private CondorSessionWorkflowInput input;

  @Override
  public String run(CondorSessionWorkflowInput in) {
    if (in.getSchemaVersion() > CondorSessionWorkflowInput.SCHEMA_VERSION) {
      throw ApplicationFailure.newNonRetryableFailure(
          "CondorSessionWorkflowInput schema_version "
              + in.getSchemaVersion()
              + " is newer than this build",
          "SchemaVersionUnsupported");
    }
    this.input = in;
    StrategyConfig config = strategy.get(in.getTenantId(), in.getStrategyId());

    if (Boolean.FALSE.equals(config.getEnabled())) {
      return skip("strategy_disabled");
    }
    if (config.getCondorEntryEt() == null
        || config.getCondorShortOffsetPct() == null
        || config.getCondorWingOffsetPct() == null
        || config.getRichnessGateLookbackDays() == null
        || config.getRichnessGateMinQuantile() == null) {
      return skip("dark_config");
    }
    String brokerTarget =
        config.getBrokerTarget() == null ? null : config.getBrokerTarget().value();
    if (brokerTarget == null || !brokerTarget.endsWith("-paper")) {
      return skip("non_paper_broker_target");
    }

    LocalDate today = calendar.todayEt();
    if (tradingCalendar(brokerTarget).tradingDays(today, today).isEmpty()) {
      return skip("market_closed");
    }
    // condor_skip_event_days: null/absent = true (skip); only an explicit false trades FOMC days.
    String eventReason =
        condorDay.eventSkipReason(today, !Boolean.FALSE.equals(config.getCondorSkipEventDays()));
    if (eventReason != null) {
      return skip(eventReason);
    }

    String entryEt = config.getCondorEntryEt();
    // A session started well past the entry minute (a delayed/caught-up schedule fire, a worker
    // outage) must not trade the frozen 14:00 rule at some later time of day.
    Duration untilLateCutoff =
        calendar.durationUntilEodCloseEt(LocalTime.parse(entryEt).plus(MAX_LATE_START));
    if (untilLateCutoff == null || untilLateCutoff.compareTo(Duration.ZERO) <= 0) {
      return skip("late_start");
    }
    Duration untilEntry =
        calendar.durationUntilEodCloseEt(LocalTime.parse(entryEt).plus(BAR_SETTLE_DELAY));
    if (untilEntry != null && untilEntry.compareTo(Duration.ZERO) > 0) {
      Workflow.sleep(untilEntry);
    }

    RichnessResult r =
        market.evaluateRichness(
            UNDERLYING, entryEt, config.getRichnessGateLookbackDays().intValue());
    double minQuantile = config.getRichnessGateMinQuantile().doubleValue();
    // The frozen rule is richness ≥ trailing median (r.gate()); min_quantile 0.5 is implied by it,
    // and a stricter configured quantile only narrows the gate further.
    boolean gate = r.gate() && r.trailingQuantile() != null && r.trailingQuantile() >= minQuantile;
    logAudit(
        KIND_GATE_EVALUATED,
        subject(
            "et_date", today.toString(),
            "richness", r.richness(),
            "trailing_median", r.trailingMedian(),
            "quantile", r.trailingQuantile(),
            "trailing_count", r.trailingCount(),
            "min_quantile", minQuantile,
            "gate", gate,
            "reason", r.reason()));
    if (!gate) {
      return "gated_out";
    }

    RiskDecision halt = risk.checkKillSwitchHalt(in.getTenantId(), in.getStrategyId());
    if (!halt.allowed()) {
      return abandon("kill_switch: " + halt.reason() + " " + halt.detail(), null);
    }

    // Config offsets are FRACTIONS of spot (0.0015 = 0.15%); the market-data activity takes
    // PERCENT (it divides by 100), so convert here — passing the fraction would put the shorts ATM.
    CondorLegsResult legs =
        market.resolveCondorLegs(
            UNDERLYING,
            config.getCondorShortOffsetPct().doubleValue() * 100,
            config.getCondorWingOffsetPct().doubleValue() * 100);
    if (legs.reason() != null || legs.legs() == null || legs.legs().size() != 4) {
      return abandon("legs_unresolved: " + legs.reason(), null);
    }

    String attemptId = today.toString();
    CondorEntryResult entry;
    try {
      entry =
          condorExec(brokerTarget)
              .enterCondor(
                  new CondorEntryRequest(
                      in.getTenantId(),
                      in.getStrategyId(),
                      brokerTarget,
                      attemptId,
                      QTY,
                      legs.legs(),
                      legs.netCreditMid(),
                      TICK,
                      Workflow.currentTimeMillis() + WALK_WINDOW.toMillis()));
    } catch (ActivityFailure e) {
      // Retries exhausted (or a non-retryable refusal): a rung may be live at the broker with
      // nothing watching it from here — page rather than fail silently into workflow history.
      logAudit(
          KIND_ENTRY_HALTED,
          subject("reason", "entry_activity_failed: " + e.getCause(), "legs", legOccs(legs)));
      return "halted";
    }

    switch (entry.outcome()) {
      case "FILLED", "PARTIAL" -> {
        String holdId = WorkflowIds.condorHold(in.getTenantId(), in.getStrategyId(), attemptId);
        startHold(
            holdId,
            new CondorHoldWorkflowInput(
                in.getTenantId(),
                in.getStrategyId(),
                brokerTarget,
                attemptId,
                attemptId,
                UNDERLYING,
                entry.filledQty(),
                entry.avgFillCredit(),
                legs.legs()));
        logAudit(
            KIND_ENTRY_FILLED,
            subject(
                "outcome", entry.outcome(),
                "credit", entry.avgFillCredit(),
                "model_credit", legs.netCreditMid(),
                "net_mid", entry.netMid(),
                "slippage_vs_mid", entry.slippageVsMid(),
                "filled_qty", entry.filledQty(),
                "rungs", entry.rungs(),
                "spot", legs.spot(),
                "legs", legOccs(legs),
                "hold_workflow_id", holdId));
        return "filled";
      }
      case "HALTED" -> {
        // The walk stopped with an order possibly live at the broker — a page, not fill-model
        // data. The FillPoller owns the SUBMITTED rung from here.
        logAudit(
            KIND_ENTRY_HALTED,
            subject("reason", entry.reason(), "rungs", entry.rungs(), "legs", legOccs(legs)));
        return "halted";
      }
      default -> {
        return abandon(entry.reason(), entry);
      }
    }
  }

  private void startHold(String holdId, CondorHoldWorkflowInput holdInput) {
    Map<String, Object> sa = new LinkedHashMap<>();
    sa.put(
        "TenantStrategy",
        WorkflowIds.tenantStrategy(holdInput.getTenantId(), holdInput.getStrategyId()));
    CondorHoldWorkflow child =
        Workflow.newChildWorkflowStub(
            CondorHoldWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(holdId)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                // The hold outlives this session by design (it runs to settlement).
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .setSearchAttributes(sa)
                .build());
    Async.function(child::run, holdInput);
    Workflow.getWorkflowExecution(child).get();
  }

  private String skip(String reason) {
    logAudit(KIND_EVENT_SKIP, subject("reason", reason));
    return "skip:" + reason;
  }

  private String abandon(String reason, CondorEntryResult entry) {
    logAudit(
        KIND_ENTRY_ABANDONED,
        subject(
            "reason", reason,
            "net_mid", entry == null ? null : entry.netMid(),
            "rungs", entry == null ? 0 : entry.rungs()));
    return "abandoned";
  }

  private static List<String> legOccs(CondorLegsResult legs) {
    return legs.legs().stream().map(CondorMarketActivity.CondorLeg::occSymbol).toList();
  }

  private static MarketCalendarActivity tradingCalendar(String brokerTarget) {
    return Workflow.newActivityStub(
        MarketCalendarActivity.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());
  }

  /**
   * The entry walk blocks for its whole ladder (10s rungs within {@link #WALK_WINDOW}); a retry
   * resumes the journaled rungs (#897 blocker 1), so a bounded retry is safe.
   */
  private static CondorExecActivity condorExec(String brokerTarget) {
    return Workflow.newActivityStub(
        CondorExecActivity.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
            .setStartToCloseTimeout(Duration.ofMinutes(4))
            .setScheduleToCloseTimeout(Duration.ofMinutes(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
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
    event.setActor("workflow:CondorSessionWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    audit.log(event);
  }

  static Map<String, Object> subject(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
