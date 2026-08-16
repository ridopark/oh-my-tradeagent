package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import com.ohmytradeagent.marketdata.provider.inmemory.InMemoryMarketData;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies SubscribePremiumActivityImpl wires the stream source into a signal-dispatch back to the
 * originating workflow. Uses TestWorkflowEnvironment so the signal pathway is the real one.
 */
class SubscribePremiumActivityImplTest {

  private static final String MARKET_DATA_QUEUE = "market-data";
  private static final String CAPTURE_QUEUE = "capture-wf";

  /** Test-only workflow that captures the chandelier_tick signal payload. */
  @WorkflowInterface
  public interface CapturingWorkflow {
    @WorkflowMethod
    String run();

    @SignalMethod
    void chandelierTick(PremiumTick tick);
  }

  public static class CapturingWorkflowImpl implements CapturingWorkflow {
    @Override
    public String run() {
      Workflow.await(() -> TickCapture.lastTick != null);
      return "done";
    }

    @Override
    public void chandelierTick(PremiumTick tick) {
      TickCapture.lastTick = tick;
    }
  }

  /** Test driver workflow that invokes the activity. */
  @WorkflowInterface
  public interface DispatchWorkflow {
    @WorkflowMethod
    SubscribePremiumResult invoke(SubscribePremiumRequest req);
  }

  public static class DispatchWorkflowImpl implements DispatchWorkflow {
    private final SubscribePremiumActivity act =
        Workflow.newActivityStub(
            SubscribePremiumActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(MARKET_DATA_QUEUE)
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build());

    @Override
    public SubscribePremiumResult invoke(SubscribePremiumRequest req) {
      return act.subscribePremium(req);
    }
  }

  /** Capture slot — reset in @BeforeEach. */
  static final class TickCapture {
    static volatile PremiumTick lastTick;
  }

  private TestWorkflowEnvironment env;
  private InMemoryMarketData stream;

  @BeforeEach
  void setUp() {
    TickCapture.lastTick = null;
    // Disable time-skipping: the chandelier_tick signal is dispatched from the activity's real
    // background executor, so the virtual clock must stay locked to real time. Otherwise
    // getResult() unlocks time-skipping and the capturing workflow's run timeout expires on the
    // virtual clock before the real-thread signal RPC lands (issue #230).
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());

    Worker captureWorker = env.newWorker(CAPTURE_QUEUE);
    captureWorker.registerWorkflowImplementationTypes(
        CapturingWorkflowImpl.class, DispatchWorkflowImpl.class);

    stream = new InMemoryMarketData();
    SubscribePremiumActivityImpl activity =
        new SubscribePremiumActivityImpl(
            stream,
            env.getWorkflowClient(),
            Executors.newSingleThreadExecutor(),
            // 0% = emit every tick, preserving what these wiring tests were written to assert.
            // The throttle's own behaviour is covered by SubscribePremiumThrottleTest.
            BigDecimal.ZERO);
    Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
    mdWorker.registerActivitiesImplementations(activity);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void subscribeAndPushTick_signalsCapturingWorkflow() throws Exception {
    String posWfId = "pos-wf-test-1";
    CapturingWorkflow target =
        env.getWorkflowClient()
            .newWorkflowStub(
                CapturingWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CAPTURE_QUEUE)
                    .setWorkflowId(posWfId)
                    .build());
    WorkflowClient.start(target::run);

    DispatchWorkflow dispatcher =
        env.getWorkflowClient()
            .newWorkflowStub(
                DispatchWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CAPTURE_QUEUE)
                    .setWorkflowId("dispatch-" + java.util.UUID.randomUUID())
                    .build());
    SubscribePremiumResult result = dispatcher.invoke(request("NVDA  260516C00140000", posWfId));

    assertThat(result.getStatus()).isEqualTo(SubscribePremiumResult.Status.SUBSCRIBED);
    assertThat(result.getSubscriptionId()).isNotBlank();

    stream.pushTickForTest(
        "NVDA  260516C00140000",
        new BigDecimal("3.10"),
        OffsetDateTime.parse("2026-05-13T17:55:00Z"));

    // Deterministically wait for the captured signal by blocking on the capturing workflow's
    // terminal result. CapturingWorkflowImpl.run() returns "done" exactly when
    // Workflow.await(() -> TickCapture.lastTick != null) unblocks, so this returns only after the
    // chandelier_tick signal has been received and lastTick set. With time-skipping disabled (see
    // setUp), the workflow's run timeout cannot expire ahead of the real-thread signal. This
    // replaces the prior wall-clock busy-poll, which raced a fixed real-time budget against an
    // async signal path and flaked under CI scheduling pressure (issue #230).
    WorkflowStub.fromTyped(target).getResult(String.class);

    assertThat(TickCapture.lastTick).isNotNull();
    assertThat(TickCapture.lastTick.getPremium().doubleValue()).isEqualTo(3.10);
    assertThat(TickCapture.lastTick.getContractSymbol()).isEqualTo("NVDA  260516C00140000");
  }

  private SubscribePremiumRequest request(String symbol, String posWfId) {
    SubscribePremiumRequest r = new SubscribePremiumRequest();
    r.setSchemaVersion(1L);
    r.setTenantId("dev");
    r.setStrategyId("copytrade-v1");
    r.setContractSymbol(symbol);
    r.setPositionWorkflowId(posWfId);
    return r;
  }
}
