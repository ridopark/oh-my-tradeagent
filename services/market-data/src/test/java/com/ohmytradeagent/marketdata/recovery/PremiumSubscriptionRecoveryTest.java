package com.ohmytradeagent.marketdata.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.marketdata.MarketHours;
import com.ohmytradeagent.marketdata.activities.SubscribePremiumActivityImpl;
import com.ohmytradeagent.marketdata.alert.DiscordWebhookClient;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * #776 Phase 2: RTH-gated retry recovery of armed premium subscriptions. Fake {@code
 * WorkflowClient} listing + stubbed queries, REAL {@code SubscribePremiumActivityImpl} over an
 * in-memory counting provider (the shared-path/dedup assertions depend on the real activity),
 * pinned mutable {@code Clock}, fake sleeper.
 */
class PremiumSubscriptionRecoveryTest {

  private static final ZoneId ET = MarketHours.ET;

  /** Wednesday 2026-08-19 10:00 ET — squarely inside RTH. */
  private static final Instant RTH_WEDNESDAY =
      ZonedDateTime.of(2026, 8, 19, 10, 0, 0, 0, ET).toInstant();

  /** Unexpired (2027) OCCs, padded canonical form. */
  private static final String OCC_TRAIL = "DRAM  270319C00100000";

  private static final String OCC_WATCH = "NVDA  270115C00140000";
  private static final String OCC_NONE = "AAPL  270618C00190000";

  private static final String WF_TRAIL = "t-prod_real/s-copytrade-v1/pos/DRAM/a:0";
  private static final String WF_WATCH = "t-staging_paper/s-watchlist-v1/pos/NVDA/b:0";
  private static final String WF_NONE = "t-prod_real/s-copytrade-v1/pos/AAPL/c:0";

  private final WorkflowClient client = mock(WorkflowClient.class);
  private final DiscordWebhookClient alerts = mock(DiscordWebhookClient.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CountingProvider provider = new CountingProvider();
  private final SubscribePremiumActivityImpl activity =
      new SubscribePremiumActivityImpl(
          provider, null, new CountingExecutor(), new BigDecimal("0.01"));

  private final MutableClock clock = new MutableClock(RTH_WEDNESDAY, ET);
  private final RecordingSleeper sleeper = new RecordingSleeper();
  private final List<String> events = new ArrayList<>();
  private final Map<String, WorkflowStub> stubs = new LinkedHashMap<>();

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    logger().addAppender(logs);
  }

  @AfterEach
  void detachLogs() {
    logger().detachAppender(logs);
  }

  private static ch.qos.logback.classic.Logger logger() {
    return (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(PremiumSubscriptionRecovery.class);
  }

  private String allLogs() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent e : List.copyOf(logs.list)) {
      sb.append(e.getFormattedMessage()).append('\n');
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // fixture plumbing
  // ---------------------------------------------------------------------------

  private PremiumSubscriptionRecovery recovery() {
    return recovery(activity, 5, Duration.ofSeconds(30), 20, Duration.ofSeconds(120));
  }

  private PremiumSubscriptionRecovery recovery(
      SubscribePremiumActivityImpl act,
      int maxAttempts,
      Duration backoff,
      int cap,
      Duration deadline) {
    return new PremiumSubscriptionRecovery(
        client, act, alerts, registry, maxAttempts, backoff, cap, deadline, clock, sleeper);
  }

  /** Wires the visibility listing, in the given (newest-first) order. */
  private void wireList(String... workflowIds) {
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv -> {
              events.add("list");
              return Stream.of(workflowIds).map(PremiumSubscriptionRecoveryTest::metadata);
            });
  }

