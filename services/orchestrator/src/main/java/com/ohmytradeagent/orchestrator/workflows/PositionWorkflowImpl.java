package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
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

  static final String EXEC_TASK_QUEUE_PAPER = CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_PAPER;

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
      Workflow.await(() -> !pendingExits.isEmpty() || eodFired || expiryFired || remainingQty == 0);
      if (eodFired || expiryFired || remainingQty == 0) {
        break;
      }
      PartialExitRequest req = pendingExits.poll();
      processOne(req);
    }

    if (eodFired || expiryFired) {
      flattenRemaining(eodFired ? "eod" : "expiry");
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
    }
    // On EOD/expiry pre-emption we leave exitInFlight/currentInFlightSignalId set so
    // flattenRemaining() can cancel the still-open broker order.
  }

  private void flattenRemaining(String reason) {
    String kindReq =
        "eod".equals(reason)
            ? KIND_EOD_FORCE_FLATTEN_REQUESTED
            : KIND_EXPIRY_FORCE_FLATTEN_REQUESTED;
    auditLog(
        kindReq,
        subject(
            "entry_signal_id", input.getEntrySignalId(),
            "contract_symbol", input.getContractSymbol(),
            "remaining_qty", remainingQty));

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
      String kindDone =
          "eod".equals(reason) ? KIND_EOD_FORCE_FLATTENED : KIND_EXPIRY_FORCE_FLATTENED;
      auditLog(
          kindDone,
          subject(
              "entry_signal_id", input.getEntrySignalId(),
              "contract_symbol", input.getContractSymbol(),
              "qty_flattened", flattened));
    } catch (RuntimeException e) {
      auditLog(
          KIND_EOD_FORCE_FLATTEN_FAILED,
          subject(
              "entry_signal_id", input.getEntrySignalId(),
              "contract_symbol", input.getContractSymbol(),
              "error", e.getMessage(),
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
