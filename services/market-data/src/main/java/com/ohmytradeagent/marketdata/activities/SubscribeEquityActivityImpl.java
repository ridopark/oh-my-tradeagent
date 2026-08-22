package com.ohmytradeagent.marketdata.activities;

import com.ohmytradeagent.contract.EquityTick;
import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import com.ohmytradeagent.contract.activities.SubscribeEquityActivity;
import com.ohmytradeagent.marketdata.MarketHours;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import com.ohmytradeagent.marketdata.provider.alpaca.StockFeedGatedException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Phase 2 (watchlist-trigger) worker-side implementation of {@link SubscribeEquityActivity}.
 * Mirrors {@link SubscribePremiumActivityImpl}: subscribes a tick consumer on the Spring-wired
 * {@link MarketDataProvider} and signals the named target workflow on each meaningful tick. It does
 * NOT heartbeat/block as a long-lived activity — it returns SUBSCRIBED immediately and the
 * provider's feed thread drives the signal fan-out (the same precedent the premium activity
 * established).
 *
 * <p>Differences from the premium activity, all trading-critical:
 *
 * <ul>
 *   <li><b>Min-move throttle</b> — a tick is signalled only when {@code |last - lastEmitted| >=
 *       equity_emit_delta_pct * trigger_level}. The first non-stale tick always emits (it seeds the
 *       baseline). This bounds workflow history to O(meaningful moves), not O(ticks).
 *   <li><b>Stale/halted guard</b> — the provider already drops halted/stale prints; this layer
 *       additionally honours an {@link EquityTick#getStale()} flag and never signals a stale tick.
 *   <li><b>Market-hours gate</b> — subscriptions are only opened during RTH (Mon-Fri 09:30-16:00
 *       ET, weekday-only like {@code MarketCalendarActivities}). Outside RTH the activity returns
 *       GATED and does not subscribe.
 *   <li><b>Dead-feed audit</b> — a LOUD audit fires when the subscription drops or no tick arrives
 *       for {@code noTickAuditSeconds}; the watchdog never silently lets a dead feed look healthy.
 * </ul>
 *
 * <p>Fail-closed: source-side exceptions (including the provider's stock-feed gate when the
 * real-time stock WS is unconfigured) return FAILED/GATED rather than throwing, so the workflow can
 * audit and proceed without a feed.
 */
@Component
public class SubscribeEquityActivityImpl implements SubscribeEquityActivity {

  private static final Logger log = LoggerFactory.getLogger(SubscribeEquityActivityImpl.class);

  private static final ZoneId ET = MarketHours.ET;

  /**
   * Max age of an equity print before it is marked stale. A late/out-of-sequence tag (e.g. the
   * 2026-07-29 QQQ 680 print that arrived ~20 min stale) is dropped by the workflow's feed-stale
   * guard so the evaluator never transitions on it.
   */
  private static final Duration MAX_TICK_AGE = Duration.ofSeconds(15);

  private final MarketDataProvider provider;
  private final WorkflowClient workflowClient;
  private final ExecutorService dispatcher;
  private final ScheduledExecutorService watchdog;
  private final Clock clock;
  private final long noTickAuditSeconds;

  private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ScheduledFuture<?>> watchdogTasks =
      new ConcurrentHashMap<>();

  @Autowired
  public SubscribeEquityActivityImpl(
      MarketDataProvider provider,
      WorkflowClient workflowClient,
      @Qualifier("equityTickDispatcher") ExecutorService dispatcher,
      ScheduledExecutorService watchdog) {
    this(provider, workflowClient, dispatcher, watchdog, Clock.system(ET), 30L);
  }

  /** Visible for tests: inject a fixed clock and a short no-tick window. */
  SubscribeEquityActivityImpl(
      MarketDataProvider provider,
      WorkflowClient workflowClient,
      ExecutorService dispatcher,
      ScheduledExecutorService watchdog,
      Clock clock,
      long noTickAuditSeconds) {
    this.provider = provider;
    this.workflowClient = workflowClient;
    this.dispatcher = dispatcher;
    this.watchdog = watchdog;
    this.clock = clock;
    this.noTickAuditSeconds = noTickAuditSeconds;
  }

  @Override
  public SubscribeEquityResult subscribeEquity(SubscribeEquityRequest req) {
    SubscribeEquityResult result = new SubscribeEquityResult();
    result.setSchemaVersion(1L);
    result.setSubscribedAt(OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC));

    if (!isRegularTradingHours()) {
      log.warn(
          "AUDIT equity-subscribe-gated-rth: ticker={} target_wf={} outside RTH; not subscribing.",
          req.getTicker(),
          req.getTargetWorkflowId());
      result.setSubscriptionId("");
      result.setStatus(SubscribeEquityResult.Status.GATED);
      result.setError("outside regular trading hours");
      return result;
    }

    // Tenant-ownership guard: the activity must only ever signal a workflow owned by the requesting
    // tenant. Child ids are minted as t-{tenant}/... (WorkflowIds.tenantStrategy), so a mismatched
    // prefix means a cross-tenant target — fail closed rather than signalling someone else's leg.
    final String tenantPrefix = "t-" + req.getTenantId() + "/";
    if (req.getTargetWorkflowId() == null || !req.getTargetWorkflowId().startsWith(tenantPrefix)) {
      log.error(
          "AUDIT equity-subscribe-tenant-mismatch: tenant={} target_wf={} not owned by tenant;"
              + " refusing to subscribe.",
          req.getTenantId(),
          req.getTargetWorkflowId());
      result.setSubscriptionId("");
      result.setStatus(SubscribeEquityResult.Status.FAILED);
      result.setError("target_workflow_id not owned by tenant");
      return result;
    }

    try {
      final String targetWfId = req.getTargetWorkflowId();
      final String signalName = req.getSignalName();
      final String ticker = req.getTicker();
      final BigDecimal trigger = req.getTriggerLevel();
      final BigDecimal deltaPct = req.getEquityEmitDeltaPct();
      final BigDecimal minMove = trigger.multiply(deltaPct).abs();

      final String[] subIdHolder = new String[1];
      // Published before any tick consumer can read subIdHolder. The provider registers the
      // consumer
      // INSIDE subscribeEquity() and the subscription id only exists once it returns, so a tick
      // that
      // fires before subIdHolder is set would otherwise dispatch with a null id — and a first-tick
      // WorkflowNotFoundException would then skip teardown (tearDown(null) is a no-op). The
      // dispatch
      // task awaits this latch so it never reads subIdHolder before the id is published.
      final java.util.concurrent.CountDownLatch subIdReady =
          new java.util.concurrent.CountDownLatch(1);
      final ThrottleState throttle = new ThrottleState();

      Subscription sub =
          provider.subscribeEquity(
              ticker,
              tick -> {
                throttle.markTickSeen();
                if (!shouldEmit(throttle, tick, minMove)) {
                  return;
                }
                EquityTick equityTick = toEquityTick(tick);
                dispatcher.submit(
                    () -> {
                      awaitUninterruptibly(subIdReady);
                      dispatchTick(targetWfId, signalName, subIdHolder[0], equityTick);
                    });
              });
      subIdHolder[0] = sub.subscriptionId();
      active.put(sub.subscriptionId(), sub);
      throttle.markTickSeen(); // seed the watchdog clock at subscribe time
      // Arm (and register) the watchdog BEFORE releasing the dispatch, so a first-tick
      // WorkflowNotFoundException teardown always finds the watchdog future to cancel it.
      armNoTickWatchdog(sub.subscriptionId(), ticker, targetWfId, throttle);
      // Release the dispatch only after subIdHolder, active, AND the watchdog are published, so a
      // first-tick teardown can find the subscription in `active` to close it and cancel the
      // watchdog.
      subIdReady.countDown();

      result.setSubscriptionId(sub.subscriptionId());
      result.setStatus(SubscribeEquityResult.Status.SUBSCRIBED);
      return result;
    } catch (RuntimeException e) {
      // The provider's stock-feed gate (stock WS unconfigured) surfaces as a typed
      // StockFeedGatedException -> GATED; any other failure -> FAILED.
      SubscribeEquityResult.Status status =
          (e instanceof StockFeedGatedException)
              ? SubscribeEquityResult.Status.GATED
              : SubscribeEquityResult.Status.FAILED;
      log.warn(
          "AUDIT equity-subscribe-failed: tenant={} strategy={} ticker={} status={}: {}",
          req.getTenantId(),
          req.getStrategyId(),
          req.getTicker(),
          status,
          e.getMessage());
      result.setSubscriptionId("");
      result.setStatus(status);
      result.setError(e.getMessage());
      return result;
    }
  }

  /** Throttle: emit the first non-stale tick, then only on a >= minMove move from lastEmitted. */
  boolean shouldEmit(ThrottleState throttle, Tick tick, BigDecimal minMove) {
    if (tick.premium() == null) {
      return false;
    }
    BigDecimal last = tick.premium();
    // Atomic compare-and-set so two simultaneous ticks cannot both win the same baseline and
    // double-emit: only the thread that successfully advances lastEmitted emits.
    while (true) {
      BigDecimal prevEmitted = throttle.lastEmitted.get();
      if (prevEmitted != null && last.subtract(prevEmitted).abs().compareTo(minMove) < 0) {
        return false;
      }
      if (throttle.lastEmitted.compareAndSet(prevEmitted, last)) {
        return true;
      }
    }
  }

  private void armNoTickWatchdog(
      String subscriptionId, String ticker, String targetWfId, ThrottleState throttle) {
    ScheduledFuture<?> task =
        watchdog.scheduleAtFixedRate(
            () -> {
              if (!active.containsKey(subscriptionId)) {
                return; // subscription torn down; the periodic task is cancelled on teardown
              }
              long sinceMs = throttle.millisSinceLastTick();
              if (feedDead(sinceMs)) {
                log.error(
                    "AUDIT equity-feed-dead: ticker={} target_wf={} no tick for {}s "
                        + "(>= {}s threshold); feed may be dead.",
                    ticker,
                    targetWfId,
                    sinceMs / 1000,
                    noTickAuditSeconds);
              }
            },
            noTickAuditSeconds,
            noTickAuditSeconds,
            TimeUnit.SECONDS);
    watchdogTasks.put(subscriptionId, task);
  }

  /** Pure liveness predicate: the feed is dead when the last tick is older than the threshold. */
  boolean feedDead(long millisSinceLastTick) {
    return millisSinceLastTick >= noTickAuditSeconds * 1000;
  }

  private static void awaitUninterruptibly(java.util.concurrent.CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void dispatchTick(
      String targetWfId, String signalName, String subscriptionId, EquityTick tick) {
    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(targetWfId);
      stub.signal(signalName, tick);
    } catch (WorkflowNotFoundException notFound) {
      tearDown(subscriptionId, targetWfId, "target workflow closed");
    } catch (Exception ignored) {
      // Best-effort tick dispatch — transient errors are not surfaced (premium-activity precedent).
    }
  }

  /** Loud audit + subscription teardown on a dropped/closed target. */
  private void tearDown(String subscriptionId, String targetWfId, String reason) {
    if (subscriptionId == null) {
      return;
    }
    ScheduledFuture<?> watchdogTask = watchdogTasks.remove(subscriptionId);
    if (watchdogTask != null) {
      watchdogTask.cancel(false);
    }
    Subscription sub = active.remove(subscriptionId);
    if (sub != null) {
      log.error(
          "AUDIT equity-subscription-dropped: subscription_id={} target_wf={} reason={}",
          subscriptionId,
          targetWfId,
          reason);
      sub.close();
    }
  }

  boolean isRegularTradingHours() {
    // Shared with the #776 boot-recovery gate — the check lives in MarketHours so they can't drift.
    return MarketHours.isRegularTradingHours(ZonedDateTime.now(clock).withZoneSameInstant(ET));
  }

  EquityTick toEquityTick(Tick t) {
    EquityTick out = new EquityTick();
    out.setSchemaVersion(1L);
    out.setTicker(t.occSymbol());
    out.setLast(t.premium());
    out.setRetrievedAt(t.retrievedAt());
    // Wall-clock age check (activity thread, NOT workflow code): a print older than MAX_TICK_AGE is
    // marked stale so the workflow's feed-stale guard drops it. Uses the injected clock so the
    // measure is deterministic under test and real wall time in production.
    out.setStale(
        Duration.between(t.retrievedAt().toInstant(), Instant.now(clock)).compareTo(MAX_TICK_AGE)
            > 0);
    return out;
  }

  /**
   * Per-subscription throttle + liveness state. Liveness uses wall-clock {@link
   * System#currentTimeMillis()} (not the injected RTH {@code clock}) so the dead-feed watchdog
   * measures real elapsed time even when tests pin the clock for the RTH gate.
   */
  static final class ThrottleState {
    final AtomicReference<BigDecimal> lastEmitted = new AtomicReference<>();
    private volatile long lastTickMillis = System.currentTimeMillis();

    void markTickSeen() {
      lastTickMillis = System.currentTimeMillis();
    }

    long millisSinceLastTick() {
      return System.currentTimeMillis() - lastTickMillis;
    }
  }
}
