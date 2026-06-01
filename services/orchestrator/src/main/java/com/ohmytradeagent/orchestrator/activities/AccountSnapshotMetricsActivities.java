package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Issue #323 observability-only signal source: instruments the {@code AccountSnapshotActivity}
 * dispatch-failure path that {@link
 * com.ohmytradeagent.orchestrator.workflows.CopytradeSignalWorkflowImpl#dispatchAccountSnapshot}
 * fails closed on. The symmetric counterpart to the #329 {@code openpositions_value_failures_total}
 * counter ({@link VisibilityPortfolioSnapshot#VALUE_FAILURES_COUNTER_NAME}): without it a
 * persistent broker outage (Alpaca 401/5xx, retry exhaustion) that drives the cash term to the
 * {@code ZERO} fail-closed sentinel is indistinguishable in metrics from a legitimate zero-cash
 * account.
 *
 * <p>Workflows in Temporal must stay deterministic, so {@code MeterRegistry} cannot be called from
 * inside the workflow body — the workflow invokes this Activity from the catch block and the
 * Activity holds the registry. This mirrors the #89 {@link ReconciliationMetricsActivities}
 * metrics-Activity pattern; the increment lives in an Activity (like #329) rather than the
 * deterministic workflow path.
 *
 * <p>Argument list is deliberately primitive (no contract DTO) — this is additive instrumentation,
 * not a contract change.
 */
@ActivityInterface
public interface AccountSnapshotMetricsActivities {

  /**
   * Records one account-snapshot dispatch failure. Increments counter {@code
   * accountsnapshot_dispatch_failures_total}, tagged {@code broker_target} (the account read is
   * account-level, so no tenant/strategy labels). Called once per non-{@code CanceledFailure}
   * exception escaping the {@code AccountSnapshotActivity} dispatch after Temporal's retries are
   * exhausted.
   */
  void recordDispatchFailure(String brokerTarget);
}
