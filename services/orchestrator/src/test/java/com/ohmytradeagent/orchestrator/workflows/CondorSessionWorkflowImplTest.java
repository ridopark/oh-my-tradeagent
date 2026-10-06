package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.CondorExecActivity;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorEntryRequest;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorEntryResult;
import com.ohmytradeagent.contract.activities.CondorMarketActivity;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLegsResult;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.RichnessResult;
import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.CondorDayActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Gated-condor Phase 4 session: day filters, the every-day gate audit, gated-in leg resolution +
 * mid-walk entry, the abandon/halt paths, and the hand-off to {@link CondorHoldWorkflow}.
 */
class CondorSessionWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";
  private static final String MD_QUEUE = WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE;
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private StrategyActivities strategy;
  private MarketCalendarActivities calendar;
  private CondorDayActivities condorDay;
  private RiskActivities risk;
  private PositionLookupActivities positionLookup;
  private CondorMarketActivity market;
  private MarketCalendarActivity tradingCalendar;
  private CondorExecActivity condorExec;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    audit = Mockito.mock(AuditActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    condorDay = Mockito.mock(CondorDayActivities.class);
    risk = Mockito.mock(RiskActivities.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);
    market = Mockito.mock(CondorMarketActivity.class);
    tradingCalendar = Mockito.mock(MarketCalendarActivity.class);
    condorExec = Mockito.mock(CondorExecActivity.class);

    when(strategy.get("staging_paper", "gated_condor")).thenReturn(condorConfig());
    when(calendar.todayEt()).thenReturn(TODAY);
    lenient().when(calendar.durationUntilEodCloseEt(any())).thenReturn(Duration.ofMinutes(10));
    lenient().when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(17));
    lenient().when(tradingCalendar.tradingDays(TODAY, TODAY)).thenReturn(List.of(TODAY));
    lenient().when(condorDay.eventSkipReason(any(), anyBoolean())).thenReturn(null);
    lenient()
        .when(risk.checkKillSwitchHalt(anyString(), anyString()))
        .thenReturn(RiskDecision.approved());
    lenient()
        .when(market.evaluateRichness(anyString(), anyString(), anyInt()))
        .thenReturn(gate(true));
    lenient()
        .when(market.resolveCondorLegs(anyString(), anyDouble(), anyDouble()))
        .thenReturn(legs());
    lenient().when(condorExec.heldCondorLegs(any(), any(), any())).thenReturn(List.of());

    Worker core = env.newWorker(CORE_QUEUE);
    core.registerWorkflowImplementationTypes(
        CondorSessionWorkflowImpl.class, CondorHoldWorkflowImpl.class);
    core.registerActivitiesImplementations(
        audit, strategy, calendar, condorDay, risk, positionLookup);
    env.newWorker(MD_QUEUE).registerActivitiesImplementations(market);
    env.newWorker(BROKER_QUEUE).registerActivitiesImplementations(tradingCalendar, condorExec);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  static StrategyConfig condorConfig() {
    return new StrategyConfig()
        .withBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER)
        .withCondorEntryEt("14:00")
        .withCondorShortOffsetPct(new BigDecimal("0.0015"))
        .withCondorWingOffsetPct(new BigDecimal("0.006"))
        .withRichnessGateLookbackDays(60L)
        .withRichnessGateMinQuantile(new BigDecimal("0.5"));
  }

  private static RichnessResult gate(boolean pass) {
    return pass
        ? new RichnessResult(1.20, 1.00, 0.70, 60, true, null)
        : new RichnessResult(0.90, 1.00, 0.30, 60, false, null);
  }

  static List<CondorLeg> condorLegs() {
    return List.of(
        new CondorLeg("XSP   261005C00601000", "sell", "C", 601, bd("0.60"), bd("0.64")),
        new CondorLeg("XSP   261005P00599000", "sell", "P", 599, bd("0.58"), bd("0.62")),
        new CondorLeg("XSP   261005C00604000", "buy", "C", 604, bd("0.04"), bd("0.06")),
        new CondorLeg("XSP   261005P00596000", "buy", "P", 596, bd("0.05"), bd("0.07")));
  }

  private static CondorLegsResult legs() {
    return new CondorLegsResult(600.0, condorLegs(), bd("1.11"), null);
  }

  private String run() {
    CondorSessionWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CondorSessionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("t-staging_paper/s-gated_condor/condor-session/x")
                    .build());
    return wf.run(new CondorSessionWorkflowInput("staging_paper", "gated_condor"));
  }

  private List<AuditEvent> audits() {
    ArgumentCaptor<AuditEvent> c = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, Mockito.atLeast(0)).log(c.capture());
    return c.getAllValues();
  }

  private AuditEvent onlyAudit(String kind) {
    List<AuditEvent> of = audits().stream().filter(e -> kind.equals(e.getKind())).toList();
    assertThat(of).as("audits of kind %s", kind).hasSize(1);
    return of.get(0);
  }

  @Test
  void gatedOutDay_auditsTheGate_andPlacesNothing() {
    when(market.evaluateRichness(anyString(), anyString(), anyInt())).thenReturn(gate(false));

    assertThat(run()).isEqualTo("gated_out");

    assertThat(onlyAudit("CondorGateEvaluated").getSubject())
        .containsEntry("gate", false)
        .containsEntry("richness", 0.90)
        .containsEntry("quantile", 0.30)
        .containsEntry("trailing_median", 1.00);
    verify(market, never()).resolveCondorLegs(anyString(), anyDouble(), anyDouble());
    verify(condorExec, never()).enterCondor(any());
  }

  @Test
  void gatedInDay_auditsTheGateToo() {
    when(condorExec.enterCondor(any())).thenReturn(abandoned());

    run();

    assertThat(onlyAudit("CondorGateEvaluated").getSubject()).containsEntry("gate", true);
  }

  @Test
  void fomcDay_skipsBeforeTheGate() {
    when(condorDay.eventSkipReason(TODAY, true)).thenReturn("fomc");

    assertThat(run()).isEqualTo("skip:fomc");

    assertThat(onlyAudit("CondorEventSkip").getSubject()).containsEntry("reason", "fomc");
    verify(market, never()).evaluateRichness(anyString(), anyString(), anyInt());
    verify(condorExec, never()).enterCondor(any());
  }

  @Test
  void eventDaySkip_defaultsOn_whenConfigLeavesItNull() {
    when(condorExec.enterCondor(any())).thenReturn(abandoned());

    run();

    verify(condorDay).eventSkipReason(TODAY, true);
  }

  @Test
  void marketClosedDay_skips() {
    when(tradingCalendar.tradingDays(TODAY, TODAY)).thenReturn(List.of());

    assertThat(run()).isEqualTo("skip:market_closed");
    verify(market, never()).evaluateRichness(anyString(), anyString(), anyInt());
  }

  @Test
  void nonPaperBrokerTarget_skipsBeforeAnyMarketOrExecCall() {
    when(strategy.get("staging_paper", "gated_condor"))
        .thenReturn(condorConfig().withBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE));

    assertThat(run()).isEqualTo("skip:non_paper_broker_target");
    verify(market, never()).evaluateRichness(anyString(), anyString(), anyInt());
    verify(condorExec, never()).enterCondor(any());
  }

  @Test
  void darkConfig_skips() {
    when(strategy.get("staging_paper", "gated_condor"))
        .thenReturn(condorConfig().withCondorEntryEt(null));

    assertThat(run()).isEqualTo("skip:dark_config");
    verify(condorExec, never()).enterCondor(any());
  }

  @Test
  void gatedInDay_resolvesLegsAndCallsMlegEntry() {
    when(condorExec.enterCondor(any())).thenReturn(filled());

    assertThat(run()).isEqualTo("filled");

    // Config offsets are fractions (0.0015); the activity takes percent → 0.15 / 0.60.
    verify(market)
        .resolveCondorLegs(
            eq("XSP"),
            org.mockito.AdditionalMatchers.eq(0.15, 1e-9),
            org.mockito.AdditionalMatchers.eq(0.60, 1e-9));
    ArgumentCaptor<CondorEntryRequest> req = ArgumentCaptor.forClass(CondorEntryRequest.class);
    verify(condorExec).enterCondor(req.capture());
    assertThat(req.getValue().legs()).isEqualTo(condorLegs());
    assertThat(req.getValue().modelCredit()).isEqualByComparingTo("1.11");
    assertThat(req.getValue().brokerTarget()).isEqualTo("alpaca-paper");
    assertThat(req.getValue().attemptId()).isEqualTo("2026-10-05");
    assertThat(req.getValue().qty()).isEqualTo(1L);
    assertThat(onlyAudit("CondorEntryFilled").getSubject())
        .containsEntry("hold_workflow_id", "t-staging_paper/s-gated_condor/condor/2026-10-05");
    // The hold child started and seeded the recon cache for every leg.
    verify(positionLookup, timeout(5000).times(4))
        .cachePositionMapping(
            eq("staging_paper"),
            eq("gated_condor"),
            anyString(),
            eq("t-staging_paper/s-gated_condor/condor/2026-10-05"));
  }

  @Test
  void abandonedWalk_endsTheSessionCleanly_withoutAHold() {
    when(condorExec.enterCondor(any())).thenReturn(abandoned());

    assertThat(run()).isEqualTo("abandoned");

    assertThat(onlyAudit("CondorEntryAbandoned").getSubject())
        .containsEntry("reason", "below_floor: next 1.08 < 1.09");
    verify(positionLookup, never()).cachePositionMapping(any(), any(), any(), any());
  }

  @Test
  void haltedWalk_auditsTheFailureKind() {
    when(condorExec.enterCondor(any()))
        .thenReturn(
            new CondorEntryResult("HALTED", bd("1.11"), 0L, null, null, 1, "cancel failed"));

    assertThat(run()).isEqualTo("halted");
    assertThat(onlyAudit("CondorEntryHalted").getSubject())
        .containsEntry("reason", "cancel failed");
  }

  @Test
  void killSwitchHalted_abandonsBeforeLegsOrEntry() {
    when(risk.checkKillSwitchHalt("staging_paper", "gated_condor"))
        .thenReturn(RiskDecision.rejected(RejectionReason.KILL_SWITCH_TRIPPED, "tripped"));

    assertThat(run()).isEqualTo("abandoned");
    verify(market, never()).resolveCondorLegs(anyString(), anyDouble(), anyDouble());
    verify(condorExec, never()).enterCondor(any());
  }

  @Test
  void lateStart_pastEntryPlusCutoff_skipsWithoutEvaluating() {
    when(calendar.durationUntilEodCloseEt(java.time.LocalTime.of(14, 5))).thenReturn(Duration.ZERO);

    assertThat(run()).isEqualTo("skip:late_start");
    verify(market, never()).evaluateRichness(anyString(), anyString(), anyInt());
  }

  @Test
  void entryActivityFailure_auditsHalted_insteadOfFailingSilently() {
    when(condorExec.enterCondor(any()))
        .thenThrow(
            io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                "refused", "CondorNotPaperError"));

    assertThat(run()).isEqualTo("halted");
    assertThat(onlyAudit("CondorEntryHalted").getSubject().get("reason").toString())
        .contains("entry_activity_failed");
  }

  @Test
  void unresolvedLegs_abandon() {
    when(market.resolveCondorLegs(anyString(), anyDouble(), anyDouble()))
        .thenReturn(new CondorLegsResult(600.0, List.of(), null, "no quote for XSP"));

    assertThat(run()).isEqualTo("abandoned");
    verify(condorExec, never()).enterCondor(any());
  }

  private static CondorEntryResult filled() {
    return new CondorEntryResult("FILLED", bd("1.11"), 1L, bd("1.10"), bd("0.01"), 2, null);
  }

  private static CondorEntryResult abandoned() {
    return new CondorEntryResult(
        "ABANDONED", bd("1.11"), 0L, null, null, 3, "below_floor: next 1.08 < 1.09");
  }

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }
}
