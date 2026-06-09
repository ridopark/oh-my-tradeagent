package com.ohmytradeagent.orchestrator.activities;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 7 reconciliation metrics impl (issue #89). Holds the injected {@link MeterRegistry} and
 * updates one timer + two counters per reconciliation cycle.
 *
 * <p>Meters are cached per {@code (tenant, strategy, broker_target)} tag combination so we don't
 * re-create them on every cycle — Micrometer dedupes by name+tags, but caching avoids the
 * lookup-and-register cost path.
 *
 * <p>Tag cardinality is bounded: {@code tenant} + {@code strategy} come from {@code
 * ReconciliationWorkflowInput}; {@code broker_target} is the {@code <provider>-<env>} enum value
 * already used by the workflow's routing. No per-cycle / per-correlation labels.
 */
@Component
public class ReconciliationMetricsActivitiesImpl implements ReconciliationMetricsActivities {

  static final String LAG_TIMER_NAME = "reconciliation_lag_seconds";
  static final String DISCREPANCIES_COUNTER_NAME = "journal_broker_discrepancies_total";
  static final String INTENTS_COUNTER_NAME = "journal_broker_intents_reconciled_total";
  // Plan-2A R-AA-4: recon.auto_adopt.{initiated,already_owned,refused_not_held} — one counter,
  // distinguished by the `outcome` tag (Prometheus convention; queryable per outcome).
  static final String AUTO_ADOPT_COUNTER_NAME = "recon_auto_adopt_total";

  private static final Logger log =
      LoggerFactory.getLogger(ReconciliationMetricsActivitiesImpl.class);

  private final MeterRegistry meterRegistry;
  private final ConcurrentMap<String, Timer> lagTimers = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> discrepancyCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> intentCounters = new ConcurrentHashMap<>();
  // Keyed on (tenant|strategy|broker_target|outcome) so each outcome gets its own counter.
  private final ConcurrentMap<String, Counter> autoAdoptCounters = new ConcurrentHashMap<>();

  public ReconciliationMetricsActivitiesImpl(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void recordCycle(
      String tenantId,
      String strategyId,
      String brokerTarget,
      long lagMillis,
      long discrepancies,
      long intentsReconciled) {
    String key = tagKey(tenantId, strategyId, brokerTarget);

    Timer lag =
        lagTimers.computeIfAbsent(
            key,
            k ->
                Timer.builder(LAG_TIMER_NAME)
                    .description("Reconciliation cycle wall-clock duration (Phase 7 gate signal).")
                    .tag("tenant", tenantId)
                    .tag("strategy", strategyId)
                    .tag("broker_target", brokerTarget)
                    .publishPercentileHistogram(true)
                    .register(meterRegistry));
    lag.record(Duration.ofMillis(lagMillis));

    Counter disc =
        discrepancyCounters.computeIfAbsent(
            key,
            k ->
                Counter.builder(DISCREPANCIES_COUNTER_NAME)
                    .description(
                        "Sum of journal+broker orphans per reconciliation cycle (Phase 7 gate signal).")
                    .tag("tenant", tenantId)
                    .tag("strategy", strategyId)
                    .tag("broker_target", brokerTarget)
                    .register(meterRegistry));
    disc.increment(discrepancies);

    Counter intents =
        intentCounters.computeIfAbsent(
            key,
            k ->
                Counter.builder(INTENTS_COUNTER_NAME)
                    .description(
                        "Intents reconciled per reconciliation cycle (= journal_entries_checked).")
                    .tag("tenant", tenantId)
                    .tag("strategy", strategyId)
                    .tag("broker_target", brokerTarget)
                    .register(meterRegistry));
    intents.increment(intentsReconciled);

    log.debug(
        "reconciliation metrics recorded tenant={} strategy={} broker_target={} lag_ms={} discrepancies={} intents={}",
        tenantId,
        strategyId,
        brokerTarget,
        lagMillis,
        discrepancies,
        intentsReconciled);
  }

  @Override
  public void recordAutoAdopt(
      String tenantId, String strategyId, String brokerTarget, String outcome) {
    String key = tagKey(tenantId, strategyId, brokerTarget) + "|" + outcome;
    Counter c =
        autoAdoptCounters.computeIfAbsent(
            key,
            k ->
                Counter.builder(AUTO_ADOPT_COUNTER_NAME)
                    .description(
                        "Recon auto-adoption decisions for orphaned FILLED positions, by outcome "
                            + "(initiated|already_owned|refused_not_held). Plan-2A R-AA-4.")
                    .tag("tenant", tenantId)
                    .tag("strategy", strategyId)
                    .tag("broker_target", brokerTarget)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
    c.increment();
    log.debug(
        "recon auto-adopt metric tenant={} strategy={} broker_target={} outcome={}",
        tenantId,
        strategyId,
        brokerTarget,
        outcome);
  }

  private static String tagKey(String tenantId, String strategyId, String brokerTarget) {
    return tenantId + "|" + strategyId + "|" + brokerTarget;
  }
}
