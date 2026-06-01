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

  /**
   * Task (c) fail-closed bound (#325). A correlated Temporal degradation can leave {@code
   * listExecutions} succeeding while the per-workflow {@code positionState} query fails for many
   * listed positions, which would drop those positions from {@code sum_open_notional} and silently
   * <i>loosen</i> the {@code notional_cap_pct_of_equity} cap (undercounting open exposure → permits
   * trades it should reject). To keep that failure mode fail-closed we throw instead of returning
   * an undercounted list once too large a fraction of the listed positions fail to <i>value</i>
   * (genuine value-failures only — legitimate just-closed/blank/null-premium skips do NOT count).
   *
   * <p>Rule: with {@code N} listed running positions and {@code F} value-failures, throw when
   * {@code F * 2 > N} (i.e. <b>strictly more than 50%</b> of listed positions failed to value).
   * This is a single rule that also covers the small-count case: 1 failure out of 1 listed ({@code
   * 2 > 1}) fails closed, while 1 failure out of 2 listed ({@code 2 > 2} is false) stays
   * best-effort — preserving the existing best-effort skip for an isolated query race. A bare
   * {@code >} (not {@code >=}) keeps the exactly-50% boundary best-effort.
   */
  private static final int VALUE_FAILURE_NUMERATOR_MULTIPLIER = 2;

  private final WorkflowClient client;

  public VisibilityPortfolioSnapshot(WorkflowClient client) {
    this.client = client;
  }

  /**
   * WARNING — fail-closed seam (#325, hardening #318). This method must let a Visibility error (the
   * {@code listExecutions} query or the stream iteration) <b>propagate</b>. Do NOT wrap the body in
   * {@code try { ... } catch (Exception e) { return List.of(); }}: an empty list means {@code
   * sum_open_notional=0}, which <b>loosens</b> the {@code notional_cap_pct_of_equity} cap and flips
   * the gate <b>fail-OPEN</b> (it would then permit trades it should reject). The fail-closed
   * guarantee relies on the throwable reaching {@code RiskActivitiesImpl.PortfolioContext
   * .openPositions()} and failing the {@code checkEntry}/{@code checkEntryWithLimit} activity so
   * the workflow never reaches {@code placeOrder}. The per-position {@link #valuePosition} swallow
   * is deliberately bounded below (Task (c)) for the same reason.
   */
  @Override
  public List<OpenPosition> openPositions(String tenantId, String strategyId) {
    String tenantStrategy = WorkflowIds.tenantStrategy(tenantId, strategyId);
    String query =
        "WorkflowType='PositionWorkflow' AND TenantStrategy='"
            + WorkflowIds.escapeForVisibilityQuery(tenantStrategy)
            + "' AND ExecutionStatus='Running'";

    List<OpenPosition> positions = new ArrayList<>();
    int listed = 0;
    int valueFailures = 0;
    try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
      var it = stream.iterator();
      while (it.hasNext()) {
        WorkflowExecutionMetadata md = it.next();
        String wfId = md.getExecution().getWorkflowId();
        listed++;
        ValueResult result = valuePosition(wfId, tenantId, strategyId);
        if (result.failed()) {
          valueFailures++;
        } else if (result.position() != null) {
          positions.add(result.position());
        }
      }
    }

    // Task (c) fail-closed bound (#325): a correlated value-query degradation that drops more than
    // half the listed positions must fail the snapshot (throw) rather than undercount and loosen
    // the
    // cap. See VALUE_FAILURE_NUMERATOR_MULTIPLIER for the exact rule and rationale.
    if (listed > 0 && (long) valueFailures * VALUE_FAILURE_NUMERATOR_MULTIPLIER > listed) {
      throw new IllegalStateException(
          "openPositions value-failure bound exceeded: "
              + valueFailures
              + " of "
              + listed
              + " listed positions failed to value (tenant="
              + tenantId
              + " strategy="
              + strategyId
              + "); failing closed rather than undercounting sum_open_notional");
    }
    return positions;
  }

  /**
   * Query one running {@code PositionWorkflow} for its open state and turn it into an {@link
   * OpenPosition}. Best-effort within the Task (c) bound: a workflow that just closed (or whose
   * query races termination) is skipped rather than failing the whole snapshot — the gate then sees
   * one fewer position. The returned {@link ValueResult} distinguishes a genuine
   * value-<i>failure</i> (the {@code catch} branch — counted toward the fail-closed bound in {@link
   * #openPositions}) from a legitimate <i>skip</i> ({@code null} position for a
   * closed/blank/null-premium workflow — NOT counted), so a correlated query degradation fails
   * closed while an isolated close stays best-effort.
   */
  private ValueResult valuePosition(String wfId, String tenantId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      PositionState state = stub.query("positionState", PositionState.class);
      if (state == null
          || state.contractSymbol() == null
          || state.contractSymbol().isBlank()
          || state.remainingQty() <= 0
          || state.entryPremium() == null) {
        return ValueResult.skip();
      }
      BigDecimal openNotional =
          state
              .entryPremium()
              .multiply(BigDecimal.valueOf(state.remainingQty()))
              .multiply(Sizing.CONTRACT_MULTIPLIER);
      return ValueResult.valued(
          new OpenPosition(OccSymbol.underlying(state.contractSymbol()), openNotional));
    } catch (RuntimeException e) {
      log.warn(
          "positionState query failed wf={} tenant={} strategy={} err={}",
          wfId,
          tenantId,
          strategyId,
          e.getMessage());
      return ValueResult.failure();
    }
  }

  /**
   * Outcome of valuing one listed position. A {@code failed} value-query (the {@code catch} branch)
   * counts toward the Task (c) fail-closed bound; a legitimate skip ({@code null} position) does
   * not.
   */
  private record ValueResult(OpenPosition position, boolean failed) {
    static ValueResult valued(OpenPosition position) {
      return new ValueResult(position, false);
    }

    static ValueResult skip() {
      return new ValueResult(null, false);
    }

    static ValueResult failure() {
      return new ValueResult(null, true);
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
