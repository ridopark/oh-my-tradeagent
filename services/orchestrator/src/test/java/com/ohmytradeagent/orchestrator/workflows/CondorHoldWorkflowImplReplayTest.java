package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.activities.CondorExecActivity;
import com.ohmytradeagent.contract.activities.CondorMarketActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/** Replay-determinism coverage for {@link CondorHoldWorkflowImpl}'s version gates. */
class CondorHoldWorkflowImplReplayTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";

  // #920: an ITM hold that settled and reconciled clean (legs gone at the next open) under the
  // pre-#920 impl — no booked-settlement-cash read. Generated once; must never be regenerated.
  private static final String PRE_CASH_FIXTURE_RESOURCE =
      "temporal/replay/condor-hold-pre-settlement-cash-history.json";
  private static final Path PRE_CASH_FIXTURE_SOURCE_PATH =
      Path.of("src/test/resources/temporal/replay/condor-hold-pre-settlement-cash-history.json");
  private static final String PRE_CASH_WORKFLOW_ID =
      "t-staging_paper/s-gated_condor/condor/2026-10-05";

  /**
   * #920 SENTINEL. A hold recorded before the booked-settlement-cash check replays under the new
   * impl: the {@code condor-hold-settlement-cash-v1} marker is absent, so the check (a NEW exec
   * activity command after the next-open held-legs read) stays off and the stream replays
   * byte-for-byte.
   *
   * <p><b>Teeth verified 2026-10-08 (observed).</b> With the {@code getVersion} check removed this
   * replay throws {@code [TMPRL1100] Failure handling event 69 of type
   * 'EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED'} (the cash read is scheduled where the recorded hold
   * completed).
   */
  @Test
  void preSettlementCashHistoryReplaysWithoutCashRead() throws Exception {
    assertThat(getClass().getClassLoader().getResource(PRE_CASH_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. It is one-shot (pre-change only); see"
                + " regeneratePreSettlementCashFixture.",
            PRE_CASH_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        PRE_CASH_FIXTURE_RESOURCE, CondorHoldWorkflowImpl.class);
  }

  /** One-shot generator for {@link #preSettlementCashHistoryReplaysWithoutCashRead}. */
  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regeneratePreSettlementCashFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      PositionLookupActivities positionLookup = Mockito.mock(PositionLookupActivities.class);
      CondorMarketActivity market = Mockito.mock(CondorMarketActivity.class);
      CondorExecActivity condorExec = Mockito.mock(CondorExecActivity.class);
      when(calendar.durationUntilEodCloseEt(CondorHoldWorkflowImpl.SETTLE_CHECK_ET))
          .thenReturn(Duration.ofHours(2));
      when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(17));
      when(condorExec.heldCondorLegs(any(), any(), any())).thenReturn(List.of());
      // 602.50 vs short 601C: ITM, settlement debit 1.50.
      when(market.settlementSpot("XSP")).thenReturn(602.50);

      Worker core = env.newWorker(CORE_QUEUE);
      core.registerWorkflowImplementationTypes(CondorHoldWorkflowImpl.class);
      core.registerActivitiesImplementations(audit, calendar, positionLookup);
      env.newWorker(WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE)
          .registerActivitiesImplementations(market);
      env.newWorker(BROKER_QUEUE).registerActivitiesImplementations(condorExec);
      env.start();

      CondorHoldWorkflow wf =
          env.getWorkflowClient()
              .newWorkflowStub(
                  CondorHoldWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId(PRE_CASH_WORKFLOW_ID)
                      .build());
      assertThat(
              wf.run(
                  new CondorHoldWorkflowInput(
                      "staging_paper",
                      "gated_condor",
                      "alpaca-paper",
                      "2026-10-05",
                      "2026-10-05",
                      "XSP",
                      1L,
                      new BigDecimal("1.10"),
                      CondorSessionWorkflowImplTest.condorLegs())))
          .isEqualTo("settled");
      json = env.getWorkflowClient().fetchHistory(PRE_CASH_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }
    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    assertThat(json).doesNotContain("condor-hold-settlement-cash-v1");
    Files.createDirectories(PRE_CASH_FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(PRE_CASH_FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }
}
