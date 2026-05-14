package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
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

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    exec = Mockito.mock(ExecActivities.class);

    // Default calendar: no EOD/expiry pressure
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any())).thenReturn(Duration.ZERO);

    coreWorker.registerActivitiesImplementations(audit, calendar);
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_PAPER);
    brokerWorker.registerActivitiesImplementations(exec);

    env.start();
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
    long deadline = System.currentTimeMillis() + 10_000;
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
