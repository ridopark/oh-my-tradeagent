package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/**
 * Issue #150: WorkflowReplayer-based coverage for the legacy {@code v == DEFAULT_VERSION} branch of
 * {@link CopytradeSignalWorkflowImpl#handleBto}.
 *
 * <p>PR #111 introduced a 3-activity pre-trade dispatch ({@code assertPreTradeCheckRoutable} →
 * {@code dispatchPreTradeCheck} → {@code checkEntry(payload, config, preTradeResult)}); PR #149
 * fenced that dispatch with {@code Workflow.getVersion(VERSION_PRE_TRADE_DISPATCH, DEFAULT, 1)} so
 * pre-#111 in-flight workflows replay deterministically through the legacy single-{@code
 * checkEntry(payload, config, null)} branch. {@link TestWorkflowEnvironment} always reports {@code
 * getVersion(...) == 1} for fresh workflows, so the legacy branch is unreachable from a round-trip
 * test — only {@link WorkflowReplayer} against a recorded pre-#111 history exercises it.
 *
 * <p>The fixture {@code copytrade-signal-pre-111-legacy-history.json} is synthesised by running a
 * stripped-down {@link LegacyHandleBtoEmulatorWorkflowImpl} that mirrors the pre-#111 activity
 * sequence (audit → strategy.get → 2-arg CheckEntry → audit reject) under {@link
 * TestWorkflowEnvironment}, then capturing the resulting history via {@link
 * WorkflowClient#fetchHistory(String)}. Regenerate with {@code -Dgenerate.legacy.fixture=true}.
 *
 * <p>Replay against the current {@link CopytradeSignalWorkflowImpl} verifies:
 *
 * <ul>
 *   <li><b>(criterion 2)</b> No {@code NonDeterministicWorkflowError} — the {@code
 *       VERSION_PRE_TRADE_DISPATCH} gate preserves determinism for legacy histories.
 *   <li><b>(criterion 3)</b> The legacy branch invokes {@code risk.checkEntry(payload, config,
 *       null)} — the fixture's recorded {@code CheckEntry} ActivityTaskScheduled payload encodes
 *       the 2-arg pre-#111 input shape; the current impl's 3-arg call with a {@code null} third arg
 *       must be tolerated by the SDK's activity-input comparison during replay.
 *   <li><b>(criterion 4)</b> The {@link CopytradeSignalWorkflowImpl#VERSION_PRE_TRADE_DISPATCH}
 *       constant name is reflectively asserted — renaming it breaks this test.
 *   <li><b>(criterion 5)</b> If the legacy branch's signature drifts — i.e. the {@code
 *       risk.checkEntry(...)} call is removed, reordered, or replaced with a different activity —
 *       {@link WorkflowReplayer} surfaces {@link io.temporal.worker.NonDeterministicException}
 *       because the scheduled activity command no longer matches the recorded history at the
 *       checkEntry event. Verified during plan execution by temporarily replacing the legacy branch
 *       with {@code decision = RiskDecision.approved()}: the next scheduled command ({@code
 *       Resolve}) failed against the recorded reject-path event ({@code Log}), producing {@code
 *       [TMPRL1100] Failure handling event ... activityType ... 'Resolve' vs 'Log'}. The coupling
 *       is implicit through the recorded history; do not weaken it.
 *   <li><b>Caveat:</b> the SDK does NOT raise NonDeterministicException on a pure activity-input
 *       payload-count difference (3-arg-with-null vs recorded-2-arg) — that tolerance is the very
 *       property the legacy version-gate relies on, and the property this test pins.
 * </ul>
 */
class CopytradeSignalWorkflowImplLegacyReplayTest {

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/copytrade-signal-pre-111-legacy-history.json";
  private static final Path FIXTURE_SOURCE_PATH =
      Path.of("src/test/resources/temporal/replay/copytrade-signal-pre-111-legacy-history.json");

