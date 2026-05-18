package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
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

    // Signal half-out
    stub.partialExit(partialExitRequest("sig-1", "pos-happy", 0.5));
    // Wait for the workflow to call exec.placeOrder before signalling fill.
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-1", 3L, new BigDecimal("2.85"), OffsetDateTime.now()));

    // Then full close
    stub.partialExit(partialExitRequest("sig-2", "pos-happy", 1.0));
    waitForPlaceOrderCount(2);
    stub.onFill(new FillEvent("brk-2", 2L, new BigDecimal("3.10"), OffsetDateTime.now()));

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(result).isEqualTo("pos-happy");

    verify(exec, times(2)).placeOrder(any());
    AuditEvent closed = captureKind("PositionClosed");
    assertThat(asLong(closed.getSubject().get("remaining_qty"))).isEqualTo(0L);

    List<AuditEvent> partialFills = captureAll("PartialExitFilled");
    assertThat(partialFills).hasSize(2);
    assertThat(asLong(partialFills.get(0).getSubject().get("qty_filled"))).isEqualTo(3L);
    assertThat(asLong(partialFills.get(1).getSubject().get("qty_filled"))).isEqualTo(2L);
  }

  @Test
  void duplicateSignalId_isSuppressed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-dup");
    WorkflowStub.fromTyped(stub).start(input(5));

    stub.partialExit(partialExitRequest("sig-dup", "pos-dup", 1.0));
    waitForPlaceOrderCount(1);
    // Second signal with same signal_id — should be a no-op.
    stub.partialExit(partialExitRequest("sig-dup", "pos-dup", 1.0));
    stub.onFill(new FillEvent("brk-1", 5L, new BigDecimal("3.0"), OffsetDateTime.now()));

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

    // Signal both before any fill arrives
    stub.partialExit(partialExitRequest("sig-A", "pos-queue", 0.5));
    stub.partialExit(partialExitRequest("sig-B", "pos-queue", 1.0));

    waitForPlaceOrderCount(1);
    // First fill closes 2 of 4
    stub.onFill(new FillEvent("brk-A", 2L, new BigDecimal("2.85"), OffsetDateTime.now()));
    waitForPlaceOrderCount(2);
    stub.onFill(new FillEvent("brk-B", 2L, new BigDecimal("2.90"), OffsetDateTime.now()));

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

  // ---------- Phase 4: CHANDELIER_TRAIL ----------

  @Test
  void armChandelier_validInput_armsAndAuditsChandelierArmed() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-valid");
    WorkflowStub.fromTyped(stub).start(input(5));

    stub.armChandelier(
        armPayload("pos-arm-valid", "src-sig-1", new BigDecimal("2.85"), new BigDecimal("0.15")));

    // Drain to completion so the workflow terminates cleanly.
    stub.partialExit(partialExitRequest("sig-close", "pos-arm-valid", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 5L, new BigDecimal("3.10"), OffsetDateTime.now()));
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

    stub.armChandelier(armPayload("pos-arm-bad-peak", "src-sig-bp", null, new BigDecimal("0.15")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-bad-peak", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 3L, new BigDecimal("3.00"), OffsetDateTime.now()));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    AuditEvent rej = captureKind("ChandelierArmRejected");
    assertThat(rej.getSubject()).containsEntry("reason", "invalid_peak");
  }

  @Test
  void armChandelier_invalidGiveback_rejectsAndAuditsArmRejected() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-arm-bad-gb");
    WorkflowStub.fromTyped(stub).start(input(3));

    stub.armChandelier(
        armPayload("pos-arm-bad-gb", "src-sig-bg", new BigDecimal("2.85"), new BigDecimal("0.60")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-bad-gb", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 3L, new BigDecimal("3.00"), OffsetDateTime.now()));
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

    stub.armChandelier(
        armPayload(
            "pos-arm-subfail", "src-sig-sf", new BigDecimal("2.85"), new BigDecimal("0.15")));

    // A subsequent tick must NOT fire (workflow not armed).
    stub.chandelierTick(tick(new BigDecimal("2.40")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-subfail", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 3L, new BigDecimal("3.00"), OffsetDateTime.now()));
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

    stub.armChandelier(
        armPayload("pos-arm-second", "src-sig-A", new BigDecimal("2.85"), new BigDecimal("0.15")));
    stub.armChandelier(
        armPayload("pos-arm-second", "src-sig-B", new BigDecimal("3.10"), new BigDecimal("0.10")));

    stub.partialExit(partialExitRequest("sig-close", "pos-arm-second", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 3L, new BigDecimal("3.00"), OffsetDateTime.now()));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    // Only one ChandelierArmed audit emitted.
    assertThat(captureAll("ChandelierArmed")).hasSize(1);
  }

  @Test
  void chandelierTick_beforeArm_ignored() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-before-arm");
    WorkflowStub.fromTyped(stub).start(input(3));

    stub.chandelierTick(tick(new BigDecimal("1.00")));

    stub.partialExit(partialExitRequest("sig-close", "pos-tick-before-arm", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 3L, new BigDecimal("3.00"), OffsetDateTime.now()));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    assertThat(captureAll("ChandelierTrailFired")).isEmpty();
  }

  @Test
  void chandelierTick_belowPeakAboveThreshold_noFire() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-near-no-fire");
    WorkflowStub.fromTyped(stub).start(input(5));

    // peak=3.00, gb=0.15 -> threshold = 3.00 * 0.85 = 2.55
    stub.armChandelier(
        armPayload(
            "pos-tick-near-no-fire", "src-sig-1", new BigDecimal("3.00"), new BigDecimal("0.15")));
    // tick=2.60 -> 2.60 > 2.55, no fire.
    stub.chandelierTick(tick(new BigDecimal("2.60")));

    stub.partialExit(partialExitRequest("sig-close", "pos-tick-near-no-fire", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 5L, new BigDecimal("2.85"), OffsetDateTime.now()));
    WorkflowStub.fromTyped(stub).getResult(String.class);

    assertThat(captureAll("ChandelierTrailFired")).isEmpty();
  }

  @Test
  void chandelierTick_tickAtExactThreshold_fires() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    PositionWorkflow stub = newStub("pos-tick-exact-threshold");
    WorkflowStub.fromTyped(stub).start(input(5));

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

    stub.armChandelier(
        armPayload("pos-unarmed-stc", "src-sig-1", new BigDecimal("2.85"), new BigDecimal("0.15")));

    // Drain to remaining=0 via STC (not chandelier).
    stub.partialExit(partialExitRequest("sig-close", "pos-unarmed-stc", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-x", 3L, new BigDecimal("3.10"), OffsetDateTime.now()));
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

    assertThatThrownBy(() -> stub.forceClose(forceCloseRequest("", "reason ok")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("operator_id");

    // Workflow still healthy; drain to clean shutdown.
    stub.partialExit(partialExitRequest("sig-drain", "pos-force-blank-op", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-drain", 3L, new BigDecimal("3.10"), OffsetDateTime.now()));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  @Test
  void forceCloseValidator_blankReason_rejects() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-force-blank-reason");
    WorkflowStub.fromTyped(stub).start(input(3));

    assertThatThrownBy(() -> stub.forceClose(forceCloseRequest("ops-2", "")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("reason");

    stub.partialExit(partialExitRequest("sig-drain", "pos-force-blank-reason", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-drain", 3L, new BigDecimal("3.10"), OffsetDateTime.now()));
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  @Test
  void riskBreach_healthyPosition_flattens() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    PositionWorkflow stub = newStub("pos-risk-breach");
    WorkflowStub.fromTyped(stub).start(input(4));

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

    // Drain via a partial close so the workflow actually dispatches exec.placeOrder. The
    // exec mock is registered on the broker-alpaca-paper worker; a successful call confirms
    // the workflow routed Activities to that task queue.
    stub.partialExit(partialExitRequest("sig-bt-alpaca", "pos-bt-alpaca", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-bt-1", 3L, new BigDecimal("3.00"), OffsetDateTime.now()));

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

    stub.partialExit(partialExitRequest("sig-bt-default", "pos-bt-default", 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(new FillEvent("brk-bt-2", 2L, new BigDecimal("3.05"), OffsetDateTime.now()));

    WorkflowStub.fromTyped(stub).getResult(String.class);

    verify(exec, times(1)).placeOrder(any());
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
