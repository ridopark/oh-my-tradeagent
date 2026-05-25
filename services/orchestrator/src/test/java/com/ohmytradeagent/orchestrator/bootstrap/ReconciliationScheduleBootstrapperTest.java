package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Phase 2c.2 review feedback (Major 3): {@link ReconciliationScheduleBootstrapper} must derive the
 * task queue from each strategy's own {@code broker_target}, not a hardcoded {@code "alpaca-paper"}
 * constant. Two tenants pinned to different brokers must get two schedules on the matching task
 * queues.
 *
 * <p>Issue #56 (final item): after a {@code broker_target} rename, lingering Temporal schedules
 * from the prior broker target must be reaped so each {@code (tenant, strategy)} ends up with
 * exactly one schedule on the current broker queue.
 */
class ReconciliationScheduleBootstrapperTest {

  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachLogAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(ReconciliationScheduleBootstrapper.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(ReconciliationScheduleBootstrapper.class);
    logger.detachAppender(logAppender);
    logAppender.stop();
  }

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
    // Issue #110: listSchedules() is hoisted out of the per-strategy loop and invoked exactly
    // once per bootstrap pass. The stub returns the snapshot once; if anything calls it a second
    // time Mockito returns null and the run will NPE — which is the regression we want to catch.
    when(scheduleClient.listSchedules()).thenReturn(Stream.empty());
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    // Issue #110: O(N×M) → O(M). Two strategies must share a single listSchedules() snapshot.
    verify(scheduleClient, times(1)).listSchedules();

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

