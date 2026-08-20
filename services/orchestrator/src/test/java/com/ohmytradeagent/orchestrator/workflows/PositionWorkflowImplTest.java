package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
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
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PositionWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  /**
   * A definitively-PAST OCC expiry (2026-05-16). The incident / worthless-close tests want a
   * KNOWN-expired contract, so a fixed past literal is correct here. NOTE: two spaces after {@code
   * NVDA} — OCC 6-char root padding.
   */
  private static final String EXPIRED_OCC_SYMBOL = "NVDA  260516C00140000";

  /**
   * A self-renewing FUTURE OCC expiry, computed once as now+2yr (ET) so these tests NEVER expire as
   * the real calendar advances (TestWorkflowEnvironment's virtual clock starts at ~wall-now and
   * only advances by minutes via env.sleep, so now+2yr stays comfortably in the future for the
   * whole run). Same {@code NVDA } root + {@code C00140000} strike as {@link #EXPIRED_OCC_SYMBOL};
   * only the YYMMDD expiry segment is computed. (Wall-clock LocalDate.now is fine in a TEST fixture
   * — the no-LocalDate.now rule guards replay-deterministic WORKFLOW code, not tests.)
   */
  private static final String FUTURE_OCC_SYMBOL =
      "NVDA  "
          + LocalDate.now(ZoneId.of("America/New_York"))
              .plusYears(2)
              .format(DateTimeFormatter.ofPattern("yyMMdd"))
          + "C00140000";

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

    // Default calendar: no EOD/expiry pressure
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
    // Plan-2B R-AB-1: default ZERO so the guaranteed expiry-lead timer is NOT armed unless a test
    // overrides it (mirrors the durationUntilExpiryCloseEt default; ZERO/negative → no timer
    // armed).
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ZERO);
    // Default market-data: subscription succeeds.
    when(marketData.subscribePremium(any())).thenReturn(subscribedResult());
    // Plan-2A R-AA-2/R-AA-3: default live-bid quote so bounded scheduled flatten anchors on a real
    // bid. bid=2.50 mid=2.55 ask=2.60. Tests that need FAILED/UNAVAILABLE override per-test.
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

  private static OptionQuoteResult quoteOk(BigDecimal bid, BigDecimal mid, BigDecimal ask) {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol(EXPIRED_OCC_SYMBOL);
    r.setBid(bid);
    r.setMid(mid);
    r.setAsk(ask);
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.OK);
    return r;
  }

  private static OptionQuoteResult quoteFailed(String error) {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol(EXPIRED_OCC_SYMBOL);
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.FAILED);
    r.setError(error);
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

  private static SubscribePremiumResult failedSubscription(String error) {
    SubscribePremiumResult r = new SubscribePremiumResult();
    r.setSchemaVersion(1L);
    r.setSubscriptionId("");
    r.setSubscribedAt(OffsetDateTime.now());
    r.setStatus(SubscribePremiumResult.Status.FAILED);
    r.setError(error);
    return r;
  }

  private ArmChandelierPayload armPayload(
      String posWfId, String sourceSignalId, BigDecimal peak, BigDecimal giveback) {
    ArmChandelierPayload p = new ArmChandelierPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setPositionWorkflowId(posWfId);
    p.setSourceSignalId(sourceSignalId);
    p.setPeakPremium(peak);
    p.setGivebackPct(giveback);
    return p;
  }

  /** Builds a {@link FillSignalPayload} with a synthetic {@code filledAt}. */
  private static FillSignalPayload fill(String brokerOrderId, long qty, BigDecimal avg) {
    return new FillSignalPayload()
        .withBrokerOrderId(brokerOrderId)
        .withFilledQty(qty)
        .withAvgFillPrice(avg)
        .withFilledAt(OffsetDateTime.now());
  }

  /**
   * Issue #203: sends the entry-fill signal that confirms the v=1 BTO. Without this, the workflow's
   * first-fill await times out into the PositionNeverFilled branch and never reaches the partial-
   * exit pipeline. The production-side fix buffers any partialExit signal arriving before the first
   * onFill, so test ordering is robust: the test can send onFill before or after partialExit.
   */
  private static void confirmEntry(PositionWorkflow stub, long qty) {
    stub.onFill(fill("brk-entry", qty, new BigDecimal("2.30")));
  }

  private PremiumTick tick(BigDecimal premium) {
    PremiumTick t = new PremiumTick();
    t.setSchemaVersion(1L);
    t.setContractSymbol(EXPIRED_OCC_SYMBOL);
    t.setPremium(premium);
    t.setRetrievedAt(OffsetDateTime.now());
    return t;
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void happyPath_halfThenFullClose_completes() throws Exception {
    OrderIntentResult placed = submittedResult();
    when(exec.placeOrder(any())).thenReturn(placed);

    PositionWorkflow stub = newStub("pos-happy");
    WorkflowExecution exec1 = WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // Signal half-out
    stub.partialExit(partialExitRequest("sig-1", "pos-happy", 0.5));
    // Wait for the workflow to call exec.placeOrder before signalling fill.
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-1", 3L, new BigDecimal("2.85")));

    // Then full close
    stub.partialExit(partialExitRequest("sig-2", "pos-happy", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-2", 2L, new BigDecimal("3.10")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-happy");

    verify(exec, times(2)).placeOrder(any());
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);

    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(2);
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(3L);
    assertThat(asLong(partialFills.get(1).getSubject().get("qty_filled"))).isEqualTo(2L);
    // Issue #276: new executions (TestWorkflowEnvironment reports getVersion==1) carry the
    // per-symbol correlation key so DailyPnl can group FIFO by option_symbol.
    assertThat(partialFills.get(0).getSubject()).containsEntry("option_symbol", FUTURE_OCC_SYMBOL);
    assertThat(partialFills.get(1).getSubject()).containsEntry("option_symbol", FUTURE_OCC_SYMBOL);
  }

  /**
   * Issue #266 Gap A (trading-critical): the exit/STC limit fed into {@code exitIntent} from {@code
   * req.getRefPremium()} was placed UNROUNDED. A {@code refPremium} of 3.255 would submit a 3-dp
   * SELL limit and draw a non-retryable Alpaca 422 — a FAILED position close (worse than a failed
   * entry: the position is stranded with no STC). The exit limit must be rounded to a penny tick
   * via the shared {@link com.ohmytradeagent.orchestrator.domain.OptionTick#round(BigDecimal)}
   * helper before the {@code OrderIntent} is placed: 3.255 -> 3.26.
   */
  @Test
  void exitLimit_isRoundedToPennyTickBeforePlacement_issue266() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-exit-round");
    WorkflowStub.fromTyped(stub).start(futureInput(4));
    confirmEntry(stub, 4L);

    // STC with a 3-dp refPremium that must round HALF_UP to a broker-accepted penny tick.
    PartialExitRequest req = partialExitRequest("sig-round", "pos-exit-round", 1.0);
    req.setRefPremium(new BigDecimal("3.255"));
    stub.partialExit(req);
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-round", 4L, new BigDecimal("3.20")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent exit =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL && i.getLimitPrice() != null)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no SELL OrderIntent with a limit price placed"));
    assertThat(exit.getLimitPrice()).isEqualByComparingTo(new BigDecimal("3.26"));
    assertThat(exit.getLimitPrice().scale()).isLessThanOrEqualTo(2);
  }

  /**
   * Issue #288 (trading-critical): an adopted {@code PositionWorkflow} (spawned by {@code
   * AdoptionWorkflowImpl} from its already-filled broker lot) never places an entry — its FIRST
   * {@code exec.placeOrder} is the exit. Before this fix {@code exitIntent(...)} never called
   * {@code setBrokerTarget}, so the exit {@code OrderIntent} carried {@code brokerTarget=null},
   * {@code ExecActivitiesImpl.validateIntent} threw a non-retryable {@code
   * InvalidOrderIntentError}, the PlaceOrder activity failed {@code
   * RETRY_STATE_NON_RETRYABLE_FAILURE}, the workflow terminated, and recon re-flagged {@code
   * PositionOrphan} — the lot became unsellable. The resolved broker target (the same value used at
   * run() to route {@code ExecActivitiesFactory.forTarget}) must be threaded onto the exit {@code
   * OrderIntent} so the STC reaches the exec broker and the workflow survives.
   */
  @Test
  void exitIntent_carriesResolvedBrokerTarget_issue288() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    // Models the adopted-position input: brokerTarget resolved by AdoptionWorkflowImpl.buildInput
    // from StrategyConfig.broker_target and threaded onto PositionWorkflowInput.
    PositionWorkflowInput in = futureInput(4);
    in.setBrokerTarget(PositionWorkflowInput.BrokerTarget.ALPACA_PAPER);

    PositionWorkflow stub = newStub("pos-exit-broker-target");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    stub.partialExit(partialExitRequest("sig-bt", "pos-exit-broker-target", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-bt", 4L, new BigDecimal("3.20")));

    // Workflow survives the exit (does not crash/terminate/re-orphan).
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-exit-broker-target");

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent exit =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no SELL OrderIntent placed"));
    assertThat(exit.getBrokerTarget()).isEqualTo(OrderIntent.BrokerTarget.ALPACA_PAPER);
  }

  /**
   * Issue #291 (follow-up to #288): the FLATTEN path (EOD/expiry/risk-breach force-close) must also
   * thread the resolved broker target onto its SELL {@code OrderIntent}, mirroring the exit path
   * covered by {@link #exitIntent_carriesResolvedBrokerTarget_issue288()}. An adopted-shaped
   * position carries {@code brokerTarget} resolved at run() (the same value routing {@code
   * ExecActivitiesFactory.forTarget}); if {@code flattenIntent(...)} dropped it, the force-flatten
   * PlaceOrder would fail validateIntent and the lot would be stranded. Drives a flatten via the
   * EOD timer and asserts the SELL {@code OrderIntent.getBrokerTarget()} survives.
   */
  @Test
  void flattenIntent_carriesResolvedBrokerTarget_issue288() throws Exception {
    // Short EOD horizon forces the workflow to force-flatten the remaining lot via flattenIntent().
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    // Models the adopted-position input: brokerTarget resolved by AdoptionWorkflowImpl.buildInput
    // from StrategyConfig.broker_target and threaded onto PositionWorkflowInput.
    PositionWorkflowInput in = input(4);
    in.setBrokerTarget(PositionWorkflowInput.BrokerTarget.ALPACA_PAPER);
    in.setEodForceFlatten(Boolean.TRUE); // opt into the blanket EOD flatten to drive this path

    PositionWorkflow stub = newStub("pos-flatten-broker-target");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    // Let virtual time advance past EOD so the force-flatten fires, then deliver its fill.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 4L, new BigDecimal("2.50")));

    // Workflow survives the force-flatten (does not crash/terminate/re-orphan).
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-broker-target");

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no SELL OrderIntent placed"));
    assertThat(flatten.getBrokerTarget()).isEqualTo(OrderIntent.BrokerTarget.ALPACA_PAPER);
  }

  @Test
  void duplicateSignalId_isSuppressed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-dup");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    stub.partialExit(partialExitRequest("sig-dup", "pos-dup", 1.0));
    waitForPlaceOrderCount(1);
    // Second signal with same signal_id — should be a no-op.
    stub.partialExit(partialExitRequest("sig-dup", "pos-dup", 1.0));
    stub.onFill(fill("brk-1", 5L, new BigDecimal("3.0")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, times(1)).placeOrder(any());
    AuditEvent dup = captureKind("ExitDuplicateSuppressed");
    assertThat(dup.getSubject()).containsEntry("signal_id", "sig-dup");
  }

  @Test
  void queuedSecondExit_emitsExitQueuedAndDrains() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-queue");
    WorkflowStub.fromTyped(stub).start(futureInput(4));
    confirmEntry(stub, 4L);

    // Signal both before any fill arrives
    stub.partialExit(partialExitRequest("sig-A", "pos-queue", 0.5));
    stub.partialExit(partialExitRequest("sig-B", "pos-queue", 1.0));

    waitForPlaceOrderCount(1);
    // Deterministic sync: guarantee sig-B is QUEUED (and ExitQueued emitted) while sig-A is still
    // in-flight, before we drain sig-A with a fill. Without this the fill can drain sig-A first and
    // sig-B is then processed directly (never queued), so ExitQueued is never emitted -> flaky
    // captureKind("ExitQueued") below.
    waitForAuditKind("ExitQueued");
    // First fill closes 2 of 4
    stub.onFill(fill("brk-A", 2L, new BigDecimal("2.85")));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-B", 2L, new BigDecimal("2.90")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, times(2)).placeOrder(any());
    AuditEvent queued = captureKind("ExitQueued");
    assertThat(((Number) queued.getSubject().get("queue_depth")).intValue()).isPositive();
  }

  @Test
  void eodTimer_forceFlattensRemaining() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-eod");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE); // opt into the blanket EOD flatten to drive this path
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // Let virtual time advance past EOD; the bounded flatten places its limit then awaits a fill.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    // Plan-2A R-AA-1: remainingQty is zeroed ONLY by the actual fill, so the workflow stays alive
    // until this flatten fill arrives.
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-eod");

    AuditEvent requested = captureKind("EodForceFlattenRequested");
    assertThat(asLong(requested.getSubject().get("remaining_qty"))).isEqualTo(5L);

    captureKind("EodForceFlattened");
    verify(exec, atLeastOnce()).placeOrder(any());
    // Plan-2A R-AA-6: the flatten fill enters realized P&L via a PartialExitFilled carrying price.
    AuditEvent flattenFill = captureKind("PartialExitFilled");
    assertThat(((Number) flattenFill.getSubject().get("avg_fill_price")).doubleValue())
        .isEqualTo(2.50);
    assertThat(asLong(flattenFill.getSubject().get("qty_filled"))).isEqualTo(5L);
    assertThat(flattenFill.getSubject()).containsEntry("option_symbol", EXPIRED_OCC_SYMBOL);
    assertThat(flattenFill.getSubject()).containsEntry("signal_id", "flatten-eod");
  }

  @Test
  void expiryTimer_forceFlattensRemaining() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ofMillis(200));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-expiry");
    // input(3) leaves force_close_0dte_et null -> the workflow must call the activity with a null
    // closeTime, preserving the legacy 15:30 ET default path (Issue #15 null-passthrough).
    // expiry_day_floor set so the bounded expiry flatten rests a real limit (not config-error).
    PositionWorkflowInput in = input(3);
    in.setExpiryDayFloor(new BigDecimal("0.05"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 3L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent req = captureKind("ExpiryForceFlattenRequested");
    assertThat(asLong(req.getSubject().get("remaining_qty"))).isEqualTo(3L);
    captureKind("ExpiryForceFlattened");
    // Null force_close_0dte_et must reach the activity as null (legacy 15:30 ET default).
    verify(calendar).durationUntilExpiryCloseEt(any(), isNull());
  }

  /**
   * Issue #434: a contract that expires WORTHLESS has no buyer, so the scheduled PHYSICAL-expiry
   * flatten's SELL never fills. Before this fix the workflow blocked ALIVE forever waiting for a
   * fill that can never come (remainingQty zeroed only from an actual fill under
   * VERSION_FLATTEN_FILL_AWAIT) → it lingered "open" past physical expiry, where recon re-adopts it
   * and the dashboard counts it (the TSLA 260618P incident). Under VERSION_EXPIRE_WORTHLESS v>=1
   * (TestWorkflowEnvironment reports getVersion==1), when the reason=expiry flatten does not fill
   * by its bounded TTL AND the OCC has physically expired (NVDA 260516 is well in the past), the
   * lot is closed as worthless: remainingQty -> 0, a terminal PositionExpired audit, and run()
   * completes normally instead of hanging.
   */
  @Test
  void expiryNoFill_worthlessContract_closesAsExpired_completesInsteadOfLingering()
      throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ofMillis(200));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-expiry-worthless");
    PositionWorkflowInput in = input(25);
    in.setExpiryDayFloor(new BigDecimal("0.05"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 25L);

    // Let virtual time advance past the expiry timer; the flatten places its SELL then awaits a
    // fill. NO fill is delivered — a worthless contract has no buyer — so the bounded exit-fill TTL
    // elapses (time-skipped) and the worthless-close path fires.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // The workflow completes (does NOT hang ALIVE) despite the unfilled flatten.
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-expiry-worthless");

    // The physical-expiry flatten was attempted (SELL placed) but never filled...
    captureKind("ExpiryForceFlattenRequested");
    // ...so the lot is closed as WORTHLESS: a terminal PositionExpired with the pre-close qty.
    AuditEvent expired = captureKind("PositionExpired");
    assertThat(asLong(expired.getSubject().get("remaining_qty_before"))).isEqualTo(25L);
    assertThat(expired.getSubject())
        .containsEntry("reason", "worthless_expiry")
        .containsEntry("option_symbol", EXPIRED_OCC_SYMBOL);

    // No worthless-expiry exit credit: PositionExpired is P&L-neutral (no PartialExitFilled for the
    // flatten, since nothing filled).
    assertThat(captureAll("PartialExitFilled")).isEmpty();
  }

  // ---------- PLAN-2026-07-23 Phase 2: expiry-day adoption zombie self-close guard ----------

  /**
   * PLAN-2026-07-23 Phase 2 — REPRODUCES THE INCIDENT. A lot ADOPTED after its own OCC has
   * physically expired, with NOT ONE terminal-flatten timer armed (default calendar: every {@code
   * durationUntil*} is ZERO and {@code eodForceFlatten} unset), used to have no fired-flag that
   * could ever latch: run() blocked ALIVE forever on a delisted contract, fail-closing the account
   * cap. Under {@code VERSION_EXPIRE_WORTHLESS_NO_TIMER} v&gt;=1 (TestWorkflowEnvironment resolves
   * getVersion to the max, 1) the guard self-closes it worthless at entry: a terminal {@code
   * PositionExpired} ({@code reason=worthless_expiry}, P&amp;L-neutral) and run() COMPLETES instead
   * of hanging.
   */
  @Test
  void startedAfterExpiryInstantsOnExpiryDay_closesWorthlessImmediately() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    // Default EXPIRED_OCC_SYMBOL (NVDA 260516, physically expired) + DEFAULT calendar: no eod, no
    // expiry-close, no expiry-lead timer armed. This is the recon-forwarded expiry-day adoption.
    PositionWorkflow stub = newStub("pos-expiry-zombie");
    WorkflowStub.fromTyped(stub).start(input(6));
    confirmEntry(stub, 6L); // the adoption fill forwarded by recon

    // run() COMPLETES (does NOT hang ALIVE) — the zombie that used to block forever.
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-expiry-zombie");

    // Self-closed worthless at entry: a terminal PositionExpired carrying the pre-close qty.
    AuditEvent expired = captureKind("PositionExpired");
    assertThat(asLong(expired.getSubject().get("remaining_qty_before"))).isEqualTo(6L);
    assertThat(expired.getSubject())
        .containsEntry("reason", "worthless_expiry")
        .containsEntry("option_symbol", EXPIRED_OCC_SYMBOL);

    // P&L-neutral: no exit order filled, so no PartialExitFilled credit.
    assertThat(captureAll("PartialExitFilled")).isEmpty();
  }

  /**
   * PLAN-2026-07-23 Phase 2 — the guard is scoped to the no-timer case. Same physically-expired
   * OCC, but the workflow was started BEFORE its expiry instants so a terminal timer arms (here the
   * expiry-close timer, via a FUTURE {@code durationUntilExpiryCloseEt}). {@code expiryTimerArmed}
   * is true, so the guard does NOT fire: the position stays ALIVE until the expiry timer fires and
   * drives the normal bounded flatten — unchanged behavior.
   */
  @Test
  void startedBeforeExpiryInstants_armsTimersUnchanged() throws Exception {
    // A FUTURE expiry-close instant arms the expiry timer -> the guard's no-timer precondition is
    // false.
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ofMillis(200));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-timer-armed");
    PositionWorkflowInput in = input(3);
    in.setExpiryDayFloor(new BigDecimal("0.05"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    // The expiry timer fires and drives the normal flatten (SELL placed) — proving the guard did
    // NOT
    // short-circuit at entry.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 3L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // The normal expiry path ran...
    captureKind("ExpiryForceFlattenRequested");
    captureKind("ExpiryForceFlattened");
    // ...and the no-timer worthless-close guard never fired.
    assertThat(
            captureAll("PositionExpired").stream()
                .filter(e -> "worthless_expiry".equals(e.getSubject().get("reason"))))
        .isEmpty();
  }

  /**
   * PLAN-2026-07-23 Phase 2 — the guard is scoped to PHYSICALLY-expired contracts only. A
   * NON-expired OCC ({@link #FUTURE_OCC_SYMBOL}) with the default no-timer setup must NOT trip the
   * guard: {@code expiryDateFromOcc(...).isAfter(currentEtDate())} is true, so the physical-expiry
   * precondition is false. The position stays ALIVE and drives to a normal STC close, unchanged.
   */
  @Test
  void startedAfterEodOnNonExpiryDay_staysAliveUnchanged() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-not-expired");
    // FUTURE contract + default no-timer setup: guard's physical-expiry precondition is false.
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // The position is alive and manageable: a full-close STC drives it to a normal PositionClosed.
    stub.partialExit(partialExitRequest("sig-close", "pos-not-expired", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-exit", 5L, new BigDecimal("3.10")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-not-expired");

    // Normal close, and the worthless-close guard never fired.
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
    assertThat(captureAll("PositionExpired")).isEmpty();
  }

  // ---------- Phase 2 (PLAN-2026-07-12, B1): broaden worthless-close to eod / expiry_lead
  // ----------

  /**
   * Phase 2 (PLAN-2026-07-12, B1) — REPRODUCES THE 2026-07-10 AMZN INCIDENT. A 0DTE deep-OTM no-bid
   * lot whose TERMINAL flatten is {@code reason=eod} (the blanket end-of-day sweep) rests UNFILLED
   * — a worthless contract has no buyer. Before this fix the worthless-close fired ONLY for {@code
   * reason=="expiry"}, so an {@code eod}-adopted expired lot fell through to the stay-ALIVE block
   * and lingered as a phantom for ~2 days until manual termination. Under {@code
   * VERSION_EXPIRE_WORTHLESS_SCHEDULED} v&gt;=1 (TestWorkflowEnvironment resolves getVersion to the
   * max, 1) and a PHYSICALLY-expired OCC (NVDA 260516 is well in the past), the {@code eod} no-fill
   * now worthless-closes: remainingQty -&gt; 0, a terminal PositionExpired ({@code
   * reason=worthless_expiry}), and {@code run()} COMPLETES instead of lingering / retrying.
   */
  @Test
  void expiredContract_eodFlatten_noFill_closesWorthless_notLinger() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-worthless");
    PositionWorkflowInput in = input(7);
    in.setEodForceFlatten(Boolean.TRUE); // opt into the blanket EOD flatten (reason=eod)
    in.setExitFillTtlSecs(2L); // short bounded await so the unfilled flatten resolves fast
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 7L);

    // EOD fires -> bounded flatten placed -> NO fill (worthless no-bid lot) -> bounded await
    // elapses
    // -> reason=eod on a physically-expired OCC worthless-closes instead of lingering.
    env.sleep(Duration.ofMinutes(2));
    waitForPlaceOrderCount(1);

    // run() COMPLETES (does NOT hang ALIVE / retry next session).
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-eod-worthless");

    captureKind("EodForceFlattenRequested");
    AuditEvent expired = captureKind("PositionExpired");
    assertThat(asLong(expired.getSubject().get("remaining_qty_before"))).isEqualTo(7L);
    assertThat(expired.getSubject())
        .containsEntry("reason", "worthless_expiry")
        .containsEntry("option_symbol", EXPIRED_OCC_SYMBOL);

    // P&L-neutral: nothing filled, so no exit credit and no next-session retry churn.
    assertThat(captureAll("PartialExitFilled")).isEmpty();
    assertThat(captureAll("FlattenRetryScheduled")).isEmpty();
  }

  /**
   * Phase 2 (PLAN-2026-07-12, B1) — same broadening for {@code reason=expiry_lead}. A multi-day
   * lead flatten that ends up firing on/after the lot's OCC expiry date and rests unfilled
   * (worthless no-bid) must ALSO worthless-close rather than linger. The lead timer is driven by
   * {@code durationUntilExpiryFlattenEt}; the OCC (NVDA 260516) is physically expired, so under
   * v&gt;=1 the {@code expiry_lead} no-fill terminates as expire-worthless.
   */
  @Test
  void expiredContract_expiryLeadFlatten_noFill_closesWorthless() throws Exception {
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-lead-worthless");
    PositionWorkflowInput in = input(4);
    in.setExitFillTtlSecs(2L); // short bounded await so the unfilled lead flatten resolves fast
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    // Lead timer fires -> bounded flatten placed -> NO fill -> bounded await elapses -> reason=
    // expiry_lead on a physically-expired OCC worthless-closes.
    env.sleep(Duration.ofMinutes(2));
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-lead-worthless");

    captureKind("ExpiryLeadFlattenRequested");
    AuditEvent expired = captureKind("PositionExpired");
    assertThat(asLong(expired.getSubject().get("remaining_qty_before"))).isEqualTo(4L);
    assertThat(expired.getSubject())
        .containsEntry("reason", "worthless_expiry")
        .containsEntry("option_symbol", EXPIRED_OCC_SYMBOL);
    assertThat(captureAll("PartialExitFilled")).isEmpty();
    assertThat(captureAll("FlattenRetryScheduled")).isEmpty();
  }

  /**
   * Phase 2 (PLAN-2026-07-12, B1) — REGRESSION: the physical-expiry date check stays the REAL
   * guard. OFF the expiry date (OCC NVDA 270115 is well in the FUTURE), an {@code eod} flatten that
   * rests unfilled must STILL stay ALIVE exactly as before — {@code maybeCloseWorthlessAtExpiry}
   * returns false at the physical-expiry guard, never reaching the worthless-close, so there is NO
   * behavior change off the expiry date. The lot stays managed (retry-next-session / late-fill),
   * never worthless-closed.
   */
  @Test
  void notExpiredContract_eodFlatten_noFill_staysAliveUnchanged() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-notexpired");
    PositionWorkflowInput in = input(5);
    in.setContractSymbol(FUTURE_OCC_SYMBOL); // FUTURE expiry -> NOT physically expired
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L); // short TTL so the unfilled-await elapses quickly under virtual time
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> bounded flatten placed -> fill never arrives -> TTL elapses -> stays alive
    // (physical-expiry guard returns false; no worthless-close on a not-yet-expired contract).
    env.sleep(Duration.ofMinutes(2));
    waitForPlaceOrderCount(1);

    // Still RUNNING (queryable), remainingQty unchanged, and NO worthless-close / no
    // PositionClosed.
    PositionState state = stub.positionState();
    assertThat(state.remainingQty()).isEqualTo(5L);
    assertThat(captureAll("PositionExpired")).isEmpty();
    assertThat(captureAll("PositionClosed")).isEmpty();
    AuditEvent failed = captureKind("EodForceFlattenFailed");
    assertThat(failed.getSubject())
        .containsEntry("note", "bounded_flatten_unfilled_workflow_stays_alive");
  }

  /**
   * Phase 2 (PLAN-2026-07-12, B1) — DEFAULT_VERSION replay determinism for the new {@code
   * VERSION_EXPIRE_WORTHLESS_SCHEDULED} gate.
   *
   * <p><b>Test-scope note (mirrors {@code
   * processOne_exitFillTimeout_retryGateUnderVersionResolutionDocsAndAsserts}):</b> {@link
   * io.temporal.testing.TestWorkflowEnvironment} starts every workflow with a fresh history, so
   * {@code Workflow.getVersion(VERSION_EXPIRE_WORTHLESS_SCHEDULED, DEFAULT_VERSION, 1)} resolves to
   * {@code 1} (the max registered version) — there is no clean knob to force {@code
   * DEFAULT_VERSION} without {@code WorkflowReplayer} replaying a real pre-Phase-2 history file.
   * The v=0 preservation (an in-flight {@code eod}/{@code expiry_lead} history keeps returning
   * {@code false} → stay-ALIVE, byte-identical) is therefore enforced by Temporal's marker-based
   * version resolution itself; the ONLY new command appended on v=0 is the getVersion marker.
   *
   * <p>What this test DOES assert against the live code path: at DEFAULT_VERSION the broadened
   * branch returns {@code false} through the SAME early-return in {@code
   * maybeCloseWorthlessAtExpiry} that the physical-expiry guard uses. We exercise that exact
   * return-false → stay-ALIVE outcome by driving an {@code eod} no-fill on a NOT-yet-expired OCC
   * (the guard returns false before the version read) and proving the lot stays ALIVE with NO
   * PositionExpired — the identical observable behavior a v=0 in-flight history replays with.
   * Paired with {@code expiredContract_eodFlatten_noFill_closesWorthless_notLinger} (which proves
   * v=max closes), this brackets the gate: v=0 stays alive, v&gt;=1 worthless-closes.
   */
  @Test
  void expiredContract_eodFlatten_defaultVersionReplay_staysAlive_docsAndAsserts()
      throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-v0-replay");
    PositionWorkflowInput in = input(3);
    // NOT-yet-expired OCC: the physical-expiry guard returns false BEFORE the version read, giving
    // the identical return-false → stay-ALIVE outcome a v=0 in-flight history replays with.
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    env.sleep(Duration.ofMinutes(2));
    waitForPlaceOrderCount(1);

    // Legacy/v=0 observable: still ALIVE, no worthless-close, remainingQty intact.
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
    assertThat(captureAll("PositionExpired")).isEmpty();
    assertThat(captureAll("PositionClosed")).isEmpty();
  }

  @Test
  void expiryTimer_configuredForceClose0dte_drivesBoundedLimitFlatten() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ofMillis(200));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-expiry-cfg");
    PositionWorkflowInput in = input(3);
    in.setForceClose0dteEt("14:45"); // Issue #15: per-strategy 0DTE force-flat time.
    in.setExpiryDayFloor(new BigDecimal("0.05")); // R-AA-3: expiry floor collapse (bid>0).
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 3L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    captureKind("ExpiryForceFlattenRequested");
    captureKind("ExpiryForceFlattened");

    // The configured "14:45" must be parsed and passed to the calendar activity as LocalTime.
    verify(calendar).durationUntilExpiryCloseEt(any(), eq(LocalTime.of(14, 45)));

    // Plan-2A R-AA-3: a live bid (2.50) + an expiry_day_floor (0.05) -> the expiry flatten is a
    // BOUNDED marketable LIMIT (no longer a market dump): the SELL OrderIntent carries a limit
    // price
    // anchored at/through the bid and at/above the floor.
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNotNull();
    assertThat(flatten.getLimitPrice()).isGreaterThanOrEqualTo(new BigDecimal("0.05"));
    assertThat(flatten.getLimitPrice()).isLessThanOrEqualTo(new BigDecimal("2.50"));
  }

  @Test
  void eodWithInFlight_cancelsThenFlattens() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofSeconds(30));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-inflight");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE); // opt into the blanket EOD flatten to drive this path
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // Queue an STC but never deliver the fill — exit is in-flight when EOD fires.
    stub.partialExit(partialExitRequest("sig-inflight", "pos-eod-inflight", 0.5));
    waitForPlaceOrderCount(1);

    // Trigger EOD before fill arrives.
    env.sleep(Duration.ofMinutes(1));

    // EOD cancels the in-flight order, places the bounded flatten, then awaits its fill (R-AA-1).
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, atLeastOnce()).cancelOrder(anyString());
    captureKind("EodForceFlattenRequested");
    captureKind("EodForceFlattened");
  }

  // ---------- Plan-2A R-AA-1 / R-AA-3 / R-AA-6: bounded scheduled flatten ----------

  /**
   * Plan-2A R-AA-1 (the core silent-loss fix), epilogue path (~595, NOT processOne): an EOD bounded
   * flatten that is PLACED but never FILLED must leave the workflow ALIVE and must NOT emit
   * PositionClosed — remainingQty is zeroed only by an actual fill. Pre-fix the workflow zeroed at
   * placement and emitted an unconditional PositionClosed, silently completing with a live lot.
   */
  @Test
  void epilogue_eodBoundedLimitPlacedButUnfilled_staysAliveNoPositionClosed() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-unfilled");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so this exercises the stay-ALIVE path for a multi-day hold, not
    // the physically-expired worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L); // short TTL so the unfilled-await elapses quickly under virtual time
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> bounded flatten is placed -> fill never arrives -> TTL elapses -> cancel + stay
    // alive. Advance well past the TTL.
    env.sleep(Duration.ofMinutes(2));
    waitForPlaceOrderCount(1);

    // The workflow is still RUNNING (queryable) and has NOT emitted PositionClosed.
    PositionState state = stub.positionState();
    assertThat(state.remainingQty()).isEqualTo(5L);
    assertThat(captureAll("PositionClosed")).isEmpty();
    // A loud failure audit was emitted for the unfilled bounded flatten.
    AuditEvent failed = captureKind("EodForceFlattenFailed");
    assertThat(failed.getSubject())
        .containsEntry("note", "bounded_flatten_unfilled_workflow_stays_alive");
  }

  // ---------- Phase 4 (PLAN-2026-06-24-trading-remediation): flatten-fail retry-next-session
  // ------

  /**
   * Phase 4 (PLAN-2026-06-24-trading-remediation), v&gt;=1 happy retry: an EOD bounded flatten that
   * rests UNFILLED (orders submitted at/after the close) must (a) emit the alert-eligible
   * EodForceFlattenFailed, and (b) re-attempt the flatten at the NEXT market-session open —
   * emitting FlattenRetryScheduled and re-calling placeOrder — instead of being held silently
   * overnight. TestWorkflowEnvironment resolves getVersion to the max (1), so this exercises the
   * v&gt;=1 retry path.
   */
  @Test
  void phase4_unfilledFlatten_retriesAtNextSessionOpen() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    // Next-session open is 5 minutes out (virtual time); env.sleep past it fires the retry timer.
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-flatten-retry");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the retry-next-session path is exercised, not the worthless-
    // close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L); // short TTL so each unfilled await elapses fast under virtual time
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> bounded flatten placed (#1) -> TTL elapses unfilled -> cancel + loud failure.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    AuditEvent failed = captureKind("EodForceFlattenFailed");
    assertThat(failed.getSubject())
        .containsEntry("note", "bounded_flatten_unfilled_workflow_stays_alive");

    // Advance past the next-session open -> retry timer fires -> FlattenRetryScheduled +
    // placeOrder.
    env.sleep(Duration.ofMinutes(6));
    waitForPlaceOrderCount(2);
    AuditEvent scheduled = captureKind("FlattenRetryScheduled");
    assertThat(scheduled.getSubject()).containsEntry("attempt", 1).containsEntry("reason", "eod");

    // Still alive (no fill ever arrived), no PositionClosed emitted.
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    assertThat(captureAll("PositionClosed")).isEmpty();
  }

  /**
   * Phase 4: after MAX_FLATTEN_RETRY_SESSIONS (3) unfilled next-session re-attempts the workflow
   * gives up retrying, emits the terminal FlattenRetryExhausted page, and stays ALIVE (falls back
   * to the legacy await-late-fill) — never silently completing with a live lot.
   */
  @Test
  void phase4_retryBudgetExhausted_emitsTerminalPageAndStaysAlive() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-flatten-exhaust");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the retry-next-session budget path is exercised, not the
    // worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires (placement #1) then 3 next-session retries (placements #2,#3,#4). Each retry =
    // 5min timer + a 2s unfilled await; sleep generously to drive all three under virtual time.
    env.sleep(Duration.ofMinutes(1));
    env.sleep(Duration.ofMinutes(30));

    // Three FlattenRetryScheduled (attempts 1..3) then the terminal FlattenRetryExhausted page.
    waitForPlaceOrderCount(4);
    assertThat(captureAll("FlattenRetryScheduled")).hasSize(3);
    AuditEvent exhausted = captureKind("FlattenRetryExhausted");
    assertThat(exhausted.getSubject()).containsEntry("attempts", 3).containsEntry("reason", "eod");

    // Each next-session retry MUST use a DISTINCT intent_key (→ a fresh client_order_id). Reusing
    // the first attempt's key would re-POST a duplicate client_order_id and FAIL the 2nd retry
    // instead of gracefully exhausting the budget. First attempt keeps the un-suffixed key.
    ArgumentCaptor<OrderIntent> intents = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(4)).placeOrder(intents.capture());
    List<String> flattenKeys =
        intents.getAllValues().stream()
            .map(OrderIntent::getIntentKey)
            .filter(k -> k.contains(":exit:flatten-"))
            .toList();
    assertThat(flattenKeys).hasSize(4).doesNotHaveDuplicates();
    assertThat(flattenKeys).anyMatch(k -> k.endsWith(":exit:flatten-eod")); // first attempt
    assertThat(flattenKeys).anyMatch(k -> k.endsWith(":retry-1"));
    assertThat(flattenKeys).anyMatch(k -> k.endsWith(":retry-2"));
    assertThat(flattenKeys).anyMatch(k -> k.endsWith(":retry-3"));

    // Bounded: no further retries past MAX, and the workflow is still alive (no PositionClosed).
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    assertThat(captureAll("PositionClosed")).isEmpty();
  }

  /**
   * Phase 4 regression guard: a LATE fill of the resting bounded limit arriving BEFORE the
   * next-session retry timer must close the position normally — no retry is scheduled (no
   * FlattenRetryScheduled), proving the retry path does not interfere with the existing late-fill
   * recovery.
   */
  @Test
  void phase4_lateFillBeforeNextSession_closesNormallyNoRetry() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    // Next open far out so the retry timer cannot fire before the late fill arrives.
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(12));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-flatten-latefill");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the late-fill recovery path is exercised, not the worthless-
    // close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> flatten placed -> TTL elapses unfilled -> alive-block arms the (far) retry
    // timer.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    captureKind("EodForceFlattenFailed");

    // A LATE fill of the resting flatten drains the lot before the next-session timer fires.
    stub.onFill(fill("brk-flatten-late", 5L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Closed normally; no retry was scheduled.
    assertThat(captureAll("FlattenRetryScheduled")).isEmpty();
    assertThat(captureAll("FlattenRetryExhausted")).isEmpty();
    captureKind("PositionClosed");
  }

  /**
   * Plan-2A R-AA-1 broker-confirmed-zero invariant: a PARTIAL flatten fill must NOT close the
   * position — PositionClosed is emitted only at broker-confirmed remaining==0. A first flatten
   * fill of 2-of-5 leaves the workflow alive; a late fill of the residual 3 then closes it.
   */
  @Test
  void epilogue_eodPartialFlattenFill_thenResidualFill_closesOnlyAtZero() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-eod-partial");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the partial-then-residual stay-ALIVE path is exercised, not
    // the worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    // Partial flatten fill (2 of 5): the workflow must stay alive (remaining 3), no PositionClosed.
    stub.onFill(fill("brk-flatten-1", 2L, new BigDecimal("2.50")));

    // Give the workflow a moment to process the partial fill, then confirm it has not closed.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline && stub.positionState().remainingQty() == 5L) {
      Thread.sleep(50);
    }
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
    assertThat(captureAll("PositionClosed")).isEmpty();

    // Late fill of the residual 3 -> broker-confirmed zero -> PositionClosed.
    stub.onFill(fill("brk-flatten-2", 3L, new BigDecimal("2.48")));
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-eod-partial");

    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
    // R-AA-6: two PartialExitFilled rows (2 + 3) both carry price -> realized P&L counts the
    // flatten.
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(2);
    assertThat(fills.stream().mapToLong(e -> asLong(e.getSubject().get("qty_filled"))).sum())
        .isEqualTo(5L);
  }

  /**
   * Issue #735 (cumulative-vs-delta): Alpaca reports {@code filled_qty} as the CUMULATIVE quantity
   * filled so far on an order, NOT the increment since the last event. A 5-lot flatten that fills 2
   * then completes reports {@code 2} then {@code 5} on the SAME broker order.
   *
   * <p>This is the distinction every other multi-fill test in this file misses: they feed DELTAS
   * ({@code 2} then {@code 3}) on two DIFFERENT broker order ids, which is the shape a sequence of
   * separate terminal fills has — not the shape a partially-filled single order has. Feeding deltas
   * is why a green suite has never seen this bug.
   *
   * <p>Booking the second event's cumulative {@code 5} as if it were a delta over-books by 3 and
   * drives {@code remainingQty} NEGATIVE (5 - 2 - 5 = -2), breaking the R-AA-1 invariant that
   * PositionClosed is emitted at broker-confirmed zero.
   */
  @Test
  void issue735_cumulativeFilledQtyOnOneOrder_booksDeltasNotCumulative() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-735-cumulative");
    PositionWorkflowInput in = input(5);
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // Partial fill: 2 of 5 filled so far on order brk-735.
    stub.onFill(fill("brk-735", 2L, new BigDecimal("2.50")));
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline && stub.positionState().remainingQty() == 5L) {
      Thread.sleep(50);
    }
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);

    // SAME order completes: cumulative 5 (i.e. the residual 3). Must book a delta of 3, not 5.
    stub.onFill(fill("brk-735", 5L, new BigDecimal("2.48")));
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-735-cumulative");

    // Total booked is the lot, never more.
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills.stream().mapToLong(e -> asLong(e.getSubject().get("qty_filled"))).sum())
        .as("booked qty must equal the 5-lot, not 7 (2 + cumulative 5)")
        .isEqualTo(5L);

    // R-AA-1: remainingQty must land at exactly zero and never go negative.
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty")))
        .as("remainingQty must never be driven negative by a cumulative report")
        .isEqualTo(0L);
    assertThat(
            fills.stream()
                .mapToLong(e -> asLong(e.getSubject().get("remaining_qty_after")))
                .min()
                .orElse(0L))
        .as("no booking may leave remainingQty below zero")
        .isGreaterThanOrEqualTo(0L);
  }

  /**
   * Issue #735 duplicate guard: the SAME broker fill can reach the workflow via two evidences (a
   * cancel-on-filled return AND a buffered onFill). Re-reporting a cumulative already booked must
   * book NOTHING — not a second full decrement.
   */
  @Test
  void issue735_duplicateCumulativeReport_booksOnce() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-735-dup");
    PositionWorkflowInput in = input(5);
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    stub.onFill(fill("brk-dup", 2L, new BigDecimal("2.50")));
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline && stub.positionState().remainingQty() == 5L) {
      Thread.sleep(50);
    }
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);

    // The SAME cumulative again — a re-report of one fill, not new progress.
    stub.onFill(fill("brk-dup", 2L, new BigDecimal("2.50")));
    Thread.sleep(500);
    assertThat(stub.positionState().remainingQty())
        .as("a duplicate cumulative must not decrement the lot a second time")
        .isEqualTo(3L);

    // Genuine completion of the same order closes it.
    stub.onFill(fill("brk-dup", 5L, new BigDecimal("2.48")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(
            captureAll("PartialExitFilled").stream()
                .mapToLong(e -> asLong(e.getSubject().get("qty_filled")))
                .sum())
        .isEqualTo(5L);
  }

  /**
   * Issue #735 clamp: a broker report LARGER than the outstanding lot (stale, duplicated, or a
   * correction) must never drive {@code remainingQty} negative — that would emit PositionClosed
   * with remaining &lt; 0 and break R-AA-1.
   */
  @Test
  void issue735_oversizedBrokerReport_clampsAndNeverGoesNegative() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-735-oversized");
    PositionWorkflowInput in = input(5);
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // Broker reports 9 filled against a 5-lot.
    stub.onFill(fill("brk-oversized", 9L, new BigDecimal("2.40")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills.stream().mapToLong(e -> asLong(e.getSubject().get("qty_filled"))).sum())
        .as("booking is clamped to the outstanding lot")
        .isEqualTo(5L);
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty")))
        .as("remainingQty must never be negative")
        .isEqualTo(0L);
  }

  /**
   * Issue #735 Phase 2: a PARTIAL fill must not be treated as a COMPLETED exit.
   *
   * <p>{@code processOne} books the first fill, releases the exit latch and returns. That is
   * correct while every fill is terminal — which it has been, because the WS has delivered nothing
   * for ~11 weeks and {@code FillPoller} structurally cannot dispatch a partial. The moment real
   * partials arrive it is wrong: a full-close STC that fills 2 of 5 releases the latch with 3 still
   * outstanding and the exit is silently abandoned. Nothing retries it, nothing times it out, and
   * the lot sits unmanaged until EOD.
   *
   * <p>The observable: an exit that never reaches its captured {@code targetRemaining} must surface
   * as a {@code PartialExitFillTimeout} (which then drives the existing cancel/retry machinery),
   * NOT complete silently.
   */
  @Test
  void issue735p2_partialFillOnFullCloseExit_doesNotCompleteTheExitSilently() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-735p2-partial");
    PositionWorkflowInput in = input(5);
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // Full-close STC (fraction 1.0): targetRemaining == 0.
    stub.partialExit(partialExitRequest("sig-p2", "pos-735p2-partial", 1.0));
    waitForPlaceOrderCount(1);

    // The broker fills only 2 of the 5 and the rest never comes.
    stub.onFill(fill("brk-p2", 2L, new BigDecimal("2.50")));

    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline
        && captureAll("PartialExitFillTimeout").isEmpty()) {
      env.sleep(Duration.ofSeconds(1));
      Thread.sleep(50);
    }

    assertThat(stub.positionState().remainingQty())
        .as("only the 2 that actually filled may be booked")
        .isEqualTo(3L);
    assertThat(captureAll("PartialExitFillTimeout"))
        .as("an exit that never reached its target must NOT complete silently")
        .isNotEmpty();
  }

  // ---------- Phase 1 (PLAN-2026-06-30): flatten broker-authoritative terminal-state reconcile
  // ----------

  /**
   * Phase 1 criterion 2 (the core flatten fill-race fix): the flatten bounded limit rests, its TTL
   * elapses with NO onFill delivered, but the broker had ALREADY filled the SELL. {@code
   * exec.cancelOrder} surfaces the authoritative terminal state (state=FILLED, positive filledQty +
   * avgFillPrice — the exec cancel-on-filled race). The workflow must reconcile it: book exactly
   * ONE PartialExitFilled (qty = broker qty, avg carried), zero remainingQty, emit the terminal
   * EodForceFlattened + PositionClosed markers ONCE, emit NO EodForceFlattenFailed, and CLOSE (the
   * caller does not re-arm the chandelier flatten loop). Pre-Phase-1 the cancel return was
   * discarded → remainingQty stayed 5 → phantom-stuck → infinite EodForceFlattenFailed
   * (DRAM/INTC/TSLA).
   */
  @Test
  void flattenTimeout_cancelReturnsFilled_noOnFill_reconcilesAndCloses() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // The bounded flatten rests; on TTL-timeout cancel the broker reports it ALREADY_FILLED (5 @
    // 2.49) — the authoritative terminal state the lost/late onFill never surfaced.
    when(exec.cancelOrder(anyString())).thenReturn(filledCancelResult(5L, new BigDecimal("2.49")));

    PositionWorkflow stub = newStub("pos-flatten-cancel-filled");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L); // short TTL so the unfilled-await elapses fast under virtual time
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> bounded flatten placed -> NO onFill ever delivered -> TTL elapses -> cancel
    // surfaces the broker fill -> reconcile books it -> remainingQty == 0 -> close.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // The workflow closed cleanly from broker truth alone (no onFill signal was sent).
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-cancel-filled");

    // Exactly ONE PartialExitFilled carrying the broker qty + price (enters realized P&L).
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(1);
    assertThat(asLong(fills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);
    assertThat(((Number) fills.get(0).getSubject().get("avg_fill_price")).doubleValue())
        .isEqualTo(2.49);
    // remainingQty zeroed; terminal lifecycle markers emitted once; NO failure audit.
    assertThat(captureAll("EodForceFlattened")).hasSize(1);
    assertThat(captureAll("PositionClosed")).hasSize(1);
    assertThat(captureAll("EodForceFlattenFailed")).isEmpty();
    // Broker-authoritative close needed no getOrderStatus fallback (cancel surfaced the fill).
    verify(exec, never()).getOrderStatus(anyString());
  }

  /**
   * Phase 1 criterion 3 (defense-in-depth getOrderStatus fallback): {@code cancelOrder} returns
   * CANCELLED/no-fill (the cancel raced ahead of the journal), but {@code getOrderStatus} reports
   * the order FILLED. The reconcile falls back to getOrderStatus and closes exactly as criterion 2.
   */
  @Test
  void flattenTimeout_cancelNoFill_getOrderStatusFilled_reconcilesAndCloses() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString()))
        .thenReturn(cancelledResult()); // cancel did not surface fill
    when(exec.getOrderStatus(anyString()))
        .thenReturn(filledCancelResult(5L, new BigDecimal("2.47")));

    PositionWorkflow stub = newStub("pos-flatten-status-filled");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-status-filled");

    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(1);
    assertThat(asLong(fills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);
    assertThat(((Number) fills.get(0).getSubject().get("avg_fill_price")).doubleValue())
        .isEqualTo(2.47);
    assertThat(captureAll("PositionClosed")).hasSize(1);
    assertThat(captureAll("EodForceFlattenFailed")).isEmpty();
    // Fallback WAS used (cancel returned no fill).
    verify(exec, atLeastOnce()).getOrderStatus(anyString());
  }

  /**
   * Phase 1 criterion 3 (fallback-skip): when a late onFill IS buffered (lastFillEvent != null) the
   * existing success block books it and returns BEFORE the timeout branch — so getOrderStatus is
   * NEVER called and the fill is booked exactly once. Proves the {@code terminalFill == null &&
   * lastFillEvent == null} guard (guardrail #4) avoids a wasted round-trip / a second surfacing.
   */
  @Test
  void flattenTimeout_onFillBuffered_skipsGetOrderStatusFallback() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-flatten-onfill-skip");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> bounded flatten placed -> a normal onFill drains it via the success block.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-onfill-skip");

    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(1);
    assertThat(asLong(fills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);
    // The success block handled the fill; the timeout branch (and its getOrderStatus fallback) was
    // never reached.
    verify(exec, never()).getOrderStatus(anyString());
    assertThat(captureAll("EodForceFlattenFailed")).isEmpty();
  }

  /**
   * Phase 1 criterion 4 (old safety preserved): a GENUINELY unfilled rest — both {@code
   * cancelOrder} and {@code getOrderStatus} report no fill. The workflow must emit exactly one
   * EodForceFlattenFailed, set flattenAwaitingLateFill, leave remainingQty unchanged, and stay
   * ALIVE (flattenRemaining returns false, no PositionClosed) — identical to the pre-Phase-1
   * behavior.
   */
  @Test
  void flattenTimeout_genuinelyUnfilled_staysAliveNoReconcile() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult()); // no fill
    when(exec.getOrderStatus(anyString())).thenReturn(submittedResult()); // no fill
    // Far-out next open so the retry timer cannot fire and re-place during the assertion window.
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(12));

    PositionWorkflow stub = newStub("pos-flatten-genuine-timeout");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the genuinely-unfilled stay-ALIVE path is exercised, not the
    // worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(2));
    waitForPlaceOrderCount(1);

    // Still RUNNING, remainingQty unchanged, exactly one loud failure audit, no PositionClosed and
    // no fill booked.
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    AuditEvent failed = captureKind("EodForceFlattenFailed");
    assertThat(failed.getSubject())
        .containsEntry("note", "bounded_flatten_unfilled_workflow_stays_alive");
    assertThat(captureAll("EodForceFlattenFailed")).hasSize(1);
    assertThat(captureAll("PositionClosed")).isEmpty();
    assertThat(captureAll("PartialExitFilled")).isEmpty();
  }

  /**
   * Phase 1 criterion 5 (the #481 retry loop reconciles first): the first flatten attempt rests
   * unfilled (EodForceFlattenFailed), then the next-session timer wakes with lastFillEvent == null
   * but the RESTING order has ALREADY filled at the broker. The retry loop must reconcile broker
   * truth via getOrderStatus BEFORE spending a retry: book the fill and CLOSE — WITHOUT emitting
   * FlattenRetryScheduled and WITHOUT re-placing a new flatten order (no naked-short second SELL).
   */
  @Test
  void retryLoop_nextSessionWakeup_restingOrderFilledAtBroker_reconcilesWithoutRetry()
      throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // First timeout: cancel reports no fill (genuine unfilled) so the loop stays alive and arms the
    // next-session retry timer.
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());
    // getOrderStatus is called TWICE: (1) the timeout-branch fallback at the FIRST attempt — must
    // report NO fill so that attempt genuinely fails into the retry loop; (2) the retry-loop
    // reconcile at the next-session wakeup — reports FILLED (5 @ 2.46), the resting order whose
    // onFill was lost. Sequence the mock so the first call is unfilled and every later call is
    // filled.
    when(exec.getOrderStatus(anyString()))
        .thenReturn(submittedResult())
        .thenReturn(filledCancelResult(5L, new BigDecimal("2.46")));

    PositionWorkflow stub = newStub("pos-flatten-retry-reconcile");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the next-session reconcile path is exercised, not the
    // worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // EOD fires -> flatten placed (#1) -> TTL elapses; cancel + getOrderStatus both no-fill at
    // timeout -> loud failure + arm the next-session retry timer.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    captureKind("EodForceFlattenFailed");

    // Advance past the next-session open. The retry-loop reconcile now finds the resting order
    // FILLED and books it -> close WITHOUT a retry.
    env.sleep(Duration.ofMinutes(6));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-retry-reconcile");

    // Exactly the ORIGINAL placement — no re-placed flatten order (no second live SELL).
    verify(exec, times(1)).placeOrder(any());
    assertThat(captureAll("FlattenRetryScheduled")).isEmpty();
    // The stuck lot self-healed from broker truth.
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(1);
    assertThat(asLong(fills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);
    assertThat(captureAll("PositionClosed")).hasSize(1);
  }

  /**
   * Review fix (PLAN-2026-06-30) — PARTIAL reconcile in flattenRemaining's TTL-timeout branch. The
   * bounded flatten of a 5-lot rests, its TTL elapses with NO onFill, and the cancel surfaces a
   * PARTIAL broker fill (2 of 5). The timeout branch must book exactly ONE PartialExitFilled (qty
   * 2), decrement remainingQty 5 -> 3, return false, and stay ALIVE — NO PositionClosed, NO
   * negative remainingQty. (Pre-fix this branch booked the raw cumulative qty as "first-and-only
   * booking of this key"; the shared ledger now records it so the retry loop cannot re-book it.)
   */
  @Test
  void flattenTimeout_partialCancelFill_booksDeltaOnce_staysAlive() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // Cancel surfaces a PARTIAL broker fill: 2 of the 5-lot filled before cancel.
    when(exec.cancelOrder(anyString())).thenReturn(filledCancelResult(2L, new BigDecimal("1.90")));
    // Keep the next open far out so the retry timer cannot fire during the assertion window — the
    // workflow rests on the residual 3 alive-await, letting us observe the partial-only state.
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(12));

    PositionWorkflow stub = newStub("pos-flatten-partial-alive");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the partial-cancel stay-ALIVE path is exercised, not the
    // worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // Let the partial reconcile settle, then confirm the residual keeps the workflow alive at 3.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline && stub.positionState().remainingQty() == 5L) {
      Thread.sleep(50);
    }
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
    assertThat(stub.positionState().remainingQty()).isGreaterThanOrEqualTo(0L);

    // Exactly ONE PartialExitFilled (qty 2) — no double-book; NO terminal close.
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(1);
    assertThat(asLong(fills.get(0).getSubject().get("qty_filled"))).isEqualTo(2L);
    assertThat(captureAll("PositionClosed")).isEmpty();
    assertThat(captureAll("EodForceFlattened")).isEmpty();
  }

  /**
   * Review fix (PLAN-2026-06-30) — the retry loop polls the SAME key flattenRemaining already
   * booked a partial against; it must NOT double-book, and it must ADVANCE (not stall) when the
   * resting order is terminal-but-fully-accounted. Sequence: flatten #1 (K0) times out, cancel
   * surfaces a PARTIAL 2-of-5 (booked once, remaining 5 -> 3). Every next-session wakeup then polls
   * the resting key via getOrderStatus and sees the SAME cumulative 2 -> delta == 0 -> books
   * NOTHING (no second PartialExitFilled), but because a residual (3) remains it falls through to a
   * genuine retry (flattenRetrySessions++, fresh :retry-N placement). Retries never fill here, so
   * the budget is exhausted deterministically -> FlattenRetryExhausted. Asserts: total booked ==
   * total broker filled (2, no double-count), remainingQty pinned at 3 (never negative), the loop
   * advanced (FlattenRetryScheduled + a re-placement), and no PositionClosed with a residual.
   */
  @Test
  void retryLoop_pollsSameKeyAsFlattenPartial_noDoubleBook_advancesToExhaustion() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // First cancel (K0 timeout) surfaces the PARTIAL 2-of-5; every later cancel (the :retry-N
    // timeouts) surfaces NO fill so those retries genuinely rest unfilled.
    when(exec.cancelOrder(anyString()))
        .thenReturn(filledCancelResult(2L, new BigDecimal("1.90")))
        .thenReturn(cancelledResult());
    // getOrderStatus: the FIRST call is the retry-loop reconcile of K0 — it reports the SAME
    // cumulative 2 (delta == 0 -> no re-book). Every later call (retry-timeout fallbacks +
    // subsequent reconciles) reports NO fill so the residual never resolves and the budget
    // exhausts.
    when(exec.getOrderStatus(anyString()))
        .thenReturn(filledCancelResult(2L, new BigDecimal("1.90")))
        .thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-flatten-partial-retry");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the same-key no-double-book retry path is exercised, not the
    // worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // Drive well past MAX_FLATTEN_RETRY_SESSIONS worth of next-session wakeups (5-min opens).
    env.sleep(Duration.ofMinutes(40));

    // Wait for the exhaustion audit to land (budget spent, falls to the alive-await).
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline && captureAll("FlattenRetryExhausted").isEmpty()) {
      Thread.sleep(50);
    }

    // No double-book: exactly ONE PartialExitFilled, qty 2 == the total broker fill.
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(1);
    assertThat(asLong(fills.get(0).getSubject().get("qty_filled"))).isEqualTo(2L);
    // remainingQty pinned at the residual 3 the whole time — never driven negative by a re-book.
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
    // The loop ADVANCED rather than polling K0 forever: it scheduled retries and re-placed fresh
    // orders (Blocker B fix), and eventually exhausted the budget.
    assertThat(captureAll("FlattenRetryScheduled")).isNotEmpty();
    verify(exec, atLeast(2)).placeOrder(any());
    assertThat(captureAll("FlattenRetryExhausted")).hasSize(1);
    // Never closed with a residual outstanding (R-AA-1 invariant preserved).
    assertThat(captureAll("PositionClosed")).isEmpty();
  }

  /**
   * Review fix (PLAN-2026-06-30) — the residual re-placed by the retry loop eventually fills via
   * broker truth, closing the lot with the CORRECT total booked (no double-count). flatten #1 (K0)
   * times out with a PARTIAL 2-of-5 (booked once, remaining 3). The next-session reconcile of K0
   * sees cumulative 2 -> delta 0 -> falls through to a fresh :retry-1 placement (K1). That K1
   * flatten times out with its cancel surfacing the residual 3 filled -> booked once against the
   * NEW key (ledger reset on the key roll) -> remaining 3 -> 0 -> exactly ONE PositionClosed. Total
   * booked == 5 == total broker filled (2 + 3), remainingQty never negative.
   */
  @Test
  void retryLoop_residualReplacedAndFilled_closesWithCorrectTotal() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // Cancel #1 (K0 timeout): PARTIAL 2-of-5. Cancel #2 (K1/:retry-1 timeout): the residual 3
    // fills.
    // #735: K0 and K1 are two DISTINCT broker orders, so they carry distinct broker order ids.
    // K0 partially fills 2-of-5; the residual 3 is filled by the fresh :retry-1 order K1, whose
    // OWN cumulative is 3.
    when(exec.cancelOrder(anyString()))
        .thenReturn(filledCancelResult(2L, new BigDecimal("1.90"), "brk-k0"))
        .thenReturn(filledCancelResult(3L, new BigDecimal("1.85"), "brk-k1"));
    // Only getOrderStatus call is the retry-loop reconcile of K0 -> same cumulative 2 (delta 0).
    when(exec.getOrderStatus(anyString()))
        .thenReturn(filledCancelResult(2L, new BigDecimal("1.90"), "brk-k0"));

    PositionWorkflow stub = newStub("pos-flatten-residual-fill");
    PositionWorkflowInput in = input(5);
    // Phase 2: NOT-yet-expired OCC so the residual-replace-and-fill retry path is exercised, not
    // the
    // worthless-close (which is expiry-date gated).
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    // Advance past the next-session open so the retry loop reconciles K0 (delta 0) and re-places
    // K1.
    env.sleep(Duration.ofMinutes(6));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-residual-fill");

    // Two PartialExitFilled rows (2 then 3); total booked == 5 == total broker filled (no
    // double-count).
    List<AuditEvent> fills = captureAll("PartialExitFilled");
    assertThat(fills).hasSize(2);
    assertThat(fills.stream().mapToLong(e -> asLong(e.getSubject().get("qty_filled"))).sum())
        .isEqualTo(5L);
    // Exactly one terminal close, gated on remainingQty == 0.
    List<AuditEvent> closed = captureAll("PositionClosed");
    assertThat(closed).hasSize(1);
    assertThat(asLong(closed.get(0).getSubject().get("remaining_qty"))).isEqualTo(0L);
    // A fresh retry order was re-placed for the residual (the loop advanced past the delta-0 poll).
    assertThat(captureAll("FlattenRetryScheduled")).isNotEmpty();
    verify(exec, atLeast(2)).placeOrder(any());
  }

  /**
   * Plan-2A R-AA-3: a CHANDELIER_TRAIL flatten is BOUNDED — it places a marketable LIMIT
   * (limitPrice != null) anchored at/through the live bid and at/above the resolved exit floor.
   * (eod/expiry bounded-limit pricing is covered by {@link
   * #expiryTimer_configuredForceClose0dte_drivesBoundedLimitFlatten()}; the immediacy MARKET
   * pricing by {@link #forceClose_healthyPosition_acceptsAndFlattens()} / {@link
   * #riskBreach_healthyPosition_flattens()}.)
   */
  @Test
  void chandelierFlatten_isBoundedLimit_notMarket() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-chandelier-bounded");
    PositionWorkflowInput in = futureInput(5);
    in.setExitFloorAbs(new BigDecimal("0.50"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // peak=3.00 gb=0.10 -> threshold 2.70; tick 2.70 fires the trail.
    stub.armChandelier(
        armPayload(
            "pos-chandelier-bounded", "src-1", new BigDecimal("3.00"), new BigDecimal("0.10")));
    stub.chandelierTick(tick(new BigDecimal("2.70")));

    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNotNull();
    // Anchored on the live bid (2.50), at/above the floor (0.50).
    assertThat(flatten.getLimitPrice()).isGreaterThanOrEqualTo(new BigDecimal("0.50"));
    assertThat(flatten.getLimitPrice()).isLessThanOrEqualTo(new BigDecimal("2.50"));
  }

  /**
   * Plan-2A R-AA-3 quote FAIL-SAFE: when GetOptionQuoteActivity returns status=FAILED on a
   * scheduled flatten, the bounded path FAILS SAFE to a MARKETABLE exit (limitPrice == null) and
   * emits a loud FlattenQuoteUnavailable audit — never a stale ref-premium limit, never "no sell".
   */
  @Test
  void boundedFlatten_quoteFailed_marketableFallbackAndAvailabilityAudit() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(optionQuote.getOptionQuote(any())).thenReturn(quoteFailed("provider 503"));

    PositionWorkflow stub = newStub("pos-eod-quote-failed");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFloorAbs(new BigDecimal("0.50"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent avail = captureKind("FlattenQuoteUnavailable");
    assertThat(avail.getSubject())
        .containsEntry("reason", "eod")
        .containsEntry("note", "marketable_fallback");

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNull(); // marketable, not a stale ref-premium limit
  }

  /**
   * Plan-2A R-AA-3 floor FAIL-SAFE: when the resolved exit floor sits ABOVE the live bid (a floor
   * that high would forbid selling at any executable price), the bounded path FAILS SAFE to a
   * MARKETABLE exit (limitPrice == null) and emits a loud FlattenFloorConfigError audit.
   */
  @Test
  void boundedFlatten_floorAboveBid_marketableFallbackAndConfigErrorAudit() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // live bid 2.50, floor_abs 5.00 -> floor > bid.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("2.50"), new BigDecimal("2.55"), new BigDecimal("2.60")));

    PositionWorkflow stub = newStub("pos-eod-floor-above-bid");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE);
    in.setExitFloorAbs(new BigDecimal("5.00"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent cfg = captureKind("FlattenFloorConfigError");
    assertThat(cfg.getSubject()).containsEntry("note", "floor_above_live_bid_marketable_fallback");

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNull();
  }

  /**
   * Plan-2A R-AA-3 expiry-session collapse: on the expiry path with NO live bid (bid &lt;= 0) the
   * flatten goes FULLY MARKETABLE (limitPrice == null) — a no-bid contract expires worthless and is
   * out of scope of the bounded-limit guarantee (we do NOT rest a $0.01 limit that never fills).
   */
  @Test
  void boundedFlatten_expirySessionNoLiveBid_fullyMarketable() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ofMillis(200));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // bid = 0 -> no live bid on the expiry session.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("0.00"), new BigDecimal("0.01"), new BigDecimal("0.02")));

    PositionWorkflow stub = newStub("pos-expiry-no-bid");
    PositionWorkflowInput in = input(3);
    in.setExpiryDayFloor(new BigDecimal("0.05"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 3L, new BigDecimal("0.01")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    captureKind("ExpiryForceFlattened");
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNull();
  }

  // ---------- Issue #204: bounded exit-fill await in processOne() ----------

  /**
   * Issue #204 Done-when 1: when an exit order is placed and the fill never arrives within the
   * EXIT_FILL_TTL_SECS bound, the workflow must:
   *
   * <ol>
   *   <li>emit a {@code PartialExitFillTimeout} audit (subject includes signal_id, broker_order_id,
   *       intent_key, remaining_qty),
   *   <li>best-effort cancel the broker order via {@code exec.cancelOrder(intentKey)},
   *   <li>release the {@code exitInFlight} latch so {@code pendingExits} can drain on the next
   *       workflow tick,
   *   <li>NOT decrement {@code remainingQty} (no fill happened).
   * </ol>
   *
   * <p>The follow-up STC must then drain and fill normally.
   */
  @Test
  void processOne_exitFillTimeout_emitsAuditCancelsAndDrainsNextPending() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-exit-timeout");
    PositionWorkflowInput in = futureInput(5);
    in.setExitRepriceSteps(1L); // R-AB-2: cap at 1 retry → 2 timeouts then drop (legacy shape).
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // First STC: send fraction=0.5 and never deliver the fill.
    stub.partialExit(partialExitRequest("sig-stuck", "pos-exit-timeout", 0.5));
    waitForPlaceOrderCount(1);

    // Advance virtual time past the 90s exit-fill TTL — the workflow must time out, audit, cancel,
    // and (Issue #216) re-place a retry order with a fresh limit price + intent_key.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);

    // Advance past the retry's TTL too — second timeout drops the STC permanently and releases
    // the in-flight latch so pendingExits can drain.
    env.sleep(Duration.ofSeconds(120));

    // Second STC arrives after the retry was also dropped: with the latch released, processOne
    // must drain it and fill it normally.
    stub.partialExit(partialExitRequest("sig-follow", "pos-exit-timeout", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-follow", 5L, new BigDecimal("2.90")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Two timeouts — original and retry — for the stuck STC.
    List<AuditEvent> timeouts = captureAll("PartialExitFillTimeout");
    assertThat(timeouts).hasSize(2);
    assertThat(timeouts.get(0).getSubject())
        .containsEntry("signal_id", "sig-stuck")
        .containsEntry("broker_order_id", "brk-exit");
    assertThat(asLong(timeouts.get(0).getSubject().get("remaining_qty"))).isEqualTo(5L);

    // Cancel was attempted on both the original and the retry order.
    verify(exec, atLeastOnce()).cancelOrder(anyString());

    // The follow-up STC actually filled — partial-exit audit reflects 5 contracts closed.
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(1);
    assertThat(partialFills.get(0).getSubject()).containsEntry("signal_id", "sig-follow");
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);
  }

  /**
   * Issue #204: when cancelOrder throws (broker already filled/rejected the working order), the
   * timeout-handler must swallow the exception and still release the latch + audit the timeout.
   * Reconciliation closes the loop on the broker-side state.
   *
   * <p>Uses a non-retryable {@link ApplicationFailure} so the activity does not retry — the catch
   * block in the workflow body must absorb it and continue.
   */
  @Test
  void processOne_exitFillTimeout_swallowsCancelFailureAndStillReleasesLatch() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "broker rejected cancel — already filled", "BrokerCancelRejected"));

    PositionWorkflow stub = newStub("pos-exit-timeout-cancelfail");
    PositionWorkflowInput in = futureInput(3);
    in.setExitRepriceSteps(1L); // R-AB-2: cap at 1 retry → 2 timeouts then drop (legacy shape).
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    stub.partialExit(partialExitRequest("sig-stuck", "pos-exit-timeout-cancelfail", 0.5));
    waitForPlaceOrderCount(1);

    // First timeout — retry fires under Issue #216 v=1 even though cancel throws.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    // Second timeout (the retry) — drops the STC permanently, releases the latch.
    env.sleep(Duration.ofSeconds(120));

    // Follow-up STC drains — proving the latch was released even though cancel threw.
    stub.partialExit(partialExitRequest("sig-follow", "pos-exit-timeout-cancelfail", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-follow", 3L, new BigDecimal("3.00")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    captureKind("PartialExitFillTimeout");
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(1);
    assertThat(partialFills.get(0).getSubject()).containsEntry("signal_id", "sig-follow");
  }

  // ---------- Phase 1 (PLAN-2026-06-25-trading-remediation): partial-target place-failure
  // retry-next-session ----------

  /**
   * Phase 1 headline (the 2026-06-25 QQQ 720p 09:43 incident reproduction): a partial-target exit
   * whose {@code placeOrder} retries are exhausted must NOT be silently dropped. Under {@code
   * VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION} v&gt;=1 the place-failure catch (a) still emits the
   * paging {@code PartialExitPlaceFailed}, (b) leaves {@code remainingQty} intact (nothing was
   * sold), and (c) arms a one-shot timer to the NEXT RTH open; on the timer firing the partial is
   * re-driven through the normal {@code processOne} cycle with a fresh {@code :retry-1}-suffixed
   * intent key (no duplicate client_order_id), emitting the informational {@code
   * PartialExitRetryRequested} marker. TestWorkflowEnvironment resolves getVersion to the max (1),
   * so this exercises the v&gt;=1 retry path.
   */
  @Test
  void partialExit_placeOrderThrows_v1_schedulesRetryNextSession() throws Exception {
    // Next-session open is 5 minutes out (virtual time); env.sleep past it fires the re-drive
    // timer.
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    // First placeOrder (the partial) throws; the next-session re-drive succeeds (submitted) so the
    // re-attempt can fill and complete normally.
    when(exec.placeOrder(any()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "Alpaca PlaceOrder rejected (422): client_order_id must be unique",
                "InvalidRequestError"))
        .thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-partial-retry");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // Partial target STC: first placeOrder throws -> PartialExitPlaceFailed page, lot intact.
    stub.partialExit(partialExitRequest("sig-qqq", "pos-partial-retry", 0.5));
    waitForPlaceOrderCount(1);
    // A query settles the workflow so the post-catch audit command is committed before we capture.
    // remainingQty NOT decremented (the failed partial sold nothing).
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    AuditEvent placeFailed = captureKind("PartialExitPlaceFailed");
    assertThat(placeFailed.getSubject()).containsEntry("signal_id", "sig-qqq");

    // Advance past the next-session open -> re-drive timer fires -> PartialExitRetryRequested +
    // a second placeOrder with a :retry-1 intent key.
    env.sleep(Duration.ofMinutes(6));
    waitForPlaceOrderCount(2);
    // Query barrier: settle the workflow so the re-drive's audit command is committed.
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    AuditEvent retried = captureKind("PartialExitRetryRequested");
    assertThat(retried.getSubject())
        .containsEntry("signal_id", "sig-qqq")
        .containsEntry("retry_attempt", 1);

    // The re-driven partial MUST use a DISTINCT :retry-1 intent key (fresh client_order_id) — the
    // first attempt kept the un-suffixed key. Reusing it would re-POST a duplicate cid.
    ArgumentCaptor<OrderIntent> intents = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(2)).placeOrder(intents.capture());
    List<String> keys = intents.getAllValues().stream().map(OrderIntent::getIntentKey).toList();
    assertThat(keys).hasSize(2).doesNotHaveDuplicates();
    assertThat(keys).contains("pos-partial-retry:exit:sig-qqq");
    assertThat(keys).contains("pos-partial-retry:exit:sig-qqq:retry-1");

    // The re-driven partial fills; no terminal-exhausted page was needed.
    stub.onFill(fill("brk-retry", 3L, new BigDecimal("3.00")));
    assertThat(captureAll("PartialExitRetryExhausted")).isEmpty();
  }

  /**
   * Phase 1 bound + exhaustion: after {@code MAX_PARTIAL_PLACE_RETRY_SESSIONS} (1) next-session
   * re-drives still failing to place, the workflow emits the terminal paging {@code
   * PartialExitRetryExhausted}, stops re-driving (no third placeOrder), and the lot remains managed
   * and free for a later STC — exactly how the real QQQ lot still cleanly closed via a later
   * flatten.
   */
  @Test
  void partialExit_placeRetry_boundedAndExhausts_pagesTerminal() throws Exception {
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofMinutes(5));
    // Both the original partial AND its single next-session re-drive throw; a later STC succeeds.
    when(exec.placeOrder(any()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "Alpaca PlaceOrder rejected (422)", "InvalidRequestError"))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "Alpaca PlaceOrder rejected (422)", "InvalidRequestError"))
        .thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-partial-exhaust");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // Original partial fails (place #1) -> page + schedule re-drive.
    stub.partialExit(partialExitRequest("sig-qqq", "pos-partial-exhaust", 0.5));
    waitForPlaceOrderCount(1);
    // The post-catch PartialExitPlaceFailed audit is emitted via an async audit.log() activity
    // AFTER
    // the placeOrder throw, so the positionState query barrier alone doesn't guarantee Mockito has
    // recorded that invocation — a bare captureKind here races under CI parallel load. Poll for it
    // deterministically (same fix as #530), then the state assertion.
    waitForAuditKind("PartialExitPlaceFailed");
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);

    // Next session: the single budgeted re-drive (place #2) also fails -> budget spent -> terminal
    // PartialExitRetryExhausted page, NO further re-drive.
    env.sleep(Duration.ofMinutes(6));
    waitForPlaceOrderCount(2);
    // Same async-audit race as the first place failure: poll for the terminal exhausted audit
    // before capturing it, rather than relying on the positionState query barrier alone.
    waitForAuditKind("PartialExitRetryExhausted");
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    AuditEvent exhausted = captureKind("PartialExitRetryExhausted");
    assertThat(exhausted.getSubject())
        .containsEntry("signal_id", "sig-qqq")
        .containsEntry("attempts", 1)
        .containsEntry("option_symbol", FUTURE_OCC_SYMBOL);

    // Bounded: no third placeOrder for the partial. Sleep generously to prove no further re-drive.
    env.sleep(Duration.ofMinutes(30));
    AuditEvent secondPlaceFailed = captureKind("PartialExitPlaceFailed");
    // Still exactly one PartialExitRetryExhausted; no runaway re-driving.
    assertThat(captureAll("PartialExitRetryExhausted")).hasSize(1);
    assertThat(secondPlaceFailed).isNotNull();

    // The lot stays managed at the unchanged qty and a later STC drains it (place #3 succeeds).
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
    stub.partialExit(partialExitRequest("sig-later", "pos-partial-exhaust", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-later", 5L, new BigDecimal("2.90")));
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-partial-exhaust");
  }

  // ---------- B2 (PLAN-exit-place-duplicate-422-crash): exit-place-failure guard ----------

  /**
   * B2 headline: a non-retryable exit {@code placeOrder} failure (e.g. the
   * duplicate-client_order_id 422 misclassified as {@code InvalidRequestError}) MUST NOT fail the
   * PositionWorkflow and orphan the live lot. Under {@code VERSION_EXIT_PLACE_FAILURE_GUARD}
   * v&gt;=1 the catch emits {@code PartialExitPlaceFailed}, releases the in-flight latch, and
   * {@code return;}s out of processOne WITHOUT decrementing remainingQty. The workflow stays alive
   * + the lot stays managed (proven by a follow-up STC that drains and fills normally, completing
   * the run at EOD). Stronger than "not FAILED": the signal-handler turn completes, the position
   * stays queryable at the unchanged qty, and the workflow's @WorkflowMethod returns normally.
   */
  @Test
  void exitPlaceFailure_keepsWorkflowAliveManagedAndAuditsPlaceFailed_b2() throws Exception {
    // First placeOrder (the exit) throws a non-retryable ApplicationFailure; subsequent
    // placeOrder calls (the follow-up STC) succeed — proving the lot is still managed.
    when(exec.placeOrder(any()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "Alpaca rejected order (422, non-duplicate): client_order_id must be unique",
                "InvalidRequestError"))
        .thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-exit-place-fail");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // First STC: placeOrder throws. The catch must audit + release the latch + return — NOT crash.
    stub.partialExit(partialExitRequest("sig-fail", "pos-exit-place-fail", 0.5));
    waitForPlaceOrderCount(1);

    // The signal-handler turn completed and the workflow is still alive + queryable: the position
    // is still managed at the UNCHANGED qty (nothing was sold).
    PositionState afterFailure = stub.positionState();
    assertThat(afterFailure.remainingQty()).isEqualTo(5L);
    assertThat(afterFailure.contractSymbol()).isEqualTo(FUTURE_OCC_SYMBOL);

    // Follow-up STC drains (proving the in-flight latch was released) and fills normally.
    stub.partialExit(partialExitRequest("sig-ok", "pos-exit-place-fail", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-ok", 5L, new BigDecimal("3.00")));

    // The workflow completes its run normally (it never FAILED).
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-exit-place-fail");

    // PartialExitPlaceFailed audit was emitted carrying the diagnostic subject.
    AuditEvent placeFailed = captureKind("PartialExitPlaceFailed");
    assertThat(placeFailed.getSubject())
        .containsEntry("signal_id", "sig-fail")
        .containsEntry("option_symbol", FUTURE_OCC_SYMBOL);
    assertThat(asLong(placeFailed.getSubject().get("qty"))).isEqualTo(3L);
    assertThat(placeFailed.getSubject())
        .containsEntry("intent_key", "pos-exit-place-fail:exit:sig-fail");
    // The error message is the Temporal ActivityFailure message (mirrors flattenRemaining's
    // EodForceFlattenFailed convention of e.getMessage()); it identifies the failed activity.
    assertThat(String.valueOf(placeFailed.getSubject().get("error"))).contains("PlaceOrder");

    // remainingQty was NOT decremented by the failed placement: the failed STC sold nothing, the
    // successful follow-up sold all 5.
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(1);
    assertThat(partialFills.get(0).getSubject()).containsEntry("signal_id", "sig-ok");
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);
  }

  /**
   * PLAN-over-exit-422: a partial-exit whose placeOrder returns a BENIGN broker-confirmed
   * already-flat outcome (state=CANCELLED) must be treated as already-closed — NOT a
   * PartialExitPlaceFailed page. The workflow emits the visible non-paging PartialExitAlreadyFlat
   * audit carrying remaining_qty_before, zeroes remainingQty from broker truth, and completes the
   * run normally (PositionClosed). Because the lot still showed qty (nothing was sold by this STC),
   * remaining_qty_before>0 — the divergence tripwire (WARN + metric) gated on the SAME branch.
   */
  @Test
  void partialExitBenignAlreadyFlat_emitsAlreadyFlat_zeroesQty_noPlaceFailed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(alreadyClosedResult());

    PositionWorkflow stub = newStub("pos-over-exit-flat");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    stub.partialExit(partialExitRequest("sig-flat", "pos-over-exit-flat", 0.5));
    waitForPlaceOrderCount(1);

    // The run completes normally (it never FAILED, never wedged in the fill-await of a never-placed
    // order).
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-over-exit-flat");

    // The benign already-flat audit was emitted with remaining_qty_before = the (unsold) lot qty.
    AuditEvent alreadyFlat = captureKind("PartialExitAlreadyFlat");
    assertThat(alreadyFlat.getSubject())
        .containsEntry("signal_id", "sig-flat")
        .containsEntry("option_symbol", FUTURE_OCC_SYMBOL)
        .containsEntry("intent_key", "pos-over-exit-flat:exit:sig-flat");
    // remaining_qty_before>0 → the divergence WARN+metric branch fired (same gate as this value).
    assertThat(asLong(alreadyFlat.getSubject().get("remaining_qty_before"))).isEqualTo(5L);

    // NOT a failure: no PartialExitPlaceFailed page, and no PartialExitFilled (nothing was sold).
    assertThat(captureAll("PartialExitPlaceFailed")).isEmpty();
    assertThat(captureAll("PartialExitFilled")).isEmpty();
  }

  /**
   * PLAN-over-exit-422 regression: a genuine non-duplicate 422 (an over-exit signature that the
   * broker adapter did NOT confirm flat, so it surfaces as a non-retryable placeOrder FAILURE) must
   * STILL route to PartialExitPlaceFailed — the benign path only fires on a state=CANCELLED RETURN,
   * never on a thrown failure.
   */
  @Test
  void partialExitGenuine422Failure_stillEmitsPlaceFailed_notAlreadyFlat() throws Exception {
    when(exec.placeOrder(any()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "Alpaca rejected order (422, non-duplicate): bad request", "InvalidRequestError"));

    PositionWorkflow stub = newStub("pos-genuine-422");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    stub.partialExit(partialExitRequest("sig-422", "pos-genuine-422", 0.5));
    waitForPlaceOrderCount(1);

    // Position stays managed at the unchanged qty (the failure path does not zero remainingQty).
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);

    captureKind("PartialExitPlaceFailed");
    assertThat(captureAll("PartialExitAlreadyFlat")).isEmpty();
  }

  /**
   * PLAN-over-exit-422: a scheduled flatten (EOD here) whose placeOrder returns a BENIGN
   * broker-confirmed already-flat outcome (state=CANCELLED) sets remainingQty=0 and EXITS the
   * alive-loop — a flatten on an already-flat lot is satisfied, not a failure. The run completes
   * normally and emits PartialExitAlreadyFlat (NOT EodForceFlattenFailed).
   */
  @Test
  void flattenBenignAlreadyFlat_zeroesQty_exitsAliveLoop_noFailure() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofSeconds(30));
    when(exec.placeOrder(any())).thenReturn(alreadyClosedResult());

    PositionWorkflow stub = newStub("pos-flatten-flat");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);

    // The run completes normally — the flatten exited the alive-loop on broker-confirmed flat
    // without ever awaiting a fill of a never-placed order.
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-flatten-flat");

    AuditEvent alreadyFlat = captureKind("PartialExitAlreadyFlat");
    assertThat(asLong(alreadyFlat.getSubject().get("remaining_qty_before"))).isEqualTo(5L);
    assertThat(captureAll("EodForceFlattenFailed")).isEmpty();
  }

  // ---------- Phase 4: CHANDELIER_TRAIL ----------

  @Test
  void armChandelier_validInput_armsAndAuditsChandelierArmed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-valid");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    stub.armChandelier(
        armPayload("pos-arm-valid", "src-sig-1", new BigDecimal("2.85"), new BigDecimal("0.15")));

    // Drain to completion so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-close", "pos-arm-valid", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 5L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent armed = captureKind("ChandelierArmed");
    assertThat(armed.getSubject())
        .containsEntry("source_signal_id", "src-sig-1")
        .containsEntry("subscription_id", "sub-test");
    assertThat(((Number) armed.getSubject().get("peak_premium")).doubleValue()).isEqualTo(2.85);
    assertThat(((Number) armed.getSubject().get("giveback_pct")).doubleValue()).isEqualTo(0.15);
  }

  @Test
  void armChandelier_invalidPeak_rejectsAndAuditsArmRejected() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-bad-peak");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.armChandelier(armPayload("pos-arm-bad-peak", "src-sig-bp", null, new BigDecimal("0.15")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-bad-peak", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 3L, new BigDecimal("3.00")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent rej = captureKind("ChandelierArmRejected");
    assertThat(rej.getSubject()).containsEntry("reason", "invalid_peak");
  }

  @Test
  void armChandelier_invalidGiveback_rejectsAndAuditsArmRejected() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-bad-gb");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.armChandelier(
        armPayload("pos-arm-bad-gb", "src-sig-bg", new BigDecimal("2.85"), new BigDecimal("0.60")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-bad-gb", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 3L, new BigDecimal("3.00")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent rej = captureKind("ChandelierArmRejected");
    assertThat(rej.getSubject()).containsEntry("reason", "invalid_giveback");
  }

  @Test
  void armChandelier_subscribeReturnsFailed_auditsSubscriptionFailedAndStaysUnarmed()
      throws Exception {
    when(marketData.subscribePremium(any())).thenReturn(failedSubscription("upstream down"));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-subfail");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.armChandelier(
        armPayload(
            "pos-arm-subfail", "src-sig-sf", new BigDecimal("2.85"), new BigDecimal("0.15")));

    // A subsequent tick must NOT fire (workflow not armed).
    stub.chandelierTick(tick(new BigDecimal("2.40")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-subfail", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 3L, new BigDecimal("3.00")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent failed = captureKind("ChandelierSubscriptionFailed");
    assertThat(failed.getSubject())
        .containsEntry("source_signal_id", "src-sig-sf")
        .containsEntry("error", "upstream down");

    // No fire event.
    assertThat(captureAll("ChandelierTrailFired")).isEmpty();
  }

  @Test
  void armChandelier_secondArm_isNoOp() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-second");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.armChandelier(
        armPayload("pos-arm-second", "src-sig-A", new BigDecimal("2.85"), new BigDecimal("0.15")));
    stub.armChandelier(
        armPayload("pos-arm-second", "src-sig-B", new BigDecimal("3.10"), new BigDecimal("0.10")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-second", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 3L, new BigDecimal("3.00")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Only one ChandelierArmed audit emitted.
    assertThat(captureAll("ChandelierArmed")).hasSize(1);
  }

  @Test
  void chandelierTick_beforeArm_ignored() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-before-arm");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.chandelierTick(tick(new BigDecimal("1.00")));

    stub.partialExit(partialExitRequest("sig-close", "pos-tick-before-arm", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 3L, new BigDecimal("3.00")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    assertThat(captureAll("ChandelierTrailFired")).isEmpty();
  }

  @Test
  void chandelierTick_belowPeakAboveThreshold_noFire() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-near-no-fire");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // peak=3.00, gb=0.15 -> threshold = 3.00 * 0.85 = 2.55
    stub.armChandelier(
        armPayload(
            "pos-tick-near-no-fire", "src-sig-1", new BigDecimal("3.00"), new BigDecimal("0.15")));
    // tick=2.60 -> 2.60 > 2.55, no fire.
    stub.chandelierTick(tick(new BigDecimal("2.60")));

    stub.partialExit(partialExitRequest("sig-close", "pos-tick-near-no-fire", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 5L, new BigDecimal("2.85")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    assertThat(captureAll("ChandelierTrailFired")).isEmpty();
  }

  @Test
  void chandelierTick_tickAtExactThreshold_fires() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-exact-threshold");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // peak=3.00, gb=0.10 -> threshold = 2.70
    stub.armChandelier(
        armPayload(
            "pos-tick-exact-threshold",
            "src-sig-1",
            new BigDecimal("3.00"),
            new BigDecimal("0.10")));
    // tick=2.70 -> tick <= threshold fires.
    stub.chandelierTick(tick(new BigDecimal("2.70")));

    // Workflow auto-flattens on fire; wait for the bounded flatten placeOrder then deliver its fill
    // (Plan-2A R-AA-1: remainingQty zeroed only on the fill).
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent fired = captureKind("ChandelierTrailFired");
    assertThat(((Number) fired.getSubject().get("threshold")).doubleValue()).isEqualTo(2.70);
    assertThat(((Number) fired.getSubject().get("trigger_premium")).doubleValue()).isEqualTo(2.70);
  }

  @Test
  void chandelierTick_risingThenGivebackBreach_firesFlattenRemaining() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-rising");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    stub.armChandelier(
        armPayload("pos-tick-rising", "src-sig-1", new BigDecimal("3.00"), new BigDecimal("0.15")));
    // Ratchet to 4.00 -> threshold = 4.00 * 0.85 = 3.40
    stub.chandelierTick(tick(new BigDecimal("4.00")));
    // 3.30 <= 3.40 -> fire
    stub.chandelierTick(tick(new BigDecimal("3.30")));

    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent fired = captureKind("ChandelierTrailFired");
    assertThat(((Number) fired.getSubject().get("peak_premium")).doubleValue()).isEqualTo(4.00);
    assertThat(((Number) fired.getSubject().get("threshold")).doubleValue()).isEqualTo(3.40);
    assertThat(((Number) fired.getSubject().get("trigger_premium")).doubleValue()).isEqualTo(3.30);
    assertThat(asLong(fired.getSubject().get("remaining_qty"))).isEqualTo(5L);
  }

  @Test
  void chandelierUnarmedByExit_normalStcCompletes_emitsAuditWhenArmed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-unarmed-stc");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.armChandelier(
        armPayload("pos-unarmed-stc", "src-sig-1", new BigDecimal("2.85"), new BigDecimal("0.15")));

    // Drain to remaining=0 via STC (not chandelier).
    stub.partialExit(partialExitRequest("sig-close", "pos-unarmed-stc", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-x", 3L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent unarmed = captureKind("ChandelierUnarmedByExit");
    assertThat(unarmed.getSubject()).containsEntry("reason", "normal_stc");
  }

  // ---------- Phase 5: force_close + risk_breach ----------

  @Test
  void forceClose_healthyPosition_acceptsAndFlattens() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-force-healthy");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    ForceCloseResult result = stub.forceClose(forceCloseRequest("ops-1", "manual intervention"));
    assertThat(result.getStatus()).isEqualTo(ForceCloseResult.Status.ACCEPTED);
    assertThat(result.getExitSignalId()).startsWith("force:ops-1:");

    // force_close keeps exit-NOW (MARKET) but is still fill-awaited under R-AA-1: deliver the fill.
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent req = captureKind("ForceCloseRequested");
    assertThat(req.getSubject())
        .containsEntry("operator_id", "ops-1")
        .containsEntry("reason", "manual intervention");
    // flattenRemaining re-uses EOD audit kinds; the subject's reason disambiguates downstream.
    AuditEvent flatReq = captureKind("EodForceFlattenRequested");
    assertThat(flatReq.getSubject()).containsEntry("reason", "force_close");
    captureKind("EodForceFlattened");
    // force_close keeps the MARKET (immediacy) flatten: the SELL OrderIntent has no limit price.
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNull();
  }

  @Test
  void forceCloseValidator_blankOperatorId_rejects() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-force-blank-op");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    assertThatThrownBy(() -> stub.forceClose(forceCloseRequest("", "reason ok")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("operator_id");

    // Workflow still healthy; drain to clean shutdown.
    stub.partialExit(partialExitRequest("sig-drain", "pos-force-blank-op", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 3L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  @Test
  void forceCloseValidator_blankReason_rejects() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-force-blank-reason");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    assertThatThrownBy(() -> stub.forceClose(forceCloseRequest("ops-2", "")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("reason");

    stub.partialExit(partialExitRequest("sig-drain", "pos-force-blank-reason", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 3L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  // ---------- Operator "Trim" (partial_close Update) ----------

  @Test
  void partialClose_healthyPosition_sellsFractionAtMarketAndKeepsRunner() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-trim-market");
    WorkflowStub.fromTyped(stub).start(futureInput(8));
    confirmEntry(stub, 8L);

    PartialCloseResult result =
        stub.partialClose(partialCloseRequest("ops-1", "trim via /live", 0.25));
    assertThat(result.getStatus()).isEqualTo(PartialCloseResult.Status.ACCEPTED);
    assertThat(result.getExitSignalId()).startsWith("trim:ops-1:");

    // ceil(8 * 0.25) = 2 contracts sold; the other 6 keep running.
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-trim", 2L, new BigDecimal("4.60")));
    waitForAuditKind("PartialExitFilled");

    // Both of these are emitted asynchronously on the partialClose UPDATE path, which returns
    // ACCEPTED before its audits necessarily flush. Waiting for PartialExitFilled above does NOT
    // imply they have landed — they are independent emissions with no ordering guarantee between
    // them, so under CI load the capture could run first. That is exactly how this test failed in
    // CI on 2026-08-17 ("no audit event with kind=OperatorTrimRequested", 0.885s — a race, not a
    // timeout). Same fix the file already applies in seven other places.
    waitForAuditKind("OperatorTrimRequested");
    waitForAuditKind("PartialExitRequested");

    AuditEvent trim = captureKind("OperatorTrimRequested");
    assertThat(trim.getSubject())
        .containsEntry("operator_id", "ops-1")
        .containsEntry("reason", "trim via /live");
    AuditEvent partial = captureKind("PartialExitRequested");
    assertThat(asLong(partial.getSubject().get("qty_to_close"))).isEqualTo(2L);

    // The trim is exit-NOW like force_close: the SELL carries NO limit price (MARKET).
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent sell =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no SELL OrderIntent placed"));
    assertThat(sell.getLimitPrice()).isNull();
    assertThat(sell.getQty()).isEqualTo(2L);

    // Reduce-only: the position is STILL open with the untouched remainder (never a full close).
    assertThat(stub.positionState().remainingQty()).isEqualTo(6L);

    // Drain to a clean shutdown.
    stub.partialExit(partialExitRequest("sig-drain", "pos-trim-market", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-drain", 6L, new BigDecimal("4.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  /**
   * Reduce-only clamp: {@code ceil()} makes 75% of a 3-lot equal 3 — i.e. a "trim" that would
   * FLATTEN the position. The dashboard hides such a preset, but it filters against the qty it
   * RENDERED, so a partial fill between render and click reaches this. The workflow must cut the
   * trim to leave one contract rather than liquidate the lot.
   */
  @Test
  void partialClose_fractionThatWouldFlatten_isClampedToLeaveOneContract() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-trim-clamp");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    // ceil(3 * 0.75) == 3 == the whole lot.
    assertThat(stub.partialClose(partialCloseRequest("ops-1", "trim 75%", 0.75)).getStatus())
        .isEqualTo(PartialCloseResult.Status.ACCEPTED);

    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-trim", 2L, new BigDecimal("4.60")));
    waitForAuditKind("PartialExitFilled");
    // Waiting for PartialExitFilled does not imply OperatorTrimClamped has landed — different
    // kind, emitted on the partialClose UPDATE path, no ordering guarantee between them. Same
    // race that failed partialClose_healthyPosition_... in CI on 2026-08-17.
    waitForAuditKind("OperatorTrimClamped");

    AuditEvent clamped = captureKind("OperatorTrimClamped");
    assertThat(asLong(clamped.getSubject().get("qty_requested"))).isEqualTo(3L);
    assertThat(asLong(clamped.getSubject().get("qty_clamped"))).isEqualTo(2L);

    // 2 sold, NOT 3 — and the position is still open with its runner.
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent sell =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(sell.getQty()).isEqualTo(2L);
    assertThat(stub.positionState().remainingQty()).isEqualTo(1L);

    stub.partialExit(partialExitRequest("sig-drain", "pos-trim-clamp", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-drain", 1L, new BigDecimal("4.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  /**
   * A 1-lot has nothing to trim — every fraction resolves to the whole position — so the trim must
   * place NO order at all. Guards the drained-to-1 race, and overrides {@code
   * min_partial_qty_behavior=full_close}, which flushes the runner for an author-driven STC but
   * must not flatten on a discretionary operator trim.
   */
  @Test
  void partialClose_singleContract_placesNoOrderEvenUnderFullCloseBehavior() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflowInput in = futureInput(1);
    in.setMinPartialQtyBehavior(PositionWorkflowInput.MinPartialQtyBehavior.FULL_CLOSE);
    PositionWorkflow stub = newStub("pos-trim-one-lot");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 1L);

    assertThat(stub.partialClose(partialCloseRequest("ops-1", "trim 50%", 0.5)).getStatus())
        .isEqualTo(PartialCloseResult.Status.ACCEPTED);

    // The Update returns as soon as the directive is BUFFERED; the clamp happens in the main loop.
    // Sync on the audit, or the assertions below race ahead of processOne (and never-placeOrder
    // would pass vacuously).
    waitForAuditKind("OperatorTrimClamped");
    AuditEvent clamped = captureKind("OperatorTrimClamped");
    assertThat(asLong(clamped.getSubject().get("qty_clamped"))).isEqualTo(0L);

    // The single contract is untouched: no SELL reached the broker.
    verify(exec, never()).placeOrder(any());
    assertThat(stub.positionState().remainingQty()).isEqualTo(1L);

    stub.partialExit(partialExitRequest("sig-drain", "pos-trim-one-lot", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 1L, new BigDecimal("4.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  @Test
  void partialCloseValidator_fullFraction_rejects() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-trim-full-fraction");
    WorkflowStub.fromTyped(stub).start(futureInput(4));
    confirmEntry(stub, 4L);

    // fraction == 1.0 is a FULL close — force_close's job, not the trim's.
    assertThatThrownBy(() -> stub.partialClose(partialCloseRequest("ops-1", "reason ok", 1.0)))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("fraction");

    // Rejected in the validator, so nothing was enqueued: the drain below still sells the FULL 4
    // contracts and completes the workflow, which it could not do had a trim been applied.
    stub.partialExit(partialExitRequest("sig-drain", "pos-trim-full-fraction", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 4L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  @Test
  void partialCloseValidator_blankOperatorId_rejects() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-trim-blank-op");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    assertThatThrownBy(() -> stub.partialClose(partialCloseRequest("", "reason ok", 0.5)))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("operator_id");

    stub.partialExit(partialExitRequest("sig-drain", "pos-trim-blank-op", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 3L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  /**
   * Issue #762: an AUTOMATED daily-loss breach must NOT liquidate a long-dated position.
   *
   * <p>Live 2026-08-19: prod-kipark's switch tripped on a −$4,050 loss produced entirely by a 6-DTE
   * SPY put, and the cascade market-flattened everything — including DRAM at 212 DTE. A daily
   * breaker's window is one session; a 212-DTE position's P&amp;L that day is noise against its
   * holding period, and it has its own controls (trail / EOD / expiry). On prod_real that same
   * contract carries a trail ~25% away which the cascade would have pre-empted.
   */
  @Test
  void issue762_automatedBreach_doesNotFlattenLongDatedPosition() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));

    PositionWorkflow stub = newStub("pos-762-long-dated");
    WorkflowStub.fromTyped(stub).start(futureInput(4)); // 2 years out
    confirmEntry(stub, 4L);

    stub.riskBreach(riskBreachPayload("auto:daily_loss", "auto:daily_loss"));
    Thread.sleep(1500);

    assertThat(stub.positionState().remainingQty())
        .as("a daily breaker must not liquidate a position whose horizon outlives it")
        .isEqualTo(4L);
    verify(exec, never()).placeOrder(any());

    AuditEvent skipped = captureKind("RiskBreachFlattenSkippedLongDated");
    assertThat(skipped.getSubject()).containsEntry("reason", "auto:daily_loss");
    assertThat(asLong(skipped.getSubject().get("exempt_above_dte"))).isEqualTo(90L);
    assertThat(asLong(skipped.getSubject().get("days_to_expiry"))).isGreaterThan(90L);
    // The breach is still RECORDED — the trip and its entry-halt are unaffected.
    captureKind("RiskBreachActed");
  }

  /**
   * Issue #762: operator intent always wins. A MANUAL breach flattens a long-dated position exactly
   * as before — the exemption is scoped to {@code auto:*} actors only, and anything unrecognised
   * fails CLOSED (flattens).
   */
  @Test
  void issue762_operatorBreach_stillFlattensLongDatedPosition() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-762-long-dated-manual");
    WorkflowStub.fromTyped(stub).start(futureInput(4)); // same 2-year contract
    confirmEntry(stub, 4L);

    stub.riskBreach(riskBreachPayload("manual:operator", "ops-7"));

    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten-762", 4L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    assertThat(captureAll("RiskBreachFlattenSkippedLongDated"))
        .as("an operator breach is never exempt")
        .isEmpty();
    captureKind("PositionClosed");
  }

  @Test
  void riskBreach_healthyPosition_flattens() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-risk-breach");
    // #762: SHORT-dated on purpose. This test's subject is "an ordinary position flattens on an
    // automated breach"; FUTURE_OCC_SYMBOL is 2 years out and is now exempt, which would make this
    // assert the opposite of what it means. The 2-year case is covered by the #762 tests below.
    WorkflowStub.fromTyped(stub).start(shortDatedInput(4));
    confirmEntry(stub, 4L);

    stub.riskBreach(riskBreachPayload("auto:daily_loss", "auto:daily_loss"));

    // risk_breach keeps exit-NOW (MARKET) but is still fill-awaited under R-AA-1: deliver the fill.
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flatten", 4L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent acted = captureKind("RiskBreachActed");
    assertThat(acted.getSubject()).containsEntry("reason", "auto:daily_loss");
    // flattenRemaining audit (re-uses EOD kinds; subject.reason disambiguates).
    AuditEvent flatReq = captureKind("EodForceFlattenRequested");
    assertThat(flatReq.getSubject()).containsEntry("reason", "risk_breach");
    captureKind("EodForceFlattened");
    // risk_breach keeps the MARKET (immediacy) flatten: the SELL OrderIntent has no limit price.
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(flatten.getLimitPrice()).isNull();
  }

  @Test
  void riskBreach_inFlight_cancelsThenFlattens() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-risk-breach-inflight");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // Queue an STC that won't be filled — exit is in flight when risk_breach arrives.
    stub.partialExit(partialExitRequest("sig-inflight", "pos-risk-breach-inflight", 0.5));
    waitForPlaceOrderCount(1);

    stub.riskBreach(riskBreachPayload("manual:operator", "ops-3"));

    // risk_breach cancels the in-flight order, places the MARKET flatten, then awaits its fill.
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, atLeastOnce()).cancelOrder(anyString());
    captureKind("RiskBreachActed");
  }

  // ---------- Phase 2c.2: broker_target routing on PositionWorkflowInput ----------

  @Test
  void runWithBrokerTargetRoutesToBrokerQueue() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-bt-alpaca");
    PositionWorkflowInput in = futureInput(3);
    in.setBrokerTarget(PositionWorkflowInput.BrokerTarget.ALPACA_PAPER);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    // Drain via a partial close so the workflow actually dispatches exec.placeOrder. The
    // exec mock is registered on the broker-alpaca-paper worker; a successful call confirms
    // the workflow routed Activities to that task queue.
    stub.partialExit(partialExitRequest("sig-bt-alpaca", "pos-bt-alpaca", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-bt-1", 3L, new BigDecimal("3.00")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, times(1)).placeOrder(any());
  }

  @Test
  void runWithoutBrokerTargetFallsBackToDefault() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-bt-default");
    // input() helper does not set broker_target — exercises the pre-2c.2 replay path that
    // falls back to DEFAULT_BROKER_TARGET = "alpaca-paper".
    WorkflowStub.fromTyped(stub).start(futureInput(2));
    confirmEntry(stub, 2L);

    stub.partialExit(partialExitRequest("sig-bt-default", "pos-bt-default", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-bt-2", 2L, new BigDecimal("3.05")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, times(1)).placeOrder(any());
  }

  // ---------- Issue #202: eod_force_flatten = false suppresses EOD timer ----------

  /**
   * Issue #202: when {@code eod_force_flatten} is false, PositionWorkflow MUST NOT arm the 15:55 ET
   * EOD timer. The position remains in its main {@link io.temporal.workflow.Workflow#await} loop
   * until STC, expiry, chandelier trail, risk_breach, or operator force_close — never EOD. Drives
   * the copytrade author-mirror fidelity contract (see {@code
   * tenants/dev/strategies/copytrade-v1.yaml}).
   */
  @Test
  void eodTimer_skipsArmingWhenEodForceFlattenFalse() throws Exception {
    // EOD timer would fire ~immediately if armed.
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-no-eod-flatten");
    PositionWorkflowInput in = futureInput(5);
    in.setEodForceFlatten(Boolean.FALSE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // Advance virtual time well past the would-be EOD trigger to prove the timer is not armed.
    env.sleep(Duration.ofMinutes(1));

    // STC closes the position — the only normal exit path for this strategy.
    stub.partialExit(partialExitRequest("sig-stc-author", "pos-no-eod-flatten", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stc", 5L, new BigDecimal("3.20")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-no-eod-flatten");

    // No EOD force-flatten audit fired. Capture every audit kind once and assert.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds).doesNotContain("EodForceFlattenRequested", "EodForceFlattened");

    // Exit went through the normal STC path (and only that path), so exactly one placeOrder.
    verify(exec, times(1)).placeOrder(any());
  }

  /**
   * Issue #202 hardening (VERSION_EOD_FLATTEN_OPT_IN, v&gt;=1): a null {@code eod_force_flatten} is
   * fail-CLOSED — the blanket 15:55 ET EOD timer does NOT arm. This is the defense that would have
   * prevented the incident where the tenants ConfigMap dropped {@code eod_force_flatten} and the
   * resulting null silently re-armed the flatten, closing a non-0DTE copytrade position. The
   * position instead rides to STC (the expiry-close timer still handles 0DTE physical expiry).
   */
  @Test
  void eodTimer_skipsArmingWhenEodForceFlattenNull() throws Exception {
    // EOD timer would fire ~immediately if armed.
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-null-eod-flatten");
    PositionWorkflowInput in = futureInput(3);
    // eod_force_flatten left null — must be treated as "do not arm" (fail-closed).
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    // Advance virtual time well past the would-be EOD trigger to prove the timer is not armed.
    env.sleep(Duration.ofMinutes(1));

    // STC closes the position — the only normal exit path when EOD is not armed.
    stub.partialExit(partialExitRequest("sig-stc-null", "pos-null-eod-flatten", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-stc-null", 3L, new BigDecimal("3.10")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-null-eod-flatten");

    // No EOD force-flatten audit fired.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds).doesNotContain("EodForceFlattenRequested", "EodForceFlattened");
  }

  // ---------- Issue #203: phantom position — defer PositionEntered until first onFill ----------

  /**
   * Issue #203 Done-when 2: a BTO that never reaches FILLED must emit {@code PositionNeverFilled}
   * and terminate cleanly within the bounded TTL, with NO {@code PositionEntered} audit. Drives
   * reconciliation's ability to prune the stale SUBMITTED journal row.
   */
  @Test
  void btoNeverFilled_emitsPositionNeverFilledAndTerminatesWithoutPositionEntered()
      throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-never-filled");
    WorkflowStub.fromTyped(stub).start(input(5));

    // Advance virtual time past the 90s first-fill TTL without sending any onFill. The workflow
    // must time out into the PositionNeverFilled path.
    env.sleep(Duration.ofSeconds(120));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-never-filled");

    AuditEvent neverFilled = captureKind("PositionNeverFilled");
    assertThat(neverFilled.getSubject())
        .containsEntry("entry_signal_id", "entry-1")
        .containsEntry("contract_symbol", EXPIRED_OCC_SYMBOL);
    assertThat(asLong(neverFilled.getSubject().get("expected_qty"))).isEqualTo(5L);
    assertThat(asLong(neverFilled.getSubject().get("ttl_secs"))).isEqualTo(90L);

    // Critical: no PositionEntered audit. The position never existed.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds).doesNotContain("PositionEntered", "PositionClosed");

    // No order was placed — the workflow exited before the partial-exit pipeline could run.
    verify(exec, never()).placeOrder(any());
  }

  /**
   * Issue #203 Done-when 4: an STC arriving before the first onFill must not credit against a
   * non-existent position. The partial exit is buffered into pendingExits but is silently dropped
   * when the workflow terminates via the PositionNeverFilled timeout — no PartialExitFilled fires.
   */
  @Test
  void partialExitBeforeFirstFill_doesNotCreditAgainstPhantomPosition() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-stc-before-fill");
    WorkflowStub.fromTyped(stub).start(input(5));

    // STC arrives before any onFill — the workflow is still awaiting the first-fill confirmation.
    // The signal handler buffers it into pendingExits, but the main loop never reaches the
    // partial-exit drain because run() returns from the PositionNeverFilled branch first.
    stub.partialExit(partialExitRequest("sig-stc-phantom", "pos-stc-before-fill", 0.5));

    // Advance past TTL without sending onFill.
    env.sleep(Duration.ofSeconds(120));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // PositionNeverFilled fired; PartialExitFilled / PartialExitRequested did NOT.
    captureKind("PositionNeverFilled");
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds)
        .doesNotContain("PartialExitFilled", "PartialExitRequested", "PositionEntered");

    // exec.placeOrder was never called — no broker exit could target a phantom.
    verify(exec, never()).placeOrder(any());
  }

  /**
   * Issue #203 Done-when 1: PositionEntered fires AFTER the first onFill, with {@code qty} equal to
   * the broker-reported {@code filled_qty} — even when the fill is partial (filled_qty &lt;
   * input.qty).
   */
  @Test
  void positionEntered_firesAfterFirstFill_withFilledQtyNotInputQty() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-partial-fill");
    // Input requests 5 contracts, but the BTO only partial-fills 3 — PositionEntered must reflect
    // the 3 actually held, not the 5 requested.
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    stub.onFill(fill("brk-entry", 3L, new BigDecimal("2.40")));

    // Drain via a full close so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-close", "pos-partial-fill", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-exit", 3L, new BigDecimal("3.10")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent entered = captureKind("PositionEntered");
    assertThat(asLong(entered.getSubject().get("qty"))).isEqualTo(3L);
    assertThat(entered.getSubject()).containsEntry("entry_signal_id", "entry-1");

    // The exit drain saw the partial-fill qty (3), not the requested input.qty (5).
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);

    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(1);
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(3L);
  }

  // ---------- Issue #205: min_partial_qty_behavior — runner-quantum partial-exit gate ----------

  /**
   * Issue #205 Done-when 3a: when {@code min_partial_qty_behavior=skip} (default), a partial-exit
   * signal that would round to zero contracts under the integer broker quantum (remainingQty=1 +
   * fraction=0.5 → floor(0.5)=0) MUST audit {@code PartialExitSkippedMinQty} and place NO close
   * order. The runner survives for trail/EOD/STC drain. Closes the dead-config gap from issue #205
   * (the YAML key was declared but no Java code read it).
   */
  @Test
  void partialExit_remainingQty1_fraction0_5_skipBranch_emitsSkippedAuditAndPlacesNoOrder()
      throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-min-qty-skip");
    PositionWorkflowInput in = futureInput(1);
    in.setMinPartialQtyBehavior(PositionWorkflowInput.MinPartialQtyBehavior.SKIP);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 1L);

    // Half-out on a 1-contract runner: floor(1 * 0.5) = 0 → SKIP branch.
    stub.partialExit(partialExitRequest("sig-skip", "pos-min-qty-skip", 0.5));

    // The SKIP branch does not place an order, so we can't waitForPlaceOrderCount. Give the
    // workflow a virtual moment to process the signal then drain via a full close so the workflow
    // terminates cleanly.
    Thread.sleep(200);
    stub.partialExit(partialExitRequest("sig-full", "pos-min-qty-skip", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-final", 1L, new BigDecimal("3.20")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent skipped = captureKind("PartialExitSkippedMinQty");
    assertThat(skipped.getSubject()).containsEntry("signal_id", "sig-skip");
    assertThat(asLong(skipped.getSubject().get("remaining_qty"))).isEqualTo(1L);
    assertThat(((Number) skipped.getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);

    // Critical: the SKIP branch placed NO close order. Only the follow-up full-close exits placed
    // the single broker order observed.
    verify(exec, times(1)).placeOrder(any());

    // No PartialExitFilled fired for the skipped signal — the skip is a fulfillment-by-not-filling.
    List<AuditEvent> filled = captureAll("PartialExitFilled");
    assertThat(filled).hasSize(1);
    assertThat(filled.get(0).getSubject()).containsEntry("signal_id", "sig-full");
  }

  /**
   * Issue #205 Done-when 3b — incident-equivalent regression guard: when {@code
   * min_partial_qty_behavior=full_close}, a partial STC that rounds to zero contracts under the
   * integer broker quantum (remainingQty=1 + fraction=0.3 → floor(0.3)=0) MUST CLOSE the last lot —
   * {@code qtyToClose == remainingQty == 1} — rather than skip it. This is the exact operator
   * behavior the {@code /config} {@code min_partial_qty_behavior} control exposes: on a 1-lot
   * residual, {@code full_close} flushes the runner on the partial signal. PartialExitFilled fires
   * normally; PartialExitSkippedMinQty does NOT.
   */
  @Test
  void partialExit_remainingQty1_fraction0_3_fullCloseBranch_closesLastLotQty1() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-min-qty-full-close");
    PositionWorkflowInput in = futureInput(1);
    in.setMinPartialQtyBehavior(PositionWorkflowInput.MinPartialQtyBehavior.FULL_CLOSE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 1L);

    // 30%-out on a 1-contract runner: floor(1 * 0.3) = 0 → runner-quantum gate; full_close flushes.
    stub.partialExit(partialExitRequest("sig-flush", "pos-min-qty-full-close", 0.3));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-flush", 1L, new BigDecimal("3.05")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // PartialExitFilled fires for the runner-flush; no PartialExitSkippedMinQty.
    AuditEvent filled = captureKind("PartialExitFilled");
    assertThat(filled.getSubject()).containsEntry("signal_id", "sig-flush");
    assertThat(asLong(filled.getSubject().get("qty_filled"))).isEqualTo(1L);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds).doesNotContain("PartialExitSkippedMinQty");

    // Load-bearing incident assertion: the last lot is CLOSED with qtyToClose == 1.
    // (a) the PartialExitRequested audit reports qty_to_close = remainingQty = 1, and
    // (b) the exit OrderIntent actually placed on the broker carries qty == 1.
    AuditEvent requested = captureKind("PartialExitRequested");
    assertThat(asLong(requested.getSubject().get("qty_to_close"))).isEqualTo(1L);

    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent exit =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no SELL exit OrderIntent placed"));
    assertThat(exit.getQty()).isEqualTo(1L);
  }

  @Test
  void runWithInvalidBrokerTargetRaisesInvalidBrokerTargetError() {
    // The PositionWorkflowInput.BrokerTarget enum admits "paper" / "live" for back-compat
    // deserialization of pre-2c.2 audit records, but no worker polls broker-paper /
    // broker-live. ExecActivitiesFactory.taskQueueFor must fail fast with a non-retryable
    // InvalidBrokerTargetError instead of hanging on a StartToCloseTimeout.
    PositionWorkflow stub = newStub("pos-bt-legacy");
    PositionWorkflowInput in = input(3);
    in.setBrokerTarget(PositionWorkflowInput.BrokerTarget.PAPER);
    WorkflowStub.fromTyped(stub).start(in);

    assertThatThrownBy(() -> WorkflowStub.fromTyped(stub).getResult(String.class))
        .isInstanceOf(WorkflowFailedException.class)
        .hasCauseInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> {
              ApplicationFailure af = (ApplicationFailure) t.getCause();
              assertThat(af.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(af.isNonRetryable()).isTrue();
            });
  }

  // ---------- Issue #212: first-fill / exit-fill TTLs configurable via PositionWorkflowInput ----

  /**
   * Issue #212 Done-when 4: when {@code input.first_fill_ttl_secs} is set, the first-fill bounded
   * await uses that value (not the hardcoded 90s default). Drives the {@code PositionNeverFilled}
   * audit to fire at the configured deadline so per-strategy paper/live TTLs from {@code
   * StrategyConfig.pending_ttl_paper_secs / pending_ttl_live_secs} actually take effect at runtime.
   * The audit's {@code ttl_secs} subject reflects the resolved value, not the constant.
   */
  @Test
  void firstFillTtlSecs_fromInput_drivesPositionNeverFilledAtConfiguredDeadline() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-first-fill-ttl-10");
    PositionWorkflowInput in = input(5);
    in.setFirstFillTtlSecs(10L);
    WorkflowStub.fromTyped(stub).start(in);

    // Advance just past the configured 10s TTL without sending any onFill. The workflow must
    // time out via PositionNeverFilled — proving the input field, not the 90s default constant,
    // drove the bound.
    env.sleep(Duration.ofSeconds(15));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-first-fill-ttl-10");

    AuditEvent neverFilled = captureKind("PositionNeverFilled");
    // The audit's ttl_secs reflects the resolved value (input field, not the constant).
    assertThat(asLong(neverFilled.getSubject().get("ttl_secs"))).isEqualTo(10L);
    assertThat(neverFilled.getSubject())
        .containsEntry("entry_signal_id", "entry-1")
        .containsEntry("contract_symbol", EXPIRED_OCC_SYMBOL);

    // No PositionEntered audit fired — the position never existed.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds).doesNotContain("PositionEntered", "PositionClosed");
  }

  /**
   * Issue #212 Done-when 5: when {@code input.exit_fill_ttl_secs} is set, the {@code processOne()}
   * bounded exit-fill await uses that value. Drives the {@code PartialExitFillTimeout} audit to
   * fire at the configured deadline so per-strategy TTLs reach the exit-side runtime gate too.
   */
  @Test
  void exitFillTtlSecs_fromInput_drivesPartialExitFillTimeoutAtConfiguredDeadline()
      throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-exit-fill-ttl-20");
    PositionWorkflowInput in = futureInput(5);
    in.setExitFillTtlSecs(20L);
    // Plan-2B R-AB-2: cap the stepped reprice at 1 so this #212 TTL test keeps its "one retry then
    // drop" (2-timeout) shape under the redesigned loop.
    in.setExitRepriceSteps(1L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // First STC: send fraction=0.5 and never deliver the fill — the configured 20s TTL must
    // bound the wait (not the 90s default).
    stub.partialExit(partialExitRequest("sig-stuck-212", "pos-exit-fill-ttl-20", 0.5));
    waitForPlaceOrderCount(1);

    // Advance just past the configured 20s exit-fill TTL — first timeout, retry fires.
    env.sleep(Duration.ofSeconds(25));
    waitForPlaceOrderCount(2);
    // Advance past the retry's TTL too — second timeout drops the STC.
    env.sleep(Duration.ofSeconds(25));

    // Drain the runner so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-follow-212", "pos-exit-fill-ttl-20", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-follow-212", 5L, new BigDecimal("2.95")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent timeout = captureKind("PartialExitFillTimeout");
    assertThat(timeout.getSubject())
        .containsEntry("signal_id", "sig-stuck-212")
        .containsEntry("broker_order_id", "brk-exit");
    assertThat(asLong(timeout.getSubject().get("remaining_qty"))).isEqualTo(5L);
    assertThat(asLong(timeout.getSubject().get("ttl_secs"))).isEqualTo(20L);

    // Cancel was attempted on the stuck order(s).
    verify(exec, atLeastOnce()).cancelOrder(anyString());

    // The follow-up STC drained normally after the latch release.
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(1);
    assertThat(partialFills.get(0).getSubject()).containsEntry("signal_id", "sig-follow-212");
  }

  // ---------- Issue #216: PartialExitFillTimeout retry with fresh limit price ----------

  /**
   * Issue #216 Done-when 1: when the v=1 exit-fill await times out, the workflow must (a) audit
   * {@code PartialExitRetryRequested}, (b) place a retry order with a fresh {@code intent_key}
   * (suffix {@code ":retry"}) and a fresh limit price (ref_premium fallback when no chandelier tick
   * has arrived), and (c) on retry-fill, decrement remainingQty and emit {@code PartialExitFilled}
   * carrying the original signal_id (the retry is logically the same STC). No second {@code
   * PartialExitFillTimeout} fires because the retry filled.
   */
  @Test
  void processOne_exitFillTimeoutRetry_freshLimitOrderFillsAndDecrementsRemaining()
      throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-retry-fills");
    PositionWorkflowInput in = futureInput(4);
    in.setExitRepriceSteps(1L); // R-AB-2: 1 reprice step → mirrors the legacy single-retry shape.
    // exit_floor configured so the reprice produces a bounded LIMIT from the live quote
    // (source_premium=live_quote_stepped); without a floor the reprice would fail-safe to
    // marketable.
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    // First STC: ask for fraction=0.5 (qtyToClose=2) and never deliver the original fill.
    stub.partialExit(partialExitRequest("sig-retry", "pos-retry-fills", 0.5));
    waitForPlaceOrderCount(1);

    // Advance past the 90s default TTL — first timeout fires, retry is dispatched.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);

    // Deliver the retry fill — partial fill of 2 contracts (matching qtyToClose).
    stub.onFill(fill("brk-retry", 2L, new BigDecimal("2.78")));

    // Drain the runner so the workflow can terminate cleanly.
    stub.partialExit(partialExitRequest("sig-close-final", "pos-retry-fills", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-close-final", 2L, new BigDecimal("2.80")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Exactly one timeout (the original); the retry filled so no second timeout audit.
    List<AuditEvent> timeouts = captureAll("PartialExitFillTimeout");
    assertThat(timeouts).hasSize(1);
    assertThat(timeouts.get(0).getSubject()).containsEntry("signal_id", "sig-retry");

    // Exactly one reprice-requested audit carrying the original signal_id, retry_attempt=1, and the
    // R-AB-2 stepped source_premium provenance (the limit is anchored on a fresh live quote).
    List<AuditEvent> retries = captureAll("PartialExitRetryRequested");
    assertThat(retries).hasSize(1);
    assertThat(retries.get(0).getSubject())
        .containsEntry("signal_id", "sig-retry")
        .containsEntry("source_premium", "live_quote_stepped");
    assertThat(asLong(retries.get(0).getSubject().get("retry_attempt"))).isEqualTo(1L);
    // intent_key has the :reprice-1 suffix so it's distinct from the original.
    assertThat((String) retries.get(0).getSubject().get("intent_key")).endsWith(":reprice-1");

    // The reprice order placed by placeOrder() carries the :reprice-1 intent_key.
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(2)).placeOrder(intentCaptor.capture());
    List<OrderIntent> capturedIntents = intentCaptor.getAllValues();
    assertThat(capturedIntents.stream().map(OrderIntent::getIntentKey))
        .anyMatch(k -> k != null && k.endsWith(":reprice-1"));

    // Two PartialExitFilled audits: the retry-fill for sig-retry, then the closing sig-close-final.
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(2);
    assertThat(partialFills.get(0).getSubject())
        .containsEntry("signal_id", "sig-retry")
        .containsEntry("broker_order_id", "brk-retry");
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(2L);
  }

  /**
   * Issue #216 Done-when 4: retry budget caps at 1. Two consecutive timeouts (original + retry)
   * MUST drop the STC permanently — no third placeOrder, no second retry-requested audit. The
   * runner survives at the same {@code remainingQty}; a follow-up STC drains normally.
   */
  @Test
  void processOne_exitFillTimeoutRetry_secondTimeoutDropsAndCapsAtOneRetry() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-retry-caps");
    PositionWorkflowInput in = futureInput(5);
    in.setExitRepriceSteps(1L); // R-AB-2: cap the stepped reprice at 1 → 2 timeouts then drop.
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // First STC: never deliver the original fill.
    stub.partialExit(partialExitRequest("sig-drop", "pos-retry-caps", 0.5));
    waitForPlaceOrderCount(1);

    // First timeout — retry fires.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    // Second timeout (retry also stuck) — STC dropped, no third placeOrder.
    env.sleep(Duration.ofSeconds(120));

    // Follow-up STC drains normally — proving the latch released after the cap.
    stub.partialExit(partialExitRequest("sig-after", "pos-retry-caps", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-after", 5L, new BigDecimal("2.92")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Exactly 2 timeouts (original + retry) and exactly 1 retry-requested audit (cap=1).
    List<AuditEvent> timeouts = captureAll("PartialExitFillTimeout");
    assertThat(timeouts).hasSize(2);
    assertThat(timeouts.stream().map(e -> e.getSubject().get("signal_id")))
        .containsExactly("sig-drop", "sig-drop");

    List<AuditEvent> retries = captureAll("PartialExitRetryRequested");
    assertThat(retries).hasSize(1);
    assertThat(retries.get(0).getSubject()).containsEntry("signal_id", "sig-drop");

    // remainingQty was not decremented for the dropped STC; the follow-up close cleared all 5.
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(1);
    assertThat(partialFills.get(0).getSubject()).containsEntry("signal_id", "sig-after");
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(5L);

    // Cancel was attempted on both the original and retry orders.
    verify(exec, atLeast(2)).cancelOrder(anyString());
  }

  /**
   * Plan-2B R-AB-2: under the stepped-reprice redesign, the reprice limit is anchored on a FRESH
   * GetOptionQuoteActivity bid/mid (live_quote_stepped) rather than the legacy lastTick/ref/peak
   * source chain (which is now reachable only on v=0 replays — covered by the LegacyReplayTest).
   * The step limit walks toward the market by exit_reprice_tick and is bounded by exit_floor. With
   * the default quote (bid=2.50), tick=0.05, step=1, and a configured floor, the reprice limit is
   * 2.45. Formerly {@code processOne_exitFillTimeoutRetry_chandelierTickDrivesFreshLimitSource}
   * (#216).
   */
  @Test
  void processOne_steppedReprice_limitAnchoredOnFreshLiveQuote() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflowInput in = futureInput(3);
    in.setExitRepriceSteps(1L);
    in.setExitRepriceTick(new BigDecimal("0.05"));
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));

    PositionWorkflow stub = newStub("pos-reprice-quote");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    // Arm the chandelier + seed a tick — proving the stepped limit is NOT sourced from these
    // (legacy) signals but from the fresh live quote.
    stub.armChandelier(
        armPayload(
            "pos-reprice-quote", "src-arm-rab2", new BigDecimal("2.85"), new BigDecimal("0.15")));
    stub.chandelierTick(tick(new BigDecimal("2.70")));

    stub.partialExit(partialExitRequest("sig-reprice-quote", "pos-reprice-quote", 0.5));
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-reprice-quote", 2L, new BigDecimal("2.45")));

    // Drain the remaining runner.
    stub.partialExit(partialExitRequest("sig-reprice-quote-close", "pos-reprice-quote", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-reprice-quote-close", 1L, new BigDecimal("2.40")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent retry = captureKind("PartialExitRetryRequested");
    assertThat(retry.getSubject())
        .containsEntry("signal_id", "sig-reprice-quote")
        .containsEntry("source_premium", "live_quote_stepped");
    // fresh_limit_price = max(floor=1.25, bid 2.50 - 1*0.05) = 2.45 (from the live quote, NOT the
    // armed peak 2.85 or the latched tick 2.70).
    assertThat(((Number) retry.getSubject().get("fresh_limit_price")).doubleValue())
        .isEqualTo(2.45);
  }

  /**
   * Plan-2B R-AB-2: when the live quote is UNAVAILABLE on a reprice step, the bounded reprice fails
   * SAFE to a marketable exit (null limit, source_premium=marketable_fallback) and emits the
   * FlattenQuoteUnavailable observability audit — never resting above an executable price. Formerly
   * {@code processOne_exitFillTimeoutRetry_refPremiumPreferredOverPeak} (#227).
   */
  @Test
  void processOne_steppedReprice_quoteUnavailable_failsSafeToMarketable() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());
    when(optionQuote.getOptionQuote(any())).thenReturn(quoteFailed("md outage"));

    PositionWorkflowInput in = futureInput(3);
    in.setExitRepriceSteps(1L);
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));

    PositionWorkflow stub = newStub("pos-reprice-no-quote");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    stub.partialExit(partialExitRequest("sig-reprice-no-quote", "pos-reprice-no-quote", 0.5));
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-reprice-no-quote", 2L, new BigDecimal("2.40")));

    stub.partialExit(partialExitRequest("sig-no-quote-close", "pos-reprice-no-quote", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-no-quote-close", 1L, new BigDecimal("2.35")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent retry = captureKind("PartialExitRetryRequested");
    assertThat(retry.getSubject())
        .containsEntry("signal_id", "sig-reprice-no-quote")
        .containsEntry("source_premium", "marketable_fallback");
    // No fresh limit price (marketable exit).
    assertThat(retry.getSubject().get("fresh_limit_price")).isNull();
    // Loud observability audit for the market-data outage during the reprice.
    assertThat(captureAll("FlattenQuoteUnavailable")).isNotEmpty();

    // The reprice intent placed a MARKET order (null limit) when the quote was unavailable.
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(2)).placeOrder(intent.capture());
    OrderIntent reprice =
        intent.getAllValues().stream()
            .filter(i -> i.getIntentKey() != null && i.getIntentKey().endsWith(":reprice-1"))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no :reprice-1 intent placed"));
    assertThat(reprice.getLimitPrice()).isNull();
  }

  /**
   * Plan-2B R-AB-2: a step's bounded reprice never crosses the exit_floor — when the configured
   * floor sits ABOVE the quote-anchored walk price, the step fails SAFE to marketable (the floor is
   * above the live bid so a limit at the floor would never fill). Formerly {@code
   * processOne_exitFillTimeoutRetry_peakPremiumOnlyWhenLastTickAndRefAbsent} (#227).
   */
  @Test
  void processOne_steppedReprice_floorAboveBid_failsSafeToMarketable() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    // exit_floor_abs=5.00 sits ABOVE the live bid 2.50 → floor-above-bid fail-safe → marketable.
    PositionWorkflowInput in = futureInput(3);
    in.setExitRepriceSteps(1L);
    in.setExitFloorAbs(new BigDecimal("5.00"));

    PositionWorkflow stub = newStub("pos-reprice-floor-above");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    stub.partialExit(partialExitRequest("sig-floor-above", "pos-reprice-floor-above", 0.5));
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-floor-above", 2L, new BigDecimal("2.40")));

    stub.partialExit(partialExitRequest("sig-floor-above-close", "pos-reprice-floor-above", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-floor-above-close", 1L, new BigDecimal("2.35")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent retry = captureKind("PartialExitRetryRequested");
    assertThat(retry.getSubject())
        .containsEntry("signal_id", "sig-floor-above")
        .containsEntry("source_premium", "marketable_fallback");
    assertThat(retry.getSubject().get("fresh_limit_price")).isNull();
    // Loud floor-config-error audit (floor above live bid).
    assertThat(captureAll("FlattenFloorConfigError")).isNotEmpty();
  }

  /**
   * Issue #216 PR #226 review follow-up: v=0 back-compat envelope for the new {@code
   * VERSION_EXIT_RETRY_ON_TIMEOUT} gate. Under {@code DEFAULT_VERSION} (an in-flight workflow that
   * started before #216 shipped), the timeout branch must drop the STC on the first timeout — one
   * {@code PartialExitFillTimeout} audit, no {@code PartialExitRetryRequested}, no retry {@code
   * placeOrder} call — exactly matching PR #214's single-cycle semantics.
   *
   * <p><b>Test scope note:</b> {@link io.temporal.testing.TestWorkflowEnvironment} starts every
   * workflow with a fresh history, so {@code Workflow.getVersion(VERSION_EXIT_RETRY_ON_TIMEOUT,
   * DEFAULT_VERSION, 1)} resolves to {@code 1} (the max version registered) — there is no clean
   * knob to force {@code DEFAULT_VERSION} without {@code WorkflowReplayer} replaying a real
   * pre-#216 history file. The v=0 protection is therefore enforced by Temporal SDK's history-based
   * version resolution itself, which is covered by SDK-level tests. This test asserts the v=1
   * envelope that the gate produces under TestWorkflowEnvironment (single timeout + single
   * retry-requested for one stuck STC), and the javadoc documents the v=0 gap so reviewers see why
   * a direct v=0 assertion is absent.
   */
  @Test
  void processOne_exitFillTimeout_retryGateUnderVersionResolutionDocsAndAsserts() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-retry-gate-doc");
    PositionWorkflowInput in = futureInput(4);
    // Keep TTLs short so the test runs quickly under virtual time.
    in.setExitFillTtlSecs(2L);
    in.setExitRepriceSteps(1L); // R-AB-2: cap at 1 reprice → 2 timeouts then drop (legacy shape).
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    // First STC: never deliver the fill.
    stub.partialExit(partialExitRequest("sig-doc", "pos-retry-gate-doc", 0.5));
    waitForPlaceOrderCount(1);

    // First timeout — under v=1 (TestWorkflowEnvironment default) the retry fires.
    env.sleep(Duration.ofSeconds(5));
    waitForPlaceOrderCount(2);

    // Second timeout (retry also stuck) — STC dropped under the cap=1 retry budget.
    env.sleep(Duration.ofSeconds(5));

    // Drain the runner so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-doc-drain", "pos-retry-gate-doc", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-doc-drain", 4L, new BigDecimal("2.95")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // v=1 envelope: original + retry = two PartialExitFillTimeout audits, both for sig-doc.
    // Under v=0 this would be exactly one timeout audit and zero retry audits (the gate-off
    // semantics from PR #214) — that path is enforced by Temporal's getVersion replay logic
    // and is not directly testable in TestWorkflowEnvironment without WorkflowReplayer.
    List<AuditEvent> timeouts = captureAll("PartialExitFillTimeout");
    assertThat(timeouts).hasSize(2);
    assertThat(timeouts.stream().map(e -> e.getSubject().get("signal_id")))
        .containsExactly("sig-doc", "sig-doc");

    // Exactly one PartialExitRetryRequested audit under v=1 (cap=1). Under v=0 this list would
    // be empty — documented above; enforced by Temporal SDK version resolution.
    List<AuditEvent> retries = captureAll("PartialExitRetryRequested");
    assertThat(retries).hasSize(1);
    assertThat(retries.get(0).getSubject()).containsEntry("signal_id", "sig-doc");
  }

  /**
   * Issue #216 PR #226 /simplify follow-up: when an EOD timer fires while a retry order is in
   * flight, {@code flattenRemaining()} must cancel the retry order's intent_key — not the original
   * (already-cancelled) {@code :exit:<sig>} key. The {@code currentInFlightIntentKey} state field
   * tracks the live intent_key so the EOD-preemption cancel hits the right broker order. Without
   * this fix, flattenRemaining would reconstruct the original key from {@code
   * currentInFlightSignalId} and double-cancel an already-cancelled order while leaving the live
   * retry order open.
   */
  @Test
  void processOne_retryActive_eodTimerFires_cancelHitsRetryKey() throws Exception {
    // EOD horizon: long enough that the original STC times out and the retry order is placed
    // first, but short enough that EOD fires during the retry's exit-fill await (before the
    // retry's own TTL expires).
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofSeconds(150));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-during-retry");
    PositionWorkflowInput in = input(5);
    in.setEodForceFlatten(Boolean.TRUE); // opt into the blanket EOD flatten to drive this path
    in.setExitRepriceSteps(1L); // R-AB-2: single reprice step → the in-flight key is :reprice-1.
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // First STC: never deliver the fill — original will time out at the default 90s TTL.
    stub.partialExit(partialExitRequest("sig-eod-retry", "pos-eod-during-retry", 0.5));
    waitForPlaceOrderCount(1);

    // Advance past the original's 90s TTL — original times out, retry is dispatched.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);

    // Advance past the EOD horizon (set at t=150s) while the retry is still awaiting its fill.
    // The retry's own TTL would fire at t=120+90=210s, but EOD at t=150s fires first. The retry
    // await's predicate wakes on eodFired=true; processOne returns with currentInFlightIntentKey
    // still set to the ":retry"-suffixed key, and run() invokes flattenRemaining("eod") which
    // cancels that key, then places the bounded flatten and awaits its fill (R-AA-1).
    env.sleep(Duration.ofSeconds(60));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-flatten", 5L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // The original timeout fired and audited; the retry did NOT time out (EOD preempted).
    List<AuditEvent> timeouts = captureAll("PartialExitFillTimeout");
    assertThat(timeouts).hasSize(1);
    assertThat(timeouts.get(0).getSubject()).containsEntry("signal_id", "sig-eod-retry");

    captureKind("PartialExitRetryRequested");
    captureKind("EodForceFlattenRequested");
    captureKind("EodForceFlattened");

    // The critical assertion: capture every cancelOrder argument and prove that the cancel
    // fired by flattenRemaining targets the :retry intent_key — the live in-flight order —
    // not the original (already-cancelled) :exit:<sig> key. Without the
    // currentInFlightIntentKey fix from the /simplify [skip-review] commit, flattenRemaining
    // would reconstruct the original key and miss the live retry order.
    ArgumentCaptor<String> cancelKeyCaptor = ArgumentCaptor.forClass(String.class);
    verify(exec, atLeast(2)).cancelOrder(cancelKeyCaptor.capture());
    List<String> cancelledKeys = cancelKeyCaptor.getAllValues();
    // Original timeout cancel hits the :exit:<sig> key (no :reprice suffix).
    assertThat(cancelledKeys)
        .as("processOne's original-timeout cancel must hit the original intent_key")
        .anyMatch(k -> k.endsWith(":exit:sig-eod-retry"));
    // EOD-during-reprice cancel from flattenRemaining hits the :reprice-1-suffixed live key.
    assertThat(cancelledKeys)
        .as("flattenRemaining must cancel the live reprice intent_key, not the original")
        .anyMatch(k -> k.endsWith(":exit:sig-eod-retry:reprice-1"));
  }

  /**
   * VERSION_EXIT_RETRY_LATE_FILL_RECONCILE (a): a timed-out exit order fills LATE during the
   * best-effort cancel. The buffered onFill is delivered when the cancelOrder activity returns; the
   * reconcile decrements remainingQty by the late fill (3 of 3), so the computed retry qty is 0 —
   * the workflow must SKIP the retry (place NO {@code :retry} order), emit exactly one
   * PartialExitFilled and one PartialExitRetrySkippedSatisfied, and leave remainingQty correct. The
   * pre-patch bug discarded the late fill and re-sent the full qtyToClose → naked short.
   */
  @Test
  void processOne_exitFillTimeoutRetry_lateFillSatisfiesIntent_skipsRetryNoOverSell()
      throws Exception {
    PositionWorkflow stub = newStub("pos-late-fill-satisfies");
    // cancelOrder delivers the LATE original fill while the workflow is blocked in the activity, so
    // lastFillEvent != null at the reconcile point (buffered-during-cancel timing, deterministic).
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString()))
        .thenAnswer(
            inv -> {
              stub.onFill(fill("brk-late", 3L, new BigDecimal("2.79")));
              return cancelledResult();
            });

    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    // STC fraction=0.5 on remaining=5 → qtyToClose=ceil(2.5)=3, targetRemaining=2.
    stub.partialExit(partialExitRequest("sig-late", "pos-late-fill-satisfies", 0.5));
    waitForPlaceOrderCount(1);

    // Fire the original's 90s TTL: timeout → cancel (delivers the late fill) → reconcile → skip.
    env.sleep(Duration.ofSeconds(120));

    // Drain the runner (remaining should be 2) so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-drain", "pos-late-fill-satisfies", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-drain", 2L, new BigDecimal("2.81")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // No :retry-suffixed OrderIntent was EVER placed — the anti-naked-short guarantee.
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(1)).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getAllValues().stream().map(OrderIntent::getIntentKey))
        .as("no retry order may be placed when the late fill satisfied the intent")
        .noneMatch(k -> k != null && k.endsWith(":retry"));
    // No placed exit intent ever exceeds the live remaining at placement time.
    assertThat(intentCaptor.getAllValues().stream().map(OrderIntent::getQty))
        .allMatch(q -> q <= 5L);

    // Exactly one PartialExitFilled for the late original fill (qty 3, remaining_after 2).
    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(2);
    assertThat(partialFills.get(0).getSubject())
        .containsEntry("signal_id", "sig-late")
        .containsEntry("broker_order_id", "brk-late");
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(3L);
    assertThat(asLong(partialFills.get(0).getSubject().get("remaining_qty_after"))).isEqualTo(2L);

    // Exactly one skip-satisfied audit with the captured target.
    List<AuditEvent> skips = captureAll("PartialExitRetrySkippedSatisfied");
    assertThat(skips).hasSize(1);
    assertThat(skips.get(0).getSubject()).containsEntry("signal_id", "sig-late");
    assertThat(asLong(skips.get(0).getSubject().get("remaining_qty"))).isEqualTo(2L);
    assertThat(asLong(skips.get(0).getSubject().get("target_remaining"))).isEqualTo(2L);

    // No second timeout (the skip path returns without re-awaiting).
    assertThat(captureAll("PartialExitFillTimeout")).hasSize(1);
    // The late fill is reflected by EXACTLY ONE PartialExitFilled for sig-late (no double-count).
    assertThat(
            partialFills.stream().filter(e -> "sig-late".equals(e.getSubject().get("signal_id"))))
        .hasSize(1);
  }

  /**
   * VERSION_EXIT_RETRY_LATE_FILL_RECONCILE (b): a partial late fill clamps the retry to the exact
   * remainder. Position 6, fraction 0.5 → qtyToClose=3, targetRemaining=3. The original fills only
   * 1 late → remaining 5 → retry qty must be EXACTLY 2 (5−3), NOT ceil(5*0.5)=3. Proves the retry
   * is sized off the captured target, not re-derived from the fraction.
   */
  @Test
  void processOne_exitFillTimeoutRetry_partialLateFill_clampsRetryToRemainder() throws Exception {
    PositionWorkflow stub = newStub("pos-partial-late-fill");
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // First cancel (original timeout) delivers a PARTIAL late fill of 1; the retry order then fills
    // the remaining 2. cancelOrder is only invoked on the original timeout in this scenario.
    when(exec.cancelOrder(anyString()))
        .thenAnswer(
            inv -> {
              stub.onFill(fill("brk-late-partial", 1L, new BigDecimal("2.77")));
              return cancelledResult();
            });

    PositionWorkflowInput in = futureInput(6);
    in.setExitRepriceSteps(1L); // R-AB-2: single reprice step → key is :reprice-1.
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 6L);

    // STC fraction=0.5 on remaining=6 → qtyToClose=3, targetRemaining=3.
    stub.partialExit(partialExitRequest("sig-partial", "pos-partial-late-fill", 0.5));
    waitForPlaceOrderCount(1);

    // Original times out → cancel delivers the late partial fill of 1 → remaining 5 → retry qty=2.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);

    // Deliver the retry fill of 2 → remaining 3 == target.
    stub.onFill(fill("brk-retry-partial", 2L, new BigDecimal("2.80")));

    // Drain the runner (remaining 3) so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-partial-drain", "pos-partial-late-fill", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-partial-drain", 3L, new BigDecimal("2.82")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // The :reprice-1 OrderIntent qty must be EXACTLY 2 (remainder), not 3 (ceil of fraction).
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(2)).placeOrder(intentCaptor.capture());
    OrderIntent retryIntent =
        intentCaptor.getAllValues().stream()
            .filter(i -> i.getIntentKey() != null && i.getIntentKey().endsWith(":reprice-1"))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no :reprice-1 intent placed"));
    assertThat(retryIntent.getQty())
        .as("retry qty must clamp to remaining - target (2), not ceil(remaining*fraction) (3)")
        .isEqualTo(2L);

    // Exactly one retry-requested audit (the clamp does not suppress the retry here).
    assertThat(captureAll("PartialExitRetryRequested")).hasSize(1);
    // No skip-satisfied audit (retry was needed).
    assertThat(captureAll("PartialExitRetrySkippedSatisfied")).isEmpty();

    // Two PartialExitFilled entries for sig-partial: 1 (late) then 2 (retry).
    List<AuditEvent> sigPartialFills =
        captureAll("PartialExitFilled").stream()
            .filter(e -> "sig-partial".equals(e.getSubject().get("signal_id")))
            .toList();
    assertThat(sigPartialFills).hasSize(2);
    assertThat(asLong(sigPartialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(1L);
    assertThat(asLong(sigPartialFills.get(0).getSubject().get("remaining_qty_after")))
        .isEqualTo(5L);
    assertThat(asLong(sigPartialFills.get(1).getSubject().get("qty_filled"))).isEqualTo(2L);
    assertThat(asLong(sigPartialFills.get(1).getSubject().get("remaining_qty_after")))
        .isEqualTo(3L);
    // Double-count guard: the late fill (qty 1) appears in EXACTLY one PartialExitFilled.
    assertThat(
            captureAll("PartialExitFilled").stream()
                .filter(e -> "brk-late-partial".equals(e.getSubject().get("broker_order_id"))))
        .hasSize(1);
  }

  // ---------- F1: partial-exit-cancel-terminal-state-reconcile ----------

  /**
   * F1 (the core real-money fix): the original exit limit times out WITHOUT an onFill signal, but
   * the broker had FILLED it — the {@code exec.cancelOrder} cancel-on-filled race returns {@code
   * state=FILLED}. Pre-fix the workflow DISCARDED the cancel return, re-sent the full qtyToClose,
   * over-sold (broker-rejected → PartialExitPlaceFailed), and emitted NO PartialExitFilled for the
   * fill that actually happened. The fix reconciles the authoritative terminal state from the
   * cancel return: it must emit EXACTLY ONE PartialExitFilled (qty 2), ZERO PartialExitPlaceFailed,
   * place NO retry order, and leave remainingQty at the broker truth (3 - 2 = 1). No onFill is ever
   * sent.
   */
  @Test
  void
      partialExit_brokerFillsAfterTtl_cancelReturnsFilled_emitsExactlyOnePartialExitFilled_noPlaceFailed_noOverQtyRetry()
          throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // The cancel surfaces the late broker fill of 2 @ 1.84 (the live-incident shape: STC half of
    // 3).
    when(exec.cancelOrder(anyString())).thenReturn(filledCancelResult(2L, new BigDecimal("1.84")));

    PositionWorkflow stub = newStub("pos-cancel-filled");
    PositionWorkflowInput in = futureInput(3);
    in.setExitFillTtlSecs(2L); // short TTL so the unfilled await elapses fast under virtual time
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    // STC fraction=0.5 on remaining=3 → qtyToClose=ceil(1.5)=2, targetRemaining=1. Never deliver an
    // onFill — the ONLY evidence of the fill is the cancelOrder return (the defect's exact shape).
    stub.partialExit(partialExitRequest("sig-cancel-filled", "pos-cancel-filled", 0.5));
    waitForPlaceOrderCount(1);

    // Fire the TTL: timeout → cancel returns FILLED → authoritative reconcile → no retry.
    env.sleep(Duration.ofSeconds(30));

    // Drain the runner (remaining should be 1) so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-cf-drain", "pos-cancel-filled", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-cf-drain", 1L, new BigDecimal("1.86")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // EXACTLY ONE PartialExitFilled for the cancel-surfaced fill (qty 2, remaining_after 1).
    List<AuditEvent> sigFills =
        captureAll("PartialExitFilled").stream()
            .filter(e -> "sig-cancel-filled".equals(e.getSubject().get("signal_id")))
            .toList();
    assertThat(sigFills).hasSize(1);
    assertThat(asLong(sigFills.get(0).getSubject().get("qty_filled"))).isEqualTo(2L);
    assertThat(asLong(sigFills.get(0).getSubject().get("remaining_qty_after"))).isEqualTo(1L);
    assertThat(sigFills.get(0).getSubject()).containsEntry("broker_order_id", "brk-cancel-filled");

    // ZERO PartialExitPlaceFailed — the over-sell never happened.
    assertThat(captureAll("PartialExitPlaceFailed")).isEmpty();

    // NO :retry / :reprice retry order was placed for the cancel-filled signal — only the original
    // place (#1) and the unrelated drain (#2).
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(2)).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getAllValues().stream().map(OrderIntent::getIntentKey))
        .as("no retry order may be placed once the cancel surfaced the authoritative fill")
        .noneMatch(
            k ->
                k != null
                    && k.contains("sig-cancel-filled")
                    && (k.endsWith(":retry") || k.contains(":reprice-")));

    // The skip-satisfied audit fired (retryQty clamped to 0 by the reconciled remaining).
    List<AuditEvent> skips = captureAll("PartialExitRetrySkippedSatisfied");
    assertThat(skips).hasSize(1);
    assertThat(skips.get(0).getSubject()).containsEntry("signal_id", "sig-cancel-filled");
  }

  /**
   * F1 defense-in-depth: the cancel did NOT surface the fill (returns CANCELLED), but the order had
   * actually filled — {@code exec.getOrderStatus} reports the authoritative FILLED terminal state.
   * The reconcile must fall back to getOrderStatus, book the fill exactly once, and skip the retry.
   */
  @Test
  void partialExit_cancelReturnsCancelled_butGetOrderStatusFilled_reconciles() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());
    // The journal/broker authoritative state is FILLED even though cancel did not surface it.
    when(exec.getOrderStatus(anyString()))
        .thenReturn(filledCancelResult(2L, new BigDecimal("1.84")));

    PositionWorkflow stub = newStub("pos-status-filled");
    PositionWorkflowInput in = futureInput(3);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    stub.partialExit(partialExitRequest("sig-status-filled", "pos-status-filled", 0.5));
    waitForPlaceOrderCount(1);

    env.sleep(Duration.ofSeconds(30));

    stub.partialExit(partialExitRequest("sig-sf-drain", "pos-status-filled", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-sf-drain", 1L, new BigDecimal("1.86")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // getOrderStatus was consulted as the authoritative fallback.
    verify(exec, atLeastOnce()).getOrderStatus(anyString());

    // EXACTLY ONE PartialExitFilled for the status-surfaced fill (qty 2, remaining_after 1).
    List<AuditEvent> sigFills =
        captureAll("PartialExitFilled").stream()
            .filter(e -> "sig-status-filled".equals(e.getSubject().get("signal_id")))
            .toList();
    assertThat(sigFills).hasSize(1);
    assertThat(asLong(sigFills.get(0).getSubject().get("qty_filled"))).isEqualTo(2L);
    assertThat(asLong(sigFills.get(0).getSubject().get("remaining_qty_after"))).isEqualTo(1L);

    assertThat(captureAll("PartialExitPlaceFailed")).isEmpty();

    // No retry order placed for the reconciled signal.
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(2)).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getAllValues().stream().map(OrderIntent::getIntentKey))
        .noneMatch(
            k ->
                k != null
                    && k.contains("sig-status-filled")
                    && (k.endsWith(":retry") || k.contains(":reprice-")));
  }

  /**
   * F1 regression guard: a GENUINE timeout (no fill anywhere) must still retry. cancel returns
   * CANCELLED with no fill, getOrderStatus also reports not-filled → the retry fires bounded to the
   * full remaining qtyToClose (nothing was sold). The fix must NOT suppress legitimate retries.
   */
  @Test
  void partialExit_genuineTimeout_noFill_stillRetriesBoundedToRemaining() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());
    // getOrderStatus also reports a non-terminal/unfilled state (SUBMITTED, filledQty null).
    when(exec.getOrderStatus(anyString())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-genuine-timeout");
    PositionWorkflowInput in = futureInput(4);
    in.setExitRepriceSteps(1L); // single reprice step → key is :reprice-1
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    // STC fraction=0.5 on remaining=4 → qtyToClose=2.
    stub.partialExit(partialExitRequest("sig-genuine", "pos-genuine-timeout", 0.5));
    waitForPlaceOrderCount(1);

    // Original times out, no fill anywhere → retry fires.
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);

    // Deliver the retry fill so the workflow drains cleanly.
    stub.onFill(fill("brk-genuine-retry", 2L, new BigDecimal("2.78")));
    stub.partialExit(partialExitRequest("sig-genuine-drain", "pos-genuine-timeout", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-genuine-drain", 2L, new BigDecimal("2.80")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // The retry order WAS placed and bounded to the full qtyToClose (2) — nothing was sold, so the
    // reconcile must not have clamped it down.
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(2)).placeOrder(intentCaptor.capture());
    OrderIntent retryIntent =
        intentCaptor.getAllValues().stream()
            .filter(i -> i.getIntentKey() != null && i.getIntentKey().endsWith(":reprice-1"))
            .reduce((a, b) -> b)
            .orElseThrow(
                () -> new AssertionError("genuine timeout must still place a retry order"));
    assertThat(retryIntent.getQty())
        .as("genuine-timeout retry qty must equal qtyToClose (2) — nothing was sold")
        .isEqualTo(2L);

    // No skip-satisfied audit (the retry was genuinely needed).
    assertThat(captureAll("PartialExitRetrySkippedSatisfied")).isEmpty();
  }

  /**
   * F1 review BLOCKER regression: the cancel-on-filled race return AND a buffered onFill can BOTH
   * describe the SAME broker fill. TTL elapses; while the in-flight cancelOrder runs the broker
   * fills 2 ct — the onFill signal buffers (lastFillEvent != null) AND the cancel returns
   * state=FILLED filledQty=2. Without the fix the F1 reconcile books the fill from the cancel
   * return but does NOT clear lastFillEvent, so the adjacent lateFillReconcile block books it
   * AGAIN: remainingQty double-decremented (3 → 1 → -1) and TWO PartialExitFilled for one fill. The
   * fix clears lastFillEvent after the F1 reconcile so the single fill is booked EXACTLY once.
   */
  @Test
  void partialExit_cancelFilledAndBufferedOnFillSameFill_booksExactlyOnceNoDoubleDecrement()
      throws Exception {
    PositionWorkflow stub = newStub("pos-double-book");
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    // The cancel surfaces the broker fill of 2 AND delivers the buffered onFill for the SAME fill
    // while the activity runs — exactly the race the BLOCKER describes (both evidences present at
    // the post-cancel reconcile point).
    when(exec.cancelOrder(anyString()))
        .thenAnswer(
            inv -> {
              stub.onFill(fill("brk-cancel-filled", 2L, new BigDecimal("1.84")));
              return filledCancelResult(2L, new BigDecimal("1.84"));
            });

    PositionWorkflowInput in = futureInput(3);
    in.setExitFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    // STC fraction=0.5 on remaining=3 → qtyToClose=2, targetRemaining=1.
    stub.partialExit(partialExitRequest("sig-double-book", "pos-double-book", 0.5));
    waitForPlaceOrderCount(1);

    // Fire the TTL: timeout → cancel (delivers buffered onFill + returns FILLED) → reconcile ONCE.
    env.sleep(Duration.ofSeconds(30));

    // Drain the runner (remaining must be 1, NOT -1) so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-db-drain", "pos-double-book", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-db-drain", 1L, new BigDecimal("1.86")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // EXACTLY ONE PartialExitFilled for the reconciled signal (not two), remaining_after == 1.
    List<AuditEvent> sigFills =
        captureAll("PartialExitFilled").stream()
            .filter(e -> "sig-double-book".equals(e.getSubject().get("signal_id")))
            .toList();
    assertThat(sigFills)
        .as("the single broker fill must be booked EXACTLY once, not double-booked")
        .hasSize(1);
    assertThat(asLong(sigFills.get(0).getSubject().get("qty_filled"))).isEqualTo(2L);
    assertThat(asLong(sigFills.get(0).getSubject().get("remaining_qty_after")))
        .as("remainingQty must be 1 (3-2), never -1 (3-2-2 from a double-decrement)")
        .isEqualTo(1L);

    assertThat(captureAll("PartialExitPlaceFailed")).isEmpty();

    // No over-qty retry for the reconciled signal.
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, times(2)).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getAllValues().stream().map(OrderIntent::getIntentKey))
        .noneMatch(
            k ->
                k != null
                    && k.contains("sig-double-book")
                    && (k.endsWith(":retry") || k.contains(":reprice-")));
  }

  // ---------- Plan-2B R-AB-1: guaranteed multi-day expiry-lead flatten timer ----------

  /**
   * R-AB-1 Done-when: a MULTI-DAY lot (expiry not today) arms a flatten timer (the calendar's
   * {@code durationUntilExpiryFlattenEt} returns &gt; 0 for a future expiry) and, when it fires,
   * the lot is sold via the 2A bounded reason-scoped flatten with reason=expiry_lead — routed to
   * the DEDICATED ExpiryLead* kinds (NOT the Eod* fallthrough). The expiry-close (0DTE) timer stays
   * ZERO so this exercises the lead timer in isolation.
   */
  @Test
  void multiDayLot_armsExpiryLeadTimer_flattensViaBoundedLimit_routesToDedicatedKinds()
      throws Exception {
    // durationUntilExpiryCloseEt stays ZERO (multi-day → no 0DTE timer). The lead timer fires
    // shortly.
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    // exit_floor configured so the bounded flatten anchors a LIMIT (floor=max(0.05, 2.50*0.5)=1.25
    // < live bid 2.50, so a non-marketable bounded limit is produced).
    PositionWorkflowInput in = input(4);
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));

    PositionWorkflow stub = newStub("pos-expiry-lead");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    // Advance virtual time past the lead trigger so the guaranteed flatten fires.
    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-lead", 4L, new BigDecimal("2.45")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-expiry-lead");

    // Dedicated lead kinds emitted; NOT the Eod* fallthrough.
    AuditEvent leadReq = captureKind("ExpiryLeadFlattenRequested");
    assertThat(leadReq.getSubject()).containsEntry("reason", "expiry_lead");
    AuditEvent leadDone = captureKind("ExpiryLeadForceFlattened");
    assertThat(leadDone.getSubject()).containsEntry("reason", "expiry_lead");
    assertThat(captureAll("EodForceFlattenRequested")).isEmpty();
    assertThat(captureAll("EodForceFlattened")).isEmpty();

    // Sold via a BOUNDED LIMIT (not a market order): the SELL flatten intent carries a non-null
    // limit anchored on the live bid (default quote bid=2.50).
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    OrderIntent flatten =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no SELL flatten intent placed"));
    assertThat(flatten.getLimitPrice()).as("bounded LIMIT, not market").isNotNull();

    // P&L rode the shared PartialExitFilled, and the lot is broker-confirmed flat.
    assertThat(captureAll("PartialExitFilled")).isNotEmpty();
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
  }

  /**
   * R-AB-1: a lead-timer fire on a CLOSED market is a safe no-op — the bounded limit simply rests
   * unfilled until the next session, and the workflow stays ALIVE (never emits PositionClosed with
   * a live lot). Models this by firing the lead timer but delivering NO fill within the TTL: the
   * bounded flatten times out, the workflow blocks on a late fill rather than completing.
   */
  @Test
  void expiryLeadTimer_firesOnClosedMarket_isSafeNoOp_workflowStaysAlive() throws Exception {
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-lead-closed-market");
    PositionWorkflowInput in = input(4);
    // Phase 2: NOT-yet-expired OCC — the lead timer on a MULTI-DAY lot firing on a closed market is
    // a safe no-op (stays ALIVE); the worthless-close is expiry-date gated and must NOT fire here.
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    env.sleep(Duration.ofMinutes(1));
    waitForPlaceOrderCount(1);
    // No fill arrives within the TTL → bounded flatten rests unfilled; advance past it.
    env.sleep(Duration.ofSeconds(120));

    // The flatten requested but did NOT confirm flat (no fill) → a failure audit, and the workflow
    // stays ALIVE (never emits PositionClosed with a live lot). Deliver a late fill so the test can
    // drain the workflow cleanly and prove the no-op was recoverable, not a silent loss.
    assertThat(captureAll("ExpiryLeadFlattenRequested")).isNotEmpty();
    assertThat(captureAll("PositionClosed")).isEmpty();

    stub.onFill(fill("brk-late-lead", 4L, new BigDecimal("2.40")));
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-lead-closed-market");
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);
  }

  // ---------- Plan-2B R-AB-2: bounded stepped repricing on the normal exit ----------

  /**
   * R-AB-2 Done-when: the normal exit walks a BOUNDED STEPPED reprice over N steps within the
   * exit_floor — each step re-anchored on a fresh GetOptionQuoteActivity bid/mid, each with a
   * distinct :reprice-N intent_key (NO client_order_id reuse across steps). The original placement
   * never fills; the final reprice step fills. No market order is ever placed.
   */
  @Test
  void exit_steppedReprice_walksWithinFloor_noClientOrderIdReuse_thenFills() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());
    // exit_reprice_steps=2, tick=0.05; default quote bid=2.50.
    PositionWorkflowInput in = futureInput(4);
    in.setExitRepriceSteps(2L);
    in.setExitRepriceTick(new BigDecimal("0.05"));
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));

    PositionWorkflow stub = newStub("pos-stepped-reprice");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 4L);

    stub.partialExit(partialExitRequest("sig-step", "pos-stepped-reprice", 1.0));
    waitForPlaceOrderCount(1); // original placement

    env.sleep(Duration.ofSeconds(120)); // original times out → reprice-1
    waitForPlaceOrderCount(2);

    env.sleep(Duration.ofSeconds(120)); // reprice-1 times out → reprice-2
    waitForPlaceOrderCount(3);

    // Deliver the fill on the final reprice step.
    stub.onFill(fill("brk-step", 4L, new BigDecimal("2.45")));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-stepped-reprice");

    // Capture all SELL intents; the reprice steps carry distinct :reprice-N keys (no reuse).
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(3)).placeOrder(intent.capture());
    List<String> sellKeys =
        intent.getAllValues().stream()
            .filter(i -> i.getSide() == OrderIntent.Side.SELL)
            .map(OrderIntent::getIntentKey)
            .toList();
    // Distinct keys → no client_order_id reuse across steps.
    assertThat(sellKeys).doesNotHaveDuplicates();
    assertThat(sellKeys).anyMatch(k -> k != null && k.endsWith(":reprice-1"));
    assertThat(sellKeys).anyMatch(k -> k != null && k.endsWith(":reprice-2"));

    // Every reprice step's limit is BOUNDED (non-null, never a market order) and at-or-above the
    // floor. floor = max(0.05, anchor*0.5); anchor=2.50 → floor=1.25. Walk steps stay >= 1.25.
    List<OrderIntent> repriceIntents =
        intent.getAllValues().stream()
            .filter(
                i ->
                    i.getSide() == OrderIntent.Side.SELL
                        && i.getIntentKey() != null
                        && i.getIntentKey().contains(":reprice-"))
            .toList();
    assertThat(repriceIntents).hasSizeGreaterThanOrEqualTo(2);
    for (OrderIntent ri : repriceIntents) {
      assertThat(ri.getLimitPrice()).as("bounded limit, never market").isNotNull();
      assertThat(ri.getLimitPrice()).isGreaterThanOrEqualTo(new BigDecimal("1.25"));
    }
  }

  /**
   * R-AB-2 #357 naked-short guard across N steps: when NO fill arrives, every reprice step re-sends
   * the SAME reconciled remaining qty (the captured qtyToClose) — it is never re-ceil'd or inflated
   * across steps, so a multi-step walk can never over-sell. The late-fill-during-cancel reconcile
   * itself is covered for the single-retry mechanism by {@link
   * #processOne_exitFillTimeoutRetry_partialLateFill_clampsRetryToRemainder()}; here we assert the
   * cross-step qty stability that the redesigned loop must preserve.
   */
  @Test
  void exit_steppedReprice_qtyStableAcrossSteps_noOverSell() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflowInput in = futureInput(5);
    in.setExitRepriceSteps(2L);
    in.setExitRepriceTick(new BigDecimal("0.05"));
    in.setExitFloorAbs(new BigDecimal("0.05"));
    in.setExitFloorPct(new BigDecimal("0.5"));

    PositionWorkflow stub = newStub("pos-step-qty");
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 5L);

    // fraction=0.6 → qtyToClose=3. No fill ever arrives; walk both steps then drop.
    stub.partialExit(partialExitRequest("sig-step-qty", "pos-step-qty", 0.6));
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120)); // original times out → reprice-1
    waitForPlaceOrderCount(2);
    env.sleep(Duration.ofSeconds(120)); // reprice-1 times out → reprice-2
    waitForPlaceOrderCount(3);
    env.sleep(Duration.ofSeconds(120)); // reprice-2 times out → drop (cap reached)

    // Drain the runner (still 5, nothing sold) so the workflow terminates.
    stub.partialExit(partialExitRequest("sig-step-drain", "pos-step-qty", 1.0));
    waitForPlaceOrderCount(4);
    stub.onFill(fill("brk-step-drain", 5L, new BigDecimal("2.50")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Every reprice step re-sent qty=3 (the captured qtyToClose), never an inflated value.
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(3)).placeOrder(intent.capture());
    List<OrderIntent> repriceIntents =
        intent.getAllValues().stream()
            .filter(
                i ->
                    i.getIntentKey() != null
                        && i.getIntentKey().contains(":reprice-")
                        && "sig-step-qty".equals(i.getSignalId()))
            .toList();
    assertThat(repriceIntents).hasSizeGreaterThanOrEqualTo(2);
    for (OrderIntent ri : repriceIntents) {
      assertThat(ri.getQty()).as("no over-sell across steps").isEqualTo(3L);
    }
    // No PartialExitFilled for the dropped STC (nothing sold across all steps).
    assertThat(
            captureAll("PartialExitFilled").stream()
                .filter(e -> "sig-step-qty".equals(e.getSubject().get("signal_id"))))
        .isEmpty();
  }

  // ---------- helpers ----------

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
    in.setStrategyId("copytrade-v1");
    in.setEntrySignalId("entry-1");
    in.setContractSymbol(EXPIRED_OCC_SYMBOL);
    in.setQty(qty);
    in.setEntryPremium(new BigDecimal("2.30"));
    return in;
  }

  /**
   * Same as {@link #input(long)} but on a NON-expired (future) contract. Use for any test that
   * drives a LIVE position lifecycle (confirms entry then signals STC / partial-exit / chandelier /
   * manual flatten / force-close) WITHOUT arming an eod/expiry/expiry-lead terminal timer: post
   * PLAN-2026-07-23 Phase 2 the expiry-day self-close guard closes a physically-expired lot that
   * has no terminal timer armed at entry, so such a lifecycle test must hold a future-dated OCC.
   */
  private PositionWorkflowInput futureInput(long qty) {
    PositionWorkflowInput in = input(qty);
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    return in;
  }

  /**
   * Issue #762: an unexpired but SHORT-dated contract (30 DTE), below the automated-breach
   * exemption threshold. {@link #FUTURE_OCC_SYMBOL} is deliberately 2 YEARS out, which is now a
   * behavioural difference rather than an incidental one — an automated daily-loss breach does not
   * flatten it. Tests that mean "an ordinary position" need this, not that.
   */
  private static final String SHORT_DATED_OCC_SYMBOL =
      "NVDA  "
          + LocalDate.now(ZoneId.of("America/New_York"))
              .plusDays(30)
              .format(DateTimeFormatter.ofPattern("yyMMdd"))
          + "C00140000";

  private PositionWorkflowInput shortDatedInput(long qty) {
    PositionWorkflowInput in = input(qty);
    in.setContractSymbol(SHORT_DATED_OCC_SYMBOL);
    return in;
  }

  // ---------- PLAN-2026-08-16 Phase 1: premium-feed silence observability ----------

  @Test
  void trailingState_lastTickObservedAt_isNullBeforeAnyTick() throws Exception {
    PositionWorkflow stub = newStub("pos-silence-none");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    assertThat(stub.trailingState().lastTickObservedAt()).isNull();
  }

  @Test
  void tickDrain_stampsObservedAt_evenWhenTheTrailIsNotArmed() throws Exception {
    // THE gap this phase exists to close. processTick early-returns on !trailingArmed, so
    // lastTickAt
    // is stamped ONLY while armed — there is no record anywhere of "when did we last hear
    // anything".
    // A staleness detector cannot be built on a field that is blank exactly when the position is
    // unarmed, and the stamp must therefore live at the DRAIN point, before the route fork.
    PositionWorkflow stub = newStub("pos-silence-unarmed");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);

    stub.chandelierTick(tick(new BigDecimal("1.00")));

    TrailingState st = waitForTickObserved(stub);
    assertThat(st.armed()).isFalse();
    // lastTickAt stays null (processTick never ran) — that is the pre-existing behaviour,
    // unchanged.
    assertThat(st.lastTickAt()).isNull();
    // ...but we DID hear a tick, and that must now be recorded.
    assertThat(st.lastTickObservedAt()).isNotNull();
  }

  @Test
  void tickDrain_observedAtAdvancesWithEachTick_whileLastTickAtTracksQuoteTime() throws Exception {
    // The two stamps answer different questions and must not be conflated: lastTickAt is the
    // QUOTE's
    // own timestamp (how fresh the market data was), lastTickObservedAt is workflow time (when we
    // last heard anything at all). A staleness check needs the second; the gap between them is
    // itself diagnostic.
    PositionWorkflow stub = newStub("pos-silence-armed");
    WorkflowStub.fromTyped(stub).start(futureInput(3));
    confirmEntry(stub, 3L);
    stub.armChandelier(
        armPayload("pos-silence-armed", "sig-arm", new BigDecimal("3.00"), new BigDecimal("0.20")));

    stub.chandelierTick(tick(new BigDecimal("3.10")));
    TrailingState first = waitForTickObserved(stub);

    assertThat(first.armed()).isTrue();
    assertThat(first.lastTickObservedAt()).isNotNull();
    // Armed, so the quote stamp is populated too — and it is the QUOTE's time, not the
    // observation's.
    assertThat(first.lastTickAt()).isNotNull();
    assertThat(first.lastTickAt()).isNotEqualTo(first.lastTickObservedAt());
  }

  // ---------- PLAN-2026-08-16: operator-set trailing stop (arm_trail Update) ----------

  @Test
  void armTrail_operatorArmsAndStopIsPeakAnchored() throws Exception {
    // This is a copytrade position (no watchlist exit armed), so the tick loop routes to
    // processTick and compares the MID. The anchor must be in that same space: the default quote's
    // mid is 2.55, so a 20% giveback anchors at 2.55 and stops at 2.04. It is deliberately NOT the
    // bid (2.50 -> 2.00); see armTrail_copytradePosition_anchorsOnTheMid... for why that matters.
    PositionWorkflow stub = newStub("pos-armtrail-ok");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    ArmTrailResult r = stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.20")));

    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.ARMED);
    assertThat(r.getPeakPremium()).isEqualByComparingTo("2.55");
    assertThat(r.getGivebackPct()).isEqualByComparingTo("0.20");
    // stop = peak * (1 - giveback). Echoed so the UI never re-derives the workflow's arithmetic.
    assertThat(r.getStopPrice()).isEqualByComparingTo("2.04");

    AuditEvent armed = captureKind("ChandelierArmed");
    assertThat(armed.getSubject())
        .containsEntry("source", "operator")
        .containsEntry("operator_id", "ops-1");
  }

  @Test
  void armTrail_copytradePosition_anchorsOnTheMidTheTickLoopWillCompare() throws Exception {
    // THE invariant: the peak must be in the same price space as the ticks it will be compared
    // against. The main loop routes to processExitTick (which trails on the BID) only when
    // exitArmed || (exitTargetFired && trailingArmed); otherwise processTick compares the raw
    // premium, the MID. All three real-money tenants are copytrade, so this is the primary path
    // for the operator Stop-loss button, not an edge case.
    //
    // A bid anchor here is visibly wrong, not merely imprecise. Since ask >= bid, the mid always
    // exceeds it, so the FIRST tick ratchets the peak into mid space and the stop silently moves
    // up from the number the Update returned. A wide 0DTE book makes the gap impossible to write
    // off as rounding: 2.00 x 3.00 at 35% giveback promises 1.30 and delivers 1.625 — tighter, so
    // safe for money, but a stop that did not do what the operator was told is exactly the failure
    // this feature exists to prevent.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("2.00"), new BigDecimal("2.50"), new BigDecimal("3.00")));

    PositionWorkflow stub = newStub("pos-armtrail-midspace");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    ArmTrailResult r = stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.35")));

    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.ARMED);
    assertThat(r.getPeakPremium()).isEqualByComparingTo("2.50");
    // 2.50 * 0.65 = 1.625, penny-rounded. The bid-anchored answer would have been 1.30.
    assertThat(r.getStopPrice()).isEqualByComparingTo("1.63");
    assertThat(r.getStopPrice()).isNotEqualByComparingTo("1.30");
  }

  @Test
  void armTrail_cheapWideBook_isNormalAndMustStillArmOnTheMid() throws Exception {
    // The regression the removed ratio test would have caused. 0.05 x 0.11 is a healthy quote on
    // the cheap decayed contracts a 0DTE copytrade position turns into — but ask/bid is 2.2, so a
    // 2x fallback re-anchored it on the bid and handed the operator 0.03 for a stop that would
    // ratchet to 0.052 on the first tick. 73% wrong, routinely, on the number this feature exists
    // to make honest. Scale-dependence is the defect: at $5 a 2x ask is a $5 spread; at $0.05 it is
    // five ticks.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("0.05"), new BigDecimal("0.08"), new BigDecimal("0.11")));

    PositionWorkflow stub = newStub("pos-armtrail-cheapwide");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    ArmTrailResult r = stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.35")));

    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.ARMED);
    assertThat(r.getPeakPremium()).isEqualByComparingTo("0.08");
    // 0.08 * 0.65 = 0.052 -> 0.05. The bid-substituted answer would have been 0.03.
    assertThat(r.getStopPrice()).isEqualByComparingTo("0.05");
  }

  @Test
  void armTrail_crossedBook_isNeverAnchoredOn() throws Exception {
    // A blown BID inflates the mid exactly as a blown ask does, and it would slip past a check that
    // references the bid — the phantom would be vouching for itself. A crossed NBBO is never a real
    // book, so it is refused before it can anchor anything.
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("12.00"), new BigDecimal("8.05"), new BigDecimal("4.10")));

    PositionWorkflow stub = newStub("pos-armtrail-crossed");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    ArmTrailResult r = stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.35")));

    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.REJECTED);
    assertThat(r.getReason()).isEqualTo("anchor_unresolvable");
  }

  @Test
  void armTrail_rejectsWhenSubscriptionFails_andDoesNotArm() throws Exception {
    // THE failure this feature must never get wrong. Without a tick feed the trail can never fire,
    // so reporting success would leave the operator believing a real-money position is protected.
    when(marketData.subscribePremium(any())).thenReturn(failedSubscription("upstream down"));

    PositionWorkflow stub = newStub("pos-armtrail-subfail");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    ArmTrailResult r = stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.20")));

    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.REJECTED);
    assertThat(r.getReason()).isEqualTo("subscription_failed");
    // No stop was armed, and the operator is told so rather than shown a price.
    assertThat(r.getStopPrice()).isNull();
    captureKind("ChandelierSubscriptionFailed");
  }

  @Test
  void armTrail_rejectsWhenNoAnchorResolvable() throws Exception {
    // No live bid anywhere: arming at a guessed level would put the stop at the wrong price on a
    // real-money position, so the arm is refused instead.
    when(optionQuote.getOptionQuote(any())).thenReturn(quoteFailed("provider 503"));

    PositionWorkflow stub = newStub("pos-armtrail-noanchor");
    WorkflowStub.fromTyped(stub).start(futureInput(5));

    ArmTrailResult r = stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.20")));

    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.REJECTED);
    // Named precisely: the operator's remedy (retry when a quote returns) differs from a malformed
    // value they could correct.
    assertThat(r.getReason()).isEqualTo("anchor_unresolvable");
  }

  @Test
  void armTrail_alreadyArmedIsIdempotentAndNeverLoosens() throws Exception {
    PositionWorkflow stub = newStub("pos-armtrail-idem");
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    confirmEntry(stub, 5L);

    stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.10")));
    // A second, LOOSER request — a double-click, two tabs, or a second operator.
    ArmTrailResult second = stub.armTrail(armTrailRequest("ops-2", new BigDecimal("0.40")));

    assertThat(second.getStatus()).isEqualTo(ArmTrailResult.Status.ALREADY_ARMED);
    // Echoes what is IN FORCE, not what was asked for: the tight 10% stop still protects the lot.
    assertThat(second.getGivebackPct()).isEqualByComparingTo("0.10");
    // 2.55 (the mid — this is a copytrade position, so the tick loop compares mids) * 0.90.
    assertThat(second.getStopPrice()).isEqualByComparingTo("2.30");
  }

  @Test
  void armTrail_validatorRejectsGivebackAboveMax() throws Exception {
    // MAX_GIVEBACK is 0.5. A validator rejection never enters history, and it must agree with what
    // the automatic signal path would refuse — that is what chandelierArmRejection() shares.
    PositionWorkflow stub = newStub("pos-armtrail-maxgb");
    WorkflowStub.fromTyped(stub).start(futureInput(5));

    assertThatThrownBy(() -> stub.armTrail(armTrailRequest("ops-1", new BigDecimal("0.60"))))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("giveback_pct");
  }

  @Test
  void armTrail_validatorRejectsBlankOperator() throws Exception {
    PositionWorkflow stub = newStub("pos-armtrail-noop-id");
    WorkflowStub.fromTyped(stub).start(futureInput(5));

    assertThatThrownBy(() -> stub.armTrail(armTrailRequest("", new BigDecimal("0.20"))))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("operator_id");
  }

  private ArmTrailRequest armTrailRequest(String operatorId, BigDecimal giveback) {
    ArmTrailRequest r = new ArmTrailRequest();
    r.setSchemaVersion(1L);
    r.setOperatorId(operatorId);
    r.setGivebackPct(giveback);
    return r;
  }

  private ForceCloseRequest forceCloseRequest(String operatorId, String reason) {
    ForceCloseRequest r = new ForceCloseRequest();
    r.setSchemaVersion(1L);
    r.setOperatorId(operatorId);
    r.setReason(reason);
    return r;
  }

  private PartialCloseRequest partialCloseRequest(
      String operatorId, String reason, double fraction) {
    PartialCloseRequest r = new PartialCloseRequest();
    r.setSchemaVersion(1L);
    r.setOperatorId(operatorId);
    r.setReason(reason);
    r.setFraction(BigDecimal.valueOf(fraction));
    return r;
  }

  private RiskBreachPayload riskBreachPayload(String reason, String actor) {
    RiskBreachPayload p = new RiskBreachPayload();
    p.setSchemaVersion(1L);
    p.setReason(reason);
    p.setActor(actor);
    p.setOccurredAt(OffsetDateTime.now());
    return p;
  }

  private PartialExitRequest partialExitRequest(String signalId, String posWfId, double fraction) {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
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

  private OrderIntentResult cancelledResult() {
    OrderIntentResult r = submittedResult();
    r.setState(OrderIntentResult.State.CANCELLED);
    return r;
  }

  /**
   * F1 (partial-exit-cancel-terminal-state-reconcile): the exec cancel-on-filled race result — the
   * broker reports the order ALREADY_FILLED at cancel time, so the exec Activity pulls the fill
   * detail, marks the journal FILLED, and returns {@code state=FILLED} with the broker-confirmed
   * {@code filledQty}/{@code avgFillPrice}/{@code brokerOrderId}. This is the authoritative
   * terminal state the orchestrator must reconcile rather than discard.
   */
  private OrderIntentResult filledCancelResult(long filledQty, BigDecimal avgFillPrice) {
    return filledCancelResult(filledQty, avgFillPrice, "brk-cancel-filled");
  }

  /**
   * Issue #735: overload taking an explicit broker order id. Two placements of the SAME position
   * (an original {@code exit-key} and its {@code :retry-1}) are two DISTINCT broker orders and
   * therefore carry DISTINCT broker order ids — a broker never reissues one. The default overload
   * hardcodes a single id, which is harmless for tests that place once but silently models the
   * impossible when a test places twice. The #735 ledger keys on the broker order (the unit Alpaca
   * reports cumulative filled_qty against), so a test that shares one id across two orders is
   * asserting on a scenario that cannot occur.
   */
  private OrderIntentResult filledCancelResult(
      long filledQty, BigDecimal avgFillPrice, String brokerOrderId) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("exit-key");
    r.setBrokerOrderId(brokerOrderId);
    r.setState(OrderIntentResult.State.FILLED);
    r.setFilledQty(filledQty);
    r.setAvgFillPrice(avgFillPrice);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  /**
   * PLAN-over-exit-422: a placeOrder return for a BENIGN broker-confirmed already-flat over-exit —
   * state=CANCELLED with a NULL brokerOrderId (no order was created), mirroring what the exec
   * Activity surfaces after {@code markClosedAlreadyFlat}.
   */
  private OrderIntentResult alreadyClosedResult() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("exit-key");
    r.setBrokerOrderId(null);
    r.setState(OrderIntentResult.State.CANCELLED);
    r.setLastError("benign over-exit: broker-confirmed flat");
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  /**
   * Polls until a drained tick has been stamped. A tick is delivered as a SIGNAL, so it is enqueued
   * and drained by the main loop on a later workflow task — a query issued straight after the
   * signal legitimately races it. Same shape and deadline as {@link #waitForPlaceOrderCount(int)};
   * a timeout returns the still-null state so the caller's assertion reports the real failure
   * rather than the wait.
   */
  private TrailingState waitForTickObserved(PositionWorkflow stub) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    TrailingState st = stub.trailingState();
    while (System.currentTimeMillis() < deadline && st.lastTickObservedAt() == null) {
      Thread.sleep(50);
      st = stub.trailingState();
    }
    return st;
  }

  private void waitForPlaceOrderCount(int n) throws InterruptedException {
    // 50s deadline — CI runners under load have hit >25s waiting for signal-driven workflow
    // activity dispatch through TestWorkflowEnvironment; the happy path returns in <0.5s.
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

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  /**
   * Deterministic sync point for async audit emissions. Mirrors {@link #waitForPlaceOrderCount}:
   * poll (bounded) until at least one {@code audit.log(...)} call with the given kind has been
   * captured. Use this to serialize against events that are emitted asynchronously by the workflow
   * (e.g. {@code ExitQueued}) but that are not tied to a placeOrder count — without it a subsequent
   * signal/fill can race ahead of the emission and make a later {@link #captureKind} flaky.
   */
  private void waitForAuditKind(String kind) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      if (!captureAll(kind).isEmpty()) {
        return;
      }
      Thread.sleep(50);
    }
    if (captureAll(kind).isEmpty()) {
      throw new AssertionError("timed out waiting for audit event with kind=" + kind);
    }
  }

  private static long asLong(Object o) {
    if (o instanceof Number n) return n.longValue();
    throw new AssertionError("expected Number, got " + o);
  }

  private List<AuditEvent> captureAll(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream().filter(e -> kind.equals(e.getKind())).toList();
  }
}
