package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * B2 (P0c-b1): {@link WorkflowReplayer}-based determinism coverage for the {@code
 * killswitch-live-floor} version gate added to {@link KillSwitchWorkflowImpl#heartbeat()}.
 *
 * <p>The B2 edit inserts {@code Workflow.getVersion("killswitch-live-floor", DEFAULT_VERSION, 1)}
 * into the heartbeat between {@code strategy.get(...)} and the null/≤0-threshold {@code return},
 * then — only at {@code v>=1} AND for a {@code -live} broker_target AND a null/≤0 threshold — fails
 * closed with a {@code doTrip("auto:missing_loss_threshold", ...)}. For EVERY pre-B2 in-flight
 * history the recorded events carry NO {@code killswitch-live-floor} marker, so {@code
 * getVersion(...)} resolves to {@link Workflow#DEFAULT_VERSION} on replay and the heartbeat command
 * stream is byte-identical to the legacy path. {@link TestWorkflowEnvironment} always reports
 * {@code getVersion(...) == 1} for fresh workflows, so the legacy {@code v == DEFAULT_VERSION}
 * branch is unreachable from a round-trip test — only {@link WorkflowReplayer} against a recorded
 * pre-marker history exercises it.
 *
 * <p><b>The sentinel</b> ({@link #legacyPaperNullThresholdHistoryReplaysCleanly}): a PAPER workflow
 * history whose heartbeat ticks took the OLD null-threshold {@code return} path with NO {@code
 * killswitch-live-floor} marker. Replayed under the NEW impl it MUST NOT raise {@code
 * NonDeterministicException} — and it MUST FAIL if the {@code getVersion} gate is omitted (omitting
 * the gate would make the legacy ticks try to schedule the new marker / take the live-trip branch).
 * This is the regression guard for the gate's existence. Verified during plan execution that
 * temporarily deleting the {@code getVersion} line makes this replay throw {@code
 * NonDeterministicException}.
 *
 * <p>Fixture production: a genuine pre-marker history is synthesised by {@link
 * LegacyHeartbeatEmulatorWorkflowImpl}, which mirrors {@link KillSwitchWorkflowImpl}'s {@code
 * run()} + {@code heartbeat()} command stream EXACTLY — {@code calendar.todayEt()} → {@code
 * Workflow.sleep} → {@code calendar.todayEt()} → {@code calendar.isMarketOpen()} → {@code
 * strategy.get(...)} → return-on-null-threshold, per tick — but WITHOUT the {@code getVersion}
 * marker call and without the continue-as-new tail (the watermark is never crossed in the captured
 * ticks). The history is captured WHILE THE WORKFLOW IS STILL RUNNING (the loop is infinite,
 * exactly like production) via {@link WorkflowClient#fetchHistory(String)}, so it ends on a {@code
 * WorkflowTaskCompleted} rather than a {@code WorkflowExecutionCompleted} — matching how a real
 * in-flight kill-switch history looks. Regenerate with {@code -Dgenerate.legacy.fixture=true}.
 */
class KillSwitchWorkflowImplLegacyReplayTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private static final String PAPER_FIXTURE_RESOURCE =
      "temporal/replay/killswitch-pre-b2-paper-nullthreshold-legacy-history.json";
  private static final Path PAPER_FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "killswitch-pre-b2-paper-nullthreshold-legacy-history.json");
  private static final String PAPER_EMULATOR_WORKFLOW_ID = "killswitch-pre-b2-paper-emulator";

  private static final String LIVE_FIXTURE_RESOURCE =
      "temporal/replay/killswitch-pre-b2-live-validthreshold-legacy-history.json";
  private static final Path LIVE_FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "killswitch-pre-b2-live-validthreshold-legacy-history.json");
  private static final String LIVE_EMULATOR_WORKFLOW_ID = "killswitch-pre-b2-live-emulator";

  /**
   * Pins the version-marker constant name so a rename in {@link KillSwitchWorkflowImpl} fails this
   * test loudly. Renaming the literal would silently re-version live executions.
   */
  @Test
  void versionKillswitchLiveFloorConstantNameIsStable() throws Exception {
    Field marker = KillSwitchWorkflowImpl.class.getDeclaredField("VERSION_KILLSWITCH_LIVE_FLOOR");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("killswitch-live-floor");
  }

  /**
   * THE SENTINEL. A pre-B2 PAPER history whose heartbeat ticks took the legacy null-threshold
   * {@code return} path with NO {@code killswitch-live-floor} marker. Replay under the new impl:
   * the gate resolves to {@code DEFAULT_VERSION}, the paper opt-out branch runs, and the command
   * stream matches byte-for-byte → no {@code NonDeterministicException}. Omit the {@code
   * getVersion} gate and this replay throws (the new marker / live-trip branch would diverge from
   * the recorded legacy ticks). This is the regression guard.
   */
  @Test
  void legacyPaperNullThresholdHistoryReplaysCleanly() throws Exception {
    assertThat(getClass().getClassLoader().getResource(PAPER_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=KillSwitchWorkflowImplLegacyReplayTest#regeneratePaperNullThresholdFixture`",
            PAPER_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        PAPER_FIXTURE_RESOURCE, KillSwitchWorkflowImpl.class);
  }

  /**
   * A pre-B2 LIVE history whose heartbeat ticks ran the VALID-threshold path (strategy.get →
   * computeRealizedPnl → non-breaching, no trip). Confirms in-flight live workflows that exercise
   * the normal path are unaffected by the B2 edit — the {@code getVersion} marker absent in the
   * recorded history resolves to {@code DEFAULT_VERSION} and the valid-threshold fall-through is
   * unchanged.
   */
  @Test
  void legacyLiveValidThresholdHistoryReplaysCleanly() throws Exception {
    assertThat(getClass().getClassLoader().getResource(LIVE_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=KillSwitchWorkflowImplLegacyReplayTest#regenerateLiveValidThresholdFixture`",
            LIVE_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        LIVE_FIXTURE_RESOURCE, KillSwitchWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regeneratePaperNullThresholdFixture() throws Exception {
    generateFixture(
        paperNullThresholdConfig(),
        /* computeRealizedPnl= */ false,
        PAPER_EMULATOR_WORKFLOW_ID,
        PAPER_FIXTURE_SOURCE_PATH);
  }

  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateLiveValidThresholdFixture() throws Exception {
    generateFixture(
        liveValidThresholdConfig(),
        /* computeRealizedPnl= */ true,
        LIVE_EMULATOR_WORKFLOW_ID,
        LIVE_FIXTURE_SOURCE_PATH);
  }

  /**
   * Drives {@link LegacyHeartbeatEmulatorWorkflowImpl} for a few heartbeat ticks under {@link
   * TestWorkflowEnvironment}, then captures the STILL-RUNNING history (the loop is infinite, so the
   * recorded history ends on a WorkflowTaskCompleted — mirroring a real in-flight kill-switch
   * execution that carries no marker). Writes the JSON to the committed fixture path.
   */
  private void generateFixture(
      StrategyConfig cfg, boolean computeRealizedPnl, String workflowId, Path out)
      throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyHeartbeatEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      StrategyActivities strategy = Mockito.mock(StrategyActivities.class);
      DailyPnlActivities pnl = Mockito.mock(DailyPnlActivities.class);
      KillSwitchCascadeActivities cascade = Mockito.mock(KillSwitchCascadeActivities.class);
      LivePromotionActivities livePromotion = Mockito.mock(LivePromotionActivities.class);

      when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 6, 14));
      when(calendar.isMarketOpen()).thenReturn(true);
      when(strategy.get(anyString(), anyString())).thenReturn(cfg);
      // Valid-threshold emulator computes a non-breaching pnl; null-threshold emulator never
      // reaches
      // the pnl Activity (the legacy return short-circuits first), so the stub is harmless either
      // way.
      when(pnl.computeRealizedPnl(anyString(), anyString(), any()))
          .thenReturn(new BigDecimal("-100"));

      worker.registerActivitiesImplementations(
          audit, calendar, strategy, pnl, cascade, livePromotion);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      KillSwitchWorkflow wf =
          client.newWorkflowStub(
              KillSwitchWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(workflowId)
                  .build());
      WorkflowStub.fromTyped(wf).start(input());

      // A few ticks (60s interval). The infinite loop never completes; we capture the running
      // history. 5 minutes of skipped time = ~4-5 heartbeat ticks — ample, well below the
      // watermark.
      env.sleep(Duration.ofMinutes(5));

      json = client.fetchHistory(workflowId).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(out.getParent());
    Files.writeString(out, json, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Legacy emulator — mirrors the PRE-B2 run()+heartbeat() command stream EXACTLY
  // ---------------------------------------------------------------------------

  /**
   * Mirrors {@link KillSwitchWorkflowImpl}'s {@code run()} + {@code heartbeat()} command stream as
   * it was BEFORE the B2 edit. Implements {@link KillSwitchWorkflow} so the recorded {@code
   * workflowType.name} is {@code KillSwitchWorkflow} — what {@link WorkflowReplayer} expects when
   * registering {@link KillSwitchWorkflowImpl}.
   *
   * <p>Per-tick command sequence (identical to the production heartbeat, MINUS the {@code
   * getVersion("killswitch-live-floor", ...)} marker the B2 edit adds):
   *
   * <ol>
   *   <li>{@code Workflow.sleep(60s)} (START_TIMER)
   *   <li>{@code calendar.todayEt()} (TodayEt) — the heartbeat's first line
   *   <li>{@code calendar.isMarketOpen()} (IsMarketOpen)
   *   <li>{@code strategy.get(tenant, strategy)} (Get)
   *   <li>for the null-threshold (paper) emulator: return — NO pnl, NO marker. For the
   *       valid-threshold (live) emulator: {@code pnl.computeRealizedPnl(...)}
   *       (ComputeRealizedPnl), non-breaching → no trip.
   * </ol>
   *
   * <p>No {@code getVersion} call is made, so the recorded history contains no {@code
   * killswitch-live-floor} MarkerRecordedEvent — the property the sentinel pins.
   */
  public static class LegacyHeartbeatEmulatorWorkflowImpl implements KillSwitchWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

    private final AuditActivities audit = Workflow.newActivityStub(AuditActivities.class, OPTS);
    private final MarketCalendarActivities calendar =
        Workflow.newActivityStub(MarketCalendarActivities.class, OPTS);
    private final StrategyActivities strategy =
        Workflow.newActivityStub(StrategyActivities.class, OPTS);
    private final DailyPnlActivities pnl = Workflow.newActivityStub(DailyPnlActivities.class, OPTS);

    private final KillSwitchWorkflowInput input;
    private boolean tripped;
    private LocalDate tradingDay;

    @WorkflowInit
    public LegacyHeartbeatEmulatorWorkflowImpl(KillSwitchWorkflowInput in) {
      this.input = in;
    }

    @Override
    public String run(KillSwitchWorkflowInput in) {
      if (this.tradingDay == null) {
        this.tradingDay = calendar.todayEt();
      }
      while (true) {
        Workflow.sleep(Duration.ofSeconds(60));
        legacyHeartbeat();
      }
    }

    /** The PRE-B2 heartbeat body — identical to production minus the getVersion marker. */
    private void legacyHeartbeat() {
      LocalDate today = calendar.todayEt();
      if (!today.equals(tradingDay)) {
        this.tradingDay = today;
      }
      if (tripped) {
        return;
      }
      if (!calendar.isMarketOpen()) {
        return;
      }
      StrategyConfig cfg = strategy.get(input.getTenantId(), input.getStrategyId());
      BigDecimal threshold = cfg.getDailyLossThreshold();
      if (threshold == null || threshold.signum() <= 0) {
        // Legacy null-threshold opt-out — NO marker, NO pnl. This is the path the sentinel records.
        return;
      }
      BigDecimal pnlValue =
          pnl.computeRealizedPnl(input.getTenantId(), input.getStrategyId(), tradingDay);
      if (pnlValue.compareTo(threshold.negate()) <= 0) {
        // Would trip in production; the valid-threshold fixture uses a non-breaching pnl so this
        // branch is never taken (recording a clean valid-threshold tick stream).
        this.tripped = true;
        audit.log(trippedAudit());
      }
    }

    private AuditEvent trippedAudit() {
      AuditEvent e = new AuditEvent();
      e.setSchemaVersion(1L);
      e.setTenantId(input.getTenantId());
      e.setStrategyId(input.getStrategyId());
      e.setEventId(Workflow.randomUUID().toString());
      e.setOccurredAt(
          OffsetDateTime.ofInstant(
              Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
      e.setKind("KillSwitchTripped");
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("reason", "auto:daily_loss");
      e.setSubject(s);
      e.setActor("workflow:LegacyEmulator");
      e.setWorkflowId(Workflow.getInfo().getWorkflowId());
      e.setCorrelationId(input.getTenantId() + "/" + input.getStrategyId());
      return e;
    }

    // The remaining KillSwitchWorkflow surface is unused by the emulator (the fixture only drives
    // the heartbeat loop) — no-op / minimal so the interface is satisfied.
    @Override
    public void tripValidator(TripKillSwitchRequest request) {}

    @Override
    public void trip(TripKillSwitchRequest request) {}

    @Override
    public void resetValidator(ResetKillSwitchRequest request) {}

    @Override
    public void reset(ResetKillSwitchRequest request) {}

    @Override
    public void recordLivePromotionValidator(LivePromotionApprovalRequest request) {}

    @Override
    public void recordLivePromotion(LivePromotionApprovalRequest request) {}

    @Override
    public KillSwitchState killswitchState() {
      KillSwitchState s = new KillSwitchState();
      s.setSchemaVersion(1L);
      s.setTripped(tripped);
      return s;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static KillSwitchWorkflowInput input() {
    KillSwitchWorkflowInput in = new KillSwitchWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    return in;
  }

  private static StrategyConfig baseConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setAuthorWhitelist(Set.of("acme_trader"));
    c.setMaxSignalAgeBtoSecs(3600L);
    c.setMaxSignalAgeStcSecs(3600L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setResetCooldownSecs(60L);
    return c;
  }

  private static StrategyConfig paperNullThresholdConfig() {
    StrategyConfig c = baseConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    c.setDailyLossThreshold(null); // legacy null-threshold opt-out path
    return c;
  }

  private static StrategyConfig liveValidThresholdConfig() {
    StrategyConfig c = baseConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setDailyLossThreshold(new BigDecimal("2500.00"));
    return c;
  }
}
