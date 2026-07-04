package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) P3 OPEN_WORKFLOWS guard. Reuses the {@link
 * PositionsController} Advanced-Visibility Search-Attribute query: a {@code (tenant, strategy)} has
 * an open position iff at least one {@code PositionWorkflow} execution is RUNNING for its {@code
 * TenantStrategy} SA. Deleting {@code strategy_config} under a running PositionWorkflow would
 * orphan it, so any RUNNING execution blocks the delete.
 *
 * <p>Read-only. Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class OpenPositionWorkflowChecker {

  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";

  private final WorkflowClient client;

  public OpenPositionWorkflowChecker(WorkflowClient client) {
    this.client = client;
  }

  /** True iff at least one RUNNING PositionWorkflow exists for the {@code (tenant, strategy)}. */
  public boolean hasOpen(String tenant, String strategy) {
    String query =
        String.format(
            "TenantStrategy = '%s' AND WorkflowType = '%s' AND ExecutionStatus = '%s'",
            WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenant, strategy)),
            POSITION_WORKFLOW_TYPE,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING.name());
    return client.listExecutions(query).findAny().isPresent();
  }
}
