package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AuditEmitWorkflow} impl (PLAN-2026-07-03, Phase 4). Fills the deterministic {@code
 * event_id} / {@code occurred_at} from {@code Workflow.*} and delegates the single append-only
 * write to the shared {@code AuditActivities.log} (registered on this orchestrator-core worker).
 * All IO is in the activity; the body reads no wall-clock / random directly, so replay is
 * deterministic.
 */
public class AuditEmitWorkflowImpl implements AuditEmitWorkflow {

  private final AuditActivities audit =
      Workflow.newActivityStub(
          AuditActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public void emit(AuditEmitRequest request) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(request.getSchemaVersion() <= 0 ? 1L : request.getSchemaVersion());
    e.setTenantId(request.getTenantId());
    e.setStrategyId(request.getStrategyId());
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(
        OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
    e.setKind(request.getKind());
    Map<String, Object> subject =
        request.getSubject() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(request.getSubject());
    e.setSubject(subject);
    e.setActor(request.getActor());
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(request.getCorrelationId());
    audit.log(e);
  }
}
