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
 *
 * <p><b>Phase 4: the P4/P5 live-safety gates run FIRST, inside this workflow.</b> The coordinator
 * moved P4 ({@code BROKER_NOT_FLAT}) and P5 ({@code HAS_TRADE_HISTORY}) here from the api-gateway
 * because only orchestrator-core can reach the broker / exec journal (via the {@code
 * ReconciliationExecActivity} on the {@code broker-<broker_target>} queue). Either gate failing —
 * or any read faulting (fail-closed) — returns a {@link TenantDeleteResult.Status#BLOCKED} result
 * and runs ZERO teardown. Only when BOTH pass does the teardown a → b → c run.
 */
@WorkflowInterface
public interface TenantDeleteWorkflow {

  /**
   * Evaluate the P4/P5 live-safety gates then (only if both pass) run the teardown steps a → b → c.
   * {@code brokerTarget} is the stored {@code strategy_config.broker_target} (resolved by the
   * api-gateway from the config it already read for P0) — it pins the {@code broker-<target>} task
   * queue the P4/P5 gate activities route to. {@code actor} is threaded to the config-delete audit
   * tombstone ({@code operator:<id>}). Returns a {@link TenantDeleteResult}: BLOCKED (naming the
   * gate) with zero teardown, or COMPLETED with the step-(c) rows-deleted count.
   */
  @WorkflowMethod
  TenantDeleteResult deleteTenant(
      String tenantId, String strategyId, String brokerTarget, String actor);
}
