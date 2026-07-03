package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete teardown impl (PLAN-2026-07-03, Phase 2). Each step is idempotent: an
 * absent recon schedule, an absent/already-terminated kill-switch workflow, and an absent {@code
 * strategy_config} row all yield success so a retried {@code TenantDeleteWorkflow} converges.
 *
 * <p>The recon schedule id grammar ({@code recon-v2-t-<tenant>-s-<strategy>-<brokerTarget>}) and
 * the not-found-is-benign handling mirror {@code ReconciliationScheduleBootstrapper} exactly — the
 * two MUST stay in lockstep or a rename would orphan the schedule this reaps. The kill-switch id
 * comes from {@link WorkflowIds#killswitch}, the single source of truth the bootstrapper starts it
 * under.
 */
@Component
public class TenantDeleteActivitiesImpl implements TenantDeleteActivities {

  private static final Logger log = LoggerFactory.getLogger(TenantDeleteActivitiesImpl.class);

  private final StrategyRegistry strategyRegistry;
  private final StrategyConfigWriter writer;
  private final WorkflowClient workflowClient;
  private final WorkflowServiceStubs serviceStubs;

  public TenantDeleteActivitiesImpl(
      StrategyRegistry strategyRegistry,
      StrategyConfigWriter writer,
      WorkflowClient workflowClient,
      WorkflowServiceStubs serviceStubs) {
    this.strategyRegistry = strategyRegistry;
    this.writer = writer;
    this.workflowClient = workflowClient;
    this.serviceStubs = serviceStubs;
  }

  @Override
  public void resolveBrokerTargetAndDeleteReconSchedule(String tenantId, String strategyId) {
    // ORDERING TRAP: broker_target MUST be resolved from the config row BEFORE step (c) deletes it
    // —
    // the schedule id is otherwise uncomputable (zombie schedule). Within one workflow run this
    // activity precedes deleteStrategyConfig, so the row is present. On a full re-invocation after
    // a
    // prior completed teardown the row is gone; we cannot (and need not) compute the id — the
    // schedule was already reaped by the prior run, so we no-op as success.
    String brokerTarget;
    try {
      StrategyConfig cfg = strategyRegistry.get(tenantId, strategyId);
      if (cfg == null || cfg.getBrokerTarget() == null) {
        log.info(
            "tenant delete: no config / broker_target for tenant={} strategy={}; recon schedule "
                + "delete is a no-op (already torn down)",
            tenantId,
            strategyId);
        return;
      }
      brokerTarget = cfg.getBrokerTarget().value();
    } catch (RuntimeException e) {
      // Config already absent (a prior teardown ran) — the recon schedule was reaped with it.
      log.info(
          "tenant delete: config unresolved for tenant={} strategy={}; recon schedule delete is a "
              + "no-op (already torn down)",
          tenantId,
          strategyId);
      return;
    }

    String scheduleId = "recon-v2-t-" + tenantId + "-s-" + strategyId + "-" + brokerTarget;
    try {
      scheduleClient().getHandle(scheduleId).delete();
      log.info(
          "tenant delete: reaped recon schedule id={} tenant={} strategy={}",
          scheduleId,
          tenantId,
          strategyId);
    } catch (RuntimeException e) {
      if (isNotFound(e)) {
        log.info(
            "tenant delete: recon schedule id={} already absent tenant={} strategy={} — idempotent",
            scheduleId,
            tenantId,
            strategyId);
        return;
      }
      throw e;
    }
  }

  @Override
  public void terminateKillSwitchWorkflow(String tenantId, String strategyId) {
    String wfId = WorkflowIds.killswitch(tenantId, strategyId);
    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(wfId);
      stub.terminate("tenant_delete_teardown");
      log.info(
          "tenant delete: terminated kill-switch workflow id={} tenant={} strategy={}",
          wfId,
          tenantId,
          strategyId);
    } catch (WorkflowNotFoundException e) {
      // Absent or already terminated/completed — the desired end-state. Idempotent success.
      log.info(
          "tenant delete: kill-switch workflow id={} absent/terminated tenant={} strategy={} — "
              + "idempotent",
          wfId,
          tenantId,
          strategyId);
    } catch (RuntimeException e) {
      if (isNotFound(e)) {
        log.info(
            "tenant delete: kill-switch workflow id={} not found tenant={} strategy={} — idempotent",
            wfId,
            tenantId,
            strategyId);
        return;
      }
      throw e;
    }
  }

  @Override
  public int deleteStrategyConfig(String tenantId, String strategyId, String actor) {
    return writer.delete(tenantId, strategyId, actor);
  }

  /**
   * Builds a {@link ScheduleClient} bound to the {@link WorkflowClient}'s namespace, mirroring
   * {@code ReconciliationScheduleBootstrapper#newScheduleClient} (the no-options form silently
   * defaults to namespace "default" — always bind explicitly). Package-private + overridable so a
   * unit test can substitute a mock without a Temporal server.
   */
  ScheduleClient scheduleClient() {
    String namespace = workflowClient.getOptions().getNamespace();
    return ScheduleClient.newInstance(
        serviceStubs, ScheduleClientOptions.newBuilder().setNamespace(namespace).build());
  }

  /** True iff {@code e} (or a cause) is a gRPC {@code NOT_FOUND} — a benign already-gone signal. */
  private static boolean isNotFound(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof StatusRuntimeException sre
          && sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return true;
      }
    }
    return false;
  }
}
