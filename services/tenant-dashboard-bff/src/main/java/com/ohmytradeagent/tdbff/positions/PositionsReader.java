package com.ohmytradeagent.tdbff.positions;

// Positions fan-out + cost-basis notional COPIED FROM
// services/orchestrator/.../activities/VisibilityPortfolioSnapshot.java — keep in sync.
// Divergence: that snapshot feeds a risk gate and must fail CLOSED (throw) when too many positions
// fail to value, because an undercount loosens the notional cap. This is a READ-ONLY display with
// no cap to protect, so a per-workflow query race is simply skipped (best-effort) — never a throw.
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * positionState} query per workflow to value it, plus a {@code trailingState} query per workflow
 * for the armed trailing stop /live shows on the row. Cost-basis notional = {@code entryPremium ×
 * remainingQty × 100} (US equity-options multiplier) — entry premium, NOT live mark (no market-data
 * wired).
 */
@Component
public class PositionsReader {

  private static final Logger log = LoggerFactory.getLogger(PositionsReader.class);
  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";
  private static final BigDecimal CONTRACT_MULTIPLIER = new BigDecimal("100");
  // Issue #434: an option whose physical expiry has passed has been dropped by the broker at
  // expiry; a PositionWorkflow that rode a worthless contract to expiry can linger "open" until
  // its durable worthless-close lands. The dashboard is a read-only view, so filter expired OCCs
  // out of open_positions / sum_open_notional immediately. Expiry is compared against TODAY in the
  // US options market timezone (the trading calendar the broker drops contracts on).
  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

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
      // Issue #434: drop a physically-expired contract — it must not appear in open_positions nor
      // contribute to sum_open_notional. Fail-OPEN: an unparseable OCC (parseExpiry -> null) is
      // KEPT so a parse quirk never hides a real live position from the operator.
      LocalDate expiry = parseExpiry(state.contractSymbol());
      if (expiry != null && expiry.isBefore(LocalDate.now(MARKET_TZ))) {
        log.debug(
            "filtering expired position wf={} strategy={} occ={} expiry={}",
            wfId,
            strategyId,
            state.contractSymbol(),
            expiry);
        return null;
      }
      BigDecimal openNotional =
          state
              .entryPremium()
              .multiply(BigDecimal.valueOf(state.remainingQty()))
              .multiply(CONTRACT_MULTIPLIER);
      TrailingStateView trail = trailingState(wfId, strategyId);
      return new OpenPosition(
          wfId,
          strategyId,
          state.contractSymbol(),
          state.remainingQty(),
          state.entryPremium(),
          openNotional,
          trail != null && trail.armed(),
          trail != null && trail.armed() ? trail.givebackPct() : null,
          trail != null && trail.armed() ? trail.thresholdPremium() : null);
    } catch (RuntimeException e) {
      log.warn(
          "positionState query failed wf={} strategy={} err={}", wfId, strategyId, e.getMessage());
      return null;
    }
  }

  /**
   * The position's armed trailing stop, or {@code null} when it has none / the query fails.
   *
   * <p>Separately try-caught from {@link #valuePosition}: a position must NEVER disappear from the
   * operator's holdings because a decorative badge could not be read. Degrades to "no trail shown",
   * which is also what an orchestrator too old to answer this query produces.
   */
  private TrailingStateView trailingState(String wfId, String strategyId) {
    try {
      return client.newUntypedWorkflowStub(wfId).query("trailingState", TrailingStateView.class);
    } catch (RuntimeException e) {
      log.warn(
          "trailingState query failed wf={} strategy={} err={} — rendering position without its"
              + " trailing stop",
          wfId,
          strategyId,
          e.getMessage());
      return null;
    }
  }

  /**
   * Issue #434: BFF-local OCC expiry parser. The BFF must NOT depend on the orchestrator's {@code
   * OccSymbol}, so this parses the expiry independently. The OCC tail is fixed-width: {@code
   * YYMMDD}(6) + right{@code C|P}(1) + strike(8) = 15 chars, with the underlying root (variable,
   * space-padded to 6 in the canonical form, e.g. {@code TSLA 260618P00380000}) leading. Spaces are
   * stripped first so both the padded canonical form and the compact broker form parse; the expiry
   * is then the 6-digit {@code YYMMDD} at {@code length-15}. Returns {@code null} on any parse
   * failure (too short, non-numeric, invalid date) so the caller fails OPEN.
   */
  static LocalDate parseExpiry(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replace(" ", "");
    if (compact.length() < 15) {
      return null;
    }
    String yymmdd = compact.substring(compact.length() - 15, compact.length() - 9);
    try {
      int yy = Integer.parseInt(yymmdd.substring(0, 2));
      int mm = Integer.parseInt(yymmdd.substring(2, 4));
      int dd = Integer.parseInt(yymmdd.substring(4, 6));
      return LocalDate.of(2000 + yy, mm, dd);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * One valued open position. The trailing triple is DISPLAY-only, off the workflow's own {@code
   * trailingState} query: {@code trailingArmed} says whether a chandelier trail is armed, {@code
   * trailGivebackPct} at what fraction, and {@code trailStopPrice} where it would fire right now.
   * The stop price is PEAK-anchored and must be passed through as-is — recomputing it from a live
   * mark understates the stop on any position sitting below its high.
   *
   * <p>It describes the CHANDELIER trail only. A watchlist-exit position that has already hit its
   * target also carries a tighter breakeven {@code exitStopLevel} which this does not report, so on
   * that one route the badge is pessimistic (it names a stop below the level that would really
   * exit). All real-money tenants are copytrade, where the chandelier IS the stop.
   */
  public record OpenPosition(
      String workflowId,
      String strategyId,
      String contractSymbol,
      long remainingQty,
      BigDecimal entryPremium,
      BigDecimal openNotional,
      boolean trailingArmed,
      BigDecimal trailGivebackPct,
      BigDecimal trailStopPrice) {

    /**
     * Back-compat 6-arg form for call sites that predate the trailing fields. Defaults to UN-armed
     * — a producer that does not observe a trail must not claim one.
     */
    public OpenPosition(
        String workflowId,
        String strategyId,
        String contractSymbol,
        long remainingQty,
        BigDecimal entryPremium,
        BigDecimal openNotional) {
      this(
          workflowId,
          strategyId,
          contractSymbol,
          remainingQty,
          entryPremium,
          openNotional,
          false,
          null,
          null);
    }
  }

  /**
   * Transport mirror of the orchestrator's {@code PositionState} query result so the BFF can
   * deserialize the {@code positionState} query without a compile dependency on the orchestrator
   * module. Field names must match {@code PositionState(contractSymbol, remainingQty,
   * entryPremium)}. {@code ignoreUnknown} makes it forward-compatible: the orchestrator's {@code
   * positionState} result grew to add {@code entryAt}/{@code partialExited}, and without this the
   * Temporal data converter (Jackson, fail-on-unknown) threw on every query, dropping every
   * position so the dashboard showed 0 open. The BFF intentionally mirrors only the fields it
   * needs.
   *
   * <p>The trailing triple ({@code trailingArmed}, {@code trailGivebackPct}, {@code
   * trailStopPrice}) is what /live's per-position stop badge renders. An orchestrator that predates
   * those fields simply omits them and Jackson leaves the defaults ({@code false} / {@code null}),
   * so a mixed-version cluster degrades to "no trail shown" — never to a badge claiming protection
   * that isn't there.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PositionStateView(
      String contractSymbol, long remainingQty, BigDecimal entryPremium) {}

  /**
   * Transport mirror of the orchestrator's {@code TrailingState} query result — the armed
   * chandelier/operator trailing stop, which /live renders per position.
   *
   * <p>Read from the SEPARATE {@code trailingState} query rather than by widening {@code
   * positionState}, and that separation is load-bearing, not incidental. {@code positionState} is
   * deserialized inside the orchestrator by three FAIL-CLOSED consumers ({@code
   * VisibilityPortfolioSnapshot}, {@code AccountPnlActivitiesImpl}, {@code
   * PositionLookupActivitiesImpl}) whose own copy of the record has no {@code ignoreUnknown}: on a
   * rolling deploy an old pod querying a workflow served by a new pod would throw on the added
   * fields, and those failures feed {@code AccountKillSwitchWorkflowImpl}'s fail-closed count —
   * i.e. widening it to paint a dashboard badge could halt-and-flatten a live account. {@code
   * trailingState} is display-only, consumed by nothing else, and already deployed.
   *
   * <p>{@code thresholdPremium} is the fire trigger the tick loop compares against — carried
   * through as-is. It is PEAK-anchored, so it only ever rises; a client re-deriving it from a live
   * mark would understate the stop on any position below its high.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TrailingStateView(
      boolean armed, BigDecimal givebackPct, BigDecimal thresholdPremium) {}
}
