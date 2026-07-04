package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) disarm-first step: forces {@code enabled=false}
 * on a strategy through the SAME {@code StrategyConfigUpdateWorkflow} CAS path the operator enable
 * route uses (in reverse). Idempotent: an absent row or an already-disabled strategy is a no-op (P2
 * already requires all strategies disabled, so in the happy path this only re-asserts the invariant
 * — the disarm-first belt-and-suspenders at execution time closes the P2 TOCTOU).
 *
 * <p>Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class StrategyDisableClient {

  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkflowClient workflowClient;
  private final StrategyConfigReader reader;
  private final String taskQueue;

  public StrategyDisableClient(
      WorkflowClient workflowClient,
      StrategyConfigReader reader,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}") String taskQueue) {
    this.workflowClient = workflowClient;
    this.reader = reader;
    this.taskQueue = taskQueue;
  }

  /** Flip {@code enabled=false} for the strategy (no-op if absent or already disabled). */
  public void disable(String tenant, String strategy, String actor, String correlationId) {
    Optional<StrategyConfigReader.Stored> storedOpt = reader.read(tenant, strategy);
    if (storedOpt.isEmpty()) {
      return; // Nothing to disarm (row already gone) — idempotent.
    }
    StrategyConfigReader.Stored stored = storedOpt.get();
    StrategyConfig config = stored.config();
    if (!Boolean.TRUE.equals(config.getEnabled())) {
      return; // Already disabled — idempotent no-op.
    }
    config.setEnabled(false);

    StrategyConfigUpdateRequest request = new StrategyConfigUpdateRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setStrategyId(strategy);
    request.setConfig(config);
    request.setExpectedVersion(stored.version());
    request.setActor(actor);
    request.setCorrelationId(correlationId);

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowId(WorkflowIds.strategyConfigUpdate(tenant, strategy, correlationId))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
            .build();
    StrategyConfigUpdateWorkflow stub =
        workflowClient.newWorkflowStub(StrategyConfigUpdateWorkflow.class, opts);
    stub.update(request);
  }
}
