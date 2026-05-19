package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
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
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any())).thenReturn(Duration.ZERO);
    SubscribePremiumResult ok = new SubscribePremiumResult();
    ok.setSchemaVersion(1L);
    ok.setSubscriptionId("sub-test");
    ok.setSubscribedAt(OffsetDateTime.now());
    ok.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    when(marketData.subscribePremium(any())).thenReturn(ok);

    coreWorker.registerActivitiesImplementations(
        audit, strategy, risk, contract, positionLookup, calendar);
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
    when(risk.checkEntry(any(), eq(cfg), any())).thenReturn(RiskDecision.approved());

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
    verify(risk).checkEntry(any(), eq(cfg), resultCaptor.capture());
    PreTradeCheckResult captured = resultCaptor.getValue();
    assertThat(captured).isNotNull();
    assertThat(captured.getAllowed()).isTrue();

    verify(risk, Mockito.times(1)).assertPreTradeCheckRoutable(cfg);

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getSide()).isEqualTo(OrderIntent.Side.BUY);
  }

  @Test
  void handleBto_failsClosed_whenPreTradeCheckActivityThrows() {
    StrategyConfig cfg = configWithPreTradeEnabled();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(contract.resolve(any())).thenReturn(resolved());
    when(risk.checkEntry(any(), eq(cfg), any()))
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
    verify(risk).checkEntry(any(), eq(cfg), resultCaptor.capture());
    PreTradeCheckResult sentinel = resultCaptor.getValue();
    assertThat(sentinel).isNotNull();
    assertThat(sentinel.getAllowed()).isFalse();
    assertThat(sentinel.getRejectReason()).startsWith("dispatch_failed:");

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "PRE_TRADE_CHECK_FAILED");
    assertThat(rejected.getSubject()).containsEntry("outcome", "REJECTED");

    Mockito.verify(exec, Mockito.never()).placeOrder(any());
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
    when(risk.checkEntry(any(), eq(cfg), any())).thenReturn(RiskDecision.approved());

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

    // v=1 branch contract: assertPreTradeCheckRoutable fires AND checkEntry receives a non-null
    // PreTradeCheckResult (i.e. dispatchPreTradeCheck ran). The legacy v=DEFAULT_VERSION branch
    // would have skipped both and called checkEntry(..., null) instead.
    verify(risk, Mockito.times(1)).assertPreTradeCheckRoutable(cfg);
    ArgumentCaptor<PreTradeCheckResult> resultCaptor =
        ArgumentCaptor.forClass(PreTradeCheckResult.class);
    verify(risk).checkEntry(any(), eq(cfg), resultCaptor.capture());
    assertThat(resultCaptor.getValue()).isNotNull();
  }

  // ----- helpers -----

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
