package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the fill-dispatch contract: journal lookup by broker_order_id → workflow ID via {@code
 * WorkflowIds.copytradeSignal} → untyped Temporal signal on {@code onFill} with a contract-owned
 * {@link FillSignalPayload}. Tests the four documented outcomes (happy path, unknown order,
 * workflow already completed, other Temporal error).
 */
class FillDispatcherImplTest {

  private OrderIntentJournal journal;
  private WorkflowClient workflowClient;
  private WorkflowStub workflowStub;
  private SimpleMeterRegistry registry;
  private FillListenerMetrics metrics;
  private FillDispatcherImpl dispatcher;

  private static final BrokerFillEvent FILL =
      new BrokerFillEvent(
          "brk-42",
          "ck-42",
          5L,
          new BigDecimal("0.84"),
          OffsetDateTime.parse("2026-05-19T17:08:11Z"),
          BrokerFillEvent.Source.WS);

  private static final JournaledOrder ROW =
      new JournaledOrder(
          "ck-42",
          "sig-42",
          "dev",
          "copytrade-v1",
          "alpaca-paper",
          "ck-42",
          "SPY   260519C00737000",
          "BUY",
          5L,
          new BigDecimal("0.84"),
          OrderState.SUBMITTED,
          "brk-42",
          OffsetDateTime.parse("2026-05-19T17:08:00Z"),
          OffsetDateTime.parse("2026-05-19T17:08:01Z"),
          OffsetDateTime.parse("2026-05-19T17:08:01Z"),
          null,
          null,
          null,
          null,
          null,
          1L);

  // Realistic STC intent_key from PositionWorkflowImpl: <position-wf-id> + ":exit:" + signalId.
  // The dispatcher must extract the position workflow ID prefix and signal IT (not the
  // CopytradeSignalWorkflow derived from the row's signalId).
  private static final String STC_INTENT_KEY =
      "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/sig-bto-1:exit:sig-stc-7";
  private static final String STC_POSITION_WF_ID =
      "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/sig-bto-1";

  private static final JournaledOrder STC_ROW =
      new JournaledOrder(
          STC_INTENT_KEY,
          "sig-stc-7",
          "dev",
          "copytrade-v1",
          "alpaca-paper",
          STC_INTENT_KEY,
          "NVDA  260516C00140000",
          "SELL",
          3L,
          new BigDecimal("2.10"),
          OrderState.SUBMITTED,
          "brk-stc-7",
          OffsetDateTime.parse("2026-05-25T14:00:00Z"),
          OffsetDateTime.parse("2026-05-25T14:00:01Z"),
          OffsetDateTime.parse("2026-05-25T14:00:01Z"),
          null,
          null,
          null,
          null,
          null,
          1L);

  private static final BrokerFillEvent STC_FILL =
      new BrokerFillEvent(
          "brk-stc-7",
          STC_INTENT_KEY,
          3L,
          new BigDecimal("2.15"),
          OffsetDateTime.parse("2026-05-25T14:00:15Z"),
          BrokerFillEvent.Source.WS);

  @BeforeEach
  void setUp() {
    journal = mock(OrderIntentJournal.class);
    workflowClient = mock(WorkflowClient.class);
    workflowStub = mock(WorkflowStub.class);
    registry = new SimpleMeterRegistry();
    metrics = new FillListenerMetrics(registry);
    dispatcher = new FillDispatcherImpl(journal, workflowClient, metrics);

    when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(workflowStub);
  }

