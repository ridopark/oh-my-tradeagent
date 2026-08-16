package com.ohmytradeagent.marketdata.activities;

import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Subscription;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 4 / Phase 2c.2 worker-side implementation of {@link SubscribePremiumActivity}. Subscribes a
 * tick consumer on the Spring-wired {@link MarketDataProvider} (in-memory by default; Alpaca when
 * {@code MARKET_DATA_PROVIDER=alpaca}) and signals each tick into the named PositionWorkflow as
 * {@code chandelierTick}.
 *
 * <p>Signal dispatch runs on a worker-owned executor to keep the stream callback (which may be
 * driven by a feed thread) decoupled from Temporal RPC latency.
 *
 * <p>{@code subscribePremium} swallows source-side exceptions and returns FAILED so the workflow
 * can audit and proceed without a trail (instead of going into Temporal retry).
 *
 * <p><b>Min-move throttle.</b> Every tick signalled here becomes a Temporal history event on the
 * target PositionWorkflow, and that workflow — uniquely among the long-lived ones here — has NO
 * continue-as-new. At the premium feed's fixed ~2s poll an armed position generates ~11,700 signals
 * per RTH day, past this repo's own 10,000-event watermark inside a single trading day, on a
 * workflow that is explicitly multi-day (it sleeps overnight and resumes when {@code
 * eod_force_flatten=false}). So the throttle is not a performance nicety: without it an armed trail
 * walks a real-money position into Temporal's history limits.
 *
 * <p>It emits the first tick (seeding the baseline) and thereafter only when the premium has moved
 * at least {@code premium-emit-delta-pct} from the LAST EMITTED price — delta from last emitted,
 * not per-tick delta, so a slow monotonic drift still emits every step rather than never. That
 * bounds the trail's blind spot to one step: a breach is observed within {@code delta} of the true
 * threshold, never missed outright. This mirrors {@code SubscribeEquityActivityImpl}, whose javadoc
 * already named this activity as the one lacking it.
 */
@Component
public class SubscribePremiumActivityImpl implements SubscribePremiumActivity {

  private static final Logger log = LoggerFactory.getLogger(SubscribePremiumActivityImpl.class);

  private final MarketDataProvider provider;
  private final WorkflowClient workflowClient;
  private final ExecutorService dispatcher;

  /**
   * Fraction of the last emitted premium a tick must move before it is signalled. 1% by default:
   * small enough that it cannot mask a trail breach in any meaningful way (the tightest giveback
   * the workflow accepts is far wider), large enough to collapse the flat stretches that dominate a
   * poll-driven feed. Configurable so it can be tightened without a redeploy of judgement.
   */
  private final BigDecimal emitDeltaPct;

  /**
   * Subscription registry keyed by subscription_id. Phase 4 had no explicit unsubscribe path beyond
   * the workflow-not-found self-tear-down; we keep that pattern here.
   */
  private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();

  public SubscribePremiumActivityImpl(
      MarketDataProvider provider,
      WorkflowClient workflowClient,
      ExecutorService dispatcher,
      @Value("${market-data.premium-emit-delta-pct:0.01}") BigDecimal emitDeltaPct) {
    this.provider = provider;
    this.workflowClient = workflowClient;
    this.dispatcher = dispatcher;
    this.emitDeltaPct = emitDeltaPct;
  }

  /** Per-subscription emit baseline. */
  static final class ThrottleState {
    final AtomicReference<BigDecimal> lastEmitted = new AtomicReference<>();
  }

  private final ConcurrentHashMap<String, ThrottleState> throttles = new ConcurrentHashMap<>();

  /**
   * Emit the first tick, then only on a move of at least {@code emitDeltaPct} from the last EMITTED
   * premium.
   *
   * <p>The compare-and-set is load-bearing rather than defensive: the provider drives this from a
   * feed thread, and two ticks racing the same baseline would otherwise both read the old value and
   * both emit — reintroducing exactly the history growth being bounded. Only the thread that
   * successfully advances the baseline emits. Same shape as the equity throttle.
   */
  boolean shouldEmit(ThrottleState throttle, BigDecimal premium) {
    if (premium == null || premium.signum() <= 0) {
      return false; // a non-positive premium is not a price; never seed or emit on it
    }
    while (true) {
      BigDecimal prev = throttle.lastEmitted.get();
      if (prev != null && prev.signum() > 0) {
        BigDecimal minMove = prev.multiply(emitDeltaPct).abs();
        if (premium.subtract(prev).abs().compareTo(minMove) < 0) {
          return false;
        }
      }
      if (throttle.lastEmitted.compareAndSet(prev, premium)) {
        return true;
      }
    }
  }

  @Override
  public SubscribePremiumResult subscribePremium(SubscribePremiumRequest req) {
    SubscribePremiumResult result = new SubscribePremiumResult();
    result.setSchemaVersion(1L);
    result.setSubscribedAt(OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    try {
      String posWfId = req.getPositionWorkflowId();
      final String[] subIdHolder = new String[1];
      Subscription sub =
          provider.subscribePremium(
              req.getContractSymbol(),
              tick -> {
                // Throttle on the FEED thread, before the dispatcher hop: a suppressed tick must
                // cost nothing downstream, and the CAS is what makes that safe under concurrency.
                ThrottleState throttle =
                    throttles.computeIfAbsent(posWfId, k -> new ThrottleState());
                if (!shouldEmit(throttle, tick.premium())) {
                  return;
                }
                dispatcher.submit(() -> dispatchTick(posWfId, subIdHolder[0], toPremiumTick(tick)));
              });
      subIdHolder[0] = sub.subscriptionId();
      active.put(sub.subscriptionId(), sub);
      result.setSubscriptionId(sub.subscriptionId());
      result.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
      return result;
    } catch (RuntimeException e) {
      log.warn(
          "subscribePremium failed for tenant={} strategy={} symbol={}: {}",
          req.getTenantId(),
          req.getStrategyId(),
          req.getContractSymbol(),
          e.getMessage());
      result.setSubscriptionId("");
      result.setStatus(SubscribePremiumResult.Status.FAILED);
      result.setError(e.getMessage());
      return result;
    }
  }

  private void dispatchTick(String posWfId, String subscriptionId, PremiumTick tick) {
    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(posWfId);
      stub.signal("chandelierTick", tick);
    } catch (WorkflowNotFoundException notFound) {
      // Target workflow has closed; tear down the subscription so we stop fanning out.
      if (subscriptionId != null) {
        Subscription sub = active.remove(subscriptionId);
        if (sub != null) {
          sub.close();
        }
        // Drop the baseline too, or every closed position leaks one entry for the worker's life.
        throttles.remove(posWfId);
      }
    } catch (Exception ignored) {
      // Best-effort tick dispatch — Phase 4 deliberately does not surface transient errors.
    }
  }

  private static PremiumTick toPremiumTick(com.ohmytradeagent.marketdata.provider.Tick t) {
    PremiumTick out = new PremiumTick();
    out.setSchemaVersion(1L);
    out.setContractSymbol(t.occSymbol());
    out.setPremium(t.premium());
    out.setBid(t.bid());
    out.setAsk(t.ask());
    out.setRetrievedAt(t.retrievedAt());
    return out;
  }
}
