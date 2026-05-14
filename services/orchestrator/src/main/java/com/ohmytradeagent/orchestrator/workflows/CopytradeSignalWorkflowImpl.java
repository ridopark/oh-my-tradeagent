package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

  /** Used when StrategyConfig.pending_ttl_paper_secs is null. */
  static final long DEFAULT_PENDING_TTL_PAPER_SECS = 90L;

  static final String EXEC_TASK_QUEUE_PAPER = "broker-tradier-paper";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, DEFAULT_OPTIONS);
  private final RiskActivities risk =
      Workflow.newActivityStub(RiskActivities.class, DEFAULT_OPTIONS);
  private final ContractActivities contract =
      Workflow.newActivityStub(ContractActivities.class, DEFAULT_OPTIONS);
  private final ExecActivities exec =
      Workflow.newActivityStub(
          ExecActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(EXEC_TASK_QUEUE_PAPER)
              .setStartToCloseTimeout(Duration.ofSeconds(15))
              .build());

  private volatile FillEvent fillEvent;

  @Override
  public void onFill(FillEvent event) {
    this.fillEvent = event;
  }

  @Override
  public String process(CopytradeSignalPayload payload) {
    audit.log(
        auditEvent(payload, KIND_SIGNAL_RECEIVED, Map.of("signal_id", payload.getSignalId())));

    StrategyConfig config = strategy.get(payload.getTenantId(), payload.getStrategyId());

    RiskDecision decision = risk.checkEntry(payload, config);
    if (!decision.allowed()) {
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("signal_id", payload.getSignalId());
      subject.put("reason_code", decision.reason().name());
      if (decision.detail() != null) {
        subject.put("reason_detail", decision.detail());
      }
      subject.put("outcome", "REJECTED");
      audit.log(auditEvent(payload, KIND_SIGNAL_REJECTED, subject));
      return payload.getSignalId();
    }

    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));

    BigDecimal capital =
        strategy.capitalForStrategy(payload.getTenantId(), payload.getStrategyId());
    long contracts = Sizing.computeContracts(payload, config, capital);

    Map<String, Object> acceptedSubject = new LinkedHashMap<>();
    acceptedSubject.put("signal_id", payload.getSignalId());
    acceptedSubject.put("option_symbol", resolved.optionSymbol());
    acceptedSubject.put("contracts", contracts);
    acceptedSubject.put("ref_premium", payload.getPrice());
    audit.log(auditEvent(payload, KIND_SIGNAL_ACCEPTED, acceptedSubject));

    String intentKey = Workflow.getInfo().getWorkflowId() + ":entry";
    OrderIntent intent = newIntent(payload, config, resolved, contracts, intentKey);
    OrderIntentResult placed = exec.placeOrder(intent);

    Map<String, Object> submittedSubject = new LinkedHashMap<>();
    submittedSubject.put("intent_key", placed.getIntentKey());
    submittedSubject.put("broker_order_id", placed.getBrokerOrderId());
    submittedSubject.put("option_symbol", resolved.optionSymbol());
    submittedSubject.put("side", "BUY");
    submittedSubject.put("qty", contracts);
    submittedSubject.put("broker_target", config.getBrokerTarget().value());
    audit.log(auditEvent(payload, KIND_ORDER_SUBMITTED, submittedSubject));

    long ttlSecs = pendingTtlSecs(config);
    boolean filled = Workflow.await(Duration.ofSeconds(ttlSecs), () -> fillEvent != null);

    if (filled) {
      Map<String, Object> filledSubject = new LinkedHashMap<>();
      filledSubject.put("signal_id", payload.getSignalId());
      filledSubject.put("intent_key", placed.getIntentKey());
      filledSubject.put("broker_order_id", fillEvent.brokerOrderId());
      filledSubject.put("filled_qty", fillEvent.filledQty());
      filledSubject.put("avg_fill_price", fillEvent.avgFillPrice());
      filledSubject.put("outcome", "FILLED");
      audit.log(auditEvent(payload, KIND_ENTRY_FILLED, filledSubject));
      // Phase 3 hooks PositionWorkflow start here.
      return payload.getSignalId();
    }

    // TTL expired — try to cancel the broker order.
    Map<String, Object> cancelReqSubject = new LinkedHashMap<>();
    cancelReqSubject.put("intent_key", placed.getIntentKey());
    cancelReqSubject.put("broker_order_id", placed.getBrokerOrderId());
    cancelReqSubject.put("reason", "ttl_expired");
    audit.log(auditEvent(payload, KIND_ORDER_CANCEL_REQUESTED, cancelReqSubject));

    OrderIntentResult cancelResult = exec.cancelOrder(intentKey);
    if (cancelResult.getState() == OrderIntentResult.State.CANCELLED) {
      Map<String, Object> cancelledSubject = new LinkedHashMap<>();
      cancelledSubject.put("intent_key", placed.getIntentKey());
      cancelledSubject.put("broker_order_id", placed.getBrokerOrderId());
      cancelledSubject.put("reason", "ttl_expired");
      audit.log(auditEvent(payload, KIND_ORDER_CANCELLED, cancelledSubject));
    } else {
      Map<String, Object> cancelFailedSubject = new LinkedHashMap<>();
      cancelFailedSubject.put("intent_key", placed.getIntentKey());
      cancelFailedSubject.put("broker_order_id", placed.getBrokerOrderId());
      cancelFailedSubject.put("broker_reason", cancelResult.getLastError());
      cancelFailedSubject.put("severity", "ERROR");
      cancelFailedSubject.put("note", "orphan_position_until_phase_3");
      audit.log(auditEvent(payload, KIND_ORDER_CANCEL_FAILED, cancelFailedSubject));
    }

    Map<String, Object> expiredSubject = new LinkedHashMap<>();
    expiredSubject.put("signal_id", payload.getSignalId());
    expiredSubject.put("intent_key", placed.getIntentKey());
    expiredSubject.put("broker_order_id", placed.getBrokerOrderId());
    expiredSubject.put("ttl_secs", ttlSecs);
    expiredSubject.put("outcome", "EXPIRED");
    audit.log(auditEvent(payload, KIND_ENTRY_EXPIRED, expiredSubject));

    return payload.getSignalId();
  }

  private long pendingTtlSecs(StrategyConfig config) {
    Long configured = config.getPendingTtlPaperSecs();
    return configured != null ? configured : DEFAULT_PENDING_TTL_PAPER_SECS;
  }

  private OrderIntent newIntent(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      long contracts,
      String intentKey) {
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
    i.setLimitPrice(payload.getPrice());
    i.setRecordedAt(OffsetDateTime.now());
    return i;
  }

  private AuditEvent auditEvent(
      CopytradeSignalPayload payload, String kind, Map<String, ?> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(payload.getTenantId());
    event.setStrategyId(payload.getStrategyId());
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(OffsetDateTime.now());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:CopytradeSignalWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(payload.getSignalId());
    return event;
  }
}