  @Test
  void dispatch_routesFillToWorkflowSignal() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));

    dispatcher.dispatch(FILL);

    // #244: the journal is terminalized to FILLED inside dispatch() — keyed on the resolved
    // intent_key — BEFORE the signal, so the row reaches FILLED even if the signal target has
    // already completed. markFilled is conditional on RECORDED/SUBMITTED, so the repeat is a no-op.
    ArgumentCaptor<Long> filledQty = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<BigDecimal> avgPrice = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<OffsetDateTime> filledAt = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(journal)
        .markFilled(eq("ck-42"), filledQty.capture(), avgPrice.capture(), filledAt.capture());
    assertThat(filledQty.getValue()).isEqualTo(5L);
    assertThat(avgPrice.getValue()).isEqualByComparingTo(new BigDecimal("0.84"));
    assertThat(filledAt.getValue()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");

    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(name.capture(), arg.capture());

    assertThat(name.getValue()).isEqualTo("onFill");
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.getBrokerOrderId()).isEqualTo("brk-42");
              assertThat(p.getFilledQty()).isEqualTo(5L);
              assertThat(p.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
              assertThat(p.getFilledAt()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));
            });

    // Success path is the ONLY path that bumps events_dispatched_total — pinning here so a future
    // refactor that moves the counter back out of the dispatcher (or accidentally bumps it on a
    // drop path) trips this test instead of silently mis-counting.
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_stcIntentKey_routesToPositionWorkflow() {
    // Pins the STC routing branch: intent_key contains ":exit:" → workflow ID is the prefix
    // before the marker (= PositionWorkflow ID). Without this branch the dispatcher would
    // resolve to the short-lived STC CopytradeSignalWorkflow (already completed), the
    // PositionWorkflow would block on lastFillEvent until EOD, and PartialExitFilled would
    // never land in audit_log.
    when(journal.findByBrokerOrderId("brk-stc-7")).thenReturn(Optional.of(STC_ROW));
    when(journal.markFilled(eq(STC_INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(STC_FILL);

    // #244: STC exit fills are terminalized to FILLED on the row keyed by the exit intent_key too.
    verify(journal).markFilled(eq(STC_INTENT_KEY), eq(3L), any(), any());
    verify(workflowClient).newUntypedWorkflowStub(STC_POSITION_WF_ID);
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(eq("onFill"), arg.capture());
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.getBrokerOrderId()).isEqualTo("brk-stc-7");
              assertThat(p.getFilledQty()).isEqualTo(3L);
              assertThat(p.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("2.15"));
            });
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
  }

  @Test
  void dispatch_unknownBrokerOrder_andUnknownClientOrder_dropsEvent() {
    // #244: a truly unknown fill resolves NEITHER by broker_order_id NOR by the
    // client_order_id (= intent_key) fallback — only then is it counted unknown and dropped.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.empty());
    when(journal.findByIntentKey("ck-42")).thenReturn(Optional.empty());

    dispatcher.dispatch(FILL);

    verify(journal, never()).markFilled(anyString(), anyLong(), any(), any());
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString());
    verify(workflowStub, never()).signal(anyString(), org.mockito.ArgumentMatchers.any());
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_brokerOrderIdNotYetPersisted_resolvesByClientOrderId_terminalizesJournal() {
    // #244 ROOT CAUSE: the near-instant-fill race. The WS fill arrives in the ~26ms window AFTER
    // broker.placeOrder returns but BEFORE ExecActivitiesImpl.placeOrder runs
    // markSubmittedIfRecorded(intentKey, brokerOrderId) — so the row has no broker_order_id yet
    // and findByBrokerOrderId is empty. Because client_order_id == intent_key (set at upsertIntent
    // and passed to the broker), the dispatcher resolves the row by event.clientOrderId() and
    // terminalizes the journal to FILLED. WITHOUT this the fill was logged unknown + dropped and
    // the row stayed stuck SUBMITTED, stranding the position.
    JournaledOrder recordedRow =
        new JournaledOrder(
            "ck-42",
            "sig-42",
            "dev",
            "copytrade-v1",
            "alpaca-paper",
            "ck-42",
            "SPY   260519C00737000",
            "BUY",
            5L,
            new BigDecimal("0.84"),
            OrderState.SUBMITTED,
            null, // broker_order_id NOT yet persisted — the race window
            OffsetDateTime.parse("2026-05-19T17:08:00Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            null,
            null,
            null,
            null,
            null,
            1L);
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.empty());
    when(journal.findByIntentKey("ck-42")).thenReturn(Optional.of(recordedRow));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(FILL);

    // Journal reaches FILLED with correct fill detail — the AC #2 outcome.
    verify(journal)
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
    // The fill is NOT dropped as unknown.
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(0.0);
    // Still signals the originating workflow.
    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");
    verify(workflowStub).signal(eq("onFill"), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dispatch_idempotentOnRepeat_singleEffectiveFill() {
    // #244 AC #2 idempotency: the same fill arriving twice (WS then POLL) terminalizes once.
    // markFilled is conditional on state in (RECORDED, SUBMITTED), so the second call returns
    // false (no-op) and qty/price are not corrupted. The dispatcher must invoke markFilled on
    // both deliveries (the journal enforces the no-op) and must not double-count or throw.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any()))
        .thenReturn(true) // first delivery transitions SUBMITTED → FILLED
        .thenReturn(false); // second delivery is a no-op (already terminal)

    dispatcher.dispatch(FILL);
    dispatcher.dispatch(FILL);

    // Always with the SAME fill detail — a repeat cannot rewrite qty/price.
    verify(journal, times(2))
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
  }

  @Test
  void dispatch_workflowAlreadyCompleted_swallowsException_butJournalStillTerminalized() {
    // #244: the strand fix. Even when the onFill signal target has already completed
    // (WorkflowNotFoundException, swallowed as benign), the journal MUST still be terminalized to
    // FILLED — markFilled runs before the signal — so the row never sticks at SUBMITTED. Previously
    // the dispatcher only signalled and swallowed the NOT_FOUND, leaving the row stranded.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId("t-dev/s-copytrade-v1/sig/sig-42").build();
    doThrow(new WorkflowNotFoundException(exec, "CopytradeSignalWorkflow", null))
        .when(workflowStub)
        .signal(eq("onFill"), org.mockito.ArgumentMatchers.any());

    dispatcher.dispatch(FILL); // must not throw

    verify(journal)
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_otherTemporalException_propagates_afterJournalTerminalized() {
    // #244: the journal is terminalized BEFORE the signal, so a hard (non-NOT_FOUND) Temporal
    // failure still leaves the row FILLED and propagates for retry — the position is never
    // stranded even if the signal genuinely fails.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);
    doThrow(new RuntimeException("temporal boom"))
        .when(workflowStub)
        .signal(eq("onFill"), org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> dispatcher.dispatch(FILL)).hasMessage("temporal boom");

    verify(journal).markFilled(eq("ck-42"), anyLong(), any(), any());
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }
}
