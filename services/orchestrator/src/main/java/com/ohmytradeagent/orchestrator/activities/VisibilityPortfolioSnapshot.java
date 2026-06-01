package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #318: Temporal Advanced Visibility–backed {@link PortfolioSnapshot}. Lists running {@code
 * PositionWorkflow} instances for a {@code (tenant, strategy)} scope and values each open position
 * so the {@code same_underlying_count} and {@code notional_cap_pct_of_equity} portfolio gates in
 * {@link RiskActivitiesImpl} observe the real open book (the prior no-op default always reported an
 * empty list, so both gates saw zero positions).
 *
 * <p><b>Visibility query.</b> Filters on the {@code TenantStrategy} custom Search Attribute plus
 * {@code WorkflowType='PositionWorkflow' AND ExecutionStatus='Running'} — never a {@code
 * WorkflowId} prefix (Temporal SQL Visibility has no {@code STARTS_WITH} on {@code WorkflowId};
 * {@code docs/plans/PLAN.md:120-127}). Same SA-filtered shape used by {@link
 * VisibilityPositionCounter} and {@link KillSwitchCascadeActivitiesImpl}; the returned metadata
 * stream is closed via try-with-resources. Isolation is structural: the {@code TenantStrategy} SA
 * scopes the result set, so one {@code (tenant, strategy)}'s positions never leak into another's
 * snapshot.
 *
 * <p><b>{@code openNotional} source — cost basis.</b> Per running {@code PositionWorkflow}, the
 * {@code positionState()} query supplies remaining qty + per-contract entry premium, and notional
 * is {@code openNotional = entryPremium × remainingQty × CONTRACT_MULTIPLIER (100)}. This is the
 * same cost-basis notional the sizing path uses ({@code RiskActivitiesImpl.entryNotional = price ×
 * contracts × Sizing.CONTRACT_MULTIPLIER}, {@code RiskActivitiesImpl.java:409-411}) — entry
 * premium, not live mark. {@code underlyingTicker} is derived from the OCC {@code contractSymbol}
 * via {@link OccSymbol#underlying(String)} (root → underlying).
 *
 * <p><b>MTM-circularity semantics.</b> {@code sum_open_notional} (this numerator) is <b>cost
 * basis</b>, while the cap denominator {@link #accountEquity(String)} equity is <b>net-liq
 * (MTM)</b> — which already includes the unrealized MTM of the same open option longs. Net effect:
 * the {@code notional_cap_pct_of_equity} cap <i>loosens</i> on an appreciating long-options book
 * and <i>tightens</i> on a bleeding one (defensibly — shrink exposure as the book bleeds).
 * Coordinating the account-level vs per-strategy {@code open_notional} basis is tracked in
 * follow-up #323 and is out of scope here.
 *
 * <p>Registered as the {@code @Bean PortfolioSnapshot} in {@link
 * com.ohmytradeagent.orchestrator.config.RiskCollaboratorsConfig}, overriding the
 * {@code @ConditionalOnMissingBean} no-op default there.
 */
public class VisibilityPortfolioSnapshot implements PortfolioSnapshot {

  private static final Logger log = LoggerFactory.getLogger(VisibilityPortfolioSnapshot.class);

  private final WorkflowClient client;

  public VisibilityPortfolioSnapshot(WorkflowClient client) {
    this.client = client;
  }

  @Override
  public List<OpenPosition> openPositions(String tenantId, String strategyId) {
    String tenantStrategy = WorkflowIds.tenantStrategy(tenantId, strategyId);
    String query =
        "WorkflowType='PositionWorkflow' AND TenantStrategy='"
            + WorkflowIds.escapeForVisibilityQuery(tenantStrategy)
            + "' AND ExecutionStatus='Running'";

    List<OpenPosition> positions = new ArrayList<>();
    try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
      var it = stream.iterator();
      while (it.hasNext()) {
        WorkflowExecutionMetadata md = it.next();
        String wfId = md.getExecution().getWorkflowId();
        OpenPosition pos = valuePosition(wfId, tenantId, strategyId);
        if (pos != null) {
          positions.add(pos);
        }
      }
    }
    return positions;
  }

  /**
   * Query one running {@code PositionWorkflow} for its open state and turn it into an {@link
   * OpenPosition}. Best-effort: a workflow that just closed (or whose query races termination) is
   * skipped rather than failing the whole snapshot — the gate then sees one fewer position, which
   * is the safe direction for an opt-in cap.
   */
  private OpenPosition valuePosition(String wfId, String tenantId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      PositionState state = stub.query("positionState", PositionState.class);
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
              .multiply(Sizing.CONTRACT_MULTIPLIER);
      return new OpenPosition(OccSymbol.underlying(state.contractSymbol()), openNotional);
    } catch (RuntimeException e) {
      log.warn(
          "positionState query failed wf={} tenant={} strategy={} err={}",
          wfId,
          tenantId,
          strategyId,
          e.getMessage());
      return null;
    }
  }

  /**
   * Account equity is sourced over the {@code broker-<broker_target>} {@code
   * AccountSnapshotActivity} seam and threaded into the gate by the workflow (Issue #317); this
   * snapshot is only the fallback when the workflow supplies none. Returns the documented {@code
   * ZERO} sentinel so the {@code notional_cap_pct_of_equity} gate fails closed (cannot compute the
   * cap → reject), preserving the #317 fail-closed-on-zero-equity contract.
   */
  @Override
  public BigDecimal accountEquity(String brokerTarget) {
    return BigDecimal.ZERO;
  }
}
