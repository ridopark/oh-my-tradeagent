package com.ohmytradeagent.orchestrator.alert.floorbreach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #779 T3: the scheduled detector end-to-end over mocked seams. A confirmed breach produces
 * exactly one {@code FloorBreachAlerted} audit log call with the full subject; every failure mode
 * (quote client throw, positionState query throw, disabled flag) is fail-soft and produces NO audit
 * call.
 */
class FloorBreachAlertLoopTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";
  private static final String WF = "wf-pos-1";
  // Far-future expiry so the physically-expired skip never triggers in these tests.
  private static final String OCC = "NVDA  270115C00140000";

  private StrategyRegistry registry;
  private WorkflowClient client;
  private MarketDataOptionQuoteClient quoteClient;
  private FloorBreachThresholdResolver thresholdResolver;
  private FloorBreachStateStore stateStore;
  private AuditActivities audit;

  @BeforeEach
  void setUp() {
    registry = mock(StrategyRegistry.class);
    client = mock(WorkflowClient.class);
    quoteClient = mock(MarketDataOptionQuoteClient.class);
    thresholdResolver = mock(FloorBreachThresholdResolver.class);
    stateStore = new FloorBreachStateStore(wf -> null, Duration.ofHours(4));
    audit = mock(AuditActivities.class);
    when(registry.list()).thenReturn(List.of(new TenantStrategy(TENANT, STRATEGY)));
    lenient()
        .when(thresholdResolver.threshold(TENANT, STRATEGY))
        .thenReturn(new BigDecimal("0.50"));
  }

  private FloorBreachAlertLoop loop(boolean enabled) {
    return new FloorBreachAlertLoop(
        registry, client, quoteClient, thresholdResolver, stateStore, audit, enabled);
  }

  private void stubOneRunningPosition(PositionState state) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    lenient()
        .when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(WF).build());
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of(md));
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(WF)).thenReturn(stub);
    when(stub.query("positionState", PositionState.class)).thenReturn(state);
  }

  private static PositionState openPosition() {
    return new PositionState(
        OCC, 3, new BigDecimal("2.00"), OffsetDateTime.parse("2026-08-21T13:40:00Z"), false);
  }

  @Test
  void confirmedBreach_emitsExactlyOneFloorBreachAlertedWithFullSubject() {
    stubOneRunningPosition(openPosition());
    // bid 0.80 on entry 2.00 → -60%, below the 1.00 line.
    when(quoteClient.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("0.80"), new BigDecimal("1.00"), new BigDecimal("1.20")));

    FloorBreachAlertLoop loop = loop(true);
    loop.tick(); // 1st observation: unconfirmed, no audit
    verify(audit, never()).log(any());
    loop.tick(); // 2nd consecutive: page
    loop.tick(); // same step: silent
    loop.tick();

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent event = captor.getValue();
    assertThat(event.getKind()).isEqualTo("FloorBreachAlerted");
    assertThat(event.getTenantId()).isEqualTo(TENANT);
    assertThat(event.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(event.getWorkflowId()).isEqualTo(WF);
    assertThat(event.getActor()).isEqualTo("floor-breach-alerter");
    assertThat(event.getSubject())
        .containsEntry("contract_symbol", OCC)
        .containsEntry("qty", 3L)
        .containsEntry("entry_premium", new BigDecimal("2.00"))
        .containsEntry("current_bid", new BigDecimal("0.80"))
        .containsEntry("step", 60)
        .containsEntry("threshold", new BigDecimal("0.50"))
        .containsEntry("entry_at", "2026-08-21T13:40Z")
        .containsKey("loss_pct")
        .containsKey("dte");
    assertThat((BigDecimal) event.getSubject().get("loss_pct")).isEqualByComparingTo("0.60");
  }

  @Test
  void quoteClientThrow_isFailSoft_noAuditNoStateMutation_tickCompletes() {
    stubOneRunningPosition(openPosition());
    // Two throwing ticks, then a live breaching quote.
    when(quoteClient.optionQuote(OCC))
        .thenThrow(new IllegalStateException("boom"))
        .thenThrow(new IllegalStateException("boom"))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("0.80"), new BigDecimal("1.00"), new BigDecimal("1.20")));

    FloorBreachAlertLoop loop = loop(true);
    loop.tick();
    loop.tick();

    verify(audit, never()).log(any());
    // No state mutation: a subsequent REAL breach still needs its own 2-tick confirmation.
    loop.tick(); // 1st breach observation
    verify(audit, never()).log(any());
    loop.tick(); // 2nd: page
    verify(audit, times(1)).log(any());
  }

  @Test
  void disabledFlag_doesNoWorkAtAll() {
    FloorBreachAlertLoop loop = loop(false);
    loop.tick();
    verifyNoInteractions(registry, client, quoteClient, audit);
  }

  @Test
  void positionStateQueryThrow_skipsOnlyThatWorkflow() {
    WorkflowExecutionMetadata bad = mock(WorkflowExecutionMetadata.class);
    lenient()
        .when(bad.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId("wf-bad").build());
    WorkflowExecutionMetadata good = mock(WorkflowExecutionMetadata.class);
    lenient()
        .when(good.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(WF).build());
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of(bad, good));

    WorkflowStub badStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub("wf-bad")).thenReturn(badStub);
    when(badStub.query("positionState", PositionState.class))
        .thenThrow(new IllegalStateException("query race"));
    WorkflowStub goodStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(WF)).thenReturn(goodStub);
    when(goodStub.query("positionState", PositionState.class)).thenReturn(openPosition());
    when(quoteClient.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("0.80"), new BigDecimal("1.00"), new BigDecimal("1.20")));

    FloorBreachAlertLoop loop = loop(true);
    loop.tick();
    loop.tick();

    // The bad workflow was skipped; the good one still confirmed and paged.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    assertThat(captor.getValue().getWorkflowId()).isEqualTo(WF);
  }

  @Test
  void nonBreachingPosition_neverPages() {
    stubOneRunningPosition(openPosition());
    when(quoteClient.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("1.90"), new BigDecimal("2.00"), new BigDecimal("2.10")));

    FloorBreachAlertLoop loop = loop(true);
    loop.tick();
    loop.tick();
    loop.tick();

    verify(audit, never()).log(any());
  }

  @Test
  void closedOrEmptyPositionState_isSkipped() {
    stubOneRunningPosition(new PositionState(OCC, 0, new BigDecimal("2.00")));
    FloorBreachAlertLoop loop = loop(true);
    loop.tick();
    loop.tick();
    verify(audit, never()).log(any());
    verifyNoInteractions(quoteClient);
  }

  @Test
  void registryThrow_skipsTheTickQuietly() {
    when(registry.list()).thenThrow(new IllegalStateException("db down"));
    FloorBreachAlertLoop loop = loop(true);
    loop.tick();
    verifyNoInteractions(client, quoteClient, audit);
  }

  @Test
  void visibilityQueryUsesTheProvenEqualityShape() {
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of());
    loop(true).tick();
    ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
    verify(client).listExecutions(q.capture());
    assertThat(q.getValue())
        .isEqualTo(
            "WorkflowType='PositionWorkflow' AND TenantStrategy='t-acme/s-copytrade-v1'"
                + " AND ExecutionStatus='Running'");
  }
}
