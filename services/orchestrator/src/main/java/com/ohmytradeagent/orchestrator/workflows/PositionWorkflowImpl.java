package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Long-running position lifecycle. Receives STC dispatches via {@link
 * #partialExit(PartialExitRequest)}, fills via {@link #onFill(FillEvent)}, and force-flattens on
 * EOD (15:55 ET) or expiry close (15:30 ET for 0DTE). Deterministic by construction — all time
 * reads go through {@link MarketCalendarActivities} or {@link Workflow}.
 *
 * <p>Phase 4 adds CHANDELIER_TRAIL: the CopytradeSignalWorkflow's STC branch may signal {@link
 * #armChandelier(ArmChandelierPayload)} after the partial exit, which subscribes a premium stream
 * via market-data-svc. Each {@link #chandelierTick(PremiumTick)} updates the peak and fires a
 * flatten when the tick falls to or below {@code peak * (1 - giveback_pct)}.
 */
public class PositionWorkflowImpl implements PositionWorkflow {

  // Audit kinds
  private static final String KIND_POSITION_ENTERED = "PositionEntered";
  private static final String KIND_PARTIAL_EXIT_REQUESTED = "PartialExitRequested";
  private static final String KIND_PARTIAL_EXIT_FILLED = "PartialExitFilled";
  private static final String KIND_EXIT_DUPLICATE_SUPPRESSED = "ExitDuplicateSuppressed";
  private static final String KIND_EXIT_QUEUED = "ExitQueued";
  private static final String KIND_EOD_FORCE_FLATTEN_REQUESTED = "EodForceFlattenRequested";
  private static final String KIND_EOD_FORCE_FLATTENED = "EodForceFlattened";
  private static final String KIND_EOD_FORCE_FLATTEN_FAILED = "EodForceFlattenFailed";
  private static final String KIND_EXPIRY_FORCE_FLATTEN_REQUESTED = "ExpiryForceFlattenRequested";
  private static final String KIND_EXPIRY_FORCE_FLATTENED = "ExpiryForceFlattened";
  private static final String KIND_POSITION_CLOSED = "PositionClosed";

  // Phase 4 audit kinds
  private static final String KIND_CHANDELIER_ARMED = "ChandelierArmed";
  private static final String KIND_CHANDELIER_TRAIL_FIRED = "ChandelierTrailFired";
  private static final String KIND_CHANDELIER_ARM_REJECTED = "ChandelierArmRejected";
  private static final String KIND_CHANDELIER_SUBSCRIPTION_FAILED = "ChandelierSubscriptionFailed";
  private static final String KIND_CHANDELIER_UNARMED_BY_EXIT = "ChandelierUnarmedByExit";

  private static final String VERSION_CHANDELIER = "chandelier-v1";

  private static final BigDecimal MAX_GIVEBACK = new BigDecimal("0.5");

  static final String EXEC_TASK_QUEUE_PAPER = CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_PAPER;
  static final String MARKET_DATA_TASK_QUEUE = "market-data";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final ExecActivities exec =
      Workflow.newActivityStub(
          ExecActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(EXEC_TASK_QUEUE_PAPER)
              .setStartToCloseTimeout(Duration.ofSeconds(15))
              .build());
  private final SubscribePremiumActivity marketData =
      Workflow.newActivityStub(
          SubscribePremiumActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .build());

  private PositionWorkflowInput input;
  private long remainingQty;
  private final LinkedHashSet<String> processedSignalIds = new LinkedHashSet<>();
  private boolean exitInFlight;
  private final ArrayDeque<PartialExitRequest> pendingExits = new ArrayDeque<>();
  private FillEvent lastFillEvent;
  private String currentInFlightBrokerOrderId;
  private String currentInFlightSignalId;
  private boolean eodFired;
  private boolean expiryFired;

  // Phase 4: chandelier-trail state
  private boolean trailingArmed;
  private BigDecimal peakPremium;
  private BigDecimal givebackPct;
  private long ticksReceived;
  private BigDecimal lastTickPremium;
  private OffsetDateTime lastTickAt;

  /**
   * Buffered arm payloads. Signal handlers only enqueue (no activity calls); the main loop drains
   * and executes the subscribe activity. Keeps signal-processing deterministic and avoids two
   * concurrent arm signals racing through {@code marketData.subscribePremium}.
   */
  private final ArrayDeque<ArmChandelierPayload> pendingArms = new ArrayDeque<>();

  /**
   * Buffered ticks. Drained by the main loop AFTER arm processing so the arm vs tick race ("arm and
   * tick signals arrive in the same workflow task") never drops a fire-worthy tick.
   */
  private final ArrayDeque<PremiumTick> pendingTicks = new ArrayDeque<>();

  /** True once a tick crosses the threshold; main loop fires the flatten. */
  private boolean chandelierFireRequested;

  /** Tick that triggered the fire — recorded so the audit subject carries trigger_premium. */
  private PremiumTick fireTriggerTick;

  /** Threshold at fire time — recorded for the audit subject. */
  private BigDecimal fireThreshold;

  /** Set when the workflow's own close logic flattens. Drives the un-armed-by-exit audit. */
  private String closeReason;

  @Override
  public String run(PositionWorkflowInput in) {
    this.input = in;
    this.remainingQty = in.getQty();

    auditLog(
        KIND_POSITION_ENTERED,
        subject(
            "entry_signal_id", in.getEntrySignalId(),
            "contract_symbol", in.getContractSymbol(),
            "qty", in.getQty(),
            "entry_premium", in.getEntryPremium()));

    Duration eodIn = calendar.durationUntilEodEt();
    Duration expiryIn = Duration.ZERO;
    LocalDate expiryDate = expiryDateFromOcc(in.getContractSymbol());
    if (expiryDate != null) {
      expiryIn = calendar.durationUntilExpiryCloseEt(expiryDate);
    }

    if (!eodIn.isZero() && !eodIn.isNegative()) {
      Promise<Void> eodTimer = Workflow.newTimer(eodIn);
      eodTimer.thenApply(
          v -> {
            eodFired = true;
            return null;
          });
    }
    if (!expiryIn.isZero() && !expiryIn.isNegative()) {
      Promise<Void> expiryTimer = Workflow.newTimer(expiryIn);
      expiryTimer.thenApply(
          v -> {
            expiryFired = true;
            return null;
          });
    }

    while (remainingQty > 0 && !eodFired && !expiryFired) {
      Workflow.await(
          () ->
              !pendingExits.isEmpty()
                  || !pendingArms.isEmpty()
                  || !pendingTicks.isEmpty()
                  || chandelierFireRequested
                  || eodFired
                  || expiryFired
                  || remainingQty == 0);
      if (eodFired || expiryFired || remainingQty == 0) {
        break;
      }
      // Drain arms first so a co-arriving tick sees armed=true.
      while (!pendingArms.isEmpty()) {
        processArm(pendingArms.poll());
      }
      // Then drain ticks; processTick latches chandelierFireRequested on threshold breach.
      while (!pendingTicks.isEmpty()) {
        processTick(pendingTicks.poll());
      }
      if (chandelierFireRequested) {
        chandelierFireRequested = false;
        fireChandelier();
        // flattenRemaining sets remainingQty=0 -> next iteration exits the loop.
        continue;
      }
      if (!pendingExits.isEmpty()) {
        PartialExitRequest req = pendingExits.poll();
        processOne(req);
      }
    }

    if (eodFired || expiryFired) {
      flattenRemaining(eodFired ? "eod" : "expiry");
    }

    // Phase 4: if the position closed via a non-chandelier path while the trail was armed, audit
    // that the trail was torn down by the exit.
    if (trailingArmed) {
      String reason = closeReason != null ? closeReason : "normal_stc";
      if (!"chandelier_trail".equals(reason)) {
        auditLog(KIND_CHANDELIER_UNARMED_BY_EXIT, subject("reason", reason));
      }
    }

    auditLog(
        KIND_POSITION_CLOSED,
        subject(
            "entry_signal_id", input.getEntrySignalId(),
            "contract_symbol", input.getContractSymbol(),
            "remaining_qty", remainingQty));

    return Workflow.getInfo().getWorkflowId();
  }

  @Override
  public void partialExit(PartialExitRequest req) {
    // Phase 3 latent-bug fix: if the position is already drained, surface a clear audit instead of
    // recording a "duplicate_signal_id" muddle.
    if (remainingQty <= 0) {
      auditLog(
          KIND_EXIT_DUPLICATE_SUPPRESSED,
          subject("signal_id", req.getSignalId(), "note", "position_already_drained"));
      return;
    }
    if (!processedSignalIds.add(req.getSignalId())) {
      auditLog(
          KIND_EXIT_DUPLICATE_SUPPRESSED,
          subject("signal_id", req.getSignalId(), "note", "duplicate_signal_id"));
      return;
    }
    double fraction = req.getFraction() == null ? 0.0 : req.getFraction().doubleValue();
    if (fraction <= 0.0 || fraction > 1.0) {
      auditLog(
          KIND_EXIT_DUPLICATE_SUPPRESSED,
          subject(
              "signal_id", req.getSignalId(),
              "note", "bad_fraction",
              "fraction", req.getFraction()));
      return;
    }
    boolean wasBusy = exitInFlight || !pendingExits.isEmpty();
    pendingExits.add(req);
    if (wasBusy) {
      auditLog(
          KIND_EXIT_QUEUED,
          subject(
              "signal_id", req.getSignalId(),
              "queue_depth", pendingExits.size()));
    }
  }

  @Override
  public void onFill(FillEvent event) {
    this.lastFillEvent = event;
  }

  @Override
  public void armChandelier(ArmChandelierPayload p) {
    int v = Workflow.getVersion(VERSION_CHANDELIER, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // Buffer only — main loop performs validation and the subscribe activity to keep signal
    // handlers free of activity calls (deterministic-by-default pattern, matches partialExit).
    pendingArms.add(p);
  }

  @Override
  public void chandelierTick(PremiumTick tick) {
    int v = Workflow.getVersion(VERSION_CHANDELIER, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // Buffer only — main loop drains AFTER arms so a co-arriving arm+tick pair fires correctly.
    pendingTicks.add(tick);
  }

  /** Main-loop tick processor: drops ticks while unarmed, ratchets the peak, latches on breach. */
  private void processTick(PremiumTick tick) {
    if (!trailingArmed) {
      return;
    }
    ticksReceived++;
    lastTickPremium = tick.getPremium();
    lastTickAt = tick.getRetrievedAt();

    if (tick.getPremium().compareTo(peakPremium) > 0) {
      peakPremium = tick.getPremium();
    }
    BigDecimal threshold = peakPremium.multiply(BigDecimal.ONE.subtract(givebackPct));
    if (tick.getPremium().compareTo(threshold) <= 0 && !chandelierFireRequested) {
      chandelierFireRequested = true;
      fireTriggerTick = tick;
      fireThreshold = threshold;
    }
  }

  /** Main-loop arm processor: validates, calls the subscribe activity, mutates state. */
  private void processArm(ArmChandelierPayload p) {
    if (trailingArmed) {
      // Idempotent — second arm is a silent no-op (no audit, KISS).
      return;
    }
    BigDecimal peak = p.getPeakPremium();
    BigDecimal gb = p.getGivebackPct();
    if (peak == null || peak.signum() <= 0) {
      auditLog(
          KIND_CHANDELIER_ARM_REJECTED,
          subject(
              "reason",
              "invalid_peak",
              "source_signal_id",
              p.getSourceSignalId(),
              "peak_premium",
              peak));
      return;
    }
    if (gb == null || gb.signum() <= 0 || gb.compareTo(MAX_GIVEBACK) > 0) {
      auditLog(
          KIND_CHANDELIER_ARM_REJECTED,
          subject(
              "reason",
              "invalid_giveback",
              "source_signal_id",
              p.getSourceSignalId(),
              "giveback_pct",
              gb));
      return;
    }

    SubscribePremiumRequest req = new SubscribePremiumRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(input.getTenantId());
    req.setStrategyId(input.getStrategyId());
    req.setContractSymbol(input.getContractSymbol());
    req.setPositionWorkflowId(Workflow.getInfo().getWorkflowId());

    SubscribePremiumResult res = marketData.subscribePremium(req);
    if (res.getStatus() == SubscribePremiumResult.Status.FAILED) {
      auditLog(
          KIND_CHANDELIER_SUBSCRIPTION_FAILED,
          subject(
              "source_signal_id", p.getSourceSignalId(),
              "error", res.getError()));
      return;
    }

    trailingArmed = true;
    peakPremium = peak;
    givebackPct = gb;
    auditLog(
        KIND_CHANDELIER_ARMED,
        subject(
            "source_signal_id",
            p.getSourceSignalId(),
            "peak_premium",
            peak,
            "giveback_pct",
            gb,
            "subscription_id",
            res.getSubscriptionId()));
  }

  /** Main-loop chandelier fire handler. Emits the audit then flattens the remaining quantity. */
  private void fireChandelier() {
    auditLog(
        KIND_CHANDELIER_TRAIL_FIRED,
        subject(
            "peak_premium", peakPremium,
            "trigger_premium", fireTriggerTick.getPremium(),
            "threshold", fireThreshold,
            "giveback_pct", givebackPct,
            "remaining_qty", remainingQty));
    closeReason = "chandelier_trail";
    flattenRemaining("chandelier_trail");
  }

  @Override
  public TrailingState trailingState() {
    BigDecimal threshold =
        (trailingArmed && peakPremium != null && givebackPct != null)
            ? peakPremium.multiply(BigDecimal.ONE.subtract(givebackPct))
            : null;
    return new TrailingState(
        trailingArmed,
        peakPremium,
        givebackPct,
        threshold,
        lastTickPremium,
        lastTickAt,
        ticksReceived);
  }

  private void processOne(PartialExitRequest req) {
    long qtyToClose =
        Math.min(remainingQty, (long) Math.ceil(remainingQty * req.getFraction().doubleValue()));
    auditLog(
        KIND_PARTIAL_EXIT_REQUESTED,
        subject(
            "signal_id",
            req.getSignalId(),
            "qty_to_close",
            qtyToClose,
            "remaining_qty_before",
            remainingQty,
            "fraction",
            req.getFraction()));

    exitInFlight = true;
    currentInFlightSignalId = req.getSignalId();
    String intentKey = Workflow.getInfo().getWorkflowId() + ":exit:" + req.getSignalId();
    OrderIntent intent = exitIntent(req, qtyToClose, intentKey);
    lastFillEvent = null;
    OrderIntentResult placed = exec.placeOrder(intent);
    currentInFlightBrokerOrderId = placed.getBrokerOrderId();

    Workflow.await(() -> lastFillEvent != null || eodFired || expiryFired);

    if (lastFillEvent != null) {
      long filled = lastFillEvent.filledQty();
      remainingQty -= filled;
      auditLog(
          KIND_PARTIAL_EXIT_FILLED,
          subject(
              "signal_id",
              req.getSignalId(),
              "qty_filled",
              filled,
              "remaining_qty_after",
              remainingQty,
              "broker_order_id",
              lastFillEvent.brokerOrderId()));
      exitInFlight = false;
      currentInFlightBrokerOrderId = null;
      currentInFlightSignalId = null;
      if (remainingQty == 0 && closeReason == null) {
        closeReason = "normal_stc";
      }
    }
    // On EOD/expiry pre-emption we leave exitInFlight/currentInFlightSignalId set so
    // flattenRemaining() can cancel the still-open broker order.
  }

  private void flattenRemaining(String reason) {
    String kindReq;
    String kindDone;
    if ("eod".equals(reason)) {
      kindReq = KIND_EOD_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EOD_FORCE_FLATTENED;
    } else if ("expiry".equals(reason)) {
      kindReq = KIND_EXPIRY_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EXPIRY_FORCE_FLATTENED;
    } else {
      // chandelier_trail or other Phase 4+ reasons: re-use the EOD audit kinds so downstream
      // dashboards see a single force-flatten pattern (the audit subject carries `reason` for
      // disambiguation via the existing ChandelierTrailFired event).
      kindReq = KIND_EOD_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EOD_FORCE_FLATTENED;
    }

    auditLog(
        kindReq,
        subject(
            "entry_signal_id",
            input.getEntrySignalId(),
            "contract_symbol",
            input.getContractSymbol(),
            "remaining_qty",
            remainingQty,
            "reason",
            reason));

    if (exitInFlight && currentInFlightSignalId != null) {
      String intentKey = Workflow.getInfo().getWorkflowId() + ":exit:" + currentInFlightSignalId;
      try {
        exec.cancelOrder(intentKey);
      } catch (RuntimeException ignored) {
        // Cancellation best-effort; reconciliation closes the loop.
      }
    }

    if (remainingQty == 0) {
      return;
    }

    String flattenIntentKey = Workflow.getInfo().getWorkflowId() + ":exit:flatten-" + reason;
    OrderIntent intent = flattenIntent(flattenIntentKey, reason);
    try {
      exec.placeOrder(intent);
      long flattened = remainingQty;
      remainingQty = 0;
      auditLog(
          kindDone,
          subject(
              "entry_signal_id",
              input.getEntrySignalId(),
              "contract_symbol",
              input.getContractSymbol(),
              "qty_flattened",
              flattened,
              "reason",
              reason));
    } catch (RuntimeException e) {
      auditLog(
          KIND_EOD_FORCE_FLATTEN_FAILED,
          subject(
              "entry_signal_id", input.getEntrySignalId(),
              "contract_symbol", input.getContractSymbol(),
              "error", e.getMessage(),
              "reason", reason,
              "note", "orphan_until_phase_5_reconcile"));
    }
  }

  private OrderIntent exitIntent(PartialExitRequest req, long qty, String intentKey) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(input.getTenantId());
    i.setStrategyId(input.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId(req.getSignalId());
    i.setOptionSymbol(input.getContractSymbol());
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(qty);
    i.setLimitPrice(req.getRefPremium());
    i.setRecordedAt(workflowNow());
    return i;
  }

  private OrderIntent flattenIntent(String intentKey, String reason) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(input.getTenantId());
    i.setStrategyId(input.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId("flatten-" + reason);
    i.setOptionSymbol(input.getContractSymbol());
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(remainingQty);
    i.setLimitPrice(null);
    i.setRecordedAt(workflowNow());
    return i;
  }

  private void auditLog(String kind, Map<String, Object> subject) {
    audit.log(auditEvent(kind, subject));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subject) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(input.getStrategyId());
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subject));
    e.setActor("workflow:PositionWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getEntrySignalId());
    return e;
  }

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

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }

  /**
   * Parses the OCC option symbol's 6-digit YYMMDD (chars 6..12 after the 6-char root) into a
   * LocalDate. Returns null on any parse failure — the workflow then arms no expiry timer.
   */
  static LocalDate expiryDateFromOcc(String occ) {
    if (occ == null || occ.length() < 15) {
      return null;
    }
    try {
      String yymmdd = occ.substring(6, 12);
      int yy = Integer.parseInt(yymmdd.substring(0, 2));
      int mm = Integer.parseInt(yymmdd.substring(2, 4));
      int dd = Integer.parseInt(yymmdd.substring(4, 6));
      return LocalDate.of(2000 + yy, mm, dd);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
