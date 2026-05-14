package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.RiskBreachPayload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default impl: queries Temporal Visibility for Running workflows under {@code TenantStrategy} and
 * fans out the riskBreach signal. Failures on individual signals are best-effort (target may have
 * just closed) — overall Activity returns the success count.
 */
@Component
public class KillSwitchCascadeActivitiesImpl implements KillSwitchCascadeActivities {

  private static final Logger log = LoggerFactory.getLogger(KillSwitchCascadeActivitiesImpl.class);

  private final WorkflowClient client;
  private final Clock clock;

  public KillSwitchCascadeActivitiesImpl(WorkflowClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  @Override
  public long cascadeRiskBreach(
      String tenantId, String strategyId, String excludeWorkflowId, String reason, String actor) {
    String tenantStrategy = "t-" + tenantId + "/s-" + strategyId;
    String query = "TenantStrategy='" + tenantStrategy + "' AND ExecutionStatus='Running'";

    RiskBreachPayload payload = new RiskBreachPayload();
    payload.setSchemaVersion(1L);
    payload.setReason(reason);
    payload.setActor(actor);
    payload.setOccurredAt(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));

    long sent = 0L;
    try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
      var it = stream.iterator();
      while (it.hasNext()) {
        WorkflowExecutionMetadata md = it.next();
        String wfId = md.getExecution().getWorkflowId();
        if (wfId.equals(excludeWorkflowId)) {
          continue;
        }
        try {
          WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
          stub.signal("riskBreach", payload);
          sent++;
        } catch (RuntimeException e) {
          log.warn(
              "cascade signal failed wf={} tenant={} strategy={} err={}",
              wfId,
              tenantId,
              strategyId,
              e.getMessage());
        }
      }
    }
    log.info(
        "cascade complete tenant={} strategy={} sent={} reason={}",
        tenantId,
        strategyId,
        sent,
        reason);
    return sent;
  }
}
