package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Phase 3 watchlist-trigger options EXIT strategy (premium-space, on the long option). The exit is
 * enabled ONLY when {@code tp_ratio != null}; with it null the workflow must behave exactly like
 * the copytrade path (covered by {@code copytradePath_tpRatioNull_noNewExitCommands}). All other
 * tests here set {@code tp_ratio} to arm the bid-based stop / target / chandelier / time-stop
 * machinery.
 */
class PositionWorkflowImplWatchlistExitTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String SYMBOL = "NVDA  260516C00140000";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private ExecActivities exec;
  private MarketCalendarActivities calendar;
  private SubscribePremiumActivity marketData;
  private GetOptionQuoteActivity optionQuote;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    exec = Mockito.mock(ExecActivities.class);
    marketData = Mockito.mock(SubscribePremiumActivity.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);

    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilEodCloseEt(any())).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ZERO);
    when(marketData.subscribePremium(any())).thenReturn(subscribedResult());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("2.50"), new BigDecimal("2.55"), new BigDecimal("2.60")));

    coreWorker.registerActivitiesImplementations(audit, calendar);
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec);
    Worker mdWorker = env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(marketData, optionQuote);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  // ---------- (a) bid crosses -1R stop ----------

  /**
   * (a) entry_premium=2.00, sl_pct=0.25 -> R=0.50, stop level = 2.00*(1-0.25)=1.50. A bid at/below
   * 1.50 (after the debounce streak) flattens the WHOLE remaining position MARKETABLE
   * (limitPrice=null) with reason stop_loss.
   */
  @Test
  void bidCrossesStop_flattensWholeRemainingMarketable_reasonStopLoss() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-stop");
    PositionWorkflowInput in = exitInput(5);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // Two consecutive sub-threshold bids satisfy the debounce-2 streak.
    stub.chandelierTick(bidTick(new BigDecimal("1.45")));
    stub.chandelierTick(bidTick(new BigDecimal("1.40")));

    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stop", 5L, new BigDecimal("1.45")));
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-stop");

    AuditEvent req = captureKind("EodForceFlattenRequested");
    assertThat(req.getSubject()).containsEntry("reason", "stop_loss");
    assertThat(asLong(req.getSubject().get("remaining_qty"))).isEqualTo(5L);

    OrderIntent flatten = lastSell();
    assertThat(flatten.getLimitPrice()).as("stop_loss routes MARKET").isNull();
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
  }

  /** Debounce: a SINGLE outlier sub-threshold bid does NOT fire the stop. */
  @Test
  void singleOutlierBid_doesNotFireStop_debounce() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-debounce");
    PositionWorkflowInput in = exitInput(5);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // One outlier print below 1.50, then a recovery print at/above threshold resets the streak.
    stub.chandelierTick(bidTick(new BigDecimal("1.40")));
    stub.chandelierTick(bidTick(new BigDecimal("1.90")));

    // Drain via a normal STC so the workflow terminates; the stop must NOT have fired.
    stub.partialExit(partialExitRequest("sig-drain", "pos-wl-debounce", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 5L, new BigDecimal("1.95")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    List<AuditEvent> flattenReqs = captureAll("EodForceFlattenRequested");
    assertThat(flattenReqs.stream().map(e -> e.getSubject().get("reason")))
        .doesNotContain("stop_loss");
  }

  // ---------- (b) bid crosses +2R target ----------

  /**
   * (b) entry_premium=2.00, sl_pct=0.25, tp_ratio=2 -> target = 2.00*(1+2*0.25)=3.00. A bid >= 3.00
   * partial-closes tp_partial_fraction (0.5 -> 3 of 5), moves the remainder stop to breakeven
   * (2.00), and arms the chandelier (peak=bid, giveback=trail_giveback_pct). The runner then trails
   * out a tail.
   */
  @Test
  void bidCrossesTarget_partialThenBreakevenStopAndChandelierArmed_thenTrailsOut()
      throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-target");
    PositionWorkflowInput in = exitInput(5);
    in.setTrailGivebackPct(new BigDecimal("0.10"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // Bid hits the +2R target -> partial of 0.5 (ceil(2.5)=3) sold; chandelier armed peak=3.00.
    stub.chandelierTick(bidTick(new BigDecimal("3.00")));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-target-partial", 3L, new BigDecimal("3.00")));

    // Runner (2 left) trails: peak 3.00, giveback 0.10 -> threshold 2.70. A bid at 2.70 fires.
    stub.chandelierTick(bidTick(new BigDecimal("2.70")));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-trail", 2L, new BigDecimal("2.70")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-target");

    // Target partial fired exactly once.
    AuditEvent targetFired = captureKind("WatchlistExitTargetFired");
    assertThat(asLong(targetFired.getSubject().get("qty_to_close_intended"))).isEqualTo(3L);
    assertThat(((Number) targetFired.getSubject().get("breakeven")).doubleValue()).isEqualTo(2.00);

    // Chandelier armed on the runner with peak = the target bid.
    AuditEvent armed = captureKind("ChandelierArmed");
    assertThat(((Number) armed.getSubject().get("peak_premium")).doubleValue()).isEqualTo(3.00);

    // The runner trailed out via the chandelier.
    captureKind("ChandelierTrailFired");
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);

    // Two SELLs: the target partial (bounded LIMIT) + the chandelier trail (bounded LIMIT).
    verify(exec, times(2)).placeOrder(any());
  }

  /**
   * Regression: arming the runner trail on a target fire must NOT open a second premium
   * subscription. armWatchlistExit already subscribed the exit feed and processExitTick drives the
   * chandelier off that same feed; a second subscribe double-delivers every NBBO print and lets one
   * market print satisfy the post-target breakeven-stop debounce (EXIT_STOP_DEBOUNCE_TICKS). Assert
   * subscribePremium is invoked EXACTLY once across the whole position (only the initial arm).
   */
  @Test
  void targetFire_doesNotReSubscribePremium_subscribeOpenedExactlyOnce() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-no-resub");
    PositionWorkflowInput in = exitInput(5);
    in.setTrailGivebackPct(new BigDecimal("0.10"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // Bid hits the +2R target (3.00) -> partial sold and the runner trail is armed.
    stub.chandelierTick(bidTick(new BigDecimal("3.00")));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-target-partial", 3L, new BigDecimal("3.00")));

    // The trail fired the target arm; the subscription must have been opened only by the initial
    // armWatchlistExit, never again by the target fire.
    waitForKind("ChandelierArmed");
    verify(marketData, times(1)).subscribePremium(any());

    // Drain the runner via the chandelier so the workflow terminates cleanly.
    stub.chandelierTick(bidTick(new BigDecimal("2.70")));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-trail", 2L, new BigDecimal("2.70")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Still exactly one subscription after the full lifecycle.
    verify(marketData, times(1)).subscribePremium(any());
  }

  /**
   * (b') Regression: tp_ratio SET but tp_partial_fraction NULL must book HALF at the target (the
   * 0.5 default), NOT the whole position. With an even starting qty of 4, the +2R target closes 2
   * (half) and leaves a 2-contract runner whose chandelier trail is armed. With the old {@code
   * BigDecimal.ONE} fallback this would have closed all 4 and never armed the runner.
   */
  @Test
  void bidCrossesTarget_nullPartialFraction_booksHalfAndArmsRunnerTrail() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-null-fraction");
    PositionWorkflowInput in = exitInputNullFraction(4);
    in.setTrailGivebackPct(new BigDecimal("0.10"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L, new BigDecimal("2.00"));

    // Bid hits the +2R target (3.00). Null fraction -> 0.5 default -> ceil(4*0.5)=2 sold.
    stub.chandelierTick(bidTick(new BigDecimal("3.00")));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-target-half", 2L, new BigDecimal("3.00")));

    // The runner's chandelier trail was armed (it did NOT fully close at the target).
    waitForKind("ChandelierArmed");

    // Drain the 2-contract runner via the trail so the workflow terminates cleanly.
    stub.chandelierTick(bidTick(new BigDecimal("2.70")));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-runner", 2L, new BigDecimal("2.70")));
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-null-fraction");

    // The target booked HALF, not the whole lot: exactly 2 intended to close, fraction 0.5.
    AuditEvent targetFired = captureKind("WatchlistExitTargetFired");
    assertThat(asLong(targetFired.getSubject().get("qty_to_close_intended"))).isEqualTo(2L);
    assertThat(asLong(targetFired.getSubject().get("remaining_qty_before"))).isEqualTo(4L);
    assertThat(((Number) targetFired.getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);

    AuditEvent armed = captureKind("ChandelierArmed");
    assertThat(((Number) armed.getSubject().get("peak_premium")).doubleValue()).isEqualTo(3.00);

    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
  }

  // ---------- (c) target fires, runner trails back to breakeven -> scratch ----------

  /**
   * (c) after the target partial sets the remainder stop to breakeven (2.00) and arms the
   * chandelier, the runner trails back down: with a wide giveback the chandelier threshold sits at
   * or below breakeven, so the BREAKEVEN stop fires first at bid<=2.00, exiting the remainder ~at
   * scratch with reason stop_loss (breakeven).
   */
  @Test
  void targetThenRunnerTrailsBackToBreakeven_remainderExitsScratch() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-scratch");
    PositionWorkflowInput in = exitInput(5);
    in.setTrailGivebackPct(new BigDecimal("0.40")); // wide -> breakeven stop dominates
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    stub.chandelierTick(bidTick(new BigDecimal("3.00")));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-target-partial", 3L, new BigDecimal("3.00")));

    // Runner trails back to breakeven (2.00). The breakeven stop fires (debounce streak of 2).
    stub.chandelierTick(bidTick(new BigDecimal("2.00")));
    stub.chandelierTick(bidTick(new BigDecimal("1.98")));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-scratch", 2L, new BigDecimal("2.00")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-scratch");

    // The remainder exited at/near breakeven via the stop (reason stop_loss), MARKET.
    List<AuditEvent> flattenReqs = captureAll("EodForceFlattenRequested");
    assertThat(flattenReqs.stream().map(e -> e.getSubject().get("reason"))).contains("stop_loss");
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
  }

  // ---------- (d) no_progress_time_stop ----------

  /**
   * (d) neither stop nor target fires within no_progress_time_stop_secs of the first fill ->
   * flatten remaining MARKETABLE with reason time_stop.
   */
  @Test
  void noProgressTimeStop_elapses_flattensMarketable_reasonTimeStop() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-timestop");
    PositionWorkflowInput in = exitInput(5);
    in.setNoProgressTimeStopSecs(30L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // A benign mid-range bid keeps the position alive (neither stop nor target).
    stub.chandelierTick(bidTick(new BigDecimal("2.10")));

    // Advance past the 30s time-stop.
    env.sleep(Duration.ofSeconds(45));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-timestop", 5L, new BigDecimal("2.05")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-timestop");

    AuditEvent req = captureKind("EodForceFlattenRequested");
    assertThat(req.getSubject()).containsEntry("reason", "time_stop");
    OrderIntent flatten = lastSell();
    assertThat(flatten.getLimitPrice()).as("time_stop routes MARKET").isNull();
  }

  // ---------- (e) premium feed goes stale ----------

  /**
   * (e) the premium feed goes stale after arm (no tick within the staleness window) -> the workflow
   * must NOT silently hold blind; the time-based backstop flatten fires (here the time-stop). An
   * audit notes the staleness.
   */
  @Test
  void premiumFeedStaleAfterArm_failsafeFlattens_noSilentBlindHold() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-stale");
    PositionWorkflowInput in = exitInput(5);
    in.setNoProgressTimeStopSecs(30L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // No tick EVER arrives after arm -> feed is stale. Advance well past the staleness window and
    // the time-stop backstop.
    env.sleep(Duration.ofSeconds(60));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stale", 5L, new BigDecimal("2.00")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-stale");

    // Staleness was audited (no silent blind hold) AND a time-based flatten closed the lot.
    assertThat(captureAll("WatchlistExitFeedStale")).isNotEmpty();
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
  }

  // ---------- (f) force_close_eod_et ----------

  /**
   * (f) force_close_eod_et=15:30 on a >=2-DTE position: the EOD flatten arms at 15:30 (via the
   * durationUntilEodEt(LocalTime) overload), pre-empting an un-triggered bracket.
   */
  @Test
  void forceCloseEodEt_armsEodAt1530_preemptsUntriggeredBracket() throws Exception {
    when(calendar.durationUntilEodCloseEt(eq1530())).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-eod");
    PositionWorkflowInput in = exitInput(5);
    in.setForceCloseEodEt("15:30");
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-eod", 5L, new BigDecimal("2.10")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-eod");

    // The configured 15:30 was parsed and passed to the configurable EOD activity.
    verify(calendar).durationUntilEodCloseEt(LocalTime.of(15, 30));
    // The no-arg legacy EOD activity must NOT have driven the EOD timer.
    verify(calendar, never()).durationUntilEodEt();
    AuditEvent req = captureKind("EodForceFlattenRequested");
    assertThat(req.getSubject()).containsEntry("reason", "eod");
  }

  // ---------- (h) Phase 7 measurement: per-exit-leg WatchlistExitMeasured ----------

  /**
   * (h) a STOP exit emits a WatchlistExitMeasured leg audit. entry=2.00, sl_pct=0.30 -> R =
   * 0.30*2.00 = 0.60; stop fill 1.40 -> realized_R = (1.40-2.00)/0.60 = -1.0. The leg closes the
   * whole lot, so partial_fraction = 1.0 and exit_rule = stop_loss.
   */
  @Test
  void stopExit_emitsMeasuredLeg_realizedRMinusOne() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-measure-stop");
    PositionWorkflowInput in = exitInput(5);
    in.setSlPct(new BigDecimal("0.30")); // R = 0.30*2.00 = 0.60
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // stop level = 2.00*(1-0.30) = 1.40. Two bids at/below 1.40 satisfy debounce-2.
    stub.chandelierTick(bidTick(new BigDecimal("1.40")));
    stub.chandelierTick(bidTick(new BigDecimal("1.38")));

    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stop", 5L, new BigDecimal("1.40")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent measured = captureKind("WatchlistExitMeasured");
    assertThat(measured.getSubject()).containsEntry("exit_rule", "stop_loss");
    assertThat(((Number) measured.getSubject().get("entry_premium")).doubleValue()).isEqualTo(2.00);
    assertThat(((Number) measured.getSubject().get("exit_premium")).doubleValue()).isEqualTo(1.40);
    assertThat(((Number) measured.getSubject().get("realized_R")).doubleValue()).isEqualTo(-1.0);
    assertThat(((Number) measured.getSubject().get("partial_fraction")).doubleValue())
        .isEqualTo(1.0);
  }

  /**
   * (h) a TARGET+trail exit emits a WatchlistExitMeasured leg for BOTH the target partial AND the
   * terminal trail close. entry=2.00, sl_pct=0.30 -> R=0.60; target fill 3.20 -> realized_R =
   * (3.20-2.00)/0.60 = +2.0. The trail close leg carries exit_rule=chandelier_trail.
   */
  @Test
  void targetThenTrail_emitsMeasuredLegs_realizedRPlusTwoOnTarget() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-measure-target");
    PositionWorkflowInput in = exitInput(5);
    in.setSlPct(new BigDecimal("0.30")); // R = 0.60; target = 2.00*(1+2*0.30) = 3.20
    in.setTrailGivebackPct(new BigDecimal("0.10"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.00"));

    // Bid hits the +2R target (3.20) -> partial of 0.5 (ceil(2.5)=3) sold at 3.20.
    stub.chandelierTick(bidTick(new BigDecimal("3.20")));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-target-partial", 3L, new BigDecimal("3.20")));

    // Runner (2 left) trails: peak 3.20, giveback 0.10 -> threshold 2.88. A bid at 2.88 fires.
    stub.chandelierTick(bidTick(new BigDecimal("2.88")));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-trail", 2L, new BigDecimal("2.88")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    List<AuditEvent> measured = captureAll("WatchlistExitMeasured");
    assertThat(measured).hasSize(2);

    AuditEvent targetLeg =
        measured.stream()
            .filter(e -> "target".equals(e.getSubject().get("exit_rule")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no target leg measured"));
    assertThat(((Number) targetLeg.getSubject().get("exit_premium")).doubleValue()).isEqualTo(3.20);
    assertThat(((Number) targetLeg.getSubject().get("realized_R")).doubleValue()).isEqualTo(2.0);
    // 3 of 5 contracts closed on the target leg.
    assertThat(((Number) targetLeg.getSubject().get("partial_fraction")).doubleValue())
        .isEqualTo(0.6);

    AuditEvent trailLeg =
        measured.stream()
            .filter(e -> "chandelier_trail".equals(e.getSubject().get("exit_rule")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no trail leg measured"));
    assertThat(((Number) trailLeg.getSubject().get("exit_premium")).doubleValue()).isEqualTo(2.88);
    // MFE rode to the 3.20 target bid; MAE never dipped below entry on this path.
    assertThat(((Number) trailLeg.getSubject().get("premium_mfe")).doubleValue()).isEqualTo(3.20);
  }

  /** (h) copytrade (tp_ratio null) emits NO WatchlistExitMeasured — measurement is inert. */
  @Test
  void copytradePath_tpRatioNull_noMeasuredLeg() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-measure-copytrade");
    PositionWorkflowInput in = input(5);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.30"));

    stub.partialExit(partialExitRequest("sig-stc", "pos-wl-measure-copytrade", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stc", 5L, new BigDecimal("3.20")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    assertThat(captureAll("WatchlistExitMeasured")).isEmpty();
  }

  // ---------- (g) copytrade path unchanged ----------

  /**
   * (g) tp_ratio null -> NONE of the new exit commands appear: no premium subscription, no
   * WatchlistExit* audits. The position behaves exactly like the copytrade path (STC drains it).
   */
  @Test
  void copytradePath_tpRatioNull_noNewExitCommands() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-wl-copytrade");
    // input() leaves tp_ratio null -> exit disabled, byte-identical to copytrade.
    PositionWorkflowInput in = input(5);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L, new BigDecimal("2.30"));

    stub.partialExit(partialExitRequest("sig-stc", "pos-wl-copytrade", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stc", 5L, new BigDecimal("3.20")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-wl-copytrade");

    // No premium subscription was opened (exit disabled) and no exit audits fired.
    verify(marketData, never()).subscribePremium(any());
    assertThat(captureAll("WatchlistExitTargetFired")).isEmpty();
    assertThat(captureAll("WatchlistExitFeedStale")).isEmpty();
    List<AuditEvent> flattenReqs = captureAll("EodForceFlattenRequested");
    assertThat(flattenReqs.stream().map(e -> e.getSubject().get("reason")))
        .doesNotContain("stop_loss", "time_stop");
    verify(exec, times(1)).placeOrder(any());
  }

  // ---------- helpers ----------

  private static LocalTime eq1530() {
    return LocalTime.of(15, 30);
  }

  private PositionWorkflow newStub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            PositionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .build());
  }

  private PositionWorkflowInput input(long qty) {
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("watchlist-v1");
    in.setEntrySignalId("entry-1");
    in.setContractSymbol(SYMBOL);
    in.setQty(qty);
    in.setEntryPremium(new BigDecimal("2.00"));
    return in;
  }

  /** input() + the watchlist exit DNA: tp_ratio=2, sl_pct=0.25, tp_partial_fraction=0.5. */
  private PositionWorkflowInput exitInput(long qty) {
    PositionWorkflowInput in = input(qty);
    in.setTpRatio(new BigDecimal("2"));
    in.setSlPct(new BigDecimal("0.25"));
    in.setTpPartialFraction(new BigDecimal("0.5"));
    in.setTrailGivebackPct(new BigDecimal("0.10"));
    return in;
  }

  /**
   * exitInput with tp_ratio armed but tp_partial_fraction left NULL (exercises the 0.5 default).
   */
  private PositionWorkflowInput exitInputNullFraction(long qty) {
    PositionWorkflowInput in = input(qty);
    in.setTpRatio(new BigDecimal("2"));
    in.setSlPct(new BigDecimal("0.25"));
    in.setTrailGivebackPct(new BigDecimal("0.10"));
    return in;
  }

  private static void confirmEntry(PositionWorkflow stub, long qty, BigDecimal premium) {
    stub.onFill(fill("brk-entry", qty, premium));
  }

  private static FillSignalPayload fill(String brokerOrderId, long qty, BigDecimal avg) {
    return new FillSignalPayload()
        .withBrokerOrderId(brokerOrderId)
        .withFilledQty(qty)
        .withAvgFillPrice(avg)
        .withFilledAt(OffsetDateTime.now());
  }

  private PremiumTick bidTick(BigDecimal bid) {
    PremiumTick t = new PremiumTick();
    t.setSchemaVersion(1L);
    t.setContractSymbol(SYMBOL);
    // premium (smoothed mid) tracks slightly above the bid; the exit evaluates the BID.
    t.setPremium(bid.add(new BigDecimal("0.05")));
    t.setBid(bid);
    t.setAsk(bid.add(new BigDecimal("0.10")));
    t.setRetrievedAt(OffsetDateTime.now());
    return t;
  }

  private PartialExitRequest partialExitRequest(String signalId, String posWfId, double fraction) {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("watchlist-v1");
    req.setSignalId(signalId);
    req.setPositionWorkflowId(posWfId);
    req.setFraction(BigDecimal.valueOf(fraction));
    req.setRefPremium(new BigDecimal("2.85"));
    req.setReason("stc_signal");
    req.setAuthor("acme_trader");
    req.setRawLine("STC NVDA 5/16 140C @ 2.85");
    req.setOccurredAt(OffsetDateTime.of(2026, 5, 13, 17, 45, 0, 0, ZoneOffset.UTC));
    return req;
  }

  private OrderIntentResult submittedResult() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("exit-key");
    r.setBrokerOrderId("brk-exit");
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private static SubscribePremiumResult subscribedResult() {
    SubscribePremiumResult r = new SubscribePremiumResult();
    r.setSchemaVersion(1L);
    r.setSubscriptionId("sub-test");
    r.setSubscribedAt(OffsetDateTime.now());
    r.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    return r;
  }

  private static OptionQuoteResult quoteOk(BigDecimal bid, BigDecimal mid, BigDecimal ask) {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol(SYMBOL);
    r.setBid(bid);
    r.setMid(mid);
    r.setAsk(ask);
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.OK);
    return r;
  }

  private OrderIntent lastSell() {
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    return intent.getAllValues().stream()
        .filter(i -> i.getSide() == OrderIntent.Side.SELL)
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no SELL OrderIntent placed"));
  }

  private void waitForPlaceOrderCount(int n) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, times(n)).placeOrder(any());
        return;
      } catch (AssertionError ignored) {
        Thread.sleep(50);
      }
    }
    verify(exec, times(n)).placeOrder(any());
  }

  private void waitForKind(String kind) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        if (!captureAll(kind).isEmpty()) {
          return;
        }
      } catch (AssertionError ignored) {
        // no audits logged yet
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no audit event with kind=" + kind);
  }

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  private List<AuditEvent> captureAll(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream().filter(e -> kind.equals(e.getKind())).toList();
  }

  private static long asLong(Object o) {
    if (o instanceof Number n) return n.longValue();
    throw new AssertionError("expected Number, got " + o);
  }
}
