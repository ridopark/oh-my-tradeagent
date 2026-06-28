package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.BrokerTargetValidator;
import com.ohmytradeagent.orchestrator.workflows.ReconciliationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On Spring start, ensures one {@code ReconciliationWorkflow} Schedule per (tenant, strategy)
 * pinned to the broker task queue derived from the strategy's own {@code broker_target}. The
 * interval is 5 minutes per {@code PLAN.md} reconciliation flow.
 *
 * <p>{@link ScheduleAlreadyRunningException} on the create call is treated as a benign warm-boot —
 * the schedule already exists from a prior launch.
 *
 * <p>Phase 2c.2: previously hardcoded {@code "alpaca-paper"} for every tenant. Now reads {@code
 * broker_target} off each {@link StrategyConfig} so a future tenant pinned to a different broker
 * target gets a schedule on the correct task queue.
 *
 * <p>Issue #56 (final item): before creating a schedule, reap any pre-existing schedule for the
 * same {@code (tenantId, strategyId)} pair whose ID encodes a different {@code broker_target} from
 * the current config. This closes a drift bug where a {@code broker_target} rename (e.g. {@code
 * paper} → {@code alpaca-paper}) would leave the old schedule running on the prior broker queue,
 * effectively reconciling each strategy twice per interval. Reap is fail-closed: if the
 * StrategyConfig can't be loaded or is whitelist-invalid, the bootstrapper skips the reap pass for
 * that iteration to avoid blind deletes while the configuration is in an unknown state.
 */
@Component
@Profile("!test")
public class ReconciliationScheduleBootstrapper implements ApplicationRunner {

  private static final Logger log =
      LoggerFactory.getLogger(ReconciliationScheduleBootstrapper.class);

  static final String CORE_TASK_QUEUE = "orchestrator-core";
  static final Duration INTERVAL = Duration.ofMinutes(5);

  private final WorkflowClient workflowClient;
  private final WorkflowServiceStubs serviceStubs;
  private final StrategyRegistry strategyRegistry;
  private final Path tenantsDir;

