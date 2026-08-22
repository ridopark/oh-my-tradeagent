package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
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
  private TenantConfigActivities tenantConfig;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    strategy = mock(StrategyActivities.class);
    gate = mock(LiveActivationGateActivities.class);
    promotion = mock(LivePromotionActivities.class);
    snapshot = mock(AccountSnapshotActivity.class);
    tenantConfig = mock(TenantConfigActivities.class);
    // Phase 3b: at v>=1 (which TestWorkflowEnvironment always reports for fresh workflows) step (b)
    // reads the tenant account cap. Default to an ARMED cap so a -live compliantConfig passes; the
    // no-cap-armed case overrides these to null per-test.
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(LiveActivationWorkflowImpl.class);
    coreWorker.registerActivitiesImplementations(strategy, gate, promotion, tenantConfig);

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
  void noAccountCapArmed_isRejectedConfig_noActivate() {
    // Phase 3b: the tenant account cap is now the sole live loss breaker (step (b), v>=1). A -live
    // strategy whose tenant has NO armed account cap is REJECTED_CONFIG even with a per-strategy
    // daily_loss_threshold set on the config — the 4-arg invariant requires the account cap.
    when(strategy.get(TENANT, STRATEGY)).thenReturn(compliantConfig());
    when(tenantConfig.accountDailyLossPct(TENANT)).thenReturn(null);
    when(tenantConfig.accountDailyLossThreshold(TENANT)).thenReturn(null);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CONFIG);
    assertThat(result.getReason()).contains("account_daily_loss");
    verify(promotion, never()).activate(any());
  }

  @Test
  void noDailyLossThresholdButAccountCapArmed_activates_andReadsTenantCap() {
    // Phase 3b core case: a -live strategy with NO per-strategy daily_loss_threshold is now VALID
    // at
    // step (b) when the tenant account cap is armed (the armed cap satisfies the invariant). Proves
    // the v>=1 branch reads the cap via TenantConfigActivities and does NOT return REJECTED_CONFIG.
    StrategyConfig cfg = compliantConfig();
    cfg.setDailyLossThreshold(null);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(cfg);
    when(gate.killSwitchArmable(TENANT, STRATEGY)).thenReturn(true);
    when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class)))
        .thenReturn(snap(ACCOUNT, new BigDecimal("5000")));
    // default setUp stub: tenantConfig reports an armed pct cap.

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.ACTIVATED);
    verify(promotion, times(1)).activate(any());
    // v>=1 issues the two account-cap Activity commands (the proof they run at v>=1).
    verify(tenantConfig, times(1)).accountDailyLossPct(TENANT);
    verify(tenantConfig, times(1)).accountDailyLossThreshold(TENANT);
  }

  @Test
  void versionAccountCapAwareConstantNameIsStable() throws Exception {
    // Pins the version-marker constant so a rename fails loudly — a rename would silently
    // re-version
    // in-flight activations (the DEFAULT_VERSION legacy branch would no longer match old
    // histories).
    Field marker = LiveActivationWorkflowImpl.class.getDeclaredField("VERSION_ACCOUNT_CAP_AWARE");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("live-activation-account-cap-aware-v1");
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

  // ---- #780 static-capital cases (fresh executions run at static-capital v>=1) ----------------

  /** A compliant static-sizing config: like {@link #compliantConfig()} but static + a weight. */
  private StrategyConfig staticConfig(String weight) {
    StrategyConfig cfg = compliantConfig();
    cfg.setCapitalSource(StrategyConfig.CapitalSource.STATIC);
    cfg.setCapitalWeight(weight == null ? null : new BigDecimal(weight));
    return cfg;
  }

  private void staticGatesPass(String weight, String equity, String staticBase) {
    when(strategy.get(TENANT, STRATEGY)).thenReturn(staticConfig(weight));
    when(gate.killSwitchArmable(TENANT, STRATEGY)).thenReturn(true);
    AccountSnapshotResult s = snap(ACCOUNT, new BigDecimal("5000"));
    s.setEquity(equity == null ? null : new BigDecimal(equity));
    when(snapshot.accountSnapshot(any(AccountSnapshotRequest.class))).thenReturn(s);
    when(strategy.capitalForStrategy(TENANT, STRATEGY))
        .thenReturn(staticBase == null ? null : new BigDecimal(staticBase));
  }

  @Test
  void capitalSourceStatic_withinEquityBound_activates() {
    // The #780 cutover shape: base 100000 x weight 0.052 = 5200 allocation, ~10% of 52000 equity —
    // inside the 15% ceiling, so the previously hard-rejected static source now activates.
    staticGatesPass("0.052", "52000", "100000");

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.ACTIVATED);
    verify(promotion, times(1)).activate(any());
    verify(strategy, times(1)).capitalForStrategy(TENANT, STRATEGY);
  }

  @Test
  void capitalSourceStatic_exceedingEquityBound_isRejectedCapitalSource_noActivate() {
    // Old-style weight on the pod-global base: 100000 x 0.3 = 30000 against 52000 equity (57%) —
    // exactly the oversizing hazard the original account_cash-only gate existed to stop.
    staticGatesPass("0.3", "52000", "100000");

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE);
    assertThat(result.getReason()).contains("exceeds").contains("30000");
    verify(promotion, never()).activate(any());
  }

  @Test
  void capitalSourceStatic_nullWeight_isRejectedCapitalSource_noActivate() {
    staticGatesPass(null, "52000", "100000");

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE);
    assertThat(result.getReason()).contains("capital_weight");
    verify(promotion, never()).activate(any());
  }

  @Test
  void capitalSourceStatic_noProbedEquity_isRejectedCapitalSource_noActivate() {
    // Fail-closed: without a probed equity the allocation ceiling cannot be computed.
    staticGatesPass("0.052", null, "100000");

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE);
    assertThat(result.getReason()).contains("equity");
    verify(promotion, never()).activate(any());
  }

  @Test
  void capitalSourceStatic_noStaticBase_isRejectedCapitalSource_noActivate() {
    staticGatesPass("0.052", "52000", null);

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE);
    assertThat(result.getReason()).contains("static capital base");
    verify(promotion, never()).activate(any());
  }

  @Test
  void versionStaticCapitalConstantNameIsStable() throws Exception {
    // Same pin as VERSION_ACCOUNT_CAP_AWARE: a rename would silently re-version in-flight
    // activations.
    Field marker = LiveActivationWorkflowImpl.class.getDeclaredField("VERSION_STATIC_CAPITAL");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("live-activation-static-capital-v1");
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
  void allGatesPass_untripsKillSwitch_soStrategyResumes() {
    // Core proof: a successful one-click activate must UNTRIP the kill switch (a prior one-click
    // deactivate trips it) so the strategy actually resumes — otherwise the fresh promotion row is
    // inert and risk.check_entry stays fail-closed on tripped==true. Reset is single-operator,
    // attributed to the activating operator, and fired AFTER the promotion row is written.
    allGatesPass();

    LiveActivationResult result = activateStub().activateLive(activateReq());

    assertThat(result.getOutcome()).isEqualTo(LiveActivationResult.Outcome.ACTIVATED);
    verify(promotion, times(1)).activate(any());
    verify(gate, times(1)).resetKillSwitch(eq(TENANT), eq(STRATEGY), eq(OPERATOR));
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
