package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PositionWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private ExecActivities exec;
  private MarketCalendarActivities calendar;
  private SubscribePremiumActivity marketData;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    exec = Mockito.mock(ExecActivities.class);
    marketData = Mockito.mock(SubscribePremiumActivity.class);

    // Default calendar: no EOD/expiry pressure
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any())).thenReturn(Duration.ZERO);
    // Default market-data: subscription succeeds.
    when(marketData.subscribePremium(any())).thenReturn(subscribedResult());

    coreWorker.registerActivitiesImplementations(audit, calendar);
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec);
    Worker mdWorker = env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(marketData);

    env.start();
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
    t.setContractSymbol("NVDA  260516C00140000");
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
    WorkflowExecution exec1 = WorkflowStub.fromTyped(stub).start(input(5));
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
    assertThat(partialFills.get(0).getSubject())
        .containsEntry("option_symbol", "NVDA  260516C00140000");
    assertThat(partialFills.get(1).getSubject())
        .containsEntry("option_symbol", "NVDA  260516C00140000");
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
    WorkflowStub.fromTyped(stub).start(input(4));
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
    PositionWorkflowInput in = input(4);
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

  @Test
  void duplicateSignalId_isSuppressed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-dup");
    WorkflowStub.fromTyped(stub).start(input(5));
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
    WorkflowStub.fromTyped(stub).start(input(4));
    confirmEntry(stub, 4L);

    // Signal both before any fill arrives
    stub.partialExit(partialExitRequest("sig-A", "pos-queue", 0.5));
    stub.partialExit(partialExitRequest("sig-B", "pos-queue", 1.0));

    waitForPlaceOrderCount(1);
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
    WorkflowStub.fromTyped(stub).start(input(5));
    confirmEntry(stub, 5L);

    // Let virtual time advance past EOD
    env.sleep(Duration.ofMinutes(1));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-eod");

    AuditEvent requested = captureKind("EodForceFlattenRequested");
    assertThat(asLong(requested.getSubject().get("remaining_qty"))).isEqualTo(5L);

    captureKind("EodForceFlattened");
    verify(exec, atLeastOnce()).placeOrder(any());
  }

  @Test
  void expiryTimer_forceFlattensRemaining() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any())).thenReturn(Duration.ofMillis(200));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-expiry");
    WorkflowStub.fromTyped(stub).start(input(3));
    confirmEntry(stub, 3L);

    env.sleep(Duration.ofMinutes(1));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent req = captureKind("ExpiryForceFlattenRequested");
    assertThat(asLong(req.getSubject().get("remaining_qty"))).isEqualTo(3L);
    captureKind("ExpiryForceFlattened");
  }

  @Test
  void eodWithInFlight_cancelsThenFlattens() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofSeconds(30));
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-eod-inflight");
    WorkflowStub.fromTyped(stub).start(input(5));
    confirmEntry(stub, 5L);

    // Queue an STC but never deliver the fill — exit is in-flight when EOD fires.
    stub.partialExit(partialExitRequest("sig-inflight", "pos-eod-inflight", 0.5));
    waitForPlaceOrderCount(1);

    // Trigger EOD before fill arrives.
    env.sleep(Duration.ofMinutes(1));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, atLeastOnce()).cancelOrder(anyString());
    captureKind("EodForceFlattenRequested");
    captureKind("EodForceFlattened");
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
    WorkflowStub.fromTyped(stub).start(input(5));
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
    WorkflowStub.fromTyped(stub).start(input(3));
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

  // ---------- Phase 4: CHANDELIER_TRAIL ----------

  @Test
  void armChandelier_validInput_armsAndAuditsChandelierArmed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-valid");
    WorkflowStub.fromTyped(stub).start(input(5));
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
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(5));
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
    WorkflowStub.fromTyped(stub).start(input(5));
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

    // Workflow auto-flattens on fire; wait for the flatten placeOrder.
    waitForPlaceOrderCount(1);
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent fired = captureKind("ChandelierTrailFired");
    assertThat(((Number) fired.getSubject().get("threshold")).doubleValue()).isEqualTo(2.70);
    assertThat(((Number) fired.getSubject().get("trigger_premium")).doubleValue()).isEqualTo(2.70);
  }

  @Test
  void chandelierTick_risingThenGivebackBreach_firesFlattenRemaining() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-rising");
    WorkflowStub.fromTyped(stub).start(input(5));
    confirmEntry(stub, 5L);

    stub.armChandelier(
        armPayload("pos-tick-rising", "src-sig-1", new BigDecimal("3.00"), new BigDecimal("0.15")));
    // Ratchet to 4.00 -> threshold = 4.00 * 0.85 = 3.40
    stub.chandelierTick(tick(new BigDecimal("4.00")));
    // 3.30 <= 3.40 -> fire
    stub.chandelierTick(tick(new BigDecimal("3.30")));

    waitForPlaceOrderCount(1);
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
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(5));
    confirmEntry(stub, 5L);

    ForceCloseResult result = stub.forceClose(forceCloseRequest("ops-1", "manual intervention"));
    assertThat(result.getStatus()).isEqualTo(ForceCloseResult.Status.ACCEPTED);
    assertThat(result.getExitSignalId()).startsWith("force:ops-1:");

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent req = captureKind("ForceCloseRequested");
    assertThat(req.getSubject())
        .containsEntry("operator_id", "ops-1")
        .containsEntry("reason", "manual intervention");
    // flattenRemaining re-uses EOD audit kinds; the subject's reason disambiguates downstream.
    AuditEvent flatReq = captureKind("EodForceFlattenRequested");
    assertThat(flatReq.getSubject()).containsEntry("reason", "force_close");
    captureKind("EodForceFlattened");
  }

  @Test
  void forceCloseValidator_blankOperatorId_rejects() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-force-blank-op");
    WorkflowStub.fromTyped(stub).start(input(3));
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
    WorkflowStub.fromTyped(stub).start(input(3));
    confirmEntry(stub, 3L);

    assertThatThrownBy(() -> stub.forceClose(forceCloseRequest("ops-2", "")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("reason");

    stub.partialExit(partialExitRequest("sig-drain", "pos-force-blank-reason", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-drain", 3L, new BigDecimal("3.10")));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  @Test
  void riskBreach_healthyPosition_flattens() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-risk-breach");
    WorkflowStub.fromTyped(stub).start(input(4));
    confirmEntry(stub, 4L);

    stub.riskBreach(riskBreachPayload("auto:daily_loss", "auto:daily_loss"));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent acted = captureKind("RiskBreachActed");
    assertThat(acted.getSubject()).containsEntry("reason", "auto:daily_loss");
    // flattenRemaining audit (re-uses EOD kinds; subject.reason disambiguates).
    AuditEvent flatReq = captureKind("EodForceFlattenRequested");
    assertThat(flatReq.getSubject()).containsEntry("reason", "risk_breach");
    captureKind("EodForceFlattened");
  }

  @Test
  void riskBreach_inFlight_cancelsThenFlattens() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-risk-breach-inflight");
    WorkflowStub.fromTyped(stub).start(input(5));
    confirmEntry(stub, 5L);

    // Queue an STC that won't be filled — exit is in flight when risk_breach arrives.
    stub.partialExit(partialExitRequest("sig-inflight", "pos-risk-breach-inflight", 0.5));
    waitForPlaceOrderCount(1);

    stub.riskBreach(riskBreachPayload("manual:operator", "ops-3"));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, atLeastOnce()).cancelOrder(anyString());
    captureKind("RiskBreachActed");
  }

  // ---------- Phase 2c.2: broker_target routing on PositionWorkflowInput ----------

  @Test
  void runWithBrokerTargetRoutesToBrokerQueue() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-bt-alpaca");
    PositionWorkflowInput in = input(3);
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
    WorkflowStub.fromTyped(stub).start(input(2));
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
    PositionWorkflowInput in = input(5);
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
   * Issue #202: null {@code eod_force_flatten} is treated as true to preserve back-compat for
   * pre-#202 replays — the EOD timer still arms.
   */
  @Test
  void eodTimer_armsWhenEodForceFlattenNull() throws Exception {
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(100));
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-null-eod-flatten");
    PositionWorkflowInput in = input(3);
    // eod_force_flatten left null — the default-true contract.
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 3L);

    env.sleep(Duration.ofMinutes(1));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    captureKind("EodForceFlattenRequested");
    captureKind("EodForceFlattened");
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
        .containsEntry("contract_symbol", "NVDA  260516C00140000");
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
    WorkflowStub.fromTyped(stub).start(input(5));
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
    PositionWorkflowInput in = input(1);
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
   * Issue #205 Done-when 3b: when {@code min_partial_qty_behavior=full_close}, the same edge
   * (remainingQty=1 + fraction=0.5) MUST place a 1-contract close order — the runner is flushed on
   * the partial signal. PartialExitFilled fires normally; PartialExitSkippedMinQty does NOT.
   */
  @Test
  void partialExit_remainingQty1_fraction0_5_fullCloseBranch_placesOneContractCloseOrder()
      throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-min-qty-full-close");
    PositionWorkflowInput in = input(1);
    in.setMinPartialQtyBehavior(PositionWorkflowInput.MinPartialQtyBehavior.FULL_CLOSE);
    WorkflowStub.fromTyped(stub).start(in);
    confirmEntry(stub, 1L);

    stub.partialExit(partialExitRequest("sig-flush", "pos-min-qty-full-close", 0.5));
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

    // The PartialExitRequested audit reflects the rounded-up qty_to_close=1.
    AuditEvent requested = captureKind("PartialExitRequested");
    assertThat(asLong(requested.getSubject().get("qty_to_close"))).isEqualTo(1L);
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
        .containsEntry("contract_symbol", "NVDA  260516C00140000");

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
    PositionWorkflowInput in = input(5);
    in.setExitFillTtlSecs(20L);
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
    WorkflowStub.fromTyped(stub).start(input(4));
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

    // Exactly one retry-requested audit carrying the original signal_id, retry_attempt=1, and
    // the source_premium provenance (no chandelier tick yet → ref_premium fallback).
    List<AuditEvent> retries = captureAll("PartialExitRetryRequested");
    assertThat(retries).hasSize(1);
    assertThat(retries.get(0).getSubject())
        .containsEntry("signal_id", "sig-retry")
        .containsEntry("source_premium", "ref_premium");
    assertThat(asLong(retries.get(0).getSubject().get("retry_attempt"))).isEqualTo(1L);
    // intent_key has the :retry suffix so it's distinct from the original.
    assertThat((String) retries.get(0).getSubject().get("intent_key")).endsWith(":retry");

    // The retry order placed by placeOrder() carries the :retry intent_key.
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeast(2)).placeOrder(intentCaptor.capture());
    List<OrderIntent> capturedIntents = intentCaptor.getAllValues();
    assertThat(capturedIntents.stream().map(OrderIntent::getIntentKey))
        .anyMatch(k -> k != null && k.endsWith(":retry"));

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
    WorkflowStub.fromTyped(stub).start(input(5));
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
   * Issue #216: when the chandelier trail is armed and has received at least one tick before the
   * exit-fill timeout fires, the retry's fresh limit price is sourced from {@code lastTickPremium}
   * (most recent mid) rather than the original {@code req.getRefPremium()}. Drives the {@code
   * source_premium=last_tick_premium} provenance in the {@code PartialExitRetryRequested} audit
   * subject.
   */
  @Test
  void processOne_exitFillTimeoutRetry_chandelierTickDrivesFreshLimitSource() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-retry-tick");
    WorkflowStub.fromTyped(stub).start(input(3));
    confirmEntry(stub, 3L);

    // Arm the chandelier (peak=2.85, giveback=0.15 → threshold=2.4225) and seed a tick well above
    // threshold so the trail does NOT fire — only the lastTickPremium gets latched on state.
    stub.armChandelier(
        armPayload(
            "pos-retry-tick", "src-arm-216", new BigDecimal("2.85"), new BigDecimal("0.15")));
    stub.chandelierTick(tick(new BigDecimal("2.70")));

    // STC arrives; the original limit order never fills.
    stub.partialExit(partialExitRequest("sig-tick-retry", "pos-retry-tick", 0.5));
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-retry-tick", 2L, new BigDecimal("2.65")));

    // Drain the remaining runner.
    stub.partialExit(partialExitRequest("sig-tick-close", "pos-retry-tick", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-tick-close", 1L, new BigDecimal("2.60")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent retry = captureKind("PartialExitRetryRequested");
    assertThat(retry.getSubject())
        .containsEntry("signal_id", "sig-tick-retry")
        .containsEntry("source_premium", "last_tick_premium");
    // fresh_limit_price reflects the latched tick premium (2.70), not the original ref (2.85).
    assertThat(((Number) retry.getSubject().get("fresh_limit_price")).doubleValue())
        .isEqualTo(2.70);
  }

  /**
   * Issue #227: when the chandelier is armed (peak populated) but no tick has arrived (so {@code
   * lastTickPremium} stays null) AND the request carries a non-null {@code refPremium}, the retry's
   * fresh limit price MUST be sourced from {@code req.getRefPremium()} — NOT from {@code
   * peakPremium}. Rationale: {@code peakPremium} is a high-water-mark biased high for SELL exits;
   * the author-posted {@code refPremium} is the closest fresh-quote proxy when no tick has fired.
   * The new order is {@code lastTickPremium → refPremium → peakPremium}; this test exercises the
   * middle branch (ref wins over peak).
   */
  @Test
  void processOne_exitFillTimeoutRetry_refPremiumPreferredOverPeak() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-retry-ref-over-peak");
    WorkflowStub.fromTyped(stub).start(input(3));
    confirmEntry(stub, 3L);

    // Arm the chandelier so peakPremium=2.85 is latched, but do NOT fire a tick — lastTickPremium
    // stays null. Under the new order, refPremium (3.10) MUST win over peakPremium (2.85).
    stub.armChandelier(
        armPayload(
            "pos-retry-ref-over-peak",
            "src-arm-227",
            new BigDecimal("2.85"),
            new BigDecimal("0.15")));

    // STC with an explicit refPremium=3.10 (distinct from the armed peak=2.85 so the audit
    // assertion is unambiguous).
    PartialExitRequest req =
        partialExitRequest("sig-ref-over-peak", "pos-retry-ref-over-peak", 0.5);
    req.setRefPremium(new BigDecimal("3.10"));
    stub.partialExit(req);
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-retry-ref-over-peak", 2L, new BigDecimal("3.05")));

    // Drain the remaining runner.
    stub.partialExit(partialExitRequest("sig-ref-over-peak-close", "pos-retry-ref-over-peak", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-ref-over-peak-close", 1L, new BigDecimal("3.00")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent retry = captureKind("PartialExitRetryRequested");
    assertThat(retry.getSubject())
        .containsEntry("signal_id", "sig-ref-over-peak")
        .containsEntry("source_premium", "ref_premium");
    // fresh_limit_price reflects refPremium (3.10), NOT the armed peakPremium (2.85).
    assertThat(((Number) retry.getSubject().get("fresh_limit_price")).doubleValue())
        .isEqualTo(3.10);
  }

  /**
   * Issue #227: when both {@code lastTickPremium} AND {@code req.getRefPremium()} are null/zero,
   * {@code peakPremium} is the last-resort source. Drives the {@code source_premium=peak_premium}
   * audit and confirms peak only wins when both higher-priority sources are absent.
   */
  @Test
  void processOne_exitFillTimeoutRetry_peakPremiumOnlyWhenLastTickAndRefAbsent() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult());

    PositionWorkflow stub = newStub("pos-retry-peak-fallback");
    WorkflowStub.fromTyped(stub).start(input(3));
    confirmEntry(stub, 3L);

    // Arm the chandelier with peak=2.85; no tick → lastTickPremium null.
    stub.armChandelier(
        armPayload(
            "pos-retry-peak-fallback",
            "src-arm-227-peak",
            new BigDecimal("2.85"),
            new BigDecimal("0.15")));

    // STC with refPremium explicitly null so peak is the only available source.
    PartialExitRequest req =
        partialExitRequest("sig-peak-fallback", "pos-retry-peak-fallback", 0.5);
    req.setRefPremium(null);
    stub.partialExit(req);
    waitForPlaceOrderCount(1);
    env.sleep(Duration.ofSeconds(120));
    waitForPlaceOrderCount(2);
    stub.onFill(fill("brk-retry-peak-fallback", 2L, new BigDecimal("2.80")));

    // Drain the remaining runner.
    stub.partialExit(partialExitRequest("sig-peak-fallback-close", "pos-retry-peak-fallback", 1.0));
    waitForPlaceOrderCount(3);
    stub.onFill(fill("brk-peak-fallback-close", 1L, new BigDecimal("2.75")));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent retry = captureKind("PartialExitRetryRequested");
    assertThat(retry.getSubject())
        .containsEntry("signal_id", "sig-peak-fallback")
        .containsEntry("source_premium", "peak_premium");
    assertThat(((Number) retry.getSubject().get("fresh_limit_price")).doubleValue())
        .isEqualTo(2.85);
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
    PositionWorkflowInput in = input(4);
    // Keep TTLs short so the test runs quickly under virtual time.
    in.setExitFillTtlSecs(2L);
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
    WorkflowStub.fromTyped(stub).start(input(5));
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
    // cancels that key.
    env.sleep(Duration.ofSeconds(60));

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
    // Original timeout cancel hits the :exit:<sig> key (no :retry suffix).
    assertThat(cancelledKeys)
        .as("processOne's original-timeout cancel must hit the original intent_key")
        .anyMatch(k -> k.endsWith(":exit:sig-eod-retry"));
    // EOD-during-retry cancel from flattenRemaining hits the :retry-suffixed key.
    assertThat(cancelledKeys)
        .as("flattenRemaining must cancel the live retry intent_key, not the original")
        .anyMatch(k -> k.endsWith(":exit:sig-eod-retry:retry"));
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
    in.setContractSymbol("NVDA  260516C00140000");
    in.setQty(qty);
    in.setEntryPremium(new BigDecimal("2.30"));
    return in;
  }

  private ForceCloseRequest forceCloseRequest(String operatorId, String reason) {
    ForceCloseRequest r = new ForceCloseRequest();
    r.setSchemaVersion(1L);
    r.setOperatorId(operatorId);
    r.setReason(reason);
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
