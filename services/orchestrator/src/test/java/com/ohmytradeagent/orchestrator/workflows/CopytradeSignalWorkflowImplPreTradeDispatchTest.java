package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.AccountSnapshotMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.RiskCollaboratorDefaults;
import com.ohmytradeagent.orchestrator.activities.SectorResolver;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * End-to-end Temporal tests verifying the workflow-side {@code PreTradeCheckActivity} dispatch and
 * the fail-closed sentinel that flows into {@link RiskActivities#checkEntry}.
 */
class CopytradeSignalWorkflowImplPreTradeDispatchTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private StrategyActivities strategy;
  private RiskActivities risk;
  private ContractActivities contract;
  private ExecActivities exec;
  private PositionLookupActivities positionLookup;
  private MarketCalendarActivities calendar;
  private SubscribePremiumActivity marketData;
  private AccountSnapshotMetricsActivities accountSnapshotMetrics;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(
        CopytradeSignalWorkflowImpl.class, PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    risk = Mockito.mock(RiskActivities.class);
    contract = Mockito.mock(ContractActivities.class);
    exec = Mockito.mock(ExecActivities.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    marketData = Mockito.mock(SubscribePremiumActivity.class);
    accountSnapshotMetrics = Mockito.mock(AccountSnapshotMetricsActivities.class);
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
    SubscribePremiumResult ok = new SubscribePremiumResult();
    ok.setSchemaVersion(1L);
    ok.setSubscriptionId("sub-test");
    ok.setSubscribedAt(OffsetDateTime.now());
    ok.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    when(marketData.subscribePremium(any())).thenReturn(ok);

    coreWorker.registerActivitiesImplementations(
        audit, strategy, risk, contract, positionLookup, calendar, accountSnapshotMetrics);
    Worker mdWorker = env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(marketData);
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void handleBto_dispatchesPreTradeCheckViaWorkflowStub_andPassesResultToCheckEntry() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    // Workflow's v=1 branch now calls checkEntryWithLimit.
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    AtomicInteger preTradeCalls = new AtomicInteger();
    AtomicReference<PreTradeCheckRequest> capturedRequest = new AtomicReference<>();
    PreTradeCheckActivity preTradeStub =
        request -> {
          preTradeCalls.incrementAndGet();
          capturedRequest.set(request);
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    runWorkflow(btoPayload());

    assertThat(preTradeCalls.get()).isEqualTo(1);
    PreTradeCheckRequest req = capturedRequest.get();
    assertThat(req).isNotNull();
    assertThat(req.getCorrelationId()).isEqualTo("111:0");
    assertThat(req.getOptionSymbol()).isEqualTo("NVDA");
    assertThat(req.getSide()).isEqualTo(PreTradeCheckRequest.Side.BUY);
    assertThat(req.getQty()).isEqualTo(1L); // min_contracts == 1 floor
    assertThat(req.getEstimatedNotional()).isEqualByComparingTo(new BigDecimal("230.00"));
    assertThat(req.getTenantId()).isEqualTo("dev");
    assertThat(req.getStrategyId()).isEqualTo("copytrade-v1");

    ArgumentCaptor<PreTradeCheckResult> resultCaptor =
        ArgumentCaptor.forClass(PreTradeCheckResult.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), resultCaptor.capture(), any(), any());
    PreTradeCheckResult captured = resultCaptor.getValue();
    assertThat(captured).isNotNull();
    assertThat(captured.getAllowed()).isTrue();

    verify(risk, Mockito.times(1)).assertPreTradeCheckRoutable(cfg);

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getSide()).isEqualTo(OrderIntent.Side.BUY);
  }

  /**
   * Issue #195: when {@code max_slippage_abs} / {@code max_slippage_pct} are configured, the {@code
   * PreTradeCheckRequest.estimatedNotional} dispatched to exec-svc must be computed against the
   * slip-adjusted limit (max-acceptable cost), not the unadjusted mirror price. The mirror /
   * no-caps back-compat path is covered by the {@code _dispatchesPreTradeCheckViaWorkflowStub_*}
   * test above (price=2.30 -> 230.00).
   *
   * <p>Incident fixture from the issue body: price=3.10, abs=0.05, pct=0.05 -> SLIP_MIN branch ->
   * limit=3.15 -> estimated_notional = 3.15 * 100 = 315.00.
   */
  @Test
  void handleBto_preTradeCheckEstimatedNotional_usesSlipAdjustedLimit_whenCapsSet() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setMaxSlippageAbs(new BigDecimal("0.05"));
    cfg.setMaxSlippagePct(new BigDecimal("0.05"));
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    AtomicReference<PreTradeCheckRequest> capturedRequest = new AtomicReference<>();
    PreTradeCheckActivity preTradeStub =
        request -> {
          capturedRequest.set(request);
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    CopytradeSignalPayload p = btoPayload();
    p.setPrice(new BigDecimal("3.10"));
    runWorkflow(p);

    PreTradeCheckRequest req = capturedRequest.get();
    assertThat(req).isNotNull();
    // SLIP_MIN: min(3.10+0.05, 3.10*1.05) = min(3.15, 3.255) = 3.15 -> 3.15 * 100 = 315.00.
    assertThat(req.getEstimatedNotional()).isEqualByComparingTo(new BigDecimal("315.00"));
  }

  /**
   * Pins the workflow-side wiring of the slip-adjusted limit through {@code
   * risk.checkEntryWithLimit(...)}. Mirrors the request-level test above but asserts at the
   * Activity-stub boundary that the v=1 branch invokes the new Activity method with {@code
   * priced.limit()} rather than re-deriving from the mirror price.
   *
   * <p>Same SLIP_MIN incident fixture: price=3.10, abs=0.05, pct=0.05 → limit=3.15.
   */
  @Test
  void handleBto_riskCheckEntryWithLimit_receivesSlipAdjustedLimit() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setMaxSlippageAbs(new BigDecimal("0.05"));
    cfg.setMaxSlippagePct(new BigDecimal("0.05"));
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    CopytradeSignalPayload p = btoPayload();
    p.setPrice(new BigDecimal("3.10"));
    runWorkflow(p);

    ArgumentCaptor<BigDecimal> limitCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), any(), limitCaptor.capture(), any());
    assertThat(limitCaptor.getValue()).isEqualByComparingTo(new BigDecimal("3.15"));
  }

  @Test
  void handleBto_failsClosed_whenPreTradeCheckActivityThrows() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(
            RiskDecision.rejected(
                RejectionReason.PRE_TRADE_CHECK_FAILED,
                "allowed=false reason=dispatch_failed:RuntimeException"));

    PreTradeCheckActivity throwingStub =
        request -> {
          throw new RuntimeException("svc timeout");
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, throwingStub);
    env.start();

    runWorkflow(btoPayload());

    // Note: Temporal wraps the activity's RuntimeException in ActivityFailure once retries are
    // exhausted, so the simple-class-name in the sentinel reason is "ActivityFailure", not
    // "RuntimeException". The dispatch_failed prefix is the stable contract.
    ArgumentCaptor<PreTradeCheckResult> resultCaptor =
        ArgumentCaptor.forClass(PreTradeCheckResult.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), resultCaptor.capture(), any(), any());
    PreTradeCheckResult sentinel = resultCaptor.getValue();
    assertThat(sentinel).isNotNull();
    assertThat(sentinel.getAllowed()).isFalse();
    assertThat(sentinel.getRejectReason()).startsWith("dispatch_failed:");

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "PRE_TRADE_CHECK_FAILED");
    assertThat(rejected.getSubject()).containsEntry("outcome", "REJECTED");

    Mockito.verify(exec, Mockito.never()).placeOrder(any());
  }

  /**
   * Issue #115 (tightened post-#157): pins the {@code maxAttempts × startToCloseTimeout} retry
   * budget on the {@code PreTradeCheckActivity} stub. With {@code startToCloseTimeout=15s} × {@code
   * maxAttempts=3} ≈ 45s of pure run time plus exponential-backoff jitter, the {@code maxAttempts}
   * budget is the actual binding constraint on dispatch latency before the fail-closed sentinel is
   * produced. The {@code scheduleToCloseTimeout=60s} on the production stub is the absolute
   * backstop (a hard cap if jitter overruns), but in practice the test never reaches it.
   *
   * <p>The invariant asserted is the {@code PreTradeCheckActivity} attempt count: Temporal enforces
   * exactly {@code maxAttempts=3} invocations of the always-throwing stub before the workflow fails
   * closed to the {@code dispatch_failed} sentinel — proving the retry is bounded, not forever. The
   * attempt count is unaffected by {@link TestWorkflowEnvironment} auto-time-skip, so it cannot
   * exhibit the virtual-clock over-read that made the prior wall-clock latency assertion flaky
   * under CI load (the skip could advance to the 10-year workflow-execution-timeout).
   */
  @Test
  void handleBto_failsClosed_withinMaxAttemptsRetryBudget_whenPreTradeCheckActivityAlwaysThrows() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(
            RiskDecision.rejected(
                RejectionReason.PRE_TRADE_CHECK_FAILED,
                "allowed=false reason=dispatch_failed:ActivityFailure"));

    AtomicInteger preTradeAttempts = new AtomicInteger();
    PreTradeCheckActivity alwaysThrowingStub =
        request -> {
          preTradeAttempts.incrementAndGet();
          throw new RuntimeException("svc timeout");
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, alwaysThrowingStub);
    env.start();

    runWorkflow(btoPayload());

    ArgumentCaptor<PreTradeCheckResult> resultCaptor =
        ArgumentCaptor.forClass(PreTradeCheckResult.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), resultCaptor.capture(), any(), any());
    PreTradeCheckResult sentinel = resultCaptor.getValue();
    assertThat(sentinel).isNotNull();
    assertThat(sentinel.getAllowed()).isFalse();
    assertThat(sentinel.getRejectReason()).startsWith("dispatch_failed:");
    Mockito.verify(exec, Mockito.never()).placeOrder(any());

    assertThat(capture("SignalRejected").getSubject())
        .containsEntry("reason_code", "PRE_TRADE_CHECK_FAILED")
        .containsEntry("outcome", "REJECTED");

    // The stub is invoked exactly maxAttempts (3) times, then the workflow fails closed to the
    // dispatch_failed sentinel. The attempt count is the real invariant and Temporal enforces it
    // regardless of TestWorkflowEnvironment time-skip, so it does not depend on the skippable
    // virtual clock — which under CI load could over-advance to the 10-year workflow-execution
    // timeout and spuriously fail the old wall-clock budget assertion.
    assertThat(preTradeAttempts.get())
        .as(
            "PreTradeCheck must retry exactly maxAttempts (3) then fail closed — bounded, not forever")
        .isEqualTo(3);
  }

  @Test
  void handleBto_failsClosed_whenBrokerTargetIsNull() {
    // Verifies the sentinel shape that dispatchPreTradeCheck builds when getBrokerTarget() is null.
    // Because process() also dereferences getBrokerTarget() before reaching dispatchPreTradeCheck,
    // the workflow-level path cannot be exercised end-to-end via TestWorkflowEnvironment without
    // the NPE causing the workflow task to retry indefinitely. The direct unit coverage of the
    // sentinel-building helper lives in PreTradeCheckSentinelsTest (issue #113); this test keeps
    // the end-to-end intent assertion (sentinel + risk.checkEntry -> PRE_TRADE_CHECK_FAILED).
    StrategyConfig cfgNull = configWithPreTradeEnabled();
    cfgNull.setBrokerTarget(null);

    PreTradeCheckResult sentinel = new PreTradeCheckResult();
    sentinel.setSchemaVersion(1L);
    sentinel.setAllowed(false);
    sentinel.setRejectReason("dispatch_failed:NullBrokerTarget");

    assertThat(sentinel.getAllowed()).isFalse();
    assertThat(sentinel.getRejectReason()).startsWith("dispatch_failed:NullBrokerTarget");

    // risk.checkEntry with this sentinel must surface PRE_TRADE_CHECK_FAILED (allowed=false path).
    when(risk.checkEntry(any(), any(), any()))
        .thenReturn(
            RiskDecision.rejected(
                RejectionReason.PRE_TRADE_CHECK_FAILED,
                "allowed=false reason=dispatch_failed:NullBrokerTarget"));

    RiskDecision d = risk.checkEntry(btoPayload(), cfgNull, sentinel);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
  }

  /**
   * Issue #112: Verifies the {@code Workflow.getVersion("pre-trade-dispatch-v2", ...)} gate added
   * to {@link CopytradeSignalWorkflowImpl#handleBto} so that pre-#111 in-flight executions can
   * replay deterministically.
   *
   * <p>This test exercises the {@code v >= 1} branch — which is the only branch {@link
   * TestWorkflowEnvironment} can reach for a fresh workflow, since the test env always reports
   * {@code getVersion(...) == 1}. The legacy {@code v == DEFAULT_VERSION} branch (single {@code
   * checkEntry(payload, config, null)} call) is exercised only by replays of histories that began
   * before the patch was deployed and is not covered by a recorded-history {@code WorkflowReplayer}
   * fixture in this PR (explicit out-of-scope per the issue plan; the deterministic if/else
   * structure plus code review is the gate for the legacy branch).
   *
   * <p>The marker-string assertion below pins the contract: changing {@code
   * VERSION_PRE_TRADE_DISPATCH} after the patch is deployed would re-introduce the nondeterminism
   * the gate was added to prevent.
   */
  @Test
  void handleBto_versionGate_v1_dispatchesPreTradeAndPassesNonNullResultToCheckEntry()
      throws Exception {
    Field marker = CopytradeSignalWorkflowImpl.class.getDeclaredField("VERSION_PRE_TRADE_DISPATCH");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("pre-trade-dispatch-v2");

    StrategyConfig cfg = configWithPreTradeEnabled();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setMarginSufficient(true);
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    runWorkflow(btoPayload());

    // v=1 branch contract (issue #198): assertPreTradeCheckRoutable fires AND checkEntryWithLimit
    // receives a non-null PreTradeCheckResult (i.e. dispatchPreTradeCheck ran). The legacy
    // v=DEFAULT_VERSION branch would have skipped both and called checkEntry(..., null) instead.
    verify(risk, Mockito.times(1)).assertPreTradeCheckRoutable(cfg);
    ArgumentCaptor<PreTradeCheckResult> resultCaptor =
        ArgumentCaptor.forClass(PreTradeCheckResult.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), resultCaptor.capture(), any(), any());
    assertThat(resultCaptor.getValue()).isNotNull();
  }

  /**
   * Issue #114: End-to-end pin for {@link RiskActivitiesImpl#assertPreTradeCheckRoutable} —
   * verifies the non-retryable {@link ApplicationFailure} thrown from inside the workflow body
   * surfaces as the cause of {@link WorkflowFailedException} (i.e. the assertion is not retried and
   * not swallowed at the workflow boundary), and that no order is placed.
   *
   * <p>Unit-level coverage of the assertion itself lives in {@code RiskActivitiesAssertionTest};
   * this test pins the workflow-level contract by wiring a <b>real</b> {@link RiskActivitiesImpl}
   * with the {@link RiskCollaboratorDefaults#permissivePreTradeCheck() permissive default bean}
   * instead of the shared {@code Mockito.mock(RiskActivities.class)} used by the other tests.
   */
  @Test
  void handleBto_workflowFails_whenPreTradeCheckEnabled_andOnlyPermissiveDefaultBeanWired() {
    // Close the shared env from setUp(); this test builds its own with a real RiskActivitiesImpl.
    env.close();

    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      Worker coreWorker = localEnv.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(
          CopytradeSignalWorkflowImpl.class, PositionWorkflowImpl.class);

      RiskActivitiesImpl realRisk = realRiskWithPermissiveDefaultBean();
      StrategyActivities localStrategy = Mockito.mock(StrategyActivities.class);
      AuditActivities localAudit = Mockito.mock(AuditActivities.class);
      ContractActivities localContract = Mockito.mock(ContractActivities.class);
      ExecActivities localExec = Mockito.mock(ExecActivities.class);
      PositionLookupActivities localPositionLookup = Mockito.mock(PositionLookupActivities.class);
      MarketCalendarActivities localCalendar = Mockito.mock(MarketCalendarActivities.class);
      SubscribePremiumActivity localMarketData = Mockito.mock(SubscribePremiumActivity.class);
      when(localCalendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(localCalendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);

      StrategyConfig cfg = configWithPreTradeEnabled();
      when(localStrategy.get("dev", "copytrade-v1")).thenReturn(cfg);

      coreWorker.registerActivitiesImplementations(
          localAudit, localStrategy, realRisk, localContract, localPositionLookup, localCalendar);

      // PreTradeCheckActivity stub on the broker worker — never invoked because the assertion in
      // the workflow body fires before dispatch. Required only so the worker can start.
      PreTradeCheckActivity noopPreTradeStub = request -> null;
      Worker brokerWorker =
          localEnv.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
      brokerWorker.registerActivitiesImplementations(localExec, noopPreTradeStub);
      Worker mdWorker = localEnv.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      mdWorker.registerActivitiesImplementations(localMarketData);

      localEnv.start();

      CopytradeSignalWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  CopytradeSignalWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());

      // Pins the stable contract: WorkflowFailedException thrown with a non-retryable
      // ApplicationFailure of type PreTradeCheckMisconfigured somewhere in its cause chain.
      // Wrapping depth is runtime-dependent (see unwrapApplicationFailure).
      assertThatThrownBy(() -> wf.process(btoPayload()))
          .isInstanceOf(WorkflowFailedException.class)
          .satisfies(
              t -> {
                Throwable cause = t.getCause();
                ApplicationFailure af = unwrapApplicationFailure(cause);
                assertThat(af).isNotNull();
                assertThat(af.getType()).isEqualTo("PreTradeCheckMisconfigured");
                assertThat(af.isNonRetryable()).isTrue();
                assertThat(af.getOriginalMessage()).contains("dev").contains("copytrade-v1");
              });

      // Proves the workflow terminated rather than retrying past the assertion.
      Mockito.verify(localExec, Mockito.never()).placeOrder(any());
    } finally {
      localEnv.close();
    }
  }

  /**
   * Issue #317/#323: when {@code notional_cap_pct_of_equity} is enabled, the v=1 branch dispatches
   * the cross-service {@code AccountSnapshotActivity} over the {@code broker-<broker_target>} queue
   * and threads the returned <b>cash</b> (the cash component of the #323 cost-basis capital base
   * {@code cash + sum_open_notional}) down into {@code risk.checkEntryWithLimit(...)}. Pins both
   * the dispatch (request keyed solely on broker_target) and the carry-over to the risk gate's 5th
   * arg.
   */
  @Test
  void handleBto_dispatchesAccountSnapshot_andThreadsEquityIntoCheckEntryWithLimit() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // enables the account-snapshot dispatch
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    AtomicInteger accountCalls = new AtomicInteger();
    AtomicReference<AccountSnapshotRequest> capturedAccountReq = new AtomicReference<>();
    AccountSnapshotActivity accountStub =
        request -> {
          accountCalls.incrementAndGet();
          capturedAccountReq.set(request);
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setEquity(new BigDecimal("999999.99"));
          // #323: the workflow threads CASH (not net-liq equity) into the cap gate.
          r.setCash(new BigDecimal("123456.78"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, accountStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    runWorkflow(btoPayload());

    // Account snapshot was dispatched exactly once, keyed solely on broker_target (no
    // tenant/strat).
    assertThat(accountCalls.get()).isEqualTo(1);
    AccountSnapshotRequest accReq = capturedAccountReq.get();
    assertThat(accReq).isNotNull();
    assertThat(accReq.getBrokerTarget())
        .isEqualTo(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);
    assertThat(accReq.getCorrelationId()).isEqualTo("111:0");

    // The broker-supplied cash (#323 capital-base component) is threaded into the risk gate's 5th
    // argument — NOT the net-liq equity (999999.99), proving the cap reads cash.
    ArgumentCaptor<BigDecimal> equityCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), any(), any(), equityCaptor.capture());
    assertThat(equityCaptor.getValue()).isEqualByComparingTo(new BigDecimal("123456.78"));
  }

  /**
   * When the {@code AccountSnapshotActivity} throws (after Temporal exhausts its retries), {@code
   * dispatchAccountSnapshot} fails closed to {@code BigDecimal.ZERO}, which threads into the risk
   * gate's 5th arg. The notional-cap gate rejects on zero/unavailable equity, so the workflow emits
   * a {@code SignalRejected} ({@code NOTIONAL_CAP_EXCEEDED}) audit rather than throwing — pinning
   * the catch -> ZERO -> gate-rejects path end-to-end so a broker outage rejects entries instead of
   * passing an unbounded cap.
   */
  @Test
  void handleBto_failsClosed_whenAccountSnapshotActivityThrows() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // enables the account-snapshot dispatch
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    // Mirror the real notional-cap gate's fail-closed behaviour: zero/unavailable equity rejects.
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenAnswer(
            inv -> {
              BigDecimal equity = inv.getArgument(4);
              return equity != null && equity.signum() > 0
                  ? RiskDecision.approved()
                  : RiskDecision.rejected(
                      RejectionReason.NOTIONAL_CAP_EXCEEDED, "cash_unavailable");
            });

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    AccountSnapshotActivity throwingAccountStub =
        request -> {
          throw new RuntimeException("broker /v2/account timeout");
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, throwingAccountStub);
    env.start();

    runWorkflow(btoPayload());

    // The swallowed exception fails closed to ZERO equity, threaded into the risk gate's 5th arg.
    ArgumentCaptor<BigDecimal> equityCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(risk).checkEntryWithLimit(any(), eq(cfg), any(), any(), equityCaptor.capture());
    assertThat(equityCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);

    // The gate rejects on the unavailable equity -> fail-closed SignalRejected audit, no order.
    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "NOTIONAL_CAP_EXCEEDED");
    assertThat(rejected.getSubject()).containsEntry("outcome", "REJECTED");
    Mockito.verify(exec, Mockito.never()).placeOrder(any());

    // #323 observability: the non-CanceledFailure catch branch increments the symmetric
    // accountsnapshot_dispatch_failures_total counter exactly once via the metrics Activity,
    // keyed on broker_target — so a persistent broker outage is distinguishable in metrics from a
    // legitimate zero-cash account.
    Mockito.verify(accountSnapshotMetrics, Mockito.times(1))
        .recordDispatchFailure(StrategyConfig.BrokerTarget.ALPACA_PAPER.value());
  }

  /**
   * Issue #336 regression guard: a config that sets ONLY the canonical {@code
   * notional_cap_pct_of_capital_base} (the {@code notional_cap_pct_of_equity} alias null — the
   * migration end-state) MUST still dispatch the {@code AccountSnapshotActivity} and run the cap
   * against the real broker cash. Pre-fix the workflow's dispatch guard tested {@code
   * getNotionalCapPctOfEquity() == null} only, so this config returned null cash, the snapshot
   * never fired, and {@code checkNotionalCap} rejected EVERY BTO with {@code cash_unavailable} (the
   * new field was non-functional). This test wires a <b>real</b> {@link RiskActivitiesImpl} so
   * {@code checkNotionalCap} actually evaluates — asserting the snapshot IS dispatched and the cap
   * APPROVES on the math with the threaded cash (order placed), NOT a blanket {@code
   * cash_unavailable} reject. {@code RiskActivitiesNotionalCapResolverTest} passes cash straight
   * into checkEntryWithLimit and structurally cannot catch this guard gap.
   */
  @Test
  void handleBto_newOnlyCapBaseConfig_dispatchesAccountSnapshot_andCapEvaluatesWithRealCash() {
    env.close(); // rebuild with a real RiskActivitiesImpl so checkNotionalCap runs for real.
    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      Worker coreWorker = localEnv.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(
          CopytradeSignalWorkflowImpl.class, PositionWorkflowImpl.class);

      RiskActivitiesImpl realRisk = realRiskWithUntrippedKillSwitch();
      StrategyActivities localStrategy = Mockito.mock(StrategyActivities.class);
      AuditActivities localAudit = Mockito.mock(AuditActivities.class);
      ContractActivities localContract = Mockito.mock(ContractActivities.class);
      ExecActivities localExec = Mockito.mock(ExecActivities.class);
      PositionLookupActivities localPositionLookup = Mockito.mock(PositionLookupActivities.class);
      MarketCalendarActivities localCalendar = Mockito.mock(MarketCalendarActivities.class);
      SubscribePremiumActivity localMarketData = Mockito.mock(SubscribePremiumActivity.class);
      AccountSnapshotMetricsActivities localMetrics =
          Mockito.mock(AccountSnapshotMetricsActivities.class);
      when(localCalendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(localCalendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
      SubscribePremiumResult ok = new SubscribePremiumResult();
      ok.setSchemaVersion(1L);
      ok.setSubscriptionId("sub-test");
      ok.setSubscribedAt(OffsetDateTime.now());
      ok.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
      when(localMarketData.subscribePremium(any())).thenReturn(ok);

      // NEW-ONLY config: canonical capital_base set, deprecated equity alias null. preTradeCheck
      // disabled so the gate runs purely on the notional-cap math against the dispatched cash.
      StrategyConfig cfg = configWithPreTradeEnabled();
      cfg.setPreTradeCheckEnabled(false);
      cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.50"));
      cfg.setNotionalCapPctOfEquity(null);
      when(localStrategy.get("dev", "copytrade-v1")).thenReturn(cfg);
      when(localStrategy.capitalForStrategy("dev", "copytrade-v1"))
          .thenReturn(new BigDecimal("100000"));
      when(localContract.resolve(any())).thenReturn(resolved());
      when(localExec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
      when(localExec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));

      coreWorker.registerActivitiesImplementations(
          localAudit,
          localStrategy,
          realRisk,
          localContract,
          localPositionLookup,
          localCalendar,
          localMetrics);

      PreTradeCheckActivity preTradeStub =
          request -> {
            PreTradeCheckResult r = new PreTradeCheckResult();
            r.setSchemaVersion(1L);
            r.setAllowed(true);
            r.setBuyingPower(new BigDecimal("50000"));
            r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
            r.setMarginSufficient(true);
            return r;
          };
      AtomicInteger accountCalls = new AtomicInteger();
      AccountSnapshotActivity accountStub =
          request -> {
            accountCalls.incrementAndGet();
            AccountSnapshotResult r = new AccountSnapshotResult();
            r.setSchemaVersion(1L);
            r.setCash(new BigDecimal("123456.78")); // real cash -> cap = 0.50 * 123456.78
            return r;
          };
      Worker brokerWorker =
          localEnv.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
      brokerWorker.registerActivitiesImplementations(localExec, preTradeStub, accountStub);
      Worker mdWorker = localEnv.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      mdWorker.registerActivitiesImplementations(localMarketData);
      localEnv.start();

      localEnv
          .getWorkflowClient()
          .newWorkflowStub(
              CopytradeSignalWorkflow.class,
              WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build())
          .process(btoPayload());

      // The cap is enabled by the canonical field alone -> the snapshot fired (the regression: it
      // did not, before the fix).
      assertThat(accountCalls.get())
          .as("AccountSnapshot must dispatch when only notional_cap_pct_of_capital_base is set")
          .isEqualTo(1);

      // The cap EVALUATED on the math with real cash (230 projected << 0.50*123456.78 cap) and
      // APPROVED — NOT the blanket cash_unavailable reject the pre-fix guard produced. Proven by an
      // accepted signal + a placed order.
      ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
      verify(localAudit, atLeastOnce()).log(auditCaptor.capture());
      assertThat(auditCaptor.getAllValues())
          .noneSatisfy(
              e ->
                  assertThat(e.getKind())
                      .as("must not reject; cap should evaluate with real cash")
                      .isEqualTo("SignalRejected"));
      assertThat(auditCaptor.getAllValues())
          .anySatisfy(e -> assertThat(e.getKind()).isEqualTo("SignalAccepted"));
      Mockito.verify(localExec).placeOrder(any());
    } finally {
      localEnv.close();
    }
  }

  /**
   * Issue #336 regression guard (companion to the approve case): a NEW-ONLY {@code
   * notional_cap_pct_of_capital_base} config with a tiny cash term must reject on the cap MATH
   * ({@code notional=.. cap=..}), proving the gate truly evaluated against the dispatched cash —
   * NOT the blanket {@code cash_unavailable} sentinel a non-dispatched (null-cash) gate emits.
   */
  @Test
  void handleBto_newOnlyCapBaseConfig_capMathRejects_withRealCash_notCashUnavailable() {
    env.close();
    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      Worker coreWorker = localEnv.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(
          CopytradeSignalWorkflowImpl.class, PositionWorkflowImpl.class);

      RiskActivitiesImpl realRisk = realRiskWithUntrippedKillSwitch();
      StrategyActivities localStrategy = Mockito.mock(StrategyActivities.class);
      AuditActivities localAudit = Mockito.mock(AuditActivities.class);
      ContractActivities localContract = Mockito.mock(ContractActivities.class);
      ExecActivities localExec = Mockito.mock(ExecActivities.class);
      PositionLookupActivities localPositionLookup = Mockito.mock(PositionLookupActivities.class);
      MarketCalendarActivities localCalendar = Mockito.mock(MarketCalendarActivities.class);
      SubscribePremiumActivity localMarketData = Mockito.mock(SubscribePremiumActivity.class);
      AccountSnapshotMetricsActivities localMetrics =
          Mockito.mock(AccountSnapshotMetricsActivities.class);
      when(localCalendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(localCalendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);

      StrategyConfig cfg = configWithPreTradeEnabled();
      cfg.setPreTradeCheckEnabled(false);
      cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.50"));
      cfg.setNotionalCapPctOfEquity(null);
      when(localStrategy.get("dev", "copytrade-v1")).thenReturn(cfg);
      when(localStrategy.capitalForStrategy("dev", "copytrade-v1"))
          .thenReturn(new BigDecimal("100000"));
      when(localContract.resolve(any())).thenReturn(resolved());

      coreWorker.registerActivitiesImplementations(
          localAudit,
          localStrategy,
          realRisk,
          localContract,
          localPositionLookup,
          localCalendar,
          localMetrics);

      PreTradeCheckActivity preTradeStub =
          request -> {
            PreTradeCheckResult r = new PreTradeCheckResult();
            r.setSchemaVersion(1L);
            r.setAllowed(true);
            r.setBuyingPower(new BigDecimal("50000"));
            r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
            r.setMarginSufficient(true);
            return r;
          };
      // Tiny cash -> cap = 0.50 * 100 = 50, projected 1-contract notional 230 > 50 -> math reject.
      AccountSnapshotActivity accountStub =
          request -> {
            AccountSnapshotResult r = new AccountSnapshotResult();
            r.setSchemaVersion(1L);
            r.setCash(new BigDecimal("100"));
            return r;
          };
      Worker brokerWorker =
          localEnv.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
      brokerWorker.registerActivitiesImplementations(localExec, preTradeStub, accountStub);
      localEnv.start();

      localEnv
          .getWorkflowClient()
          .newWorkflowStub(
              CopytradeSignalWorkflow.class,
              WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build())
          .process(btoPayload());

      ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
      verify(localAudit, atLeastOnce()).log(auditCaptor.capture());
      AuditEvent rejected =
          auditCaptor.getAllValues().stream()
              .filter(e -> "SignalRejected".equals(e.getKind()))
              .reduce((a, b) -> b)
              .orElseThrow(() -> new AssertionError("expected a SignalRejected audit"));
      assertThat(rejected.getSubject()).containsEntry("reason_code", "NOTIONAL_CAP_EXCEEDED");
      // The cap MATH ran against the dispatched cash -> reason_detail is the notional/cap pair, NOT
      // the cash_unavailable sentinel the pre-fix (null-cash) path emitted.
      assertThat((String) rejected.getSubject().get("reason_detail"))
          .startsWith("notional=")
          .contains("cap=")
          .doesNotContain("cash_unavailable");
      Mockito.verify(localExec, Mockito.never()).placeOrder(any());
    } finally {
      localEnv.close();
    }
  }

  /**
   * Issue #323: a {@link CanceledFailure} (workflow cancellation surfacing through the {@code
   * AccountSnapshotActivity} call) must re-throw from {@code dispatchAccountSnapshot} rather than
   * fail closed to ZERO — and must NOT touch the dispatch-failure counter (cancellation is not a
   * broker degradation). Pins the {@code catch (CanceledFailure cf) { throw cf; }} branch:
   * cancelling the running workflow during the (deliberately slow) account snapshot aborts it, and
   * the counter Activity is never invoked.
   */
  @Test
  void dispatchAccountSnapshot_canceledFailurePropagates_andDoesNotIncrementCounter()
      throws Exception {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // enables the account-snapshot dispatch
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    // Block in the account snapshot long enough for the cancel to land while the activity call is
    // in flight, so CanceledFailure is thrown into the workflow at the dispatchAccountSnapshot
    // call.
    AccountSnapshotActivity slowAccountStub =
        request -> {
          try {
            Thread.sleep(60_000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setCash(new BigDecimal("123456.78"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, slowAccountStub);
    env.start();

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    WorkflowStub stub = WorkflowStub.fromTyped(wf);
    stub.start(btoPayload());
    // Let the workflow reach the in-flight account-snapshot activity, then cancel.
    env.sleep(Duration.ofSeconds(5));
    stub.cancel();

    assertThatThrownBy(() -> stub.getResult(Void.class))
        .isInstanceOf(WorkflowFailedException.class);

    // CanceledFailure rethrows — it is NOT the broker-degradation path, so the counter never fires.
    Mockito.verify(accountSnapshotMetrics, Mockito.never()).recordDispatchFailure(anyString());
    Mockito.verify(exec, Mockito.never()).placeOrder(any());
  }

  /**
   * Issue #323: the metrics emit inside {@code dispatchAccountSnapshot}'s fail-closed catch is
   * wrapped in a non-fatal {@code catch (RuntimeException metricsError)} so a metrics outage cannot
   * flip the fail-closed ZERO outcome. But {@code CanceledFailure} extends {@code
   * RuntimeException}, so if workflow cancellation lands while the metrics counter Activity is in
   * flight, that catch would swallow the cancellation and delay it. The guard {@code if
   * (metricsError instanceof CanceledFailure cf) throw cf;} re-throws instead. Pins it: the
   * account-snapshot Activity throws (entering the fail-closed catch), the (deliberately slow)
   * metrics Activity is in flight when the workflow is cancelled, and the resulting CanceledFailure
   * propagates (workflow fails) rather than being swallowed into a ZERO fail-closed completion.
   */
  @Test
  void dispatchAccountSnapshot_metricsEmitCanceledFailurePropagates() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // enables the account-snapshot dispatch
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    // Account snapshot fails -> the workflow enters dispatchAccountSnapshot's fail-closed catch and
    // dispatches the metrics counter Activity.
    AccountSnapshotActivity throwingAccountStub =
        request -> {
          throw new RuntimeException("broker /v2/account timeout");
        };
    // Block in the metrics counter Activity long enough for the cancel to land while that Activity
    // call is in flight, so CanceledFailure is thrown into the workflow at the
    // recordDispatchFailure
    // call — exactly inside the catch (RuntimeException metricsError) block.
    doAnswer(
            inv -> {
              Thread.sleep(60_000L);
              return null;
            })
        .when(accountSnapshotMetrics)
        .recordDispatchFailure(anyString());

    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, throwingAccountStub);
    env.start();

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    WorkflowStub stub = WorkflowStub.fromTyped(wf);
    stub.start(btoPayload());
    // Let the workflow reach the in-flight metrics counter Activity, then cancel.
    env.sleep(Duration.ofSeconds(5));
    stub.cancel();

    // The guard re-throws CanceledFailure rather than swallowing it: the workflow fails (cancels)
    // instead of swallowing the cancellation and completing on the ZERO fail-closed path.
    assertThatThrownBy(() -> stub.getResult(Void.class))
        .isInstanceOf(WorkflowFailedException.class);
    Mockito.verify(exec, Mockito.never()).placeOrder(any());
  }

  // ----- Phase 2 (PLAN-2026-07-06): top-level failure-audit alert -----

  /**
   * Phase 2 reproduction of the 2026-07-06 incident: with {@code pre_trade_check_enabled=true} and
   * only the permissive-default {@code PreTradeCheckActivity} bean wired, {@code
   * assertPreTradeCheckRoutable} throws a non-retryable {@code PreTradeCheckMisconfigured} {@link
   * ApplicationFailure} BEFORE any audit is written. The Phase 2 top-level catch must emit an
   * alertable {@code EntryWorkflowFailed} audit (carrying {@code signal_id} + the misconfig
   * forensics) AND still let the workflow FAIL (we add visibility, never swallow). Pre-Phase-2 this
   * failure black-holed with only a "Signal received" Discord message and no page.
   */
  @Test
  void process_emitsEntryWorkflowFailedAudit_andStillFails_whenPreTradeCheckMisconfigured_phase2() {
    env.close();
    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      Worker coreWorker = localEnv.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(
          CopytradeSignalWorkflowImpl.class, PositionWorkflowImpl.class);

      RiskActivitiesImpl realRisk = realRiskWithPermissiveDefaultBean();
      StrategyActivities localStrategy = Mockito.mock(StrategyActivities.class);
      AuditActivities localAudit = Mockito.mock(AuditActivities.class);
      ContractActivities localContract = Mockito.mock(ContractActivities.class);
      ExecActivities localExec = Mockito.mock(ExecActivities.class);
      PositionLookupActivities localPositionLookup = Mockito.mock(PositionLookupActivities.class);
      MarketCalendarActivities localCalendar = Mockito.mock(MarketCalendarActivities.class);
      when(localCalendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(localCalendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);

      StrategyConfig cfg = configWithPreTradeEnabled();
      when(localStrategy.get("dev", "copytrade-v1")).thenReturn(cfg);

      coreWorker.registerActivitiesImplementations(
          localAudit, localStrategy, realRisk, localContract, localPositionLookup, localCalendar);

      PreTradeCheckActivity noopPreTradeStub = request -> null;
      Worker brokerWorker =
          localEnv.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
      brokerWorker.registerActivitiesImplementations(localExec, noopPreTradeStub);
      Worker mdWorker = localEnv.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
      mdWorker.registerActivitiesImplementations(Mockito.mock(SubscribePremiumActivity.class));

      localEnv.start();

      CopytradeSignalWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  CopytradeSignalWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());

      // Still FAILS — the workflow terminates FAILED with the non-retryable ApplicationFailure in
      // its cause chain (we only added visibility).
      assertThatThrownBy(() -> wf.process(btoPayload()))
          .isInstanceOf(WorkflowFailedException.class)
          .satisfies(
              t -> {
                ApplicationFailure af = unwrapApplicationFailure(t.getCause());
                assertThat(af).isNotNull();
                assertThat(af.getType()).isEqualTo("PreTradeCheckMisconfigured");
                assertThat(af.isNonRetryable()).isTrue();
              });

      // AND the alertable EntryWorkflowFailed audit was emitted BEFORE the re-throw, carrying the
      // signal_id (stitches to the Discord "Signal received") + the misconfig reason forensics.
      ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
      verify(localAudit, atLeastOnce()).log(captor.capture());
      AuditEvent failed =
          captor.getAllValues().stream()
              .filter(e -> "EntryWorkflowFailed".equals(e.getKind()))
              .reduce((a, b) -> b)
              .orElseThrow(() -> new AssertionError("expected an EntryWorkflowFailed audit"));
      assertThat(failed.getSubject()).containsEntry("signal_id", "111:0");
      assertThat(failed.getSubject()).containsEntry("reason_code", "PreTradeCheckMisconfigured");
      assertThat(failed.getSubject()).containsEntry("outcome", "FAILED");
      assertThat((String) failed.getSubject().get("reason_detail"))
          .contains("dev")
          .contains("copytrade-v1");
      Mockito.verify(localExec, Mockito.never()).placeOrder(any());
    } finally {
      localEnv.close();
    }
  }

  /**
   * Phase 2 no-false-positive: a normal successful entry (order placed) must NOT emit an {@code
   * EntryWorkflowFailed} audit — the top-level catch fires only on genuine unhandled failures.
   */
  @Test
  void process_successPath_emitsNoEntryWorkflowFailedAudit_phase2() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setMarginSufficient(true);
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    runWorkflow(btoPayload());

    verify(exec).placeOrder(any());
    verify(audit, Mockito.never())
        .log(Mockito.argThat(e -> e != null && "EntryWorkflowFailed".equals(e.getKind())));
  }

  /**
   * Phase 2 no-false-positive: a normal {@code SignalRejected} control-flow path (here: strategy
   * disabled, which RETURNS rather than throwing) must NOT emit an {@code EntryWorkflowFailed}
   * audit. Only genuine unhandled failures — not handled rejections — hit the top-level catch.
   */
  @Test
  void process_signalRejectedPath_emitsNoEntryWorkflowFailedAudit_phase2() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setEnabled(false); // Phase 7 enable-gate: SignalRejected(STRATEGY_DISABLED), returns.
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    env.start();

    runWorkflow(btoPayload());

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "STRATEGY_DISABLED");
    verify(audit, Mockito.never())
        .log(Mockito.argThat(e -> e != null && "EntryWorkflowFailed".equals(e.getKind())));
    Mockito.verify(exec, Mockito.never()).placeOrder(any());
  }

  /**
   * Phase 2 CanceledFailure carve-out: workflow cancellation surfacing into {@code process()} must
   * propagate untouched (mirrors the dispatchAccountSnapshot carve-out) and must NOT emit an {@code
   * EntryWorkflowFailed} audit — cancellation is not a failure to alert on.
   */
  @Test
  void process_canceledFailure_propagatesUnaudited_phase2() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // enables the account-snapshot dispatch
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    AccountSnapshotActivity slowAccountStub =
        request -> {
          try {
            Thread.sleep(60_000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setCash(new BigDecimal("123456.78"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, slowAccountStub);
    env.start();

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    WorkflowStub stub = WorkflowStub.fromTyped(wf);
    stub.start(btoPayload());
    env.sleep(Duration.ofSeconds(5));
    stub.cancel();

    assertThatThrownBy(() -> stub.getResult(Void.class))
        .isInstanceOf(WorkflowFailedException.class);
    // Cancellation re-throws untouched: no EntryWorkflowFailed page.
    verify(audit, Mockito.never())
        .log(Mockito.argThat(e -> e != null && "EntryWorkflowFailed".equals(e.getKind())));
    Mockito.verify(exec, Mockito.never()).placeOrder(any());
  }

  /**
   * Phase 2 replay-stability pin: the failure-audit version marker id is load-bearing. Changing it
   * after deploy re-introduces the nondeterminism the gate prevents. Mirrors the {@code
   * VERSION_PRE_TRADE_DISPATCH} marker-string pin above.
   */
  @Test
  void entryFailureAuditVersionIdIsStable_phase2() throws Exception {
    Field marker =
        CopytradeSignalWorkflowImpl.class.getDeclaredField("VERSION_ENTRY_FAILURE_AUDIT");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("entry-workflow-failure-audit-v1");
  }

  // ----- helpers -----

  /**
   * Builds a real {@link RiskActivitiesImpl} wired with {@link
   * RiskCollaboratorDefaults#permissivePreTradeCheck()} — the no-op bean whose presence (when
   * {@code preTradeCheckEnabled=true}) causes {@code assertPreTradeCheckRoutable} to throw a
   * non-retryable {@link ApplicationFailure}. Mirrors {@code RiskActivitiesAssertionTest}'s private
   * {@code buildRiskWith} helper — kept local to avoid cross-test coupling.
   */
  private static RiskActivitiesImpl realRiskWithPermissiveDefaultBean() {
    Clock clock = Clock.fixed(Instant.parse("2026-05-13T17:22:31Z"), ZoneOffset.UTC);
    return new RiskActivitiesImpl(
        (tenant, strategy) -> 0L,
        clock,
        Mockito.mock(WorkflowClient.class),
        RiskCollaboratorDefaults.permissivePortfolioSnapshot(),
        SectorResolver.CONFIG_BACKED,
        RiskCollaboratorDefaults.zeroDailyTradeCounter(),
        RiskCollaboratorDefaults.zeroDrawdownSampler(),
        RiskCollaboratorDefaults.permissivePreTradeCheck());
  }

  /**
   * Builds a real {@link RiskActivitiesImpl} whose kill-switch read returns an UNTRIPPED state, so
   * checkEntryWithLimit reaches the notional-cap gate instead of failing closed on a missing
   * KillSwitchWorkflow. Clock is fixed to the payload's posted_at so the signal-age gate passes;
   * permissive collaborator defaults zero out the other Issue #6 gates, leaving the notional-cap
   * math (against the workflow-dispatched cash) as the binding constraint.
   */
  private static RiskActivitiesImpl realRiskWithUntrippedKillSwitch() {
    Clock clock = Clock.fixed(Instant.parse("2026-05-13T17:22:31Z"), ZoneOffset.UTC);
    WorkflowClient client = Mockito.mock(WorkflowClient.class);
    KillSwitchWorkflow ksStub = Mockito.mock(KillSwitchWorkflow.class);
    KillSwitchState untripped = new KillSwitchState();
    untripped.setTripped(false);
    when(ksStub.killswitchState()).thenReturn(untripped);
    when(client.newWorkflowStub(eq(KillSwitchWorkflow.class), anyString())).thenReturn(ksStub);
    return new RiskActivitiesImpl(
        (tenant, strategy) -> 0L,
        clock,
        client,
        RiskCollaboratorDefaults.permissivePortfolioSnapshot(),
        SectorResolver.CONFIG_BACKED,
        RiskCollaboratorDefaults.zeroDailyTradeCounter(),
        RiskCollaboratorDefaults.zeroDrawdownSampler(),
        RiskCollaboratorDefaults.permissivePreTradeCheck());
  }

  /**
   * Walks up to three levels of the cause chain looking for an {@link ApplicationFailure}.
   * Temporal's runtime may wrap a workflow-body {@code ApplicationFailure} in another failure type
   * (e.g. {@code ActivityFailure}); this test pins the failure's <em>type</em> and
   * <em>non-retryable</em> flag, not the wrapping depth.
   */
  private static ApplicationFailure unwrapApplicationFailure(Throwable t) {
    Throwable cur = t;
    for (int i = 0; i < 3 && cur != null; i++) {
      if (cur instanceof ApplicationFailure) {
        return (ApplicationFailure) cur;
      }
      cur = cur.getCause();
    }
    return null;
  }

  private void runWorkflow(CopytradeSignalPayload payload) {
    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    wf.process(payload);
  }

  private AuditEvent capture(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  // ---------- dynamic-account-cash-sizing: capital_source=account_cash ----------

  /**
   * dynamic-account-cash-sizing: with {@code capital_source=account_cash} and NO notional cap, the
   * workflow MUST still dispatch {@code AccountSnapshotActivity} (the cash-sizing widening of the
   * dispatch enablement) and size from the broker CASH × capital_weight — NOT the global static
   * $100k. cash=5000 × weight=0.2 = $1000 allocation; price=2.30 → $230/contract → floor(4.34)=4,
   * inside [min=1,max=5]. If sizing had wrongly used the static $100k base, allocation=$20k → 86 →
   * clamped to max=5, so a qty of 4 proves the cash base was used. {@code capitalForStrategy} (the
   * static allocator) must NEVER be consulted on this path.
   */
  @Test
  void handleBto_accountCashSizing_noNotionalCap_sizesFromBrokerCash_andDispatchesSnapshot() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    // Deliberately NO notional cap: account_cash alone must force the snapshot dispatch.
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    AtomicInteger accountCalls = new AtomicInteger();
    AccountSnapshotActivity accountStub =
        request -> {
          accountCalls.incrementAndGet();
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setEquity(new BigDecimal("999999.99"));
          r.setCash(new BigDecimal("5000"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, accountStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    runWorkflow(btoPayload());

    // account_cash forced the snapshot dispatch even though no notional cap was configured.
    assertThat(accountCalls.get()).isEqualTo(1);
    // Sized from cash (5000 × 0.2 = 1000 / 230 = floor 4), NOT the static $100k (which would
    // max=5).
    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getQty()).isEqualTo(4L);
    // The static allocator must never be consulted under account_cash.
    verify(strategy, Mockito.never()).capitalForStrategy(anyString(), anyString());
  }

  /**
   * dynamic-account-cash-sizing fail-closed: with {@code capital_source=account_cash}, when the
   * resolved cash is ZERO (broker outage → the {@code dispatchAccountSnapshot} ZERO sentinel, or a
   * genuinely empty account) the entry is REJECTED with reason {@code capital_unavailable} — no
   * placeOrder, no PositionWorkflow — and NEVER falls back to the static $100k. The risk gate
   * APPROVES here (no notional cap, approved decision) so the reject is driven purely by the
   * cash-sizing fail-closed branch, not the cap gate.
   */
  @Test
  void handleBto_accountCashSizing_zeroCash_rejectsCapitalUnavailable_noOrder() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    // Broker outage: the snapshot throws → dispatchAccountSnapshot fails closed to ZERO cash.
    AccountSnapshotActivity throwingAccountStub =
        request -> {
          throw new RuntimeException("broker /v2/account timeout");
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub, throwingAccountStub);
    env.start();

    runWorkflow(btoPayload());

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "CAPITAL_UNAVAILABLE");
    assertThat(rejected.getSubject()).containsEntry("reason_detail", "capital_unavailable");
    assertThat(rejected.getSubject()).containsEntry("outcome", "REJECTED");
    // Fail-closed: NO order placed, and the static allocator is NEVER used as a fallback.
    Mockito.verify(exec, Mockito.never()).placeOrder(any());
    verify(strategy, Mockito.never()).capitalForStrategy(anyString(), anyString());
  }

  /**
   * Back-compat: {@code capital_source} absent (defaults to {@code static}) sizes from the static
   * allocator, byte-identical to the pre-change path. capital=$100k × 0.2 = $20k / $230 = 86 →
   * clamped to max=5. No AccountSnapshotActivity is registered, proving the static path never
   * dispatches one (no notional cap, no account_cash).
   */
  @Test
  void handleBto_defaultStaticSource_sizesFromStaticAllocator_unchanged() {
    StrategyConfig cfg = configWithPreTradeEnabled(); // capital_source unset → STATIC default
    assertThat(cfg.getCapitalSource()).isEqualTo(StrategyConfig.CapitalSource.STATIC);
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());

    PreTradeCheckActivity preTradeStub =
        request -> {
          PreTradeCheckResult r = new PreTradeCheckResult();
          r.setSchemaVersion(1L);
          r.setAllowed(true);
          r.setBuyingPower(new BigDecimal("50000"));
          r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
          r.setMarginSufficient(true);
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, preTradeStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    runWorkflow(btoPayload());

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getQty()).isEqualTo(5L); // static $100k → clamped to max
    verify(strategy, Mockito.times(1)).capitalForStrategy("dev", "copytrade-v1");
  }

  // ---------- notional-cap clamp-to-headroom (Phase F4B) ----------

  /**
   * Phase F4B lever (B): an over-cap entry is SIZED DOWN to fit the remaining notional-cap headroom
   * instead of being rejected outright. cash=5000 × weight=0.2 = $1000 / ($2.27 × 100) =
   * floor(4.40) = 4 cash-sized contracts; the cap headroom (stubbed) is 1; max=5. The clamp is
   * MIN(4, 1, 5) = 1 → 1 contract placed, and SignalRejected is NOT emitted. Mirrors the live IREN
   * forensic (cap $1,342, open $1,070, headroom $272, $227/ct → 1).
   */
  @Test
  void notionalCap_overCap_clampsToHeadroom_entersReducedQty() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setPreTradeCheckEnabled(false);
    cfg.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.80"));
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    // Cap headroom binds at 1 contract.
    when(risk.notionalCapHeadroomContracts(eq(cfg), any(), any(), anyString(), anyString()))
        .thenReturn(1L);

    AccountSnapshotActivity accountStub =
        request -> {
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setCash(new BigDecimal("5000"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, accountStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    CopytradeSignalPayload p = btoPayload();
    p.setPrice(new BigDecimal("2.27"));
    runWorkflow(p);

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getQty()).isEqualTo(1L); // clamped to headroom, NOT rejected
    // The over-cap entry was sized down, not rejected.
    verify(audit, Mockito.never())
        .log(Mockito.argThat(e -> e != null && "SignalRejected".equals(e.getKind())));
  }

  /**
   * Phase F4B: when the clamp falls BELOW {@code min_contracts} (headroom 0) the entry is STILL
   * rejected with {@code NOTIONAL_CAP_EXCEEDED} and NO order is placed — a sub-minimum entry is not
   * worth placing.
   */
  @Test
  void notionalCap_clampBelowMinContracts_stillRejects() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setPreTradeCheckEnabled(false);
    cfg.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.80"));
    cfg.setMinContracts(1L);
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    // No headroom: clamp yields 0 < min_contracts(1).
    when(risk.notionalCapHeadroomContracts(eq(cfg), any(), any(), anyString(), anyString()))
        .thenReturn(0L);

    AccountSnapshotActivity accountStub =
        request -> {
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setCash(new BigDecimal("5000"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, accountStub);
    env.start();

    CopytradeSignalPayload p = btoPayload();
    p.setPrice(new BigDecimal("2.27"));
    runWorkflow(p);

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "NOTIONAL_CAP_EXCEEDED");
    assertThat(rejected.getSubject()).containsEntry("outcome", "REJECTED");
    Mockito.verify(exec, Mockito.never()).placeOrder(any());
  }

  /**
   * Phase F4B: the placed qty is exactly MIN(account-cash sizing, cap-headroom, max_contracts),
   * floored. cash=5000 × 0.2 = $1000 / ($2.27 × 100) = 4 cash-sized; headroom (stubbed) = 3; max=2.
   * MIN(4, 3, 2) = 2 → 2 contracts placed.
   */
  @Test
  void notionalCap_clampIsMinOfCashAndCapAndMax() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    cfg.setPreTradeCheckEnabled(false);
    cfg.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.80"));
    cfg.setMaxContracts(2L);
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(risk.notionalCapHeadroomContracts(eq(cfg), any(), any(), anyString(), anyString()))
        .thenReturn(3L);

    AccountSnapshotActivity accountStub =
        request -> {
          AccountSnapshotResult r = new AccountSnapshotResult();
          r.setSchemaVersion(1L);
          r.setCash(new BigDecimal("5000"));
          return r;
        };
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec, accountStub);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
    env.start();

    CopytradeSignalPayload p = btoPayload();
    p.setPrice(new BigDecimal("2.27"));
    runWorkflow(p);

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getQty()).isEqualTo(2L); // MIN(4 cash, 3 headroom, 2 max)
  }

  private static ContractResolveResult resolved() {
    return new ContractResolveResult(
        "NVDA  260516C00140000",
        "NVDA",
        LocalDate.of(2026, 5, 16),
        new BigDecimal("140"),
        "C",
        ContractResolveResult.SOURCE_GENERATED);
  }

  private static OrderIntentResult submittedResult(String intentKey, String brokerOrderId) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey(intentKey);
    r.setBrokerOrderId(brokerOrderId);
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private static OrderIntentResult cancelledResult(String intentKey, String brokerOrderId) {
    OrderIntentResult r = submittedResult(intentKey, brokerOrderId);
    r.setState(OrderIntentResult.State.CANCELLED);
    return r;
  }

  private static CopytradeSignalPayload btoPayload() {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("111:0");
    p.setMessageId("111");
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

  private static StrategyConfig configWithPreTradeEnabled() {
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
    c.setPendingTtlPaperSecs(1L); // short TTL so test exits quickly
    c.setPreTradeCheckEnabled(true);
    return c;
  }
}
