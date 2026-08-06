package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/**
 * PR #474: WorkflowReplayer-based coverage for the legacy ({@code adoptionVersion < 2}) branch of
 * {@link AdoptionWorkflowImpl#adopt}.
 *
 * <p>PR #474 added an {@code EntryFilled} cost-basis audit emission at the tail of {@code adopt()},
 * version-gated by bumping {@code Workflow.getVersion(VERSION_ADOPTION, DEFAULT_VERSION, 2)} and
 * emitting the new {@code audit.log(EntryFilled)} command ONLY at {@code adoptionVersion >= 2}. A
 * pre-fix (v1) in-flight adoption recorded its history with the {@code adoption-v1} marker at
 * version 1, so on replay {@code getVersion(...)} returns 1 and the new command must be skipped —
 * otherwise the extra audit command trips {@link io.temporal.worker.NonDeterministicException}
 * against the recorded history. {@link TestWorkflowEnvironment} always reports the MAX version for
 * fresh workflows, so the legacy branch is unreachable from a round-trip test — only {@link
 * WorkflowReplayer} against a recorded v1 history exercises it.
 *
 * <p>The fixture {@code adoption-pre-entryfilled-legacy-history.json} is synthesised by running a
 * {@link LegacyAdoptionEmulatorWorkflowImpl} that mirrors the pre-#474 {@code adopt()} command
 * sequence exactly but reads {@code getVersion("adoption-v1", DEFAULT_VERSION, 1)} (MAX 1, so the
 * recorded marker is version 1) and emits NO {@code EntryFilled} command. Replay against the
 * current (v2) {@link AdoptionWorkflowImpl} then proves the gate preserves determinism for legacy
 * histories. Regenerate with {@code -Dgenerate.legacy.fixture=true}.
 */
class AdoptionWorkflowImplLegacyReplayTest {

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/adoption-pre-entryfilled-legacy-history.json";
  private static final Path FIXTURE_SOURCE_PATH =
      Path.of("src/test/resources/temporal/replay/adoption-pre-entryfilled-legacy-history.json");

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String EXEC_QUEUE = "broker-alpaca-paper";
  private static final String LEGACY_EMULATOR_WORKFLOW_ID = "legacy-pre-entryfilled-adoption-emu";

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "op-1";
  private static final String OCC = "UNH   260618C00400000";
  private static final String SIGNAL_ID = "sig-abc";
  private static final String INTENT_KEY = "intent-abc";
  private static final String BROKER_ORDER_ID = "db5459fe";
  private static final OffsetDateTime FILLED_AT = OffsetDateTime.parse("2026-05-19T17:08:11Z");

  // Child-workflow recorder. Temporal's test env runs workers in-process; the spawned child
  // RecordingPositionWorkflowImpl publishes what it received here so the emulator can forward
  // onFill.
  static final Map<String, PositionWorkflowInput> STARTED = new ConcurrentHashMap<>();
  static final Map<String, FillSignalPayload> FILLS = new ConcurrentHashMap<>();

  /**
   * Pins the version-marker constant name so a rename in {@link AdoptionWorkflowImpl} fails this
   * test loudly. Mirrors {@code
   * CopytradeSignalWorkflowImplLegacyReplayTest#versionPreTradeDispatchConstantNameIsStable}.
   */
  @Test
  void versionAdoptionConstantNameIsStable() throws Exception {
    Field marker = AdoptionWorkflowImpl.class.getDeclaredField("VERSION_ADOPTION");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("adoption-v1");
  }

