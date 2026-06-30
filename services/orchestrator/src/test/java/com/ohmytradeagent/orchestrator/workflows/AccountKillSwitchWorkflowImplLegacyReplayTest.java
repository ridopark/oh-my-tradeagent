package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
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
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/**
 * Replay-determinism coverage for the {@code account-daily-loss-pct-of-sod-equity-v1} version gate
 * added to {@link AccountKillSwitchWorkflowImpl#heartbeat()}.
 *
 * <p>The change inserts {@code Workflow.getVersion("account-daily-loss-pct-of-sod-equity-v1",
 * DEFAULT_VERSION, 1)} at the top of the heartbeat and, only at {@code v>=1}, adds the SOD-equity
 * snapshot + the pct threshold-resolution commands ({@code accountDailyLossPct} read, {@code
 * tenantBrokerTarget} read, the {@code AccountSnapshotActivity} dispatch). For EVERY pre-change
 * in-flight history the recorded events carry NO {@code account-daily-loss-pct-of-sod-equity-v1}
 * marker, so {@code getVersion(...)} resolves to {@link Workflow#DEFAULT_VERSION} on replay and the
 * heartbeat command stream is byte-identical to the legacy absolute-threshold path. {@link
 * TestWorkflowEnvironment} always reports {@code getVersion(...) == 1} for fresh workflows, so the
 * legacy {@code v == DEFAULT_VERSION} branch is unreachable from a round-trip test — only {@link
 * WorkflowReplayer} against a recorded pre-marker history exercises it.
 *
 * <p><b>The sentinel</b> ({@link #legacyAbsoluteThresholdHistoryReplaysCleanly}): a pre-change
 * history whose heartbeat ticks took the legacy absolute-threshold opt-out (null threshold) {@code
 * return} path with NO marker. Replayed under the new impl it MUST NOT raise {@code
 * NonDeterministicException} — and it MUST FAIL if the {@code getVersion} gate is omitted (omitting
 * the gate would make the legacy ticks try to schedule the new marker / take the SOD-equity
 * branch). This is the regression guard for the gate's existence.
 *
 * <p>Fixture production: a genuine pre-marker history is synthesised by {@link
 * LegacyHeartbeatEmulatorWorkflowImpl}, which mirrors {@link AccountKillSwitchWorkflowImpl}'s
 * {@code run()} + {@code heartbeat()} command stream EXACTLY as it was BEFORE the change — {@code
 * calendar.todayEt()} → {@code Workflow.sleep} → {@code calendar.todayEt()} → {@code
 * calendar.isMarketOpen()} → {@code tenantConfig.accountDailyLossThreshold(...)} →
 * return-on-null-threshold, per tick — but WITHOUT the {@code getVersion} marker call and without
 * the continue-as-new tail. The history is captured WHILE THE WORKFLOW IS STILL RUNNING (the loop
 * is infinite, exactly like production) via {@link WorkflowClient#fetchHistory(String)}, so it ends
 * on a {@code WorkflowTaskCompleted} — matching how a real in-flight kill-switch history looks.
 * Regenerate with {@code -Dgenerate.legacy.fixture=true}.
 */
