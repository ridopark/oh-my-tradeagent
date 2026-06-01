package com.ohmytradeagent.tdbff.positions;

// Positions fan-out + cost-basis notional COPIED FROM
// services/orchestrator/.../activities/VisibilityPortfolioSnapshot.java — keep in sync.
// Divergence: that snapshot feeds a risk gate and must fail CLOSED (throw) when too many positions
// fail to value, because an undercount loosens the notional cap. This is a READ-ONLY display with
// no cap to protect, so a per-workflow query race is simply skipped (best-effort) — never a throw.
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lists a tenant's open positions across all of its strategies. Per strategy it runs the proven
 * {@code TenantStrategy='t-<t>/s-<sid>'} equality Visibility query (Temporal SQL Visibility has no
 * {@code STARTS_WITH} on workflow id) for running {@code PositionWorkflow}s, then fans out a {@code
 * positionState} query per workflow to value it. Cost-basis notional = {@code entryPremium ×
 * remainingQty × 100} (US equity-options multiplier) — entry premium, NOT live mark (no market-data
 * wired).
 */
@Component
public class PositionsReader {

  private static final Logger log = LoggerFactory.getLogger(PositionsReader.class);
  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";
  private static final BigDecimal CONTRACT_MULTIPLIER = new BigDecimal("100");

  private final WorkflowClient client;
  private final TenantStrategyResolver strategyResolver;

  public PositionsReader(WorkflowClient client, TenantStrategyResolver strategyResolver) {
    this.client = client;
    this.strategyResolver = strategyResolver;
  }

  /** Open positions for the tenant, unioned across its strategies and deduped by workflow id. */
  public List<OpenPosition> openPositions(String tenantId) {
    List<OpenPosition> out = new ArrayList<>();
    Set<String> seenWorkflowIds = new LinkedHashSet<>();
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      String query =
          String.format(
              "WorkflowType='%s' AND TenantStrategy='%s' AND ExecutionStatus='Running'",
              POSITION_WORKFLOW_TYPE,
              WorkflowIds.escapeForVisibilityQuery(
                  WorkflowIds.tenantStrategy(tenantId, strategyId)));
      try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
        var it = stream.iterator();
        while (it.hasNext()) {
          String wfId = it.next().getExecution().getWorkflowId();
          if (!seenWorkflowIds.add(wfId)) {
            continue;
          }
          OpenPosition valued = valuePosition(wfId, strategyId);
          if (valued != null) {
            out.add(valued);
          }
        }
      }
    }
    return out;
  }

  /**
   * Queries one running {@code PositionWorkflow}'s {@code positionState} and values it.
   * Best-effort: a workflow that just closed (or whose query races termination) is skipped, not
   * fatal — a read-only view has no cap to keep fail-closed.
   */
  private OpenPosition valuePosition(String wfId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      // PositionState is an orchestrator type; query it untyped into a transport record so the BFF
      // does not depend on the orchestrator module. Fields: contractSymbol, remainingQty,
      // entryPremium.
      PositionStateView state = stub.query("positionState", PositionStateView.class);
      if (state == null
          || state.contractSymbol() == null
          || state.contractSymbol().isBlank()
          || state.remainingQty() <= 0
          || state.entryPremium() == null) {
        return null;
      }
      BigDecimal openNotional =
          state
              .entryPremium()
              .multiply(BigDecimal.valueOf(state.remainingQty()))
              .multiply(CONTRACT_MULTIPLIER);
      return new OpenPosition(
          wfId,
          strategyId,
          state.contractSymbol(),
          state.remainingQty(),
          state.entryPremium(),
          openNotional);
    } catch (RuntimeException e) {
      log.warn(
          "positionState query failed wf={} strategy={} err={}", wfId, strategyId, e.getMessage());
      return null;
    }
  }

  /** One valued open position. */
  public record OpenPosition(
      String workflowId,
      String strategyId,
      String contractSymbol,
      long remainingQty,
      BigDecimal entryPremium,
      BigDecimal openNotional) {}

  /**
   * Transport mirror of the orchestrator's {@code PositionState} query result so the BFF can
   * deserialize the {@code positionState} query without a compile dependency on the orchestrator
   * module. Field names must match {@code PositionState(contractSymbol, remainingQty,
   * entryPremium)}.
   */
  public record PositionStateView(
      String contractSymbol, long remainingQty, BigDecimal entryPremium) {}
}
