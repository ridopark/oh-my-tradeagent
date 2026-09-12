package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * #848 Phase 1: {@code subscribeEquity} must be idempotent per (ticker, targetWorkflowId) — the
 * same guarantee {@link SubscribePremiumDedupTest} pins for the premium side.
 *
 * <p>Before this, every call opened a fresh provider subscription with a fresh subscription id. Two
 * live subscriptions for one leg double-deliver every qualifying print to the SAME workflow and
 * leave an orphaned feed handle, so no re-subscribe path (restart recovery, or a workflow-side
 * retry after feed silence) could be built on top of it safely.
 *
 * <p>Deliberately does NOT use TestWorkflowEnvironment: every assertion here is about how many
 * subscriptions the PROVIDER sees, which needs no Temporal at all.
 */
class SubscribeEquityDedupTest {

  private static final String TICKER = "NVDA";

  /**
   * Must start with {@code t-<tenantId>/} or the activity's tenant-ownership guard fails closed.
   */
  private static final String WF_A = "t-staging_paper/s-watchlist-trigger-v1/wl/2026-09-10/NVDA/C";

  private static final String WF_B = "t-staging_paper/s-watchlist-trigger-v1/wl/2026-09-10/NVDA/P";

  /**
   * Tue 2026-06-23, 14:30 UTC == 10:30 ET — inside RTH, so the feed gate lets the subscribe run.
   */
  private static final Clock RTH_CLOCK =
      Clock.fixed(Instant.parse("2026-06-23T14:30:00Z"), ZoneId.of("America/New_York"));

  private ScheduledExecutorService watchdog;

  @AfterEach
  void tearDown() {
    if (watchdog != null) {
      watchdog.shutdownNow();
    }
  }

  /** Counts provider-side subscriptions and lets a test push ticks into registered listeners. */
  private static final class CountingProvider implements MarketDataProvider {
    final AtomicInteger subscribeCalls = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    final Map<String, List<Consumer<Tick>>> listeners = new LinkedHashMap<>();
    private int nextId = 1;

    @Override
    public Optional<Quote> snapshotQuote(String occSymbol) {
      return Optional.empty();
    }

    @Override
    public Optional<BigDecimal> snapshotEquityPrice(String ticker) {
      return Optional.empty();
    }

    @Override
    public synchronized Subscription subscribeEquity(String ticker, Consumer<Tick> onTick) {
      subscribeCalls.incrementAndGet();
      listeners.computeIfAbsent(ticker, k -> new ArrayList<>()).add(onTick);
      String id = "eq-sub-" + (nextId++);
      return new Subscription() {
        @Override
        public String subscriptionId() {
          return id;
        }

        @Override
        public void close() {
          closeCalls.incrementAndGet();
          listeners.getOrDefault(ticker, List.of()).remove(onTick);
        }
      };
    }

    @Override
    public Subscription subscribePremium(String occSymbol, Consumer<Tick> onTick) {
      throw new UnsupportedOperationException();
    }

    void push(String ticker, String price) {
      Tick t =
          new Tick(
              ticker,
              new BigDecimal(price),
              new BigDecimal(price),
              new BigDecimal(price),
              OffsetDateTime.parse("2026-06-23T14:31:00Z"));
      for (Consumer<Tick> l : List.copyOf(listeners.getOrDefault(ticker, List.of()))) {
        l.accept(t);
      }
    }
  }

  /** Records submissions without running them, so a dispatch never needs a live WorkflowClient. */
  private static final class CountingExecutor extends AbstractExecutorService {
    final AtomicInteger submitted = new AtomicInteger();

