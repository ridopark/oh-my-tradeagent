package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.activities.CondorExecActivity;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorFlattenRequest;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorFlattenResult;
import com.ohmytradeagent.contract.activities.CondorExecActivity.HeldLeg;
import com.ohmytradeagent.contract.activities.CondorExecActivity.SettlementCash;
import com.ohmytradeagent.contract.activities.CondorMarketActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Gated-condor Phase 4 hold: recon-cache seeding, settlement pricing on ITM/OTM fixtures, the
 * held-after-expiry mismatch page, and the operator / kill-switch flatten of all four legs.
 */
class CondorHoldWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";
  private static final String HOLD_ID = "t-staging_paper/s-gated_condor/condor/2026-10-05";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private PositionLookupActivities positionLookup;
  private CondorMarketActivity market;
  private CondorExecActivity condorExec;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);
    market = Mockito.mock(CondorMarketActivity.class);
    condorExec = Mockito.mock(CondorExecActivity.class);

    when(calendar.durationUntilEodCloseEt(CondorHoldWorkflowImpl.SETTLE_CHECK_ET))
        .thenReturn(Duration.ofHours(2));
    lenient().when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(17));
    // Before the 16:00 expiry close, so a flatten may be re-attempted.
    lenient()
        .when(calendar.durationUntilEodCloseEt(CondorHoldWorkflowImpl.EXPIRY_CLOSE_ET))
        .thenReturn(Duration.ofHours(1));
    lenient().when(condorExec.heldCondorLegs(any(), any(), any())).thenReturn(List.of());
    // Nothing booked: matches an OTM expiry (expected settlement cash 0).
    lenient()
        .when(condorExec.bookedSettlementCash(any(), any(), any(), any()))
        .thenReturn(new SettlementCash(BigDecimal.ZERO, 0));
    lenient()
        .when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(true, true, null));

    Worker core = env.newWorker(CORE_QUEUE);
    core.registerWorkflowImplementationTypes(CondorHoldWorkflowImpl.class);
    core.registerActivitiesImplementations(audit, calendar, positionLookup);
    env.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE)
        .registerActivitiesImplementations(market);
    env.newWorker(BROKER_QUEUE).registerActivitiesImplementations(condorExec);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  private static CondorHoldWorkflowInput input() {
    // shorts 601C / 599P, wings 604C / 596P, credit 1.10
    return new CondorHoldWorkflowInput(
        "staging_paper",
        "gated_condor",
        "alpaca-paper",
        "2026-10-05",
        "2026-10-05",
        "XSP",
        1L,
        new BigDecimal("1.10"),
        CondorSessionWorkflowImplTest.condorLegs());
  }

  private CondorHoldWorkflow stub() {
    return env.getWorkflowClient()
        .newWorkflowStub(
            CondorHoldWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).setWorkflowId(HOLD_ID).build());
  }

  private String runToCompletion() {
    return stub().run(input());
  }

  private AuditEvent onlyAudit(String kind) {
    ArgumentCaptor<AuditEvent> c = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, Mockito.atLeast(0)).log(c.capture());
    List<AuditEvent> of = c.getAllValues().stream().filter(e -> kind.equals(e.getKind())).toList();
    assertThat(of).as("audits of kind %s", kind).hasSize(1);
    return of.get(0);
  }

  @Test
  void seedsTheReconPositionCacheForAllFourLegs() {
    when(market.settlementSpot("XSP")).thenReturn(600.0);

    runToCompletion();

    for (String occ :
        List.of(
            "XSP   261005C00601000",
            "XSP   261005P00599000",
            "XSP   261005C00604000",
            "XSP   261005P00596000")) {
      verify(positionLookup).cachePositionMapping("staging_paper", "gated_condor", occ, HOLD_ID);
    }
  }

  @Test
  void otmSettlement_keepsTheFullCredit_andReconcilesClean() {
    when(market.settlementSpot("XSP")).thenReturn(600.40);

    assertThat(runToCompletion()).isEqualTo("settled");

    Map<String, Object> s = onlyAudit("CondorSettled").getSubject();
    assertThat(s).containsEntry("outcome", "OTM");
    assertThat(new BigDecimal(s.get("expected_pnl").toString())).isEqualByComparingTo("110.00");
    assertThat(new BigDecimal(s.get("settlement_debit").toString())).isEqualByComparingTo("0");
    verify(condorExec).heldCondorLegs(eq("staging_paper"), eq("alpaca-paper"), any());
    verify(condorExec, never()).flattenCondor(any());
  }

  @Test
  void itmSettlement_chargesIntrinsicOfTheBreachedShort() {
    // 602.50 vs short 601C → 1.50 debit; credit 1.10 → (1.10 − 1.50) × 100 = −40.
    when(market.settlementSpot("XSP")).thenReturn(602.50);
    when(condorExec.bookedSettlementCash(any(), any(), any(), any()))
        .thenReturn(new SettlementCash(new BigDecimal("-150.00"), 1));

    assertThat(runToCompletion()).isEqualTo("settled");

    Map<String, Object> s = onlyAudit("CondorSettled").getSubject();
    assertThat(s).containsEntry("outcome", "ITM");
    assertThat(new BigDecimal(s.get("expected_pnl").toString())).isEqualByComparingTo("-40.00");
  }

  @Test
  void beyondTheWing_lossIsCappedAtWidthMinusCredit() {
    // 610 vs 601C/604C: debit 9 − 6 = 3 (the wing width); (1.10 − 3) × 100 = −190.
    when(market.settlementSpot("XSP")).thenReturn(610.0);

    runToCompletion();

    assertThat(
            new BigDecimal(onlyAudit("CondorSettled").getSubject().get("expected_pnl").toString()))
        .isEqualByComparingTo("-190.00");
  }

  @Test
  void nextOpenDurationNull_doesNotNpe_stillReconciles() {
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(null);
    when(market.settlementSpot("XSP")).thenReturn(600.0);

    assertThat(runToCompletion()).isEqualTo("settled");
    verify(condorExec).heldCondorLegs(any(), any(), any());
  }

  // #920: the broker's booked settlement cash is compared to the expected settlement cash
  // (-debit x 100 x qty) after the next open. Within tolerance: no page.
  @Test
  void bookedSettlementCashWithinTolerance_noMismatchPage() {
    when(market.settlementSpot("XSP")).thenReturn(602.50); // expected cash -150
    when(condorExec.bookedSettlementCash(any(), any(), any(), any()))
        .thenReturn(new SettlementCash(new BigDecimal("-160.00"), 1));

    assertThat(runToCompletion()).isEqualTo("settled");

    verify(condorExec)
        .bookedSettlementCash(
            eq("staging_paper"),
            eq("alpaca-paper"),
            eq(
                List.of(
                    "XSP   261005C00601000",
                    "XSP   261005P00599000",
                    "XSP   261005C00604000",
                    "XSP   261005P00596000")),
            eq("2026-10-05"));
    verify(audit, never()).log(Mockito.argThat(e -> "CondorSettleMismatch".equals(e.getKind())));
  }

  // #920: booked cash diverging beyond tolerance (here: nothing booked for an ITM expiry) pages
  // CondorSettleMismatch (RED) with both figures.
  @Test
  void bookedSettlementCashDiverges_pagesMismatchWithBothFigures() {
    when(market.settlementSpot("XSP")).thenReturn(602.50); // expected cash -150

    assertThat(runToCompletion()).isEqualTo("settle_mismatch");

    Map<String, Object> s = onlyAudit("CondorSettleMismatch").getSubject();
    assertThat(s).containsEntry("reason", "booked_cash_mismatch").containsEntry("activities", 0);
    assertThat(new BigDecimal(s.get("expected_settlement_cash").toString()))
        .isEqualByComparingTo("-150");
    assertThat(new BigDecimal(s.get("booked_settlement_cash").toString()))
        .isEqualByComparingTo("0");
  }

  @Test
  void bookedSettlementCashReadFailing_pagesMismatch_workflowDoesNotFail() {
    when(market.settlementSpot("XSP")).thenReturn(600.0);
    when(condorExec.bookedSettlementCash(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("activities endpoint down"));

    assertThat(runToCompletion()).isEqualTo("settle_mismatch");

    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "booked_cash_read_failed");
  }

  // An unresolved settlement (no spot) has no expected figure to compare: no cash read.
  @Test
  void unresolvedSettlement_skipsTheBookedCashRead() {
    when(market.settlementSpot("XSP")).thenReturn(null);

    runToCompletion();

    verify(condorExec, never()).bookedSettlementCash(any(), any(), any(), any());
  }

  @Test
  void legStillHeldAfterExpiry_pagesSettleMismatch() {
    when(market.settlementSpot("XSP")).thenReturn(600.0);
    when(condorExec.heldCondorLegs(any(), any(), any()))
        .thenReturn(List.of(new HeldLeg("XSP   261005C00601000", -1L)));

    assertThat(runToCompletion()).isEqualTo("settle_mismatch");

    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "legs_still_held_after_expiry");
  }

  @Test
  void noSettlementSpot_pagesSettleMismatch_andIsUnresolved() {
    when(market.settlementSpot("XSP")).thenReturn(null);

    // Never silently "settled" with no priced P&L.
    assertThat(runToCompletion()).isEqualTo("settle_unresolved");
    assertThat(
            org.mockito.Mockito.mockingDetails(audit).getInvocations().stream()
                .map(i -> ((AuditEvent) i.getArgument(0)).getKind()))
        .doesNotContain("CondorSettled");

    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "settlement_spot_unavailable");
  }

  @Test
  void nonFiniteSettlementSpot_isNeverPriced() {
    when(market.settlementSpot("XSP")).thenReturn(Double.NaN);

    assertThat(runToCompletion()).isEqualTo("settle_unresolved");
    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "settlement_spot_unavailable");
  }

  @Test
  void settlementSpotActivityFailing_pagesMismatch_workflowDoesNotFail() {
    when(market.settlementSpot("XSP"))
        .thenThrow(io.temporal.failure.ApplicationFailure.newNonRetryableFailure("md down", "X"));

    assertThat(runToCompletion()).isEqualTo("settle_unresolved");
    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "settlement_spot_unavailable");
    verify(condorExec).heldCondorLegs(any(), any(), any());
  }

  @Test
  void finalHeldLegsReadFailing_pagesMismatch_workflowDoesNotFail() {
    when(market.settlementSpot("XSP")).thenReturn(600.0);
    when(condorExec.heldCondorLegs(any(), any(), any()))
        .thenThrow(io.temporal.failure.ApplicationFailure.newNonRetryableFailure("exec down", "X"));

    assertThat(runToCompletion()).isEqualTo("settle_mismatch");
    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "held_legs_read_failed");
  }

  @Test
  void partialBranchHeldLegsReadFailing_pricesAllLegs_andPages() {
    when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(false, false, "short cover not confirmed filled"));
    when(condorExec.heldCondorLegs(any(), any(), any()))
        .thenThrow(io.temporal.failure.ApplicationFailure.newNonRetryableFailure("exec down", "X"))
        .thenReturn(List.of());
    when(market.settlementSpot("XSP")).thenReturn(610.0);
    when(condorExec.bookedSettlementCash(any(), any(), any(), any()))
        .thenReturn(new SettlementCash(new BigDecimal("-300.00"), 2));
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    WorkflowStub.fromTyped(wf).getResult(String.class);

    assertThat(onlyAudit("CondorSettleMismatch").getSubject())
        .containsEntry("reason", "held_legs_read_failed");
    Map<String, Object> s = onlyAudit("CondorSettled").getSubject();
    // Conservative: all four legs priced (610 → debit 3, capped at the wing), no credit.
    assertThat(s.get("legs_priced").toString()).contains("601000", "599000", "604000", "596000");
    assertThat(new BigDecimal(s.get("expected_pnl").toString())).isEqualByComparingTo("-300.00");
  }

  @Test
  void forceClose_flattensAllFourLegs_beforeSettlement() {
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    assertThat(WorkflowStub.fromTyped(wf).getResult(String.class))
        .isEqualTo("flattened:operator_force_close");

    ArgumentCaptor<CondorFlattenRequest> req = ArgumentCaptor.forClass(CondorFlattenRequest.class);
    verify(condorExec).flattenCondor(req.capture());
    assertThat(req.getValue().legs()).isEqualTo(CondorSessionWorkflowImplTest.condorLegs());
    assertThat(req.getValue().attemptId()).isEqualTo("2026-10-05");
    assertThat(onlyAudit("CondorFlattened").getSubject())
        .containsEntry("exit_reason", "operator_force_close")
        .containsEntry("shorts_covered", true);
    verify(market, never()).settlementSpot(anyString());
  }

  @Test
  void killSwitchRiskBreach_flattensTheCombo_taggedKillswitchFlatten() {
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    // The cascade signals by name, untyped — exactly as KillSwitchCascadeActivitiesImpl does.
    RiskBreachPayload breach = new RiskBreachPayload();
    breach.setSchemaVersion(1L);
    breach.setReason("account_daily_loss");
    breach.setActor("auto:account_daily_loss");
    env.getWorkflowClient().newUntypedWorkflowStub(HOLD_ID).signal("riskBreach", breach);

    assertThat(WorkflowStub.fromTyped(wf).getResult(String.class))
        .isEqualTo("flattened:killswitch_flatten");
    verify(condorExec).flattenCondor(any());
    assertThat(onlyAudit("CondorFlattened").getSubject())
        .containsEntry("exit_reason", "killswitch_flatten");
  }

  @Test
  void shortsNotCovered_pagesFlattenIncomplete() {
    when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(false, false, "short cover not confirmed filled"));
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    when(market.settlementSpot("XSP")).thenReturn(600.0);

    // An incomplete flatten falls through to settlement reconciliation.
    assertThat(WorkflowStub.fromTyped(wf).getResult(String.class)).isEqualTo("settled");

    assertThat(onlyAudit("CondorFlattenIncomplete").getSubject())
        .containsEntry("shorts_covered", false)
        .containsEntry("longs_closed", false);
    // Once to price the remainder at 16:15, once for the next-open reconciliation.
    verify(condorExec, Mockito.times(2)).heldCondorLegs(any(), any(), any());
  }

  @Test
  void partialFlatten_settlementPricesOnlyTheLegsStillHeld() {
    // Shorts not confirmed covered → wings left in place. At 16:15 the broker holds only the two
    // long wings; with spot 610 the long 604C is worth 6, so the remainder settles +$600 — NOT the
    // full-combo −$190 that pricing all four legs would report.
    when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(false, false, "short cover not confirmed filled"));
    when(condorExec.heldCondorLegs(any(), any(), any()))
        .thenReturn(
            List.of(
                new HeldLeg("XSP   261005C00604000", 1L), new HeldLeg("XSP   261005P00596000", 1L)))
        .thenReturn(List.of());
    when(market.settlementSpot("XSP")).thenReturn(610.0);
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    WorkflowStub.fromTyped(wf).getResult(String.class);

    Map<String, Object> s = onlyAudit("CondorSettled").getSubject();
    assertThat(s).containsEntry("partial", true);
    assertThat(new BigDecimal(s.get("expected_pnl").toString())).isEqualByComparingTo("600.00");
  }

  @Test
  void cacheSeedFailure_neverBlocksTheHold() {
    Mockito.doThrow(new RuntimeException("redis down"))
        .when(positionLookup)
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
    when(market.settlementSpot("XSP")).thenReturn(600.0);

    assertThat(runToCompletion()).isEqualTo("settled");
  }

  @Test
  void shortCoverNeverFills_flattenIsReCalledUpToMaxAttempts_thenPagesOnceAndSettles() {
    // The activity RETURNS (not throws) shorts_covered=false when a cover stays unfilled through
    // its polls; the hold itself must re-call it, then page only after the final attempt.
    when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(false, false, "short cover not confirmed filled"));
    when(market.settlementSpot("XSP")).thenReturn(600.0);
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.riskBreach(new RiskBreachPayload());

    assertThat(WorkflowStub.fromTyped(wf).getResult(String.class)).isEqualTo("settled");

    verify(condorExec, Mockito.times(CondorHoldWorkflowImpl.EXEC_MAX_ATTEMPTS))
        .flattenCondor(any());
    assertThat(onlyAudit("CondorFlattenIncomplete").getSubject())
        .containsEntry("attempts", CondorHoldWorkflowImpl.EXEC_MAX_ATTEMPTS)
        .containsEntry("exit_reason", "killswitch_flatten");
    onlyAudit("CondorSettled");
  }

  @Test
  void slowShortCover_secondAttemptCompletes_noIncompletePage() {
    when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(false, false, "short cover not confirmed filled"))
        .thenReturn(new CondorFlattenResult(true, true, null));
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    assertThat(WorkflowStub.fromTyped(wf).getResult(String.class))
        .isEqualTo("flattened:operator_force_close");
    verify(condorExec, Mockito.times(2)).flattenCondor(any());
    assertThat(onlyAudit("CondorFlattened").getSubject()).containsEntry("attempts", 2);
  }

  @Test
  void pastTheExpiryClose_flattenIsNotReAttempted() {
    when(calendar.durationUntilEodCloseEt(CondorHoldWorkflowImpl.EXPIRY_CLOSE_ET))
        .thenReturn(Duration.ZERO);
    when(condorExec.flattenCondor(any()))
        .thenReturn(new CondorFlattenResult(false, false, "short cover not confirmed filled"));
    when(market.settlementSpot("XSP")).thenReturn(600.0);
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    WorkflowStub.fromTyped(wf).getResult(String.class);

    verify(condorExec, Mockito.times(1)).flattenCondor(any());
    onlyAudit("CondorFlattenIncomplete");
  }

  @Test
  void flattenActivityFailure_isAuditedAndStillReconciles() {
    when(condorExec.flattenCondor(any()))
        .thenThrow(
            io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                "market closed", "InvalidRequestError"));
    when(market.settlementSpot("XSP")).thenReturn(600.0);
    CondorHoldWorkflow wf = stub();
    WorkflowClient.start(wf::run, input());
    wf.forceClose("operator:alice");

    assertThat(WorkflowStub.fromTyped(wf).getResult(String.class)).isEqualTo("settled");
    assertThat(onlyAudit("CondorFlattenIncomplete").getSubject().get("reason").toString())
        .contains("flatten_activity_failed");
  }
}
