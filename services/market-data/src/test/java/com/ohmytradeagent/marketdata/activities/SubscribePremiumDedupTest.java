package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * #776 Phase 1: {@code subscribePremium} must be idempotent per (contract, positionWorkflowId).
 *
 * <p>A second live subscription for the same pair is never wanted — both feed the SAME workflow, so
 * a duplicate double-delivers every NBBO print. {@code PositionWorkflowImpl} documents the hazard
 * and works around it by setting trail state directly rather than re-subscribing. The #776 boot
 * recovery cannot take that workaround (it has no workflow-side state to set), so the dedup has to
 * live here — otherwise recovery races an operator's manual re-arm and opens the second
 * subscription itself.
 *
 * <p>Deliberately does NOT use TestWorkflowEnvironment: the assertion is about how many
 * subscriptions the PROVIDER sees, which needs no Temporal at all.
 */
class SubscribePremiumDedupTest {

  private static final String OCC = "DRAM  270319C00100000";
  private static final String WF_A = "t-prod_real/s-copytrade-v1/pos/DRAM/x:0";
  private static final String WF_B = "t-prod-jinchul/s-copytrade-v1/pos/DRAM/y:0";

  /**
   * Counts provider-side subscriptions and lets a test push ticks into the registered listeners.
   */
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
    public Subscription subscribeEquity(String ticker, Consumer<Tick> onTick) {
      throw new UnsupportedOperationException();
    }

    @Override
    public synchronized Subscription subscribePremium(String occSymbol, Consumer<Tick> onTick) {
      subscribeCalls.incrementAndGet();
      listeners.computeIfAbsent(occSymbol, k -> new ArrayList<>()).add(onTick);
      String id = "sub-" + (nextId++);
      return new Subscription() {
        @Override
        public String subscriptionId() {
          return id;
        }

        @Override
        public void close() {
          closeCalls.incrementAndGet();
          listeners.getOrDefault(occSymbol, List.of()).remove(onTick);
        }
      };
    }