    @Override
    public void execute(Runnable command) {
      submitted.incrementAndGet();
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  private static SubscribeEquityRequest req(String ticker, String wfId, String deltaPct) {
    SubscribeEquityRequest r = new SubscribeEquityRequest();
    r.setSchemaVersion(1L);
    r.setTenantId("staging_paper");
    r.setStrategyId("watchlist-trigger-v1");
    r.setTicker(ticker);
    r.setTargetWorkflowId(wfId);
    r.setSignalName("equityTick");
    r.setTriggerLevel(new BigDecimal("100.00"));
    r.setEquityEmitDeltaPct(new BigDecimal(deltaPct));
    return r;
  }

  /** Runs submissions inline, so a dispatch (and its teardown path) executes synchronously. */
  private static ExecutorService inlineExecutor() {
    return new AbstractExecutorService() {
      @Override
      public void execute(Runnable command) {
        command.run();
      }

      @Override
      public void shutdown() {}

      @Override
      public List<Runnable> shutdownNow() {
        return List.of();
      }

      @Override
      public boolean isShutdown() {
        return false;
      }

      @Override
      public boolean isTerminated() {
        return false;
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
      }
    };
  }

  /** A WorkflowClient whose every signal reports the target workflow as gone. */
  private static WorkflowClient notFoundClient() {
    WorkflowClient client = org.mockito.Mockito.mock(WorkflowClient.class);
    WorkflowStub stub = org.mockito.Mockito.mock(WorkflowStub.class);
    org.mockito.Mockito.when(
            client.newUntypedWorkflowStub(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(stub);
    io.temporal.api.common.v1.WorkflowExecution execution =
        io.temporal.api.common.v1.WorkflowExecution.newBuilder()
            .setWorkflowId(WF_A)
            .setRunId("run-1")
            .build();
    org.mockito.Mockito.doThrow(
            new WorkflowNotFoundException(execution, "WatchlistTriggerWorkflow", null))
        .when(stub)
        .signal(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    return client;
  }

  private SubscribeEquityActivityImpl activity(CountingProvider p, CountingExecutor ex) {
    watchdog = Executors.newSingleThreadScheduledExecutor();
    return new SubscribeEquityActivityImpl(p, null, ex, watchdog, RTH_CLOCK, 3600L);
  }

  @Test
  void secondSubscribeForSameTickerAndWorkflow_reusesTheSubscription() {
    CountingProvider p = new CountingProvider();
    SubscribeEquityActivityImpl a = activity(p, new CountingExecutor());

    SubscribeEquityResult first = a.subscribeEquity(req(TICKER, WF_A, "0.01"));
    SubscribeEquityResult second = a.subscribeEquity(req(TICKER, WF_A, "0.01"));

    assertThat(first.getStatus()).isEqualTo(SubscribeEquityResult.Status.SUBSCRIBED);
    assertThat(second.getStatus()).isEqualTo(SubscribeEquityResult.Status.SUBSCRIBED);
    assertThat(second.getSubscriptionId()).isEqualTo(first.getSubscriptionId());
    assertThat(p.subscribeCalls.get()).isEqualTo(1);
    assertThat(p.listeners.get(TICKER)).hasSize(1);
  }

  /**
   * The whole point of the dedup: one tick must reach the leg ONCE. With two live subscriptions the
   * same print is delivered twice, which inflates the leg's history and can double-drive the entry
   * state machine.
   */
  @Test
  void oneTickIsDeliveredOnce_notOncePerDuplicateSubscribe() {
    CountingProvider p = new CountingProvider();
    CountingExecutor ex = new CountingExecutor();
    SubscribeEquityActivityImpl a = activity(p, ex);

    a.subscribeEquity(req(TICKER, WF_A, "0.01"));
    a.subscribeEquity(req(TICKER, WF_A, "0.01"));

    // Far outside the 1% band of trigger 100.00, so it is emitted rather than throttled.
    p.push(TICKER, "150.00");

    assertThat(ex.submitted.get()).isEqualTo(1);
  }

  /** Two DIFFERENT legs on the same underlying (a call leg and a put leg) must each get a feed. */
  @Test
  void differentWorkflowsOnSameTicker_eachGetTheirOwnSubscription() {
    CountingProvider p = new CountingProvider();
    SubscribeEquityActivityImpl a = activity(p, new CountingExecutor());

    SubscribeEquityResult first = a.subscribeEquity(req(TICKER, WF_A, "0.01"));
    SubscribeEquityResult second = a.subscribeEquity(req(TICKER, WF_B, "0.01"));

    assertThat(second.getSubscriptionId()).isNotEqualTo(first.getSubscriptionId());
    assertThat(p.subscribeCalls.get()).isEqualTo(2);
  }

  /**
   * Teardown must reclaim the dedup-index entry, not just the `active` entry (review finding on
   * #851). Two things go wrong without it: the index grows by one entry per leg for the worker's
   * life — legs are day-scoped, so that is unbounded — and a re-subscribe after teardown consults a
   * stale mapping instead of opening a genuinely fresh subscription.
   *
   * <p>Drives teardown the way production does: a dispatch whose target workflow is gone. The
   * dispatcher here runs submissions inline so the WorkflowNotFoundException path actually
   * executes.
   */
  @Test
  void afterTeardown_aResubscribeOpensAFreshSubscription() {
    CountingProvider p = new CountingProvider();
    watchdog = Executors.newSingleThreadScheduledExecutor();
    // Inline executor + a WorkflowClient whose stub always reports "not found", so the first
    // dispatch drives tearDown exactly as a closed leg does in production.
    SubscribeEquityActivityImpl a =
        new SubscribeEquityActivityImpl(
            p, notFoundClient(), inlineExecutor(), watchdog, RTH_CLOCK, 3600L);

    SubscribeEquityResult first = a.subscribeEquity(req(TICKER, WF_A, "0.01"));
    p.push(TICKER, "150.00"); // dispatch -> WorkflowNotFound -> tearDown

    assertThat(p.closeCalls.get()).isEqualTo(1);

    // THE defect: the index entry must be reclaimed AT TEARDOWN. Asserting it only after a
    // re-subscribe would prove nothing — compute() replaces a stale mapping on the next subscribe,
    // so a re-subscribe self-heals and the leak hides. A leg torn down and never re-subscribed is
    // the real case, and legs are day-scoped, so the growth is unbounded.
    assertThat(a.dedupIndexSize()).isZero();

    // And the self-healing path still has to behave: a later subscribe for the same key opens a
    // genuinely fresh subscription rather than handing back the dead id.
    SubscribeEquityResult afterTeardown = a.subscribeEquity(req(TICKER, WF_A, "0.01"));

    assertThat(afterTeardown.getStatus()).isEqualTo(SubscribeEquityResult.Status.SUBSCRIBED);
    assertThat(afterTeardown.getSubscriptionId()).isNotEqualTo(first.getSubscriptionId());
    assertThat(p.subscribeCalls.get()).isEqualTo(2);
    assertThat(a.dedupIndexSize()).isEqualTo(1);
  }

  /**
   * A re-subscribe must not reset the emit baseline. The re-subscribe path this dedup exists for
   * fires after feed silence, and resetting the baseline would let the next in-band tick through —
   * a spurious signal into a leg whose entry machine requires a live cross.
   */
  @Test
  void secondSubscribe_doesNotResetTheThrottleBaseline() {
    CountingProvider p = new CountingProvider();
    CountingExecutor ex = new CountingExecutor();
    // 10% band on trigger 100.00 == a 10.00 min-move, easy to stay inside.
    SubscribeEquityActivityImpl a = activity(p, ex);

    a.subscribeEquity(req(TICKER, WF_A, "0.10"));
    p.push(TICKER, "150.00"); // emitted: establishes the baseline
    int afterFirst = ex.submitted.get();

    a.subscribeEquity(req(TICKER, WF_A, "0.10")); // reuse; must not clear the baseline
    p.push(TICKER, "150.50"); // inside the band relative to 150.00 -> must be suppressed

    assertThat(afterFirst).isEqualTo(1);
    assertThat(ex.submitted.get()).isEqualTo(1);
  }
}
