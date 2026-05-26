package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
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
import java.io.InputStream;
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

  private static final String CORE_QUEUE = "orchestrator-core";

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
    // Sanity-check the fixture exists; emit a clear error path rather than the SDK's
    // FileNotFound. The fixture is regenerated via the @EnabledIfSystemProperty test below.
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
      assertThat(in)
          .as(
              "Missing fixture resource %s. Regenerate with"
                  + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                  + " -Dtest=CopytradeSignalWorkflowImplLegacyReplayTest#regenerateLegacyFixture`",
              FIXTURE_RESOURCE)
          .isNotNull();
    }

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, CopytradeSignalWorkflowImpl.class);
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
    String workflowId;
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
                  .setWorkflowId("legacy-pre-111-emulator")
                  .build());
      wf.process(btoPayload());
      workflowId = "legacy-pre-111-emulator";

      WorkflowExecutionHistory history = client.fetchHistory(workflowId);
      // toJson(true) requests pretty-printed protojson; the SDK uses
      // google.protobuf.util.JsonFormat
      // under the hood so the output is round-trippable via WorkflowExecutionHistory.fromJson(...).
      String json = history.toJson(true);

      Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
      Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
    } finally {
      env.close();
    }

    // Sanity check: the regenerated file must parse back via WorkflowExecutionHistory.fromJson.
    assertThat(Files.exists(FIXTURE_SOURCE_PATH)).isTrue();
    String roundTrip = Files.readString(FIXTURE_SOURCE_PATH, StandardCharsets.UTF_8);
    WorkflowExecutionHistory parsed = WorkflowExecutionHistory.fromJson(roundTrip);
    assertThat(parsed.getEvents()).isNotEmpty();
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
    public void onFill(FillEvent event) {
      // unused — replay path never signals
    }

    @Override
    public void riskBreach(com.ohmytradeagent.contract.RiskBreachPayload payload) {
      // unused — replay path never signals
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
  }
}
