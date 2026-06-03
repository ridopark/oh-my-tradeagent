package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistMirrorActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * WatchlistMirrorWorkflow is a single-step workflow: it dispatches the {@code postWatchlistAlert}
 * activity exactly once and completes. No search attributes, no DB. Activity is mocked.
 */
class WatchlistMirrorWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private WatchlistMirrorActivities mirror;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(CORE_QUEUE);
    worker.registerWorkflowImplementationTypes(WatchlistMirrorWorkflowImpl.class);

    mirror = Mockito.mock(WatchlistMirrorActivities.class);
    worker.registerActivitiesImplementations(mirror);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void invokesPostWatchlistAlertExactlyOnceAndCompletes() {
    WatchlistMirrorPayload payload = new WatchlistMirrorPayload();
    payload.setSchemaVersion(1L);
    payload.setTenantId("dev");
    payload.setStrategyId("copytrade-v1");
    payload.setEtDate(LocalDate.of(2026, 6, 3));
    payload.setAuthor("TradingTheTrend");
    payload.setRawText("AAPL calls");
    payload.setSourceMessageId("msg-1");

    WatchlistMirrorWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                WatchlistMirrorWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("watchlist-mirror-2026-06-03")
                    .build());

    assertThatCode(() -> wf.mirror(payload)).doesNotThrowAnyException();

    ArgumentCaptor<WatchlistMirrorPayload> captor =
        ArgumentCaptor.forClass(WatchlistMirrorPayload.class);
    verify(mirror, times(1)).postWatchlistAlert(captor.capture());
    assertThat(captor.getValue().getSourceMessageId()).isEqualTo("msg-1");
    assertThat(captor.getValue().getRawText()).isEqualTo("AAPL calls");
  }
}
