package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveActivationResult;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.orchestrator.activities.LiveActivationGateActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase F (operator-account-onboarding) coverage for the one-click live activation / deactivation
 * workflow. The {@link AccountSnapshotActivity} is registered on a SEPARATE {@code
 * broker-alpaca-live} worker (matching production routing); every other activity is on
 * orchestrator-core. Each refusal path is its own case; the all-pass path asserts a single
 * activate() with the probed expected_account_id. deactivateLive asserts deactivate() AND a
 * kill-switch trip.
 */
class LiveActivationWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String EXEC_QUEUE = "broker-alpaca-live";
  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark";
  private static final String ACCOUNT = "PA3FKGPFYPLH";

  private TestWorkflowEnvironment env;
  private StrategyActivities strategy;
  private LiveActivationGateActivities gate;
  private LivePromotionActivities promotion;
  private AccountSnapshotActivity snapshot;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    strategy = mock(StrategyActivities.class);
    gate = mock(LiveActivationGateActivities.class);
    promotion = mock(LivePromotionActivities.class);
    snapshot = mock(AccountSnapshotActivity.class);

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(LiveActivationWorkflowImpl.class);
    coreWorker.registerActivitiesImplementations(strategy, gate, promotion);

    Worker brokerWorker = env.newWorker(EXEC_QUEUE);
    brokerWorker.registerActivitiesImplementations(snapshot);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  // ---- helpers ------------------------------------------------------------------------------

  private LiveActivationWorkflow activateStub() {
    return env.getWorkflowClient()
        .newWorkflowStub(
            LiveActivationWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  private LiveDeactivationWorkflow deactivateStub() {
    return env.getWorkflowClient()
        .newWorkflowStub(
            LiveDeactivationWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  private LiveActivationRequest activateReq() {
    LiveActivationRequest r = new LiveActivationRequest();
    r.setSchemaVersion(1L);
    r.setTenantId(TENANT);
    r.setStrategyId(STRATEGY);
    r.setBrokerTarget(LiveActivationRequest.BrokerTarget.LIVE); // placeholder; workflow uses config
    r.setOperatorId(OPERATOR);
    return r;
  }

  private LiveDeactivationRequest deactivateReq() {
    LiveDeactivationRequest r = new LiveDeactivationRequest();
    r.setSchemaVersion(1L);
    r.setTenantId(TENANT);
    r.setStrategyId(STRATEGY);
    r.setBrokerTarget(LiveDeactivationRequest.BrokerTarget.LIVE);
    r.setOperatorId(OPERATOR);
    return r;
  }

  /** A fully-compliant live config: alpaca-live, loss gates set, account_cash sizing. */
  private StrategyConfig compliantConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setDailyLossThreshold(new BigDecimal("250"));
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.5"));
    c.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    return c;
  }

  private AccountSnapshotResult snap(String accountNumber, BigDecimal cash) {
    AccountSnapshotResult r = new AccountSnapshotResult();
    r.setSchemaVersion(1L);
    r.setEquity(new BigDecimal("5000"));
    r.setCash(cash);
    r.setAccountNumber(accountNumber);
    return r;
  }

  private void allGatesPass() {
    when(strategy.get(TENANT, STRATEGY)).thenReturn(compliantConfig());
    when(gate.killSwitchArmable(TENANT, STRATEGY)).thenReturn(true);
    when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class)))
        .thenReturn(snap(ACCOUNT, new BigDecimal("5000")));
  }

  // ---- refusal cases ------------------------------------------------------------------------

  @Test
  void notLive_isRejectedNotLive_noActivate() {
    StrategyConfig paper = compliantConfig();
    paper.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(paper);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_NOT_LIVE);
    verify(promotion, never()).activate(any());
  }

  @Test
  void dailyLossThresholdMissing_isRejectedConfig_noActivate() {
    StrategyConfig cfg = compliantConfig();
    cfg.setDailyLossThreshold(BigDecimal.ZERO); // not > 0
    when(strategy.get(TENANT, STRATEGY)).thenReturn(cfg);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CONFIG);
    assertThat(result.getReason()).contains("daily_loss_threshold");
    verify(promotion, never()).activate(any());
  }

  @Test
  void notionalCapUnset_isRejectedConfig_noActivate() {
    StrategyConfig cfg = compliantConfig();
    cfg.setNotionalCapPctOfCapitalBase(null);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(cfg);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CONFIG);
    assertThat(result.getReason()).contains("notional_cap");
    verify(promotion, never()).activate(any());
  }

  @Test
  void capitalSourceStatic_isRejectedCapitalSource_noActivate() {
    StrategyConfig cfg = compliantConfig();
    cfg.setCapitalSource(StrategyConfig.CapitalSource.STATIC);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(cfg);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE);
    verify(promotion, never()).activate(any());
  }

  @Test
  void killSwitchUnreachable_isRejectedKillswitch_noActivate() {
    when(strategy.get(TENANT, STRATEGY)).thenReturn(compliantConfig());
    when(gate.killSwitchArmable(TENANT, STRATEGY)).thenReturn(false);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_KILLSWITCH);
    verify(promotion, never()).activate(any());
  }

  @Test
  void blankAccountNumber_isRejectedAccount_noActivate() {
    when(strategy.get(TENANT, STRATEGY)).thenReturn(compliantConfig());
    when(gate.killSwitchArmable(TENANT, STRATEGY)).thenReturn(true);
    when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class)))
        .thenReturn(snap("", new BigDecimal("5000")));

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_ACCOUNT);
    verify(promotion, never()).activate(any());
  }

  @Test
  void nonPositiveCash_isRejectedAccount_noActivate() {
    when(strategy.get(TENANT, STRATEGY)).thenReturn(compliantConfig());
    when(gate.killSwitchArmable(TENANT, STRATEGY)).thenReturn(true);
    when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class)))
        .thenReturn(snap(ACCOUNT, BigDecimal.ZERO));

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_ACCOUNT);
    verify(promotion, never()).activate(any());
  }

  // ---- success paths ------------------------------------------------------------------------

  @Test
  void allGatesPass_activatesOnce_withProbedAccount() {
    allGatesPass();

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.ACTIVATED);
    assertThat(result.getExpectedAccountId()).isEqualTo(ACCOUNT);

    ArgumentCaptor<LiveActivationRequest> captor =
        ArgumentCaptor.forClass(LiveActivationRequest.class);
    verify(promotion, times(1)).activate(captor.capture());
    LiveActivationRequest activated = captor.getValue();
    assertThat(activated.getTenantId()).isEqualTo(TENANT);
    assertThat(activated.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(activated.getOperatorId()).isEqualTo(OPERATOR);
    assertThat(activated.getExpectedAccountId()).isEqualTo(ACCOUNT);
    // The activate row carries the STORED config's broker_target, not the request placeholder.
    assertThat(activated.getBrokerTarget())
        .isEqualTo(LiveActivationRequest.BrokerTarget.ALPACA_LIVE);
  }

  @Test
  void secondTenant_allGatesPass_activatesWithItsOwnProbedAccount() {
    // Fleet enablement Phase 2: a SECOND distinct live tenant activates independently, emitting
    // LivePromotionApproved with ITS OWN probed account — proving the gate is tenant-parameterized,
    // not pinned to the first tenant/account. Also Verify-C evidence: the account probe threads
    // this
    // tenant's NON-BLANK tenant_id into the AccountSnapshotRequest so exec resolves the right
    // broker.
    String tenant2 = "prod_real";
    String account2 = "847309116";
    when(strategy.get(tenant2, STRATEGY)).thenReturn(compliantConfig());
    when(gate.killSwitchArmable(tenant2, STRATEGY)).thenReturn(true);
    when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class)))
        .thenReturn(snap(account2, new BigDecimal("12000")));

    LiveActivationRequest req = new LiveActivationRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(tenant2);
    req.setStrategyId(STRATEGY);
    req.setBrokerTarget(LiveActivationRequest.BrokerTarget.LIVE);
    req.setOperatorId(OPERATOR);

    LiveActivationResult result = activateStub().activateLive(req);

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.ACTIVATED);
    assertThat(result.getExpectedAccountId()).isEqualTo(account2);

    ArgumentCaptor<LiveActivationRequest> captor =
        ArgumentCaptor.forClass(LiveActivationRequest.class);
    verify(promotion, times(1)).activate(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(tenant2);
    assertThat(captor.getValue().getExpectedAccountId()).isEqualTo(account2);
    assertThat(captor.getValue().getBrokerTarget())
        .isEqualTo(LiveActivationRequest.BrokerTarget.ALPACA_LIVE);

    // Verify-C: the AccountSnapshotRequest carries this tenant's non-blank tenant_id.
    ArgumentCaptor<AccountSnapshotRequest> snapCaptor =
        ArgumentCaptor.forClass(AccountSnapshotRequest.class);
    verify(snapshot).accountSnapshot(snapCaptor.capture());
    assertThat(snapCaptor.getValue().getTenantId()).isEqualTo(tenant2);
  }

  // ---- deactivation -------------------------------------------------------------------------

  @Test
  void deactivate_emitsDeactivation_andTripsKillSwitch() {
    when(strategy.get(TENANT, STRATEGY)).thenReturn(compliantConfig());

    LiveActivationResult result = deactivateStub().deactivateLive(deactivateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.DEACTIVATED);

    ArgumentCaptor<LiveDeactivationRequest> captor =
        ArgumentCaptor.forClass(LiveDeactivationRequest.class);
    verify(promotion, times(1)).deactivate(captor.capture());
    // The deactivation row carries the stored config's broker_target so the gate's probe matches.
    assertThat(captor.getValue().getBrokerTarget())
        .isEqualTo(LiveDeactivationRequest.BrokerTarget.ALPACA_LIVE);

    verify(gate, times(1))
        .tripKillSwitch(eq(TENANT), eq(STRATEGY), eq(OPERATOR), any(String.class));
  }

  @Test
  void deactivate_configMiss_skipsUnmatchableRow_butStillTripsKillSwitch() {
    // A config-read miss must NOT write a LivePromotionDeactivated row with the inbound placeholder
    // broker_target — it could never match the gate's probe (a silent, success-reporting no-op).
    // The
    // kill-switch trip is the real stop and must still fire.
    when(strategy.get(TENANT, STRATEGY)).thenReturn(null);

    LiveActivationResult result = deactivateStub().deactivateLive(deactivateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.DEACTIVATED);
    verify(promotion, never()).deactivate(any());
    verify(gate, times(1))
        .tripKillSwitch(eq(TENANT), eq(STRATEGY), eq(OPERATOR), any(String.class));
  }
}