  // Issue #279: legacy (v=DEFAULT_VERSION) breach-abort command-order fixture. Pins the pre-#274
  // sequence Log(SignalAbortedByRiskBreach) -> CancelOrder -> return so a future edit can't
  // silently
  // break replay determinism for in-flight pre-#274 workflows that already took the breach-abort
  // branch.
  private static final String BREACH_ABORT_FIXTURE_RESOURCE =
      "temporal/replay/copytrade-signal-pre-274-breach-abort-legacy-history.json";
  private static final Path BREACH_ABORT_FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "copytrade-signal-pre-274-breach-abort-legacy-history.json");

  // Issue #279: legacy (v=DEFAULT_VERSION) TTL-expiry command-order fixture. Pins the pre-#165
  // phase
  // 2 sequence Log(OrderCancelRequested) -> CancelOrder -> Log(OrderCancelled) -> Log(EntryExpired)
  // so the VERSION_TTL_FILLED_ADOPTION gate's legacy branch stays replay-deterministic for
  // in-flight
  // pre-#165 workflows.
  private static final String TTL_EXPIRY_FIXTURE_RESOURCE =
      "temporal/replay/copytrade-signal-pre-165-ttl-expiry-legacy-history.json";
  private static final Path TTL_EXPIRY_FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "copytrade-signal-pre-165-ttl-expiry-legacy-history.json");

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String LEGACY_EMULATOR_WORKFLOW_ID = "legacy-pre-111-emulator";
  private static final String BREACH_ABORT_EMULATOR_WORKFLOW_ID = "legacy-pre-274-breach-emulator";
  private static final String TTL_EXPIRY_EMULATOR_WORKFLOW_ID = "legacy-pre-165-ttl-emulator";

  /**
   * Pins the version-marker constant name so a rename in {@link CopytradeSignalWorkflowImpl} fails
   * this test loudly. Mirrors the pattern at line 309 of {@code
   * CopytradeSignalWorkflowImplPreTradeDispatchTest}.
   */
  @Test
  void versionPreTradeDispatchConstantNameIsStable() throws Exception {
    Field marker = CopytradeSignalWorkflowImpl.class.getDeclaredField("VERSION_PRE_TRADE_DISPATCH");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("pre-trade-dispatch-v2");
  }

  /**
   * The main replay assertion: replays the pre-#111 history against the current impl and verifies
   * no {@code NonDeterministicWorkflowError}. The SDK's deterministic replay engine walks the
   * recorded events, calls into the current workflow code, and compares scheduled activity commands
   * to recorded {@code ActivityTaskScheduled} events. The legacy branch's {@code
   * risk.checkEntry(payload, config, null)} call must be tolerated by this comparison against the
   * recorded 2-arg input bytes.
   */
  @Test
  void legacyPre111HistoryReplaysAgainstCurrentImplWithoutNonDeterminism() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=CopytradeSignalWorkflowImplLegacyReplayTest#regenerateLegacyFixture`",
            FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, CopytradeSignalWorkflowImpl.class);
  }

  /**
   * Issue #279: replays the pre-#274 breach-abort history against the current impl. The recorded
   * history carries NO {@code breach-filled-adoption-v1} marker, so {@code getVersion(...)} returns
   * {@code DEFAULT_VERSION} during replay and the current impl takes the legacy branch (lines
   * 295-304 of {@link CopytradeSignalWorkflowImpl}): {@code auditRiskBreachAbort} (Log) -> {@code
   * cancelOrder} (CancelOrder) -> return. A future edit that reorders those commands, drops the
   * cancel, or moves the cancel before the audit would trip {@link
   * io.temporal.worker.NonDeterministicException} against the recorded sequence. Pins the in-flight
   * pre-#274 replay determinism the issue calls out.
   */
  @Test
  void legacyPre274BreachAbortHistoryReplaysAgainstCurrentImplWithoutNonDeterminism()
      throws Exception {
    assertThat(getClass().getClassLoader().getResource(BREACH_ABORT_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=CopytradeSignalWorkflowImplLegacyReplayTest#regenerateBreachAbortFixture`",
            BREACH_ABORT_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        BREACH_ABORT_FIXTURE_RESOURCE, CopytradeSignalWorkflowImpl.class);
  }

  /**
   * Issue #279: replays the pre-#165 TTL-expiry history against the current impl. The recorded
   * history carries NO {@code ttl-filled-adoption-v1} marker, so {@code getVersion(...)} returns
   * {@code DEFAULT_VERSION} during replay and the current impl skips the FILLED-adoption branch
   * (lines 579-584 of {@link CopytradeSignalWorkflowImpl}) and takes the legacy CANCELLED path:
   * {@code Log(OrderCancelRequested)} -> {@code cancelOrder} (CancelOrder) -> {@code
   * Log(OrderCancelled)} -> {@code Log(EntryExpired)}. Pins the in-flight pre-#165 replay
   * determinism uniformly with the breach-abort gate.
   */
  @Test
  void legacyPre165TtlExpiryHistoryReplaysAgainstCurrentImplWithoutNonDeterminism()
      throws Exception {
    assertThat(getClass().getClassLoader().getResource(TTL_EXPIRY_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=CopytradeSignalWorkflowImplLegacyReplayTest#regenerateTtlExpiryFixture`",
            TTL_EXPIRY_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        TTL_EXPIRY_FIXTURE_RESOURCE, CopytradeSignalWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  /**
   * One-shot fixture generator. Disabled by default; run via {@code -Dgenerate.legacy.fixture=true}
   * after a meaningful change to the legacy emulator (or if the fixture is missing). Writes the
   * recorded history JSON to {@link #FIXTURE_SOURCE_PATH} so the regenerated file is committed
   * alongside this test.
   *
   * <p>The emulator workflow mirrors pre-#111 {@link CopytradeSignalWorkflowImpl#handleBto}'s
   * reject path exactly: {@code audit.log(SignalReceived)} → {@code strategy.get} → 2-arg {@code
   * CheckEntry} returning {@code allowed=false} → {@code audit.log(SignalRejected)}. No
   * pre-trade-dispatch-v2 marker exists in the emulator, so the recorded history has no {@code
   * MarkerRecordedEvent} for that change-id — replays through the current impl take the {@code v ==
   * DEFAULT_VERSION} branch deterministically.
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateLegacyFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyHandleBtoEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      StrategyActivities strategy = Mockito.mock(StrategyActivities.class);
      LegacyRiskActivities legacyRisk = Mockito.mock(LegacyRiskActivities.class);

      StrategyConfig cfg = legacyStrategyConfig();
      when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
      when(legacyRisk.checkEntry(any(), any()))
          .thenReturn(
              RiskDecision.rejected(RejectionReason.AUTHOR_NOT_WHITELISTED, "legacy_test_reject"));

      worker.registerActivitiesImplementations(audit, strategy, legacyRisk);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      CopytradeSignalWorkflow wf =
          client.newWorkflowStub(
              CopytradeSignalWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(LEGACY_EMULATOR_WORKFLOW_ID)
                  .build());
      wf.process(btoPayload());

      json = client.fetchHistory(LEGACY_EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  /**
   * Issue #279: one-shot generator for the pre-#274 breach-abort fixture. Disabled by default; run
   * via {@code -Dgenerate.legacy.fixture=true}. The {@link LegacyBreachAbortEmulatorWorkflowImpl}
   * mirrors the pre-#274 v=DEFAULT_VERSION breach-abort command order exactly: {@code
   * Log(SignalReceived)} -> {@code strategy.get} -> 2-arg {@code CheckEntry} (approved) -> {@code
   * contract.resolve} -> {@code capitalForStrategy} -> {@code Log(SignalAccepted)} -> {@code
   * placeOrder} -> {@code Log(OrderSubmitted)} -> [await wakes on riskBreach signal] -> {@code
   * Log(SignalAbortedByRiskBreach)} -> {@code cancelOrder} -> return. No {@code
   * breach-filled-adoption-v1} marker is recorded, so replays through the current impl take the
   * legacy branch.
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateBreachAbortFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyBreachAbortEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      StrategyActivities strategy = Mockito.mock(StrategyActivities.class);
      LegacyRiskActivities legacyRisk = Mockito.mock(LegacyRiskActivities.class);
      LegacyExecActivities legacyExec = Mockito.mock(LegacyExecActivities.class);
      LegacyContractActivities legacyContract = Mockito.mock(LegacyContractActivities.class);

      StrategyConfig cfg = legacyStrategyConfig();
      cfg.setPendingTtlPaperSecs(120L); // generous TTL so the breach signal lands before expiry
      when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
      when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
      when(legacyRisk.checkEntry(any(), any())).thenReturn(RiskDecision.approved());
      when(legacyContract.resolve(any())).thenReturn(LEGACY_OCC);
      when(legacyExec.placeOrder(any())).thenReturn(submitted("intent-K", "brk-1"));
      when(legacyExec.cancelOrder(any())).thenReturn(cancelled("intent-K", "brk-1"));

      worker.registerActivitiesImplementations(audit, strategy, legacyRisk, legacyContract);
      // Exec activities are pinned to broker-alpaca-paper (matching ExecActivitiesFactory) —
      // register
      // them on a dedicated worker for that queue, otherwise placeOrder blocks forever.
      Worker brokerWorker = env.newWorker("broker-alpaca-paper");
      brokerWorker.registerActivitiesImplementations(legacyExec);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      CopytradeSignalWorkflow wf =
          client.newWorkflowStub(
              CopytradeSignalWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(BREACH_ABORT_EMULATOR_WORKFLOW_ID)
                  .build());
      io.temporal.client.WorkflowStub.fromTyped(wf).start(btoPayload());

      // Wait until placeOrder ran (workflow now sitting on the bounded fill-await with a live
      // START_TIMER) then deliver the breach with no preceding onFill — exactly the live race the
      // legacy branch handles. Signalling after placeOrder ensures the riskBreach (not the skipped
      // TTL timer) wakes the await, so the recorded post-await sequence is the breach-abort branch.
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline) {
        try {
          Mockito.verify(legacyExec, Mockito.atLeastOnce()).placeOrder(any());
          break;
        } catch (AssertionError ignored) {
          Thread.sleep(50);
        }
      }
      wf.riskBreach(riskBreachPayload());
      io.temporal.client.WorkflowStub.fromTyped(wf).getResult(String.class);

      json = client.fetchHistory(BREACH_ABORT_EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(BREACH_ABORT_FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(BREACH_ABORT_FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  /**
   * Issue #279: one-shot generator for the pre-#165 TTL-expiry fixture. Disabled by default; run
   * via {@code -Dgenerate.legacy.fixture=true}. The {@link LegacyTtlExpiryEmulatorWorkflowImpl}
   * mirrors the pre-#165 phase 2 v=DEFAULT_VERSION TTL-expiry command order on a CANCELLED cancel
   * result: the entry sequence above, then [TTL await times out with no fill] -> {@code
   * Log(OrderCancelRequested)} -> {@code cancelOrder} (returns CANCELLED) -> {@code
   * Log(OrderCancelled)} -> {@code Log(EntryExpired)} -> return. No {@code ttl-filled-adoption-v1}
   * marker is recorded, so replays through the current impl skip the FILLED-adoption branch and
   * take the legacy CANCELLED path.
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateTtlExpiryFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyTtlExpiryEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      StrategyActivities strategy = Mockito.mock(StrategyActivities.class);
      LegacyRiskActivities legacyRisk = Mockito.mock(LegacyRiskActivities.class);
      LegacyExecActivities legacyExec = Mockito.mock(LegacyExecActivities.class);
      LegacyContractActivities legacyContract = Mockito.mock(LegacyContractActivities.class);

      StrategyConfig cfg = legacyStrategyConfig();
      cfg.setPendingTtlPaperSecs(1L); // short TTL so the await expires quickly under test time-skip
      when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
      when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
      when(legacyRisk.checkEntry(any(), any())).thenReturn(RiskDecision.approved());
      when(legacyContract.resolve(any())).thenReturn(LEGACY_OCC);
      when(legacyExec.placeOrder(any())).thenReturn(submitted("intent-K", "brk-1"));
      when(legacyExec.cancelOrder(any())).thenReturn(cancelled("intent-K", "brk-1"));

      worker.registerActivitiesImplementations(audit, strategy, legacyRisk, legacyContract);
      // Exec activities are pinned to broker-alpaca-paper (matching ExecActivitiesFactory) —
      // register
      // them on a dedicated worker for that queue, otherwise placeOrder blocks forever.
      Worker brokerWorker = env.newWorker("broker-alpaca-paper");
      brokerWorker.registerActivitiesImplementations(legacyExec);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      CopytradeSignalWorkflow wf =
          client.newWorkflowStub(
              CopytradeSignalWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(TTL_EXPIRY_EMULATOR_WORKFLOW_ID)
                  .build());
      // No onFill / no breach: the TTL await expires (test env skips time) and the legacy
      // cancel-then-expire sequence is recorded.
      wf.process(btoPayload());

      json = client.fetchHistory(TTL_EXPIRY_EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(TTL_EXPIRY_FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(TTL_EXPIRY_FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static CopytradeSignalPayload btoPayload() {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("legacy-111:0");
    p.setMessageId("legacy-111");
    p.setAuthor("acme_trader");
    p.setPostedAt(OffsetDateTime.of(2026, 5, 13, 17, 22, 31, 0, ZoneOffset.UTC));
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker("NVDA");
    p.setExpiry(LocalDate.of(2026, 5, 16));
    p.setStrike(new BigDecimal("140"));
    p.setRight(CopytradeSignalPayload.Right.C);
    p.setPrice(new BigDecimal("2.30"));
    p.setRawLine("BTO NVDA 5/16 140C @ 2.30");
    return p;
  }

  private static StrategyConfig legacyStrategyConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setAuthorWhitelist(Set.of("acme_trader"));
    c.setMaxSignalAgeBtoSecs(3600L);
    c.setMaxSignalAgeStcSecs(3600L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    c.setPendingTtlPaperSecs(1L);
    // CRITICAL: pre_trade_check_enabled was the toggle introduced ALONGSIDE PR #111. A faithful
    // pre-#111 history has it null/false — and even if set, the legacy emulator doesn't call
    // assertPreTradeCheckRoutable or dispatchPreTradeCheck, mirroring pre-#111 handleBto.
    c.setPreTradeCheckEnabled(false);
    return c;
  }

  /**
   * Pre-#111 {@link com.ohmytradeagent.orchestrator.activities.RiskActivities} shape: the {@code
   * checkEntry} method took 2 args, not 3. Temporal's default activity-type derivation is {@code
   * MethodName-with-first-char-uppercased} (interface name is irrelevant for type-name), so both
   * this interface and the production {@code RiskActivities} produce activity type {@code
   * CheckEntry} — the recorded history's {@code activityType.name} matches what the current
   * production impl schedules. Only the input payload count differs, which is the determinism
   * question this test answers.
   */
  @ActivityInterface
  public interface LegacyRiskActivities {
    RiskDecision checkEntry(CopytradeSignalPayload payload, StrategyConfig config);
  }

  // Issue #279: OCC resolved by the breach/TTL emulators' contract.resolve. Matches the
  // CopytradeSignalWorkflowImplTest value so the recorded Resolve activity input is representative.
  private static final ContractResolveResult LEGACY_OCC =
      new ContractResolveResult(
          "NVDA  260516C00140000",
          "NVDA",
          LocalDate.of(2026, 5, 16),
          new BigDecimal("140"),
          "C",
          ContractResolveResult.SOURCE_GENERATED);

  private static OrderIntentResult submitted(String intentKey, String brokerOrderId) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey(intentKey);
    r.setBrokerOrderId(brokerOrderId);
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private static OrderIntentResult cancelled(String intentKey, String brokerOrderId) {
    OrderIntentResult r = submitted(intentKey, brokerOrderId);
    r.setState(OrderIntentResult.State.CANCELLED);
    return r;
  }

  private static RiskBreachPayload riskBreachPayload() {
    RiskBreachPayload r = new RiskBreachPayload();
    r.setSchemaVersion(1L);
    r.setReason("auto:daily_loss");
    r.setActor("auto:daily_loss");
    r.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
    return r;
  }

  /**
   * Pre-#111 {@link com.ohmytradeagent.orchestrator.activities.ContractActivities} shape used by
   * the breach/TTL emulators. Method name {@code resolve} derives activity type {@code Resolve} —
   * matching the production stub — so the recorded history's {@code activityType.name} lines up on
   * replay regardless of the interface name.
   */
  @ActivityInterface
  public interface LegacyContractActivities {
    ContractResolveResult resolve(ContractResolveInput input);
  }

  /**
   * Pre-#111 {@link com.ohmytradeagent.orchestrator.activities.ExecActivities} shape used by the
   * breach/TTL emulators. Method names {@code placeOrder} / {@code cancelOrder} derive activity
   * types {@code PlaceOrder} / {@code CancelOrder} — matching the production stub.
   */
  @ActivityInterface
  public interface LegacyExecActivities {
    OrderIntentResult placeOrder(OrderIntent intent);

    OrderIntentResult cancelOrder(String intentKey);
  }

  /**
   * Issue #279: emulator mirroring the pre-#274 v=DEFAULT_VERSION breach-abort command order
   * exactly. Implements {@link CopytradeSignalWorkflow} so the recorded {@code workflowType.name}
   * is {@code CopytradeSignalWorkflow} — what {@link WorkflowReplayer} expects when registering
   * {@link CopytradeSignalWorkflowImpl}. The exec stub is pinned to {@code broker-alpaca-paper} to
   * match the task queue {@code ExecActivitiesFactory.forTarget("alpaca-paper")} routes to in
   * production.
   *
   * <p>Activity sequence — must match {@link CopytradeSignalWorkflowImpl#handleBto}'s legacy
   * breach-abort branch:
   *
   * <ol>
   *   <li>{@code audit.log(SignalReceived)} (Log)
   *   <li>{@code strategy.get} (Get)
   *   <li>{@code risk.checkEntry} (CheckEntry, 2-arg pre-#111 shape, approved)
   *   <li>{@code contract.resolve} (Resolve)
   *   <li>{@code strategy.capitalForStrategy} (CapitalForStrategy)
   *   <li>{@code audit.log(SignalAccepted)} (Log)
   *   <li>{@code exec.placeOrder} (PlaceOrder)
   *   <li>{@code audit.log(OrderSubmitted)} (Log)
   *   <li>[await wakes on riskBreach signal, no fill]
   *   <li>{@code audit.log(SignalAbortedByRiskBreach)} (Log) — auditRiskBreachAbort
   *   <li>{@code exec.cancelOrder} (CancelOrder, best-effort, result discarded)
   * </ol>
   */
  public static class LegacyBreachAbortEmulatorWorkflowImpl implements CopytradeSignalWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
    private static final ActivityOptions EXEC_OPTS =
        ActivityOptions.newBuilder()
            .setTaskQueue("broker-alpaca-paper")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();
    private final AuditActivities audit = Workflow.newActivityStub(AuditActivities.class, OPTS);
    private final StrategyActivities strategy =
        Workflow.newActivityStub(StrategyActivities.class, OPTS);
    private final LegacyRiskActivities risk =
        Workflow.newActivityStub(LegacyRiskActivities.class, OPTS);
    private final LegacyContractActivities contract =
        Workflow.newActivityStub(LegacyContractActivities.class, OPTS);
    private final LegacyExecActivities exec =
        Workflow.newActivityStub(LegacyExecActivities.class, EXEC_OPTS);

    private boolean riskBreachReceived;

    @Override
    public String process(CopytradeSignalPayload payload) {
      audit.log(auditEvent(payload, "SignalReceived"));
      StrategyConfig cfg = strategy.get(payload.getTenantId(), payload.getStrategyId());
      RiskDecision decision = risk.checkEntry(payload, cfg);
      if (!decision.allowed()) {
        audit.log(auditEvent(payload, "SignalRejected"));
        return payload.getSignalId();
      }
      contract.resolve(ContractResolveInput.from(payload));
      strategy.capitalForStrategy(payload.getTenantId(), payload.getStrategyId());
      audit.log(auditEvent(payload, "SignalAccepted"));
      String intentKey = Workflow.getInfo().getWorkflowId() + ":entry";
      exec.placeOrder(intent(payload, cfg, intentKey));
      audit.log(auditEvent(payload, "OrderSubmitted"));

      // Bounded await — schedules a START_TIMER exactly like the current impl's
      // Workflow.await(Duration.ofSeconds(ttlSecs), () -> fillEvent != null || riskBreachReceived).
      // The recorded START_TIMER must be present so the current impl's command sequence matches on
      // replay. A generous TTL ensures the riskBreach signal (not the timer) wakes the await.
      long ttlSecs = cfg.getPendingTtlPaperSecs() != null ? cfg.getPendingTtlPaperSecs() : 90L;
      Workflow.await(Duration.ofSeconds(ttlSecs), () -> riskBreachReceived);
      // Legacy v=DEFAULT_VERSION breach-abort: audit-and-abort then best-effort cancel.
      audit.log(auditEvent(payload, "SignalAbortedByRiskBreach"));
      try {
        exec.cancelOrder(intentKey);
      } catch (RuntimeException ignored) {
        // best-effort
      }
      return payload.getSignalId();
    }

    @Override
    public void onFill(FillSignalPayload event) {}

    @Override
    public void riskBreach(RiskBreachPayload payload) {
      // Mirror the production riskBreach handler's VERSION_RISK_BREACH gate so the recorded history
      // contains the risk-breach-v1 marker. A faithful pre-#274 history has risk-breach-v1 PRESENT
      // (the breach handling shipped well before #274) but breach-filled-adoption-v1 ABSENT (that's
      // the #274 gate this fixture pins). Without recording risk-breach-v1, the current impl's
      // handler would return DEFAULT_VERSION on replay, ignore the breach, and never enter the
      // breach-abort branch.
      int v = Workflow.getVersion("risk-breach-v1", Workflow.DEFAULT_VERSION, 1);
      if (v == Workflow.DEFAULT_VERSION) {
        return;
      }
      this.riskBreachReceived = true;
    }
  }

  /**
   * Issue #279: emulator mirroring the pre-#165 phase 2 v=DEFAULT_VERSION TTL-expiry command order
   * on a CANCELLED cancel result. Same entry sequence as the breach emulator, then [TTL await
   * expires with no fill] -> {@code Log(OrderCancelRequested)} -> {@code cancelOrder} (CancelOrder,
   * returns CANCELLED) -> {@code Log(OrderCancelled)} -> {@code Log(EntryExpired)} -> return. No
   * {@code ttl-filled-adoption-v1} marker recorded, so the current impl skips the FILLED-adoption
   * branch on replay.
   */
  public static class LegacyTtlExpiryEmulatorWorkflowImpl implements CopytradeSignalWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
    private static final ActivityOptions EXEC_OPTS =
        ActivityOptions.newBuilder()
            .setTaskQueue("broker-alpaca-paper")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();
    private final AuditActivities audit = Workflow.newActivityStub(AuditActivities.class, OPTS);
    private final StrategyActivities strategy =
        Workflow.newActivityStub(StrategyActivities.class, OPTS);
    private final LegacyRiskActivities risk =
        Workflow.newActivityStub(LegacyRiskActivities.class, OPTS);
    private final LegacyContractActivities contract =
        Workflow.newActivityStub(LegacyContractActivities.class, OPTS);
    private final LegacyExecActivities exec =
        Workflow.newActivityStub(LegacyExecActivities.class, EXEC_OPTS);

    @Override
    public String process(CopytradeSignalPayload payload) {
      audit.log(auditEvent(payload, "SignalReceived"));
      StrategyConfig cfg = strategy.get(payload.getTenantId(), payload.getStrategyId());
      RiskDecision decision = risk.checkEntry(payload, cfg);
      if (!decision.allowed()) {
        audit.log(auditEvent(payload, "SignalRejected"));
        return payload.getSignalId();
      }
      contract.resolve(ContractResolveInput.from(payload));
      strategy.capitalForStrategy(payload.getTenantId(), payload.getStrategyId());
      audit.log(auditEvent(payload, "SignalAccepted"));
      String intentKey = Workflow.getInfo().getWorkflowId() + ":entry";
      exec.placeOrder(intent(payload, cfg, intentKey));
      audit.log(auditEvent(payload, "OrderSubmitted"));

      long ttlSecs = cfg.getPendingTtlPaperSecs() != null ? cfg.getPendingTtlPaperSecs() : 90L;
      Workflow.await(Duration.ofSeconds(ttlSecs), () -> false); // never fills -> TTL expiry path.

      // Legacy v=DEFAULT_VERSION TTL-expiry on CANCELLED: cancel-request, cancel, cancelled,
      // expired.
      audit.log(auditEvent(payload, "OrderCancelRequested"));
      OrderIntentResult cancelResult = exec.cancelOrder(intentKey);
      if (cancelResult.getState() == OrderIntentResult.State.CANCELLED) {
        audit.log(auditEvent(payload, "OrderCancelled"));
      } else {
        audit.log(auditEvent(payload, "OrderCancelFailed"));
      }
      audit.log(auditEvent(payload, "EntryExpired"));
      return payload.getSignalId();
    }

    @Override
    public void onFill(FillSignalPayload event) {}

    @Override
    public void riskBreach(RiskBreachPayload payload) {}
  }

  private static OrderIntent intent(
      CopytradeSignalPayload payload, StrategyConfig cfg, String intentKey) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(payload.getTenantId());
    i.setStrategyId(payload.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId(payload.getSignalId());
    i.setBrokerTarget(OrderIntent.BrokerTarget.fromValue(cfg.getBrokerTarget().value()));
    i.setOptionSymbol(LEGACY_OCC.optionSymbol());
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(1L);
    i.setLimitPrice(payload.getPrice());
    i.setRecordedAt(
        OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
    return i;
  }

  private static AuditEvent auditEvent(CopytradeSignalPayload payload, String kind) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(payload.getTenantId());
    event.setStrategyId(payload.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(
        OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
    event.setKind(kind);
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("signal_id", payload.getSignalId());
    event.setSubject(s);
    event.setActor("workflow:LegacyEmulator");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(payload.getSignalId());
    return event;
  }

  /**
   * Emulator that mirrors the pre-#111 {@code handleBto} reject path exactly. Implements the
   * production {@link CopytradeSignalWorkflow} interface so the recorded history's {@code
   * workflowType.name} is {@code CopytradeSignalWorkflow} — what {@link
   * WorkflowReplayer#replayWorkflowExecution} expects when registering {@link
   * CopytradeSignalWorkflowImpl}.
   *
   * <p>Activity sequence — must match {@link CopytradeSignalWorkflowImpl#handleBto}'s legacy branch
   * up to and including the reject audit:
   *
   * <ol>
   *   <li>{@code audit.log(SignalReceived)}
   *   <li>{@code strategy.get(tenantId, strategyId)}
   *   <li>{@code risk.checkEntry(payload, config)} — 2-arg pre-#111 shape, returns {@code
   *       allowed=false}
   *   <li>{@code audit.log(SignalRejected)}
   * </ol>
   */
  public static class LegacyHandleBtoEmulatorWorkflowImpl implements CopytradeSignalWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
    private final AuditActivities audit = Workflow.newActivityStub(AuditActivities.class, OPTS);
    private final StrategyActivities strategy =
        Workflow.newActivityStub(StrategyActivities.class, OPTS);
    private final LegacyRiskActivities risk =
        Workflow.newActivityStub(LegacyRiskActivities.class, OPTS);

    @Override
    public String process(CopytradeSignalPayload payload) {
      audit.log(auditEvent(payload, "SignalReceived"));
      StrategyConfig cfg = strategy.get(payload.getTenantId(), payload.getStrategyId());
      RiskDecision decision = risk.checkEntry(payload, cfg);
      if (!decision.allowed()) {
        audit.log(auditEvent(payload, "SignalRejected"));
      }
      return payload.getSignalId();
    }

    @Override
    public void onFill(FillSignalPayload event) {}

    @Override
    public void riskBreach(com.ohmytradeagent.contract.RiskBreachPayload payload) {}

    private static AuditEvent auditEvent(CopytradeSignalPayload payload, String kind) {
      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId(payload.getTenantId());
      event.setStrategyId(payload.getStrategyId());
      event.setEventId(Workflow.randomUUID().toString());
      event.setOccurredAt(
          OffsetDateTime.ofInstant(
              java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
      event.setKind(kind);
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("signal_id", payload.getSignalId());
      event.setSubject(s);
      event.setActor("workflow:LegacyEmulator");
      event.setWorkflowId(Workflow.getInfo().getWorkflowId());
      event.setCorrelationId(payload.getSignalId());
      return event;
    }
  }
}