  /**
   * The main replay assertion: replays the pre-#474 (v1) adoption history against the current (v2)
   * {@link AdoptionWorkflowImpl} and verifies no {@code NonDeterministicWorkflowError}. The
   * recorded history carries the {@code adoption-v1} marker at version 1, so {@code
   * getVersion(VERSION_ADOPTION, DEFAULT_VERSION, 2)} resolves to 1 during replay and the {@code
   * adoptionVersion >= 2} branch (the new {@code audit.log(EntryFilled)} command) is correctly
   * skipped — byte-identical to the recorded command stream. This is the determinism guarantee the
   * reviewer flagged as untested.
   */
  @Test
  void legacyAdoptionHistoryReplaysAgainstCurrentImplWithoutNonDeterminism() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=AdoptionWorkflowImplLegacyReplayTest#regenerateAdoptionLegacyFixture`",
            FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, AdoptionWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  /**
   * One-shot fixture generator. Disabled by default; run via {@code -Dgenerate.legacy.fixture=true}
   * after a meaningful change to the legacy emulator (or if the fixture is missing). Mirrors {@link
   * AdoptionWorkflowImplTest}'s setup (core worker + broker-alpaca-paper worker, TenantStrategy/
   * ContractSymbol search attributes, RecordingPositionWorkflowImpl child). Writes the recorded
   * history JSON to {@link #FIXTURE_SOURCE_PATH} so the regenerated file is committed alongside
   * this test.
   *
   * <p>The {@link LegacyAdoptionEmulatorWorkflowImpl} reproduces the pre-#474 {@code adopt()}
   * command sequence at {@code getVersion("adoption-v1", DEFAULT, 1)} (MAX 1 → recorded marker
   * version 1) and emits NO {@code EntryFilled} command, so the recorded history has the {@code
   * adoption-v1} marker at version 1 and no trailing {@code audit.log(EntryFilled)} — exactly a
   * pre-fix in-flight history.
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateAdoptionLegacyFixture() throws Exception {
    STARTED.clear();
    FILLS.clear();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);

      ReconciliationExecActivity exec = Mockito.mock(ReconciliationExecActivity.class);
      StrategyActivities strategy = Mockito.mock(StrategyActivities.class);
      PositionLookupActivities positionLookup = Mockito.mock(PositionLookupActivities.class);
      AuditActivities audit = Mockito.mock(AuditActivities.class);

      when(strategy.get(TENANT, STRATEGY)).thenReturn(legacyConfig());
      when(exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC))
          .thenReturn(brokerLot(5L, new BigDecimal("3.40")));
      when(exec.journalListFilledByOcc(TENANT, STRATEGY, OCC))
          .thenReturn(List.of(filledJournalRow()));
      when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);
      when(exec.journalReconcileToFilled(eq(INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

      Worker coreWorker = env.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(
          LegacyAdoptionEmulatorWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
      coreWorker.registerActivitiesImplementations(strategy, positionLookup, audit);
      // Exec broker-truth lives on a DISTINCT queue, matching ExecActivitiesFactory.taskQueueFor.
      Worker brokerWorker = env.newWorker(EXEC_QUEUE);
      brokerWorker.registerActivitiesImplementations(exec);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      AdoptionWorkflow wf =
          client.newWorkflowStub(
              AdoptionWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(LEGACY_EMULATOR_WORKFLOW_ID)
                  .build());
      wf.adopt(input(OCC));

      json = client.fetchHistory(LEGACY_EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static AdoptionWorkflowInput input(String occ) {
    AdoptionWorkflowInput in = new AdoptionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(TENANT);
    in.setStrategyId(STRATEGY);
    in.setOcc(occ);
    in.setOperatorId(OPERATOR);
    return in;
  }

  private static BrokerPosition brokerLot(long qty, BigDecimal avgEntryPrice) {
    BrokerPosition p = new BrokerPosition();
    p.setSchemaVersion(1L);
    p.setOptionSymbol(OCC);
    p.setQty(qty);
    p.setSide(BrokerPosition.Side.LONG);
    p.setAvgEntryPrice(avgEntryPrice);
    return p;
  }

  private static JournalEntry filledJournalRow() {
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

  private static StrategyConfig legacyConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setEodForceFlatten(Boolean.FALSE);
    c.setPendingTtlPaperSecs(120L);
    return c;
  }

  /**
   * Emulator mirroring the pre-#474 {@link AdoptionWorkflowImpl#adopt} command sequence EXACTLY but
   * at the OLD version: it reads {@code Workflow.getVersion("adoption-v1", DEFAULT_VERSION, 1)}
   * (MAX 1, so the recorded {@code MarkerRecordedEvent} carries version 1) and does NOT emit the
   * v2-only {@code audit.log(EntryFilled)} command. Implements {@link AdoptionWorkflow} so the
   * recorded {@code workflowType.name} is {@code AdoptionWorkflow} — what {@link WorkflowReplayer}
   * expects when registering {@link AdoptionWorkflowImpl}. Uses the SAME production activity
   * interfaces ({@link ReconciliationExecActivity}, {@link StrategyActivities}, {@link
   * PositionLookupActivities}, {@link AuditActivities}) and child {@link PositionWorkflow} as the
   * impl, so every recorded {@code activityType.name}, task queue, and child-start command matches
   * what the current impl schedules on replay.
   *
   * <p>Command sequence (happy-path adoption — broker holds the lot, FILLED journal anchor, no live
   * owner) — must match {@link AdoptionWorkflowImpl#adopt} at {@code adoptionVersion == 1}:
   *
   * <ol>
   *   <li>getVersion(adoption-v1)=1 [MarkerRecordedEvent version 1]
   *   <li>{@code strategy.get} (Get)
   *   <li>{@code exec.brokerGetPositionByOcc} (BrokerGetPositionByOcc) — on broker-alpaca-paper
   *   <li>{@code exec.journalListFilledByOcc} (JournalListFilledByOcc) — resolveAnchor, FILLED
   *       non-empty so NO journalDumpOpen
   *   <li>{@code positionLookup.isPositionWorkflowRunning} (IsPositionWorkflowRunning)
   *   <li>start PositionWorkflow ABANDON child (Async.function child::run) +
   *       getWorkflowExecution.get + {@code child.onFill}
   *   <li>{@code exec.journalReconcileToFilled} (JournalReconcileToFilled)
   *   <li>{@code positionLookup.cachePositionMapping} (CachePositionMapping)
   *   <li>{@code audit.log(PositionAdopted)} (Log)
   * </ol>
   *
   * <p>The new EntryFilled command sits AFTER the PositionAdopted audit at v2; its absence from
   * this v1 sequence is the property under test.
   */
  public static final class LegacyAdoptionEmulatorWorkflowImpl implements AdoptionWorkflow {
    private static final ActivityOptions CORE_OPTIONS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
    private static final ActivityOptions EXEC_OPTIONS =
        ActivityOptions.newBuilder()
            .setTaskQueue(EXEC_QUEUE)
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build();

