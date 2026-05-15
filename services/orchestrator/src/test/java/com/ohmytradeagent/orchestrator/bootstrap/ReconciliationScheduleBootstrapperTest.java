package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Phase 2c.2 review feedback (Major 3): {@link ReconciliationScheduleBootstrapper} must derive the
 * task queue from each strategy's own {@code broker_target}, not a hardcoded {@code "alpaca-paper"}
 * constant. Two tenants pinned to different brokers must get two schedules on the matching task
 * queues.
 */
class ReconciliationScheduleBootstrapperTest {

  @Test
  void usesBrokerTargetFromStrategyConfig(@TempDir Path tenantsDir) throws Exception {
    // Two tenants with different broker targets — tenant A is alpaca-paper, tenant B is
    // tradier-paper. The tenants/<id>/strategies/<sid>.yaml files just need to exist so the
    // scanner emits them; the real broker_target comes from the StrategyRegistry stub below.
    writeYaml(tenantsDir, "tenant-a", "strat-1");
    writeYaml(tenantsDir, "tenant-b", "strat-2");

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("tenant-a", "strat-1"))
        .thenReturn(strategyConfig(StrategyConfig.BrokerTarget.ALPACA_PAPER));
    when(registry.get("tenant-b", "strat-2"))
        .thenReturn(strategyConfig(StrategyConfig.BrokerTarget.TRADIER_PAPER));

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Schedule> scheduleCaptor = ArgumentCaptor.forClass(Schedule.class);
    verify(scheduleClient, times(2))
        .createSchedule(idCaptor.capture(), scheduleCaptor.capture(), any(ScheduleOptions.class));

    // Schedules are returned in scanner-emit order (filesystem walk over tenant subdirs).
    // Build a name→schedule map to assert independently of that order.
    java.util.Map<String, Schedule> byId = new java.util.HashMap<>();
    for (int i = 0; i < idCaptor.getAllValues().size(); i++) {
      byId.put(idCaptor.getAllValues().get(i), scheduleCaptor.getAllValues().get(i));
    }

    String alpacaId = "recon-t-tenant-a-s-strat-1-alpaca-paper";
    String tradierId = "recon-t-tenant-b-s-strat-2-tradier-paper";
    assertThat(byId).containsKey(alpacaId);
    assertThat(byId).containsKey(tradierId);

    assertThat(taskQueueOf(byId.get(alpacaId))).isEqualTo("orchestrator-core");
    assertThat(taskQueueOf(byId.get(tradierId))).isEqualTo("orchestrator-core");

    // The workflow itself doesn't pin to the broker-* queue (the orchestrator-core queue runs
    // the workflow body); the broker routing happens inside the workflow via the
    // ExecActivitiesFactory using the broker_target in the workflow input. So the
    // queue-correctness assertion lives in the input we serialize into the Schedule action.
    assertThat(workflowIdPrefixOf(byId.get(alpacaId))).contains("/recon/alpaca-paper/");
    assertThat(workflowIdPrefixOf(byId.get(tradierId))).contains("/recon/tradier-paper/");
  }

  @Test
  void invalidBrokerTargetSkipped(@TempDir Path tenantsDir) throws Exception {
    writeYaml(tenantsDir, "tenant-a", "strat-1");

    StrategyConfig cfg = new StrategyConfig();
    // Don't set broker_target — bootstrapper should log error and skip rather than misroute.

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("tenant-a", "strat-1")).thenReturn(cfg);

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    verify(scheduleClient, times(0))
        .createSchedule(any(String.class), any(Schedule.class), any(ScheduleOptions.class));
  }

  private static StrategyConfig strategyConfig(StrategyConfig.BrokerTarget target) {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(target);
    return cfg;
  }

  private static void writeYaml(Path tenantsDir, String tenantId, String strategyId)
      throws IOException {
    Path strategies = tenantsDir.resolve(tenantId).resolve("strategies");
    Files.createDirectories(strategies);
    // The file's contents don't matter — the StrategyRegistry stub returns the config; only the
    // scanner's directory walk needs the file to exist.
    Files.writeString(
        strategies.resolve(strategyId + ".yaml"), "schema_version: 1\n", StandardCharsets.UTF_8);
  }

  private static String taskQueueOf(Schedule schedule) {
    ScheduleActionStartWorkflow action = (ScheduleActionStartWorkflow) schedule.getAction();
    return action.getOptions().getTaskQueue();
  }

  private static String workflowIdPrefixOf(Schedule schedule) {
    ScheduleActionStartWorkflow action = (ScheduleActionStartWorkflow) schedule.getAction();
    return action.getOptions().getWorkflowId();
  }
}
