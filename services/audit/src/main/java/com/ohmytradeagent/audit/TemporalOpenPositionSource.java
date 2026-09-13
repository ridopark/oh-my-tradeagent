package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import java.util.HashSet;
import java.util.List;
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

  /**
   * One snapshot of the running set per process. The CLI is a point-in-time batch check that exits,
   * and {@link CompletenessRunner} asks once per discovered pair — 12 pairs meant 12 cluster-wide
   * scans of every running workflow (#854 review). Caching also makes the run internally
   * consistent: every pair is judged against the SAME snapshot rather than a set that shifts
   * mid-run.
   *
   * <p>Deliberately NOT a server-side {@code TenantStrategy} search-attribute filter, which the
   * review also suggested: a PositionWorkflow missing that attribute would silently drop out of the
   * result, and a position wrongly believed closed becomes a fabricated MISSING_TERMINAL_CLOSE. The
   * client-side prefix filter cannot miss one.
   */
  private List<String> cachedWorkflowIds;

  public TemporalOpenPositionSource(WorkflowClient workflowClient) {
    this.workflowClient = workflowClient;
  }

  @Override
  public Set<String> openCorrelationIds(String tenantId, String strategyId) {
    String prefix = WorkflowIds.tenantStrategy(tenantId, strategyId) + "/pos/";
    Set<String> correlationIds = new HashSet<>();
    for (String workflowId : runningPositionWorkflowIds()) {
      if (!workflowId.startsWith(prefix)) {
        continue;
      }
      String correlationId = WorkflowIds.entrySignalIdFromPosition(workflowId);
      if (correlationId != null) {
        correlationIds.add(correlationId);
      }
    }
    log.info(
        "open-positions tenant={} strategy={} open={}",
        tenantId,
        strategyId,
        correlationIds.size());
    return correlationIds;
  }

  /**
   * Every Running {@code PositionWorkflow} id in the cluster, fetched once per process.
   *
   * <p>Package-private so tests can drive the prefix-matching and id-extraction logic without
   * mocking Temporal's visibility stream — that logic is where a regression would actually hide (a
   * changed {@code WorkflowIds.tenantStrategy} separator, say).
   */
  private List<String> runningPositionWorkflowIds() {
    if (cachedWorkflowIds == null) {
      cachedWorkflowIds = fetchRunningPositionWorkflowIds();
    }
    return cachedWorkflowIds;
  }

  /**
   * The raw visibility call, separated from the caching above so BOTH are testable: a test that
   * overrode the caching method could never observe whether the cache worked (it did not, first
   * time round — the cache sat inside the overridden method and the test caught it).
   */
  List<String> fetchRunningPositionWorkflowIds() {
    try {
      return workflowClient
          .listExecutions(LIST_QUERY)
          .map(m -> m.getExecution().getWorkflowId())
          .toList();
    } catch (RuntimeException e) {
      // Fail CLOSED and loudly. Swallowing this would hand back an empty set, which reads as
      // "nothing is open" and would convert every unclosed lifecycle into a MISSING_TERMINAL_CLOSE
      // divergence — a fabricated red day. A red day for "could not determine" is correct; a red
      // day
      // blamed on the ledger is not.
      log.error("AUDIT open-position-lookup-failed: err={}", e.toString());
      throw new IllegalStateException(
          "cannot determine open positions — refusing to score the window"
              + " (Temporal visibility unavailable)",
          e);
    }
  }
}
