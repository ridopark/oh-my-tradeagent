package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #239/#285 coverage for the operator-triggered orphan-adoption workflow. The exec activity
 * is registered on a SEPARATE {@code broker-alpaca-paper} worker, so a passing test proves the
 * adoption path routes broker-truth calls through the exec task queue (not the throwing in-process
 * placeholder). Covers: happy-path adoption, ALREADY_OWNED no-op, REFUSED_NOT_HELD, and
 * REFUSED_NO_ANCHOR.
 */
class AdoptionWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String EXEC_QUEUE = "broker-alpaca-paper";

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "op-1";
  private static final String OCC = "UNH   260618C00400000";
  private static final String SIGNAL_ID = "sig-abc";
  private static final String INTENT_KEY = "intent-abc";
  private static final String BROKER_ORDER_ID = "db5459fe";
  private static final OffsetDateTime FILLED_AT = OffsetDateTime.parse("2026-05-19T17:08:11Z");

  /**
   * Child-workflow recorder. Temporal's test env runs workers in-process (same JVM), so the spawned
   * child {@link RecordingPositionWorkflowImpl} can publish what it received here for assertions.
   */
  static final Map<String, PositionWorkflowInput> STARTED = new ConcurrentHashMap<>();

  static final Map<String, FillSignalPayload> FILLS = new ConcurrentHashMap<>();

  private TestWorkflowEnvironment env;
  private ReconciliationExecActivity exec;
  private StrategyActivities strategy;
  private PositionLookupActivities positionLookup;
  private AuditActivities audit;

  @BeforeEach
  void setUp() {
    STARTED.clear();
    FILLS.clear();
    env = TestWorkflowEnvironment.newInstance();
    // The adopted PositionWorkflow child is started with TenantStrategy/ContractSymbol custom SAs
    // (matching the production spawn) — register them or the in-memory test visibility store
    // rejects the child start with INVALID_ARGUMENT.
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    exec = mock(ReconciliationExecActivity.class);
    strategy = mock(StrategyActivities.class);
    positionLookup = mock(PositionLookupActivities.class);
    audit = mock(AuditActivities.class);

    // The adoption workflow resolves broker_target from strategy config FIRST (to build the
    // exec-queue stub before any broker-truth call), so every path needs a config. Per-test
    // overrides may re-stub eod_force_flatten etc.
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    // The adoption workflow under test + a light recording PositionWorkflow double (so a real
    // child start + onFill forward is exercised without pulling in market-data/exec deps).
    coreWorker.registerWorkflowImplementationTypes(
        AdoptionWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
    coreWorker.registerActivitiesImplementations(strategy, positionLookup, audit);

    // Exec broker-truth lives on a DISTINCT queue — the adoption path only reaches it by routing
    // through the exec task queue, which is the whole point of #285.
    Worker brokerWorker = env.newWorker(EXEC_QUEUE);
    brokerWorker.registerActivitiesImplementations(exec);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  private AdoptionResult runAdopt() {
    return runAdopt(OCC);
  }

  private AdoptionResult runAdopt(String occ) {
    return newAdoptionStub().adopt(input(occ));
  }

  private AdoptionWorkflow newAdoptionStub() {
    return env.getWorkflowClient()
        .newWorkflowStub(
            AdoptionWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  private AdoptionWorkflowInput input(String occ) {
    AdoptionWorkflowInput in = new AdoptionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(TENANT);
    in.setStrategyId(STRATEGY);
    in.setOcc(occ);
    in.setOperatorId(OPERATOR);
    return in;
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
    // Plan-2A R-AA-5: force_close_0dte_et (previously omitted from buildInput) + the
    // bounded-flatten
    // floors, so buildInput's regression test can assert they reach the adopted child input.
    c.setForceClose0dteEt("14:45");
    c.setExitFloorAbs(new BigDecimal("0.05"));
    c.setExitFloorPct(new BigDecimal("0.5"));
    c.setExpiryDayFloor(new BigDecimal("0.01"));
    return c;
  }

  @Test
  void happyPath_startsOwner_forwardsFill_terminalizesJournal_seedsCache_auditsProvenance() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    AdoptionResult result = runAdopt();

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);
    String expectedWfId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    assertThat(result.getWorkflowId()).isEqualTo(expectedWfId);
    assertThat(result.getQty()).isEqualTo(5L);
    assertThat(result.getEntrySignalId()).isEqualTo(SIGNAL_ID);

    // The PositionWorkflow child was started with broker-truth qty/premium under the canonical id.
    PositionWorkflowInput started = STARTED.get(expectedWfId);
    assertThat(started).isNotNull();
    assertThat(started.getTenantId()).isEqualTo(TENANT);
    assertThat(started.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(started.getEntrySignalId()).isEqualTo(SIGNAL_ID);
    assertThat(started.getContractSymbol()).isEqualTo(OCC);
    assertThat(started.getQty()).isEqualTo(5L);
    assertThat(started.getEntryPremium()).isEqualByComparingTo(new BigDecimal("3.40"));
    // eod_force_flatten propagated verbatim; TTLs sourced from config.
    assertThat(started.getEodForceFlatten()).isEqualTo(Boolean.FALSE);
    assertThat(started.getFirstFillTtlSecs()).isEqualTo(120L);
    // Plan-2A R-AA-5: buildInput now sets force_close_0dte_et (previously omitted — regression
    // guard) and carries the bounded-flatten floors onto the adopted child.
    assertThat(started.getForceClose0dteEt()).isEqualTo("14:45");
    assertThat(started.getExitFloorAbs()).isEqualByComparingTo("0.05");
    assertThat(started.getExitFloorPct()).isEqualByComparingTo("0.5");
    assertThat(started.getExpiryDayFloor()).isEqualByComparingTo("0.01");
    // Issue #288: the resolved broker target is threaded onto the adopted child input from
    // StrategyConfig.broker_target so PositionWorkflowImpl can stamp it on the exit OrderIntent
    // (its first exec.placeOrder is the STC — without it the lot would re-orphan).
    assertThat(started.getBrokerTarget())
        .isEqualTo(PositionWorkflowInput.BrokerTarget.ALPACA_PAPER);

    // onFill forwarded so the first-fill gate wakes.
    FillSignalPayload fill = FILLS.get(expectedWfId);
    assertThat(fill).isNotNull();
    assertThat(fill.getFilledQty()).isEqualTo(5L);
    assertThat(fill.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
    assertThat(fill.getBrokerOrderId()).isEqualTo(BROKER_ORDER_ID);

    // Journal terminalized through the exec queue + discovery cache seeded.
    verify(exec).journalReconcileToFilled(eq(INTENT_KEY), eq(5L), any(BigDecimal.class), any());
    verify(positionLookup).cachePositionMapping(TENANT, STRATEGY, OCC, expectedWfId);

    // PositionAdopted audit emitted with provenance (including the triggering operator).
    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(auditCaptor.capture());
    AuditEvent ev = firstOfKind(auditCaptor.getAllValues(), "PositionAdopted");
    assertThat(ev).isNotNull();
    assertThat(ev.getTenantId()).isEqualTo(TENANT);
    assertThat(ev.getStrategyId()).isEqualTo(STRATEGY);
    Map<String, Object> subject = ev.getSubject();
    assertThat(subject).containsEntry("option_symbol", OCC);
    assertThat(subject).containsEntry("entry_signal_id", SIGNAL_ID);
    assertThat(subject).containsEntry("intent_key", INTENT_KEY);
    assertThat(subject).containsEntry("broker_order_id", BROKER_ORDER_ID);
    assertThat(subject).containsEntry("workflow_id", expectedWfId);
    assertThat(subject).containsEntry("operator_id", OPERATOR);
    assertThat(subject).containsKey("qty");
    assertThat(subject).containsKey("entry_premium");
  }

  @Test
  void adopt_emitsEntryFilled_withCostBasisFields() {
    // Bug: the dashboard RealizedPnlCalculator builds per-contract cost basis ONLY from EntryFilled
    // audit rows (option_symbol, filled_qty, avg_fill_price). The reconciliation AdoptionWorkflow
    // never emitted EntryFilled, so an adopted-then-exited lot read as pure profit. Assert adoption
    // now emits an EntryFilled carrying the exact fields the calc reads.
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("7.84")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    AdoptionResult result = runAdopt();
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(auditCaptor.capture());
    AuditEvent entryFilled = firstOfKind(auditCaptor.getAllValues(), "EntryFilled");
    assertThat(entryFilled).as("adoption must emit an EntryFilled cost-basis row").isNotNull();
    assertThat(entryFilled.getTenantId()).isEqualTo(TENANT);
    assertThat(entryFilled.getStrategyId()).isEqualTo(STRATEGY);

    Map<String, Object> subject = entryFilled.getSubject();
    // These three keys are EXACTLY what RealizedPnlCalculator.fetchLots reads for cost basis:
    // option_symbol, filled_qty, avg_fill_price.
    assertThat(subject).containsEntry("option_symbol", OCC);
    // filled_qty / avg_fill_price round-trip through the audit-log activity boundary as JSON, so
    // Jackson deserializes them as Integer / Double; compare boxing-agnostically by numeric value
    // (the dashboard calc reads these from jsonb numerically, so the boxed type is irrelevant).
    assertThat(((Number) subject.get("filled_qty")).longValue()).isEqualTo(5L);
    assertThat(new BigDecimal(subject.get("avg_fill_price").toString()))
        .isEqualByComparingTo(new BigDecimal("7.84"));
    // Marker distinguishing an adoption-synthesized fill from a normal broker fill.
    assertThat(subject).containsEntry("recovery", "adopted");
  }

  private static AuditEvent firstOfKind(List<AuditEvent> events, String kind) {
    return events.stream().filter(e -> kind.equals(e.getKind())).findFirst().orElse(null);
  }

  @Test
  void alreadyOwned_noStart_noForward_noTerminalize_isNoop() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(filledJournalRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    AdoptionResult result = runAdopt();

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    assertThat(STARTED).isEmpty();
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void brokerDoesNotHold_refusedNotHeld_noSideEffects() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC)).thenReturn(null);

    AdoptionResult result = runAdopt();

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NOT_HELD);
    assertThat(STARTED).isEmpty();
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
    // Phantom guard: never even probes for a live owner.
    verify(positionLookup, never()).isPositionWorkflowRunning(anyString());
  }

  @Test
  void noJournalAnchor_refusedNoAnchor_noSideEffects() {
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC)).thenReturn(List.of());
    when(exec.journalDumpOpen(TENANT, STRATEGY)).thenReturn(List.of());

    AdoptionResult result = runAdopt();

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NO_ANCHOR);
    assertThat(STARTED).isEmpty();
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

    AdoptionResult result = runAdopt(compactOcc);

    // Probe was invoked with the PADDED workflow id rebuilt from the anchor, not the compact input.
    ArgumentCaptor<String> probeId = ArgumentCaptor.forClass(String.class);
    verify(positionLookup).isPositionWorkflowRunning(probeId.capture());
    assertThat(probeId.getValue()).isEqualTo(paddedOwnerId);

    // Live owner found -> no duplicate adoption.
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    assertThat(STARTED).isEmpty();
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

    AdoptionResult result = runAdopt(compactOcc);

    // Anchor resolved via the padding-agnostic open-row fallback -> adoption proceeds (not
    // refused).
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);
    String paddedWfId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    // Owner started under the canonical PADDED id rebuilt from the anchor.
    assertThat(result.getWorkflowId()).isEqualTo(paddedWfId);

    // Identity + discovery are keyed on the canonical PADDED OCC (not the compact operator input),
    // matching CopytradeSignalWorkflowImpl's spawn so the adopted owner is discoverable by the same
    // ContractSymbol Visibility query + Redis cache key the STC lookup uses.
    PositionWorkflowInput started = STARTED.get(paddedWfId);
    assertThat(started).isNotNull();
    assertThat(started.getContractSymbol()).isEqualTo(OCC);
    verify(positionLookup).cachePositionMapping(TENANT, STRATEGY, OCC, paddedWfId);
  }

  @Test
  void nullBrokerTarget_failsFastWithInvalidBrokerTargetError() {
    // Issue #285: broker_target is resolved from StrategyConfig FIRST so the exec-queue stub can be
    // built before any broker-truth call. When the config carries a null broker_target there is no
    // exec task queue to route to, so the workflow must fail fast with a non-retryable
    // InvalidBrokerTargetError (ExecActivitiesFactory.taskQueueFor) instead of NPEing or hanging on
    // a StartToCloseTimeout — and before any broker-truth side effect.
    StrategyConfig noTarget = config(Boolean.FALSE);
    noTarget.setBrokerTarget(null);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(noTarget);

    AdoptionWorkflow wf = newAdoptionStub();

    assertThatThrownBy(() -> wf.adopt(input(OCC)))
        .isInstanceOf(WorkflowFailedException.class)
        .hasCauseInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> {
              ApplicationFailure af = (ApplicationFailure) t.getCause();
              assertThat(af.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(af.isNonRetryable()).isTrue();
            });

    // Fast-fail happened before any broker-truth call / side effect.
    assertThat(STARTED).isEmpty();
    verify(exec, never()).brokerGetPositionByOcc(anyString(), anyString(), anyString());
    verify(exec, never()).journalReconcileToFilled(anyString(), anyLong(), any(), any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  /**
   * Light {@link PositionWorkflow} double registered on the core queue instead of the real {@code
   * PositionWorkflowImpl}: records the start input + onFill payload to the static maps, then parks
   * on a never-completing await so the adopted owner stays "running" like production. The adoption
   * workflow doesn't wait on this child's result, so parking is harmless.
   */
  public static final class RecordingPositionWorkflowImpl implements PositionWorkflow {
    @Override
    public String run(PositionWorkflowInput input) {
      STARTED.put(Workflow.getInfo().getWorkflowId(), input);
      // Park until an onFill arrives, then record it. The adoption workflow forwards onFill right
      // after the child is durably scheduled; it does not block on this child's completion.
      Workflow.await(() -> FILLS.containsKey(Workflow.getInfo().getWorkflowId()));
      return input.getEntrySignalId();
    }

    @Override
    public void partialExit(PartialExitRequest req) {}

    @Override
    public void onFill(FillSignalPayload event) {
      FILLS.put(Workflow.getInfo().getWorkflowId(), event);
    }

    @Override
    public void armChandelier(ArmChandelierPayload payload) {}

    @Override
    public void chandelierTick(PremiumTick tick) {}

    @Override
    public void riskBreach(RiskBreachPayload payload) {}

    @Override
    public TrailingState trailingState() {
      return null;
    }

    @Override
    public PositionState positionState() {
      return null;
    }

    @Override
    public ExitProximityView exitProximity() {
      return null;
    }

    @Override
    public void forceCloseValidator(ForceCloseRequest request) {}

    @Override
    public ForceCloseResult forceClose(ForceCloseRequest request) {
      return null;
    }
  }
}
