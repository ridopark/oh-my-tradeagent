package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #239 unit coverage for the operator-triggered orphan-adoption Activity. All five
 * collaborators are mocked. Covers: happy-path adoption, already-owned no-op, broker-doesn't-hold
 * refusal, and copytrade {@code eod_force_flatten=false} propagation.
 */
class PositionAdoptionActivitiesImplTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OCC = "UNH   260618C00400000";
  private static final String SIGNAL_ID = "sig-abc";
  private static final String INTENT_KEY = "intent-abc";
  private static final String BROKER_ORDER_ID = "db5459fe";
  private static final OffsetDateTime FILLED_AT = OffsetDateTime.parse("2026-05-19T17:08:11Z");

  private WorkflowClient workflowClient;
  private StrategyActivities strategy;
  private PositionLookupActivities positionLookup;
  private ReconciliationExecActivity exec;
  private AuditActivities audit;
  private WorkflowStub stub;

  private PositionAdoptionActivitiesImpl activities;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    strategy = mock(StrategyActivities.class);
    positionLookup = mock(PositionLookupActivities.class);
    exec = mock(ReconciliationExecActivity.class);
    audit = mock(AuditActivities.class);
    stub = mock(WorkflowStub.class);

    when(workflowClient.newUntypedWorkflowStub(anyString(), any())).thenReturn(stub);

    activities =
        new PositionAdoptionActivitiesImpl(workflowClient, strategy, positionLookup, exec, audit);
  }

  private BrokerPosition brokerLot(long qty, BigDecimal avgEntryPrice) {
    BrokerPosition p = new BrokerPosition();
    p.setSchemaVersion(1L);
    p.setOptionSymbol(OCC);
    p.setQty(qty);
    p.setSide(BrokerPosition.Side.LONG);
    p.setAvgEntryPrice(avgEntryPrice);
    return p;
  }

  private JournalEntry filledJournalRow() {
    JournalEntry e = new JournalEntry();
    e.setSchemaVersion(1L);
    e.setIntentKey(INTENT_KEY);
    e.setSignalId(SIGNAL_ID);
    e.setTenantId(TENANT);
    e.setStrategyId(STRATEGY);
    e.setOptionSymbol(OCC);
    e.setQty(5L);
    e.setBrokerOrderId(BROKER_ORDER_ID);
    e.setSubmittedAt(FILLED_AT);
    e.setRecordedAt(FILLED_AT);
    return e;
  }

  private StrategyConfig config(Boolean eodForceFlatten) {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setEodForceFlatten(eodForceFlatten);
    c.setPendingTtlPaperSecs(120L);
    return c;
  }

  @Test
  void happyPath_startsOwner_signalsFill_terminalizesJournal_seedsCache_auditsProvenance() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, OCC);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);

    String expectedWfId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    assertThat(result.getWorkflowId()).isEqualTo(expectedWfId);

    // Started the PositionWorkflow with the canonical id + TenantStrategy/ContractSymbol SAs.
    ArgumentCaptor<io.temporal.client.WorkflowOptions> optsCaptor =
        ArgumentCaptor.forClass(io.temporal.client.WorkflowOptions.class);
    verify(workflowClient).newUntypedWorkflowStub(eq("PositionWorkflow"), optsCaptor.capture());
    io.temporal.client.WorkflowOptions opts = optsCaptor.getValue();
    assertThat(opts.getWorkflowId()).isEqualTo(expectedWfId);
    assertThat(opts.getTaskQueue()).isEqualTo("orchestrator-core");
    @SuppressWarnings("unchecked")
    Map<String, Object> sa = (Map<String, Object>) opts.getSearchAttributes();
    assertThat(sa).containsEntry("TenantStrategy", WorkflowIds.tenantStrategy(TENANT, STRATEGY));
    assertThat(sa).containsEntry("ContractSymbol", OCC);

    // start(posInput) was called with the reconstructed input carrying broker-truth qty/price.
    ArgumentCaptor<Object> startArg = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(startArg.capture());
    PositionWorkflowInput posInput = (PositionWorkflowInput) startArg.getValue();
    assertThat(posInput.getTenantId()).isEqualTo(TENANT);
    assertThat(posInput.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(posInput.getEntrySignalId()).isEqualTo(SIGNAL_ID);
    assertThat(posInput.getContractSymbol()).isEqualTo(OCC);
    assertThat(posInput.getQty()).isEqualTo(5L);
    assertThat(posInput.getEntryPremium()).isEqualByComparingTo(new BigDecimal("3.40"));

    // onFill signalled with broker-confirmed payload.
    ArgumentCaptor<Object> signalArgs = ArgumentCaptor.forClass(Object.class);
    verify(stub).signal(eq("onFill"), signalArgs.capture());
    FillSignalPayload fill = (FillSignalPayload) signalArgs.getValue();
    assertThat(fill.getFilledQty()).isEqualTo(5L);
    assertThat(fill.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
    assertThat(fill.getBrokerOrderId()).isEqualTo(BROKER_ORDER_ID);

    // Journal terminalized + discovery cache seeded.
    verify(exec).journalReconcileToFilled(eq(INTENT_KEY), eq(5L), any(BigDecimal.class), any());
    verify(positionLookup).cachePositionMapping(TENANT, STRATEGY, OCC, expectedWfId);

    // PositionAdopted audit emitted with provenance.
    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(auditCaptor.capture());
    AuditEvent ev = auditCaptor.getValue();
    assertThat(ev.getKind()).isEqualTo("PositionAdopted");
    assertThat(ev.getTenantId()).isEqualTo(TENANT);
    assertThat(ev.getStrategyId()).isEqualTo(STRATEGY);
    Map<String, Object> subject = ev.getSubject();
    assertThat(subject).containsEntry("option_symbol", OCC);
    assertThat(subject).containsEntry("entry_signal_id", SIGNAL_ID);
    assertThat(subject).containsEntry("intent_key", INTENT_KEY);
    assertThat(subject).containsEntry("broker_order_id", BROKER_ORDER_ID);
    assertThat(subject).containsEntry("workflow_id", expectedWfId);
    assertThat(subject).containsKey("qty");
    assertThat(subject).containsKey("entry_premium");
  }

  @Test
  void alreadyOwned_noStart_noSignal_noTerminalize_isNoop() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, OCC);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString(), any());
    verify(stub, never()).start(any());
    verify(stub, never()).signal(anyString(), any());
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void brokerDoesNotHold_refused_noStartNoSignalNoTerminalize() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC)).thenReturn(null);

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, OCC);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NOT_HELD);
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString(), any());
    verify(stub, never()).start(any());
    verify(stub, never()).signal(anyString(), any());
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
    // Phantom guard: never even probes for a live owner.
    verify(positionLookup, never()).isPositionWorkflowRunning(anyString());
  }

  @Test
  void retryAfterPartialAdoption_resendsOnFill_terminalizes_caches_audits_andAdopts() {
    // Regression for the retry signal-loss: on an activity retry, start() succeeded on the prior
    // attempt (so it now throws WorkflowExecutionAlreadyStarted) but the fill signal had not yet
    // been sent. The running-probe still returns false (the adopted owner is parked on the
    // first-fill gate, not yet "running" by this probe), so we must NOT bail out on the
    // already-started catch — we must fall through and re-send onFill + finish steps 6-8.
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    // start() throws as if the workflow was already started on a prior (interrupted) attempt.
    doThrow(
            new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.getDefaultInstance(),
                "PositionWorkflow",
                new RuntimeException("already started")))
        .when(stub)
        .start(any());

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, OCC);

    // Outcome is ADOPTED (not ALREADY_OWNED) — the retry recovers the dropped fill signal.
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);

    String expectedWfId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    assertThat(result.getWorkflowId()).isEqualTo(expectedWfId);

    // The dropped fill signal is re-sent (the whole point of the fix).
    ArgumentCaptor<Object> signalArgs = ArgumentCaptor.forClass(Object.class);
    verify(stub).signal(eq("onFill"), signalArgs.capture());
    FillSignalPayload fill = (FillSignalPayload) signalArgs.getValue();
    assertThat(fill.getFilledQty()).isEqualTo(5L);
    assertThat(fill.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
    assertThat(fill.getBrokerOrderId()).isEqualTo(BROKER_ORDER_ID);

    // Steps 6-8 still complete (all idempotent): terminalize, cache, audit.
    verify(exec).journalReconcileToFilled(eq(INTENT_KEY), eq(5L), any(BigDecimal.class), any());
    verify(positionLookup).cachePositionMapping(TENANT, STRATEGY, OCC, expectedWfId);

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getKind()).isEqualTo("PositionAdopted");
  }

  @Test
  void noJournalAnchor_refused_noStartNoSignal() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC)).thenReturn(List.of());
    when(exec.journalDumpOpen(TENANT, STRATEGY)).thenReturn(List.of());

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, OCC);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NO_ANCHOR);
    // No workflow start / no signal occurred — refusal is before any side effect.
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString(), any());
    verify(stub, never()).start(any());
    verify(stub, never()).signal(anyString(), any());
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void compactOperatorOcc_resolvesPaddedOwnerId_isNoop_noDuplicate() {
    // Issue #246: the operator supplies the broker/audit *compact* OCC, but the live owner's
    // PositionWorkflow was registered under the *padded* OccSymbol.of form (the journal anchor's
    // canonical option_symbol). The idempotency probe must run against the padded id rebuilt from
    // the anchor — not the raw compact operator input — or it misses the owner and double-adopts.
    String compactOcc = "UNH260618C00400000";
    BrokerPosition lot = brokerLot(5L, new BigDecimal("3.40"));
    lot.setOptionSymbol(compactOcc);
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, compactOcc)).thenReturn(lot);
    // Journal anchor carries the canonical PADDED option_symbol (OCC constant = padded form).
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, compactOcc))
        .thenReturn(List.of(filledJournalRow()));
    // The live owner is registered under the padded id; the probe must hit that id to find it.
    String paddedOwnerId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    when(positionLookup.isPositionWorkflowRunning(paddedOwnerId)).thenReturn(true);

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, compactOcc);

    // Probe was invoked with the PADDED workflow id rebuilt from the anchor, not the compact input.
    ArgumentCaptor<String> probeId = ArgumentCaptor.forClass(String.class);
    verify(positionLookup).isPositionWorkflowRunning(probeId.capture());
    assertThat(probeId.getValue()).isEqualTo(paddedOwnerId);

    // Live owner found -> no duplicate adoption.
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString(), any());
    verify(stub, never()).start(any());
    verify(stub, never()).signal(anyString(), any());
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void compactOperatorOcc_openRowFallback_resolvesPaddedAnchor_underPaddingMismatch() {
    // Issue #246: when there is no FILLED row, resolveAnchor falls back to an open
    // (RECORDED/SUBMITTED) row. The match must be padding-agnostic: a compact operator OCC must
    // still resolve an open row whose option_symbol is the padded form (and vice versa).
    String compactOcc = "UNH260618C00400000";
    BrokerPosition lot = brokerLot(5L, new BigDecimal("3.40"));
    lot.setOptionSymbol(compactOcc);
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, compactOcc)).thenReturn(lot);
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, compactOcc)).thenReturn(List.of());
    // Open row carries the PADDED option_symbol (OCC constant); operator supplied compact.
    when(exec.journalDumpOpen(TENANT, STRATEGY)).thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    AdoptionResult result = activities.adoptOrphanPosition(TENANT, STRATEGY, compactOcc);

    // Anchor resolved via the padding-agnostic open-row fallback -> adoption proceeds (not refused).
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);
    // Owner started under the canonical PADDED id rebuilt from the anchor.
    assertThat(result.getWorkflowId())
        .isEqualTo(WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID));
  }

  @Test
  void copytradeEodForceFlattenFalse_propagatesVerbatim_andTtlsFromConfig() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    activities.adoptOrphanPosition(TENANT, STRATEGY, OCC);

    ArgumentCaptor<Object> startArg = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(startArg.capture());
    PositionWorkflowInput posInput = (PositionWorkflowInput) startArg.getValue();

    // eod_force_flatten passed through verbatim — exactly false, never null, never defaulted.
    assertThat(posInput.getEodForceFlatten()).isEqualTo(Boolean.FALSE);
    // per-strategy TTLs sourced from config (pending_ttl_paper_secs=120), not left null.
    assertThat(posInput.getFirstFillTtlSecs()).isEqualTo(120L);
    assertThat(posInput.getExitFillTtlSecs()).isEqualTo(120L);
  }
}
