package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the polling fallback contract: scan SUBMITTED rows older than the grace window, ask the
 * broker, route any FILLED row through the shared {@link FillDispatcher} with {@link
 * BrokerFillEvent.Source#POLL}, and respect the batch cap.
 */
class FillPollerTest {

  private static final Instant NOW = Instant.parse("2026-05-23T20:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private OrderIntentJournal journal;
  private OptionsBroker broker;
  private FillDispatcher dispatcher;
  private SimpleMeterRegistry registry;
  private FillListenerMetrics metrics;
  private FillPoller poller;
  private FillPollerProperties props;

  @BeforeEach
  void setUp() {
    journal = mock(OrderIntentJournal.class);
    broker = mock(OptionsBroker.class);
    dispatcher = mock(FillDispatcher.class);
    registry = new SimpleMeterRegistry();
    metrics = new FillListenerMetrics(registry);
    props = new FillPollerProperties(true, 30_000L, 60_000L, 50);
    poller = new FillPoller(journal, broker, dispatcher, metrics, props, FIXED_CLOCK);
  }

  @Test
  void runOnce_filledRow_dispatchesWithPollSource() {
    JournaledOrder row = row("ck-1", "brk-1");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-1")).thenReturn(BrokerOrderStatus.FILLED);
    when(broker.getFillDetail("brk-1"))
        .thenReturn(
            new BrokerFillDetail(
                7L, new BigDecimal("1.45"), OffsetDateTime.parse("2026-05-23T19:58:00Z")));

    poller.runOnce();

    ArgumentCaptor<BrokerFillEvent> captor = ArgumentCaptor.forClass(BrokerFillEvent.class);
    verify(dispatcher).dispatch(captor.capture());

    BrokerFillEvent dispatched = captor.getValue();
    assertThat(dispatched.brokerOrderId()).isEqualTo("brk-1");
    assertThat(dispatched.clientOrderId()).isEqualTo("ck-1");
    assertThat(dispatched.filledQty()).isEqualTo(7L);
    assertThat(dispatched.avgFillPrice()).isEqualByComparingTo(new BigDecimal("1.45"));
    assertThat(dispatched.filledAt()).isEqualTo(OffsetDateTime.parse("2026-05-23T19:58:00Z"));
    assertThat(dispatched.source()).isEqualTo(BrokerFillEvent.Source.POLL);

    assertThat(registry.counter("fill_listener.poll_cycles").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.poll_rows_scanned").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.poll_fills_detected").count()).isEqualTo(1.0);
  }

  @Test
  void runOnce_stillOpen_doesNotDispatch() {
    JournaledOrder row = row("ck-2", "brk-2");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-2")).thenReturn(BrokerOrderStatus.OPEN);

    poller.runOnce();

    verify(dispatcher, never()).dispatch(any());
    verify(broker, never()).getFillDetail(any());
    assertThat(registry.counter("fill_listener.poll_rows_scanned").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.poll_fills_detected").count()).isEqualTo(0.0);
  }

  @Test
  void runOnce_emptyJournal_makesNoBrokerCalls() {
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of());

    poller.runOnce();

    verifyNoInteractions(broker);
    verify(dispatcher, never()).dispatch(any());
    assertThat(registry.counter("fill_listener.poll_cycles").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.poll_rows_scanned").count()).isEqualTo(0.0);
  }

  @Test
  void runOnce_passesCutoffMinusGraceToJournal() {
    when(journal.findSubmittedOlderThan(any(), anyInt())).thenReturn(List.of());

    poller.runOnce();

    ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(journal).findSubmittedOlderThan(cutoff.capture(), eq(50));
    OffsetDateTime expected = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(60);
    assertThat(cutoff.getValue()).isEqualTo(expected);
  }

  @Test
  void runOnce_respectsBatchSize() {
    FillPollerProperties tinyBatch = new FillPollerProperties(true, 30_000L, 60_000L, 3);
    poller = new FillPoller(journal, broker, dispatcher, metrics, tinyBatch, FIXED_CLOCK);
    when(journal.findSubmittedOlderThan(any(), eq(3))).thenReturn(List.of());

    poller.runOnce();

    verify(journal).findSubmittedOlderThan(any(), eq(3));
  }

  @Test
  void runOnce_journalThrows_recordsScanFailure_noBrokerCalls() {
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenThrow(new RuntimeException("db boom"));

    poller.runOnce();

    verifyNoInteractions(broker);
    verify(dispatcher, never()).dispatch(any());
    assertThat(registry.counter("fill_listener.poll_scan_failures").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.poll_cycles").count()).isEqualTo(0.0);
  }

  @Test
  void runOnce_mixedBatch_onlyFilledRowsDispatched() {
    JournaledOrder a = row("ck-a", "brk-a");
    JournaledOrder b = row("ck-b", "brk-b");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(a, b));
    when(broker.getOrderStatus("brk-a")).thenReturn(BrokerOrderStatus.OPEN);
    when(broker.getOrderStatus("brk-b")).thenReturn(BrokerOrderStatus.FILLED);
    when(broker.getFillDetail("brk-b"))
        .thenReturn(
            new BrokerFillDetail(
                4L, new BigDecimal("0.55"), OffsetDateTime.parse("2026-05-23T19:59:30Z")));

    poller.runOnce();

    verify(dispatcher, times(1)).dispatch(any());
    assertThat(registry.counter("fill_listener.poll_rows_scanned").count()).isEqualTo(2.0);
    assertThat(registry.counter("fill_listener.poll_fills_detected").count()).isEqualTo(1.0);
  }

  @Test
  void backstop_missedEvent_terminalizesJournalToFilled_evenWhenWorkflowCompleted() {
    // #244 AC #4: the poll/recon backstop must catch a stream/webhook event the WS listener
    // missed and terminalize the journal to FILLED WITHOUT relying on a live onFill signal target.
    // Wire a REAL FillDispatcherImpl behind the poller so this exercises the full backstop path:
    // poll finds a SUBMITTED row the broker reports FILLED → dispatch → markFilled. The onFill
    // signal target is gone (workflow already completed → WorkflowNotFoundException, swallowed),
    // yet the journal still reaches FILLED so the position is not stranded.
    OrderIntentJournal realJournal = mock(OrderIntentJournal.class);
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowStub stub = mock(WorkflowStub.class);
    FillDispatcherImpl realDispatcher =
        new FillDispatcherImpl(realJournal, workflowClient, metrics);
    FillPoller backstopPoller =
        new FillPoller(realJournal, broker, realDispatcher, metrics, props, FIXED_CLOCK);

    JournaledOrder submittedRow = row("ck-missed", "brk-missed");
    when(realJournal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(submittedRow));
    when(broker.getOrderStatus("brk-missed")).thenReturn(BrokerOrderStatus.FILLED);
    when(broker.getFillDetail("brk-missed"))
        .thenReturn(
            new BrokerFillDetail(
                5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-23T19:58:00Z")));
    when(realJournal.findByBrokerOrderId("brk-missed")).thenReturn(Optional.of(submittedRow));
    when(realJournal.markFilled(eq("ck-missed"), anyLong(), any(), any())).thenReturn(true);
    when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(stub);
    WorkflowExecution exec =
        WorkflowExecution.newBuilder()
            .setWorkflowId("t-dev/s-copytrade-v1/sig/sig-ck-missed")
            .build();
    org.mockito.Mockito.doThrow(
            new WorkflowNotFoundException(exec, "CopytradeSignalWorkflow", null))
        .when(stub)
        .signal(eq("onFill"), any());

    backstopPoller.runOnce(); // must not throw despite the completed workflow

    // The journal is terminalized to FILLED through the backstop, independent of signal liveness.
    verify(realJournal)
        .markFilled(
            eq("ck-missed"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-23T19:58:00Z")));
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(0.0);
  }

  @Test
  void runOnce_expiredRow_marksExpired_noFillDetail() {
    JournaledOrder row = row("ck-exp", "brk-exp");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-exp")).thenReturn(BrokerOrderStatus.EXPIRED);

    poller.runOnce();

    verify(journal, times(1)).markExpired("ck-exp");
    verify(broker, never()).getFillDetail(any());
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void runOnce_expiredRow_alreadyFilled_isSilentNoOp() {
    // The guarded markExpired returns false (the row won the late-fill race to FILLED via WS); the
    // poller must not fall through to fill-dispatch or otherwise act.
    JournaledOrder row = row("ck-exp", "brk-exp");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-exp")).thenReturn(BrokerOrderStatus.EXPIRED);
    when(journal.markExpired("ck-exp")).thenReturn(false);

    poller.runOnce();

    verify(journal, times(1)).markExpired("ck-exp");
    verify(broker, never()).getFillDetail(any());
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void runOnce_cancelledRow_marksCancelledIfSubmitted() {
    JournaledOrder row = row("ck-can", "brk-can");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-can")).thenReturn(BrokerOrderStatus.CANCELLED);

    poller.runOnce();

    verify(journal, times(1)).markCancelledIfSubmitted("ck-can");
    verify(broker, never()).getFillDetail(any());
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void runOnce_rejectedRow_marksBrokerRejected() {
    JournaledOrder row = row("ck-rej", "brk-rej");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-rej")).thenReturn(BrokerOrderStatus.REJECTED);

    poller.runOnce();

    verify(journal, times(1)).markBrokerRejected(eq("ck-rej"), anyString());
    verify(broker, never()).getFillDetail(any());
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void runOnce_unknownStatus_noJournalWrite() {
    JournaledOrder row = row("ck-unk", "brk-unk");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-unk")).thenReturn(BrokerOrderStatus.UNKNOWN);

    poller.runOnce();

    verify(journal, never()).markExpired(any());
    verify(journal, never()).markCancelledIfSubmitted(any());
    verify(journal, never()).markBrokerRejected(any(), any());
    verify(broker, never()).getFillDetail(any());
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void runOnce_openStatus_noJournalWrite() {
    JournaledOrder row = row("ck-open", "brk-open");
    when(journal.findSubmittedOlderThan(any(), eq(50))).thenReturn(List.of(row));
    when(broker.getOrderStatus("brk-open")).thenReturn(BrokerOrderStatus.OPEN);

    poller.runOnce();

    verify(journal, never()).markExpired(any());
    verify(journal, never()).markCancelledIfSubmitted(any());
    verify(journal, never()).markBrokerRejected(any(), any());
    verify(broker, never()).getFillDetail(any());
    verify(dispatcher, never()).dispatch(any());
  }

  private JournaledOrder row(String intentKey, String brokerOrderId) {
    return new JournaledOrder(
        intentKey,
        "sig-" + intentKey,
        "dev",
        "copytrade-v1",
        "alpaca-paper",
        intentKey,
        "SPY   260519C00737000",
        "BUY",
        5L,
        new BigDecimal("0.84"),
        OrderState.SUBMITTED,
        brokerOrderId,
        OffsetDateTime.parse("2026-05-23T19:00:00Z"),
        OffsetDateTime.parse("2026-05-23T19:00:01Z"),
        OffsetDateTime.parse("2026-05-23T19:00:01Z"),
        null,
        null,
        null,
        null,
        null,
        1L);
  }
}
