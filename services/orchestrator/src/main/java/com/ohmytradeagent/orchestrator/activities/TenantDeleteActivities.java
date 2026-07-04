package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Operator tenant-delete teardown (PLAN-2026-07-03, Phase 2) — DARK durable-teardown primitive for
 * ONE {@code (tenant, strategy)} unit. Drives the {@code TenantDeleteWorkflow}'s three
 * individually-idempotent steps. Each is an Activity (not workflow body) because every step is IO
 * against a client boundary a deterministic workflow may not touch directly — the schedule client,
 * another workflow's lifecycle, and the DB.
 *
 * <p>All three are idempotent so a retried teardown converges: an absent recon schedule, an
 * absent/already-terminated kill-switch workflow, and an absent {@code strategy_config} row all
 * yield success rather than a fault.
 */
@ActivityInterface
public interface TenantDeleteActivities {

  /**
   * Step (a): reap EVERY reconciliation Temporal Schedule whose id starts with the {@code (tenant,
   * strategy)} prefix ({@code recon-v2-t-<tenant>-s-<strategy>-}) — regardless of its trailing
   * {@code broker_target} suffix. This needs only {@code (tenant, strategy)}, never {@code
   * broker_target}, so it does NOT read the {@code strategy_config} row and has no ordering
   * dependency on step (c). Reaping by prefix (mirroring {@code
   * ReconciliationScheduleBootstrapper.reapStaleSchedules}) also catches a schedule left under a
   * stale broker suffix. An absent schedule (already reaped) is swallowed per-id as success; a
   * genuine (non-not-found) delete error propagates so the bounded activity retry fires.
   */
  void deleteReconSchedules(String tenantId, String strategyId);

  /**
   * Step (b): terminate the per-{@code (tenant, strategy)} {@code KillSwitchWorkflow} ({@code
   * WorkflowIds.killswitch}). An absent or already-terminated/completed workflow is swallowed as
   * success.
   */
  void terminateKillSwitchWorkflow(String tenantId, String strategyId);

  /**
   * Step (b'): terminate the tenant-level {@code AccountKillSwitchWorkflow} ({@code
   * WorkflowIds.accountKillswitch}). The account switch is tenant-scoped (spans EVERY strategy on
   * the tenant's shared {@code broker_target}), so reaping it is UNCONDITIONAL here: the
   * api-gateway {@code MULTI_STRATEGY_UNSUPPORTED} guard enforces single-strategy, so deleting the
   * strategy == deleting the whole tenant. Same idempotent pattern as {@link
   * #terminateKillSwitchWorkflow}: an absent or already-terminated/completed workflow is swallowed
   * as success.
   */
  void terminateAccountKillSwitchWorkflow(String tenantId);

  /**
   * Step (c): delete the {@code strategy_config} row and write the retained {@code TenantDeleted}
   * tombstone (via {@code StrategyConfigWriter#delete}). Returns the rows-deleted count; 0 (already
   * absent) is a success.
   */
  int deleteStrategyConfig(String tenantId, String strategyId, String actor);
}
