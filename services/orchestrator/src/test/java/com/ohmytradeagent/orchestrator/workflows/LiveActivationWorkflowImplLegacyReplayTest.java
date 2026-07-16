package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveActivationResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.orchestrator.activities.LiveActivationGateActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/**
 * Phase 3b (single-account-loss-rule): {@link WorkflowReplayer}-based determinism coverage for the
 * {@code live-activation-account-cap-aware-v1} version gate added to {@link
 * LiveActivationWorkflowImpl#activateLive}.
 *
 * <p>The 3b edit inserts {@code Workflow.getVersion("live-activation-account-cap-aware-v1",
 * DEFAULT_VERSION, 1)} at the top of {@code activateLive} and — only at {@code v>=1} — reads the
 * tenant account cap via TWO {@code TenantConfigActivities} commands (in step (b), BEFORE {@code
 * gate.killSwitchArmable}), then uses the 4-arg {@code validateLiveRequiredGates} overload. For
 * EVERY pre-3b in-flight history the recorded events carry NO {@code
 * live-activation-account-cap-aware-v1} marker, so {@code getVersion(...)} resolves to {@link
 * Workflow#DEFAULT_VERSION} on replay, the pre-3b 2-arg gate runs, and the command stream (no
 * TenantConfigActivities commands) is byte-identical to the legacy path. {@link
 * TestWorkflowEnvironment} always reports {@code getVersion(...) == 1} for fresh workflows, so the
 * legacy {@code DEFAULT_VERSION} branch is unreachable from a round-trip test — only {@link
 * WorkflowReplayer} against a recorded pre-marker history exercises it.
 *
 * <p><b>The sentinel</b> ({@link #legacyActivationHistoryReplaysCleanly}): a completed legacy
 * activation history whose command stream ran the pre-3b {@code activateLive} (strategy.get →
 * killSwitchArmable → account probe → activate → resetKillSwitch) with NO {@code
 * live-activation-account-cap-aware-v1} marker. Replayed under the NEW impl it MUST NOT raise
 * {@code NonDeterministicException} — proving the DEFAULT_VERSION branch issues NO new {@code
 * TenantConfigActivities} command. It MUST FAIL if the {@code getVersion} gate is omitted (omitting
 * it would make replay schedule the two account-cap activities before {@code killSwitchArmable},
 * diverging from the recorded legacy commands). This is the regression guard for the gate.
 *
 * <p>Fixture production: a genuine pre-marker history is synthesised by {@link
 * LegacyActivateEmulatorWorkflowImpl}, which mirrors {@link LiveActivationWorkflowImpl}'s {@code
 * activateLive} command stream EXACTLY — {@code strategy.get} → {@code gate.killSwitchArmable} →
 * {@code AccountSnapshotActivity.accountSnapshot} (on {@code broker-alpaca-live}) → {@code
 * promotion.activate} → {@code gate.resetKillSwitch} — but WITHOUT the {@code getVersion} marker
 * and WITHOUT the account-cap reads. Regenerate with {@code -Dgenerate.legacy.fixture=true}.
 */
class LiveActivationWorkflowImplLegacyReplayTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String EXEC_QUEUE = "broker-alpaca-live";
  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark";
  private static final String ACCOUNT = "PA3FKGPFYPLH";

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/live-activation-pre-phase3b-legacy-history.json";
  private static final Path FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "live-activation-pre-phase3b-legacy-history.json");
  private static final String EMULATOR_WORKFLOW_ID = "live-activation-pre-phase3b-emulator";

  /**
   * THE SENTINEL. A pre-3b activation history (no {@code live-activation-account-cap-aware-v1}
   * marker) replayed under the new impl: the gate resolves to {@code DEFAULT_VERSION}, the 2-arg
   * step (b) runs, and the command stream matches byte-for-byte → no {@code
   * NonDeterministicException}. Omit the {@code getVersion} gate and this replay throws (the new
   * account-cap activities would diverge from the recorded legacy commands). Regression guard.
   */
  @Test
  void legacyActivationHistoryReplaysCleanly() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=LiveActivationWorkflowImplLegacyReplayTest#regenerateLegacyActivationFixture`",
            FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, LiveActivationWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateLegacyActivationFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker coreWorker = env.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(LegacyActivateEmulatorWorkflowImpl.class);

      StrategyActivities strategy = Mockito.mock(StrategyActivities.class);
      LiveActivationGateActivities gate = Mockito.mock(LiveActivationGateActivities.class);
      LivePromotionActivities promotion = Mockito.mock(LivePromotionActivities.class);
      AccountSnapshotActivity snapshot = Mockito.mock(AccountSnapshotActivity.class);

      when(strategy.get(anyString(), anyString())).thenReturn(compliantConfig());
      when(gate.killSwitchArmable(anyString(), anyString())).thenReturn(true);
      when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class)))
          .thenReturn(snap(ACCOUNT, new BigDecimal("5000")));

      coreWorker.registerActivitiesImplementations(strategy, gate, promotion);
      Worker brokerWorker = env.newWorker(EXEC_QUEUE);
      brokerWorker.registerActivitiesImplementations(snapshot);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      LiveActivationWorkflow wf =
          client.newWorkflowStub(
              LiveActivationWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(EMULATOR_WORKFLOW_ID)
                  .build());
      LiveActivationResult result = wf.activateLive(activateReq());
      assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.ACTIVATED);

      json = client.fetchHistory(EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Legacy emulator — mirrors the PRE-3b activateLive command stream EXACTLY
  // ---------------------------------------------------------------------------

  /**
   * Mirrors {@link LiveActivationWorkflowImpl}'s {@code activateLive} command stream as it was
   * BEFORE the 3b edit. Implements {@link LiveActivationWorkflow} so the recorded {@code
   * workflowType.name} is {@code LiveActivationWorkflow} — what {@link WorkflowReplayer} expects
   * when registering {@link LiveActivationWorkflowImpl}.
   *
   * <p>Command sequence (identical to the pre-3b activateLive, MINUS the {@code
   * getVersion("live-activation-account-cap-aware-v1", ...)} marker and the two {@code
   * TenantConfigActivities} reads the 3b edit adds):
   *
   * <ol>
   *   <li>{@code strategy.get(tenant, strategy)} (ScheduleActivityTask)
   *   <li>{@code StrategyConfigInvariants.validateLiveRequiredGates(config, label)} — pure, NO
   *       command
   *   <li>capital_source check — pure, NO command
   *   <li>{@code gate.killSwitchArmable(...)} (ScheduleActivityTask)
   *   <li>{@code AccountSnapshotActivity.accountSnapshot(...)} on {@code broker-alpaca-live}
   *       (ScheduleActivityTask)
   *   <li>{@code promotion.activate(...)} (ScheduleActivityTask)
   *   <li>{@code gate.resetKillSwitch(...)} (ScheduleActivityTask)
   * </ol>
   */
  public static class LegacyActivateEmulatorWorkflowImpl implements LiveActivationWorkflow {

    private static final ActivityOptions CORE_OPTIONS =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build();

    private final StrategyActivities strategy =
        Workflow.newActivityStub(StrategyActivities.class, CORE_OPTIONS);
    private final LiveActivationGateActivities gate =
        Workflow.newActivityStub(LiveActivationGateActivities.class, CORE_OPTIONS);
    private final LivePromotionActivities promotion =
        Workflow.newActivityStub(LivePromotionActivities.class, CORE_OPTIONS);

    @Override
    public LiveActivationResult activateLive(LiveActivationRequest request) {
      String tenant = request.getTenantId();
      String strategyId = request.getStrategyId();
      String label = tenant + "/" + strategyId;

      // (a) stored config must be live. NO getVersion marker (pre-3b).
      StrategyConfig config = strategy.get(tenant, strategyId);
      if (config == null || !StrategyConfigInvariants.isLive(config)) {
        return result(LiveActivationResult.Outcome.REJECTED_NOT_LIVE, "strategy is not live", null);
      }

      // (b) pre-3b 2-arg gate — pure, no command.
      try {
        StrategyConfigInvariants.validateLiveRequiredGates(config, label);
      } catch (IllegalStateException e) {
        return result(LiveActivationResult.Outcome.REJECTED_CONFIG, e.getMessage(), null);
      }

      // (c) capital_source — pure.
      if (config.getCapitalSource() != StrategyConfig.CapitalSource.ACCOUNT_CASH) {
        return result(LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE, "capital_source", null);
      }

      // (d) kill switch armable.
      if (!gate.killSwitchArmable(tenant, strategyId)) {
        return result(LiveActivationResult.Outcome.REJECTED_KILLSWITCH, "kill switch", null);
      }

      // (e) fresh account probe (broker-<target>).
      AccountSnapshotResult snapshot = probeAccount(request, config);
      String accountNumber = snapshot == null ? null : snapshot.getAccountNumber();
      BigDecimal cash = snapshot == null ? null : snapshot.getCash();
      if (accountNumber == null || accountNumber.isBlank() || cash == null || cash.signum() <= 0) {
        return result(LiveActivationResult.Outcome.REJECTED_ACCOUNT, "account probe", null);
      }

      // (f) activate.
      LiveActivationRequest activateReq = new LiveActivationRequest();
      activateReq.setSchemaVersion(request.getSchemaVersion());
      activateReq.setTenantId(tenant);
      activateReq.setStrategyId(strategyId);
      activateReq.setBrokerTarget(
          LiveActivationRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
      activateReq.setOperatorId(request.getOperatorId());
      activateReq.setExpectedAccountId(accountNumber);
      promotion.activate(activateReq);

      // (g) reset kill switch.
      gate.resetKillSwitch(tenant, strategyId, request.getOperatorId());

      return result(LiveActivationResult.Outcome.ACTIVATED, null, accountNumber);
    }

    private AccountSnapshotResult probeAccount(
        LiveActivationRequest request, StrategyConfig config) {
      String brokerTarget = config.getBrokerTarget().value();
      AccountSnapshotActivity activity =
          Workflow.newActivityStub(
              AccountSnapshotActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                  .setStartToCloseTimeout(Duration.ofSeconds(15))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                  .build());

      AccountSnapshotRequest r = new AccountSnapshotRequest();
      r.setSchemaVersion(1L);
      r.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.fromValue(brokerTarget));
      r.setTenantId(request.getTenantId());
      return activity.accountSnapshot(r);
    }

    private static LiveActivationResult result(
        LiveActivationResult.Outcome outcome, String reason, String expectedAccountId) {
      LiveActivationResult r = new LiveActivationResult();
      r.setSchemaVersion(1L);
      r.setOutcome(outcome);
      if (reason != null) {
        r.setReason(reason);
      }
      if (expectedAccountId != null) {
        r.setExpectedAccountId(expectedAccountId);
      }
      return r;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static LiveActivationRequest activateReq() {
    LiveActivationRequest r = new LiveActivationRequest();
    r.setSchemaVersion(1L);
    r.setTenantId(TENANT);
    r.setStrategyId(STRATEGY);
    r.setBrokerTarget(LiveActivationRequest.BrokerTarget.LIVE);
    r.setOperatorId(OPERATOR);
    return r;
  }

  private static StrategyConfig compliantConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setDailyLossThreshold(new BigDecimal("250"));
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.5"));
    c.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    return c;
  }

  private static AccountSnapshotResult snap(String accountNumber, BigDecimal cash) {
    AccountSnapshotResult r = new AccountSnapshotResult();
    r.setSchemaVersion(1L);
    r.setEquity(new BigDecimal("5000"));
    r.setCash(cash);
    r.setAccountNumber(accountNumber);
    return r;
  }
}
