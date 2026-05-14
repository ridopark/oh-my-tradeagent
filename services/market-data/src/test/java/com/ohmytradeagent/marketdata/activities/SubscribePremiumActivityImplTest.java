package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import com.ohmytradeagent.marketdata.stream.InMemoryPremiumStreamSource;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
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
  private InMemoryPremiumStreamSource stream;

  @BeforeEach
  void setUp() {
    TickCapture.lastTick = null;
    env = TestWorkflowEnvironment.newInstance();

    Worker captureWorker = env.newWorker(CAPTURE_QUEUE);
    captureWorker.registerWorkflowImplementationTypes(
        CapturingWorkflowImpl.class, DispatchWorkflowImpl.class);

    stream = new InMemoryPremiumStreamSource();
    SubscribePremiumActivityImpl activity =
        new SubscribePremiumActivityImpl(
            stream, env.getWorkflowClient(), Executors.newSingleThreadExecutor());
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

    // 50s deadline (under the 60s @Timeout) — CI runners under load have hit >25s for signal
    // propagation through TestWorkflowEnvironment; the prior 25s was still too tight.
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline && TickCapture.lastTick == null) {
      Thread.sleep(50);
    }

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
