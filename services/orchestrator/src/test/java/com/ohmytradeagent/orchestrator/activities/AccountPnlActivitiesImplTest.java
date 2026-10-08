package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import com.ohmytradeagent.contract.temporal.LenientDataConverter;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.workflows.CondorHoldWorkflowInput;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountPnlActivitiesImplTest {

  private static final LocalDate DAY = LocalDate.of(2026, 5, 14);
  private static final String POSITION_QUERY = "WorkflowType='PositionWorkflow'";
  private static final String CONDOR_QUERY = "WorkflowType='CondorHoldWorkflow'";

  private DailyPnlActivities dailyPnl;
  private WorkflowClient client;
  private StrategyRegistry strategyRegistry;
  private AccountPnlActivitiesImpl activities;

  @BeforeEach
  void setUp() {
    dailyPnl = mock(DailyPnlActivities.class);
    client = mock(WorkflowClient.class);
    strategyRegistry = mock(StrategyRegistry.class);
    lenient()
        .when(client.getOptions())
        .thenReturn(
            WorkflowClientOptions.newBuilder()
                .setDataConverter(LenientDataConverter.instance())
                .build());
    lenient().when(client.listExecutions(contains(CONDOR_QUERY))).thenAnswer(inv -> Stream.empty());
  }

  private AccountPnlActivitiesImpl forStrategies(List<String> strategyIds) {
    return new AccountPnlActivitiesImpl(
        dailyPnl, tenantId -> strategyIds, client, strategyRegistry);
  }

  private static StrategyConfig cfgWithBrokerTarget(StrategyConfig.BrokerTarget bt) {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setBrokerTarget(bt);
    return c;
  }

  // Realized PnL sums the existing per-strategy FIFO composition over every tenant strategy.
  @Test
  void computeTenantRealizedPnl_sumsAcrossStrategies() {
    activities = forStrategies(List.of("copytrade-v1", "copytrade-v2"));
    when(dailyPnl.computeRealizedPnl("dev", "copytrade-v1", DAY))
        .thenReturn(new BigDecimal("-1200"));
    when(dailyPnl.computeRealizedPnl("dev", "copytrade-v2", DAY))
        .thenReturn(new BigDecimal("-800"));

    assertThat(activities.computeTenantRealizedPnl("dev", DAY))
        .isEqualByComparingTo(new BigDecimal("-2000"));
  }

  // Phase 2 (C4): mixed broker_target — each strategy pairs with its OWN broker_target so the
  // account workflow can route per-strategy realized reads to different broker queues.
  @Test
  void tenantStrategyBrokerTargets_resolvesPerStrategyBrokerTarget_mixed() {
    activities = forStrategies(List.of("s-paper", "s-live"));
    when(strategyRegistry.get("dev", "s-paper"))
        .thenReturn(cfgWithBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER));
    when(strategyRegistry.get("dev", "s-live"))
        .thenReturn(cfgWithBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE));

    assertThat(activities.tenantStrategyBrokerTargets("dev"))
        .containsExactly(
            new TenantStrategyBrokerTarget("s-paper", "alpaca-paper"),
            new TenantStrategyBrokerTarget("s-live", "alpaca-live"));
  }

  // Phase 2 (G2): a strategy whose config read throws is returned with a null broker_target — the
  // workflow fails CLOSED on it rather than the activity silently dropping it (under-count).
  @Test
  void tenantStrategyBrokerTargets_unresolvableStrategy_returnsNullBrokerTarget() {
    activities = forStrategies(List.of("s-ok", "s-broken"));
    when(strategyRegistry.get("dev", "s-ok"))
        .thenReturn(cfgWithBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER));
    when(strategyRegistry.get("dev", "s-broken"))
        .thenThrow(new RuntimeException("config unreadable"));

    assertThat(activities.tenantStrategyBrokerTargets("dev"))
        .containsExactly(
            new TenantStrategyBrokerTarget("s-ok", "alpaca-paper"),
            new TenantStrategyBrokerTarget("s-broken", null));
  }

  // Phase 2 (G2): fail-closed — an empty resolved strategy set throws (never sum nothing).
  @Test
  void tenantStrategyBrokerTargets_emptyStrategySet_failClosed() {
    activities = forStrategies(List.of());

    assertThatThrownBy(() -> activities.tenantStrategyBrokerTargets("dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty strategy set");
  }

  // Open book unions positions across both tenant strategies, one equality query per strategy.
  @Test
  void accountOpenBook_unionsPositionsAcrossStrategies() {
    activities = forStrategies(List.of("copytrade-v1", "copytrade-v2"));
    stubExecutionsByQuery(
        Map.of(
            "t-dev/s-copytrade-v1", "wf-s1",
            "t-dev/s-copytrade-v2", "wf-s2"),
        Map.of(
            "wf-s1", new PositionState("NVDA  250516C00140000", 2L, new BigDecimal("2.00")),
            "wf-s2", new PositionState("AAPL  250516C00200000", 1L, new BigDecimal("5.00"))));

    AccountOpenBook book = activities.accountOpenBook("dev");

    assertThat(book.listed()).isEqualTo(2);
    assertThat(book.valueFailures()).isZero();
    assertThat(book.positions())
        .containsExactlyInAnyOrder(
            new OpenPositionValuation("NVDA  250516C00140000", new BigDecimal("2.00"), 2L),
            new OpenPositionValuation("AAPL  250516C00200000", new BigDecimal("5.00"), 1L));
  }

  // A positionState query failure is counted as a valueFailure (degradation signal), not a skip.
  @Test
  void accountOpenBook_countsQueryFailureAsValueFailure() {
    activities = forStrategies(List.of("copytrade-v1"));
    WorkflowExecutionMetadata good = metadata("wf-good");
    WorkflowExecutionMetadata bad = metadata("wf-bad");
    when(client.listExecutions(contains(POSITION_QUERY))).thenReturn(Stream.of(good, bad));

    WorkflowStub goodStub = mock(WorkflowStub.class);
    when(goodStub.query(eq("positionState"), eq(PositionState.class)))
        .thenReturn(new PositionState("NVDA  250516C00140000", 1L, new BigDecimal("3.00")));
    WorkflowStub badStub = mock(WorkflowStub.class);
    when(badStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("query failed"));
    when(client.newUntypedWorkflowStub("wf-good")).thenReturn(goodStub);
    when(client.newUntypedWorkflowStub("wf-bad")).thenReturn(badStub);

    AccountOpenBook book = activities.accountOpenBook("dev");

    assertThat(book.listed()).isEqualTo(2);
    assertThat(book.valueFailures()).isEqualTo(1);
    assertThat(book.positions())
        .containsExactly(
            new OpenPositionValuation("NVDA  250516C00140000", new BigDecimal("3.00"), 1L));
  }

  // Just-closed/blank/null-premium positions are legitimate skips — neither in positions nor
  // counted.
  @Test
  void accountOpenBook_skipsClosedOrUnvaluablePositions() {
    activities = forStrategies(List.of("copytrade-v1"));
    stubExecutions(
        Map.of(
            "wf-closed", new PositionState("NVDA  250516C00140000", 0L, new BigDecimal("2.50")),
            "wf-blank", new PositionState("", 2L, new BigDecimal("2.50")),
            "wf-live", new PositionState("MSFT  250516C00300000", 2L, new BigDecimal("1.00"))));

    AccountOpenBook book = activities.accountOpenBook("dev");

    assertThat(book.valueFailures()).isZero();
    assertThat(book.positions())
        .containsExactly(
            new OpenPositionValuation("MSFT  250516C00300000", new BigDecimal("1.00"), 2L));
  }

  // Fail-closed: an empty resolved strategy set throws rather than reporting an empty book.
  @Test
  void accountOpenBook_throwsOnEmptyStrategySet_failClosed() {
    activities = forStrategies(List.of());

    assertThatThrownBy(() -> activities.accountOpenBook("dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty strategy set");
  }

  // Fail-closed: a Visibility error propagates (not swallowed into an empty book).
  @Test
  void accountOpenBook_propagatesListExecutionsError_failClosed() {
    activities = forStrategies(List.of("copytrade-v1"));
    when(client.listExecutions(anyString()))
        .thenThrow(new RuntimeException("visibility unavailable"));

    assertThatThrownBy(() -> activities.accountOpenBook("dev"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("visibility unavailable");
  }

  // #899: a running CondorHoldWorkflow is charged at its DEFINED max loss, (wider wing - credit) x
  // 100 x qty, read from the hold's recorded start input — no quote, so a wide or missing book can
  // never move it. Condors are kept out of positions/listed (the PositionWorkflow fail-closed
  // denominator and the per-lot bid valuation).
  @Test
  void accountOpenBook_chargesRunningCondorHoldsAtDefinedMaxLoss() {
    activities = forStrategies(List.of("condor-v1", "copytrade-v1"));
    when(client.listExecutions(contains(POSITION_QUERY))).thenAnswer(inv -> Stream.empty());
    when(client.listExecutions(contains(CONDOR_QUERY)))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              return query.contains("TenantStrategy='t-dev/s-condor-v1'")
                  ? Stream.of(metadata("hold-a"), metadata("hold-b"))
                  : Stream.empty();
            });
    // hold-a: 3-wide, credit 0.80, qty 2 -> 440; hold-b: 4-wide put wing, credit 1.00, qty 1 ->
    // 300.
    stubCondorHold("hold-a", condorInput(596, 599, 601, 604, "0.80", 2L));
    stubCondorHold("hold-b", condorInput(595, 599, 601, 603, "1.00", 1L));

    AccountOpenBook book = activities.accountOpenBook("dev");

    assertThat(book.condorMaxLoss()).isEqualByComparingTo("740");
    assertThat(book.condorHolds()).isEqualTo(2);
    assertThat(book.positions()).isEmpty();
    assertThat(book.listed()).isZero();
    assertThat(book.valueFailures()).isZero();
  }

  @Test
  void accountOpenBook_noCondorHolds_chargesZero() {
    activities = forStrategies(List.of("copytrade-v1"));
    stubExecutions(
        Map.of("wf-live", new PositionState("MSFT  250516C00300000", 2L, new BigDecimal("1.00"))));

    assertThat(activities.accountOpenBook("dev").condorMaxLoss()).isEqualByComparingTo("0");
  }

  // #899 round 2: an unreadable hold (history read failure, or a malformed input) is COUNTED as a
  // condor read failure — never thrown, which would disarm the whole shared cap — and the readable
  // holds are still charged.
  @Test
  void accountOpenBook_unreadableCondorHolds_countedNotThrown_restCharged() {
    activities = forStrategies(List.of("condor-v1"));
    when(client.listExecutions(contains(POSITION_QUERY))).thenAnswer(inv -> Stream.empty());
    when(client.listExecutions(contains(CONDOR_QUERY)))
        .thenAnswer(
            inv -> Stream.of(metadata("hold-ok"), metadata("hold-gone"), metadata("hold-bad")));
    stubCondorHold("hold-ok", condorInput(596, 599, 601, 604, "0.80", 2L));
    when(client.fetchHistory("hold-gone")).thenThrow(new RuntimeException("history unavailable"));
    CondorHoldWorkflowInput threeLegs = condorInput(596, 599, 601, 604, "0.80", 1L);
    threeLegs.setLegs(threeLegs.getLegs().subList(0, 3));
    stubCondorHold("hold-bad", threeLegs);

    AccountOpenBook book = activities.accountOpenBook("dev");

    assertThat(book.condorMaxLoss()).isEqualByComparingTo("440");
    assertThat(book.condorReadFailures()).isEqualTo(2);
    assertThat(book.condorHolds()).isEqualTo(3);
  }

  // ----- helpers (mirror VisibilityPortfolioSnapshotTest) -----

  private void stubExecutionsByQuery(
      Map<String, String> tenantStrategyToWorkflowId,
      Map<String, PositionState> stateByWorkflowId) {
    when(client.listExecutions(contains(POSITION_QUERY)))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              return tenantStrategyToWorkflowId.entrySet().stream()
                  .filter(e -> query.contains("TenantStrategy='" + e.getKey() + "'"))
                  .map(Map.Entry::getValue)
                  .map(this::metadata);
            });
    stateByWorkflowId.forEach(
        (wfId, state) -> {
          WorkflowStub stub = mock(WorkflowStub.class);
          lenient()
              .when(stub.query(eq("positionState"), eq(PositionState.class)))
              .thenReturn(state);
          when(client.newUntypedWorkflowStub(wfId)).thenReturn(stub);
        });
  }

  private void stubExecutions(Map<String, PositionState> byWorkflowId) {
    Stream<WorkflowExecutionMetadata> stream = byWorkflowId.keySet().stream().map(this::metadata);
    when(client.listExecutions(contains(POSITION_QUERY))).thenReturn(stream);
    byWorkflowId.forEach(
        (wfId, state) -> {
          WorkflowStub stub = mock(WorkflowStub.class);
          lenient()
              .when(stub.query(eq("positionState"), eq(PositionState.class)))
              .thenReturn(state);
          when(client.newUntypedWorkflowStub(wfId)).thenReturn(stub);
        });
  }

  private static CondorHoldWorkflowInput condorInput(
      int longPut, int shortPut, int shortCall, int longCall, String credit, long qty) {
    List<CondorLeg> legs =
        List.of(
            new CondorLeg("XSP-SC", "sell", "C", shortCall, null, null),
            new CondorLeg("XSP-SP", "sell", "P", shortPut, null, null),
            new CondorLeg("XSP-LC", "buy", "C", longCall, null, null),
            new CondorLeg("XSP-LP", "buy", "P", longPut, null, null));
    return new CondorHoldWorkflowInput(
        "dev",
        "condor-v1",
        "alpaca-paper",
        "att-1",
        "2026-05-14",
        "XSP",
        qty,
        new BigDecimal(credit),
        legs);
  }

  private void stubCondorHold(String workflowId, CondorHoldWorkflowInput input) {
    HistoryEvent started =
        HistoryEvent.newBuilder()
            .setEventId(1)
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .setWorkflowExecutionStartedEventAttributes(
                WorkflowExecutionStartedEventAttributes.newBuilder()
                    .setInput(LenientDataConverter.instance().toPayloads(input).orElseThrow()))
            .build();
    when(client.fetchHistory(workflowId))
        .thenReturn(new WorkflowExecutionHistory(History.newBuilder().addEvents(started).build()));
  }

  private WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    lenient()
        .when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }
}
