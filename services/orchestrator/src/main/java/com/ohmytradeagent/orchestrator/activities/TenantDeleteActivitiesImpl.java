package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.bootstrap.ReconciliationScheduleBootstrapper;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete teardown impl (PLAN-2026-07-03, Phase 2). Each step is idempotent: no
 * matching recon schedules, an absent/already-terminated kill-switch workflow, and an absent {@code
 * strategy_config} row all yield success so a retried {@code TenantDeleteWorkflow} converges.
 *
 * <p>Step (a) reaps recon schedules by the {@code (tenant, strategy)} PREFIX ({@link
 * ReconciliationScheduleBootstrapper#reconSchedulePrefix}) rather than by an exact id computed from
 * {@code broker_target}. That eliminates two failure modes of the old exact-id approach: (1) it no
 * longer reads the {@code strategy_config} row, so a transient registry/DB error can never be
 * mistaken for "already torn down" and leak a zombie schedule that fires forever; and (2) it
 * catches a schedule left under a DIFFERENT broker suffix (e.g. after a {@code broker_target}
 * change). The kill-switch id comes from {@link WorkflowIds#killswitch}, the single source of truth
 * the bootstrapper starts it under. The not-found-is-benign handling matches the bootstrapper's
 * reap.
 */
@Component
public class TenantDeleteActivitiesImpl implements TenantDeleteActivities {

  private static final Logger log = LoggerFactory.getLogger(TenantDeleteActivitiesImpl.class);

  private final StrategyConfigWriter writer;
  private final WorkflowClient workflowClient;
  private final WorkflowServiceStubs serviceStubs;

  public TenantDeleteActivitiesImpl(
      StrategyConfigWriter writer,
      WorkflowClient workflowClient,
      WorkflowServiceStubs serviceStubs) {
    this.writer = writer;
    this.workflowClient = workflowClient;
    this.serviceStubs = serviceStubs;
  }

  @Override
  public void deleteReconSchedules(String tenantId, String strategyId) {
    // Reap by (tenant, strategy) PREFIX — never by an exact id derived from broker_target. This
    // needs no strategy_config read (so there is nothing to resolve-before-config-delete, and a
    // transient DB blip can never be swallowed as false "already torn down") and it reaps EVERY
    // schedule under the prefix, including one left under a stale broker suffix. Mirrors
    // ReconciliationScheduleBootstrapper.reapStaleSchedules'
    // list+prefix-filter+getHandle().delete().
    String prefix = ReconciliationScheduleBootstrapper.reconSchedulePrefix(tenantId, strategyId);
    ScheduleClient client = scheduleClient();
    List<String> matching;
    try (Stream<ScheduleListDescription> listed = client.listSchedules()) {
      matching =
          listed
              .map(ScheduleListDescription::getScheduleId)
              .filter(id -> id.startsWith(prefix))
              .collect(Collectors.toList());
    }
    int reaped = 0;
    for (String id : matching) {
      try {
        client.getHandle(id).delete();
        reaped++;
        log.info(
            "tenant delete: reaped recon schedule id={} tenant={} strategy={}",
            id,
            tenantId,
            strategyId);
      } catch (RuntimeException e) {
        if (isNotFound(e)) {
          // Already gone (a peer race or a prior partial run) — idempotent; keep reaping the rest.
          log.info(
              "tenant delete: recon schedule id={} already absent tenant={} strategy={} — idempotent",
              id,
              tenantId,
              strategyId);
          continue;
        }
        // A GENUINE (non-not-found) delete error MUST propagate so the bounded activity retry fires
        // — never swallow it as false success (that would leak a zombie schedule).
        throw e;
      }
    }
    log.info(
        "tenant delete: recon schedule reap complete tenant={} strategy={} matched={} reaped={}",
        tenantId,
        strategyId,
        matching.size(),
        reaped);
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
   * Builds a {@link ScheduleClient} bound to the {@link WorkflowClient}'s namespace via {@link
   * ReconciliationScheduleBootstrapper#scheduleClientForNamespace} — the single owner of the
   * "no-options newInstance silently defaults to the default namespace" footgun, so the binding
   * rule lives in one place. Package-private + overridable so a unit test can substitute a mock
   * without a Temporal server.
   */
  ScheduleClient scheduleClient() {
    return ReconciliationScheduleBootstrapper.scheduleClientForNamespace(
        serviceStubs, workflowClient.getOptions().getNamespace());
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