class AccountKillSwitchWorkflowImplLegacyReplayTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/account-killswitch-pre-pct-nullthreshold-legacy-history.json";
  private static final Path FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "account-killswitch-pre-pct-nullthreshold-legacy-history.json");
  private static final String EMULATOR_WORKFLOW_ID = "account-killswitch-pre-pct-emulator";

  /**
   * Pins the version-marker constant value so a rename in {@link AccountKillSwitchWorkflowImpl}
   * fails this test loudly. Renaming the literal would silently re-version live executions.
   */
  @Test
  void versionAccountDailyLossPctConstantNameIsStable() throws Exception {
    Field marker =
        AccountKillSwitchWorkflowImpl.class.getDeclaredField("VERSION_ACCOUNT_DAILY_LOSS_PCT");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("account-daily-loss-pct-of-sod-equity-v1");
  }

  /**
   * THE SENTINEL. A pre-change history whose heartbeat ticks took the legacy null-threshold {@code
   * return} path with NO {@code account-daily-loss-pct-of-sod-equity-v1} marker. Replay under the
   * new impl: the gate resolves to {@code DEFAULT_VERSION}, the absolute-threshold path runs, and
   * the command stream matches byte-for-byte → no {@code NonDeterministicException}. Omit the
   * {@code getVersion} gate and this replay throws (the new marker / SOD-equity branch would
   * diverge from the recorded legacy ticks). This is the regression guard.
   */
  @Test
  void legacyAbsoluteThresholdHistoryReplaysCleanly() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=AccountKillSwitchWorkflowImplLegacyReplayTest#regenerateFixture"
                + " -Dsurefire.failIfNoSpecifiedTests=false`",
            FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, AccountKillSwitchWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyHeartbeatEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      TenantConfigActivities tenantConfig = Mockito.mock(TenantConfigActivities.class);
      AccountPnlActivities accountPnl = Mockito.mock(AccountPnlActivities.class);
      AccountKillSwitchCascadeActivities cascade =
          Mockito.mock(AccountKillSwitchCascadeActivities.class);

      when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 6, 14));
      when(calendar.isMarketOpen()).thenReturn(true);
      // Legacy null-threshold opt-out path: the heartbeat returns before any PnL/book read.
      when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);

      worker.registerActivitiesImplementations(audit, calendar, tenantConfig, accountPnl, cascade);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      AccountKillSwitchWorkflow wf =
          client.newWorkflowStub(
              AccountKillSwitchWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(EMULATOR_WORKFLOW_ID)
                  .build());
      WorkflowStub.fromTyped(wf).start(input());

      // A few ticks (60s interval). The infinite loop never completes; capture the running history.
      env.sleep(Duration.ofMinutes(5));

      json = client.fetchHistory(EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Legacy emulator — mirrors the PRE-change run()+heartbeat() command stream EXACTLY
  // ---------------------------------------------------------------------------

  /**
   * Mirrors {@link AccountKillSwitchWorkflowImpl}'s {@code run()} + {@code heartbeat()} command
   * stream as it was BEFORE the pct change. Implements {@link AccountKillSwitchWorkflow} so the
   * recorded {@code workflowType.name} is {@code AccountKillSwitchWorkflow} — what {@link
   * WorkflowReplayer} expects when registering {@link AccountKillSwitchWorkflowImpl}.
   *
   * <p>Per-tick command sequence (identical to the pre-change heartbeat, MINUS the {@code
   * getVersion("account-daily-loss-pct-of-sod-equity-v1", ...)} marker): {@code
   * Workflow.sleep(60s)} → {@code calendar.todayEt()} → (not-tripped, no cooldown) → {@code
   * calendar.isMarketOpen()} → {@code tenantConfig.accountDailyLossThreshold(...)} → return on null
   * threshold. No {@code getVersion} call is made, so the recorded history contains no marker.
   */
  public static class LegacyHeartbeatEmulatorWorkflowImpl implements AccountKillSwitchWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

    private final MarketCalendarActivities calendar =
        Workflow.newActivityStub(MarketCalendarActivities.class, OPTS);
    private final TenantConfigActivities tenantConfig =
        Workflow.newActivityStub(TenantConfigActivities.class, OPTS);

    private final AccountKillSwitchWorkflowInput input;
    private boolean tripped;
    private LocalDate tradingDay;

    @WorkflowInit
    public LegacyHeartbeatEmulatorWorkflowImpl(AccountKillSwitchWorkflowInput in) {
      this.input = in;
    }

    @Override
    public String run(AccountKillSwitchWorkflowInput in) {
      if (this.tradingDay == null) {
        this.tradingDay = calendar.todayEt();
      }
      while (true) {
        Workflow.sleep(Duration.ofSeconds(60));
        legacyHeartbeat();
      }
    }

    /** The PRE-change heartbeat body — identical to production minus the getVersion marker. */
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
      BigDecimal threshold = tenantConfig.accountDailyLossThreshold(input.getTenantId());
      if (threshold == null || threshold.signum() <= 0) {
        // Legacy null-threshold opt-out — NO marker, NO PnL. This is the path the sentinel records.
        return;
      }
      // Valid-threshold tail unused by the fixture (the null-threshold opt-out short-circuits).
      this.tripped = true;
    }

    // The remaining surface is unused by the emulator (the fixture only drives the heartbeat loop).
    @Override
    public void tripValidator(TripKillSwitchRequest request) {}

    @Override
    public void trip(TripKillSwitchRequest request) {}

    @Override
    public void resetValidator(ResetKillSwitchRequest request) {}

    @Override
    public void reset(ResetKillSwitchRequest request) {}

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

  private static AccountKillSwitchWorkflowInput input() {
    AccountKillSwitchWorkflowInput in = new AccountKillSwitchWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    return in;
  }
}
