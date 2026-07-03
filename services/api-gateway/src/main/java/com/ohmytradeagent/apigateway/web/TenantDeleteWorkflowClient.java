package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.TenantDeleteResult;
import com.ohmytradeagent.orchestrator.workflows.TenantDeleteWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4): starts and awaits the orchestrator {@link
 * TenantDeleteWorkflow} (which runs the P4/P5 live-safety gates then the teardown) for ONE {@code
 * (tenant, strategy)}, returning its {@link TenantDeleteResult} synchronously. A bounded
 * server-side run-timeout keeps the blocking stub from pinning the request thread if
 * orchestrator-core has no live poller.
 *
 * <p>Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class TenantDeleteWorkflowClient {

  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(60);

  private final WorkflowClient workflowClient;
  private final String taskQueue;

  public TenantDeleteWorkflowClient(
      WorkflowClient workflowClient,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}") String taskQueue) {
    this.workflowClient = workflowClient;
    this.taskQueue = taskQueue;
  }

  /**
   * Runs the teardown workflow for one {@code (tenant, strategy)}. {@code brokerTarget} pins the
   * P4/P5 broker task queue; {@code actor} threads the audit tombstone; {@code correlationId} keys
   * the workflow id (a fresh id per request keeps re-runs from colliding).
   */
  public TenantDeleteResult deleteTenant(
      String tenant, String strategy, String brokerTarget, String actor, String correlationId) {
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowId(
                WorkflowIds.tenantStrategy(tenant, strategy) + "/tenant-delete/" + correlationId)
            .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
            .build();
    TenantDeleteWorkflow stub = workflowClient.newWorkflowStub(TenantDeleteWorkflow.class, opts);
    return stub.deleteTenant(tenant, strategy, brokerTarget, actor);
  }
}
