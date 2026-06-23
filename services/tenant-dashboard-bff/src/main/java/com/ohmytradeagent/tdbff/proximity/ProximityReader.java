package com.ohmytradeagent.tdbff.proximity;

// Mirrors PositionsReader's listExecutions + per-workflow query fan-out. Like that reader this is a
// READ-ONLY display with no cap to protect, so a per-workflow query race (a workflow terminating
// between the listExecutions and the query) is simply skipped best-effort, never a throw.
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry/exit proximity for a tenant's live watchlist legs and open positions, for the dashboard
 * {@code /live} view. Per strategy it runs the same {@code TenantStrategy='t-<t>/s-<sid>' AND
 * ExecutionStatus='Running'} Visibility query {@code PositionsReader} uses (both {@code
 * WatchlistTriggerWorkflow} and {@code PositionWorkflow} carry the {@code TenantStrategy} search
 * attribute), then fans out the {@code entryProximity} / {@code exitProximity} query per workflow.
 * Distances are computed here so the workflow queries stay deterministic (no clock read).
 */
@Component
public class ProximityReader {

  private static final Logger log = LoggerFactory.getLogger(ProximityReader.class);
  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";
  private static final String WATCHLIST_WORKFLOW_TYPE = "WatchlistTriggerWorkflow";
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int PCT_SCALE = 4;

  private final WorkflowClient client;
  private final TenantStrategyResolver strategyResolver;

  public ProximityReader(WorkflowClient client, TenantStrategyResolver strategyResolver) {
    this.client = client;
    this.strategyResolver = strategyResolver;
  }