    private final AuditActivities audit =
        Workflow.newActivityStub(AuditActivities.class, CORE_OPTIONS);
    private final StrategyActivities strategy =
        Workflow.newActivityStub(StrategyActivities.class, CORE_OPTIONS);
    private final PositionLookupActivities positionLookup =
        Workflow.newActivityStub(PositionLookupActivities.class, CORE_OPTIONS);
    private final ReconciliationExecActivity exec =
        Workflow.newActivityStub(ReconciliationExecActivity.class, EXEC_OPTIONS);

    @Override
    public AdoptionResult adopt(AdoptionWorkflowInput in) {
      // PRE-#474 version anchor: MAX 1, so the recorded marker is version 1 and replay under the
      // current impl (MAX 2) resolves getVersion(adoption-v1, DEFAULT, 2) -> 1, skipping
      // EntryFilled.
      Workflow.getVersion(AdoptionWorkflowImpl.VERSION_ADOPTION, Workflow.DEFAULT_VERSION, 1);

      String tenantId = in.getTenantId();
      String strategyId = in.getStrategyId();
      String occ = in.getOcc();
      String operatorId = in.getOperatorId();

      StrategyConfig config = strategy.get(tenantId, strategyId);

      BrokerPosition brokerLot = exec.brokerGetPositionByOcc(tenantId, strategyId, occ);
      JournalEntry anchor = exec.journalListFilledByOcc(tenantId, strategyId, occ).get(0);
      String entrySignalId = anchor.getSignalId();
      String canonicalOcc = anchor.getOptionSymbol();
      String posWfId = WorkflowIds.position(tenantId, strategyId, canonicalOcc, entrySignalId);

      positionLookup.isPositionWorkflowRunning(posWfId);

      long qty = brokerLot.getQty();
      BigDecimal entryPremium = brokerLot.getAvgEntryPrice();
      OffsetDateTime filledAt =
          anchor.getSubmittedAt() != null ? anchor.getSubmittedAt() : workflowNow();

      PositionWorkflowInput posInput = new PositionWorkflowInput();
      posInput.setSchemaVersion(1L);
      posInput.setTenantId(tenantId);
      posInput.setStrategyId(strategyId);
      posInput.setEntrySignalId(entrySignalId);
      posInput.setContractSymbol(canonicalOcc);
      posInput.setQty(qty);
      posInput.setEntryPremium(entryPremium);
      if (config != null && config.getBrokerTarget() != null) {
        posInput.setBrokerTarget(
            PositionWorkflowInput.BrokerTarget.fromValue(config.getBrokerTarget().value()));
      }
      posInput.setEodForceFlatten(config != null ? config.getEodForceFlatten() : null);

      Map<String, Object> sa = new LinkedHashMap<>();
      sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenantId, strategyId));
      sa.put("ContractSymbol", canonicalOcc);
      ChildWorkflowOptions opts =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowId(posWfId)
              .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
              .setSearchAttributes(sa)
              .build();
      PositionWorkflow child = Workflow.newChildWorkflowStub(PositionWorkflow.class, opts);
      Async.function(child::run, posInput);
      Workflow.getWorkflowExecution(child).get();

