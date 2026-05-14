package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
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
      audit.log(auditEvent(payload, KIND_SIGNAL_REJECTED, subject));
      return payload.getSignalId();
    }

    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));

    BigDecimal capital =
        strategy.capitalForStrategy(payload.getTenantId(), payload.getStrategyId());
    long contracts = Sizing.computeContracts(payload, config, capital);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", payload.getSignalId());
    subject.put("option_symbol", resolved.optionSymbol());
    subject.put("contracts", contracts);
    subject.put("ref_premium", payload.getPrice());
    audit.log(auditEvent(payload, KIND_SIGNAL_ACCEPTED, subject));

    return payload.getSignalId();
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