  public ReconciliationScheduleBootstrapper(
      WorkflowClient workflowClient,
      WorkflowServiceStubs serviceStubs,
      StrategyRegistry strategyRegistry,
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.workflowClient = workflowClient;
    this.serviceStubs = serviceStubs;
    this.strategyRegistry = strategyRegistry;
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping Reconciliation Schedule bootstrap", tenantsDir);
      return;
    }
    runWith(newScheduleClient());
  }

  /**
   * Package-private hook for tests so we can drive the bootstrapper against a mock {@link
   * ScheduleClient} without standing up a Temporal server.
   *
   * <p>Issue #110: {@code listSchedules()} is hoisted out of the per-strategy loop and collected
   * lazily on first need. For N strategies sharing M schedules this reduces the Temporal frontend
   * round-trips from O(N×M) to O(M) — and early-exit iterations (whitelist-reject, missing
   * StrategyConfig) still incur zero list RPCs because the snapshot is only collected when the
   * first valid strategy reaches the reap step.
   */
  void runWith(ScheduleClient scheduleClient) {
    List<ScheduleListDescription> existingSchedules = null;
    for (TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      String brokerTarget = resolveValidBrokerTarget(ts.tenantId(), ts.strategyId());
      if (brokerTarget == null) {
        continue;
      }
      String desiredScheduleId =
          "recon-v2-t-" + ts.tenantId() + "-s-" + ts.strategyId() + "-" + brokerTarget;
      if (existingSchedules == null) {
        // Lazy: only call listSchedules() once the first valid strategy reaches the reap step.
        // try-with-resources closes the SDK Stream exactly once per bootstrap pass.
        try (Stream<ScheduleListDescription> listed = scheduleClient.listSchedules()) {
          existingSchedules = listed.collect(Collectors.toUnmodifiableList());
        }
      }
      reapStaleSchedules(
          scheduleClient, existingSchedules, ts.tenantId(), ts.strategyId(), desiredScheduleId);
      ensureSchedule(scheduleClient, ts.tenantId(), ts.strategyId(), brokerTarget);
    }
  }

  /**
   * Builds a {@link ScheduleClient} bound to the {@link WorkflowClient}'s namespace. Extracted from
   * {@link #run} so {@code TenantReconcileLoop} can ensure a single tenant's schedule on a tick
   * without re-running the full boot scan. (The no-options {@code ScheduleClient.newInstance} form
   * silently defaults to namespace "default" — always bind explicitly.)
   */
  ScheduleClient newScheduleClient() {
    String namespace = workflowClient.getOptions().getNamespace();
    ScheduleClientOptions opts = ScheduleClientOptions.newBuilder().setNamespace(namespace).build();
    return ScheduleClient.newInstance(serviceStubs, opts);
  }

  /**
   * Idempotent per-{@code (tenant, strategy)} ensure: loads the strategy's {@code broker_target}
   * via the active {@link StrategyRegistry}, validates it against the whitelist, and creates the
   * reconciliation Schedule if absent ({@link ScheduleAlreadyRunningException} is swallowed as a
   * benign warm/repeat). Shared by the boot path's per-strategy logic and {@code
   * TenantReconcileLoop} so a runtime-inserted tenant gets a recon schedule without a restart.
   *
   * <p>Unlike the boot {@link #run} pass this does NOT reap stale schedules — a newly enumerated
   * tenant has no prior {@code broker_target}-renamed schedules to reap; broker-target-rename
   * cleanup stays a boot-only concern. Returns silently (logs) on a missing/whitelist-invalid
   * config so a single bad tenant can't wedge the loop.
   *
   * <p>Returns {@code true} only when the schedule is confirmed present (freshly created or
   * already-running); {@code false} if the config is missing/whitelist-invalid or the create hit an
   * unexpected error. The reconcile loop uses this to retry a pair next tick rather than latch it
   * as done after a transient failure.
   */
  boolean ensureForTenantStrategy(
      ScheduleClient scheduleClient, String tenantId, String strategyId) {
    String brokerTarget = resolveValidBrokerTarget(tenantId, strategyId);
    if (brokerTarget == null) {
      return false;
    }
    return ensureSchedule(scheduleClient, tenantId, strategyId, brokerTarget);
  }

  /**
   * Loads the strategy's {@code broker_target} via the active {@link StrategyRegistry} and
   * validates it against the whitelist. Returns the validated {@code broker_target}, or {@code
   * null} (after logging the reason) when the config is missing, declares no {@code broker_target},
   * or is whitelist-invalid. Shared by the boot {@link #runWith} pass and {@link
   * #ensureForTenantStrategy} so both apply one fail-closed resolution policy — a single bad tenant
   * logs and is skipped rather than wedging the caller.
   */
  private String resolveValidBrokerTarget(String tenantId, String strategyId) {
    String brokerTarget;
    try {
      StrategyConfig cfg = strategyRegistry.get(tenantId, strategyId);
      if (cfg.getBrokerTarget() == null) {
        log.error(
            "tenant={} strategy={}: broker_target missing in StrategyConfig; skipping schedule",
            tenantId,
            strategyId);
        return null;
      }
      brokerTarget = cfg.getBrokerTarget().value();
    } catch (RuntimeException e) {
      log.error(
          "tenant={} strategy={}: failed to load StrategyConfig; skipping schedule",
          tenantId,
          strategyId,
          e);
      return null;
    }
    if (!BrokerTargetValidator.isValid(brokerTarget)) {
      log.error(
          "tenant={} strategy={}: broker_target {} rejected by whitelist; skipping schedule",
          tenantId,
          strategyId,
          brokerTarget);
      return null;
    }
    return brokerTarget;
  }

  /**
   * Deletes any existing Temporal schedule for {@code (tenantId, strategyId)} whose ID encodes a
   * different {@code broker_target} than the desired one. Match key is the prefix {@code
   * "recon-v2-t-<tenantId>-s-<strategyId>-"} — the schedule-ID grammar built in {@link
   * #ensureSchedule}. The trailing dash prevents tenant-prefix collisions (e.g. {@code dev} vs
   * {@code dev-other}).
   *
   * <p>A "schedule not found" race (two replicas reaping simultaneously) is logged at warn level;
   * any other {@link RuntimeException} from the SDK is logged and the iteration continues so a
   * single stale schedule can't take down the whole bootstrap pass.
   *
   * <p>Issue #110: callers pass a pre-collected snapshot of existing schedules so the full
   * namespace listing happens once per bootstrap pass instead of once per strategy. The {@code
   * scheduleClient} parameter is still required for the per-stale-entry {@code
   * getHandle(staleId).delete()} step, which remains O(stale-entries) and is unavoidable.
   *
   * <p>Package-private so unit tests can drive it directly against a mock {@link ScheduleClient}.
   */
  void reapStaleSchedules(
      ScheduleClient scheduleClient,
      List<ScheduleListDescription> existingSchedules,
      String tenantId,
      String strategyId,
      String desiredScheduleId) {
    String prefix = "recon-v2-t-" + tenantId + "-s-" + strategyId + "-";
    for (ScheduleListDescription d : existingSchedules) {
      String staleId = d.getScheduleId();
      if (!staleId.startsWith(prefix) || staleId.equals(desiredScheduleId)) {
        continue;
      }
      try {
        ScheduleHandle handle = scheduleClient.getHandle(staleId);
        handle.delete();
        log.info(
            "reaped stale Reconciliation Schedule id={} (desired={}) tenant={} strategy={}",
            staleId,
            desiredScheduleId,
            tenantId,
            strategyId);
      } catch (RuntimeException e) {
        log.warn(
            "could not reap stale Reconciliation Schedule id={} (desired={}); peer race or already removed",
            staleId,
            desiredScheduleId,
            e);
      }
    }
  }

  /**
   * Why "v2" in the prefix: a {@code recon-t-<tenant>-s-<strategy>-<broker>} schedule on the
   * homelab cluster fell into a zombie state where {@code listSchedules()} returned empty but
   * {@code createSchedule()} still threw {@code ScheduleAlreadyRunningException} — the underlying
   * scheduler workflow was gone, but Temporal's internal schedule registry kept a stub entry that
   * blocked re-creation under the same ID. Bumping the prefix to {@code recon-v2-t-} side-steps the
   * registry collision; the old zombie metadata stays in Temporal forever but is inert (doesn't
   * fire, doesn't appear in listings, doesn't consume resources).
   */
  private boolean ensureSchedule(
      ScheduleClient scheduleClient, String tenantId, String strategyId, String brokerTarget) {
    String scheduleId = "recon-v2-t-" + tenantId + "-s-" + strategyId + "-" + brokerTarget;
    String wfIdPrefix = WorkflowIds.reconciliationPrefix(tenantId, strategyId, brokerTarget);

    ReconciliationWorkflowInput input = new ReconciliationWorkflowInput();
    input.setSchemaVersion(1L);
    input.setTenantId(tenantId);
    input.setStrategyId(strategyId);
    input.setBrokerTarget(ReconciliationWorkflowInput.BrokerTarget.fromValue(brokerTarget));

    Map<String, Object> sa = new HashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenantId, strategyId));

    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowId(wfIdPrefix + "{{.ScheduledRunID}}")
            .setTaskQueue(CORE_TASK_QUEUE)
            .setSearchAttributes(sa)
            .build();

    ScheduleActionStartWorkflow action =
        ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType(ReconciliationWorkflow.class)
            .setArguments(input)
            .setOptions(wfOptions)
            .build();

    ScheduleSpec spec =
        ScheduleSpec.newBuilder().setIntervals(List.of(new ScheduleIntervalSpec(INTERVAL))).build();

    Schedule schedule = Schedule.newBuilder().setAction(action).setSpec(spec).build();

    try {
      scheduleClient.createSchedule(scheduleId, schedule, ScheduleOptions.newBuilder().build());
      log.info(
          "created Reconciliation Schedule id={} tenant={} strategy={} broker_target={}",
          scheduleId,
          tenantId,
          strategyId,
          brokerTarget);
      return true;
    } catch (ScheduleAlreadyRunningException already) {
      log.info("Reconciliation Schedule id={} already exists (warm boot)", scheduleId);
      return true;
    } catch (RuntimeException e) {
      log.error("failed to create Reconciliation Schedule id={}", scheduleId, e);
      return false;
    }
  }
}
