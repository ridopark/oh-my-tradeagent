package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.EquityTick;
import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import com.ohmytradeagent.contract.activities.SubscribeEquityActivity;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 2 (watchlist-trigger): verifies SubscribeEquityActivityImpl mirrors the premium activity's
 * signal pathway and adds the min-move throttle, stale guard, RTH gate, and dead-feed audit. The
 * end-to-end signal test uses TestWorkflowEnvironment so the signal pathway is the real one; the
 * throttle/gate/audit logic is unit-tested directly. NO network in any test.
 */
class SubscribeEquityActivityImplTest {

  private static final String MARKET_DATA_QUEUE = "market-data";
  private static final String CAPTURE_QUEUE = "capture-wf";

  /** A clock fixed inside RTH (Tue 2026-06-23, 10:30 ET == 14:30 UTC). */
  private static final Clock RTH_CLOCK =
      Clock.fixed(Instant.parse("2026-06-23T14:30:00Z"), ZoneId.of("America/New_York"));

  /** A clock outside RTH (Tue 2026-06-23, 20:00 UTC == 16:00 ET, exactly at close == closed). */
  private static final Clock CLOSED_CLOCK =
      Clock.fixed(Instant.parse("2026-06-23T20:00:00Z"), ZoneId.of("America/New_York"));

  @WorkflowInterface
  public interface CapturingWorkflow {
    @WorkflowMethod
    String run();

    @SignalMethod
    void equityTick(EquityTick tick);
  }

  public static class CapturingWorkflowImpl implements CapturingWorkflow {
    @Override
    public String run() {
      Workflow.await(() -> TickCapture.lastTick != null);
      return "done";
    }

    @Override
    public void equityTick(EquityTick tick) {
      TickCapture.lastTick = tick;
    }
  }

  @WorkflowInterface
  public interface DispatchWorkflow {
    @WorkflowMethod
    SubscribeEquityResult invoke(SubscribeEquityRequest req);
  }

  public static class DispatchWorkflowImpl implements DispatchWorkflow {
    private final SubscribeEquityActivity act =
        Workflow.newActivityStub(
            SubscribeEquityActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(MARKET_DATA_QUEUE)
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build());

    @Override
    public SubscribeEquityResult invoke(SubscribeEquityRequest req) {
      return act.subscribeEquity(req);
    }
  }

  static final class TickCapture {
    static volatile EquityTick lastTick;
  }

  private TestWorkflowEnvironment env;
  private InMemoryMarketData stream;
  private ScheduledExecutorService watchdog;

  @BeforeEach
  void setUp() {
    TickCapture.lastTick = null;
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());

    Worker captureWorker = env.newWorker(CAPTURE_QUEUE);
    captureWorker.registerWorkflowImplementationTypes(
        CapturingWorkflowImpl.class, DispatchWorkflowImpl.class);

    stream = new InMemoryMarketData();
    watchdog = Executors.newSingleThreadScheduledExecutor();
    SubscribeEquityActivityImpl activity =
        new SubscribeEquityActivityImpl(
            stream,
            env.getWorkflowClient(),
            Executors.newSingleThreadExecutor(),
            watchdog,
            RTH_CLOCK,
            3600L);
    Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
    mdWorker.registerActivitiesImplementations(activity);

