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
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
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
import java.time.LocalDate;
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

  /**
   * Bound on how long {@link #awaitEntry} waits for the asynchronously-started child to publish.
   *
   * <p>Generous because it only elapses in the failure case: when the child IS scheduled promptly
   * (the norm) the first read succeeds and nothing sleeps. Kept well under the module's 60s
   * per-test timeout so a child that genuinely never starts still fails as a named assertion here
   * rather than as an anonymous timeout.
   */
  private static final long ASYNC_CHILD_AWAIT_MS = 5_000L;

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

  private JournalEntry lastSellRow() {
    JournalEntry e = filledJournalRow();
    e.setIntentKey("t-dev/s-x/pos/occ/sig:exit:stc-sig-9");
    e.setSignalId("stc-sig-9");
    e.setBrokerOrderId("sell-boid-9");
    e.setSide(JournalEntry.Side.SELL);
    return e;
  }

  private JournalEntry entryBuyRow() {
    JournalEntry e = filledJournalRow();
    e.setSide(JournalEntry.Side.BUY);
    return e;
  }

  @Test
  void afterAPartialExit_adoptionAnchorsOnTheEntryBuy_notTheLastSell() {
    // #930: recon's latest-fill row is the last SELL; adopting on it keyed the new owner to the
    // STC's signal id and the SELL's broker order id. The entry BUY is the anchor.
    when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(brokerLot(3L, new BigDecimal("2.00")));
    when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
        .thenReturn(List.of(lastSellRow(), entryBuyRow()));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config(Boolean.FALSE));
    when(exec.journalReconcileToFilled(anyString(), anyLong(), any(), any())).thenReturn(true);

    AdoptionResult result = runAdopt();

    assertThat(result.getEntrySignalId()).isEqualTo(SIGNAL_ID);
    String expectedWfId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    assertThat(result.getWorkflowId()).isEqualTo(expectedWfId);
    assertThat(awaitEntry(FILLS, expectedWfId).getBrokerOrderId()).isEqualTo(BROKER_ORDER_ID);
    verify(exec).journalReconcileToFilled(eq(INTENT_KEY), eq(3L), any(BigDecimal.class), any());
    verify(exec, never())
        .journalReconcileToFilled(
            eq("t-dev/s-x/pos/occ/sig:exit:stc-sig-9"), anyLong(), any(), any());
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
    PositionWorkflowInput started = awaitEntry(STARTED, expectedWfId);
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
    FillSignalPayload fill = awaitEntry(FILLS, expectedWfId);
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
    // Exactly one EntryFilled — a second would double-count cost basis in RealizedPnlCalculator
    // (which sums every EntryFilled row for the contract), turning the offset into a phantom loss.
    long entryFilledCount =
        auditCaptor.getAllValues().stream().filter(e -> "EntryFilled".equals(e.getKind())).count();
    assertThat(entryFilledCount)
        .as("adoption must emit EXACTLY ONE EntryFilled cost-basis row")
        .isEqualTo(1L);
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

  /**
   * Reads an entry published by the adopted child workflow, waiting for it to appear.
   *
   * <p>The child is started fire-and-forget — {@code AdoptionWorkflowImpl} calls {@code
   * Async.function(child::run, posInput)} and never awaits it, by design. So {@code adopt()}
   * returning establishes NO happens-before with the child's first workflow task, which is what
   * writes {@link #STARTED} (and, after the forwarded signal, {@link #FILLS}). Reading the map
   * immediately is therefore a race that the test usually wins and occasionally does not: it lost
   * twice under CPU contention on 2026-08-17, failing at ~0.1s with "Expecting actual not to be
   * null" — once here and once in {@code compactOperatorOcc_openRowFallback_…}, the only two tests
   * that read these maps. See #723.
   *
   * <p>Returns whatever the final read yields, including {@code null}, so the caller's {@code
   * isNotNull()} stays the thing that reports the failure. A child that never starts is a real
   * regression and must still fail — this only removes the spurious loss of the race.
   */
  private static <V> V awaitEntry(Map<String, V> map, String workflowId) {
    long deadline = System.currentTimeMillis() + ASYNC_CHILD_AWAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      V value = map.get(workflowId);
      if (value != null) {
        return value;
      }
      try {
        Thread.sleep(10L);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return map.get(workflowId);
  }

  @Test
  void awaitEntry_returnsAnEntryPublishedAfterTheFirstReadWouldHaveLost() throws Exception {
    // Pins the mechanism the two intermittent failures in this class needed (#723). The adopted
    // child is started fire-and-forget, so the map is empty at the instant adopt() returns and only
    // fills in once the child's first workflow task runs. This reproduces that ordering
    // DETERMINISTICALLY — publish strictly after a bare read has already missed — so the helper is
    // demonstrated to wait rather than assumed to. Setting ASYNC_CHILD_AWAIT_MS to 0 fails it.
    Map<String, String> published = new ConcurrentHashMap<>();
    String workflowId = "wf-published-late";

    assertThat(published.get(workflowId))
        .as("a bare read at this instant is exactly what lost the race in CI")
        .isNull();

    Thread publisher =
        new Thread(
            () -> {
              try {
                Thread.sleep(150L);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
              }
              published.put(workflowId, "child-started");
            });
    publisher.start();
    try {
      assertThat(awaitEntry(published, workflowId)).isEqualTo("child-started");
    } finally {
      publisher.join(ASYNC_CHILD_AWAIT_MS);
    }
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

  // Unrecorded adoption refuses an expired contract against the real clock the test env runs on,
  // so these tests use a far-future contract rather than OCC (already past).
  private static final String LIVE_OCC = "UNH   301220C00400000";
  private static final String LIVE_COMPACT = "UNH301220C00400000";

  private AdoptionResult runAdoptUnrecorded(String occ) {
    AdoptionWorkflowInput in = input(occ);
    in.setAllowUnrecorded(true);
    return newAdoptionStub().adopt(in);
  }

  private void stubUnrecordedLot(BigDecimal avgEntryPrice) {
    BrokerPosition lot = brokerLot(10L, avgEntryPrice);
    lot.setOptionSymbol(LIVE_COMPACT);
    when(exec.brokerGetPositionByOcc(eq(TENANT), eq(STRATEGY), anyString())).thenReturn(lot);
    when(exec.journalListFilledByOcc(eq(TENANT), eq(STRATEGY), anyString())).thenReturn(List.of());
    when(exec.journalDumpOpen(TENANT, STRATEGY)).thenReturn(List.of());
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
  }

  @Test
  void unrecorded_operatorAdopt_startsOwnerFromBrokerTruth_withoutTouchingTheJournal() {
    // A lot bought directly at the broker: no journal row. The operator's Adopt (allow_unrecorded)
    // still takes ownership, keyed on the padded OCC and a synthesized signal id.
    StrategyConfig cfg = config(Boolean.FALSE);
    cfg.setBrokerAccountId("acct-1");
    when(strategy.get(TENANT, STRATEGY)).thenReturn(cfg);
    stubUnrecordedLot(new BigDecimal("1.25"));

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);
    assertThat(result.getEntrySignalId()).startsWith("adopt:");
    String expectedWfId =
        WorkflowIds.position(TENANT, STRATEGY, LIVE_OCC, result.getEntrySignalId());
    assertThat(result.getWorkflowId()).isEqualTo(expectedWfId);
    PositionWorkflowInput started = awaitEntry(STARTED, expectedWfId);
    assertThat(started.getContractSymbol()).isEqualTo(LIVE_OCC);
    assertThat(started.getQty()).isEqualTo(10L);
    assertThat(started.getEntryPremium()).isEqualByComparingTo("1.25");
    assertThat(started.getEodForceFlatten()).isEqualTo(Boolean.FALSE);
    FillSignalPayload fill = awaitEntry(FILLS, expectedWfId);
    assertThat(fill.getFilledQty()).isEqualTo(10L);
    assertThat(fill.getBrokerOrderId()).isEqualTo(result.getEntrySignalId());
    verify(positionLookup).hasRunningOwnerForOcc(TENANT, LIVE_OCC);
    verify(positionLookup).hasRunningOwnerForOccOnAccount("acct-1", LIVE_OCC);
    verify(exec, never()).journalReconcileToFilled(any(), anyLong(), any(), any());
    verify(positionLookup).cachePositionMapping(TENANT, STRATEGY, LIVE_OCC, expectedWfId);

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(auditCaptor.capture());
    Map<String, Object> adopted =
        firstOfKind(auditCaptor.getAllValues(), "PositionAdopted").getSubject();
    assertThat(adopted).containsEntry("unrecorded", true).containsEntry("broker_order_id", null);
    Map<String, Object> entry = firstOfKind(auditCaptor.getAllValues(), "EntryFilled").getSubject();
    assertThat(entry)
        .containsEntry("option_symbol", LIVE_OCC)
        .containsEntry("recovery", "adopted_unrecorded");
    assertThat(((Number) entry.get("filled_qty")).longValue()).isEqualTo(10L);
  }

  @Test
  void unrecorded_withoutTheOperatorFlag_isStillRefused() {
    // Recon's auto-adoption never sets allow_unrecorded: an unrecorded lot only pages.
    stubUnrecordedLot(new BigDecimal("1.25"));

    AdoptionResult result = runAdopt(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NO_ANCHOR);
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_ownedByAnotherSignalForThisTenant_isAlreadyOwned() {
    // A second Adopt press, or a copytrade owner under its own signal id: the fresh synthesized id
    // can't collide with it, so only the by-OCC probe stops a second owner.
    stubUnrecordedLot(new BigDecimal("1.25"));
    when(positionLookup.hasRunningOwnerForOcc(TENANT, LIVE_OCC)).thenReturn(true);

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    assertThat(STARTED).isEmpty();
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void unrecorded_secondPress_findsTheCachedOwner_evenWhenVisibilityMissesIt() {
    // Visibility lags a just-started owner (#960) and reads false on an outage; the pos:* cache
    // the first adoption wrote synchronously is what stops the second press.
    stubUnrecordedLot(new BigDecimal("1.25"));
    String first = WorkflowIds.position(TENANT, STRATEGY, LIVE_OCC, "adopt:run-1");
    when(positionLookup.findPositionWorkflowId(TENANT, STRATEGY, LIVE_OCC)).thenReturn(first);
    when(positionLookup.isPositionWorkflowRunning(first)).thenReturn(true);

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_ownedByASiblingTenantOnTheSameAccount_isAlreadyOwned() {
    StrategyConfig cfg = config(Boolean.FALSE);
    cfg.setBrokerAccountId(" acct-1 ");
    when(strategy.get(TENANT, STRATEGY)).thenReturn(cfg);
    stubUnrecordedLot(new BigDecimal("1.25"));
    when(positionLookup.hasRunningOwnerForOccOnAccount("acct-1", LIVE_OCC)).thenReturn(true);

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ALREADY_OWNED);
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_condorLeg_isRefusedUnsafe() {
    stubUnrecordedLot(new BigDecimal("1.25"));
    when(positionLookup.isCondorLegOcc(TENANT, LIVE_OCC)).thenReturn(true);

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_UNSAFE);
    assertThat(result.getReason()).isEqualTo("condor_leg");
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_runningCondorHold_isRefusedUnsafe() {
    stubUnrecordedLot(new BigDecimal("1.25"));
    when(positionLookup.hasRunningCondorOwnerForOcc(TENANT, LIVE_OCC)).thenReturn(true);

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getReason()).isEqualTo("condor_leg");
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_withASellAlreadyWorkingAtTheBroker_isRefusedUnsafe() {
    // The tenant's own resting sell on Alpaca: an owner's exit would sell the lot twice.
    stubUnrecordedLot(new BigDecimal("1.25"));
    BrokerOpenOrder buyElsewhere = new BrokerOpenOrder();
    buyElsewhere.setOptionSymbol(LIVE_COMPACT);
    buyElsewhere.setSide(BrokerOpenOrder.Side.BUY);
    BrokerOpenOrder sell = new BrokerOpenOrder();
    sell.setOptionSymbol(LIVE_COMPACT);
    sell.setSide(BrokerOpenOrder.Side.SELL);
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of(buyElsewhere, sell));

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getReason()).isEqualTo("open_sell_order");
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_openBuyOnly_doesNotBlock() {
    stubUnrecordedLot(new BigDecimal("1.25"));
    BrokerOpenOrder buy = new BrokerOpenOrder();
    buy.setOptionSymbol(LIVE_COMPACT);
    buy.setSide(BrokerOpenOrder.Side.BUY);
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of(buy));

    assertThat(runAdoptUnrecorded(LIVE_COMPACT).getOutcome())
        .isEqualTo(AdoptionResult.Outcome.ADOPTED);
  }

  @Test
  void unrecorded_pastItsExpiryClose_isRefusedUnsafe() {
    // The test env clock starts at real now; an OCC that expired yesterday is past its close.
    LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("America/New_York")).minusDays(1);
    String expired =
        "UNH"
            + yesterday.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
            + "C00400000";
    BrokerPosition lot = brokerLot(10L, new BigDecimal("1.25"));
    lot.setOptionSymbol(expired);
    when(exec.brokerGetPositionByOcc(eq(TENANT), eq(STRATEGY), anyString())).thenReturn(lot);
    when(exec.journalListFilledByOcc(eq(TENANT), eq(STRATEGY), anyString())).thenReturn(List.of());
    when(exec.journalDumpOpen(TENANT, STRATEGY)).thenReturn(List.of());

    AdoptionResult result = runAdoptUnrecorded(expired);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_UNSAFE);
    assertThat(result.getReason()).isEqualTo("expired");
    assertThat(STARTED).isEmpty();
  }

  @Test
  void unrecorded_withoutABrokerCostBasis_isRefused() {
    stubUnrecordedLot(null);

    AdoptionResult result = runAdoptUnrecorded(LIVE_COMPACT);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NO_ANCHOR);
    assertThat(STARTED).isEmpty();
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
    PositionWorkflowInput started = awaitEntry(STARTED, paddedWfId);
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
    public void supersede(String correctedSignalId, String correctedOcc) {}

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

    @Override
    public void partialCloseValidator(PartialCloseRequest request) {}

    @Override
    public PartialCloseResult partialClose(PartialCloseRequest request) {
      return null;
    }

    // PLAN-2026-08-16 arm_trail: test double only — never invoked by these suites. Returning null
    // is the same convention the sibling Updates above use.
    @Override
    public void armTrailValidator(ArmTrailRequest request) {}

    @Override
    public ArmTrailResult armTrail(ArmTrailRequest request) {
      return null;
    }

    @Override
    public void correctBookedLotValidator(
        com.ohmytradeagent.contract.CorrectBookedLotRequest request) {}

    @Override
    public com.ohmytradeagent.contract.CorrectBookedLotResult correctBookedLot(
        com.ohmytradeagent.contract.CorrectBookedLotRequest request) {
      return null;
    }

    @Override
    public void disarmTrailValidator(com.ohmytradeagent.contract.DisarmTrailRequest request) {}

    @Override
    public com.ohmytradeagent.contract.DisarmTrailResult disarmTrail(
        com.ohmytradeagent.contract.DisarmTrailRequest request) {
      return null;
    }
  }
}