    String alpacaId = "recon-v2-t-tenant-a-s-strat-1-alpaca-paper";
    String tradierId = "recon-v2-t-tenant-b-s-strat-2-tradier-paper";
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
    // Reap pass is short-circuited by the existing fail-closed guard.
    verify(scheduleClient, times(0)).listSchedules();
    verify(scheduleClient, times(0)).getHandle(any());
  }

  @Test
  void reapsStaleScheduleWhenBrokerTargetChanges(@TempDir Path tenantsDir) throws Exception {
    writeYaml(tenantsDir, "t-dev", "s-copytrade-v1");

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("t-dev", "s-copytrade-v1"))
        .thenReturn(strategyConfig(StrategyConfig.BrokerTarget.ALPACA_PAPER));

    String staleId = "recon-v2-t-t-dev-s-s-copytrade-v1-tradier-paper";
    String desiredId = "recon-v2-t-t-dev-s-s-copytrade-v1-alpaca-paper";

    ScheduleListDescription stale = mock(ScheduleListDescription.class);
    when(stale.getScheduleId()).thenReturn(staleId);

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    when(scheduleClient.listSchedules()).thenReturn(Stream.of(stale));

    ScheduleHandle staleHandle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(staleId)).thenReturn(staleHandle);

    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    verify(scheduleClient).listSchedules();
    verify(scheduleClient).getHandle(staleId);
    verify(staleHandle, times(1)).delete();
    verify(scheduleClient, times(1))
        .createSchedule(eq(desiredId), any(Schedule.class), any(ScheduleOptions.class));
  }

  @Test
  void doesNotReapMatchingSchedule(@TempDir Path tenantsDir) throws Exception {
    writeYaml(tenantsDir, "t-dev", "s-copytrade-v1");

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("t-dev", "s-copytrade-v1"))
        .thenReturn(strategyConfig(StrategyConfig.BrokerTarget.ALPACA_PAPER));

    String desiredId = "recon-v2-t-t-dev-s-s-copytrade-v1-alpaca-paper";

    ScheduleListDescription desired = mock(ScheduleListDescription.class);
    when(desired.getScheduleId()).thenReturn(desiredId);

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    when(scheduleClient.listSchedules()).thenReturn(Stream.of(desired));
    // Warm-boot: createSchedule throws ScheduleAlreadyRunningException, which the bootstrapper
    // swallows as an info log.
    when(scheduleClient.createSchedule(
            eq(desiredId), any(Schedule.class), any(ScheduleOptions.class)))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("already running")));

    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    verify(scheduleClient, times(0)).getHandle(any());
    verify(scheduleClient, times(1))
        .createSchedule(eq(desiredId), any(Schedule.class), any(ScheduleOptions.class));
  }

  @Test
  void doesNotReapOtherTenantsOrStrategies(@TempDir Path tenantsDir) throws Exception {
    writeYaml(tenantsDir, "t-dev", "s-copytrade-v1");

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("t-dev", "s-copytrade-v1"))
        .thenReturn(strategyConfig(StrategyConfig.BrokerTarget.ALPACA_PAPER));

    String desiredId = "recon-v2-t-t-dev-s-s-copytrade-v1-alpaca-paper";

    ScheduleListDescription otherTenant = mock(ScheduleListDescription.class);
    when(otherTenant.getScheduleId()).thenReturn("recon-v2-t-other-s-s-copytrade-v1-tradier-paper");
    ScheduleListDescription otherStrategy = mock(ScheduleListDescription.class);
    when(otherStrategy.getScheduleId())
        .thenReturn("recon-v2-t-t-dev-s-s-other-strat-tradier-paper");
    ScheduleListDescription killSwitch = mock(ScheduleListDescription.class);
    when(killSwitch.getScheduleId()).thenReturn("t-t-dev/s-s-copytrade-v1/killswitch");

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    when(scheduleClient.listSchedules())
        .thenReturn(Stream.of(otherTenant, otherStrategy, killSwitch));

    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    verify(scheduleClient, times(0)).getHandle(any());
    verify(scheduleClient, times(1))
        .createSchedule(eq(desiredId), any(Schedule.class), any(ScheduleOptions.class));
  }

  @Test
  void reapSkippedWhenStrategyConfigInvalid(@TempDir Path tenantsDir) throws Exception {
    writeYaml(tenantsDir, "t-dev", "s-copytrade-v1");

    StrategyConfig cfg = new StrategyConfig();
    // broker_target not set — fail-closed: reap pass must be skipped.

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("t-dev", "s-copytrade-v1")).thenReturn(cfg);

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());
    bootstrapper.runWith(scheduleClient);

    verify(scheduleClient, times(0)).listSchedules();
    verify(scheduleClient, times(0)).getHandle(any());
    verify(scheduleClient, times(0))
        .createSchedule(any(String.class), any(Schedule.class), any(ScheduleOptions.class));
  }

  @Test
  void continuesAndCreatesScheduleWhenReapDeleteThrows(@TempDir Path tenantsDir) throws Exception {
    writeYaml(tenantsDir, "t-dev", "s-copytrade-v1");

    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("t-dev", "s-copytrade-v1"))
        .thenReturn(strategyConfig(StrategyConfig.BrokerTarget.ALPACA_PAPER));

    String staleId = "recon-v2-t-t-dev-s-s-copytrade-v1-tradier-paper";
    String desiredId = "recon-v2-t-t-dev-s-s-copytrade-v1-alpaca-paper";

    ScheduleListDescription stale = mock(ScheduleListDescription.class);
    when(stale.getScheduleId()).thenReturn(staleId);

    ScheduleHandle staleHandle = mock(ScheduleHandle.class);
    doThrow(new RuntimeException("simulated SDK error")).when(staleHandle).delete();

    ScheduleClient scheduleClient = mock(ScheduleClient.class);
    when(scheduleClient.listSchedules()).thenReturn(Stream.of(stale));
    when(scheduleClient.getHandle(staleId)).thenReturn(staleHandle);

    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);

    ReconciliationScheduleBootstrapper bootstrapper =
        new ReconciliationScheduleBootstrapper(
            workflowClient, stubs, registry, tenantsDir.toString());

    assertThatCode(() -> bootstrapper.runWith(scheduleClient)).doesNotThrowAnyException();

    verify(scheduleClient, times(1))
        .createSchedule(eq(desiredId), any(Schedule.class), any(ScheduleOptions.class));

    boolean warnEmitted =
        logAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage()
                            .startsWith("could not reap stale Reconciliation Schedule"));
    assertThat(warnEmitted).isTrue();
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