  /** Live un-fired watchlist legs for the tenant, unioned across strategies, deduped by wf id. */
  public List<WatchlistProximity> watchlist(String tenantId) {
    List<WatchlistProximity> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      try (Stream<WorkflowExecutionMetadata> stream =
          client.listExecutions(runningQuery(WATCHLIST_WORKFLOW_TYPE, tenantId, strategyId))) {
        var it = stream.iterator();
        while (it.hasNext()) {
          String wfId = it.next().getExecution().getWorkflowId();
          if (!seen.add(wfId)) {
            continue;
          }
          WatchlistProximity w = entryProximity(wfId, strategyId);
          if (w != null) {
            out.add(w);
          }
        }
      }
    }
    return out;
  }

  /** Armed watchlist-exit positions for the tenant, unioned across strategies, deduped by wf id. */
  public List<PositionProximity> positions(String tenantId) {
    List<PositionProximity> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      try (Stream<WorkflowExecutionMetadata> stream =
          client.listExecutions(runningQuery(POSITION_WORKFLOW_TYPE, tenantId, strategyId))) {
        var it = stream.iterator();
        while (it.hasNext()) {
          String wfId = it.next().getExecution().getWorkflowId();
          if (!seen.add(wfId)) {
            continue;
          }
          PositionProximity p = exitProximity(wfId, strategyId);
          if (p != null) {
            out.add(p);
          }
        }
      }
    }
    return out;
  }

  private static String runningQuery(String workflowType, String tenantId, String strategyId) {
    return String.format(
        "WorkflowType='%s' AND TenantStrategy='%s' AND ExecutionStatus='Running'",
        workflowType,
        WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenantId, strategyId)));
  }

  private WatchlistProximity entryProximity(String wfId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      EntryProximityView v = stub.query("entryProximity", EntryProximityView.class);
      if (v == null || v.ticker() == null || v.ticker().isBlank()) {
        return null;
      }
      return new WatchlistProximity(
          wfId,
          strategyId,
          v.ticker(),
          v.direction(),
          v.triggerLevel(),
          v.bandLow(),
          v.bandHigh(),
          v.lastPrice(),
          v.state(),
          distanceToTrigger(v));
    } catch (RuntimeException e) {
      log.warn(
          "entryProximity query failed wf={} strategy={} err={}", wfId, strategyId, e.getMessage());
      return null;
    }
  }

  private PositionProximity exitProximity(String wfId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      ExitProximityView v = stub.query("exitProximity", ExitProximityView.class);
      // Only watchlist-exit positions (armed) carry proximity levels; skip copytrade/unarmed.
      if (v == null || !v.armed() || v.contractSymbol() == null || v.contractSymbol().isBlank()) {
        return null;
      }
      return new PositionProximity(
          wfId,
          strategyId,
          v.contractSymbol(),
          v.entryPremium(),
          v.stopLevel(),
          v.targetLevel(),
          v.lastBid(),
          v.peakPremium(),
          v.trailingArmed(),
          pct(subtract(v.lastBid(), v.stopLevel()), v.lastBid()),
          pct(subtract(v.targetLevel(), v.lastBid()), v.lastBid()));
    } catch (RuntimeException e) {
      log.warn(
          "exitProximity query failed wf={} strategy={} err={}", wfId, strategyId, e.getMessage());
      return null;
    }
  }

  /**
   * Percent the underlying must still move toward the trigger to fire (positive = not yet crossed,
   * &lt;=0 = past it). Direction-aware: ABOVE needs price to rise to the trigger, BELOW to fall.
   */
  static Double distanceToTrigger(EntryProximityView v) {
    if (v.lastPrice() == null || v.triggerLevel() == null || v.triggerLevel().signum() == 0) {
      return null;
    }
    BigDecimal gap =
        "BELOW".equals(v.direction())
            ? v.lastPrice().subtract(v.triggerLevel())
            : v.triggerLevel().subtract(v.lastPrice());
    return pct(gap, v.triggerLevel());
  }

  private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
    return (a == null || b == null) ? null : a.subtract(b);
  }

  /**
   * Underlying root from an OCC option symbol (e.g. {@code NVDA 260516C00140000} or compact {@code
   * NVDA260516C00140000} -> {@code NVDA}). The OCC tail is fixed-width:
   * YYMMDD(6)+right(1)+strike(8) = 15 chars; the root is whatever precedes it (spaces stripped).
   * Returns null on a too-short / unparseable symbol so the caller skips the underlying-price
   * lookup.
   */
  public static String underlyingTicker(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replace(" ", "");
    if (compact.length() <= 15) {
      return null;
    }
    String root = compact.substring(0, compact.length() - 15);
    return root.isBlank() ? null : root;
  }

  /** {@code numerator / denominator * 100}, rounded; null if either operand is null or denom 0. */
  static Double pct(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator
        .divide(denominator, PCT_SCALE + 2, RoundingMode.HALF_UP)
        .multiply(HUNDRED)
        .setScale(PCT_SCALE, RoundingMode.HALF_UP)
        .doubleValue();
  }

  /** One live watchlist leg's entry proximity. */
  public record WatchlistProximity(
      String workflowId,
      String strategyId,
      String ticker,
      String direction,
      BigDecimal triggerLevel,
      BigDecimal bandLow,
      BigDecimal bandHigh,
      BigDecimal lastPrice,
      String state,
      Double distanceToTriggerPct) {}

  /** One armed position's exit proximity. */
  public record PositionProximity(
      String workflowId,
      String strategyId,
      String contractSymbol,
      BigDecimal entryPremium,
      BigDecimal stopLevel,
      BigDecimal targetLevel,
      BigDecimal lastBid,
      BigDecimal peakPremium,
      boolean trailingArmed,
      Double distanceToStopPct,
      Double distanceToTargetPct) {}

  /**
   * Transport mirror of the orchestrator's {@code EntryProximityView} query result (field names
   * must match) so the BFF deserializes the {@code entryProximity} query without a compile
   * dependency on the orchestrator module.
   */
  public record EntryProximityView(
      String ticker,
      String direction,
      BigDecimal triggerLevel,
      BigDecimal bandLow,
      BigDecimal bandHigh,
      BigDecimal lastPrice,
      String state) {}

  /** Transport mirror of the orchestrator's {@code ExitProximityView} query result. */
  public record ExitProximityView(
      String contractSymbol,
      BigDecimal entryPremium,
      BigDecimal stopLevel,
      BigDecimal targetLevel,
      BigDecimal lastBid,
      BigDecimal lastTickPremium,
      BigDecimal peakPremium,
      boolean trailingArmed,
      BigDecimal givebackPct,
      boolean armed,
      OffsetDateTime lastTickAt) {}
}