  private static WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }

  private WorkflowStub stubFor(String workflowId) {
    return stubs.computeIfAbsent(
        workflowId,
        id -> {
          WorkflowStub stub = mock(WorkflowStub.class);
          when(client.newUntypedWorkflowStub(eq(id))).thenReturn(stub);
          return stub;
        });
  }

  private void wireArmed(String workflowId, String occ, boolean trailingArmed, boolean exitArmed) {
    WorkflowStub stub = stubFor(workflowId);
    when(stub.query(eq("exitProximity"), eq(ExitProximityViewMirror.class), any(Object[].class)))
        .thenReturn(new ExitProximityViewMirror(occ, trailingArmed, exitArmed));
    wireState(workflowId, occ, 5L);
  }

  private void wireState(String workflowId, String occ, long remainingQty) {
    WorkflowStub stub = stubFor(workflowId);
    when(stub.query(eq("positionState"), eq(PositionStateViewMirror.class), any(Object[].class)))
        .thenReturn(new PositionStateViewMirror(occ, remainingQty));
  }

  // ---------------------------------------------------------------------------
  // T1 — both trail kinds, recovered through the SAME activity path
  // ---------------------------------------------------------------------------

  @Test
  void recoversChandelierAndWatchlistTrails_throughTheActivityPath() {
    wireList(WF_NONE, WF_WATCH, WF_TRAIL);
    wireArmed(WF_TRAIL, OCC_TRAIL, true, false);
    wireArmed(
        WF_WATCH, OCC_WATCH, false, true); // watchlist exit-only: `armed`, not `trailingArmed`
    wireArmed(WF_NONE, OCC_NONE, false, false);

    PremiumSubscriptionRecovery.Sweep sweep = recovery().sweepOnce();

    assertThat(sweep.recovered()).isEqualTo(2);
    assertThat(sweep.skippedUnarmed()).isEqualTo(1);
    assertThat(provider.subscribeCalls.get()).isEqualTo(2);
    assertThat(provider.listeners.keySet()).containsExactlyInAnyOrder(OCC_TRAIL, OCC_WATCH);

    // Shared-path proof via the activity's dedup registry: a MANUAL re-arm for the same pair now
    // reuses the recovery-opened subscription. If recovery had gone straight to the provider, the
    // activity's registry would not know the pair and this call would open a THIRD subscription.
    SubscribePremiumResult manual = activity.subscribePremium(request(OCC_TRAIL, WF_TRAIL));
    assertThat(manual.getStatus()).isEqualTo(SubscribePremiumResult.Status.SUBSCRIBED);
    assertThat(provider.subscribeCalls.get()).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // T2 — FAILED tallies as failed, never as skipped_unarmed
  // ---------------------------------------------------------------------------

  @Test
  void failedSubscribeTalliesAsFailed_notSkippedUnarmed() {
    wireList(WF_TRAIL);
    wireArmed(WF_TRAIL, OCC_TRAIL, true, false);
    provider.failingOccs.add(OCC_TRAIL); // provider throws -> activity returns Status.FAILED

    PremiumSubscriptionRecovery.Sweep sweep = recovery().sweepOnce();

    assertThat(sweep.failed()).isEqualTo(1);
    assertThat(sweep.skippedUnarmed()).isZero();
    assertThat(allLogs()).contains("failed=1").contains("skipped_unarmed=0");
  }

  // ---------------------------------------------------------------------------
  // T3 — headline safety: the ready listener never touches Temporal on the boot thread
  // ---------------------------------------------------------------------------

  @Test
  void readyListenerReturnsWithoutTouchingTemporal() throws Exception {
    List<Thread> listingThreads = new ArrayList<>();
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv -> {
              synchronized (listingThreads) {
                listingThreads.add(Thread.currentThread());
              }
              return Stream.<WorkflowExecutionMetadata>empty();
            });

    PremiumSubscriptionRecovery recovery = recovery();
    recovery.onApplicationReady(); // must return; Temporal work happens on the loop thread only

    Thread loop = recovery.loopThreadForTest();
    assertThat(loop).isNotNull();
    assertThat(loop).isNotSameAs(Thread.currentThread());
    assertThat(loop.isDaemon()).isTrue();
    loop.join(5_000);
    assertThat(loop.isAlive()).isFalse();

    synchronized (listingThreads) {
      assertThat(listingThreads).isNotEmpty(); // the sweep DID run…
      assertThat(listingThreads).doesNotContain(Thread.currentThread()); // …but never on the caller
    }
    assertThat(allLogs()).contains("premium-recovery-started");
  }

  // ---------------------------------------------------------------------------
  // T4 — Temporal down at boot: never throws, retries, then recovers
  // ---------------------------------------------------------------------------

  @Test
  void temporalDownAtBoot_neverThrows_retriesThenRecovers() {
    AtomicInteger listings = new AtomicInteger();
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv -> {
              if (listings.incrementAndGet() <= 2) {
                throw new RuntimeException("temporal down");
              }
              return Stream.of(metadata(WF_TRAIL));
            });
    wireArmed(WF_TRAIL, OCC_TRAIL, true, false);

    recovery().runLoop(); // must not throw

    assertThat(listings.get()).isEqualTo(3);
    assertThat(provider.subscribeCalls.get()).isEqualTo(1);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(30));
    assertThat(registry.counter("omo_trail_recovery_attempts_total").count()).isEqualTo(3.0d);
    assertThat(registry.get("omo_trail_recovery_last_result").gauge().value()).isEqualTo(1.0d);
  }

  // ---------------------------------------------------------------------------
  // T5 — bounded attempts, then alert and give up
  // ---------------------------------------------------------------------------

  @Test
  void givesUpAfterMaxAttempts_withAlert() {
    AtomicInteger listings = new AtomicInteger();
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv -> {
              listings.incrementAndGet();
              throw new RuntimeException("temporal down");
            });

    recovery().runLoop(); // returning IS the thread terminating

    assertThat(listings.get()).isEqualTo(5);
    assertThat(sleeper.sleeps)
        .containsExactly(
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30));
    verify(alerts, times(1)).post(anyString());
    assertThat(registry.get("omo_trail_recovery_last_result").gauge().value()).isEqualTo(0.0d);
  }

  // ---------------------------------------------------------------------------
  // T6 — RTH gate: outside hours nothing happens until the next open
  // ---------------------------------------------------------------------------

  @Test
  void outsideRth_sleepsUntilOpen_andSweepsAfter() {
    clock.set(ZonedDateTime.of(2026, 8, 19, 8, 0, 0, 0, ET).toInstant()); // Wed 08:00 ET
    sleeper.advanceClockOnSleep = clock;
    wireList(WF_TRAIL);
    wireArmed(WF_TRAIL, OCC_TRAIL, true, false);

    recovery().runLoop();

    // The 08:00 assertion: the FIRST event must be the sleep-to-open — no listing, no query, no
    // subscribe happened before it.
    assertThat(events).isNotEmpty();
    assertThat(events.get(0)).isEqualTo("sleep:" + Duration.ofMinutes(90));
    assertThat(events).contains("list");
    assertThat(events.indexOf("list")).isGreaterThan(0);
    assertThat(provider.subscribeCalls.get()).isEqualTo(1);
  }

  @Test
  void weekend_sleepsUntilMondayOpen() {
    clock.set(ZonedDateTime.of(2026, 8, 22, 12, 0, 0, 0, ET).toInstant()); // Saturday noon ET
    sleeper.advanceClockOnSleep = clock;
    wireList(WF_TRAIL);
    wireArmed(WF_TRAIL, OCC_TRAIL, true, false);

    recovery().runLoop();

    // Sat 12:00 -> Mon 09:30 = 45h30m, then the sweep runs.
    assertThat(events.get(0)).isEqualTo("sleep:" + Duration.ofHours(45).plusMinutes(30));
    assertThat(provider.subscribeCalls.get()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // T7 — closed and expired positions are skipped; unparseable OCC fails OPEN
  // ---------------------------------------------------------------------------

  @Test
  void skipsClosedAndExpired() {
    String wfClosed = "t-prod_real/s-copytrade-v1/pos/CLSD/d:0";
    String wfExpPadded = "t-prod_real/s-copytrade-v1/pos/EXP1/e:0";
    String wfExpCompact = "t-prod_real/s-copytrade-v1/pos/EXP2/f:0";
    String wfGarbled = "t-prod_real/s-copytrade-v1/pos/GRBL/g:0";
    String occExpPadded = "TSLA  240918P00300000"; // padded canonical, expired 2024-09-18
    String occExpCompact = "TSLA240918P00300000"; // compact broker form, same expiry
    String occGarbled = "NOT-AN-OCC";

    wireList(wfGarbled, wfExpCompact, wfExpPadded, wfClosed);
    wireArmed(wfClosed, OCC_TRAIL, true, false);
    wireState(wfClosed, OCC_TRAIL, 0L); // fully closed
    wireArmed(wfExpPadded, occExpPadded, true, false);
    wireArmed(wfExpCompact, occExpCompact, true, false);
    wireArmed(wfGarbled, occGarbled, true, false);

    PremiumSubscriptionRecovery.Sweep sweep = recovery().sweepOnce();

    assertThat(sweep.skippedClosed()).isEqualTo(1);
    assertThat(sweep.skippedExpired()).isEqualTo(2); // BOTH the padded AND the compact form
    assertThat(sweep.recovered()).isEqualTo(1); // the unparseable OCC fails OPEN: subscribe runs
    assertThat(sweep.failed()).isZero();
    assertThat(provider.listeners.keySet()).containsExactly(occGarbled);

    // The parser itself, pinned to the BFF's semantics.
    assertThat(PremiumSubscriptionRecovery.parseExpiry(occExpPadded))
        .isEqualTo(LocalDate.of(2024, 9, 18));
    assertThat(PremiumSubscriptionRecovery.parseExpiry(occExpCompact))
        .isEqualTo(LocalDate.of(2024, 9, 18));
    assertThat(PremiumSubscriptionRecovery.parseExpiry(occGarbled)).isNull();
    assertThat(PremiumSubscriptionRecovery.parseExpiry(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // T8 — bounds: cap counts subscriptions (not examinations), deadline bounds the
  //      sweep, processing is oldest-first
  // ---------------------------------------------------------------------------

  /**
   * THE #784-review bug (retry/cap interaction): with MORE armed trails than the per-sweep cap,
   * attempt 2 must recover the REMAINDER — not re-spend the whole cap on dedup-reuses of the
   * positions attempt 1 already subscribed, truncate the same tail again, and give up after
   * maxAttempts with the newest trails permanently orphaned. Already-recovered workflows must be
   * carried across attempts within one recovery run and consume ZERO cap on later sweeps.
   */
  @org.junit.jupiter.api.Test
  void overCapBook_isFullyRecoveredAcrossRetries_notGivenUpOn() {
    List<String> listed = new ArrayList<>();
    for (int i = 25; i >= 1; i--) {
      listed.add(armedWf(i));
    }
    for (int i = 1; i <= 25; i++) {
      wireArmed(armedWf(i), occFor(i), true, false);
    }
    wireList(listed.toArray(String[]::new));

    PremiumSubscriptionRecovery r = recovery(); // cap 20, maxAttempts 5
    r.runLoop();

    // ALL 25 end up subscribed, and the run completes instead of giving up.
    assertThat(provider.listeners.keySet()).hasSize(25);
    assertThat(allLogs()).contains("premium-recovery-complete");
    assertThat(allLogs()).doesNotContain("premium-recovery-gave-up");
  }

  @Test
  void capCountsSubscriptionsNotExaminations_oldestFirst() {
    // Visibility returns NEWEST-first: wf-25 (newest) … wf-01 (oldest). Interleave 5 unarmed
    // workflows at the END of the listing (= oldest, examined FIRST) — they must consume zero cap.
    List<String> listed = new ArrayList<>();
    for (int i = 25; i >= 1; i--) {
      listed.add(armedWf(i));
    }
    for (int i = 1; i <= 5; i++) {
      String unarmed = "t-prod_real/s-copytrade-v1/pos/UN" + i + "/u:0";
      listed.add(unarmed);
      wireArmed(unarmed, occFor(90 + i), false, false);
    }
    for (int i = 1; i <= 25; i++) {
      wireArmed(armedWf(i), occFor(i), true, false);
    }
    wireList(listed.toArray(String[]::new));

    PremiumSubscriptionRecovery.Sweep sweep = recovery().sweepOnce();

    assertThat(sweep.recovered()).isEqualTo(20);
    assertThat(sweep.skippedUnarmed()).isEqualTo(5); // examinations are free
    assertThat(sweep.truncated()).isEqualTo(1);
    assertThat(sweep.complete()).isFalse(); // partial -> the loop retries
    // Oldest-first: the FIVE dropped are the NEWEST five (wf-21..wf-25).
    Set<String> subscribed = provider.listeners.keySet();
    for (int i = 1; i <= 20; i++) {
      assertThat(subscribed).contains(occFor(i));
    }
    for (int i = 21; i <= 25; i++) {
      assertThat(subscribed).doesNotContain(occFor(i));
    }
    assertThat(allLogs()).contains("truncated").contains("remaining=5");
    verify(alerts, atLeastOnce()).post(anyString());
  }

  @Test
  void deadlineBoundsTheSweep() {
    // Each exitProximity query costs 61s on the stepping clock; the 120s deadline is checked
    // between workflows, so the third workflow is never reached.
    String wf1 = armedWf(1);
    String wf2 = armedWf(2);
    String wf3 = armedWf(3);
    wireList(wf3, wf2, wf1); // newest-first; processed oldest-first: wf1, wf2, wf3
    for (int i = 1; i <= 3; i++) {
      WorkflowStub stub = stubFor(armedWf(i));
      String occ = occFor(i);
      when(stub.query(eq("exitProximity"), eq(ExitProximityViewMirror.class), any(Object[].class)))
          .thenAnswer(
              inv -> {
                clock.advance(Duration.ofSeconds(61));
                return new ExitProximityViewMirror(occ, true, false);
              });
      wireState(armedWf(i), occ, 5L);
    }

    PremiumSubscriptionRecovery.Sweep sweep = recovery().sweepOnce();

    assertThat(sweep.recovered()).isEqualTo(2);
    assertThat(sweep.truncated()).isEqualTo(1);
    assertThat(sweep.complete()).isFalse(); // the attempt is partial
    assertThat(provider.listeners.keySet()).containsExactlyInAnyOrder(occFor(1), occFor(2));
  }

  // ---------------------------------------------------------------------------
  // T9 — request carries tenant/strategy from the workflow id; marker always logs
  // ---------------------------------------------------------------------------

  @Test
  void requestCarriesTenantAndStrategy_andUnparseableIdTalliesFailed() {
    SubscribePremiumActivityImpl mockActivity = mock(SubscribePremiumActivityImpl.class);
    SubscribePremiumResult ok = new SubscribePremiumResult();
    ok.setSchemaVersion(1L);
    ok.setSubscriptionId("sub-1");
    ok.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    when(mockActivity.subscribePremium(any())).thenReturn(ok);

    String wfCanonical = "t-prod_real/s-copytrade-v1/pos/DRAM/x:0";
    String wfUnparseable = "no-canonical-prefix";
    wireList(wfUnparseable, wfCanonical);
    wireArmed(wfCanonical, OCC_TRAIL, true, false);
    wireArmed(wfUnparseable, OCC_WATCH, true, false);

    PremiumSubscriptionRecovery.Sweep sweep =
        recovery(mockActivity, 5, Duration.ofSeconds(30), 20, Duration.ofSeconds(120)).sweepOnce();

    // The unparseable id tallies failed WITHOUT aborting the sweep.
    assertThat(sweep.failed()).isEqualTo(1);
    assertThat(sweep.recovered()).isEqualTo(1);

    ArgumentCaptor<SubscribePremiumRequest> captor =
        ArgumentCaptor.forClass(SubscribePremiumRequest.class);
    verify(mockActivity, times(1)).subscribePremium(captor.capture());
    SubscribePremiumRequest req = captor.getValue();
    assertThat(req.getTenantId()).isEqualTo("prod_real");
    assertThat(req.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(req.getContractSymbol()).isEqualTo(OCC_TRAIL);
    assertThat(req.getPositionWorkflowId()).isEqualTo(wfCanonical);
    assertThat(req.getSchemaVersion()).isEqualTo(1L);
  }

  @Test
  void markerAndMetricAppearEvenOutsideRth() throws Exception {
    clock.set(ZonedDateTime.of(2026, 8, 22, 12, 0, 0, 0, ET).toInstant()); // Saturday: outside RTH
    CountDownLatch park = new CountDownLatch(1);
    PremiumSubscriptionRecovery recovery =
        new PremiumSubscriptionRecovery(
            client,
            activity,
            alerts,
            registry,
            5,
            Duration.ofSeconds(30),
            20,
            Duration.ofSeconds(120),
            clock,
            d -> park.await()); // the loop thread parks in its calendar wait

    recovery.onApplicationReady();

    // Marker logged synchronously from the listener itself, BEFORE any RTH wait.
    assertThat(allLogs()).contains("AUDIT premium-recovery-started");
    // The gauge exists (registered at construction) and reports pending.
    assertThat(registry.get("omo_trail_recovery_last_result").gauge().value()).isEqualTo(0.0d);
    verify(client, times(0)).listExecutions(anyString()); // still gated
    // The daemon loop thread stays parked in its calendar wait; deliberately NOT released — a
    // parked daemon thread is exactly the production shape of an off-hours boot.
    assertThat(park.getCount()).isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static String armedWf(int i) {
    return String.format("t-prod_real/s-copytrade-v1/pos/R%02d/w:0", i);
  }

  /** Distinct unexpired OCC per index: root R{i} padded to 6, expiry 2027-01-15. */
  private static String occFor(int i) {
    return String.format("R%02d   270115C00100000", i);
  }

  private static SubscribePremiumRequest request(String occ, String wfId) {
    SubscribePremiumRequest r = new SubscribePremiumRequest();
    r.setSchemaVersion(1L);
    r.setTenantId("prod_real");
    r.setStrategyId("copytrade-v1");
    r.setContractSymbol(occ);
    r.setPositionWorkflowId(wfId);
    return r;
  }

  /** Settable/advanceable clock pinned to ET. */
  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    MutableClock(Instant start, ZoneId zone) {
      this.now = new AtomicReference<>(start);
      this.zone = zone;
    }

    void set(Instant instant) {
      now.set(instant);
    }

    void advance(Duration d) {
      now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zoneId) {
      MutableClock c = new MutableClock(now.get(), zoneId);
      return c;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  /** Records requested sleeps; optionally advances the mutable clock by the requested amount. */
  final class RecordingSleeper implements PremiumSubscriptionRecovery.Sleeper {
    final List<Duration> sleeps = new ArrayList<>();
    MutableClock advanceClockOnSleep;

    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
      events.add("sleep:" + duration);
      if (advanceClockOnSleep != null) {
        advanceClockOnSleep.advance(duration);
      }
    }
  }

  /** Counts provider-side subscriptions; can be told to throw for specific OCCs. */
  static final class CountingProvider implements MarketDataProvider {
    final AtomicInteger subscribeCalls = new AtomicInteger();
    final Map<String, List<Consumer<Tick>>> listeners = new LinkedHashMap<>();
    final Set<String> failingOccs = new java.util.HashSet<>();
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
      if (failingOccs.contains(occSymbol)) {
        throw new RuntimeException("provider down for " + occSymbol);
      }
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
          listeners.getOrDefault(occSymbol, List.of()).remove(onTick);
        }
      };
    }
  }

  /** Records submissions without running them, so a dispatch never needs a live WorkflowClient. */
  static final class CountingExecutor extends AbstractExecutorService {
    @Override
    public void execute(Runnable command) {}

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
}
