package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Operator tenant-delete teardown (PLAN-2026-07-03, Phase 2) — DARK durable-teardown primitive for
 * ONE {@code (tenant, strategy)} unit. Drives the {@code TenantDeleteWorkflow}'s three ordered,
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
   * Step (a): resolve the strategy's {@code broker_target} from {@code strategy_config} FIRST, then
   * delete the reconciliation Temporal Schedule keyed on it. The broker_target MUST be read BEFORE
   * step (c) deletes the config row — the schedule id ({@code
   * recon-v2-t-<tenant>-s-<strategy>-<brokerTarget>}) is otherwise uncomputable, which would leave
   * a zombie schedule firing forever. An absent schedule (already reaped) is swallowed as success.
   */
  void resolveBrokerTargetAndDeleteReconSchedule(String tenantId, String strategyId);

  /**
   * Step (b): terminate the per-{@code (tenant, strategy)} {@code KillSwitchWorkflow} ({@code
   * WorkflowIds.killswitch}). An absent or already-terminated/completed workflow is swallowed as
   * success.
   */
  void terminateKillSwitchWorkflow(String tenantId, String strategyId);

  /**
   * Step (c): delete the {@code strategy_config} row and write the retained {@code TenantDeleted}
   * tombstone (via {@code StrategyConfigWriter#delete}). Returns the rows-deleted count; 0 (already
   * absent) is a success.
   */
  int deleteStrategyConfig(String tenantId, String strategyId, String actor);
}
