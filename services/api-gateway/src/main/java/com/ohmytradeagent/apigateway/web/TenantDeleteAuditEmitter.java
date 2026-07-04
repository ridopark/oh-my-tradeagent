package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.AuditEmitRequest;
import com.ohmytradeagent.orchestrator.workflows.AuditEmitWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) audit surface. api-gateway has no direct
 * hash-chained {@code audit_log} writer, so it records the tenant-delete lifecycle events ({@code
 * TenantDeleteRequested} / {@code TenantDeleteBlocked} for P0–P3 / {@code TenantDeleteCompleted} /
 * {@code TenantDeleteStepFailed}) by starting the generic {@link AuditEmitWorkflow} on
 * orchestrator-core and awaiting its (void) result.
 *
 * <p>Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class TenantDeleteAuditEmitter {

  private static final Logger log = LoggerFactory.getLogger(TenantDeleteAuditEmitter.class);
  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkflowClient workflowClient;
  private final String taskQueue;

  public TenantDeleteAuditEmitter(
      WorkflowClient workflowClient,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}") String taskQueue) {
    this.workflowClient = workflowClient;
    this.taskQueue = taskQueue;
  }

  /**
   * Emits one audit event. Best-effort: an audit-emit failure is logged and swallowed so it never
   * masks the delete result the operator is waiting on (the append-only trail is a record, not a
   * gate).
   */
  public void emit(
      String kind,
      String tenant,
      String strategy,
      String actor,
      String correlationId,
      Map<String, Object> subject) {
    try {
      AuditEmitRequest request = new AuditEmitRequest();
      request.setKind(kind);
      request.setTenantId(tenant);
      request.setStrategyId(strategy);
      request.setActor(actor);
      request.setCorrelationId(correlationId);
      request.setSubject(subject);

      WorkflowOptions opts =
          WorkflowOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setWorkflowId(
                  WorkflowIds.auditEmit(correlationId, kind, UUID.randomUUID().toString()))
              .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
              .build();
      AuditEmitWorkflow stub = workflowClient.newWorkflowStub(AuditEmitWorkflow.class, opts);
      stub.emit(request);
    } catch (RuntimeException e) {
      log.error(
          "tenant-delete audit emit failed kind={} tenant={} correlationId={} cause={}",
          kind,
          tenant,
          correlationId,
          e.getClass().getName());
    }
  }
}
