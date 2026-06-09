package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Phase 7 live-promotion gate signal source (issue #89): instruments per-cycle reconciliation lag
 * plus journal/broker discrepancy + intent counts as Micrometer meters on the orchestrator's {@code
 * /actuator/prometheus} endpoint.
 *
 * <p>Workflows in Temporal must stay deterministic, so {@code MeterRegistry} cannot be called from
 * inside the workflow body — the workflow invokes this Activity once per cycle and the Activity
 * holds the registry.
 *
 * <p>Argument list is deliberately primitive (no contract DTO) — this issue is additive
 * instrumentation, not a contract change. See {@code docs/ops/reconciliation-metrics.md} for meter
 * names, labels, and the PromQL the Phase 7 gate operator runs daily.
 */
@ActivityInterface
public interface ReconciliationMetricsActivities {

  /**
   * Records one reconciliation cycle's metrics. Updates:
   *
   * <ul>
   *   <li>Timer {@code reconciliation_lag_seconds} (with {@code _bucket} for {@code
   *       histogram_quantile} p99 queries).
   *   <li>Counter {@code journal_broker_discrepancies_total} (= {@code journalOrphans +
   *       brokerOrphans}).
   *   <li>Counter {@code journal_broker_intents_reconciled_total} (= {@code intentsReconciled}).
   * </ul>
   *
   * <p>All three meters are tagged with {@code tenant}, {@code strategy}, {@code broker_target}.
   */
  void recordCycle(
      String tenantId,
      String strategyId,
      String brokerTarget,
      long lagMillis,
      long discrepancies,
      long intentsReconciled);

  /**
   * Plan-2A R-AA-4: records one recon auto-adoption decision as a counter increment. Emits {@code
   * recon_auto_adopt_total} tagged with {@code tenant}, {@code strategy}, {@code broker_target},
   * and {@code outcome} ∈ {@code {initiated, already_owned, refused_not_held}} — the {@code
   * recon.auto_adopt.{initiated,already_owned,refused_not_held}} metric family. A benign {@code
   * already_owned} / {@code refused_not_held} skip is NOT an alert (a just-closed or settling-close
   * lot racing the adopt); only {@code initiated} reflects a real ABANDON-child start.
   */
  void recordAutoAdopt(String tenantId, String strategyId, String brokerTarget, String outcome);
}
