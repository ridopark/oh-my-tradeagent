package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.EquityTick;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.FireDecision;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribeEquityActivity;
import com.ohmytradeagent.orchestrator.activities.TriggerFireDecider;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Temporal-test coverage for {@link WatchlistTriggerWorkflowImpl}, plan items (a)-(k). The pure
 * cross/band/state logic is covered exhaustively in {@code EntryStateMachineTest}; here we exercise
 * the workflow wiring: signal delivery, the FIRE order path, fail-closed branches, EOD cancel, the
 * exactly-one-placeOrder guarantee, and stale-tick tolerance.
 */
class WatchlistTriggerWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";
  private static final String OCC = "NVDA  260626C00762000";

  private TestWorkflowEnvironment env;
  private long savedHistoryLengthWatermark;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private RiskActivities risk;
  private ContractActivities contract;
  private TriggerFireDecider fireDecider;
  private SubscribeEquityActivity subscribeEquity;
  private GetOptionQuoteActivity optionQuote;
  private ExecActivities exec;
  private AccountSnapshotActivity accountSnapshot;
  private MarketCalendarActivity tradingCalendar;
  private PositionLookupActivities positionLookup;

  @BeforeEach
  void setUp() {
    savedHistoryLengthWatermark = WatchlistTriggerWorkflowImpl.historyLengthWatermark;
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(
        WatchlistTriggerWorkflowImpl.class, PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    risk = Mockito.mock(RiskActivities.class);
    contract = Mockito.mock(ContractActivities.class);
    fireDecider = Mockito.mock(TriggerFireDecider.class);
    subscribeEquity = Mockito.mock(SubscribeEquityActivity.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);
    exec = Mockito.mock(ExecActivities.class);
    accountSnapshot = Mockito.mock(AccountSnapshotActivity.class);
    tradingCalendar = Mockito.mock(MarketCalendarActivity.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);

    // Defaults: no EOD pressure, all gates green, $100k cash, $3.15 premium, fills via signal.
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    lenient()
        .when(fireDecider.evaluateTriggerFire(any(), any()))
        .thenReturn(
            new FireDecision()
                .withProceed(true)
                .withSizeMultiplier(BigDecimal.ONE)
                .withReason("default"));
    lenient()
        .when(risk.checkWatchlistEntry(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    lenient()
        .when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                OCC,
                "NVDA",
                LocalDate.of(2026, 6, 26),
                new BigDecimal("762"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    lenient()
        .when(subscribeEquity.subscribeEquity(any()))
        .thenReturn(subscribeResult(SubscribeEquityResult.Status.SUBSCRIBED));
    lenient().when(optionQuote.getOptionQuote(any())).thenReturn(quoteOk(new BigDecimal("3.15")));
    lenient()
        .when(accountSnapshot.accountSnapshot(any()))
        .thenReturn(cash(new BigDecimal("100000")));
    lenient()
        .when(tradingCalendar.tradingDays(any(), any()))
        .thenReturn(weekdayCalendar(LocalDate.of(2026, 6, 23), 21));
    lenient().when(exec.placeOrder(any())).thenReturn(submittedResult());

    coreWorker.registerActivitiesImplementations(
        audit, calendar, risk, contract, fireDecider, positionLookup);
    Worker mdWorker = env.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(subscribeEquity, optionQuote);
    Worker brokerWorker = env.newWorker(BROKER_QUEUE);
    brokerWorker.registerActivitiesImplementations(exec, accountSnapshot, tradingCalendar);

    env.start();
  }

  @AfterEach
  void tearDown() {
    WatchlistTriggerWorkflowImpl.historyLengthWatermark = savedHistoryLengthWatermark;
    env.close();
  }

  // (b) BREAKOUT live cross into band -> one order (weekly OCC, qty = flat sizing).
  @Test
  void breakoutAbove_liveCross_placesOneOrder() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-breakout-fire");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false)); // seed below T
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");

    ArgumentCaptor<OrderIntent> captor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(1)).placeOrder(captor.capture());
    OrderIntent intent = captor.getValue();
    assertThat(intent.getSide()).isEqualTo(OrderIntent.Side.BUY);
    assertThat(intent.getOptionSymbol()).isEqualTo(OCC); // weekly-expiry OCC
    assertThat(intent.getLimitPrice()).isEqualByComparingTo("3.15");
    assertThat(intent.getIntentKey()).isEqualTo("wl-breakout-fire:entry");
    // flat sizing: floor(100000 * 0.2 / (3.15*100)) = 63, clamped to max_contracts 50.
    assertThat(intent.getQty()).isEqualTo(50L);
  }

  // PLAN-2026-07-09: entry_mode is now opt-in (null-when-absent) in the shared schema. A watchlist
  // config that leaves it unset must still behave as BREAKOUT via the code-side entryMode()
  // fallback: the TriggerArmed audit records entry_mode=BREAKOUT and the breakout cross fires.
  @Test
  void breakoutAbove_nullEntryMode_resolvesBreakout() throws Exception {
    StrategyConfig c = config();
    c.setEntryMode(null); // opt-in field left unset -> code default BREAKOUT
    WatchlistTriggerWorkflow wf = newStub("wl-null-entrymode");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false)); // seed below T
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");

    AuditEvent armed = captureKind("TriggerArmed");
    assertThat(armed.getSubject()).containsEntry("entry_mode", "BREAKOUT");
    // (placeOrder count already asserted by waitForPlaceOrderCount(1) above.)
  }

  // Sub-penny option-quote midpoint (e.g. (2.65+2.70)/2 = 2.675) must be rounded to a 2-decimal
  // penny tick before it becomes the BTO limit, else Alpaca rejects the order with a non-retryable
  // 422. HALF_UP at the half-cent rounds up: 2.675 -> 2.68.
  @Test
  void fire_halfCentMid_placesPennyRoundedLimit() throws Exception {
    when(optionQuote.getOptionQuote(any())).thenReturn(quoteOk(new BigDecimal("2.675")));
    WatchlistTriggerWorkflow wf = newStub("wl-halfcent");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("2.68")));

    WorkflowStub.fromTyped(wf).getResult(String.class);
    ArgumentCaptor<OrderIntent> captor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(1)).placeOrder(captor.capture());
    BigDecimal limit = captor.getValue().getLimitPrice();
    assertThat(limit).isEqualByComparingTo("2.68");
    assertThat(limit.scale()).isEqualTo(2);
  }

  // Below the half-cent, HALF_UP rounds down: 2.674 -> 2.67.
  @Test
  void fire_roundsDownBelowHalfCent() throws Exception {
    when(optionQuote.getOptionQuote(any())).thenReturn(quoteOk(new BigDecimal("2.674")));
    WatchlistTriggerWorkflow wf = newStub("wl-rounddown");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("2.67")));

    WorkflowStub.fromTyped(wf).getResult(String.class);
    ArgumentCaptor<OrderIntent> captor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(1)).placeOrder(captor.capture());
    assertThat(captor.getValue().getLimitPrice()).isEqualByComparingTo("2.67");
  }

  // Already a 2-decimal penny tick: rounding is a no-op (no drift).
  @Test
  void fire_alreadyTwoDecimals_unchanged() throws Exception {
    when(optionQuote.getOptionQuote(any())).thenReturn(quoteOk(new BigDecimal("2.65")));
    WatchlistTriggerWorkflow wf = newStub("wl-twodp");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("2.65")));

    WorkflowStub.fromTyped(wf).getResult(String.class);
    ArgumentCaptor<OrderIntent> captor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(1)).placeOrder(captor.capture());
    assertThat(captor.getValue().getLimitPrice()).isEqualByComparingTo("2.65");
  }

  // (c) BREAKOUT first tick already past band -> SKIP, no order.
  @Test
  void breakoutAbove_firstCrossPastBand_skips() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-breakout-skip");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.50"), false)); // seed
    wf.equityTick(tick(new BigDecimal("775.00"), false)); // ran past chase cap -> SKIP

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":skipped");
    verify(exec, never()).placeOrder(any());
  }

  // (f) ABOVE & BELOW mirrored: BREAKOUT below fires.
  @Test
  void breakoutBelow_liveCross_placesOneOrder() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-breakout-below");
    WorkflowStub.fromTyped(wf).start(input(breakoutBelowPayload(), config()));

    wf.equityTick(tick(new BigDecimal("761.50"), false)); // seed above T
    wf.equityTick(tick(new BigDecimal("760.60"), false)); // cross down into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
  }

  // (d) RETEST breakout-then-pullback -> one order.
  @Test
  void retestAbove_breakoutThenPullback_placesOneOrder() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-retest-fire");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), retestConfig()));

    wf.equityTick(tick(new BigDecimal("765.50"), false)); // > bandHigh -> BROKEN_OUT
    wf.equityTick(tick(new BigDecimal("763.20"), false)); // pull-back into Z -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
  }

  // (e) RETEST breakout-then-failed -> SKIP, no order.
  @Test
  void retestAbove_breakoutThenFailed_skips() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-retest-skip");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), retestConfig()));

    wf.equityTick(tick(new BigDecimal("765.50"), false)); // BROKEN_OUT
    wf.equityTick(tick(new BigDecimal("750.00"), false)); // < bandLow -> support lost -> SKIP

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":skipped");
    verify(exec, never()).placeOrder(any());
  }

  // (a) no cross before EOD -> no order (EOD cancels the un-fired leg).
  @Test
  void noCrossBeforeEod_cancelsNoOrder() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    WatchlistTriggerWorkflow wf = newStub("wl-eod");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("759.00"), false)); // below T, no cross
    env.sleep(Duration.ofMinutes(1)); // fire the EOD timer

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":eod_cancelled");
    verify(exec, never()).placeOrder(any());
  }

  // Finding 1: GATED subscription (default unentitled-feed posture) -> loud audit, no ticks ever
  // arrive, leg fails closed to the EOD cancel with no order placed.
  @Test
  void gatedSubscription_emitsLoudAudit_noOrder_eodCancels() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(subscribeEquity.subscribeEquity(any()))
        .thenReturn(subscribeResult(SubscribeEquityResult.Status.GATED));
    WatchlistTriggerWorkflow wf = newStub("wl-gated");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    env.sleep(Duration.ofMinutes(1)); // fire the EOD timer; no ticks ever arrive

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":eod_cancelled");
    verify(exec, never()).placeOrder(any());
    AuditEvent gated = captureKind("TriggerSubscriptionUnavailable");
    assertThat(gated.getSubject()).containsEntry("ticker", "NVDA");
    assertThat(gated.getSubject()).containsEntry("status", "GATED");
  }

  // Finding 3: continue-as-new replay seeded with BROKEN_OUT (RETEST). The live-cross guarantee
  // must hold across resume: a first post-resume tick still beyond the band must NOT fire; only a
  // valid pull-back into the zone fires.
  @Test
  void retestResumedBrokenOut_firesOnlyOnValidPullback() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-retest-resume");
    WatchlistTriggerWorkflowInput in = input(breakoutAbovePayload(), retestConfig());
    in.setCarriedState(EntryStateMachine.State.BROKEN_OUT);
    in.setCarriedPrev(new BigDecimal("765.50"));
    in.setEtDate(LocalDate.of(2026, 6, 24));
    WorkflowStub.fromTyped(wf).start(in);

    wf.equityTick(
        tick(new BigDecimal("766.00"), false)); // still above bandHigh -> no spurious fire
    waitNoPlaceOrder();
    wf.equityTick(tick(new BigDecimal("763.20"), false)); // valid pull-back into Z -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
  }

  // Blocker: the streaming equity subscription is created exactly once across the whole lifecycle,
  // even when the leg continues-as-new at the history watermark. A resumed run reuses the existing
  // subscription (same workflow id) and must NOT re-subscribe, else every resume stacks another
  // listener delivering duplicate ticks.
  @Test
  void subscriptionCreatedOnceAcrossContinueAsNew() throws Exception {
    // Long EOD so the leg keeps looping; lower the watermark so a few non-firing ticks trip
    // continue-as-new.
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(200));
    WatchlistTriggerWorkflowImpl.historyLengthWatermark = 1L;
    WatchlistTriggerWorkflow wf = newStub("wl-resub");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    // Sub-trigger ticks never cross (prev < T && last >= T is required), so the machine stays ARMED
    // and the loop reaches the watermark check -> continue-as-new on each pass.
    for (int i = 0; i < 5; i++) {
      wf.equityTick(tick(new BigDecimal("759.00").add(new BigDecimal(i % 2)), false));
    }
    env.sleep(Duration.ofMinutes(1)); // fire the EOD timer on the resumed run -> terminate

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":eod_cancelled");
    verify(exec, never()).placeOrder(any());
    // Exactly one subscription across all continue-as-new cycles (not once-per-run).
    verify(subscribeEquity, times(1)).subscribeEquity(any());
  }

  // Finding #1: durationUntilEodEt returns null -> no NPE; leg fails safe to the EOD-cancel path
  // with no order placed.
  @Test
  void nullEodDuration_failsSafeToEodCancel_noOrder() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(null);
    WatchlistTriggerWorkflow wf = newStub("wl-null-eod");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":eod_cancelled");
    verify(exec, never()).placeOrder(any());
  }

  // Phase 1 (root cause): a live cross inside the EOD entry cutoff must NOT open a position. The
  // 2026-06-24 incident fired at 15:33 ET, 3 min AFTER its own force_close_eod_et=15:30, and could
  // not flatten before the 16:00 bell. With no_entry_within_close_minutes set and the calendar
  // reporting little/no time to the cutoff, fire() rejects (reason=too_close_to_eod), returns
  // eod_skip, and NEVER places an order.
  @Test
  void crossInsideEodCutoff_rejects_noOrder() throws Exception {
    // Cutoff (force_close_eod_et=15:30) is essentially now -> zero duration to close.
    when(calendar.durationUntilEodCloseEt(any())).thenReturn(Duration.ZERO);
    StrategyConfig c = config();
    c.setForceCloseEodEt("15:30");
    c.setNoEntryWithinCloseMinutes(30L);
    WatchlistTriggerWorkflow wf = newStub("wl-eod-entry-guard");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false)); // seed below T
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE attempt

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":eod_skip");
    verify(exec, never()).placeOrder(any());
    AuditEvent rejected = captureKind("TriggerFireRejected");
    assertThat(rejected.getSubject()).containsEntry("reason", "too_close_to_eod");
    assertThat(rejected.getSubject()).containsEntry("cutoff_et", "15:30");
  }

  // Phase 1: cutoff configured but the calendar reports plenty of time before the close (toClose
  // minutes >= no_entry_within_close_minutes) -> the guard is inert and entry proceeds as today.
  @Test
  void crossWellBeforeEodCutoff_placesOrder() throws Exception {
    when(calendar.durationUntilEodCloseEt(any())).thenReturn(Duration.ofHours(2));
    StrategyConfig c = config();
    c.setForceCloseEodEt("15:30");
    c.setNoEntryWithinCloseMinutes(30L);
    WatchlistTriggerWorkflow wf = newStub("wl-eod-guard-clear");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
  }

  // Phase 1: no_entry_within_close_minutes absent -> guard disabled, behaviour unchanged even with
  // the calendar reporting zero time to close (the guard never consults the calendar when null).
  @Test
  void nullEntryCutoff_guardDisabled_placesOrder() throws Exception {
    lenient().when(calendar.durationUntilEodCloseEt(any())).thenReturn(Duration.ZERO);
    WatchlistTriggerWorkflow wf = newStub("wl-eod-guard-null");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
  }

  // Phase 3 (Defect A) happy path: once FillDispatcherImpl routes the entry fill to THIS `/wl/...`
  // leg (the exec-side fix), a fill arriving inside the TTL wakes `Workflow.await(ttl, () ->
  // fillEvent != null)` immediately — the leg emits EntryFilled(outcome=FILLED, no recovery),
  // starts the PositionWorkflow inline (OCC->wfId cache seeded), and NEVER reaches the timeout
  // branch: cancelOrder is not called and no TriggerEntryUnfilled is emitted. This is the
  // wake-on-fill behavior the broken routing denied in prod; the workflow-side predicate is the
  // bare match copytrade already uses (one order per leg execution -> a bare match is sufficient,
  // no broker_order_id correlation needed).
  @Test
  void fillWithinTtl_wakesAwait_startsPositionInline_noTimeoutBranch() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-fill-wakes");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15"))); // fill routed to THIS leg within the TTL

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
    // The await woke on the fill, so the timeout branch is never entered.
    verify(exec, never()).cancelOrder(any());

    // Happy-path EntryFilled (outcome=FILLED, NOT the cancel_on_filled recovery label).
    AuditEvent filled = captureKind("EntryFilled");
    assertThat(filled.getSubject()).containsEntry("outcome", "FILLED");
    assertThat(filled.getSubject()).doesNotContainKey("recovery");
    assertThat(((Number) filled.getSubject().get("filled_qty")).longValue()).isEqualTo(5L);

    // PositionWorkflow started inline -> OCC->wfId mapping seeded (no orphan waiting on recon).
    verify(positionLookup, times(1))
        .cachePositionMapping(eq("dev"), eq("watchlist-trigger-v1"), eq(OCC), anyString());

    // No orphan hand-off: the leg did not fall through to TriggerEntryUnfilled.
    ArgumentCaptor<AuditEvent> allAudits = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(allAudits.capture());
    assertThat(allAudits.getAllValues()).noneMatch(e -> "TriggerEntryUnfilled".equals(e.getKind()));
  }

  // Finding #2: order placed but no fill arrives within the TTL -> the leg cancels the resting
  // order, audits TriggerEntryUnfilled, and completes WITHOUT starting a PositionWorkflow.
  @Test
  void noFillWithinTtl_cancelsOrder_noPositionWorkflow() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(submittedResult());
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L); // short TTL so the await elapses quickly
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    // never send onFill; let the TTL elapse.

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
    verify(exec, times(1)).placeOrder(any());
    verify(exec, times(1)).cancelOrder(any());
    AuditEvent unfilled = captureKind("TriggerEntryUnfilled");
    assertThat(unfilled.getSubject()).containsEntry("outcome", "UNFILLED");
  }

  // Issue #165 port: the broker filled inside the TTL/cancel race. cancelOrder reconciles the
  // journal to FILLED and returns the broker-confirmed fill -> adopt the lot inline (synth fill,
  // EntryFilled(recovery=cancel_on_filled), spawn the PositionWorkflow). No TriggerEntryUnfilled.
  @Test
  void ttlExpired_cancelReturnsFilled_adoptsPosition() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    // never send onFill; let the TTL elapse.

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
    verify(exec, times(1)).cancelOrder(any());

    AuditEvent filled = captureKind("EntryFilled");
    assertThat(filled.getSubject()).containsEntry("recovery", "cancel_on_filled");
    assertThat(filled.getSubject()).containsEntry("option_symbol", OCC);
    // filled_qty round-trips through the audit JSON as a numeric; compare on long value.
    assertThat(((Number) filled.getSubject().get("filled_qty")).longValue()).isEqualTo(5L);
    AuditEvent posStart = positionStartInput(wf);
    assertThat(posStart).isNotNull();
  }

  // Partial fill: the broker filled fewer contracts than placed. The adopted PositionWorkflow must
  // carry the BROKER-confirmed filled qty, not the placed qty.
  @Test
  void ttlExpired_cancelReturnsFilled_partialQty_adoptsBrokerQtyNotPlaced() throws Exception {
    RecordingPositionWorkflowImpl.STARTED.clear();
    RecordingPositionWorkflowImpl.FILLS.clear();

    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      localEnv.registerSearchAttribute(
          "TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      localEnv.registerSearchAttribute(
          "ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      Worker core = localEnv.newWorker(CORE_QUEUE);
      core.registerWorkflowImplementationTypes(
          WatchlistTriggerWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
      core.registerActivitiesImplementations(
          audit, calendar, risk, contract, fireDecider, positionLookup);
      Worker md = localEnv.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      md.registerActivitiesImplementations(subscribeEquity, optionQuote);
      Worker broker = localEnv.newWorker(BROKER_QUEUE);
      broker.registerActivitiesImplementations(exec, accountSnapshot, tradingCalendar);
      localEnv.start();

      // Placed 50 (flat sizing clamp), broker filled only 3.
      when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(3L, new BigDecimal("3.20")));
      StrategyConfig c = config();
      c.setPendingTtlPaperSecs(1L);

      WatchlistTriggerWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WatchlistTriggerWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId("wl-no-fill")
                      .build());
      WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

      wf.equityTick(tick(new BigDecimal("760.80"), false));
      wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
      String result = WorkflowStub.fromTyped(wf).getResult(String.class);
      assertThat(result).endsWith(":fired");

      long deadline = System.currentTimeMillis() + 10_000;
      while (RecordingPositionWorkflowImpl.STARTED.isEmpty()
          && System.currentTimeMillis() < deadline) {
        sleep();
      }
      assertThat(RecordingPositionWorkflowImpl.STARTED).hasSize(1);
      PositionWorkflowInput child =
          RecordingPositionWorkflowImpl.STARTED.values().iterator().next();
      assertThat(child.getQty()).isEqualTo(3L); // broker filled qty, NOT the placed 50
      assertThat(child.getEntryPremium()).isEqualByComparingTo("3.20");
    } finally {
      localEnv.close();
    }
  }

  // FILLED state but the broker-confirmed filled qty is null/0 (no real fill) -> do NOT adopt; fall
  // through to the legacy TriggerEntryUnfilled path, no child.
  @Test
  void ttlExpired_cancelReturnsFilledZeroQty_staysUnfilled() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(null, null));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
    verify(exec, times(1)).cancelOrder(any());
    AuditEvent unfilled = captureKind("TriggerEntryUnfilled");
    assertThat(unfilled.getSubject()).containsEntry("outcome", "UNFILLED");
  }

  // KILL-SWITCH/HALT GUARD: the broker filled inside the race, but an ACCOUNT-scoped kill switch is
  // active (the auto:account_daily_loss incident). Do NOT inline-adopt; fall through to the legacy
  // unfilled path so recon's AdoptionWorkflow re-confirms broker truth safely.
  @Test
  void ttlExpired_killSwitchActive_skipsInlineAdoption() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
    when(risk.checkKillSwitchHalt(any(), any()))
        .thenReturn(RiskDecision.rejected(RejectionReason.KILL_SWITCH_TRIPPED, "scope=account"));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
    AuditEvent unfilled = captureKind("TriggerEntryUnfilled");
    assertThat(unfilled.getSubject()).containsEntry("outcome", "UNFILLED");
  }

  // Owner-running precheck: a PositionWorkflow for this OCC is already running (recon won the
  // race).
  // The adoption must no-op (no second child start, no collision) and complete deterministically.
  @Test
  void ttlExpired_ownerAlreadyRunning_noDoubleStart() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
    when(positionLookup.isPositionWorkflowRunning(any())).thenReturn(true);
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
  }

  // CANCELLED (the broker really cancelled, no fill) -> legacy TriggerEntryUnfilled, no child.
  @Test
  void ttlExpired_cancelReturnsCancelled_staysUnfilled() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelledResult());
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
    verify(exec, times(1)).cancelOrder(any());
    AuditEvent unfilled = captureKind("TriggerEntryUnfilled");
    assertThat(unfilled.getSubject()).containsEntry("outcome", "UNFILLED");
  }

  // cancelOrder throws -> the existing try/catch fails closed to the legacy unfilled path, no
  // child.
  @Test
  void ttlExpired_cancelThrows_staysUnfilled_failClosed() throws Exception {
    when(exec.cancelOrder(any())).thenThrow(new RuntimeException("broker down"));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
    AuditEvent unfilled = captureKind("TriggerEntryUnfilled");
    assertThat(unfilled.getSubject()).containsEntry("outcome", "UNFILLED");
  }

  // ---- Phase 1 (PLAN-2026-07-06): getOrderStatus defense-in-depth on the entry TTL branch ----

  // Incident reproduction (SPY 2026-07-06): the journal was terminalized FILLED (WS listener) at
  // 14:39:55.992, but cancelOrder returned a NON-FILLED state so the cancel-on-filled inline check
  // didn't surface it -> the workflow logged TriggerEntryUnfilled at 14:39:56.205 and the lot
  // orphaned until recon adopted it ~5s later. The new getOrderStatus re-check re-reads broker
  // truth (FILLED qty 5) and adopts the lot inline: EntryFilled(recovery=getorderstatus_reconcile),
  // child PositionWorkflow started, and NO TriggerEntryUnfilled / no orphan-to-recon.
  @Test
  void ttlExpired_cancelNonFilled_getOrderStatusFilled_adoptsPosition() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelledResult());
    when(exec.getOrderStatus(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    // never send onFill; let the TTL elapse.

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).cancelOrder(any());
    verify(exec, times(1)).getOrderStatus(any());

    AuditEvent filled = captureKind("EntryFilled");
    assertThat(filled.getSubject()).containsEntry("recovery", "getorderstatus_reconcile");
    assertThat(filled.getSubject()).containsEntry("option_symbol", OCC);
    assertThat(((Number) filled.getSubject().get("filled_qty")).longValue()).isEqualTo(5L);
    assertNoAuditKind("TriggerEntryUnfilled");
  }

  // cancelOrder throws (cancelResult == null, the existing try/catch fails closed) but the fill is
  // observable in the journal -> getOrderStatus returns FILLED -> adopt inline, no orphan.
  @Test
  void ttlExpired_cancelThrows_getOrderStatusFilled_adoptsPosition() throws Exception {
    when(exec.cancelOrder(any())).thenThrow(new RuntimeException("broker down"));
    when(exec.getOrderStatus(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).getOrderStatus(any());
    AuditEvent filled = captureKind("EntryFilled");
    assertThat(filled.getSubject()).containsEntry("recovery", "getorderstatus_reconcile");
    assertNoAuditKind("TriggerEntryUnfilled");
  }

  // Legacy preserved: both cancelOrder AND getOrderStatus report non-FILLED (the order really did
  // not fill) -> fall through to the legacy TriggerEntryUnfilled path, no child.
  @Test
  void ttlExpired_cancelAndGetOrderStatusNonFilled_staysUnfilled() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelledResult());
    when(exec.getOrderStatus(any())).thenReturn(cancelledResult());
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-no-fill");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":entry_unfilled");
    verify(exec, times(1)).getOrderStatus(any());
    AuditEvent unfilled = captureKind("TriggerEntryUnfilled");
    assertThat(unfilled.getSubject()).containsEntry("outcome", "UNFILLED");
  }

  // Partial fill via getOrderStatus: the broker filled fewer contracts (3) than placed. For an
  // ENTRY we already HOLD the filled contracts at the broker, so adopting the FILLED qty (not
  // deferring) is the safe choice — the adopted PositionWorkflow must carry the broker-confirmed
  // filled qty (3), mirroring how handleTtlFilledAdoption sizes from getFilledQty().
  @Test
  void ttlExpired_getOrderStatusPartialFill_adoptsBrokerFilledQty() throws Exception {
    RecordingPositionWorkflowImpl.STARTED.clear();
    RecordingPositionWorkflowImpl.FILLS.clear();

    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      localEnv.registerSearchAttribute(
          "TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      localEnv.registerSearchAttribute(
          "ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      Worker core = localEnv.newWorker(CORE_QUEUE);
      core.registerWorkflowImplementationTypes(
          WatchlistTriggerWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
      core.registerActivitiesImplementations(
          audit, calendar, risk, contract, fireDecider, positionLookup);
      Worker md = localEnv.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      md.registerActivitiesImplementations(subscribeEquity, optionQuote);
      Worker broker = localEnv.newWorker(BROKER_QUEUE);
      broker.registerActivitiesImplementations(exec, accountSnapshot, tradingCalendar);
      localEnv.start();

      // cancelOrder non-FILLED; getOrderStatus reports a PARTIAL fill of 3 (placed 50 flat clamp).
      when(exec.cancelOrder(any())).thenReturn(cancelledResult());
      when(exec.getOrderStatus(any())).thenReturn(cancelFilledResult(3L, new BigDecimal("3.20")));
      StrategyConfig c = config();
      c.setPendingTtlPaperSecs(1L);

      WatchlistTriggerWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WatchlistTriggerWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId("wl-no-fill")
                      .build());
      WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

      wf.equityTick(tick(new BigDecimal("760.80"), false));
      wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
      String result = WorkflowStub.fromTyped(wf).getResult(String.class);
      assertThat(result).endsWith(":fired");

      long deadline = System.currentTimeMillis() + 10_000;
      while (RecordingPositionWorkflowImpl.STARTED.isEmpty()
          && System.currentTimeMillis() < deadline) {
        sleep();
      }
      assertThat(RecordingPositionWorkflowImpl.STARTED).hasSize(1);
      PositionWorkflowInput child =
          RecordingPositionWorkflowImpl.STARTED.values().iterator().next();
      assertThat(child.getQty()).isEqualTo(3L); // broker-filled qty, NOT the placed 50
      assertThat(child.getEntryPremium()).isEqualByComparingTo("3.20");
    } finally {
      localEnv.close();
    }
  }

  // Version-gate stability: the getOrderStatus-reconcile change id is a load-bearing constant. A
  // pre-fix history replays on DEFAULT_VERSION and must NOT call getOrderStatus / adopt inline; the
  // getVersion marker is read unconditionally at branch entry so old histories take the legacy
  // path.
  // TestWorkflowEnvironment always returns maxSupported (1) for a fresh execution, so the
  // DEFAULT_VERSION replay path is not exercisable here without a recorded history fixture; pinning
  // the literal (mirroring armedCacheVersionIdIsStable) guards against a silent rename that would
  // break in-flight-leg determinism.
  @Test
  void entryGetOrderStatusReconcileVersionIdIsStable() {
    assertThat(WatchlistTriggerWorkflowImpl.VERSION_ENTRY_GETORDERSTATUS_RECONCILE)
        .isEqualTo("watchlist-entry-getorderstatus-reconcile-v1");
  }

  // Recon false-orphan fix: a normal watchlist fire must seed the OCC -> PositionWorkflow-id
  // mapping
  // in the position cache (exactly as Copytrade/Adoption do), so recon's owner-lookup hits Redis
  // before the lagged Visibility index. The seed args must be (tenant, strategy, OCC, posWfId)
  // where
  // posWfId is the same id the child was started under.
  @Test
  void fire_normalFill_seedsPositionCache() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-cache-seed");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");

    String posWfId = WorkflowIds.position("dev", "watchlist-trigger-v1", OCC, "msg-1");
    verify(positionLookup, times(1))
        .cachePositionMapping("dev", "watchlist-trigger-v1", OCC, posWfId);
  }

  // Inline #472 cancel-on-filled adoption that starts the child must seed the position cache with
  // the same (tenant, strategy, OCC, posWfId) args, so the inline-adopted lot is also
  // recon-visible.
  @Test
  void inlineAdoption_startsChild_seedsPositionCache() throws Exception {
    when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
    StrategyConfig c = config();
    c.setPendingTtlPaperSecs(1L);
    WatchlistTriggerWorkflow wf = newStub("wl-adopt-cache");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    // never send onFill; let the TTL elapse so cancelOrder -> FILLED triggers inline adoption.

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");

    String posWfId = WorkflowIds.position("dev", "watchlist-trigger-v1", OCC, "msg-1");
    verify(positionLookup, times(1))
        .cachePositionMapping("dev", "watchlist-trigger-v1", OCC, posWfId);
  }

  // Inline adoption that LOSES the child-start race to recon (a child already running under
  // posWfId)
  // must NOT seed the cache: the cache must never point at a workflow this run did not start. The
  // owner-running precheck returns false (so adoption proceeds to startPositionWorkflow), and the
  // real child start then collides with a pre-started workflow under the same id -> startFailed.
  @Test
  void inlineAdoption_lostStartRace_doesNotSeedCache() throws Exception {
    RecordingPositionWorkflowImpl.STARTED.clear();
    RecordingPositionWorkflowImpl.FILLS.clear();

    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      localEnv.registerSearchAttribute(
          "TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      localEnv.registerSearchAttribute(
          "ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      Worker core = localEnv.newWorker(CORE_QUEUE);
      core.registerWorkflowImplementationTypes(
          WatchlistTriggerWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
      core.registerActivitiesImplementations(
          audit, calendar, risk, contract, fireDecider, positionLookup);
      Worker md = localEnv.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      md.registerActivitiesImplementations(subscribeEquity, optionQuote);
      Worker broker = localEnv.newWorker(BROKER_QUEUE);
      broker.registerActivitiesImplementations(exec, accountSnapshot, tradingCalendar);
      localEnv.start();

      // Recon "won the race": a child PositionWorkflow is already running under posWfId, so the
      // adoption's child-start collides (WorkflowExecutionAlreadyStarted) -> startFailed -> no
      // seed.
      // isPositionWorkflowRunning stays false (default) so adoption reaches startPositionWorkflow.
      String posWfId = WorkflowIds.position("dev", "watchlist-trigger-v1", OCC, "msg-1");
      PositionWorkflow squatter =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  PositionWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId(posWfId)
                      .build());
      WorkflowStub.fromTyped(squatter).start(parkInput());

      when(exec.cancelOrder(any())).thenReturn(cancelFilledResult(5L, new BigDecimal("3.20")));
      StrategyConfig c = config();
      c.setPendingTtlPaperSecs(1L);
      WatchlistTriggerWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WatchlistTriggerWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId("wl-adopt-lostrace")
                      .build());
      WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

      wf.equityTick(tick(new BigDecimal("760.80"), false));
      wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
      String result = WorkflowStub.fromTyped(wf).getResult(String.class);
      assertThat(result).endsWith(":fired");

      // The pre-started squatter is the only run that "started" posWfId; the adoption lost the race
      // and must not have cached the mapping.
      verify(positionLookup, never()).cachePositionMapping(any(), any(), any(), any());
    } finally {
      localEnv.close();
    }
  }

  // Parked-child start input for the lost-race squatter.
  private static PositionWorkflowInput parkInput() {
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("watchlist-trigger-v1");
    in.setEntrySignalId("msg-1");
    in.setContractSymbol(OCC);
    in.setQty(5L);
    in.setEntryPremium(new BigDecimal("3.20"));
    in.setBrokerTarget(PositionWorkflowInput.BrokerTarget.ALPACA_PAPER);
    return in;
  }

  // Returns the EntryFilled audit (recovery=cancel_on_filled) if one was emitted, else null.
  private AuditEvent positionStartInput(WatchlistTriggerWorkflow wf) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> "EntryFilled".equals(e.getKind()))
        .filter(e -> "cancel_on_filled".equals(e.getSubject().get("recovery")))
        .reduce((a, b) -> b)
        .orElse(null);
  }

  // Armed-watchlist Redis seed: arming a leg must SADD its workflow id into the armed-watchlist set
  // exactly once, keyed on (tenant, strategy, et_date, workflowId), so the BFF enumerates the armed
  // legs from Redis instead of a lagging listExecutions visibility query.
  @Test
  void arm_seedsArmedLegCache() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-armed-seed");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    // Seed a sub-trigger tick + cancel so the leg arms then completes deterministically (the seed
    // happens on the first run regardless of the eventual fire/cancel outcome).
    wf.equityTick(tick(new BigDecimal("760.50"), false));
    wf.cancel();
    WorkflowStub.fromTyped(wf).getResult(String.class);

    verify(positionLookup, times(1))
        .cacheArmedLeg("dev", "watchlist-trigger-v1", LocalDate.of(2026, 6, 24), "wl-armed-seed");
  }

  // Best-effort guarantee: a Redis failure must NOT fail or stall arming. The activity impl
  // swallows
  // Redis errors (PositionLookupActivitiesImplTest.cacheArmedLeg_redisThrows_swallowed), so the
  // activity returns normally on a Redis outage. This test pins the matching workflow-side
  // property:
  // arming + the fire path complete normally when the seed is a no-op (the real impl's behaviour on
  // a Redis failure). The seed runs on a dedicated short-timeout, single-attempt stub
  // (ARMED_CACHE_OPTIONS) so even a hung Redis cannot stall the live arm past 5s.
  @Test
  void arm_cacheSeedNoOp_doesNotFailArming() throws Exception {
    // positionLookup.cacheArmedLeg is unstubbed -> returns normally (mirrors the impl swallowing a
    // Redis error and returning).
    WatchlistTriggerWorkflow wf = newStub("wl-armed-noop");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
    verify(positionLookup, times(1)).cacheArmedLeg(any(), any(), any(), any());
  }

  // Best-effort guarantee, throw/timeout path: a Redis HANG can't reach the impl's own swallow — it
  // blocks until the 5s StartToClose fires server-side, raising an ActivityFailure (a
  // RuntimeException) in the workflow. The seed call MUST catch it so the arm continues. Stubbing
  // cacheArmedLeg to THROW reproduces that surfaced failure; arming + the fire path must still
  // complete normally (:fired, NOT a workflow failure). Distinct from the no-op (swallow-success)
  // test above: this exercises the throw the bare call mishandled.
  @Test
  void arm_cacheSeedThrows_doesNotFailArming() throws Exception {
    Mockito.doThrow(new RuntimeException("redis hung -> activity timeout"))
        .when(positionLookup)
        .cacheArmedLeg(any(), any(), any(), any());
    WatchlistTriggerWorkflow wf = newStub("wl-armed-throw");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
    verify(positionLookup, atLeastOnce()).cacheArmedLeg(any(), any(), any(), any());
  }

  // Version-gate stability: the armed-cache change id is a load-bearing constant (a pre-fix history
  // replays on DEFAULT_VERSION and must NOT seed). Pin the literal so a rename can't silently break
  // determinism for in-flight legs.
  @Test
  void armedCacheVersionIdIsStable() {
    assertThat(WatchlistTriggerWorkflowImpl.VERSION_ARMED_CACHE)
        .isEqualTo("watchlist-armed-cache-v1");
  }

  // (g) proceed:false -> no order.
  @Test
  void fireDeciderRejects_noOrder() throws Exception {
    when(fireDecider.evaluateTriggerFire(any(), any()))
        .thenReturn(
            new FireDecision()
                .withProceed(false)
                .withSizeMultiplier(BigDecimal.ZERO)
                .withReason("blocked"));
    WatchlistTriggerWorkflow wf = newStub("wl-fire-rejected");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fire_rejected");
    verify(exec, never()).placeOrder(any());
  }

  // (h) sizeMultiplier below-min -> skip (no min-size trade).
  @Test
  void armMultiplierBelowMin_skipsNoOrder() throws Exception {
    // arm mult so small that raw rounds below min_contracts(1) -> Sizing returns skip.
    WatchlistTriggerWorkflow wf = newStub("wl-below-min");
    WorkflowStub.fromTyped(wf)
        .start(input(breakoutAbovePayload(), config(), new BigDecimal("0.00001")));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":sizing_skip");
    verify(exec, never()).placeOrder(any());
  }

  // (i) zero cash -> fail-closed.
  @Test
  void zeroCash_failsClosed() throws Exception {
    when(accountSnapshot.accountSnapshot(any())).thenReturn(cash(BigDecimal.ZERO));
    WatchlistTriggerWorkflow wf = newStub("wl-zero-cash");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":capital_unavailable");
    verify(exec, never()).placeOrder(any());
  }

  // (i) no option quote -> fail-closed.
  @Test
  void noOptionQuote_failsClosed() throws Exception {
    OptionQuoteResult unavailable = new OptionQuoteResult();
    unavailable.setSchemaVersion(1L);
    unavailable.setStatus(OptionQuoteResult.Status.UNAVAILABLE);
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailable);
    WatchlistTriggerWorkflow wf = newStub("wl-no-quote");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":no_option_quote");
    verify(exec, never()).placeOrder(any());
  }

  // risk gate rejects -> fail-closed, no order.
  @Test
  void riskGateRejects_noOrder() throws Exception {
    when(risk.checkWatchlistEntry(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "too many"));
    WatchlistTriggerWorkflow wf = newStub("wl-risk-reject");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":risk_rejected");
    verify(exec, never()).placeOrder(any());
  }

  // (j) exactly one placeOrder per child even with extra post-fire ticks.
  @Test
  void extraTicksAfterFire_stillExactlyOneOrder() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-once");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
    waitForPlaceOrderCount(1);
    // Bombard with more in-band ticks while the fire path awaits the fill.
    wf.equityTick(tick(new BigDecimal("762.00"), false));
    wf.equityTick(tick(new BigDecimal("763.00"), false));
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    WorkflowStub.fromTyped(wf).getResult(String.class);
    verify(exec, times(1)).placeOrder(any());
  }

  // (k) stale tick ignored: a stale print at the cross does NOT fire; a later live cross does.
  @Test
  void staleTick_ignored_thenLiveCrossFires() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-stale");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.80"), false)); // seed
    wf.equityTick(tick(new BigDecimal("761.40"), true)); // STALE in-band -> ignored, no transition
    waitNoPlaceOrder();
    wf.equityTick(tick(new BigDecimal("761.50"), false)); // live cross -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
    AuditEvent stale = captureKind("TriggerFeedStale");
    assertThat(stale.getSubject()).containsKey("ticker");
  }

  // The fire path hands off to a PositionWorkflow with the weekly OCC.
  @Test
  void fire_startsPositionWorkflowWithFilledQty() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-handoff");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));
    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));
    waitForPlaceOrderCount(1);
    wf.onFill(fill(4L, new BigDecimal("3.10")));

    WorkflowStub.fromTyped(wf).getResult(String.class);
    AuditEvent filled = captureKind("EntryFilled");
    assertThat(filled.getSubject()).containsEntry("option_symbol", OCC);
  }

  // The fire decider receives an ArmContext carrying the et_date.
  @Test
  void fireDecider_receivesArmContextWithEtDate() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-armctx");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));
    wf.equityTick(tick(new BigDecimal("760.80"), false));
    wf.equityTick(tick(new BigDecimal("761.40"), false));
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));
    WorkflowStub.fromTyped(wf).getResult(String.class);

    ArgumentCaptor<ArmContext> captor = ArgumentCaptor.forClass(ArmContext.class);
    verify(fireDecider, atLeastOnce()).evaluateTriggerFire(any(), captor.capture());
    assertThat(captor.getValue().getEtDate()).isEqualTo(LocalDate.of(2026, 6, 24));
  }

  // Phase 5: the fire->PositionWorkflow handoff must forward the Phase-3 premium exit params so the
  // watchlist position arms TP/SL/trail. Drives the fire path against a recording child and asserts
  // the captured PositionWorkflowInput carries the exit fields sourced from StrategyConfig.
  @Test
  void fire_handsOffExitParamsToPositionWorkflow() throws Exception {
    RecordingPositionWorkflowImpl.STARTED.clear();
    RecordingPositionWorkflowImpl.FILLS.clear();

    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      localEnv.registerSearchAttribute(
          "TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      localEnv.registerSearchAttribute(
          "ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);

      Worker core = localEnv.newWorker(CORE_QUEUE);
      // Recording fake child (not the real PositionWorkflowImpl) so the handoff input is captured
      // at start without running the full position lifecycle.
      core.registerWorkflowImplementationTypes(
          WatchlistTriggerWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
      core.registerActivitiesImplementations(
          audit, calendar, risk, contract, fireDecider, positionLookup);
      Worker md = localEnv.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      md.registerActivitiesImplementations(subscribeEquity, optionQuote);
      Worker broker = localEnv.newWorker(BROKER_QUEUE);
      broker.registerActivitiesImplementations(exec, accountSnapshot, tradingCalendar);
      localEnv.start();

      StrategyConfig c = config();
      c.setTpRatio(new BigDecimal("2.0"));
      c.setSlPct(new BigDecimal("0.35"));
      c.setTpPartialFraction(new BigDecimal("0.5"));
      c.setTrailGivebackPct(new BigDecimal("0.25"));
      c.setNoProgressTimeStopSecs(900L);
      c.setForceCloseEodEt("15:50");

      WatchlistTriggerWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WatchlistTriggerWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId("wl-exit-handoff")
                      .build());
      WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), c));

      wf.equityTick(tick(new BigDecimal("760.80"), false));
      wf.equityTick(tick(new BigDecimal("761.40"), false)); // FIRE
      wf.onFill(fill(5L, new BigDecimal("3.15")));

      WorkflowStub.fromTyped(wf).getResult(String.class);

      long deadline = System.currentTimeMillis() + 10_000;
      while (RecordingPositionWorkflowImpl.STARTED.isEmpty()
          && System.currentTimeMillis() < deadline) {
        sleep();
      }
      assertThat(RecordingPositionWorkflowImpl.STARTED).hasSize(1);
      PositionWorkflowInput child =
          RecordingPositionWorkflowImpl.STARTED.values().iterator().next();
      assertThat(child.getTpRatio()).isEqualByComparingTo("2.0");
      assertThat(child.getSlPct()).isEqualByComparingTo("0.35");
      assertThat(child.getTpPartialFraction()).isEqualByComparingTo("0.5");
      assertThat(child.getTrailGivebackPct()).isEqualByComparingTo("0.25");
      assertThat(child.getNoProgressTimeStopSecs()).isEqualTo(900L);
      assertThat(child.getForceCloseEodEt()).isEqualTo("15:50");
    } finally {
      localEnv.close();
    }
  }

  /** Light PositionWorkflow double: records the start input and parks until terminated. */
  public static final class RecordingPositionWorkflowImpl implements PositionWorkflow {
    static final Map<String, PositionWorkflowInput> STARTED = new ConcurrentHashMap<>();
    static final Map<String, FillSignalPayload> FILLS = new ConcurrentHashMap<>();

    @Override
    public String run(PositionWorkflowInput input) {
      STARTED.put(Workflow.getInfo().getWorkflowId(), input);
      Workflow.await(() -> FILLS.containsKey(Workflow.getInfo().getWorkflowId()));
      return input.getEntrySignalId();
    }

    @Override
    public void partialExit(PartialExitRequest req) {}

    @Override
    public void onFill(FillSignalPayload event) {
      FILLS.put(Workflow.getInfo().getWorkflowId(), event);
    }

    @Override
    public void armChandelier(ArmChandelierPayload payload) {}

    @Override
    public void chandelierTick(PremiumTick tick) {}

    @Override
    public void riskBreach(RiskBreachPayload payload) {}

    @Override
    public void supersede(String correctedSignalId, String correctedOcc) {}

    @Override
    public TrailingState trailingState() {
      return null;
    }

    @Override
    public PositionState positionState() {
      return null;
    }

    @Override
    public ExitProximityView exitProximity() {
      return null;
    }

    @Override
    public void forceCloseValidator(ForceCloseRequest request) {}

    @Override
    public ForceCloseResult forceClose(ForceCloseRequest request) {
      return null;
    }

    @Override
    public void partialCloseValidator(PartialCloseRequest request) {}

    @Override
    public PartialCloseResult partialClose(PartialCloseRequest request) {
      return null;
    }

    // PLAN-2026-08-16 arm_trail: test double only — never invoked by these suites. Returning null
    // is the same convention the sibling Updates above use.
    @Override
    public void armTrailValidator(ArmTrailRequest request) {}

    @Override
    public ArmTrailResult armTrail(ArmTrailRequest request) {
      return null;
    }
  }

  // Pre-open arm: the first equity subscribe (before 09:30 ET) is GATED; the leg must NOT give up.
  // It defers and re-subscribes at the RTH open, then a live cross still fires. Regression for the
  // "armed 6 min pre-open -> feedless + unfireable all day" incident.
  @Test
  void armedPreOpen_gatedThenResubscribesAtRthOpen_stillFires() throws Exception {
    when(subscribeEquity.subscribeEquity(any()))
        .thenReturn(subscribeResult(SubscribeEquityResult.Status.GATED))
        .thenReturn(subscribeResult(SubscribeEquityResult.Status.SUBSCRIBED));
    when(calendar.durationUntilRthOpenEt()).thenReturn(Duration.ofMinutes(6));

    WatchlistTriggerWorkflow wf = newStub("wl-preopen-defer");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    // Advance past the 6-min defer so the RTH-open re-subscribe fires; EOD (8h default) is far off.
    env.sleep(Duration.ofMinutes(7));

    wf.equityTick(tick(new BigDecimal("760.80"), false)); // seed below T
    wf.equityTick(tick(new BigDecimal("761.40"), false)); // live cross into band -> FIRE
    waitForPlaceOrderCount(1);
    wf.onFill(fill(5L, new BigDecimal("3.15")));

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":fired");
    verify(exec, times(1)).placeOrder(any());
    // Subscribed twice: the initial GATED attempt + the deferred RTH-open retry.
    verify(subscribeEquity, times(2)).subscribeEquity(any());
    AuditEvent deferred = captureKind("TriggerSubscriptionDeferred");
    assertThat(deferred.getSubject()).containsEntry("ticker", "NVDA");
  }

  // Dashboard entry-proximity query: a live un-fired leg exposes its trigger, band, machine state,
  // and most-recent underlying price for the /live view.
  @Test
  void entryProximity_reportsTriggerBandStateAndLastPrice() throws Exception {
    WatchlistTriggerWorkflow wf = newStub("wl-entry-proximity");
    WorkflowStub.fromTyped(wf).start(input(breakoutAbovePayload(), config()));

    wf.equityTick(tick(new BigDecimal("760.50"), false)); // seed prev; no cross -> stays ARMED

    EntryProximityView view = awaitEntryProximity(wf);
    assertThat(view.ticker()).isEqualTo("NVDA");
    assertThat(view.direction()).isEqualTo("ABOVE");
    assertThat(view.triggerLevel()).isEqualByComparingTo("761.00");
    assertThat(view.bandLow()).isEqualByComparingTo("757.195"); // 761*(1-0.005)
    assertThat(view.bandHigh()).isEqualByComparingTo("764.805"); // 761*(1+0.005)
    assertThat(view.lastPrice()).isEqualByComparingTo("760.50");
    assertThat(view.state()).isEqualTo("ARMED");
    // OCC resolved at arm (same resolution fire() uses), for the dashboard's indicative premium.
    assertThat(view.optionSymbol()).isEqualTo(OCC);

    wf.cancel();
    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).endsWith(":cancelled");
  }

  private EntryProximityView awaitEntryProximity(WatchlistTriggerWorkflow wf) {
    long deadline = System.currentTimeMillis() + 10_000;
    EntryProximityView view = wf.entryProximity();
    while (view.lastPrice() == null && System.currentTimeMillis() < deadline) {
      sleep();
      view = wf.entryProximity();
    }
    return view;
  }

  // ---------- helpers ----------

  private WatchlistTriggerWorkflow newStub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            WatchlistTriggerWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .build());
  }

  private static WatchlistTriggerWorkflowInput input(
      WatchlistTriggerPayload payload, StrategyConfig config) {
    return input(payload, config, BigDecimal.ONE);
  }

  private static WatchlistTriggerWorkflowInput input(
      WatchlistTriggerPayload payload, StrategyConfig config, BigDecimal armMult) {
    return new WatchlistTriggerWorkflowInput(payload, config, armMult);
  }

  private static WatchlistTriggerPayload breakoutAbovePayload() {
    return basePayload(WatchlistTriggerPayload.Direction.ABOVE);
  }

  private static WatchlistTriggerPayload breakoutBelowPayload() {
    return basePayload(WatchlistTriggerPayload.Direction.BELOW);
  }

  private static WatchlistTriggerPayload basePayload(WatchlistTriggerPayload.Direction dir) {
    WatchlistTriggerPayload p = new WatchlistTriggerPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("watchlist-trigger-v1");
    p.setTicker("NVDA");
    p.setDirection(dir);
    p.setTrigger(new BigDecimal("761.00"));
    p.setStrike(new BigDecimal("762"));
    p.setRight(WatchlistTriggerPayload.Right.C);
    p.setAction(WatchlistTriggerPayload.Action.BTO);
    p.setEtDate(LocalDate.of(2026, 6, 24)); // a Wednesday -> Friday 6/26 weekly
    p.setSourceMessageId("msg-1");
    return p;
  }

  private static StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("watchlist-trigger-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    c.setMinContracts(1L);
    c.setMaxContracts(50L);
    c.setEntryMode(StrategyConfig.EntryMode.BREAKOUT);
    c.setGapTolerancePct(new BigDecimal("0.005"));
    c.setEquityEmitDeltaPct(new BigDecimal("0.0005"));
    return c;
  }

  private static StrategyConfig retestConfig() {
    StrategyConfig c = config();
    c.setEntryMode(StrategyConfig.EntryMode.RETEST);
    return c;
  }

  private static EquityTick tick(BigDecimal last, boolean stale) {
    EquityTick t = new EquityTick();
    t.setSchemaVersion(1L);
    t.setTicker("NVDA");
    t.setLast(last);
    t.setRetrievedAt(OffsetDateTime.now());
    t.setStale(stale);
    return t;
  }

  private static FillSignalPayload fill(long qty, BigDecimal avg) {
    return new FillSignalPayload()
        .withBrokerOrderId("brk-1")
        .withFilledQty(qty)
        .withAvgFillPrice(avg)
        .withFilledAt(OffsetDateTime.now());
  }

  private static SubscribeEquityResult subscribeResult(SubscribeEquityResult.Status status) {
    SubscribeEquityResult r = new SubscribeEquityResult();
    r.setSchemaVersion(1L);
    r.setSubscriptionId(status == SubscribeEquityResult.Status.SUBSCRIBED ? "sub-1" : "");
    r.setSubscribedAt(OffsetDateTime.now());
    r.setStatus(status);
    return r;
  }

  private static OptionQuoteResult quoteOk(BigDecimal mid) {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol(OCC);
    r.setBid(mid.subtract(new BigDecimal("0.05")));
    r.setMid(mid);
    r.setAsk(mid.add(new BigDecimal("0.05")));
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.OK);
    return r;
  }

  private static AccountSnapshotResult cash(BigDecimal amount) {
    AccountSnapshotResult r = new AccountSnapshotResult();
    r.setSchemaVersion(1L);
    r.setCash(amount);
    r.setEquity(amount);
    return r;
  }

  private static OrderIntentResult submittedResult() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("wl:entry");
    r.setBrokerOrderId("brk-1");
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  // cancelOrder result where the broker had already filled inside the TTL/cancel race: the exec
  // sidecar reconciles the journal to FILLED and returns the broker-confirmed fill detail.
  private static OrderIntentResult cancelFilledResult(Long filledQty, BigDecimal avgFillPrice) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("wl-no-fill:entry");
    r.setBrokerOrderId("brk-1");
    r.setState(OrderIntentResult.State.FILLED);
    r.setFilledQty(filledQty);
    r.setAvgFillPrice(avgFillPrice);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private static OrderIntentResult cancelledResult() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("wl-no-fill:entry");
    r.setBrokerOrderId("brk-1");
    r.setState(OrderIntentResult.State.CANCELLED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private static List<LocalDate> weekdayCalendar(LocalDate start, int days) {
    List<LocalDate> out = new ArrayList<>();
    for (int i = 0; i < days; i++) {
      LocalDate d = start.plusDays(i);
      switch (d.getDayOfWeek()) {
        case SATURDAY, SUNDAY -> {}
        default -> out.add(d);
      }
    }
    return out;
  }

  private void waitForPlaceOrderCount(int n) {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, times(n)).placeOrder(any());
        return;
      } catch (AssertionError ignored) {
        sleep();
      }
    }
    verify(exec, times(n)).placeOrder(any());
  }

  private void waitNoPlaceOrder() {
    // brief settle so a buggy fire would have placed by now.
    long deadline = System.currentTimeMillis() + 1_000;
    while (System.currentTimeMillis() < deadline) {
      sleep();
    }
    verify(exec, never()).placeOrder(any());
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  private void assertNoAuditKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    assertThat(captor.getAllValues().stream().anyMatch(e -> kind.equals(e.getKind())))
        .as("no audit event with kind=" + kind)
        .isFalse();
  }
}
