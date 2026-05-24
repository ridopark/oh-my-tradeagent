package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * WorkflowIds.copytradeSignal} → untyped Temporal signal on {@code onFill} with a payload whose
 * JSON shape matches the orchestrator's {@code FillEvent} record. Tests the four documented
 * outcomes (happy path, unknown order, workflow already completed, other Temporal error).
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

    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");

    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(name.capture(), arg.capture());

    assertThat(name.getValue()).isEqualTo("onFill");
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.brokerOrderId()).isEqualTo("brk-42");
              assertThat(p.filledQty()).isEqualTo(5L);
              assertThat(p.avgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
              assertThat(p.filledAt()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));
            });

    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_unknownBrokerOrder_dropsEvent() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.empty());

    dispatcher.dispatch(FILL);

    verify(workflowClient, never()).newUntypedWorkflowStub(anyString());
    verify(workflowStub, never()).signal(anyString(), org.mockito.ArgumentMatchers.any());
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(1.0);
  }

  @Test
  void dispatch_workflowAlreadyCompleted_swallowsException() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId("t-dev/s-copytrade-v1/sig/sig-42").build();
    doThrow(new WorkflowNotFoundException(exec, "CopytradeSignalWorkflow", null))
        .when(workflowStub)
        .signal(eq("onFill"), org.mockito.ArgumentMatchers.any());

    dispatcher.dispatch(FILL); // must not throw

    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_otherTemporalException_propagates() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    doThrow(new RuntimeException("temporal boom"))
        .when(workflowStub)
        .signal(eq("onFill"), org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> dispatcher.dispatch(FILL)).hasMessage("temporal boom");

    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
  }
}