    void push(String occSymbol, String mid, String bid) {
      Tick t =
          new Tick(
              occSymbol,
              new BigDecimal(mid),
              new BigDecimal(bid),
              new BigDecimal(bid).add(new BigDecimal("0.10")),
              OffsetDateTime.parse("2026-08-20T14:00:00Z"));
      for (Consumer<Tick> l : List.copyOf(listeners.getOrDefault(occSymbol, List.of()))) {
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

  private static SubscribePremiumRequest req(String occ, String wfId) {
    SubscribePremiumRequest r = new SubscribePremiumRequest();
    r.setSchemaVersion(1L);
    r.setTenantId("t");
    r.setStrategyId("s");
    r.setContractSymbol(occ);
    r.setPositionWorkflowId(wfId);
    return r;
  }

  private static SubscribePremiumActivityImpl activity(
      CountingProvider p, ExecutorService ex, String deltaPct) {
    return new SubscribePremiumActivityImpl(p, null, ex, new BigDecimal(deltaPct));
  }

  @Test
  void secondSubscribeForSameOccAndWorkflow_reusesTheSubscription() {
    CountingProvider p = new CountingProvider();
    SubscribePremiumActivityImpl a = activity(p, new CountingExecutor(), "0.01");

    SubscribePremiumResult first = a.subscribePremium(req(OCC, WF_A));
    SubscribePremiumResult second = a.subscribePremium(req(OCC, WF_A));

    assertThat(first.getStatus()).isEqualTo(SubscribePremiumResult.Status.SUBSCRIBED);
    assertThat(second.getStatus()).isEqualTo(SubscribePremiumResult.Status.SUBSCRIBED);
    assertThat(second.getSubscriptionId()).isEqualTo(first.getSubscriptionId());
    assertThat(p.subscribeCalls.get()).isEqualTo(1);
    assertThat(p.listeners.get(OCC)).hasSize(1);
  }

  /**
   * A re-subscribe that reset the emit baseline would let the next in-band tick through, and #776's
   * recovery re-subscribes the whole armed book at once — that is a burst, on workflows with no
   * continue-as-new.
   */
  @Test
  void secondSubscribe_doesNotResetTheThrottleBaseline() {
    CountingProvider p = new CountingProvider();
    CountingExecutor ex = new CountingExecutor();
    SubscribePremiumActivityImpl a = activity(p, ex, "0.10"); // 10% band, easy to stay inside

    a.subscribePremium(req(OCC, WF_A));
    p.push(OCC, "3.00", "2.95"); // seeds the baseline -> emits
    assertThat(ex.submitted.get()).isEqualTo(1);

    a.subscribePremium(req(OCC, WF_A)); // re-subscribe
    p.push(OCC, "3.01", "2.96"); // well inside the 10% band

    assertThat(ex.submitted.get()).isEqualTo(1);
  }

  /**
   * The multi-tenant case, and the reason the key cannot be the OCC alone: prod_real and
   * prod-jinchul hold the SAME TSLA contract today. Collapsing them starves one workflow's trail.
   */
  @Test
  void sameOccDifferentWorkflows_bothGetTheirOwnSubscription() {
    CountingProvider p = new CountingProvider();
    CountingExecutor ex = new CountingExecutor();
    SubscribePremiumActivityImpl a = activity(p, ex, "0");

    SubscribePremiumResult ra = a.subscribePremium(req(OCC, WF_A));
    SubscribePremiumResult rb = a.subscribePremium(req(OCC, WF_B));

    assertThat(ra.getSubscriptionId()).isNotEqualTo(rb.getSubscriptionId());
    assertThat(p.subscribeCalls.get()).isEqualTo(2);

    p.push(OCC, "3.00", "2.95");
    assertThat(ex.submitted.get()).isEqualTo(2); // BOTH workflows fed
  }

  @Test
  void differentOccSameWorkflow_bothSubscribe() {
    CountingProvider p = new CountingProvider();
    SubscribePremiumActivityImpl a = activity(p, new CountingExecutor(), "0.01");

    a.subscribePremium(req(OCC, WF_A));
    a.subscribePremium(req("TSLA  260918P00300000", WF_A));

    assertThat(p.subscribeCalls.get()).isEqualTo(2);
  }

  // --- the tearDown/reuse interleaving. tearDown does active.remove -> close -> index.remove, so
  // there is a window where the index still names a subscription that is already dead, and a
  // window where a LATE teardown of a superseded id can touch state owned by the live one. All
  // three tests below survived the original suite; each is paired with the guard it kills.

  /**
   * The {@code active.containsKey(existingId)} liveness check. Without it, reuse hands back an id
   * whose subscription is already closed — the caller believes it is subscribed and no feed exists.
   * Reproduces the real interleaving: active/close done, index removal not yet.
   */
  @Test
  void reuseMustNotHandBackADeadSubscription() {
    CountingProvider p = new CountingProvider();
    SubscribePremiumActivityImpl a = activity(p, new CountingExecutor(), "0.01");

    String firstId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();
    // Mid-teardown state: removed from `active` and closed, dedup index not yet cleared.
    a.simulateTearDownRaceForTest(firstId);

    String secondId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();

    assertThat(secondId).isNotEqualTo(firstId);
    assertThat(p.subscribeCalls.get()).isEqualTo(2);
  }

  /**
   * The two-arg {@code remove(key, expected)}. With a one-arg remove, a late teardown of a
   * SUPERSEDED id evicts the index entry naming the LIVE subscription — so the next subscribe opens
   * a SECOND live feed for the same pair, which is precisely what this whole change prevents.
   */
  @Test
  void lateTearDownOfASupersededIdMustNotOrphanTheLiveIndexEntry() {
    CountingProvider p = new CountingProvider();
    SubscribePremiumActivityImpl a = activity(p, new CountingExecutor(), "0.01");

    String firstId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();
    a.simulateTearDownRaceForTest(firstId);
    String liveId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();

    // The OLD subscription's dispatcher finally notices the workflow is gone and tears down.
    a.tearDown(OCC, WF_A, firstId);

    // A subsequent subscribe must still reuse the LIVE one, not open a third feed.
    String afterId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();
    assertThat(afterId).isEqualTo(liveId);
    assertThat(p.subscribeCalls.get()).isEqualTo(2);
    assertThat(p.listeners.get(OCC)).hasSize(1);
  }

  /**
   * {@code throttles} is keyed on the workflow id ALONE, so an unconditional clear in teardown
   * wipes the LIVE subscription's emit baseline and lets the next in-band tick through — defeating
   * {@link #secondSubscribe_doesNotResetTheThrottleBaseline} from the teardown side.
   */
  @Test
  void staleTearDownMustNotWipeTheLiveThrottleBaseline() {
    CountingProvider p = new CountingProvider();
    CountingExecutor ex = new CountingExecutor();
    SubscribePremiumActivityImpl a = activity(p, ex, "0.10");

    String firstId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();
    a.simulateTearDownRaceForTest(firstId);
    a.subscribePremium(req(OCC, WF_A)); // live subscription
    p.push(OCC, "3.00", "2.95"); // seeds the baseline
    assertThat(ex.submitted.get()).isEqualTo(1);

    a.tearDown(OCC, WF_A, firstId); // late teardown of the SUPERSEDED id

    p.push(OCC, "3.01", "2.96"); // well inside the 10% band
    assertThat(ex.submitted.get()).isEqualTo(1);
  }

  /** After tear-down the pair must be re-subscribable; a stale id must not be handed back. */
  @Test
  void afterTearDown_aFreshSubscribeOpensANewSubscription() {
    CountingProvider p = new CountingProvider();
    SubscribePremiumActivityImpl a = activity(p, new CountingExecutor(), "0.01");

    String firstId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();
    a.tearDown(OCC, WF_A, firstId);

    String secondId = a.subscribePremium(req(OCC, WF_A)).getSubscriptionId();

    assertThat(secondId).isNotEqualTo(firstId);
    assertThat(p.subscribeCalls.get()).isEqualTo(2);
  }
}
