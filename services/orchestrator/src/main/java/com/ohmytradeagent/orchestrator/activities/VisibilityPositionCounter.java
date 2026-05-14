package com.ohmytradeagent.orchestrator.activities;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import org.springframework.stereotype.Component;

/**
 * Counts running PositionWorkflows via Temporal Advanced Visibility, filtering on the {@code
 * TenantStrategy} custom Search Attribute. Single source of truth used by risk gates and the Phase
 * 5 kill-switch fan-out.
 */
@Component
public class VisibilityPositionCounter implements PositionCounter {

  private final WorkflowClient workflowClient;

  public VisibilityPositionCounter(WorkflowClient workflowClient) {
    this.workflowClient = workflowClient;
  }

  @Override
  public long countOpen(String tenantId, String strategyId) {
    String query =
        String.format(
            "WorkflowType='PositionWorkflow' AND TenantStrategy='t-%s/s-%s' AND ExecutionStatus='%s'",
            tenantId, strategyId, WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING.name());
    CountWorkflowExecutionsRequest req =
        CountWorkflowExecutionsRequest.newBuilder()
            .setNamespace(workflowClient.getOptions().getNamespace())
            .setQuery(query)
            .build();
    CountWorkflowExecutionsResponse resp =
        workflowClient.getWorkflowServiceStubs().blockingStub().countWorkflowExecutions(req);
    return resp.getCount();
  }
}
