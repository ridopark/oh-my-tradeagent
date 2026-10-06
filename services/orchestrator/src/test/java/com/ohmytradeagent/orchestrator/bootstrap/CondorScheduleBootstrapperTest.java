package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CondorScheduleBootstrapperTest {

  private static final TenantStrategy TS = new TenantStrategy("staging_paper", "gated_condor");

  private final StrategyRegistry registry = mock(StrategyRegistry.class);
  private final ScheduleClient client = mock(ScheduleClient.class);
  private final CondorScheduleBootstrapper bootstrapper =
      new CondorScheduleBootstrapper(
          mock(WorkflowClient.class), mock(WorkflowServiceStubs.class), registry);

  private static StrategyConfig condor() {
    return new StrategyConfig()
        .withBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER)
        .withCondorEntryEt("14:00");
  }

  @Test
  void fireTime_isEntryMinusTenMinutes() {
    assertThat(CondorScheduleBootstrapper.fireTime(condor())).isEqualTo(LocalTime.of(13, 50));
  }

  @Test
  void fireTime_nullWhenDarkDisabledOrNotPaper() {
    assertThat(CondorScheduleBootstrapper.fireTime(new StrategyConfig())).isNull();
    assertThat(CondorScheduleBootstrapper.fireTime(condor().withEnabled(false))).isNull();
    assertThat(
            CondorScheduleBootstrapper.fireTime(
                condor().withBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE)))
        .isNull();
  }

  @Test
  void enabledStrategy_getsItsSchedule_andAStaleTimeIsReaped() {
    when(registry.list()).thenReturn(List.of(TS));
    when(registry.get("staging_paper", "gated_condor")).thenReturn(condor());
    ScheduleListDescription stale = mock(ScheduleListDescription.class);
    when(stale.getScheduleId()).thenReturn("condor-v1-t-staging_paper-s-gated_condor-1420");
    when(client.listSchedules()).thenReturn(Stream.of(stale));
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(client.getHandle("condor-v1-t-staging_paper-s-gated_condor-1420")).thenReturn(handle);

    bootstrapper.runWith(client);

    verify(handle).delete();
    verify(client)
        .createSchedule(eq("condor-v1-t-staging_paper-s-gated_condor-1350"), any(), any());
  }

  @Test
  void disabledStrategy_hasItsScheduleReaped_andNoneCreated() {
    when(registry.list()).thenReturn(List.of(TS));
    when(registry.get("staging_paper", "gated_condor")).thenReturn(condor().withEnabled(false));
    ScheduleListDescription live = mock(ScheduleListDescription.class);
    when(live.getScheduleId()).thenReturn("condor-v1-t-staging_paper-s-gated_condor-1350");
    when(client.listSchedules()).thenReturn(Stream.of(live));
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(client.getHandle("condor-v1-t-staging_paper-s-gated_condor-1350")).thenReturn(handle);

    bootstrapper.runWith(client);

    verify(handle).delete();
    verify(client, never()).createSchedule(any(), any(), any());
  }
}
