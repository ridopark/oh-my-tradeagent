package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.workflows.BrokerTargetValidator;
import com.ohmytradeagent.orchestrator.workflows.ReconciliationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
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
    runWith(ScheduleClient.newInstance(serviceStubs));
  }

  /**
   * Package-private hook for tests so we can drive the bootstrapper against a mock {@link
   * ScheduleClient} without standing up a Temporal server.
   */
  void runWith(ScheduleClient scheduleClient) {
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      String brokerTarget;
      try {
        StrategyConfig cfg = strategyRegistry.get(ts.tenantId(), ts.strategyId());
        if (cfg.getBrokerTarget() == null) {
          log.error(
              "tenant={} strategy={}: broker_target missing in StrategyConfig; skipping schedule",
              ts.tenantId(),
              ts.strategyId());
          continue;
        }
        brokerTarget = cfg.getBrokerTarget().value();
      } catch (RuntimeException e) {
        log.error(
            "tenant={} strategy={}: failed to load StrategyConfig; skipping schedule",
            ts.tenantId(),
            ts.strategyId(),
            e);
        continue;
      }
      if (!BrokerTargetValidator.isValid(brokerTarget)) {
        log.error(
            "tenant={} strategy={}: broker_target {} rejected by whitelist; skipping schedule",
            ts.tenantId(),
            ts.strategyId(),
            brokerTarget);
        continue;
      }
      String desiredScheduleId =
          "recon-t-" + ts.tenantId() + "-s-" + ts.strategyId() + "-" + brokerTarget;
      reapStaleSchedules(scheduleClient, ts.tenantId(), ts.strategyId(), desiredScheduleId);
      ensureSchedule(scheduleClient, ts.tenantId(), ts.strategyId(), brokerTarget);
    }
  }

  /**
   * Deletes any existing Temporal schedule for {@code (tenantId, strategyId)} whose ID encodes a
   * different {@code broker_target} than the desired one. Match key is the prefix {@code
   * "recon-t-<tenantId>-s-<strategyId>-"} — the schedule-ID grammar built in {@link
   * #ensureSchedule}. The trailing dash prevents tenant-prefix collisions (e.g. {@code dev} vs
   * {@code dev-other}).
   *
   * <p>A "schedule not found" race (two replicas reaping simultaneously) is logged at warn level;
   * any other {@link RuntimeException} from the SDK is logged and the iteration continues so a
   * single stale schedule can't take down the whole bootstrap pass.
   *
   * <p>Package-private so unit tests can drive it directly against a mock {@link ScheduleClient}.
   */
  void reapStaleSchedules(
      ScheduleClient scheduleClient, String tenantId, String strategyId, String desiredScheduleId) {
    String prefix = "recon-t-" + tenantId + "-s-" + strategyId + "-";
    try (Stream<ScheduleListDescription> listed = scheduleClient.listSchedules()) {
      listed
          .filter(d -> d.getScheduleId().startsWith(prefix))
          .filter(d -> !d.getScheduleId().equals(desiredScheduleId))
          .forEach(
              d -> {
                String staleId = d.getScheduleId();
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
              });
    }
  }

  private void ensureSchedule(
      ScheduleClient scheduleClient, String tenantId, String strategyId, String brokerTarget) {
    String scheduleId = "recon-t-" + tenantId + "-s-" + strategyId + "-" + brokerTarget;
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
    } catch (ScheduleAlreadyRunningException already) {
      log.info("Reconciliation Schedule id={} already exists (warm boot)", scheduleId);
    } catch (RuntimeException e) {
      log.error("failed to create Reconciliation Schedule id={}", scheduleId, e);
    }
  }
}
