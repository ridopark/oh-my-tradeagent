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
 * <p>It emits the first tick (seeding the baseline) and thereafter only when the mid OR the bid has
 * moved at least {@code premium-emit-delta-pct} from the LAST EMITTED value of that same side —
 * delta from last emitted, not per-tick delta, so a slow monotonic drift still emits every step
 * rather than never. Both sides are gated because the tick carries the mid but the watchlist exit
 * path evaluates the bid; see {@link #shouldEmit}.
 *
 * <p><b>The residual, stated precisely.</b> A move INSIDE the band is not observed until the price
 * leaves the band — bounded in price, unbounded in TIME. It cannot compound past one step, because
 * the baseline advances on every emission, so a breach is acted on within {@code delta} of the true
 * threshold rather than missed outright. For a stop that is a bounded worse fill (~2.7 cents on a
 * $2.70 contract at the 1% default), which is small against a 35% giveback — but it is a real cost,
 * not a free one.
 *
 * <p>This mirrors {@code SubscribeEquityActivityImpl}, whose javadoc already named this activity as
 * the one lacking it.
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

  /**
   * Dedup index: (contractSymbol, positionWorkflowId) -> subscription_id (#776).
   *
   * <p>A SECOND live subscription for the same pair is never wanted — both feed the same workflow,
   * so a duplicate double-delivers every NBBO print, doubles the signal rate on a workflow with no
   * continue-as-new, and lets ONE market print satisfy a debounce that is supposed to require
   * consecutive independent ticks. {@code PositionWorkflowImpl} documents the hazard and dodges it
   * by setting trail state directly instead of re-subscribing; the #776 boot recovery cannot use
   * that dodge, so the guarantee has to live here.
   *
   * <p>Keyed on the PAIR, never on the contract alone: two tenants' PositionWorkflows on the same
   * OCC are independent subscribers and must both be fed (prod_real and prod-jinchul hold the same
   * TSLA contract today).
   */
  private final ConcurrentHashMap<String, String> subscriptionIdByKey = new ConcurrentHashMap<>();

  /**
   * NUL-separated so it cannot collide: an OCC carries spaces, a workflow id carries '/' and ':'.
   */
  private static String dedupKey(String occSymbol, String positionWorkflowId) {
    return occSymbol + '\u0000' + positionWorkflowId;
  }

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

  /**
   * The last emitted mid AND bid, advanced together. One reference rather than two so the pair can
   * never tear: two independent CAS'd fields would let one thread advance the mid while another
   * advances the bid, leaving a baseline that was never an actual quote.
   */
  record Baseline(BigDecimal mid, BigDecimal bid) {}

  /** Per-subscription emit baseline. */
  static final class ThrottleState {
    final AtomicReference<Baseline> lastEmitted = new AtomicReference<>();
  }

  private final ConcurrentHashMap<String, ThrottleState> throttles = new ConcurrentHashMap<>();

  /**
   * Emit the first tick, then only when the mid OR the bid has moved at least {@code emitDeltaPct}
   * from ITS OWN last emitted value.
   *
   * <p><b>Why the bid is gated separately.</b> The premium carried on a tick is the MID ({@code
   * AlpacaMarketData} builds it from {@code q.mid()}), but {@code PositionWorkflowImpl
   * .processExitTick} evaluates {@code tick.getBid()} — the -1R stop, the +2R target and the
   * post-target trail are all bid-driven. A widening book moves the bid a long way while the mid
   * sits still, because the mid is the average of the two sides moving apart: 2.70x2.90 drifting to
   * 1.35x4.25 holds the mid at 2.80 throughout while the bid falls through a 1.40 stop. Gating on
   * the mid alone would suppress every one of those ticks and the stop would never fire, with the
   * error unbounded in bid terms. So the throttle must gate on what the workflow actually
   * evaluates, not merely on a price that summarises it.
   *
   * <p>Emitting on EITHER keeps the history-ceiling argument intact: a tick is still only emitted
   * when something the workflow reads has genuinely moved.
   *
   * <p>The compare-and-set is load-bearing rather than defensive: the provider drives this from a
   * feed thread, and two ticks racing the same baseline would otherwise both read the old value and
   * both emit — reintroducing exactly the history growth being bounded. Only the thread that
   * successfully advances the baseline emits. Same shape as the equity throttle.
   */
  boolean shouldEmit(ThrottleState throttle, BigDecimal mid, BigDecimal bid) {
    if (mid == null || mid.signum() <= 0) {
      return false; // a non-positive premium is not a price; never seed or emit on it
    }
    while (true) {
      Baseline prev = throttle.lastEmitted.get();
      if (prev != null && !movedEnough(prev.mid(), mid) && !movedEnough(prev.bid(), bid)) {
        return false;
      }
      if (throttle.lastEmitted.compareAndSet(prev, new Baseline(mid, bid))) {
        return true;
      }
    }
  }

  /**
   * Has {@code now} moved at least {@code emitDeltaPct} from {@code prev}?
   *
   * <p>An unmeasurable side (absent or non-positive baseline, absent current value) answers "no
   * reason to emit" rather than "emit": it is not evidence of movement, and the other side still
   * governs. A vanishing bid is not silently tolerated here — {@code AlpacaMarketData} rejects a
   * no-bid quote outright, so one never reaches this throttle.
   */
  private boolean movedEnough(BigDecimal prev, BigDecimal now) {
    if (prev == null || prev.signum() <= 0 || now == null) {
      return false;
    }
    return now.subtract(prev).abs().compareTo(prev.multiply(emitDeltaPct).abs()) >= 0;
  }

  @Override
  public SubscribePremiumResult subscribePremium(SubscribePremiumRequest req) {
    SubscribePremiumResult result = new SubscribePremiumResult();
    result.setSchemaVersion(1L);
    result.setSubscribedAt(OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    try {
      String posWfId = req.getPositionWorkflowId();
      String occSymbol = req.getContractSymbol();
      String key = dedupKey(occSymbol, posWfId);
      // compute() holds the per-key bin lock, so the "is one already live?" check and the
      // provider subscribe are atomic against a concurrent subscribe for the same pair — two
      // callers cannot both see "absent" and both open one. Same discipline as
      // AlpacaMarketData.startPremiumPoll. The provider subscribe is in-process (registers a
      // listener, starts a poll); no network call happens under this lock.
      String subscriptionId =
          subscriptionIdByKey.compute(
              key,
              (k, existingId) -> {
                // Reuse only a subscription that is still LIVE. A torn-down id lingering in the
                // index must not be handed back as if it were feeding anything.
                if (existingId != null && active.containsKey(existingId)) {
                  // LOUD on purpose. Re-arming from /live is the operator's repair for an orphaned
                  // trail, and before this dedup existed a re-arm always opened a fresh
                  // subscription. Now it can legitimately do nothing, so an operator whose re-arm
                  // appeared to succeed must be able to see WHY nothing changed.
                  log.info(
                      "subscribePremium reuse: occ={} wf={} existing_subscription_id={} "
                          + "(no new subscription opened)",
                      occSymbol,
                      posWfId,
                      existingId);
                  return existingId;
                }
                final String[] subIdHolder = new String[1];
                Subscription sub =
                    provider.subscribePremium(
                        occSymbol,
                        tick -> {
                          // Throttle on the FEED thread, before the dispatcher hop: a suppressed
                          // tick must cost nothing downstream, and the CAS is what makes that safe
                          // under concurrency.
                          ThrottleState throttle =
                              throttles.computeIfAbsent(posWfId, t -> new ThrottleState());
                          if (!shouldEmit(throttle, tick.premium(), tick.bid())) {
                            return;
                          }
                          dispatcher.submit(
                              () ->
                                  dispatchTick(
                                      occSymbol, posWfId, subIdHolder[0], toPremiumTick(tick)));
                        });
                subIdHolder[0] = sub.subscriptionId();
                active.put(sub.subscriptionId(), sub);
                return sub.subscriptionId();
              });
      // NOTE: reuse deliberately does NOT touch `throttles`. Resetting the emit baseline on a
      // re-subscribe would let the next in-band tick through, and #776 recovery re-subscribes the
      // whole armed book at once — that is a burst into workflows with no continue-as-new.
      result.setSubscriptionId(subscriptionId);
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

  private void dispatchTick(
      String occSymbol, String posWfId, String subscriptionId, PremiumTick tick) {
    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(posWfId);
      stub.signal("chandelierTick", tick);
    } catch (WorkflowNotFoundException notFound) {
      // Target workflow has closed; tear down the subscription so we stop fanning out.
      tearDown(occSymbol, posWfId, subscriptionId);
    } catch (Exception ignored) {
      // Best-effort tick dispatch — Phase 4 deliberately does not surface transient errors.
    }
  }

  /**
   * Detach one subscription and drop every index entry that referenced it. Package-private so the
   * dedup tests exercise the REAL tear-down rather than a test-only twin.
   *
   * <p>The dedup entry is removed with the two-arg {@code remove(key, expected)} so a re-subscribe
   * that already replaced it (recovery racing a manual re-arm) is not clobbered by a late tear-down
   * of the OLD subscription.
   */
  void tearDown(String occSymbol, String posWfId, String subscriptionId) {
    if (subscriptionId == null) {
      return;
    }
    Subscription sub = active.remove(subscriptionId);
    if (sub == null) {
      // Already torn down, or this id was SUPERSEDED by a newer subscription for the same pair.
      // Returning here is load-bearing: `throttles` is keyed on the workflow id alone, so an
      // unconditional clear would wipe the LIVE subscription's emit baseline and let the next
      // in-band tick through — the exact invariant secondSubscribe_doesNotResetTheThrottleBaseline
      // exists to protect, defeated from the teardown side.
      subscriptionIdByKey.remove(dedupKey(occSymbol, posWfId), subscriptionId);
      return;
    }
    sub.close();
    subscriptionIdByKey.remove(dedupKey(occSymbol, posWfId), subscriptionId);
    // Drop the baseline too, or every closed position leaks one entry for the worker's life.
    // Note this is the ONLY teardown path (Phase 4 has no explicit unsubscribe), so a baseline
    // is reclaimed when a later dispatch discovers the workflow is gone — not at close itself.
    throttles.remove(posWfId);
  }

  /**
   * Reproduces the window inside {@link #tearDown} where {@code active.remove} and {@code
   * sub.close()} have happened but the dedup-index removal has NOT — the interleaving a concurrent
   * subscribe actually observes. Package-private and test-only: the window is otherwise unreachable
   * from outside, and both guards covering it survived a mutation sweep without it.
   */
  void simulateTearDownRaceForTest(String subscriptionId) {
    Subscription sub = active.remove(subscriptionId);
    if (sub != null) {
      sub.close();
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
