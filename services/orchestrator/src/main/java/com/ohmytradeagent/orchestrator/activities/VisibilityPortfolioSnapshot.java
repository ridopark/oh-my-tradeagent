package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #318: Temporal Advanced Visibility–backed {@link PortfolioSnapshot}. Lists running {@code
 * PositionWorkflow} instances for the requesting tenant and values each open position so the {@code
 * same_underlying_count} and {@code notional_cap_pct_of_equity} portfolio gates in {@link
 * RiskActivitiesImpl} observe the real open book (the prior no-op default always reported an empty
 * list, so both gates saw zero positions).
 *
 * <p><b>Visibility query (#323 — tenant-account-wide).</b> Filters on the {@code TenantStrategy}
 * custom Search Attribute plus {@code WorkflowType='PositionWorkflow' AND
 * ExecutionStatus='Running'} — never a {@code WorkflowId} prefix (Temporal SQL Visibility has no
 * {@code STARTS_WITH} on {@code WorkflowId}; {@code docs/plans/PLAN.md:120-127}). Per the
 * operator's #323 design decision a {@code broker_target} is owned by exactly one tenant and the
 * tenant's strategies share it, so the cap basis is the tenant's <b>whole</b> running book. The
 * {@code TenantStrategy} filter is therefore an {@code IN (...)} clause over <i>all of the
 * requesting tenant's strategies</i> ({@link TenantStrategies#strategyIdsForTenant}), not just the
 * current strategy. The returned metadata stream is closed via try-with-resources. <b>Cross-tenant
 * isolation is preserved structurally:</b> only the requesting tenant's strategies enter the {@code
 * IN} list (built with the {@code t-<t>/} prefix on every element), so another tenant's
 * PositionWorkflows never leak into the snapshot. The single-tenant single-strategy deployment
 * yields a one-element {@code IN} list — the same result set as the pre-#323 {@code
 * TenantStrategy='...'} equality filter (inertness).
 *
 * <p><b>{@code openNotional} source — cost basis.</b> Per running {@code PositionWorkflow}, the
 * {@code positionState()} query supplies remaining qty + per-contract entry premium, and notional
 * is {@code openNotional = entryPremium × remainingQty × CONTRACT_MULTIPLIER (100)}. This is the
 * same cost-basis notional the sizing path uses ({@code RiskActivitiesImpl.entryNotional = price ×
 * contracts × Sizing.CONTRACT_MULTIPLIER}, {@code RiskActivitiesImpl.java:409-411}) — entry
 * premium, not live mark. {@code underlyingTicker} is derived from the OCC {@code contractSymbol}
 * via {@link OccSymbol#underlying(String)} (root → underlying).
 *
 * <p><b>Cost-basis capital base (#323).</b> {@code sum_open_notional} (this numerator) is <b>cost
 * basis</b> (entry premium × remaining qty × multiplier). As of #323 the {@code
 * notional_cap_pct_of_equity} cap denominator is the <b>cost-basis capital base</b> {@code cash +
 * sum_open_notional}, not the net-liq (MTM) equity — so numerator and denominator share the same
 * cost-basis open-notional term and the cap is MTM-stable (it neither loosens on an appreciating
 * long-options book nor tightens on a bleeding one, and adds no new market-data dependency). This
 * snapshot's {@link #accountEquity(String)} fallback still returns the documented ZERO sentinel
 * (fail-closed); the live capital base is threaded over the broker dispatch seam ({@code cash}
 * added to the Alpaca {@code /v2/account} read, see {@code RiskActivitiesImpl.checkNotionalCap}).
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
   * an undercounted list once the listed positions fail to <i>value</i> badly enough. Only genuine
   * value-failures ({@link ValueResult#failure()} — a Running workflow that cannot answer its state
   * query, a degradation signal) count; legitimate just-closed/blank/null-premium <i>skips</i>
   * ({@link ValueResult#skip()}) do NOT.
   *
   * <p>The bound combines two rules (see {@link #failsClosed}):
   *
   * <ul>
   *   <li><b>Relative >50% threshold (larger books).</b> With {@code N} listed running positions
   *       and {@code F} value-failures, throw when {@code F * 2 > N} (strictly more than half of
   *       listed positions failed to value). A bare {@code >} (not {@code >=}) keeps the
   *       exactly-50% boundary best-effort on larger books, where a single isolated query race is
   *       proportionally small.
   *   <li><b>Small-book floor (1–2 positions).</b> On a 1- or 2-position book, <i>any</i> single
   *       genuine value-failure fails closed. On such a tiny book one missed position is up to a
   *       full position's notional — materially loosening the cap — and the relative threshold
   *       alone leaves a hole at exactly 50% (1-of-2: {@code 2 > 2} is false), so the floor closes
   *       it. Benign skips still do not count, so an all-skips tiny book stays best-effort (empty
   *       list).
   * </ul>
   */
  private static final int RELATIVE_FAILURE_THRESHOLD_MULTIPLIER = 2;

  /** Books with at most this many listed positions fall under the small-book floor. */
  private static final int SMALL_BOOK_MAX_POSITIONS = 2;

  /**
   * Issue #329 observability-only counter. Incremented by the per-call {@code valueFailures} tally
   * whenever {@code valueFailures > 0}, tagged {@code tenant}/{@code strategy}/{@code
   * failed_closed} ({@code true} when the {@link #failsClosed} bound tripped and the call threw,
   * {@code false} when the failures stayed under the bound and the call returned a best-effort
   * list). Surfaces correlated near-boundary value-query degradation (e.g. exactly 50% on a larger
   * book, or 1-of-3) that would otherwise drop positions from {@code sum_open_notional} silently.
   * This is purely a signal — it does NOT influence the gate decision, the {@link #failsClosed}
   * bound, the relative threshold, or the small-book floor.
   */
  static final String VALUE_FAILURES_COUNTER_NAME = "openpositions_value_failures_total";

  private final WorkflowClient client;
  private final MeterRegistry meterRegistry;
  private final TenantStrategies tenantStrategies;
  private final ConcurrentMap<String, Counter> valueFailureCounters = new ConcurrentHashMap<>();

  /**
   * Back-compat / single-strategy constructor: the tenant-strategy resolver collapses to the
   * requesting strategy only, so the {@code TenantStrategy IN (...)} clause is a one-element list —
   * the same result set as the pre-#323 equality filter. Used by unit tests and any deployment that
   * does not wire the scanner-backed {@link TenantStrategies}.
   */
  public VisibilityPortfolioSnapshot(WorkflowClient client, MeterRegistry meterRegistry) {
    this(client, meterRegistry, tenantId -> List.of());
  }

  /**
   * Issue #323 production constructor: the {@link TenantStrategies} resolver widens the cap basis
   * to all of the requesting tenant's strategies on the shared {@code broker_target}.
   */
  public VisibilityPortfolioSnapshot(
      WorkflowClient client, MeterRegistry meterRegistry, TenantStrategies tenantStrategies) {
    this.client = client;
    this.meterRegistry = meterRegistry;
    this.tenantStrategies = tenantStrategies;
  }

  /**
   * Whether the value-failure tally over a non-empty listed book must fail the snapshot closed.
   * Trips when either the relative {@code >50%} threshold is exceeded or — on a 1–2 position book —
   * at least one genuine value-failure occurred (the small-book floor). See {@link
   * #RELATIVE_FAILURE_THRESHOLD_MULTIPLIER} for the full rule and rationale.
   */
  private static boolean failsClosed(int listed, int valueFailures) {
    boolean exceedsRelativeThreshold =
        (long) valueFailures * RELATIVE_FAILURE_THRESHOLD_MULTIPLIER > listed;
    boolean tripsSmallBookFloor = listed <= SMALL_BOOK_MAX_POSITIONS && valueFailures >= 1;
    return exceedsRelativeThreshold || tripsSmallBookFloor;
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
    String query =
        "WorkflowType='PositionWorkflow' AND TenantStrategy IN ("
            + tenantStrategyInList(tenantId, strategyId)
            + ") AND ExecutionStatus='Running'";

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

    // Task (c) fail-closed bound (#325): a correlated value-query degradation that drops too many
    // listed positions must fail the snapshot (throw) rather than undercount and loosen the cap.
    // See failsClosed / RELATIVE_FAILURE_THRESHOLD_MULTIPLIER for the relative threshold plus the
    // small-book floor and rationale.
    boolean failedClosed = listed > 0 && failsClosed(listed, valueFailures);

    // Issue #329 observability-only emit: surface any genuine value-failures (regardless of whether
    // the fail-closed bound tripped) so near-boundary degradation episodes are visible rather than
    // silent. Gated on valueFailures > 0; does NOT alter the gate decision below.
    recordValueFailures(tenantId, strategyId, failedClosed, valueFailures);

    if (failedClosed) {
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
   * Builds the comma-separated, single-quoted {@code TenantStrategy} value list for the {@code IN
   * (...)} clause (#323). Resolves the requesting tenant's full strategy set via {@link
   * TenantStrategies} and always unions in the requesting {@code strategyId} so the snapshot can
   * never be narrower than the pre-#323 single-strategy filter (and so an empty/missing resolver
   * result still queries at least the current strategy rather than building a match-nothing query
   * that would loosen the cap fail-OPEN). Cross-tenant isolation holds because every element is
   * built with the {@code t-<tenantId>/} prefix from {@link WorkflowIds#tenantStrategy}. A throw
   * from the resolver (unreadable tenants tree) propagates — fail-closed (#325).
   */
  private String tenantStrategyInList(String tenantId, String strategyId) {
    Set<String> strategyIds = new LinkedHashSet<>();
    strategyIds.add(strategyId);
    strategyIds.addAll(tenantStrategies.strategyIdsForTenant(tenantId));
    return strategyIds.stream()
        .map(sid -> WorkflowIds.tenantStrategy(tenantId, sid))
        .map(WorkflowIds::escapeForVisibilityQuery)
        .map(escaped -> "'" + escaped + "'")
        .collect(Collectors.joining(", "));
  }

  /**
   * Issue #329 observability-only emit. When the per-call {@code valueFailures} tally is non-zero,
   * increment {@link #VALUE_FAILURES_COUNTER_NAME} by that count, tagged {@code tenant}/{@code
   * strategy}/{@code failed_closed}. Counters are cached per {@code (tenant, strategy,
   * failed_closed)} tag combination (Micrometer dedupes by name+tags anyway; caching avoids the
   * lookup-and-register cost). Tag cardinality is bounded — no per-workflow / per-correlation
   * labels. A no-op when {@code valueFailures == 0}, so an all-good / all-legitimate-skips book
   * emits nothing.
   */
  private void recordValueFailures(
      String tenantId, String strategyId, boolean failedClosed, int valueFailures) {
    if (valueFailures <= 0) {
      return;
    }
    String key = tenantId + "|" + strategyId + "|" + failedClosed;
    Counter counter =
        valueFailureCounters.computeIfAbsent(
            key,
            k ->
                Counter.builder(VALUE_FAILURES_COUNTER_NAME)
                    .description(
                        "Per-position openPositions value-query failures (#329 observability; does not affect the gate decision).")
                    .tag("tenant", tenantId)
                    .tag("strategy", strategyId)
                    .tag("failed_closed", Boolean.toString(failedClosed))
                    .register(meterRegistry));
    counter.increment(valueFailures);
    log.warn(
        "openPositions value-failures observed tenant={} strategy={} value_failures={} failed_closed={}",
        tenantId,
        strategyId,
        valueFailures,
        failedClosed);
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
