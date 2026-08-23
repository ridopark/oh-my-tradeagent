package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.activities.TenantStrategyBrokerTarget;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkflowImplementationOptions;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AccountKillSwitchWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String MARKET_DATA_QUEUE = "market-data";
  // The account-snapshot dispatch routes to broker-<target>; tests pin alpaca-paper.
  private static final String BROKER_TARGET = "alpaca-paper";
  private static final String BROKER_QUEUE = "broker-" + BROKER_TARGET;

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private TenantConfigActivities tenantConfig;
  private AccountPnlActivities accountPnl;
  private DailyPnlExecActivity execPnl;
  private AccountKillSwitchCascadeActivities cascade;
  private GetOptionQuoteActivity optionQuote;
  private AccountSnapshotActivity accountSnapshot;
  private int originalStillHoldingRepageTicks;
  private int originalMtmTripTicks;
  private int originalMtmIntickRefetches;
  private long originalHistoryLengthWatermark;

  @BeforeEach
  void setUp() {
    // Phase 2b: capture the production re-page cadence so tests that shrink it can restore it.
    originalStillHoldingRepageTicks = AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS;
    // PLAN-2026-07-22: capture the mtm-debounce tunables so tests that isolate the cross-tick
    // debounce (in-tick re-fetch disabled) can restore them.
    originalMtmTripTicks = AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS;
    originalMtmIntickRefetches = AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES;
    // PLAN-2026-07-22 (debounce CAN-carry): capture the continue-as-new watermark so the CAN-carry
    // test can shrink it to force a continueAsNew between two debounce ticks, then restore it.
    originalHistoryLengthWatermark = AccountKillSwitchWorkflowImpl.historyLengthWatermark;
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(AccountKillSwitchWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    tenantConfig = Mockito.mock(TenantConfigActivities.class);
    accountPnl = Mockito.mock(AccountPnlActivities.class);
    execPnl = Mockito.mock(DailyPnlExecActivity.class);
    cascade = Mockito.mock(AccountKillSwitchCascadeActivities.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);
    accountSnapshot = Mockito.mock(AccountSnapshotActivity.class);

    // Defaults: market open, today fixed, ABSOLUTE threshold set (legacy path so the existing
    // suite is unchanged), no pct, no realized loss, empty book, no quotes.
    when(calendar.isMarketOpen()).thenReturn(true);
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 14));
    // OCC fixture convention (PLAN-2026-07-23 Phase 1): expiry is now behavioral, because a
    // PHYSICALLY EXPIRED contract is valued at zero instead of counting as a quote failure. So
    // ...261218... = LIVE (expires 2026-12-18, after the todayEt above) — use it whenever a test
    // means "a real open position", including "...whose quote is unavailable". ...240119... =
    // EXPIRED (2024-01-19) — only the Phase 1 tests that deliberately hold a dead contract.
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);
    when(tenantConfig.tenantBrokerTarget(anyString())).thenReturn(BROKER_TARGET);
    // Phase 2: v>=1 sums a per-strategy realized read routed to each strategy's broker queue. By
    // default the tenant has ONE strategy on alpaca-paper; the exec realized read returns zero.
    when(accountPnl.tenantStrategyBrokerTargets(anyString()))
        .thenReturn(List.of(new TenantStrategyBrokerTarget("s1", BROKER_TARGET)));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString())).thenReturn(new AccountOpenBook(List.of(), 0, 0));
    when(cascade.cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));

    coreWorker.registerActivitiesImplementations(
        audit, calendar, tenantConfig, accountPnl, cascade);

    // GetOptionQuoteActivity is routed to the market-data task queue from the workflow stub.
    Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
    mdWorker.registerActivitiesImplementations(optionQuote);

    // AccountSnapshotActivity (SOD equity) + the v>=1 realized read (DailyPnlExecActivity) both
    // route to broker-<target> from the workflow stub. Register the exec realized read on BOTH the
    // paper and live queues here (workers must be created BEFORE env.start()) so the mixed
    // broker_target tests can route s2 to broker-alpaca-live without a post-start newWorker.
    Worker brokerWorker = env.newWorker(BROKER_QUEUE);
    brokerWorker.registerActivitiesImplementations(accountSnapshot, execPnl);
    Worker brokerLiveWorker = env.newWorker("broker-alpaca-live");
    brokerLiveWorker.registerActivitiesImplementations(execPnl);

    env.start();
  }

  @AfterEach
  void tearDown() {
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = originalStillHoldingRepageTicks;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = originalMtmTripTicks;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = originalMtmIntickRefetches;
    AccountKillSwitchWorkflowImpl.historyLengthWatermark = originalHistoryLengthWatermark;
    env.close();
  }

  // ---------- THE DRILL: two strategies, realized + MTM crossing the cap ----------

  // A tenant with TWO strategies whose realized + open-MTM loss crosses
  // account_daily_loss_threshold trips the account kill switch EXACTLY once, HALTS + pages, and —
  // per the Phase 2 (PLAN-2026-07-15) no-auto-flatten policy — does NOT cascade a MARKET flatten
  // (fresh execution == v>=1). The trip subject carries flatten=manual so the operator is paged to
  // flatten by hand.
  @Test
  void heartbeat_twoStrategies_realizedPlusMtmCrossesThreshold_tripsOnceNoAutoFlatten() {
    // Phase 2 (C4): a tenant with TWO strategies on DIFFERENT broker_targets. The account path
    // routes each per-strategy realized read to its OWN broker queue and sums them:
    //   s1 (alpaca-paper) realized -1200 + s2 (alpaca-live) realized -1800 = -3000.
    // The exec activity is registered on both broker queues in setUp; route s2 to the live one.
    when(accountPnl.tenantStrategyBrokerTargets(anyString()))
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget("s1", "alpaca-paper"),
                new TenantStrategyBrokerTarget("s2", "alpaca-live")));
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s1"), any()))
        .thenReturn(new BigDecimal("-1200"));
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s2"), any()))
        .thenReturn(new BigDecimal("-1800"));
    // Open MTM: two losing positions valued (liveBid - entryPremium) * qty * 100.
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    // s1 position: entry 3.00, bid 2.00 -> -1.00 * 10 * 100 = -1000
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 10L),
                    // s2 position: entry 5.00, bid 4.00 -> -1.00 * 15 * 100 = -1500
                    new OpenPositionValuation(
                        "AAPL  261218C00200000", new BigDecimal("5.00"), 15L)),
                2,
                0));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.00")));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(okQuote("AAPL  261218C00200000", new BigDecimal("4.00")));

    // Total = -3000 + (-1000) + (-1500) = -5500 <= -5000 -> trip.
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-drill");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
    assertThat(s.getActor()).isEqualTo("auto:account_daily_loss");

    // Phase 2 (PLAN-2026-07-15): NO auto-flatten — the account-scoped cascade is never dispatched.
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());

    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject())
        .containsEntry("scope", "account")
        .containsEntry("flatten", "manual")
        // C3: the page carries the open-position count + current MTM (two priced positions here).
        .containsEntry("open_positions", 2)
        .containsKey("open_mtm");
  }

  // PLAN-2026-07-22 safety-lock: a prior-day position closed today at a LOSS pre-fix read as a
  // phantom GAIN (raw exit proceeds credited with zero cost basis), MASKING the breach so the
  // account daily-loss cap FAILED OPEN. The exec-journal FIFO fix now returns the REAL cross-day
  // loss, which crosses the 5000 absolute cap and trips auto:account_daily_loss. The harness stubs
  // the corrected per-strategy figure; the FIFO itself is pinned in DailyPnlExecActivityImplTest.
  @Test
  void heartbeat_crossDayLoss_nowTrips_auto_account_daily_loss() {
    // Corrected cross-day realized loss (pre-fix the phantom read +2068 and masked this). Empty
    // book (default) so the realized read alone crosses the cap — no MTM/quotes involved.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-crossday");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
    assertThat(s.getActor()).isEqualTo("auto:account_daily_loss");
  }

  // Below threshold: total loss does not cross the cap -> no trip.
  @Test
  void heartbeat_belowThreshold_doesNotTrip() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-1000"));
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.50")));
    // -1000 + (2.50-3.00)*5*100 = -1000 - 250 = -1250 > -5000 -> no trip.

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-below");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // Honest MTM (#591 review): a positionState VALUE failure (position dropped from the book, so
  // excluded from the MTM sum) must NOT be cached as a complete open_mtm — the reset banner would
  // otherwise show a partial figure as the total. openPositions still reflects the whole book
  // count.
  @Test
  void heartbeat_valueFailure_cachesWholeCount_butOmitsPartialMtm() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-1000"));
    // listed=3 (whole book), valueFailures=1 (one positionState read failed -> excluded from the
    // valued positions), one priced position that quotes cleanly (quoteFailures=0).
    // failsClosed(3,1)
    // is false (1*2 !> 3, and 3 > SMALL_BOOK_MAX_POSITIONS) so the value failure does not
    // fail-close.
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L)),
                3,
                1));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.50")));
    // -1000 + (2.50-3.00)*5*100 = -1250 > -5000 -> no trip.

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-valuefail");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    // Whole-book count is surfaced honestly ("you still hold 3")...
    assertThat(s.getOpenPositions()).isEqualTo(3L);
    // ...but the partial MTM (missing the value-failed position) is NOT cached as the total.
    assertThat(s.getOpenMtm()).isNull();
  }

  // Unset threshold => cap inert: even a massive loss does not trip (and PnL is never computed).
  @Test
  void heartbeat_unsetThreshold_capInert_noTripOnLargeLoss() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-inert");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    // Inert path short-circuits before any PnL/book read.
    verify(execPnl, never()).computeRealizedPnl(anyString(), anyString(), any());
    verify(accountPnl, never()).accountOpenBook(anyString());
  }

  // ---------- PLAN-2026-07-22: small-book MTM-unavailable quote debounce ----------

  // The 2026-07-21 fix. A small (2-position) book with BOTH quotes UNAVAILABLE is over-sensitive (a
  // single miss trivially satisfies the fail-close bound). It must NOT trip on the FIRST
  // unpriceable
  // tick (debounce); only a SECOND CONSECUTIVE unpriceable tick fail-closes — still fail-CLOSED,
  // but
  // past a single/transient blip. Isolate the cross-tick debounce by disabling the in-tick
  // re-fetch. (Replaces the old heartbeat_quoteUnavailable_failsClosed_doesNotFailOpen, which
  // asserted a trip on the first miss — that is exactly the over-sensitivity being fixed.)
  @Test
  void heartbeat_smallBookUnpriceable_debouncesThenFailsClosedOnSecondTick() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-debounce");
    WorkflowStub.fromTyped(stub).start(input());

    // First unpriceable tick: DEFERRED, not tripped (a single/transient miss must not fail-close).
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchTripped")).isEqualTo(0L);

    // Second consecutive unpriceable tick: fail-closes with the distinct reason.
    env.sleep(Duration.ofSeconds(60));
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
    // Fail-closed AUTO trip halts + pages but does NOT auto-flatten (Phase 2 no-auto-flatten).
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject())
        .containsEntry("flatten", "manual")
        .containsEntry("open_positions", 2)
        .doesNotContainKey("open_mtm");
  }

  // ---------- PLAN-2026-07-22: deferred-fail-close YELLOW page ----------

  // A single unpriceable tick (a blip) DEFERS and now emits EXACTLY ONE
  // AccountKillSwitchMtmDeferred
  // audit (the YELLOW "cap deferred a fail-close on a quote blip — watching" page) — and still does
  // NOT trip. Makes the previously silent WARN-only defer operator-visible.
  @Test
  void heartbeat_smallBookBlip_emitsOneMtmDeferredNoTrip() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-defer-blip");
    WorkflowStub.fromTyped(stub).start(input());

    // One unpriceable tick: DEFERRED (not tripped) and paged exactly once.
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchTripped")).isEqualTo(0L);
    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(1L);
    AuditEvent deferred = captureKind("AccountKillSwitchMtmDeferred");
    assertThat(deferred.getSubject())
        .containsEntry("scope", "account")
        .containsEntry("listed", 2)
        .containsEntry("failures", 2)
        .containsEntry("consecutive_ticks", 1)
        .containsEntry("trip_ticks", 2)
        .containsKey("trading_day");
  }

  // Two consecutive unpriceable ticks: the deferred page fires ONCE (on tick 1), then the trip
  // fires
  // on tick 2 — and NO second deferred emit on tick 2 (the counter reaches trip_ticks so the defer
  // branch is not entered). One page per miss-episode, not per-tick spam.
  @Test
  void heartbeat_smallBookTwoConsecutive_deferredPageOnceThenTrip() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-defer-then-trip");
    WorkflowStub.fromTyped(stub).start(input());

    // Tick 1: deferred page, not tripped.
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(1L);

    // Tick 2: fail-closes — and NO second deferred emit (still exactly one).
    env.sleep(Duration.ofSeconds(60));
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(1L);
  }

  // A cleanly-priced book never enters the defer branch → no deferred page.
  @Test
  void heartbeat_cleanBook_noMtmDeferredEmit() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-defer-clean");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(4 * 60));

    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(0L);
  }

  // A genuine computed-loss trip (priced book, reason auto:account_daily_loss) never touches the
  // defer branch → no deferred page (unchanged loss path).
  @Test
  void heartbeat_genuineDailyLoss_noMtmDeferredEmit() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000")); // crosses the 5000 absolute cap
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-defer-realloss");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(0L);
  }

  // A single transient miss followed by a clean price must NOT trip — the debounce counter resets
  // on
  // any cleanly-priced tick, so non-consecutive misses cannot accumulate. (In-tick re-fetch
  // disabled
  // so exactly one quote call per tick makes the miss/price sequence deterministic.)
  @Test
  void heartbeat_smallBookSingleUnpriceableTickThenPriced_doesNotTrip() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
                1,
                0));
    // Tick 1 quote UNAVAILABLE (a blip); every later tick priced (benign -10 MTM, no loss trip).
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(unavailableQuote("NVDA  261218C00140000"))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-debounce-blip");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(4 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchTripped")).isEqualTo(0L);
  }

  // "Consecutive" semantics: miss / clean / miss / clean ... must NEVER trip — every cleanly-priced
  // tick resets the counter to 0, so non-consecutive misses can never reach N. (In-tick re-fetch
  // off.)
  @Test
  void heartbeat_smallBookInterleavedMissThenClean_neverAccumulatesToTrip() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
                1,
                0));
    // Alternate unavailable / ok on every tick (one call/tick with in-tick re-fetch disabled).
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            unavailableQuote("NVDA  261218C00140000"),
            okQuote("NVDA  261218C00140000", new BigDecimal("2.90")),
            unavailableQuote("NVDA  261218C00140000"),
            okQuote("NVDA  261218C00140000", new BigDecimal("2.90")),
            unavailableQuote("NVDA  261218C00140000"),
            okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-interleaved");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(6 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchTripped")).isEqualTo(0L);
  }

  // PRIMARY defense: a momentary quote blip that clears within the SAME heartbeat (via the bounded
  // in-tick re-fetch) never even enters the cross-tick debounce — the book prices on the re-fetch
  // and the tick evaluates normally (no trip, no defer). Removes the 2026-07-21 failure mode with
  // no
  // widened blind window.
  @Test
  void heartbeat_smallBookBlipClearsViaInTickRefetch_doesNotDeferOrTrip() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
                1,
                0));
    // First quote (initial valuation) UNAVAILABLE; the in-tick re-fetch call returns OK — the blip
    // cleared mid-tick.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(unavailableQuote("NVDA  261218C00140000"))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-intick-blip");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick (the in-tick re-fetch happens within it)

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchTripped")).isEqualTo(0L);
  }

  // The relative >50% large-book threshold is UNCHANGED: a large book (3 positions) with 2 quotes
  // UNAVAILABLE (>50% failures) fail-closes on the VERY FIRST tick — the debounce touches only the
  // small-book floor, never the relative large-book bound.
  @Test
  void heartbeat_largeBookRelativeFailure_failsClosedImmediately_unchanged() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L),
                    new OpenPositionValuation("TSLA  261218C00300000", new BigDecimal("4.00"), 5L)),
                3,
                0));
    // Two of three unpriceable (66% > 50%), one priced.
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(unavailableQuote("NVDA  261218C00140000"));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(unavailableQuote("AAPL  261218C00200000"));
    when(optionQuote.getOptionQuote(quoteFor("TSLA  261218C00300000")))
        .thenReturn(okQuote("TSLA  261218C00300000", new BigDecimal("3.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-largebook");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
  }

  // Real-loss separation (a): a genuine computed-loss breach on a FULLY PRICED book trips on the
  // FIRST qualifying tick with reason auto:account_daily_loss — the debounce lives inside the
  // fail-close branch, which a priced book never enters, so the counter is never consulted on the
  // loss path.
  @Test
  void heartbeat_genuineDailyLoss_tripsFirstTick_debounceNotConsulted() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000")); // crosses the 5000 absolute cap
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-realloss-first");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // Real-loss separation (b): a book that is partially unpriceable but BELOW the fail-close bound
  // (a
  // large book, 1-of-3 miss) AND whose priced portion crosses the loss trips IMMEDIATELY with
  // reason
  // auto:account_daily_loss — the debounce never gates a real computed loss.
  @Test
  void heartbeat_lossBreachWithPartialMiss_dailyLossWinsImmediately() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation(
                        "NVDA  261218C00140000", new BigDecimal("12.00"), 10L),
                    new OpenPositionValuation(
                        "AAPL  261218C00200000", new BigDecimal("12.00"), 10L),
                    new OpenPositionValuation("TSLA  261218C00300000", new BigDecimal("4.00"), 1L)),
                3,
                0));
    // 1 of 3 unpriceable (33% < 50% => NOT fail-closed on a 3-book); the two priced positions carry
    // a large loss that crosses the cap: (2-12)*10*100 = -10000 each = -20000.
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.00")));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(okQuote("AAPL  261218C00200000", new BigDecimal("2.00")));
    when(optionQuote.getOptionQuote(quoteFor("TSLA  261218C00300000")))
        .thenReturn(unavailableQuote("TSLA  261218C00300000")); // the partial miss

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-loss-partialmiss");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // Reset/untrip CLEARS the debounce counter: after a debounced fail-close and a reset, a fresh
  // unpriceable outage must take N ticks AGAIN (not immediately re-fail-close on a stale counter).
  @Test
  void heartbeat_smallBookDebounce_clearedOnUntrip() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-debounce-reset");
    WorkflowStub.fromTyped(stub).start(input());

    // Two consecutive unpriceable ticks -> fail-close.
    env.sleep(Duration.ofSeconds(135));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // Reset (clears the debounce counter + arms a 60s cooldown).
    stub.reset(resetRequest("alice"));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // One unpriceable tick PAST the cooldown must NOT immediately re-fail-close (counter was
    // cleared
    // -> back to 1, deferred). Had reset left the counter at 2, this tick would re-trip at once.
    env.sleep(Duration.ofSeconds(135));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // A second consecutive unpriceable tick re-fail-closes (a fresh N-tick debounce completed).
    env.sleep(Duration.ofSeconds(60));
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
  }

  // A new trading day RESETS the debounce counter: an unpriceable tick on day 1 followed by the day
  // rollover must NOT let a single day-2 unpriceable tick fail-close — day 2 starts a fresh N-tick
  // count.
  @Test
  void heartbeat_smallBookDebounce_resetOnNewTradingDay() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-debounce-newday");
    WorkflowStub.fromTyped(stub).start(input());

    // Day 1, first unpriceable tick -> deferred (counter=1).
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // Roll the trading day forward BEFORE the next tick -> the rollover resets the counter to 0.
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));

    // Day 2, first unpriceable tick: counter reset then incremented to 1 -> still deferred, NOT
    // tripped. (Without the day-rollover reset it would be the 2nd consecutive tick and trip.)
    env.sleep(Duration.ofSeconds(60));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // Day 2, second consecutive unpriceable tick -> fail-closes.
    env.sleep(Duration.ofSeconds(60));
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
  }

  // ---------- PLAN-2026-07-22: debounce counter carried across continue-as-new (v4) ----------

  // THE WHOLE POINT. A CAN landing mid-debounce must NOT reset the count. One unpriceable tick
  // (counter -> 1, deferred, NO trip) → continueAsNew (carries the count as v4) → one MORE
  // unpriceable tick post-CAN trips auto:account_mtm_unavailable because the count reached 2 (it
  // SURVIVED the CAN). Pre-fix the CAN would reset the count and this second tick would only defer
  // (a 3rd tick would be needed) — so a trip on the 2nd (post-CAN) unpriceable tick proves the
  // carry. Exactly ONE deferred page across the episode (the emit fires on the first defer only).
  @Test
  void heartbeat_smallBookDebounce_carriedAcrossCanTripsOnSecondPostCanTick() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    // Force a continueAsNew after the first (dense) heartbeat tick but before the second: a single
    // debounce tick's history far exceeds 30 events, while a tripped early-return tick does not.
    AccountKillSwitchWorkflowImpl.historyLengthWatermark = 30L;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    String workflowId = "t-dev/account/killswitch-debounce-can";
    AccountKillSwitchWorkflow stub = newStub(workflowId);
    WorkflowStub typed = WorkflowStub.fromTyped(stub);
    typed.start(input());
    String runIdBeforeCan = typed.getExecution().getRunId();

    // Tick 1: unpriceable -> DEFER (counter=1), one deferred page, NOT tripped; then the
    // post-heartbeat watermark check fires continueAsNew, carrying counter=1 as a v4 input.
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(1L);

    // Confirm the workflow actually crossed the continueAsNew boundary (run id rotated) — otherwise
    // this would not be testing the carry at all.
    String runIdAfterCan =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(workflowId)
            .describe()
            .getExecution()
            .getRunId();
    assertThat(runIdAfterCan).isNotEqualTo(runIdBeforeCan);

    // Tick 2 (post-CAN): the restored counter (1) increments to 2 == trip_ticks -> fail-close on
    // the SECOND unpriceable tick. Had the CAN reset the count, this tick would only defer.
    env.sleep(Duration.ofSeconds(75));
    AccountKillSwitchWorkflow stubAfter =
        env.getWorkflowClient().newWorkflowStub(AccountKillSwitchWorkflow.class, workflowId);
    KillSwitchState s = stubAfter.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
    // Still exactly one deferred page across the whole episode (tick 2 tripped, it did not defer).
    assertThat(countKind("AccountKillSwitchMtmDeferred")).isEqualTo(1L);
  }

  // Init restore (independent of the live CAN boundary): a workflow STARTED FROM a v4 carry input
  // that already carries consecutive_mtm_unavailable_ticks=1 restores the counter at @WorkflowInit,
  // so the very FIRST post-restore unpriceable tick trips (1 -> 2). Built by hand (NOT via
  // carryForwardInput) so a builder regression cannot mask itself through this restore fixture.
  @Test
  void debounceCounterRestoredFromV4CarryInput_tripsOnFirstUnpriceableTick() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    // v4 carry as continueAsNew would emit it mid-debounce: schema_version 4, counter=1, same day.
    AccountKillSwitchWorkflowInput carried = new AccountKillSwitchWorkflowInput();
    carried.setSchemaVersion(4L);
    carried.setTenantId("dev");
    carried.setTradingDay(LocalDate.of(2026, 5, 14));
    carried.setSodEquity(new BigDecimal("5000"));
    carried.setConsecutiveMtmUnavailableTicks(1L);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-v4-restore");
    WorkflowStub.fromTyped(stub).start(carried);

    // ONE tick: the restored counter (1) increments to 2 -> immediate fail-close (a fresh count
    // would only defer on the first tick).
    env.sleep(Duration.ofSeconds(75));
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
  }

  // A NEW trading day still RESETS the carried counter across a CAN: tick 1 defers (counter=1) and
  // continues-as-new carrying counter=1; the day then rolls forward, so the first day-2 unpriceable
  // tick resets to 0 then increments to 1 -> DEFER (NOT trip), and only a second day-2 consecutive
  // tick trips. A v4 carry must never leak a stale mid-outage count into a fresh day.
  @Test
  void heartbeat_smallBookDebounce_newDayResetsCarriedCounterAcrossCan() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    AccountKillSwitchWorkflowImpl.historyLengthWatermark = 30L;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    String workflowId = "t-dev/account/killswitch-debounce-can-newday";
    AccountKillSwitchWorkflow stub = newStub(workflowId);
    WorkflowStub.fromTyped(stub).start(input());

    // Day 1, tick 1: unpriceable -> DEFER (counter=1), then continueAsNew carries counter=1 (v4).
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // Roll the trading day forward BEFORE the next tick -> the post-CAN rollover must reset the
    // carried counter to 0.
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));

    // Day 2, first unpriceable tick: rollover resets to 0 then increments to 1 -> still DEFER, NOT
    // tripped. Without the reset the carried counter (1) would trip this first day-2 tick.
    env.sleep(Duration.ofSeconds(75));
    AccountKillSwitchWorkflow stubDay2 =
        env.getWorkflowClient().newWorkflowStub(AccountKillSwitchWorkflow.class, workflowId);
    assertThat(stubDay2.killswitchState().getTripped()).isFalse();

    // Day 2, second consecutive unpriceable tick -> fail-closes (fresh N-tick debounce completed).
    env.sleep(Duration.ofSeconds(75));
    AccountKillSwitchWorkflow stubDay2b =
        env.getWorkflowClient().newWorkflowStub(AccountKillSwitchWorkflow.class, workflowId);
    KillSwitchState s = stubDay2b.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
  }

  // The widened schema guard REJECTS a newer-than-build carry (schema_version 6) at @WorkflowInit —
  // an old build must never silently accept a payload it cannot interpret. Uses a dedicated env
  // whose worker FAILS the workflow (not just the task) on the guard's IllegalArgumentException so
  // the rejection surfaces via getResult; v6 acceptance is proven by the restore/carry tests above.
  @Test
  void schemaGuard_rejectsSchemaVersionAboveSeven() {
    TestWorkflowEnvironment guardEnv = TestWorkflowEnvironment.newInstance();
    try {
      Worker w = guardEnv.newWorker(CORE_QUEUE);
      w.registerWorkflowImplementationTypes(
          WorkflowImplementationOptions.newBuilder()
              .setFailWorkflowExceptionTypes(IllegalArgumentException.class)
              .build(),
          AccountKillSwitchWorkflowImpl.class);
      guardEnv.start();

      AccountKillSwitchWorkflowInput tooNew = new AccountKillSwitchWorkflowInput();
      // #670 widened the ceiling to 7 (tripped_trading_day carry); 8 is the too-new probe.
      tooNew.setSchemaVersion(8L);
      tooNew.setTenantId("dev");
      AccountKillSwitchWorkflow stub =
          guardEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  AccountKillSwitchWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId("t-dev/account/killswitch-v6")
                      .build());
      WorkflowStub typed = WorkflowStub.fromTyped(stub);
      typed.start(tooNew);

      assertThatThrownBy(() -> typed.getResult(String.class))
          .isInstanceOf(WorkflowFailedException.class)
          .hasStackTraceContaining("schema_version unsupported");
    } finally {
      guardEnv.close();
    }
  }

  // A v5 carry input (the widened schema) is ACCEPTED at @WorkflowInit and runs a full heartbeat
  // without a schema throw — the acceptance side of the widened guard.
  @Test
  void schemaGuard_acceptsSchemaVersionFive() {
    AccountKillSwitchWorkflowInput v5 = new AccountKillSwitchWorkflowInput();
    v5.setSchemaVersion(5L);
    v5.setTenantId("dev");
    v5.setLastOpenPositions(2L);
    v5.setLastOpenMtm(new BigDecimal("1500"));
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-v5-accept");
    WorkflowStub.fromTyped(stub).start(v5);
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isFalse();
  }

  // ---------- PLAN-2026-07-23 Phase 1: a PHYSICALLY EXPIRED contract is worth zero, not unknown
  // ----------

  // THE 2026-07-22 INCIDENT, reproduced. staging_paper's book was 3 running positions, 2 of them
  // holding contracts that had EXPIRED days earlier (delisted => quote unavailable FOREVER). The
  // old code counted both as quote failures: failsClosed(listed=3, failures=2) => 2*2 > 3 => an
  // auto:account_mtm_unavailable trip 47 SECONDS after the open, every single session, with no
  // debounce (the small-book grace is gated on listed <= 2). An expired contract's value is KNOWN
  // (zero), so it must not be a "failure" at all.
  @Test
  void heartbeat_expiredContractsUnpriceable_valuedAtZero_noMtmUnavailableTrip() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    // Expired 2025-05-16, ~1y before the mocked todayEt (2026-05-14).
                    new OpenPositionValuation("NVDA  240119C00140000", new BigDecimal("1.00"), 5L),
                    new OpenPositionValuation("TSLA  240119C00300000", new BigDecimal("1.00"), 5L),
                    // Live: expires 2026-12-18, well after todayEt.
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("3.00"), 5L)),
                3,
                0));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  240119C00140000")))
        .thenReturn(unavailableQuote("NVDA  240119C00140000"));
    when(optionQuote.getOptionQuote(quoteFor("TSLA  240119C00300000")))
        .thenReturn(unavailableQuote("TSLA  240119C00300000"));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(okQuote("AAPL  261218C00200000", new BigDecimal("3.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-expired-zero");
    WorkflowStub.fromTyped(stub).start(input());
    // Several ticks: the old behavior tripped on the FIRST one and stayed tripped.
    env.sleep(Duration.ofSeconds(200));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchTripped")).isEqualTo(0L);
    // Booked loss is 2 x (0 - 1.00) x 5 x 100 = -1000, inside the 5000 cap => no loss trip either.
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // Fork 2 of the plan: the expired lot BOOKS ITS REAL LOSS (0 - entryPremium) rather than being
  // skipped. A worthless expiry is a total loss of the premium paid, so the cap must SEE it — this
  // makes the cap stricter, never looser. Here the expired lot alone crosses the 5000 threshold:
  // (0 - 12.00) x 50 x 100 = -60000 => a DAILY-LOSS trip, not an mtm-unavailable one.
  @Test
  void heartbeat_expiredContract_booksTotalPremiumLoss_tripsDailyLoss() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation(
                        "NVDA  240119C00140000", new BigDecimal("12.00"), 50L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  240119C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-expired-books-loss");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    // The distinction that matters: the cap engaged on the REAL LOSS, not on an unpriceable book.
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // Regression guard: the fail-closed protection for a GENUINE market-data outage is untouched. The
  // same 2-of-3 unpriceable shape as the incident, but on contracts that have NOT expired, still
  // fail-closes immediately (listed=3 gets no small-book debounce).
  @Test
  void heartbeat_unexpiredContractsUnpriceable_stillFailsClosed() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("1.00"), 5L),
                    new OpenPositionValuation("TSLA  261218C00300000", new BigDecimal("1.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("3.00"), 5L)),
                3,
                0));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(unavailableQuote("NVDA  261218C00140000"));
    when(optionQuote.getOptionQuote(quoteFor("TSLA  261218C00300000")))
        .thenReturn(unavailableQuote("TSLA  261218C00300000"));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(okQuote("AAPL  261218C00200000", new BigDecimal("3.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-unexpired-failsclosed");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // ONE tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
  }

  // ---------- dual-control trip/reset (mirror per-strategy) ----------

  @Test
  void tripUpdate_setsStateAndAuditsAndCascades() {
    // Phase 2 (PLAN-2026-07-15): a MANUAL operator trip (non-auto: reason) STILL flattens — the
    // deliberate one-click flatten path — so the cascade is dispatched and NO flatten=manual key.
    // Avoid an auto-trip racing the manual trip: market closed.
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-trip");
    WorkflowStub.fromTyped(stub).start(input());

    stub.trip(tripRequest("manual:operator_initiated", "operator:ridopark"));

    KillSwitchState state = stub.killswitchState();
    assertThat(state.getTripped()).isTrue();
    assertThat(state.getReason()).isEqualTo("manual:operator_initiated");

    verify(cascade, timeout(2000).times(1))
        .cascadeAccountRiskBreach(
            eq("dev"),
            eq("t-dev/account/killswitch-trip"),
            eq("manual:operator_initiated"),
            eq("operator:ridopark"));
    // Manual trip flattens => NO no-auto-flatten marker on the subject.
    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject()).doesNotContainKey("flatten");
  }

  @Test
  void tripUpdate_whenAlreadyTripped_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-dup");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:first", "operator:a"));

    assertThatThrownBy(() -> stub.trip(tripRequest("manual:second", "operator:b")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("already_tripped");

    // Manual trip cascade fired exactly once (first trip); the second is rejected by the validator.
    verify(cascade, timeout(2000).times(1))
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void resetUpdate_whenNotTripped_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-resetfirst");
    WorkflowStub.fromTyped(stub).start(input());

    assertThatThrownBy(() -> stub.reset(resetRequest("alice")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("not_tripped");
  }

  @Test
  void resetUpdate_blankApprover_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-blank");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    assertThatThrownBy(() -> stub.reset(resetRequest("")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("approver_id_1_required");
  }

  @Test
  void resetUpdate_singleOperator_clearsTrippedAndSetsCooldown() {
    // Single-operator account reset: untrips, arms cooldown, emits KillSwitchResetApproved whose
    // subject carries approver_id_1 + via=manual_reset + cooling_down_until + cooldown_secs and NO
    // approver_id_2 (dual control retired).
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-reset");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    stub.reset(resetRequest("alice"));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getCoolingDownUntil()).isNotNull();

    AuditEvent reset = captureKind("KillSwitchResetApproved");
    assertThat(reset.getSubject())
        .containsEntry("approver_id_1", "alice")
        .containsEntry("via", "manual_reset")
        .containsKey("cooling_down_until")
        .containsKey("cooldown_secs")
        .doesNotContainKey("approver_id_2");
  }

  // ---------- post-reset cooldown: a still-down book must not immediately re-trip ----------

  // Auto-trip on a down book, RESET (sets coolingDownUntil = now + cooldownSecs), then heartbeat
  // again WHILE the book is still down but BEFORE the cooldown elapses: the cap must stay inert
  // (tripped=false). After the cooldown window passes the cap re-engages and trips again.
  @Test
  void heartbeat_afterReset_doesNotReTripDuringCooldown_thenReTripsAfter() {
    // A single down position large enough to cross the 5000 cap: (2.00-12.00)*10*100 = -10000.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation(
                        "NVDA  261218C00140000", new BigDecimal("12.00"), 10L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-cooldown");
    WorkflowStub.fromTyped(stub).start(input());

    // First heartbeat auto-trips on the down book.
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // Single-operator reset -> coolingDownUntil = now + DEFAULT_RESET_COOLDOWN_SECS (60s).
    stub.reset(resetRequest("alice"));
    KillSwitchState afterReset = stub.killswitchState();
    assertThat(afterReset.getTripped()).isFalse();
    assertThat(afterReset.getCoolingDownUntil()).isNotNull();

    // Advance ~30s (one more heartbeat) but stay INSIDE the 60s cooldown: the still-down book must
    // NOT re-trip. Without the cooldown guard this heartbeat would immediately re-trip.
    env.sleep(Duration.ofSeconds(30));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // Past the cooldown window: the cap re-engages and trips again on the still-down book.
    env.sleep(Duration.ofSeconds(90));
    KillSwitchState afterCooldown = stub.killswitchState();
    assertThat(afterCooldown.getTripped()).isTrue();
    assertThat(afterCooldown.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // ---------- PLAN-2026-07-22 (#591, risk C6): cached open exposure on the state query + reset
  // ----

  // A priceable, below-threshold book: the state query surfaces the listed count AND the SIGNED
  // unrealized MTM cached from the last heartbeat's open-book valuation. A GAIN (positive MTM)
  // proves
  // the sign is preserved (a gain must never read as a loss).
  @Test
  void killswitchState_priceableBook_returnsCountAndSignedMtm() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    // entry 3.00, bid 4.00 -> (4-3)*10*100 = +1000 (a gain)
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 10L),
                    // entry 5.00, bid 6.00 -> (6-5)*5*100 = +500 (a gain)
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("4.00")));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(okQuote("AAPL  261218C00200000", new BigDecimal("6.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-exposure-priceable");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse(); // +1500 gain, nowhere near the -5000 cap
    assertThat(s.getOpenPositions()).isEqualTo(2L);
    assertThat(s.getOpenMtm()).isEqualByComparingTo("1500"); // SIGNED positive — a gain, not a loss
  }

  // An unpriceable book (every quote UNAVAILABLE): the count is still cached, but the MTM is left
  // null — a partial/absent valuation must never be surfaced as the total. (Debounce/in-tick
  // refetch
  // pinned so a single unpriceable tick defers rather than trips, keeping the assert on the cache.)
  @Test
  void killswitchState_unpriceableBook_returnsCountNullMtm() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-exposure-unpriceable");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // one unpriceable tick: deferred, not tripped

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getOpenPositions()).isEqualTo(2L); // count still cached
    assertThat(s.getOpenMtm()).isNull(); // no partial MTM shown as the total
  }

  // FIX 1 (freshness on a blip): a small book that fails the FIRST in-tick valuation (quoteFailures
  // > 0) then prices cleanly on the in-tick re-fetch must cache the REFETCHED signed MTM — the top-
  // of-tick cache ran on the blipped (null-MTM) valuation, so without the post-refetch re-cache the
  // banner would show a stale/null MTM even though the book priced this tick. Pre-fix: getOpenMtm()
  // null; post-fix: the +1000 refetched gain.
  @Test
  void killswitchState_smallBookBlipClearsViaRefetch_cachesRefetchedSignedMtm() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 2;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                // entry 3.00, refetched bid 4.00 -> (4-3)*10*100 = +1000 (a gain)
                List.of(
                    new OpenPositionValuation(
                        "NVDA  261218C00140000", new BigDecimal("3.00"), 10L)),
                1,
                0));
    // First quote UNAVAILABLE (the blip), then it clears to a good bid on the in-tick re-fetch.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(unavailableQuote("NVDA  261218C00140000"))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("4.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-blip-recache");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // one tick: blip clears via re-fetch, no defer, no trip

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getOpenPositions()).isEqualTo(1L);
    assertThat(s.getOpenMtm()).isEqualByComparingTo("1000"); // REFETCHED signed MTM, not null/stale
  }

  // FIX 1 (counterpart): a small book that stays UNPRICEABLE even after the in-tick re-fetch leaves
  // the cached MTM null (never a partial as the total) while still caching the listed count — the
  // re-cache after re-fetch must not fabricate an MTM when quoteFailures stays > 0.
  @Test
  void killswitchState_staysUnpriceableAfterRefetch_cachesCountNullMtm() {
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 2;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 2; // one tick defers, not trips
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 5L)),
                1,
                0));
    // Never clears — every valuation (initial + both re-fetch attempts) sees UNAVAILABLE.
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-blip-nopclear");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75)); // one unpriceable tick after re-fetch: deferred, not tripped

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getOpenPositions()).isEqualTo(1L); // count cached
    assertThat(s.getOpenMtm()).isNull(); // still unpriceable after re-fetch -> no MTM
  }

  // The reset audit subject records the open exposure the operator resumed on (mirrors the trip /
  // still-holding subjects) so a blind "reset to trade again" over an underwater book is auditable.
  @Test
  void reset_auditSubjectCarriesCachedExposure() {
    // A single deeply-down position: (2.00-12.00)*10*100 = -10000 crosses the 5000 cap -> trip, and
    // the priced book caches open_positions=1 + a SIGNED-negative open_mtm=-10000.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation(
                        "NVDA  261218C00140000", new BigDecimal("12.00"), 10L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-exposure-reset");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    stub.reset(resetRequest("alice"));

    AuditEvent reset = captureKind("KillSwitchResetApproved");
    assertThat(reset.getSubject()).containsEntry("open_positions", 1).containsKey("open_mtm");
    // The audit subject round-trips through the activity boundary, so the BigDecimal MTM arrives as
    // a Double; assert its (SIGNED-negative) numeric value.
    assertThat(((Number) reset.getSubject().get("open_mtm")).doubleValue()).isEqualTo(-10000.0);
  }

  // A fresh account workflow queried BEFORE its first valued heartbeat: both fields null (nothing
  // cached yet). This is also the per-strategy KillSwitchWorkflow's steady state — it never caches
  // these fields, so the shared DTO's fail-closed check_entry consumer keeps reading them as null.
  @Test
  void killswitchState_beforeFirstHeartbeat_bothNull() {
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-exposure-fresh");
    WorkflowStub.fromTyped(stub).start(input());

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getOpenPositions()).isNull();
    assertThat(s.getOpenMtm()).isNull();
  }

  // ---------- pct-of-SOD-equity cap (the change) ----------

  // pct=0.40, SOD equity=5000 => effective threshold 2000. A total loss of -2000 trips; -1999
  // does not. (Drive the loss purely via realized so the book/quote path stays trivial.)
  @Test
  void pctConfigured_tripsAtFortyPctOfSodEquity() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));

    // -1999 > -2000 -> no trip.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-1999"));
    AccountKillSwitchWorkflow below = newStub("t-dev/account/killswitch-pct-below");
    WorkflowStub.fromTyped(below).start(input());
    env.sleep(Duration.ofSeconds(75));
    assertThat(below.killswitchState().getTripped()).isFalse();

    // -2000 <= -2000 -> trip.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-2000"));
    AccountKillSwitchWorkflow at = newStub("t-dev/account/killswitch-pct-at");
    WorkflowStub.fromTyped(at).start(input());
    env.sleep(Duration.ofSeconds(75));
    KillSwitchState s = at.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // When BOTH pct and absolute are set, the pct x SOD-equity threshold wins. pct=0.40 x 5000 =
  // 2000 (NOT the absolute 5000), so a -2500 loss trips even though it is under the absolute cap.
  @Test
  void pctTakesPrecedenceOverAbsolute() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-2500"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-pct-precedence");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isTrue();
  }

  // Only the absolute threshold is set (pct unset) => legacy behavior, no SOD-equity read at all.
  @Test
  void pctUnset_absoluteStillWorks() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-abs-only");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isTrue();
    // Pct unset => the SOD-equity dispatch never fires.
    verify(accountSnapshot, never()).accountSnapshot(any());
  }

  // Neither pct nor absolute set => inert: never trips, no PnL/equity reads.
  @Test
  void bothUnset_inert() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-both-unset");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(accountSnapshot, never()).accountSnapshot(any());
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // SOD equity unavailable + pct configured + NO absolute fallback => fail-SAFE DEFER: the account
  // cap is skipped this tick (no trip), the heartbeat survives, and the equity read is retried on
  // the next tick.
  @Test
  void sodEquityUnavailable_pctConfigured_defersDoesNotTripOrCrash() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // Equity read fails (broker outage) -> snapshot throws on every attempt.
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("equity_unavailable"));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-equity-down");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(135));

    // No trip on an unknown base, and the heartbeat keeps ticking (state query still answers).
    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    // Retried across multiple heartbeats (more than one tick attempted the read).
    verify(accountSnapshot, atLeast(2)).accountSnapshot(any());
  }

  // The SOD-equity snapshot is taken ONCE per trading day, not every heartbeat. Several heartbeats
  // within one day => exactly one equity read; a day rollover re-snapshots.
  @Test
  void sodEquitySnapshotOncePerDay() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));
    // Stay well under the cap so it never trips and the heartbeat keeps looping.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-snap-once");
    WorkflowStub.fromTyped(stub).start(input());

    // Three heartbeats on the same trading day => exactly one equity read.
    env.sleep(Duration.ofSeconds(190));
    verify(accountSnapshot, times(1)).accountSnapshot(any());

    // Roll the trading day forward => the next heartbeat re-snapshots SOD equity.
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    env.sleep(Duration.ofSeconds(70));
    verify(accountSnapshot, times(2)).accountSnapshot(any());
  }

  // ---------- continue-as-new sod_equity carry-forward ----------

  // The pure carry-forward builder honors the rolling-deploy schema_version branch. Mirrors the
  // sod_equity discipline extended for the v4 mtm-debounce counter and the v5 exposure cache: only
  // bump the version when the NEWER field is actually carried, so an old pod mid-rollout is never
  // handed a too-new input.
  //   exposure present        -> v5, last_open_positions/last_open_mtm carried
  //   count>0                 -> v4, both sod_equity + consecutive_mtm_unavailable_ticks carried
  //   count==0 && sod!=null    -> v3, sod_equity carried, ticks absent
  //   count==0 && sod==null    -> v2, all absent (byte-identical legacy shape)
  @Test
  void carryForwardInput_bumpsSchemaVersionOnlyWhenNewerFieldCarried() {
    LocalDate day = LocalDate.of(2026, 5, 14);

    // v7 (#670): the trip's trading-day tag is carried with a carried trip.
    AccountKillSwitchWorkflowInput withTripDay =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev",
            true,
            "auto:account_daily_loss",
            "auto:account_daily_loss",
            null,
            null,
            day,
            null,
            0,
            null,
            null,
            day,
            day);
    assertThat(withTripDay.getSchemaVersion()).isEqualTo(7L);
    assertThat(withTripDay.getTrippedTradingDay()).isEqualTo(day);

    // v6 (#669): the still-tripped page day is carried — a persistently-tripped cap rolls via
    // continue-as-new roughly daily, and losing this field loses that day's page.
    AccountKillSwitchWorkflowInput withPageDay =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev",
            true,
            "manual:ops",
            "operator:x",
            null,
            null,
            day,
            null,
            0,
            null,
            null,
            day,
            null);
    assertThat(withPageDay.getSchemaVersion()).isEqualTo(6L);
    assertThat(withPageDay.getLastStillTrippedPageDay()).isEqualTo(day);

    // v5: the reset-banner open-exposure cache is carried (with the sod_equity/debounce it
    // implies).
    AccountKillSwitchWorkflowInput withExposure =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev",
            false,
            "",
            "",
            null,
            null,
            day,
            new BigDecimal("5000"),
            1,
            2,
            new BigDecimal("-1500"),
            null,
            null);
    assertThat(withExposure.getSchemaVersion()).isEqualTo(5L);
    assertThat(withExposure.getLastOpenPositions()).isEqualTo(2L);
    assertThat(withExposure.getLastOpenMtm()).isEqualByComparingTo(new BigDecimal("-1500"));

    // v5 boundary: even a lone last_open_positions (0 count, null MTM before a fully-priced tick)
    // is exposure state -> stamp v5, MTM absent.
    AccountKillSwitchWorkflowInput exposureCountOnly =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev", false, "", "", null, null, day, null, 0, 0, null, null, null);
    assertThat(exposureCountOnly.getSchemaVersion()).isEqualTo(5L);
    assertThat(exposureCountOnly.getLastOpenPositions()).isEqualTo(0L);
    assertThat(exposureCountOnly.getLastOpenMtm()).isNull();

    // v4: a nonzero mid-debounce count is carried (with the sod_equity it implies); no exposure.
    AccountKillSwitchWorkflowInput withDebounce =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev",
            false,
            "",
            "",
            null,
            null,
            day,
            new BigDecimal("5000"),
            1,
            null,
            null,
            null,
            null);
    assertThat(withDebounce.getSchemaVersion()).isEqualTo(4L);
    assertThat(withDebounce.getConsecutiveMtmUnavailableTicks()).isEqualTo(1L);
    assertThat(withDebounce.getSodEquity()).isEqualByComparingTo(new BigDecimal("5000"));
    assertThat(withDebounce.getLastOpenPositions()).isNull();
    assertThat(withDebounce.getLastOpenMtm()).isNull();

    // v3: sod_equity captured but no active debounce (count==0) -> ticks absent, NOT a v4.
    AccountKillSwitchWorkflowInput withEquity =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev",
            false,
            "",
            "",
            null,
            null,
            day,
            new BigDecimal("5000"),
            0,
            null,
            null,
            null,
            null);
    assertThat(withEquity.getSchemaVersion()).isEqualTo(3L);
    assertThat(withEquity.getSodEquity()).isEqualByComparingTo(new BigDecimal("5000"));
    assertThat(withEquity.getConsecutiveMtmUnavailableTicks()).isNull();

    // v2: no newer field carried -> byte-identical legacy shape.
    AccountKillSwitchWorkflowInput noEquity =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev", false, "", "", null, null, day, null, 0, null, null, null, null);
    assertThat(noEquity.getSchemaVersion()).isEqualTo(2L);
    assertThat(noEquity.getSodEquity()).isNull();
    assertThat(noEquity.getConsecutiveMtmUnavailableTicks()).isNull();
    assertThat(noEquity.getLastOpenPositions()).isNull();
    assertThat(noEquity.getLastOpenMtm()).isNull();
  }

  // A workflow STARTED FROM a continue-as-new carry-forward input that already carries sod_equity
  // (v3) restores it at @WorkflowInit and does NOT re-dispatch the broker AccountSnapshot on the
  // post-CAN run — the same-day equity survives the CAN. The pct cap still works off the carried
  // value (pct=0.40 x carried 5000 = 2000 -> a -2000 loss trips).
  @Test
  void sodEquityCarriedAcrossCan_restoresWithoutRedispatch() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // If the workflow were to (incorrectly) re-read equity, it would get 9999 — proving any trip is
    // off the CARRIED 5000 (threshold 2000), not a fresh read (threshold 3999.6).
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("9999")));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-2000"));

    // Carried input as continueAsNew would emit it: v3, sod_equity=5000, same trading day. Built by
    // hand (NOT via carryForwardInput) so this restore assertion is independent of the builder the
    // unit test pins — a builder regression cannot mask itself through this fixture.
    AccountKillSwitchWorkflowInput carried = new AccountKillSwitchWorkflowInput();
    carried.setSchemaVersion(3L);
    carried.setTenantId("dev");
    carried.setTradingDay(LocalDate.of(2026, 5, 14));
    carried.setSodEquity(new BigDecimal("5000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-can-restore");
    WorkflowStub.fromTyped(stub).start(carried);
    env.sleep(Duration.ofSeconds(75));

    // Trips off the CARRIED equity (5000 -> threshold 2000; -2000 <= -2000).
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
    // The carried sodEquity was restored => NO broker snapshot re-dispatch on the post-CAN run.
    verify(accountSnapshot, never()).accountSnapshot(any());
  }

  // FIX 2 (init restore): a workflow STARTED FROM a v5 carry input restores the reset-banner
  // exposure at @WorkflowInit and surfaces it via killswitchState() BEFORE any post-restore
  // heartbeat runs — so the exposure is fresh immediately after a CAN, not blank for a heartbeat.
  // Queried before env.sleep so the only possible source is the init restore (no re-value).
  @Test
  void exposureRestoredFromV5CarryInput_surfacedByKillswitchState() {
    AccountKillSwitchWorkflowInput carried = new AccountKillSwitchWorkflowInput();
    carried.setSchemaVersion(5L);
    carried.setTenantId("dev");
    carried.setTradingDay(LocalDate.of(2026, 5, 14));
    carried.setLastOpenPositions(3L);
    carried.setLastOpenMtm(new BigDecimal("-2500")); // SIGNED negative — a loss

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-v5-exposure-restore");
    WorkflowStub.fromTyped(stub).start(carried);

    KillSwitchState s = stub.killswitchState(); // no sleep: reflects the init restore only
    assertThat(s.getOpenPositions()).isEqualTo(3L);
    assertThat(s.getOpenMtm()).isEqualByComparingTo("-2500");
  }

  // FIX 2 (init restore, absent): a pre-v5 carry (v3, sod_equity only, NO exposure fields) restores
  // the exposure as null — an absent field must never fabricate a stale exposure.
  @Test
  void exposureAbsentFromPreV5CarryInput_staysNull() {
    AccountKillSwitchWorkflowInput carried = new AccountKillSwitchWorkflowInput();
    carried.setSchemaVersion(3L);
    carried.setTenantId("dev");
    carried.setTradingDay(LocalDate.of(2026, 5, 14));
    carried.setSodEquity(new BigDecimal("5000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-prev5-exposure-null");
    WorkflowStub.fromTyped(stub).start(carried);

    KillSwitchState s = stub.killswitchState(); // no sleep: reflects the init restore only
    assertThat(s.getOpenPositions()).isNull();
    assertThat(s.getOpenMtm()).isNull();
  }

  // FIX 2 (THE WHOLE POINT — freshness after a CAN): a priceable book is valued (caches count +
  // signed MTM) on tick 1, which then continues-as-new (shrunk watermark). The carried exposure is
  // restored at @WorkflowInit, so killswitchState() queried AFTER the CAN boundary but BEFORE the
  // first post-CAN heartbeat STILL returns the exposure — pre-fix (fields dropped on CAN) both
  // would read null for up to a full heartbeat. Confirms the run-id rotated so this genuinely
  // crosses the CAN boundary.
  @Test
  void exposureCarriedAcrossCan_stateStaysFreshPostCan() {
    // Force a continueAsNew after the first (dense) valuation tick: a clean valuation tick's
    // history
    // (realized read + accountOpenBook + 2 quotes) far exceeds 30 events.
    AccountKillSwitchWorkflowImpl.historyLengthWatermark = 30L;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    // entry 3.00, bid 4.00 -> (4-3)*10*100 = +1000
                    new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 10L),
                    // entry 5.00, bid 6.00 -> (6-5)*5*100 = +500 ; total +1500
                    new OpenPositionValuation("AAPL  261218C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  261218C00140000")))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("4.00")));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  261218C00200000")))
        .thenReturn(okQuote("AAPL  261218C00200000", new BigDecimal("6.00")));

    String workflowId = "t-dev/account/killswitch-exposure-can";
    AccountKillSwitchWorkflow stub = newStub(workflowId);
    WorkflowStub typed = WorkflowStub.fromTyped(stub);
    typed.start(input());
    String runIdBeforeCan = typed.getExecution().getRunId();

    // Tick 1: values the book (caches count=2, MTM=+1500) then continueAsNew carries the exposure
    // (v5). The 75s stop lands 15s into the post-CAN run — BEFORE its first heartbeat (at +60s).
    env.sleep(Duration.ofSeconds(75));

    String runIdAfterCan =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(workflowId)
            .describe()
            .getExecution()
            .getRunId();
    assertThat(runIdAfterCan).isNotEqualTo(runIdBeforeCan); // genuinely crossed the CAN boundary

    // Queried post-CAN, before the first post-CAN heartbeat: the exposure is the CARRIED value.
    AccountKillSwitchWorkflow stubAfter =
        env.getWorkflowClient().newWorkflowStub(AccountKillSwitchWorkflow.class, workflowId);
    KillSwitchState s = stubAfter.killswitchState();
    assertThat(s.getOpenPositions()).isEqualTo(2L); // pre-fix: null
    assertThat(s.getOpenMtm()).isEqualByComparingTo("1500"); // pre-fix: null
  }

  // ---------- Issue #670: the armability-gated deferred rollover clear ----------

  /**
   * THE #670 window, closed: day 2 opens with the cap UN-ARMABLE (pct configured, SOD equity
   * unreadable, no absolute fallback — prod-kipark's 2026-07-21 state). Pre-#670 the rollover
   * cleared the trip anyway and the tenant traded all session with an inert cap. Now: the trip
   * HOLDS while un-armable (no unprotected window), the un-armable ticks page CAP-INACTIVE, and the
   * moment armability returns the deferred clear fires and normal evaluation resumes.
   */
  @Test
  void staleTrip_holdsWhileUnarmable_pagesInactive_thenClearsWhenArmable()
      throws InterruptedException {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000")); // trips the 5000 absolute on day 1

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-670");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // Day 2 arrives with the cap UN-ARMABLE: absolute pulled, pct configured but equity dark.
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("equity_unavailable"));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

    // Well past INACTIVE_ALERT_TICKS: the trip must HOLD and the inactive page must fire.
    env.sleep(Duration.ofSeconds(8 * 60));
    assertThat(stub.killswitchState().getTripped())
        .as("an un-armable cap must not un-halt itself")
        .isTrue();
    assertThat(countKind("KillSwitchClearedOnRollover")).isZero();
    waitForAuditKind("AccountKillSwitchCapInactive");
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);

    // Armability returns: the deferred clear fires with its marker and evaluation resumes.
    org.mockito.Mockito.reset(accountSnapshot);
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    env.sleep(Duration.ofSeconds(65));
    assertThat(stub.killswitchState().getTripped()).isFalse();
    waitForAuditKind("KillSwitchClearedOnRollover");
    AuditEvent cleared = captureKind("KillSwitchClearedOnRollover");
    assertThat(cleared.getSubject()).containsEntry("deferred_until_armable", true);
    assertThat(String.valueOf(cleared.getSubject().get("prior_trading_day")))
        .isEqualTo("2026-05-14");
  }

  /** Market closed at the rollover: the stale trip holds until the first OPEN tick, then clears. */
  @Test
  void staleTrip_holdsThroughClosedMarket_clearsAtTheOpen() throws InterruptedException {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-670-closed");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    when(calendar.isMarketOpen()).thenReturn(false);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    env.sleep(Duration.ofSeconds(2 * 60));
    assertThat(stub.killswitchState().getTripped())
        .as("pre-open, the overnight trip holds — no cleared-but-cannot-trip window")
        .isTrue();
    assertThat(countKind("KillSwitchClearedOnRollover")).isZero();

    when(calendar.isMarketOpen()).thenReturn(true);
    env.sleep(Duration.ofSeconds(65));
    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("KillSwitchClearedOnRollover")).isEqualTo(1L);
  }

  // ---------- cap-inactive observability alert (PR #504 follow-up) ----------

  // pct configured + SOD equity unavailable for >= INACTIVE_ALERT_TICKS consecutive ticks => emit
  // exactly ONE AccountKillSwitchCapInactive audit (the Discord alert trigger), NOT one per tick.
  @Test
  void pctConfigured_sodEquityDownForNTicks_emitsCapInactiveOnce() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("equity_unavailable"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-cap-inactive");
    WorkflowStub.fromTyped(stub).start(input());

    // Run well past INACTIVE_ALERT_TICKS (3) but inside the re-page window (30): exactly one alert.
    env.sleep(Duration.ofSeconds(8 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);
    // Recovery not yet reached.
    assertThat(countKind("AccountKillSwitchCapReArmed")).isEqualTo(0L);
  }

  // A single transient defer (fewer than INACTIVE_ALERT_TICKS) must NOT alert.
  @Test
  void pctConfigured_singleTransientDefer_doesNotAlert() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // Fail the FIRST snapshot, then succeed: equity becomes available, cap arms — counter never
    // reaches the alert threshold.
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("blip"))
        .thenReturn(snapshot(new BigDecimal("5000")));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-transient");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(5 * 60));

    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(0L);
  }

  // Recovery: after alerting inactive, equity comes back => cap arms => emit ONE re-arm audit and
  // reset, so a subsequent outage can alert inactive again (counter reset proven by re-alert).
  @Test
  void capInactiveThenRecovers_emitsReArmAndResetsCounter() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    // Equity DOWN on every call (defer every tick) — drive by time, not call-count, since the
    // 3-attempt retry policy inflates per-tick call counts.
    when(accountSnapshot.accountSnapshot(any())).thenThrow(new IllegalStateException("down"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-recovers");
    WorkflowStub.fromTyped(stub).start(input());

    // Past INACTIVE_ALERT_TICKS -> one inactive alert.
    env.sleep(Duration.ofSeconds(6 * 60));
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);

    // Equity recovers -> the next arm tick emits the re-arm audit and resets the counter. Use
    // doReturn (not when(...).thenReturn) to re-stub a currently-throwing mock without invoking it.
    Mockito.doReturn(snapshot(new BigDecimal("5000"))).when(accountSnapshot).accountSnapshot(any());
    env.sleep(Duration.ofSeconds(3 * 60));

    assertThat(countKind("AccountKillSwitchCapReArmed")).isEqualTo(1L);
    assertThat(stub.killswitchState().getTripped()).isFalse();
  }

  // pct NOT configured (absolute-only, or nothing) => never alerts, no behavior change. Here the
  // absolute cap is set and the book is empty: the cap arms every tick, never inactive.
  @Test
  void pctNotConfigured_neverAlertsInactive() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-abs-noalert");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(8 * 60));

    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(0L);
    assertThat(countKind("AccountKillSwitchCapReArmed")).isEqualTo(0L);
  }

  // (b) An unroutable/bare broker_target must DEGRADE to a defer (no throw, no heartbeat error
  // spam) and feed the inactive counter — proving the stub-build/fromValue moved inside the try.
  @Test
  void bareBrokerTarget_degradesToDeferAndFeedsInactiveCounter() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // A legacy bare target: ExecActivitiesFactory.taskQueueFor rejects it (no worker polls
    // broker-paper); pre-fix this threw out of captureSodEquity -> heartbeat error. Now it defers.
    when(tenantConfig.tenantBrokerTarget(anyString())).thenReturn("paper");

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-bare-target");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(8 * 60));

    // No heartbeat-error spam (the bare target degraded inside captureSodEquity, not at run()).
    assertThat(countKind("KillSwitchHeartbeatError")).isEqualTo(0L);
    // The defer fed the inactive counter and crossed the threshold -> exactly one inactive alert.
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);
    assertThat(stub.killswitchState().getTripped()).isFalse();
  }

  // PLAN-2026-07-22 (fail-loud): a configured pct cap whose broker_target does NOT resolve (the
  // DB-onboarded-tenant-absent-from-the-tree structural silent-unprotect, prod-kipark 2026-07-21)
  // must DEFER fail-LOUD — the AccountKillSwitchCapInactive subject names the typed reason
  // (broker_target_unresolved) and, because the tenant HOLDS an open position, carries the
  // open_positions count that gates the loud-RED "cap NOT protecting" escalation.
  @Test
  void brokerTargetUnresolved_capInactive_carriesTypedReasonAndOpenPositions() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // broker_target does not resolve -> captureSodEquity defers with
    // reason=broker_target_unresolved.
    when(tenantConfig.tenantBrokerTarget(anyString())).thenReturn(null);
    // The tenant is holding one open position (probed on the emit tick for the holds-risk gate).
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-broker-unresolved");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(8 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    // No spurious trip on an unknown loss base, no heartbeat-error spam.
    assertThat(countKind("KillSwitchHeartbeatError")).isEqualTo(0L);
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);

    AuditEvent inactive = captureKind("AccountKillSwitchCapInactive");
    assertThat(inactive.getSubject())
        .containsEntry("reason", "broker_target_unresolved")
        .containsEntry("open_positions", 1);
  }

  // ---------- Phase 2 (C4/G2): account fail-CLOSED on a per-strategy realized read failure
  // ----------

  // Two strategies on different broker_targets; the SECOND per-strategy realized read throws. G2
  // FORBIDS a partial sum — the whole tenant compute defers (no trip on a partial/unknown number),
  // even though the first strategy alone would already cross the cap.
  @Test
  void heartbeat_perStrategyRealizedReadFails_failsClosed_noPartialSumNoTrip() {
    // The exec activity is registered on both broker queues in setUp.
    when(accountPnl.tenantStrategyBrokerTargets(anyString()))
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget("s1", "alpaca-paper"),
                new TenantStrategyBrokerTarget("s2", "alpaca-live")));
    // s1 alone (-6000) would cross the 5000 cap; s2's read is unavailable. Must NOT sum the partial
    // and trip — a partial under-counts the loss (here it would OVER-count, but the invariant is
    // "never trip on an incomplete tenant sum"): defer instead.
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s1"), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s2"), any()))
        .thenThrow(new IllegalStateException("exec live journal unavailable"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-failclosed-realized");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // C6/G1: a persistent per-strategy realized-read outage on v>=1 never trips (missing number is
  // not a loss) and, past REALIZED_READ_FAILURE_ALERT_TICKS, emits ONE distinct bounded alert.
  @Test
  void heartbeat_realizedReadOutage_doesNotTrip_thenAlertsAfterThreshold() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("exec journal unavailable"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-realized-outage");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(4 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    assertThat(countKind("KillSwitchRealizedReadUnavailable")).isEqualTo(1L);
  }

  // ---------- Phase 2b (risk C1): periodic still-holding re-page ----------

  // Tripped + market-open + holding across the throttle cadence: re-page fires on the boundary
  // (carrying count/MTM/minutes-since-trip), NOT every tick.
  @Test
  void trippedHoldingMarketOpen_repagesOnThrottleBoundary_notEveryTick() {
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = 3;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000")); // crosses the 5000 absolute cap -> auto trip
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-repage");
    WorkflowStub.fromTyped(stub).start(input());

    // Trip on tick 1 (t~=60s); first re-page after 3 more tripped market-open ticks (t~=240s).
    env.sleep(Duration.ofSeconds(260));
    assertThat(stub.killswitchState().getTripped()).isTrue();
    assertThat(stub.killswitchState().getReason()).isEqualTo("auto:account_daily_loss");
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);

    // Between boundaries (next at ~420s) NO new page — proves it does not fire every tick.
    env.sleep(Duration.ofSeconds(120)); // total ~380s
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);

    // Past the next boundary -> second re-page.
    env.sleep(Duration.ofSeconds(80)); // total ~460s
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(2L);

    AuditEvent repage = captureKind("AccountKillSwitchStillHolding");
    assertThat(repage.getSubject())
        .containsEntry("scope", "account")
        .containsEntry("open_positions", 1)
        .containsKey("open_mtm")
        .containsKey("minutes_since_trip");
  }

  // Operator flattens (book -> empty): re-paging STOPS (holding -> 0) AND the cached exposure the
  // reset banner reads clears to a flat book (0/null) rather than staying stuck at the last
  // non-zero figure (PLAN-2026-07-22 #591 flatten-to-zero freshness).
  @Test
  void tripped_holdingDropsToZero_stopsRepaging_andClearsCachedExposure() {
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = 3;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-repage-flat");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(260));
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);
    // Precondition: the tripped-holding heartbeat cached the non-zero exposure the banner shows.
    KillSwitchState held = stub.killswitchState();
    assertThat(held.getOpenPositions()).isEqualTo(1L);
    assertThat(held.getOpenMtm()).isNotNull();

    // Operator manually flattened -> the book is now empty.
    Mockito.doReturn(new AccountOpenBook(List.of(), 0, 0))
        .when(accountPnl)
        .accountOpenBook(anyString());
    env.sleep(Duration.ofSeconds(300));

    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);
    // The cached exposure must now reflect the flat book, not the stale non-zero figure.
    KillSwitchState flat = stub.killswitchState();
    assertThat(flat.getOpenPositions()).isZero();
    assertThat(flat.getOpenMtm()).isNull();
  }

  // Market closes after a trip: re-paging STOPS (no overnight spam).
  @Test
  void tripped_marketCloses_stopsRepaging() {
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = 3;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-repage-closed");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(260));
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);

    // Market closes -> the re-page cadence resets and no further pages fire.
    Mockito.doReturn(false).when(calendar).isMarketOpen();
    env.sleep(Duration.ofSeconds(300));

    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);
  }

  // Reset (untrip) STOPS re-paging: the loss is resolved and the switch does not re-trip / re-page.
  @Test
  void tripped_thenReset_stopsRepaging() {
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = 3;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-repage-reset");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(260));
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);

    // Operator resets; loss is resolved (flattened) so it neither re-trips nor re-pages.
    stub.reset(resetRequest("alice"));
    Mockito.doReturn(BigDecimal.ZERO)
        .when(execPnl)
        .computeRealizedPnl(anyString(), anyString(), any());
    Mockito.doReturn(new AccountOpenBook(List.of(), 0, 0))
        .when(accountPnl)
        .accountOpenBook(anyString());
    env.sleep(Duration.ofSeconds(400));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);
  }

  // Quotes go dark before a re-page: the page still fires with the count + minutes, but OMITS the
  // (now unreliable) MTM — degrade quietly.
  @Test
  void tripped_quoteUnavailableDuringRepage_pagesCountWithoutMtm() {
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = 3;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-repage-noquote");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(70)); // trip on tick 1
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // Quotes go dark before the first re-page boundary.
    Mockito.doReturn(unavailableQuote("NVDA  261218C00140000"))
        .when(optionQuote)
        .getOptionQuote(any());
    env.sleep(Duration.ofSeconds(200)); // past the ~t=240s boundary

    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);
    AuditEvent repage = captureKind("AccountKillSwitchStillHolding");
    assertThat(repage.getSubject())
        .containsEntry("open_positions", 1)
        .containsKey("minutes_since_trip")
        .doesNotContainKey("open_mtm");
  }

  // ---------- PLAN-2026-08-12: clear the auto account daily-loss trip at the rollover ----------

  @Test
  void heartbeat_dayRollover_clearsAutoAccountDailyLossTrip_andAudits()
      throws InterruptedException {
    // A DAILY cap must be daily. An auto:account_daily_loss trip taken on day 1 must NOT survive
    // the trading-day rollover: the next heartbeat that observes a new todayEt clears the trip
    // tuple and records the un-halt in audit_log.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000")); // crosses the 5000 absolute cap

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-rollover-clear");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75)); // tick 1: trips on day 1
    KillSwitchState day1 = stub.killswitchState();
    assertThat(day1.getTripped()).isTrue();
    assertThat(day1.getActor()).isEqualTo("auto:account_daily_loss");

    // Roll the trading day forward BEFORE the next tick, and stop the loss so the cleared cap does
    // not immediately re-trip (the re-trip path has its own test below).
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

    env.sleep(Duration.ofSeconds(60)); // tick 2: rollover -> clear

    KillSwitchState day2 = stub.killswitchState();
    assertThat(day2.getTripped()).isFalse();
    assertThat(day2.getReason()).isEmpty();
    assertThat(day2.getActor()).isEmpty();
    assertThat(day2.getTrippedAt()).isNull();
    assertThat(day2.getTradingDay()).isEqualTo(LocalDate.of(2026, 5, 15));
    // coolingDownUntil is a POST-RESET debounce, not day-scoped: the rollover must not invent one
    // (a cooldown here would have RiskActivitiesImpl reject entries with KILL_SWITCH_COOLING_DOWN
    // for the window the clear exists to end).
    assertThat(day2.getCoolingDownUntil()).isNull();

    waitForAuditKind("KillSwitchClearedOnRollover");
    AuditEvent cleared = captureKind("KillSwitchClearedOnRollover");
    assertThat(cleared.getSubject())
        .containsEntry("reason", "auto:account_daily_loss")
        .containsEntry("actor", "auto:account_daily_loss")
        .containsEntry("scope", "account")
        .containsKey("tripped_at");
    assertThat(String.valueOf(cleared.getSubject().get("prior_trading_day")))
        .isEqualTo("2026-05-14");
    assertThat(String.valueOf(cleared.getSubject().get("trading_day"))).isEqualTo("2026-05-15");
    assertThat(countKind("KillSwitchClearedOnRollover")).isEqualTo(1L);
  }

  @Test
  void heartbeat_dayRollover_mtmUnavailableTripPersists() {
    // THE NON-GOAL GUARD (PLAN-2026-08-12). auto:account_mtm_unavailable is a DATA-QUALITY
    // fail-closed, not a day event — the cap engaged because the book could not be priced, and a
    // new calendar day does not make it priceable. It must stay tripped across the rollover. Guards
    // against a startsWith("auto:") regression: the discriminator is an EXACT actor match.
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_INTICK_REFETCHES = 0;
    AccountKillSwitchWorkflowImpl.MTM_UNAVAILABLE_TRIP_TICKS = 1; // fail-close on the first miss
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  261218C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-rollover-mtm");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75)); // tick 1: fail-closed trip
    KillSwitchState day1 = stub.killswitchState();
    assertThat(day1.getTripped()).isTrue();
    assertThat(day1.getActor()).isEqualTo("auto:account_mtm_unavailable");

    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    env.sleep(Duration.ofSeconds(60));

    // Positive sync point FIRST (the Query round-trips through the workflow, proving the rollover
    // tick was processed) — only then is the "no clear audit" assertion meaningful.
    KillSwitchState day2 = stub.killswitchState();
    assertThat(day2.getTradingDay()).isEqualTo(LocalDate.of(2026, 5, 15));
    assertThat(day2.getTripped()).isTrue();
    assertThat(day2.getActor()).isEqualTo("auto:account_mtm_unavailable");
    assertThat(countKind("KillSwitchClearedOnRollover")).isZero();
  }

  @Test
  void heartbeat_dayRollover_operatorTripPersists() {
    // A deliberate operator halt must never be silently re-armed by the passage of midnight.
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-rollover-operator");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:operator_initiated", "operator:ridopark"));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTradingDay()).isEqualTo(LocalDate.of(2026, 5, 15));
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("manual:operator_initiated");
    assertThat(s.getActor()).isEqualTo("operator:ridopark");
    assertThat(countKind("KillSwitchClearedOnRollover")).isZero();
  }

  @Test
  void heartbeat_dayRolloverClear_doesNotRepageWhileHolding() {
    // The clear sits at the END of the rollover branch, BEFORE the `if (tripped)` block — so a
    // cleared tick structurally cannot reach maybeRepageWhileHolding(). With the re-page window
    // set to 1, EVERY tripped tick pages; the rollover tick must page ZERO times because by then
    // the switch is no longer tripped.
    AccountKillSwitchWorkflowImpl.STILL_HOLDING_REPAGE_TICKS = 1;
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(accountPnl.accountOpenBook(anyString())).thenReturn(holdingBook());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  261218C00140000", new BigDecimal("2.90")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-rollover-norepage");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75)); // tick 1: trip
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // POSITIVE CONTROL: one more tripped tick on the SAME day does re-page, so the zero below is
    // the clear's doing and not a mis-wired throttle.
    env.sleep(Duration.ofSeconds(60)); // tick 2: tripped + holding -> re-page
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);

    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    env.sleep(Duration.ofSeconds(60)); // tick 3: rollover -> clear -> NOT a tripped tick

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getTradingDay()).isEqualTo(LocalDate.of(2026, 5, 15));
    assertThat(countKind("AccountKillSwitchStillHolding")).isEqualTo(1L);
  }

  @Test
  void heartbeat_afterRolloverClear_reSnapshotsSodEquityAndReTripsSameTick()
      throws InterruptedException {
    // The rollover already nulls sodEquity, so a cleared cap re-captures the NEW day's start-of-day
    // equity and evaluates against it — the whole point of clearing. And because the clear falls
    // through to normal evaluation on the SAME tick, a book that is still underwater on the new day
    // clears and re-trips within one heartbeat: correct-but-noisy, and strictly better than a
    // silent multi-day halt (PLAN-2026-08-12 "Non-goal: the cross-day unrealized charge").
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));
    // Effective cap = 0.40 * 5000 = 2000; a -3000 realized loss crosses it on both days.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-3000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-rollover-resnapshot");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75)); // tick 1: day-1 SOD snapshot + trip
    assertThat(stub.killswitchState().getTripped()).isTrue();
    verify(accountSnapshot, times(1)).accountSnapshot(any());

    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    env.sleep(Duration.ofSeconds(60)); // tick 2: rollover -> clear -> re-snapshot -> re-trip

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getActor()).isEqualTo("auto:account_daily_loss");
    assertThat(s.getTradingDay()).isEqualTo(LocalDate.of(2026, 5, 15));
    // The cleared cap re-armed against DAY 2's start-of-day equity, not day 1's stale base.
    verify(accountSnapshot, times(2)).accountSnapshot(any());

    // The clear is on the record, and the re-trip is a NEW trip rather than the stale day-1 one.
    waitForAuditKind("KillSwitchClearedOnRollover");
    waitForKindCount("KillSwitchTripped", 2L);
    assertThat(countKind("KillSwitchClearedOnRollover")).isEqualTo(1L);
    assertThat(countKind("KillSwitchTripped")).isEqualTo(2L);
  }

  @Test
  void heartbeat_sameDay_accountDailyLossTripStaysTripped() {
    // The clear is scoped to the ROLLOVER branch. Without this test, a bug that hoists it out of
    // `if (!today.equals(tradingDay))` would un-trip the real-money cap on the very next heartbeat
    // — making it a no-op — and every other test here would still pass.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-sameday-sticky");
    WorkflowStub.fromTyped(stub).start(input());

    // Several ticks WITHOUT advancing todayEt.
    env.sleep(Duration.ofSeconds(4 * 60));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getActor()).isEqualTo("auto:account_daily_loss");
    assertThat(s.getTradingDay()).isEqualTo(LocalDate.of(2026, 5, 14));
    assertThat(countKind("KillSwitchClearedOnRollover")).isZero();
  }

  // ---------- helpers ----------

  private static AccountOpenBook holdingBook() {
    return new AccountOpenBook(
        List.of(new OpenPositionValuation("NVDA  261218C00140000", new BigDecimal("3.00"), 1L)),
        1,
        0);
  }

  private static AccountSnapshotResult snapshot(BigDecimal equity) {
    AccountSnapshotResult r = new AccountSnapshotResult();
    r.setSchemaVersion(1L);
    r.setEquity(equity);
    return r;
  }

  private AccountKillSwitchWorkflow newStub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            AccountKillSwitchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .build());
  }

  private static AccountKillSwitchWorkflowInput input() {
    AccountKillSwitchWorkflowInput in = new AccountKillSwitchWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    return in;
  }

  private static TripKillSwitchRequest tripRequest(String reason, String actor) {
    TripKillSwitchRequest r = new TripKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setReason(reason);
    r.setActor(actor);
    return r;
  }

  private static ResetKillSwitchRequest resetRequest(String a1) {
    ResetKillSwitchRequest r = new ResetKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setApproverId1(a1);
    return r;
  }

  private static GetOptionQuoteRequest quoteFor(String contractSymbol) {
    return org.mockito.ArgumentMatchers.argThat(
        req -> req != null && contractSymbol.equals(req.getContractSymbol()));
  }

  private static OptionQuoteResult okQuote(String contractSymbol, BigDecimal bid) {
    OptionQuoteResult q = new OptionQuoteResult();
    q.setSchemaVersion(1L);
    q.setContractSymbol(contractSymbol);
    q.setBid(bid);
    q.setRetrievedAt(OffsetDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC));
    q.setStatus(OptionQuoteResult.Status.OK);
    return q;
  }

  private static OptionQuoteResult unavailableQuote(String contractSymbol) {
    OptionQuoteResult q = new OptionQuoteResult();
    q.setSchemaVersion(1L);
    q.setContractSymbol(contractSymbol);
    q.setRetrievedAt(OffsetDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC));
    q.setStatus(OptionQuoteResult.Status.UNAVAILABLE);
    return q;
  }

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  /** Counts audit events of {@code kind} logged so far (0 if none) — never throws. */
  private long countKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    Mockito.verify(audit, Mockito.atLeast(0)).log(captor.capture());
    return captor.getAllValues().stream().filter(e -> kind.equals(e.getKind())).count();
  }

  /**
   * Deterministic sync point for async audit emissions (mirrors {@code
   * KillSwitchWorkflowImplTest}). Heartbeat-driven audits are emitted on the activity worker thread
   * while the workflow clock is skipped by {@link TestWorkflowEnvironment#sleep}; under CI load the
   * skip can return before the last tick's {@code audit.log} invocation is visible to this (test)
   * thread, making an instantaneous {@link #captureKind}/{@link #countKind} read flaky. Poll
   * (bounded) until at least {@code atLeast} events of {@code kind} have been captured before
   * asserting on them.
   */
  private void waitForKindCount(String kind, long atLeast) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (countKind(kind) >= atLeast) {
        return;
      }
      Thread.sleep(25);
    }
    if (countKind(kind) < atLeast) {
      throw new AssertionError(
          "timed out waiting for >=" + atLeast + " audit event(s) with kind=" + kind);
    }
  }

  /** Bounded wait for the first audit event of {@code kind} (see {@link #waitForKindCount}). */
  private void waitForAuditKind(String kind) throws InterruptedException {
    waitForKindCount(kind, 1L);
  }

  /**
   * Issue #669: a tripped-and-FLAT account cap previously said nothing forever (the holding re-page
   * requires open positions). Now: nothing more on the trip day, then exactly one still-tripped
   * page per subsequent trading day. An operator trip is used — the sticky actor class the rollover
   * never clears.
   */
  @Test
  void heartbeat_stillTrippedFlat_pagesOncePerSubsequentDay() throws Exception {
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-still-flat");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:operator_halt", "operator:ridopark"));

    // Trip-day ticks: the trip page already fired; the daily reminder starts tomorrow.
    env.sleep(Duration.ofSeconds(75));
    env.sleep(Duration.ofSeconds(65));
    assertThat(countKind("KillSwitchStillTripped")).isEqualTo(0L);

    // Day 2, flat book: exactly one page across several ticks.
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    env.sleep(Duration.ofSeconds(65));
    env.sleep(Duration.ofSeconds(65));
    waitForAuditKind("KillSwitchStillTripped");
    assertThat(countKind("KillSwitchStillTripped")).isEqualTo(1L);
    assertThat(captureKind("KillSwitchStillTripped").getSubject())
        .containsEntry("actor", "operator:ridopark")
        .containsKey("tripped_at");
    assertThat(stub.killswitchState().getTripped()).isTrue();
  }
}
