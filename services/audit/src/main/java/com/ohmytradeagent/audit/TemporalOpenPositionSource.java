package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.client.WorkflowClient;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link OpenPositionSource} backed by Temporal visibility: the Running {@code PositionWorkflow}
 * executions ARE the set of open positions.
 *
 * <p>The mapping needs no workflow query. A position workflow id is {@code
 * t-{tenant}/s-{strategy}/pos/{occ}/{entrySignalId}} and the {@code entrySignalId} is exactly the
 * {@code correlation_id} the lifecycle's audit events carry, so {@link
 * WorkflowIds#entrySignalIdFromPosition} recovers it from the id alone.
 */
@Component
public class TemporalOpenPositionSource implements OpenPositionSource {

  private static final Logger log = LoggerFactory.getLogger(TemporalOpenPositionSource.class);

  /** Read-only visibility listing; the same shape the premium-recovery sweep uses. */
  static final String LIST_QUERY =
      "WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'Running'";

  private final WorkflowClient workflowClient;

  public TemporalOpenPositionSource(WorkflowClient workflowClient) {
    this.workflowClient = workflowClient;
  }

  @Override
  public Set<String> openCorrelationIds(String tenantId, String strategyId) {
    String prefix = WorkflowIds.tenantStrategy(tenantId, strategyId) + "/pos/";
    Set<String> correlationIds = new HashSet<>();
    try {
      for (WorkflowExecutionInfo info :
          workflowClient
              .listExecutions(LIST_QUERY)
              .map(m -> m.getWorkflowExecutionInfo())
              .toList()) {
        String workflowId = info.getExecution().getWorkflowId();
        if (!workflowId.startsWith(prefix)) {
          continue;
        }
        String correlationId = WorkflowIds.entrySignalIdFromPosition(workflowId);
        if (correlationId != null) {
          correlationIds.add(correlationId);
        }
      }
    } catch (RuntimeException e) {
      // Fail CLOSED and loudly. Swallowing this would hand back an empty set, which reads as
      // "nothing is open" and would convert every unclosed lifecycle into a MISSING_TERMINAL_CLOSE
      // divergence — a fabricated red day. A red day for "could not determine" is correct; a red
      // day
      // blamed on the ledger is not.
      log.error(
          "AUDIT open-position-lookup-failed: tenant={} strategy={} err={}",
          tenantId,
          strategyId,
          e.toString());
      throw new IllegalStateException(
          "cannot determine open positions for "
              + tenantId
              + "/"
              + strategyId
              + " — refusing to score the window (Temporal visibility unavailable)",
          e);
    }
    log.info(
        "open-positions tenant={} strategy={} open={}",
        tenantId,
        strategyId,
        correlationIds.size());
    return correlationIds;
  }
}