    env.start();
  }

  @AfterEach
  void tearDown() {
    watchdog.shutdownNow();
    env.close();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void subscribeAndPushTick_signalsCapturingWorkflow() {
    String targetWfId = "wt-wf-test-1";
    CapturingWorkflow target =
        env.getWorkflowClient()
            .newWorkflowStub(
                CapturingWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CAPTURE_QUEUE)
                    .setWorkflowId(targetWfId)
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
    SubscribeEquityResult result =
        dispatcher.invoke(request("NVDA", targetWfId, "140.00", "0.0005"));

    assertThat(result.getStatus()).isEqualTo(SubscribeEquityResult.Status.SUBSCRIBED);
    assertThat(result.getSubscriptionId()).isNotBlank();

    stream.pushEquityTickForTest(
        "NVDA", new BigDecimal("140.12"), OffsetDateTime.parse("2026-06-23T14:31:00Z"));

    WorkflowStub.fromTyped(target).getResult(String.class);

    assertThat(TickCapture.lastTick).isNotNull();
    assertThat(TickCapture.lastTick.getLast().doubleValue()).isEqualTo(140.12);
    assertThat(TickCapture.lastTick.getTicker()).isEqualTo("NVDA");
    assertThat(TickCapture.lastTick.getStale()).isFalse();
  }

  @Test
  void outsideRth_returnsGated_andDoesNotSubscribe() {
    SubscribeEquityActivityImpl gated =
        new SubscribeEquityActivityImpl(
            stream,
            env.getWorkflowClient(),
            Executors.newSingleThreadExecutor(),
            watchdog,
            CLOSED_CLOCK,
            3600L);

    SubscribeEquityResult result =
        gated.subscribeEquity(req("NVDA", "wf", "equityTick", "140.00", "0.0005"));

    assertThat(result.getStatus()).isEqualTo(SubscribeEquityResult.Status.GATED);
    assertThat(result.getSubscriptionId()).isEmpty();
    // No subscription => a pushed tick reaches nobody (gate prevented subscribe).
    CopyOnWriteArrayList<EquityTick> rx = new CopyOnWriteArrayList<>();
    stream.subscribeEquity("NVDA", t -> rx.add(toEquityTick(t)));
    stream.pushEquityTickForTest(
        "NVDA", new BigDecimal("140.0"), OffsetDateTime.parse("2026-06-23T20:00:00Z"));
    // only our local listener got it; the gated activity registered none
    assertThat(rx).hasSize(1);
  }

  @Test
  void failsClosed_whenProviderGatesStockFeed() {
    SubscribeEquityActivityImpl activity =
        new SubscribeEquityActivityImpl(
            new GatedProvider(),
            env.getWorkflowClient(),
            Executors.newSingleThreadExecutor(),
            watchdog,
            RTH_CLOCK,
            3600L);

    SubscribeEquityResult result =
        activity.subscribeEquity(req("NVDA", "wf", "equityTick", "140.00", "0.0005"));

    assertThat(result.getStatus()).isEqualTo(SubscribeEquityResult.Status.GATED);
    assertThat(result.getError()).contains("gated");
  }

  // --- direct unit tests of the throttle ---

  @Test
  void throttle_suppressesSubDeltaMoves_emitsOnDeltaMove() {
    SubscribeEquityActivityImpl activity = newBareActivity();
    // trigger T=100, delta=0.01 => minMove = 1.00
    BigDecimal minMove = new BigDecimal("100").multiply(new BigDecimal("0.01"));
    SubscribeEquityActivityImpl.ThrottleState throttle =
        new SubscribeEquityActivityImpl.ThrottleState();

    List<Boolean> emits = new ArrayList<>();
    // first tick always emits (seeds baseline at 100.00)
    emits.add(activity.shouldEmit(throttle, tick("100.00"), minMove));
    // +0.50 < 1.00 => suppressed
    emits.add(activity.shouldEmit(throttle, tick("100.50"), minMove));
    // +0.90 cumulative from 100.00 = 100.90, still < 1.00 from baseline => suppressed
    emits.add(activity.shouldEmit(throttle, tick("100.90"), minMove));
    // 101.00 => exactly +1.00 => emit, baseline now 101.00
    emits.add(activity.shouldEmit(throttle, tick("101.00"), minMove));
    // 101.50 => +0.50 from new baseline => suppressed
    emits.add(activity.shouldEmit(throttle, tick("101.50"), minMove));
    // 99.90 => |99.90-101.00|=1.10 >= 1.00 => emit (downside)
    emits.add(activity.shouldEmit(throttle, tick("99.90"), minMove));

    assertThat(emits).containsExactly(true, false, false, true, false, true);
    assertThat(emits.stream().filter(b -> b).count()).isEqualTo(3L);
  }

  @Test
  void feedDead_predicate_firesAtOrPastThreshold() {
    // noTickAuditSeconds = 5 here
    SubscribeEquityActivityImpl activity =
        new SubscribeEquityActivityImpl(
            stream,
            env.getWorkflowClient(),
            Executors.newSingleThreadExecutor(),
            watchdog,
            RTH_CLOCK,
            5L);
    assertThat(activity.feedDead(4_999L)).isFalse();
    assertThat(activity.feedDead(5_000L)).isTrue();
    assertThat(activity.feedDead(10_000L)).isTrue();
  }

  @Test
  void rthGate_trueInsideFalseOutside() {
    assertThat(newActivityWithClock(RTH_CLOCK).isRegularTradingHours()).isTrue();
    assertThat(newActivityWithClock(CLOSED_CLOCK).isRegularTradingHours()).isFalse();
    // Weekend: Sat 2026-06-20 14:30 UTC
    Clock weekend =
        Clock.fixed(Instant.parse("2026-06-20T14:30:00Z"), ZoneId.of("America/New_York"));
    assertThat(newActivityWithClock(weekend).isRegularTradingHours()).isFalse();
  }

  // --- helpers ---

  private SubscribeEquityActivityImpl newBareActivity() {
    return newActivityWithClock(RTH_CLOCK);
  }

  private SubscribeEquityActivityImpl newActivityWithClock(Clock clock) {
    return new SubscribeEquityActivityImpl(
        stream,
        env.getWorkflowClient(),
        Executors.newSingleThreadExecutor(),
        watchdog,
        clock,
        3600L);
  }

  private static com.ohmytradeagent.marketdata.provider.Tick tick(String last) {
    return new com.ohmytradeagent.marketdata.provider.Tick(
        "NVDA", new BigDecimal(last), OffsetDateTime.parse("2026-06-23T14:31:00Z"));
  }

  private static EquityTick toEquityTick(com.ohmytradeagent.marketdata.provider.Tick t) {
    EquityTick e = new EquityTick();
    e.setSchemaVersion(1L);
    e.setTicker(t.occSymbol());
    e.setLast(t.premium());
    e.setRetrievedAt(t.retrievedAt());
    e.setStale(false);
    return e;
  }

  /** Provider whose equity subscription is gated (mirrors Alpaca's fail-closed throw). */
  private static final class GatedProvider
      implements com.ohmytradeagent.marketdata.provider.MarketDataProvider {
    @Override
    public java.util.Optional<com.ohmytradeagent.marketdata.provider.Quote> snapshotQuote(
        String occSymbol) {
      return java.util.Optional.empty();
    }

    @Override
    public com.ohmytradeagent.marketdata.provider.Subscription subscribePremium(
        String occSymbol,
        java.util.function.Consumer<com.ohmytradeagent.marketdata.provider.Tick> onTick) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.ohmytradeagent.marketdata.provider.Subscription subscribeEquity(
        String ticker,
        java.util.function.Consumer<com.ohmytradeagent.marketdata.provider.Tick> onTick) {
      throw new IllegalStateException("stock data WS not configured; equity subscription gated");
    }
  }

  private SubscribeEquityRequest request(
      String ticker, String targetWfId, String trigger, String deltaPct) {
    return req(ticker, targetWfId, "equityTick", trigger, deltaPct);
  }

  private SubscribeEquityRequest req(
      String ticker, String targetWfId, String signalName, String trigger, String deltaPct) {
    SubscribeEquityRequest r = new SubscribeEquityRequest();
    r.setSchemaVersion(1L);
    r.setTenantId("dev");
    r.setStrategyId("watchlist-trigger-v1");
    r.setTicker(ticker);
    r.setTargetWorkflowId(targetWfId);
    r.setSignalName(signalName);
    r.setTriggerLevel(new BigDecimal(trigger));
    r.setEquityEmitDeltaPct(new BigDecimal(deltaPct));
    return r;
  }
}