      FillSignalPayload fill = new FillSignalPayload();
      fill.setBrokerOrderId(anchor.getBrokerOrderId());
      fill.setFilledQty(qty);
      fill.setAvgFillPrice(entryPremium);
      fill.setFilledAt(filledAt);
      child.onFill(fill);

      exec.journalReconcileToFilled(anchor.getIntentKey(), qty, entryPremium, filledAt);
      positionLookup.cachePositionMapping(tenantId, strategyId, canonicalOcc, posWfId);

      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("option_symbol", canonicalOcc);
      subject.put("entry_signal_id", entrySignalId);
      subject.put("intent_key", anchor.getIntentKey());
      subject.put("broker_order_id", anchor.getBrokerOrderId());
      subject.put("qty", qty);
      subject.put("entry_premium", entryPremium);
      subject.put("workflow_id", posWfId);
      subject.put("operator_id", operatorId);
      subject.put("eod_force_flatten", posInput.getEodForceFlatten());
      subject.put("evidence", "legacy pre-EntryFilled adoption emulator");
      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId(tenantId);
      event.setStrategyId(strategyId);
      event.setEventId(Workflow.randomUUID().toString());
      event.setOccurredAt(workflowNow());
      event.setKind("PositionAdopted");
      event.setSubject(subject);
      event.setActor("operator:" + operatorId);
      event.setWorkflowId(posWfId);
      event.setCorrelationId(entrySignalId);
      audit.log(event);

      // PRE-#474: NO EntryFilled emission here (the v2-only command). This is the gated command the
      // current impl must skip on replay of this v1 history.

      AdoptionResult result = new AdoptionResult();
      result.setSchemaVersion(1L);
      result.setOutcome(AdoptionResult.Outcome.ADOPTED);
      result.setWorkflowId(posWfId);
      result.setEntrySignalId(entrySignalId);
      result.setQty(qty);
      return result;
    }

    private static OffsetDateTime workflowNow() {
      return OffsetDateTime.ofInstant(
          Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
    }
  }

  /**
   * Light {@link PositionWorkflow} double registered on the core queue (mirrors {@code
   * AdoptionWorkflowImplTest.RecordingPositionWorkflowImpl}): records the start input + onFill
   * payload to the static maps, then parks on a never-completing await so the adopted owner stays
   * "running" like production. The adoption workflow does not wait on this child's result.
   */
  public static final class RecordingPositionWorkflowImpl implements PositionWorkflow {
    @Override
    public String run(PositionWorkflowInput input) {
      STARTED.put(Workflow.getInfo().getWorkflowId(), input);
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
  }
}
