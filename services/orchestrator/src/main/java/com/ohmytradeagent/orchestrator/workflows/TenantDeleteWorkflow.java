package com.ohmytradeagent.orchestrator.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Operator tenant-delete teardown carrier (PLAN-2026-07-03, Phase 2) — the DARK durable primitive
 * the api-gateway will invoke per-strategy in Phase 4. Tears down ONE {@code (tenant, strategy)}
 * unit by running three individually-idempotent Activities: (a) reap every recon schedule under the
 * {@code (tenant, strategy)} prefix, (b) terminate the kill-switch workflow, (c) delete the {@code
 * strategy_config} row (writing the retained {@code TenantDeleted} tombstone).
 *
 * <p><b>No {@code Workflow.getVersion} change-point</b> — a net-new workflow type started fresh per
 * call, so there is no long-lived history to version. The body reads no wall-clock / random and
 * does IO only through Activities, so replay determinism is trivially preserved.
 *
 * <p><b>Input shape is per-{@code (tenant, strategy)}.</b> The three teardown targets are all keyed
 * that way — recon schedules share the id prefix {@code recon-v2-t-<tenant>-s-<strategy>-}, the
 * kill-switch workflow id is {@code WorkflowIds.killswitch(tenant, strategy)}, and the {@code
 * strategy_config} primary key is {@code (tenant_id, strategy_id)} — so one delete unit is one
 * {@code (tenant, strategy)}. A tenant with multiple strategies is torn down by the api-gateway
 * invoking this workflow once per strategy.
 */
@WorkflowInterface
public interface TenantDeleteWorkflow {

  /**
   * Run the teardown steps a → b → c. {@code actor} is threaded to the config-delete audit
   * tombstone ({@code operator:<id>}). Returns the rows-deleted count from step (c) (0 when already
   * absent).
   */
  @WorkflowMethod
  int deleteTenant(String tenantId, String strategyId, String actor);
}
