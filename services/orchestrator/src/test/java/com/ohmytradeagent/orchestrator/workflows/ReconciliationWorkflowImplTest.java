package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
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
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
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
  private AuditQueryActivities auditQuery;
  private ReconciliationExecActivity exec;
  private ReconciliationMetricsActivities metrics;
  private PositionLookupActivities positionLookup;

  @BeforeEach
  void setUp() {
    // Issue #219: disable time skipping so virtual time stays aligned with wall clock between
    // sequential runWorkflow() calls. The realStateMachine tests stub firstSeenPositionOrphan /
    // firstSeenJournalOrphan with wall-clock-relative timestamps (t-2min, t-31min) and rely on
    // Duration.between(firstSeen, workflowNow()) matching real elapsed time. With default time
    // skipping, the env can fast-forward virtual time arbitrarily while the test thread blocks on
    // wf.run() completion, causing tick-2's stubbed t-2min firstSeen to look 30+ minutes old and
    // unexpectedly fire the escalation. This made the test deterministic locally but flaky in CI.
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(ReconciliationWorkflowImpl.class);
    audit = Mockito.mock(AuditActivities.class);
    auditQuery = Mockito.mock(AuditQueryActivities.class);
    exec = Mockito.mock(ReconciliationExecActivity.class);
    metrics = Mockito.mock(ReconciliationMetricsActivities.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);
    // Issue #206: default to "no prior detection" so existing tests (which don't care about
    // debounce) keep emitting per-cycle PositionOrphan / JournalOrphan audits as before. The
    // primitive long return defaults to 0 already, but make it explicit for readability.
    when(auditQuery.countPriorPositionOrphans(
            anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(0L);
    when(auditQuery.countPriorJournalOrphans(anyString(), anyString(), anyString(), any()))
        .thenReturn(0L);
    // Per-window escalation guard: default to "no prior Ongoing audit in the window" so the
    // existing threshold tests still emit the PositionOrphanOngoing / JournalOrphanOngoing signal
    // on the first escalation tick.
    when(auditQuery.countPriorPositionOrphanOngoing(
            anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(0L);
    when(auditQuery.countPriorJournalOrphanOngoing(anyString(), anyString(), anyString(), any()))
        .thenReturn(0L);
    coreWorker.registerActivitiesImplementations(audit, auditQuery, metrics, positionLookup);
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
  void run_brokerCompactOccVsJournalPaddedOcc_resolvesOwnerNoOrphan() {
    // Issue #243: the broker reports the *compact* OCC (Alpaca strips the space-padding on order
    // placement and returns the compact symbol from listOpenPositions) while the journal row — and
    // the running PositionWorkflow id, built at spawn from OccSymbol.of's %-6s padded root — hold
    // the *padded* 21-char OCC. Recon must rebuild expected_workflow_id from the journal row's
    // canonical (padded) option_symbol, not the broker's compact form, so the running owner is
    // found and NO false PositionOrphan fires.
    String compactOcc = "SPY260519C00737000";
    String paddedOcc = "SPY   260519C00737000";
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition(compactOcc, 5L, new BigDecimal("0.84"))));
    // The journal lookup is format-agnostic (JooqOrderIntentJournal.findLatestFilledByOcc strips
    // padding), so a compact broker OCC resolves the padded FILLED row.
    when(exec.journalListFilledByOcc(anyString(), anyString(), eq(compactOcc)))
        .thenReturn(List.of(filledJournal("intent-1", "chat-1506342699765338194:0", paddedOcc)));
    // The live owner's workflow id carries the PADDED OCC (spawn-time form). Recon must look it up
    // under that id, not the compact one.
    String paddedWfId = "t-dev/s-copytrade-v1/pos/" + paddedOcc + "/chat-1506342699765338194:0";
    when(positionLookup.isPositionWorkflowRunning(eq(paddedWfId))).thenReturn(true);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getPositionOrphans()).isEqualTo(0L);
    verify(audit, never())
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

  @Test
  void positionOrphan_priorDetectionWithinWindow_isDebounced() {
    // Issue #206: the same broker position has already been detected as a PositionOrphan within
    // the 1h debounce window. The workflow must suppress the per-cycle PositionOrphan audit
    // entirely (no PositionOrphan AND no PositionOrphanOngoing yet — escalation only fires at the
    // 3rd detection). Summary still counts the orphan since the broker state is unchanged.
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), anyString())).thenReturn(List.of());
    // 1 prior detection in the window → priorCount=1, this tick is the 2nd. Below the threshold
    // (escalation fires at the 3rd), so the audit is fully suppressed.
    when(auditQuery.countPriorPositionOrphans(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(1L);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getPositionOrphans()).isEqualTo(1L);
    // No PositionOrphan audit emitted.
    Mockito.verify(audit, never())
        .log(Mockito.argThat(e -> e != null && "PositionOrphan".equals(e.getKind())));
    // No PositionOrphanOngoing escalation yet (only 2nd detection, threshold is 3).
    Mockito.verify(audit, never())
        .log(Mockito.argThat(e -> e != null && "PositionOrphanOngoing".equals(e.getKind())));
  }

  @Test
  void journalOrphan_priorDetectionWithinWindow_isDebounced() {
    // Issue #221 (parallel to positionOrphan_priorDetectionWithinWindow_isDebounced): the same
    // journal entry has already been detected as a JournalOrphan within the debounce window. The
    // workflow must suppress both the per-cycle JournalOrphan audit AND the JournalOrphanOngoing
    // escalation (priorCount=1 enters the else/debounce-suppression branch; firstSeenJournalOrphan
    // IS called but returns null by default — Mockito's default for unstubbed Object methods — so
    // the `firstSeen != null` guard prevents the JournalOrphanOngoing escalation). Summary still
    // counts the orphan since the journal state is unchanged.
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-debounce", "OCC-debounce", old)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    // 1 prior detection in the window → priorCount=1, this tick is the 2nd. Debounce suppresses
    // the per-cycle JournalOrphan audit.
    when(auditQuery.countPriorJournalOrphans(
            eq("dev"), eq("copytrade-v1"), eq("intent-debounce"), any()))
        .thenReturn(1L);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getJournalOrphans()).isEqualTo(1L);
    // No JournalOrphan audit emitted (debounce suppression).
    Mockito.verify(audit, never())
        .log(Mockito.argThat(e -> e != null && "JournalOrphan".equals(e.getKind())));
    // No JournalOrphanOngoing escalation either (firstSeenJournalOrphan returns null by default;
    // the time-based escalation requires a non-null firstSeen older than ORPHAN_ESCALATION_WINDOW).
    Mockito.verify(audit, never())
        .log(Mockito.argThat(e -> e != null && "JournalOrphanOngoing".equals(e.getKind())));
  }

  @Test
  void positionOrphan_thirdDetectionWithinWindow_emitsOngoingEscalation() {
    // Issue #219: escalation is now driven by time-since-first-seen, not by a count threshold.
    // The audit_log COUNT freezes at 1 once debounce suppression kicks in, so the workflow must
    // fall through to firstSeenPositionOrphan(...) when priorCount >= 1, and emit
    // PositionOrphanOngoing iff the first-seen row is older than ORPHAN_ESCALATION_WINDOW (30m).
    OffsetDateTime firstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(45);
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), anyString())).thenReturn(List.of());
    // Realistic post-#219 count source: tick 1 wrote a PositionOrphan, debounce suppressed every
    // subsequent tick, so the count is frozen at 1. NOT mocked to 2 — that was the old bug-mask.
    when(auditQuery.countPriorPositionOrphans(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(1L);
    when(auditQuery.firstSeenPositionOrphan(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(firstSeen);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getPositionOrphans()).isEqualTo(1L);
    // PositionOrphan is NOT emitted at the escalation tick (the Ongoing event replaces it).
    Mockito.verify(audit, never())
        .log(Mockito.argThat(e -> e != null && "PositionOrphan".equals(e.getKind())));

    AuditEvent ongoing = captureKind("PositionOrphanOngoing");
    assertThat(ongoing.getSubject())
        .containsEntry("option_symbol", "SPY   260519C00737000")
        .containsEntry("journal_status", "missing")
        .containsEntry("first_seen_at", firstSeen.toString());
    // Issue #231: time-based escalation carries the actual age-since-first-seen, not the static
    // escalation window. firstSeen is ~45m ago so the computed age is comfortably >= 1800s; assert
    // >= because the workflow `now` is not pinned to the test wall clock (small skew is expected).
    assertThat(((Number) ongoing.getSubject().get("age_secs")).longValue())
        .isGreaterThanOrEqualTo(1800L);
    assertThat(ongoing.getSubject()).doesNotContainKey("escalation_window_secs");
    assertThat((String) ongoing.getSubject().get("last_seen_at")).isNotBlank();
  }

  @Test
  void journalOrphan_thirdDetectionWithinWindow_emitsOngoingEscalation() {
    // Issue #219: JournalOrphan escalation also keys off firstSeenJournalOrphan, not a count
    // threshold. priorCount stays at 1 once debounce suppression activates.
    OffsetDateTime firstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(45);
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-orphan", "OCC-orphan", old)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(auditQuery.countPriorJournalOrphans(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(1L);
    when(auditQuery.firstSeenJournalOrphan(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(firstSeen);

    ReconciliationSummary summary = runWorkflow();

    assertThat(summary.getJournalOrphans()).isEqualTo(1L);
    Mockito.verify(audit, never())
        .log(Mockito.argThat(e -> e != null && "JournalOrphan".equals(e.getKind())));

    AuditEvent ongoing = captureKind("JournalOrphanOngoing");
    assertThat(ongoing.getSubject())
        .containsEntry("intent_key", "intent-orphan")
        .containsEntry("first_seen_at", firstSeen.toString());
    // Issue #231: assert the computed age-since-first-seen (>= the 30m window), not the static
    // escalation window value, and confirm the old field is gone.
    assertThat(((Number) ongoing.getSubject().get("age_secs")).longValue())
        .isGreaterThanOrEqualTo(1800L);
    assertThat(ongoing.getSubject()).doesNotContainKey("escalation_window_secs");
  }

  @Test
  void positionOrphan_fourthDetectionWithinWindow_doesNotEmitOngoingTwice() {
    // Issue #219 invariant: once escalated within a debounce window, subsequent ticks must NOT
    // re-emit PositionOrphanOngoing even though the time-since-first-seen condition stays true.
    // The countPriorPositionOrphanOngoing == 0 guard is what enforces once-per-window — the
    // first-seen timestamp would re-trigger the >= window check forever otherwise.
    // Tick-3: priorOrphans=1, firstSeen=45m ago, priorOngoing=0 → emit PositionOrphanOngoing.
    // Tick-4: same priorOrphans=1, same firstSeen=45m ago, priorOngoing=1 → suppress.
    OffsetDateTime firstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(45);
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), anyString())).thenReturn(List.of());
    when(auditQuery.countPriorPositionOrphans(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(1L);
    when(auditQuery.firstSeenPositionOrphan(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(firstSeen);
    // Tick-3 sees 0 prior Ongoing rows (emits); tick-4 sees 1 (suppresses).
    when(auditQuery.countPriorPositionOrphanOngoing(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(0L, 1L);

    runWorkflow(); // tick-3
    runWorkflow(); // tick-4

    // Exactly one PositionOrphanOngoing audit emitted across both ticks.
    Mockito.verify(audit, times(1))
        .log(Mockito.argThat(e -> e != null && "PositionOrphanOngoing".equals(e.getKind())));
  }

  @Test
  void journalOrphan_fourthDetectionWithinWindow_doesNotEmitOngoingTwice() {
    // Parallel to the PositionOrphan case — JournalOrphan keyed on intent_key. Same #219
    // invariant: time-based trigger + priorOngoing == 0 guard together enforce once-per-window.
    OffsetDateTime firstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(45);
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-orphan", "OCC-orphan", old)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(auditQuery.countPriorJournalOrphans(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(1L);
    when(auditQuery.firstSeenJournalOrphan(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(firstSeen);
    when(auditQuery.countPriorJournalOrphanOngoing(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(0L, 1L);

    runWorkflow(); // tick-3
    runWorkflow(); // tick-4

    Mockito.verify(audit, times(1))
        .log(Mockito.argThat(e -> e != null && "JournalOrphanOngoing".equals(e.getKind())));
  }

  @Test
  void positionOrphan_realStateMachine_emitsOngoingAfterEscalationWindow() {
    // Issue #219: drive the workflow through 3 sequential ticks with the real audit_log shape —
    // count is 0 on tick 1 (first sighting), then 1 on every subsequent tick because debounce
    // suppression freezes it. The firstSeen timestamp advances from null (tick 1, nothing in the
    // window yet) → t-2min (tick 2, within escalation window) → t-31min (tick 3, past the 30m
    // escalation window).
    //
    // Expected emission sequence:
    //   tick 1 → PositionOrphan (first sighting)
    //   tick 2 → suppressed (firstSeen too recent)
    //   tick 3 → PositionOrphanOngoing (firstSeen > escalation window)
    OffsetDateTime tick2FirstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
    OffsetDateTime tick3FirstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(31);
    when(exec.journalDumpOpen(anyString(), anyString())).thenReturn(List.of());
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(exec.brokerListOpenPositions(anyString(), anyString()))
        .thenReturn(List.of(brokerPosition("SPY   260519C00737000", 5L, new BigDecimal("0.84"))));
    when(exec.journalListFilledByOcc(anyString(), anyString(), anyString())).thenReturn(List.of());
    when(auditQuery.countPriorPositionOrphans(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(0L, 1L, 1L);
    // firstSeenPositionOrphan is only invoked on tick 2 and tick 3 (tick 1 takes the
    // priorCount==0 first-emission branch and skips the firstSeen lookup).
    when(auditQuery.firstSeenPositionOrphan(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(tick2FirstSeen, tick3FirstSeen);
    // countPriorPositionOrphanOngoing is only invoked on tick 3 (tick 1 skips it, tick 2's
    // firstSeen is too recent so the time-gate short-circuits before it's called).
    when(auditQuery.countPriorPositionOrphanOngoing(
            eq("dev"), eq("copytrade-v1"), eq("SPY   260519C00737000"), eq("missing"), any()))
        .thenReturn(0L);

    runWorkflow(); // tick 1
    runWorkflow(); // tick 2
    runWorkflow(); // tick 3

    // Exactly one PositionOrphan (tick 1) and exactly one PositionOrphanOngoing (tick 3).
    Mockito.verify(audit, times(1))
        .log(Mockito.argThat(e -> e != null && "PositionOrphan".equals(e.getKind())));
    Mockito.verify(audit, times(1))
        .log(Mockito.argThat(e -> e != null && "PositionOrphanOngoing".equals(e.getKind())));

    AuditEvent ongoing = captureKind("PositionOrphanOngoing");
    assertThat(ongoing.getSubject())
        .containsEntry("option_symbol", "SPY   260519C00737000")
        .containsEntry("journal_status", "missing")
        .containsEntry("first_seen_at", tick3FirstSeen.toString());
  }

  @Test
  void journalOrphan_realStateMachine_emitsOngoingAfterEscalationWindow() {
    // Issue #219: same 3-tick state machine for JournalOrphan keyed on intent_key.
    //   tick 1 → JournalOrphan (count=0, firstSeen=null)
    //   tick 2 → suppressed (count=1, firstSeen=t-2m)
    //   tick 3 → JournalOrphanOngoing (count=1, firstSeen=t-31m)
    OffsetDateTime tick2FirstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
    OffsetDateTime tick3FirstSeen = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(31);
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
    when(exec.journalDumpOpen(anyString(), anyString()))
        .thenReturn(List.of(journal("intent-orphan", "OCC-orphan", old)));
    when(exec.brokerListOpenOrders()).thenReturn(List.of());
    when(auditQuery.countPriorJournalOrphans(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(0L, 1L, 1L);
    // firstSeenJournalOrphan is only invoked on tick 2 and tick 3 (tick 1 first-emission branch
    // skips it).
    when(auditQuery.firstSeenJournalOrphan(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(tick2FirstSeen, tick3FirstSeen);
    // countPriorJournalOrphanOngoing only invoked on tick 3 (tick 2 short-circuits on time gate).
    when(auditQuery.countPriorJournalOrphanOngoing(
            eq("dev"), eq("copytrade-v1"), eq("intent-orphan"), any()))
        .thenReturn(0L);

    runWorkflow(); // tick 1
    runWorkflow(); // tick 2
    runWorkflow(); // tick 3

    Mockito.verify(audit, times(1))
        .log(Mockito.argThat(e -> e != null && "JournalOrphan".equals(e.getKind())));
    Mockito.verify(audit, times(1))
        .log(Mockito.argThat(e -> e != null && "JournalOrphanOngoing".equals(e.getKind())));

    AuditEvent ongoing = captureKind("JournalOrphanOngoing");
    assertThat(ongoing.getSubject())
        .containsEntry("intent_key", "intent-orphan")
        .containsEntry("first_seen_at", tick3FirstSeen.toString());
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
