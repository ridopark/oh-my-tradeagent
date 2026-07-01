package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 6: {@link AccountPnlActivities} default impl.
 *
 * <p><b>Realized (tenant-wide).</b> Reuses the existing, tested per-strategy realized-PnL FIFO
 * composition ({@link DailyPnlActivities#computeRealizedPnl}) once per strategy and sums the
 * results — it does NOT re-implement the SQL and does NOT modify the per-strategy method. The
 * strategy set comes from {@link TenantStrategies#strategyIdsForTenant} (the same resolver the #323
 * notional-cap snapshot uses).
 *
 * <p><b>Open book (tenant-wide).</b> Mirrors {@link VisibilityPortfolioSnapshot}: runs the PROVEN
 * {@code TenantStrategy='t-<t>/s-<sid>'} equality query once per strategy (never a {@code
 * STARTS_WITH} / {@code IN (...)} clause — Temporal SQL Visibility lacks both), unions deduped by
 * workflow id, and queries each running PositionWorkflow's {@code positionState}. It returns the
 * raw per-position fields plus the {@code listed} / {@code valueFailures} counts so the workflow
 * can apply the #325 fail-closed bound across BOTH the positionState failures and the option-quote
 * failures it sees when valuing MTM.
 *
 * <p><b>Fail-CLOSED seam.</b> A Visibility error (the {@code listExecutions} query or stream
 * iteration) and a resolver throw both PROPAGATE — they are NOT swallowed into an empty book. An
 * empty book would report zero open loss and could let a real drawdown slip under the cap
 * (fail-OPEN). An empty resolved strategy set likewise throws.
 */
public class AccountPnlActivitiesImpl implements AccountPnlActivities {

  private static final Logger log = LoggerFactory.getLogger(AccountPnlActivitiesImpl.class);

  private final DailyPnlActivities dailyPnl;
  private final TenantStrategies tenantStrategies;
  private final WorkflowClient client;
  private final StrategyRegistry strategyRegistry;

  public AccountPnlActivitiesImpl(
      DailyPnlActivities dailyPnl,
      TenantStrategies tenantStrategies,
      WorkflowClient client,
      StrategyRegistry strategyRegistry) {
    this.dailyPnl = dailyPnl;
    this.tenantStrategies = tenantStrategies;
    this.client = client;
    this.strategyRegistry = strategyRegistry;
  }

  @Override
  public BigDecimal computeTenantRealizedPnl(String tenantId, LocalDate tradingDay) {
    List<String> strategyIds = tenantStrategies.strategyIdsForTenant(tenantId);
    BigDecimal total = BigDecimal.ZERO;
    for (String sid : strategyIds) {
      if (sid == null || sid.isBlank()) {
        continue;
      }
      total = total.add(dailyPnl.computeRealizedPnl(tenantId, sid, tradingDay));
    }
    return total;
  }

  @Override
  public List<TenantStrategyBrokerTarget> tenantStrategyBrokerTargets(String tenantId) {
    List<String> strategyIds = tenantStrategies.strategyIdsForTenant(tenantId);
    List<TenantStrategyBrokerTarget> out = new ArrayList<>();
    for (String sid : strategyIds) {
      if (sid == null || sid.isBlank()) {
        continue;
      }
      String brokerTarget = null;
      try {
        StrategyConfig cfg = strategyRegistry.get(tenantId, sid);
        StrategyConfig.BrokerTarget bt = cfg == null ? null : cfg.getBrokerTarget();
        brokerTarget = bt == null ? null : bt.value();
      } catch (RuntimeException e) {
        // Leave brokerTarget null — the workflow fails CLOSED on a strategy it cannot route (G2)
        // rather than the activity silently dropping it (which would under-count the account loss).
        log.warn(
            "tenantStrategyBrokerTargets: strategy config read failed tenant={} strategy={} err={}",
            tenantId,
            sid,
            e.getMessage());
      }
      out.add(new TenantStrategyBrokerTarget(sid, brokerTarget));
    }
    if (out.isEmpty()) {
      // Fail CLOSED: an empty strategy set means we cannot know the tenant's realized book. Summing
      // nothing would zero the realized loss and could let a real drawdown slip under the cap.
      throw new IllegalStateException(
          "tenantStrategyBrokerTargets resolved an empty strategy set for tenant="
              + tenantId
              + "; failing closed rather than summing nothing and under-counting realized loss");
    }
    return out;
  }

  @Override
  public AccountOpenBook accountOpenBook(String tenantId) {
    Set<String> strategyIds = new LinkedHashSet<>();
    for (String sid : tenantStrategies.strategyIdsForTenant(tenantId)) {
      if (sid != null && !sid.isBlank()) {
        strategyIds.add(sid);
      }
    }
    if (strategyIds.isEmpty()) {
      // Fail CLOSED: an empty strategy set means we cannot know the tenant's running book.
      // Reporting
      // an empty book would zero the open loss and could let a real drawdown slip under the cap.
      throw new IllegalStateException(
          "accountOpenBook resolved an empty strategy set for tenant="
              + tenantId
              + "; failing closed rather than querying nothing and under-counting open loss");
    }

    List<OpenPositionValuation> positions = new ArrayList<>();
    Set<String> seenWorkflowIds = new LinkedHashSet<>();
    int listed = 0;
    int valueFailures = 0;
    for (String sid : strategyIds) {
      String query =
          "WorkflowType='PositionWorkflow' AND TenantStrategy='"
              + WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenantId, sid))
              + "' AND ExecutionStatus='Running'";
      try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
        var it = stream.iterator();
        while (it.hasNext()) {
          WorkflowExecutionMetadata md = it.next();
          String wfId = md.getExecution().getWorkflowId();
          if (!seenWorkflowIds.add(wfId)) {
            continue;
          }
          listed++;
          OpenPositionValuation valuation = valuePosition(wfId, tenantId, sid);
          if (valuation == FAILED) {
            valueFailures++;
          } else if (valuation != null) {
            positions.add(valuation);
          }
        }
      }
    }
    return new AccountOpenBook(positions, listed, valueFailures);
  }

  /**
   * Sentinel for a genuine positionState-query failure (a degradation signal, not a benign skip).
   */
  private static final OpenPositionValuation FAILED =
      new OpenPositionValuation("__failed__", BigDecimal.ZERO, 0L);

  /**
   * Queries one running PositionWorkflow for its open state. Returns the valuation inputs, {@code
   * null} for a legitimate just-closed/blank/null-premium skip, or {@link #FAILED} when the query
   * itself threw (counted toward the fail-closed bound by the workflow). Mirrors {@link
   * VisibilityPortfolioSnapshot#valuePosition}.
   */
  private OpenPositionValuation valuePosition(String wfId, String tenantId, String strategyId) {
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
      return new OpenPositionValuation(
          state.contractSymbol(), state.entryPremium(), state.remainingQty());
    } catch (RuntimeException e) {
      log.warn(
          "positionState query failed wf={} tenant={} strategy={} err={}",
          wfId,
          tenantId,
          strategyId,
          e.getMessage());
      return FAILED;
    }
  }
}
