package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.TenantDeleteActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator tenant-delete teardown impl (PLAN-2026-07-03). Phase 4 makes this the REAL-MONEY safety
 * gate: the two orchestrator-reachable live-safety gates run FIRST — BEFORE any teardown — and a
 * failure of either (or a fail-closed read fault) returns {@link TenantDeleteResult.Status#BLOCKED}
 * with ZERO teardown.
 *
 * <ul>
 *   <li><b>P4 BROKER_NOT_FLAT</b> — the broker must report zero open positions AND zero
 *       open/pending orders. Read via the SAME {@link ReconciliationExecActivity} path the
 *       reconciliation workflow uses ({@link ReconciliationExecActivity#brokerListOpenPositions} +
 *       {@link ReconciliationExecActivity#brokerListOpenOrders}), routed to the {@code
 *       broker-<target>} queue via {@link ExecActivitiesFactory#taskQueueFor}. A read fault fails
 *       closed → BLOCKED.
 *   <li><b>P5 HAS_TRADE_HISTORY</b> — {@code order_intent_journal} must be empty for the tenant
 *       (never placed an order). Read via {@link ReconciliationExecActivity#journalCountByTenant}
 *       (all-states count). A non-zero count, or a read fault, → BLOCKED.
 * </ul>
 *
 * <p>Only when BOTH gates pass does the teardown a → b → b' → c run (reap recon schedules by prefix
 * → terminate the per-(tenant,strategy) kill-switch workflow → terminate the tenant-level account
 * kill-switch workflow → delete {@code strategy_config} + retained {@code TenantDeleted}
 * tombstone). The account switch reap (b') is unconditional: because the route is
 * single-strategy-only (api-gateway {@code MULTI_STRATEGY_UNSUPPORTED} guard), deleting the
 * strategy == deleting the tenant, so the tenant-scoped account switch is an orphan and must be
 * reaped too. Order within the teardown is not load-bearing (step (a) reaps by {@code (tenant,
 * strategy)} prefix, never reading {@code broker_target} from the config row).
 *
 * <p>Determinism: no {@code Instant.now}/{@code UUID}/non-deterministic iteration in the body —
 * timestamps come from {@link Workflow#currentTimeMillis} and ids from {@link Workflow#randomUUID}.
 * All IO is through Activities; each Activity is idempotent so a retried teardown converges.
 * Net-new workflow type → no {@code Workflow.getVersion} change-point needed.
 */
public class TenantDeleteWorkflowImpl implements TenantDeleteWorkflow {

  private static final Duration ACTIVITY_TIMEOUT = Duration.ofSeconds(30);
  private static final RetryOptions BOUNDED_RETRY =
      RetryOptions.newBuilder().setMaximumAttempts(3).build();

  private final TenantDeleteActivities activities =
      Workflow.newActivityStub(
          TenantDeleteActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(ACTIVITY_TIMEOUT)
              .setRetryOptions(BOUNDED_RETRY)
              .build());

  // Emits the TenantDeleteBlocked tombstone when a gate refuses. Inherits this workflow's task
  // queue
  // (orchestrator-core), where the AuditActivities impl is registered. Bounded retry so a
  // persistent
  // audit-write fault surfaces rather than looping forever.
  private final AuditActivities audit =
      Workflow.newActivityStub(
          AuditActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(ACTIVITY_TIMEOUT)
              .setRetryOptions(BOUNDED_RETRY)
              .build());

  @Override
  public TenantDeleteResult deleteTenant(
      String tenantId, String strategyId, String brokerTarget, String actor) {

    // The P4/P5 gate activities route to the broker task queue (broker-<broker_target>), exactly
    // like ReconciliationWorkflow. brokerTarget is the stored strategy_config value the api-gateway
    // resolved (and already proved paper in P0). taskQueueFor fails fast (non-retryable) on a
    // null/blank/legacy target — a misconfigured teardown never hangs.
    ReconciliationExecActivity exec =
        Workflow.newActivityStub(
            ReconciliationExecActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(ACTIVITY_TIMEOUT)
                .setRetryOptions(BOUNDED_RETRY)
                .build());

    // ---- P4 BROKER_NOT_FLAT (fail-closed) ----
    try {
      List<BrokerPosition> positions = exec.brokerListOpenPositions(tenantId, strategyId);
      List<BrokerOpenOrder> openOrders = exec.brokerListOpenOrders(tenantId, strategyId);
      // A NULL list is an UNKNOWN broker read, not proof of flatness — never treat an unknown/null
      // read as flat. Fail closed to BLOCKED, same as a >0 count or a read fault.
      if (positions == null) {
        return blocked(
            TenantDeleteResult.BlockReason.BROKER_NOT_FLAT,
            tenantId,
            strategyId,
            actor,
            "broker returned null positions (fail-closed)");
      }
      if (openOrders == null) {
        return blocked(
            TenantDeleteResult.BlockReason.BROKER_NOT_FLAT,
            tenantId,
            strategyId,
            actor,
            "broker returned null orders (fail-closed)");
      }
      int posCount = positions.size();
      int orderCount = openOrders.size();
      if (posCount > 0 || orderCount > 0) {
        return blocked(
            TenantDeleteResult.BlockReason.BROKER_NOT_FLAT,
            tenantId,
            strategyId,
            actor,
            "broker not flat: open_positions=" + posCount + " open_orders=" + orderCount);
      }
    } catch (ActivityFailure e) {
      // A broker read that faulted leaves flatness UNKNOWN — never treat unknown as flat. Block.
      return blocked(
          TenantDeleteResult.BlockReason.BROKER_NOT_FLAT,
          tenantId,
          strategyId,
          actor,
          "broker flatness read faulted (fail-closed): " + e.getClass().getSimpleName());
    }

    // ---- P5 HAS_TRADE_HISTORY (fail-closed) ----
    try {
      long journalRows = exec.journalCountByTenant(tenantId, strategyId);
      if (journalRows > 0) {
        return blocked(
            TenantDeleteResult.BlockReason.HAS_TRADE_HISTORY,
            tenantId,
            strategyId,
            actor,
            "order_intent_journal not empty: rows=" + journalRows);
      }
    } catch (ActivityFailure e) {
      // A journal read that faulted leaves trade-history UNKNOWN — never treat unknown as
      // never-traded. Block.
      return blocked(
          TenantDeleteResult.BlockReason.HAS_TRADE_HISTORY,
          tenantId,
          strategyId,
          actor,
          "trade-history read faulted (fail-closed): " + e.getClass().getSimpleName());
    }

    // ---- Both gates passed → teardown a → b → b' → c ----
    // (a) reap every recon schedule under the (tenant, strategy) prefix.
    activities.deleteReconSchedules(tenantId, strategyId);
    // (b) terminate the per-(tenant, strategy) kill-switch workflow.
    activities.terminateKillSwitchWorkflow(tenantId, strategyId);
    // (b') terminate the tenant-level account kill-switch workflow. UNCONDITIONAL: the api-gateway
    // MULTI_STRATEGY_UNSUPPORTED guard means deleting this (only) strategy == deleting the whole
    // tenant, so its account-level switch is now an orphan and must be reaped too. Idempotent
    // (absent/already-terminated = success). Order within the teardown is not load-bearing.
    activities.terminateAccountKillSwitchWorkflow(tenantId);
    // (c) delete the config row (+ retained TenantDeleted tombstone).
    int deleted = activities.deleteStrategyConfig(tenantId, strategyId, actor);
    return TenantDeleteResult.completed(deleted);
  }

  /**
   * Emits the {@code TenantDeleteBlocked} audit tombstone and returns the BLOCKED result. Emitting
   * from the workflow (not a teardown activity) keeps the refusal on the append-only audit trail
   * even though ZERO teardown runs.
   */
  private TenantDeleteResult blocked(
      TenantDeleteResult.BlockReason reason,
      String tenantId,
      String strategyId,
      String actor,
      String detail) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(tenantId);
    e.setStrategyId(strategyId);
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(
        OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
    e.setKind("TenantDeleteBlocked");
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("blocked_by", reason.name());
    subject.put("gate", reason == TenantDeleteResult.BlockReason.BROKER_NOT_FLAT ? "P4" : "P5");
    subject.put("detail", detail);
    e.setSubject(subject);
    e.setActor(actor);
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(tenantId + "/" + strategyId);
    audit.log(e);
    return TenantDeleteResult.blocked(reason);
  }
}
