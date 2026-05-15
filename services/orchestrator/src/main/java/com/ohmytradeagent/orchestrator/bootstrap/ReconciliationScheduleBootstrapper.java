package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.ReconciliationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On Spring start, ensures one {@code ReconciliationWorkflow} Schedule per (tenant, strategy,
 * broker_target). v0 ships paper-only; live-target schedules wire on in Phase 7. The interval is 5
 * minutes per {@code PLAN.md} reconciliation flow.
 *
 * <p>{@link ScheduleAlreadyRunningException} on the create call is treated as a benign warm-boot —
 * the schedule already exists from a prior launch.
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
  private final Path tenantsDir;

  public ReconciliationScheduleBootstrapper(
      WorkflowClient workflowClient,
      WorkflowServiceStubs serviceStubs,
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.workflowClient = workflowClient;
    this.serviceStubs = serviceStubs;
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping Reconciliation Schedule bootstrap", tenantsDir);
      return;
    }
    ScheduleClient scheduleClient = ScheduleClient.newInstance(serviceStubs);
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      // v0: paper only. Live target wires in Phase 7 alongside exec-svc-tradier-live.
      ensureSchedule(scheduleClient, ts.tenantId(), ts.strategyId(), "paper");
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
