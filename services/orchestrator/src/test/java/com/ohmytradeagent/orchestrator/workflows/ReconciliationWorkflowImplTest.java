package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.ReconciliationSummary;
import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ReconciliationWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  // Phase 2c.2: broker_target=alpaca-paper -> task queue broker-alpaca-paper via the factory.
  private static final String EXEC_QUEUE = "broker-alpaca-paper";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private ReconciliationExecActivity exec;
  private ReconciliationMetricsActivities metrics;
  private PositionLookupActivities positionLookup;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(ReconciliationWorkflowImpl.class);
    audit = Mockito.mock(AuditActivities.class);
    exec = Mockito.mock(ReconciliationExecActivity.class);
    metrics = Mockito.mock(ReconciliationMetricsActivities.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);
    coreWorker.registerActivitiesImplementations(audit, metrics, positionLookup);
    Worker brokerWorker = env.newWorker(EXEC_QUEUE);
    brokerWorker.registerActivitiesImplementations(exec);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void happyPath_matchingJournalAndBroker_zeroOrphans() {
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-1", "OCC-1", OffsetDateTime.now(ZoneOffset.UTC))));
    when(exec.brokerListOpenOrders()).thenReturn(List.of(broker("brk-1", "intent-1", "OCC-1")));

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getJournalEntriesChecked()).isEqualTo(1L);
    assertThat(summary.getBrokerOrdersChecked()).isEqualTo(1L);
    assertThat(summary.getJournalOrphans()).isEqualTo(0L);
    assertThat(summary.getBrokerOrphans()).isEqualTo(0L);

    AuditEvent completed = captureKind("ReconciliationCompleted");
    assertThat(((Number) completed.getSubject().get("journal_orphans")).longValue()).isEqualTo(0L);
    assertThat(((Number) completed.getSubject().get("broker_orphans")).longValue()).isEqualTo(0L);
  }

  @Test
  void journalOrphan_oldEntryWithNoBrokerMatch_emitsAudit() {
    // 10 minutes ago — older than the 5-minute stale threshold.
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-orphan", "OCC-orphan", old)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());

    ReconciliationSummary summary = runWorkflow();
    assertThat(summary.getJournalOrphans()).isEqualTo(1L);
    assertThat(summary.getBrokerOrphans()).isEqualTo(0L);

    AuditEvent orphan = captureKind("JournalOrphan");
    assertThat(orphan.getSubject())
        .containsEntry("intent_key", "intent-orphan")
        .containsEntry("state", "RECORDED");
    assertThat(((Number) orphan.getSubject().get("stale_secs")).longValue()).isGreaterThan(60L);
  }

  @Test
  void journalOrphan_recentEntryWithNoBrokerMatch_notReported() {
    // Within stale window — should NOT fire orphan audit.
    OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-fresh", "OCC-fresh", recent)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());

    ReconciliationSummary summary = runWorkflow();
    assertThat(summary.getJournalOrphans()).isEqualTo(0L);
  }

  @Test
  void brokerOrphan_orderWithNoJournalEntry_emitsAudit() {
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders())
        .thenReturn(List.of(broker("brk-stranger", "client-stranger", "OCC-stranger")));

    ReconciliationSummary summary = runWorkflow();
    assertThat(summary.getJournalOrphans()).isEqualTo(0L);
    assertThat(summary.getBrokerOrphans()).isEqualTo(1L);

    AuditEvent orphan = captureKind("BrokerOrphan");
    assertThat(orphan.getSubject())
        .containsEntry("broker_order_id", "brk-stranger")
        .containsEntry("client_order_id", "client-stranger")
        .containsEntry("option_symbol", "OCC-stranger");
  }

  @Test
  void runWithNullBrokerTargetRaisesInvalidBrokerTargetError() {
    // Phase 2c.2 review polish (#50 item 1): a null broker_target on the workflow input must
    // surface as a non-retryable InvalidBrokerTargetError (via the factory's null/blank guard)
    // instead of NPEing inside the workflow body.
    ReconciliationWorkflowInput in = new ReconciliationWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    // broker_target deliberately left null.
    ReconciliationWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    WorkflowStub.fromTyped(wf).start(in);

    assertThatThrownBy(() -> WorkflowStub.fromTyped(wf).getResult(ReconciliationSummary.class))
        .isInstanceOf(WorkflowFailedException.class)
        .hasCauseInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> {
              ApplicationFailure af = (ApplicationFailure) t.getCause();
              assertThat(af.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(af.isNonRetryable()).isTrue();
            });
  }

  @Test
  void emptyJournalAndBroker_zeroCounts() {
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());

    ReconciliationSummary summary = runWorkflow();
    assertThat(summary.getJournalEntriesChecked()).isEqualTo(0L);
    assertThat(summary.getBrokerOrdersChecked()).isEqualTo(0L);
    assertThat(summary.getJournalOrphans()).isEqualTo(0L);
    assertThat(summary.getBrokerOrphans()).isEqualTo(0L);
  }

  @Test
  void metricsActivityFailure_workflowStillReturnsSummary_andEmitsAuditFallback() {
    // Issue #89 / PR #129 review: the metrics Activity is non-fatal — a metrics outage must NOT
    // fail the cycle. Per docs/ops/reconciliation-metrics.md the gate operator falls back to the
    // audit-log SQL on the ReconciliationMetricsRecordFailed event, so the audit must carry
    // non-null error_class + error_message for the failure to be diagnosable.
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-orphan", "OCC-orphan", old)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    Mockito.doThrow(new RuntimeException("meter registry exploded"))
        .when(metrics)
        .recordCycle(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong());

    ReconciliationSummary summary = runWorkflow();

    // (a) workflow still returns its summary normally despite the metrics outage.
    assertThat(summary.getJournalOrphans()).isEqualTo(1L);
    assertThat(summary.getBrokerOrphans()).isEqualTo(0L);
    assertThat(summary.getJournalEntriesChecked()).isEqualTo(1L);

    // (b) the ReconciliationMetricsRecordFailed audit fallback is emitted with non-null
    // error_class + error_message so the gate operator's SQL fallback is diagnosable.
    AuditEvent failed = captureKind("ReconciliationMetricsRecordFailed");
    assertThat(failed.getSubject()).containsEntry("broker_target", "alpaca-paper");
    assertThat(((Number) failed.getSubject().get("discrepancies")).longValue()).isEqualTo(1L);
    assertThat(((Number) failed.getSubject().get("intents_reconciled")).longValue()).isEqualTo(1L);
    assertThat((String) failed.getSubject().get("error_class")).isNotBlank();
    assertThat((String) failed.getSubject().get("error_message")).isNotBlank();
  }

  @Test
  void metricsActivity_invokedExactlyOnce_withJournalAndOrphanCounts() {
    // Issue #89: workflow must call ReconciliationMetricsActivities.recordCycle exactly once per
    // cycle, with discrepancies = journalOrphans + brokerOrphans and intentsReconciled =
    // journal.size().
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(
            List.of(
                journal("intent-1", "OCC-1", OffsetDateTime.now(ZoneOffset.UTC)),
                journal("intent-orphan", "OCC-orphan", old)));
    when(exec.brokerListOpenOrders())
        .thenReturn(
            List.of(
                broker("brk-1", "intent-1", "OCC-1"),
                broker("brk-stranger", "client-stranger", "OCC-stranger")));

    runWorkflow();

    // journal.size() = 2, journalOrphans = 1 (the stale entry), brokerOrphans = 1 (the stranger).
    verify(metrics, times(1))
        .recordCycle(
            eq("dev"),
            eq("copytrade-v1"),
            eq("alpaca-paper"),
            anyLong(),
            /* discrepancies= */ eq(2L),
            /* intentsReconciled= */ eq(2L));
  }

  @Test
  void run_brokerPositionWithNoRunningWorkflow_emitsPositionOrphan() {
    // Issue #165 Phase 3: a broker-held position with no running PositionWorkflow must surface
    // as a PositionOrphan audit + a position_orphans count on the summary. Workflow id is rebuilt
    // from the most recent FILLED journal entry for the OCC.
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), eq("SPY   260519C00737000")))
        .thenReturn(
            List.of(
                filledJournal("intent-1", "chat-1506342699765338194:0", "SPY   260519C00737000")));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getPositionOrphans()).isEqualTo(1L);

    AuditEvent orphan = captureKind("PositionOrphan");
    assertThat(orphan.getSubject())
        .containsEntry("option_symbol", "SPY   260519C00737000")
        .containsEntry("journal_status", "filled")
        .containsEntry("journal_entry_signal_id", "chat-1506342699765338194:0")
        .containsEntry(
            "expected_workflow_id",
            "t-dev/s-copytrade-v1/pos/SPY   260519C00737000/chat-1506342699765338194:0");
    assertThat(((Number) orphan.getSubject().get("qty")).longValue()).isEqualTo(5L);

    AuditEvent completed = captureKind("ReconciliationCompleted");
    assertThat(((Number) completed.getSubject().get("position_orphans")).longValue()).isEqualTo(1L);
  }

  @Test
  void run_brokerPositionWithRunningWorkflow_noOrphan() {
    // Workflow already running for this position → no PositionOrphan audit, count stays at 0.
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), eq("SPY   260519C00737000")))
        .thenReturn(
            List.of(
                filledJournal("intent-1", "chat-1506342699765338194:0", "SPY   260519C00737000")));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getPositionOrphans()).isEqualTo(0L);
    Mockito.verify(audit, Mockito.never())
        .log(Mockito.argThat(e -> e != null && "PositionOrphan".equals(e.getKind())));
  }

  @Test
  void run_brokerPositionMissingJournalEntry_emitsPositionOrphanMissing() {
    // Broker holds a position with no FILLED journal record → strongest orphan signal, emit a
    // PositionOrphan with expected_workflow_id=null + journal_status=missing.
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), anyString())).thenReturn(List.of());

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getPositionOrphans()).isEqualTo(1L);

    AuditEvent orphan = captureKind("PositionOrphan");
    assertThat(orphan.getSubject())
        .containsEntry("option_symbol", "SPY   260519C00737000")
        .containsEntry("journal_status", "missing")
        .containsEntry("expected_workflow_id", null);
    assertThat(((Number) orphan.getSubject().get("qty")).longValue()).isEqualTo(5L);
  }

  // ---------- helpers ----------

  private ReconciliationSummary runWorkflow() {
    ReconciliationWorkflowInput in = new ReconciliationWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    in.setBrokerTarget(ReconciliationWorkflowInput.BrokerTarget.ALPACA_PAPER);
    ReconciliationWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    return wf.run(in);
  }

  private JournalEntry journal(String intentKey, String occ, OffsetDateTime recordedAt) {
    JournalEntry j = new JournalEntry();
    j.setSchemaVersion(1L);
    j.setIntentKey(intentKey);
    j.setSignalId("sig-1");
    j.setTenantId("dev");
    j.setStrategyId("copytrade-v1");
    j.setBrokerTarget(JournalEntry.BrokerTarget.PAPER);
    j.setClientOrderId(intentKey);
    j.setOptionSymbol(occ);
    j.setSide(JournalEntry.Side.BUY);
    j.setQty(1L);
    j.setState(JournalEntry.State.RECORDED);
    j.setRecordedAt(recordedAt);
    return j;
  }

  private JournalEntry filledJournal(String intentKey, String signalId, String occ) {
    JournalEntry j = new JournalEntry();
    j.setSchemaVersion(1L);
    j.setIntentKey(intentKey);
    j.setSignalId(signalId);
    j.setTenantId("dev");
    j.setStrategyId("copytrade-v1");
    j.setBrokerTarget(JournalEntry.BrokerTarget.ALPACA_PAPER);
    j.setClientOrderId(intentKey);
    j.setOptionSymbol(occ);
    j.setSide(JournalEntry.Side.BUY);
    j.setQty(5L);
    j.setState(JournalEntry.State.FILLED);
    j.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
    return j;
  }

  private BrokerPosition brokerPosition(String occ, long qty, BigDecimal avgEntryPrice) {
    BrokerPosition p = new BrokerPosition();
    p.setSchemaVersion(1L);
    p.setOptionSymbol(occ);
    p.setQty(qty);
    p.setSide(BrokerPosition.Side.LONG);
    p.setAvgEntryPrice(avgEntryPrice);
    return p;
  }

  private BrokerOpenOrder broker(String brokerOrderId, String clientOrderId, String occ) {
    BrokerOpenOrder o = new BrokerOpenOrder();
    o.setSchemaVersion(1L);
    o.setBrokerOrderId(brokerOrderId);
    o.setClientOrderId(clientOrderId);
    o.setOptionSymbol(occ);
    o.setSide(BrokerOpenOrder.Side.BUY);
    o.setQty(1L);
    o.setState("open");
    return o;
  }

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }
}
