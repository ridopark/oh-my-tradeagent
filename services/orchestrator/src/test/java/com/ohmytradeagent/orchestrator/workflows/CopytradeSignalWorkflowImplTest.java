package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeEntryStatus;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionStatus;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class CopytradeSignalWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private AuditQueryActivities auditQuery;
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
    // Register the custom search attributes startPositionWorkflow sets on the child workflow.
    // Production registers these at cluster bootstrap; the test server requires per-test setup.
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(
        CopytradeSignalWorkflowImpl.class, PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    auditQuery = Mockito.mock(AuditQueryActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    risk = Mockito.mock(RiskActivities.class);
    contract = Mockito.mock(ContractActivities.class);
    exec = Mockito.mock(ExecActivities.class);
    positionLookup = Mockito.mock(PositionLookupActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    marketData = Mockito.mock(SubscribePremiumActivity.class);
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    // PLAN-2026-07-23 Phase 2: give every spawned child PositionWorkflow a FUTURE expiry-close so
    // its expiry timer ARMS. These tests use expired-dated OCC literals as a live-position
    // stand-in;
    // with a >0 expiry-close the new expire-worthless-no-timer guard is skipped (a live position
    // has
    // a terminal timer), so a spawned+confirmed child does not self-close at entry and can still
    // receive its STC. Inert for the assertions here — an 8h timer never fires in these
    // minute-scale
    // tests. (Was ZERO = no expiry timer, which now trips the guard on the expired OCC.)
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ofHours(8));
    SubscribePremiumResult ok = new SubscribePremiumResult();
    ok.setSchemaVersion(1L);
    ok.setSubscriptionId("sub-test");
    ok.setSubscribedAt(OffsetDateTime.now());
    ok.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    when(marketData.subscribePremium(any())).thenReturn(ok);

    coreWorker.registerActivitiesImplementations(
        audit, auditQuery, strategy, risk, contract, positionLookup, calendar);
    // ExecActivities lives on the exec-svc task queue; register a separate worker.
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec);
    // P3-a: live-promotion VALID-path tests use broker_target=alpaca-live, which routes
    // ExecActivities to broker-alpaca-live. Register the same exec mock there so the dispatched
    // placeOrder is answered (refusal tests never reach placeOrder, so this is only load-bearing
    // for liveBtoWithValidPromotion_dispatchesOrder).
    Worker brokerLiveWorker = env.newWorker("broker-alpaca-live");
    brokerLiveWorker.registerActivitiesImplementations(exec);
    Worker mdWorker = env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(marketData);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void rejectedByAuthor_producesSignalRejectedAndDoesNotCallExec() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(
            RiskDecision.rejected(RejectionReason.AUTHOR_NOT_WHITELISTED, "author=stranger"));

    runWorkflow(btoPayload());

    verify(contract, never()).resolve(any());
    verify(exec, never()).placeOrder(any());

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "AUTHOR_NOT_WHITELISTED");
    assertThat(rejected.getSubject()).containsEntry("outcome", "REJECTED");
  }

  @Test
  void rejectedByStaleSignal_producesSignalRejected() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.rejected(RejectionReason.SIGNAL_TOO_OLD, "age_secs=2000"));

    runWorkflow(btoPayload());

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "SIGNAL_TOO_OLD");
    verify(exec, never()).placeOrder(any());
  }

  @Test
  void rejectedByMaxPositions_producesSignalRejected() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "open=5"));

    runWorkflow(btoPayload());

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "MAX_POSITIONS_EXCEEDED");
    verify(exec, never()).placeOrder(any());
  }

  @Test
  void approvedSignal_callsExecPlaceOrderAndEmitsOrderSubmitted() {
    // Issue #191: configure both slippage caps (abs=0.05, pct=0.05) and price=3.10 so the
    // BTO limit derives via the SLIP_MIN branch (min(3.15, 3.255) = 3.15). The end-to-end wire-up
    // is asserted via (a) the captured OrderIntent.limitPrice and (b) the OrderSubmitted audit
    // subject carrying limit_price_strategy=slip_min.
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(1L); // short TTL so test exits quickly
    cfg.setMaxSlippageAbs(new BigDecimal("0.05"));
    cfg.setMaxSlippagePct(new BigDecimal("0.05"));
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    CopytradeSignalPayload p = btoPayload();
    p.setPrice(new BigDecimal("3.10"));
    runWorkflow(p);

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    OrderIntent submitted = intentCaptor.getValue();
    assertThat(submitted.getIntentKey()).endsWith(":entry");
    assertThat(submitted.getSide()).isEqualTo(OrderIntent.Side.BUY);
    assertThat(submitted.getQty()).isEqualTo(5L);
    assertThat(submitted.getOptionSymbol()).isEqualTo("NVDA  260516C00140000");
    // Issue #191: compareTo (not equals) — BigDecimal scale of `3.10 + 0.05` is 2 (matches plan
    // halt-condition 4 guard against scale-mismatch flakes).
    assertThat(submitted.getLimitPrice()).isEqualByComparingTo(new BigDecimal("3.15"));

    AuditEvent orderSubmitted = capture("OrderSubmitted");
    assertThat(orderSubmitted.getSubject())
        .containsEntry("broker_order_id", "stub-intent-K")
        .containsEntry("option_symbol", "NVDA  260516C00140000")
        .containsEntry("limit_price_strategy", "slip_min");
  }

  // ---------- Phase 7: per-tenant strategy enable toggle ----------

  @Test
  void disabledStrategy_emitsSignalRejected_andPlacesNoOrder() {
    // enabled == false: the strategy admits no new entries. The signal is rejected with
    // reason_detail=strategy_disabled BEFORE any exec activity — no placeOrder, no
    // PositionWorkflow.
    StrategyConfig cfg = config();
    cfg.setEnabled(false);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);

    String result = runWorkflow(btoPayload());
    assertThat(result).isEqualTo("111:0");

    verify(contract, never()).resolve(any());
    verify(exec, never()).placeOrder(any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());

    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject())
        .containsEntry("signal_id", "111:0")
        .containsEntry("reason_code", "STRATEGY_DISABLED")
        .containsEntry("reason_detail", "strategy_disabled")
        .containsEntry("outcome", "REJECTED");
  }

  @Test
  void enabledNull_proceedsAsToday() {
    // Absent/null enabled MUST be treated as enabled (no behavior change for older blobs / existing
    // tenants). The entry proceeds exactly as the approved-signal path.
    StrategyConfig cfg = config();
    cfg.setEnabled(null);
    cfg.setPendingTtlPaperSecs(1L);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    runWorkflow(btoPayload());

    verify(exec, times(1)).placeOrder(any());
    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(
            all.getAllValues().stream()
                .filter(e -> "SignalRejected".equals(e.getKind()))
                .anyMatch(e -> "STRATEGY_DISABLED".equals(e.getSubject().get("reason_code"))))
        .isFalse();
  }

  @Test
  void enabledTrue_proceeds() {
    // enabled == true: the entry proceeds (regression — same path as the approved signal).
    StrategyConfig cfg = config();
    cfg.setEnabled(true);
    cfg.setPendingTtlPaperSecs(1L);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    runWorkflow(btoPayload());

    verify(exec, times(1)).placeOrder(any());
  }

  /**
   * Phase 7: reflective constant-name pin for the enable-gate change-id, mirroring {@link
   * #versionFilledAdoptionConstantNamesAreStable}. Renaming or re-valuing the {@code
   * Workflow.getVersion} change-id after deploy would re-introduce non-determinism — in-flight
   * histories minted with the OLD id would no longer find their marker and would replay through the
   * wrong branch. The string VALUE is load-bearing, so this pins it exactly.
   */
  @Test
  void versionStrategyEnabledGateConstantNameIsStable() throws Exception {
    Field gate =
        CopytradeSignalWorkflowImpl.class.getDeclaredField("VERSION_STRATEGY_ENABLED_GATE");
    gate.setAccessible(true);
    assertThat((String) gate.get(null)).isEqualTo("strategy-enabled-gate-v1");
  }

  // ---------- P3-a: live-promotion dispatch gate ----------

  @Test
  void liveBtoWithNoValidPromotion_refusesOrder_emitsLivePromotionMissingAudit() {
    // ABSENT: a LIVE BTO with no valid LivePromotionApproved row must be refused — no placeOrder,
    // no PositionWorkflow — and emit a LivePromotionMissing audit with reason=absent.
    StrategyConfig cfg = liveConfig();
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(auditQuery.checkLivePromotion(anyString(), anyString(), anyString(), any()))
        .thenReturn(LivePromotionStatus.ABSENT);

    String result = runWorkflow(btoPayload());
    assertThat(result).isEqualTo("111:0");

    verify(exec, never()).placeOrder(any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());

    AuditEvent missing = capture("LivePromotionMissing");
    assertThat(missing.getSubject())
        .containsEntry("signal_id", "111:0")
        .containsEntry("tenant_id", "dev")
        .containsEntry("strategy_id", "copytrade-v1")
        .containsEntry("broker_target", "alpaca-live")
        .containsEntry("reason", "absent")
        .containsEntry("outcome", "REJECTED");
  }

  @Test
  void liveBtoWithValidPromotion_dispatchesOrder() {
    // VALID: a LIVE BTO with a fresh approval dispatches the order through the normal path.
    StrategyConfig cfg = liveConfig();
    cfg.setPendingTtlPaperSecs(1L); // short TTL so the test exits quickly after dispatch
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(auditQuery.checkLivePromotion(anyString(), anyString(), anyString(), any()))
        .thenReturn(LivePromotionStatus.VALID);
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    runWorkflow(btoPayload());

    verify(exec, times(1)).placeOrder(any());
    capture("OrderSubmitted");
    // No refusal emitted on the VALID path.
    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(
            all.getAllValues().stream().anyMatch(e -> "LivePromotionMissing".equals(e.getKind())))
        .isFalse();
  }

  @Test
  void liveBtoWithStalePromotion_refusesOrder() {
    // STALE: refused with reason=stale. Also verify the deterministic staleness window — the
    // notStaleSince arg passed to the verify activity is workflowStart − 30 days.
    StrategyConfig cfg = liveConfig();
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(auditQuery.checkLivePromotion(anyString(), anyString(), anyString(), any()))
        .thenReturn(LivePromotionStatus.STALE);

    // Capture the workflow's start time so we can pin the 30d window deterministically.
    long startMillis = env.currentTimeMillis();
    runWorkflow(btoPayload());

    verify(exec, never()).placeOrder(any());
    AuditEvent missing = capture("LivePromotionMissing");
    assertThat(missing.getSubject()).containsEntry("reason", "stale");

    ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(auditQuery)
        .checkLivePromotion(
            eq("dev"), eq("copytrade-v1"), eq("alpaca-live"), sinceCaptor.capture());
    OffsetDateTime expected =
        OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(startMillis - java.time.Duration.ofDays(30).toMillis()),
            ZoneOffset.UTC);
    // The workflow's first command runs at-or-after env start; the window is currentTimeMillis−30d.
    // Assert the captured value is exactly 30 days before the verify-call instant, allowing for the
    // tiny advance between env start and the verify command.
    assertThat(sinceCaptor.getValue()).isAfterOrEqualTo(expected);
    assertThat(sinceCaptor.getValue())
        .isBeforeOrEqualTo(expected.plus(java.time.Duration.ofMinutes(5)));
  }

  @Test
  void liveBtoWithConfigChangedPromotion_refusesOrder() {
    // P3-b CONFIG_CHANGED: a risk-relevant TenantConfigChanged landed after the approval, so the
    // verify returns CONFIG_CHANGED. The live BTO is refused — no placeOrder, no PositionWorkflow —
    // and a LivePromotionMissing audit with reason=config_changed is emitted. The workflow still
    // returns the signalId (a clean refusal, not a failure).
    StrategyConfig cfg = liveConfig();
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(auditQuery.checkLivePromotion(anyString(), anyString(), anyString(), any()))
        .thenReturn(LivePromotionStatus.CONFIG_CHANGED);

    String result = runWorkflow(btoPayload());
    assertThat(result).isEqualTo("111:0");

    verify(exec, never()).placeOrder(any());
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());

    AuditEvent missing = capture("LivePromotionMissing");
    assertThat(missing.getSubject()).containsEntry("reason", "config_changed");
  }

  @Test
  void liveBtoWithVerifyError_refusesOrder() {
    // VERIFY_ERROR: the verify failed closed — refused with reason=verify_error.
    StrategyConfig cfg = liveConfig();
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(auditQuery.checkLivePromotion(anyString(), anyString(), anyString(), any()))
        .thenReturn(LivePromotionStatus.VERIFY_ERROR);

    runWorkflow(btoPayload());

    verify(exec, never()).placeOrder(any());
    AuditEvent missing = capture("LivePromotionMissing");
    assertThat(missing.getSubject()).containsEntry("reason", "verify_error");
  }

  @Test
  void paperBtoSkipsLivePromotionGate() {
    // Paper (alpaca-paper): the live-promotion gate must NOT run — checkLivePromotion is never
    // invoked — and the order dispatches unchanged.
    StrategyConfig cfg = config(); // alpaca-paper
    cfg.setPendingTtlPaperSecs(1L);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    runWorkflow(btoPayload());

    verify(auditQuery, never()).checkLivePromotion(anyString(), anyString(), anyString(), any());
    verify(exec, times(1)).placeOrder(any());
  }

  @Test
  void approvedSignal_ttlExpiry_cancelsOrderAndEmitsEntryExpired() {
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    runWorkflow(btoPayload());

    AuditEvent cancelReq = capture("OrderCancelRequested");
    assertThat(cancelReq.getSubject()).containsEntry("reason", "ttl_expired");

    AuditEvent cancelled = capture("OrderCancelled");
    assertThat(cancelled.getSubject()).containsEntry("broker_order_id", "stub-intent-K");

    AuditEvent expired = capture("EntryExpired");
    assertThat(expired.getSubject()).containsEntry("outcome", "EXPIRED");
    assertThat(((Number) expired.getSubject().get("ttl_secs")).longValue()).isPositive();
  }

  @Test
  void approvedSignal_cancelFailed_emitsOrderCancelFailedWithFailureNote() {
    // Issue #165 phase 2: the audit note value changes from `orphan_position_until_phase_3`
    // to `cancel_failed` — the orphan case is now either recovered by the new FILLED branch
    // in handleTtlExpired or detected by Phase 3 reconciliation. The else-branch (non-CANCELLED,
    // non-FILLED) keeps the note key so audit consumers don't break.
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    OrderIntentResult cancelFailed = submittedResult("intent-K", "stub-intent-K");
    cancelFailed.setLastError("some transient broker failure");
    when(exec.cancelOrder(anyString())).thenReturn(cancelFailed);

    runWorkflow(btoPayload());

    AuditEvent failed = capture("OrderCancelFailed");
    assertThat(failed.getSubject())
        .containsEntry("broker_reason", "some transient broker failure")
        .containsEntry("severity", "ERROR")
        .containsEntry("note", "cancel_failed");
  }

  @Test
  void handleTtlExpired_brokerAlreadyFilled_spawnsPositionWorkflow() {
    // Issue #165 phase 2: when the broker filled inside the TTL/cancel race, the exec sidecar
    // now returns state=FILLED with broker-confirmed filled_qty + avg_fill_price. The
    // orchestrator must recognise this as a successful entry, log EntryFilled (not
    // EntryExpired/OrderCancelFailed), and spawn the PositionWorkflow so subsequent STCs
    // dispatch partialExit instead of OrphanSTC.
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));
    // No onFill signal -> TTL fires -> cancelOrder runs -> broker reports already filled.
    OrderIntentResult cancelFilled = submittedResult("intent-K", "brk-1");
    cancelFilled.setState(OrderIntentResult.State.FILLED);
    cancelFilled.setFilledQty(5L);
    cancelFilled.setAvgFillPrice(new BigDecimal("0.84"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelFilled);

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("rec-bto-1")
                    .build());
    WorkflowStub.fromTyped(wf).start(btoPayload());
    WorkflowStub.fromTyped(wf).getResult(String.class);

    // EntryFilled present with recovery marker and broker-confirmed numbers.
    AuditEvent filled = capture("EntryFilled");
    assertThat(filled.getSubject())
        .containsEntry("outcome", "FILLED")
        .containsEntry("recovery", "cancel_on_filled")
        .containsEntry("broker_order_id", "brk-1")
        // Issue #276: new executions (TestWorkflowEnvironment reports getVersion==1) carry the
        // per-symbol correlation key on the cancel-on-filled recovery EntryFilled too.
        .containsEntry("option_symbol", "NVDA  260516C00140000");
    assertThat(((Number) filled.getSubject().get("filled_qty")).longValue()).isEqualTo(5L);
    // Audit subject round-trips through Jackson, so BigDecimal arrives back as Double — compare
    // as Number to avoid coupling the test to the on-wire numeric representation.
    assertThat(((Number) filled.getSubject().get("avg_fill_price")).doubleValue()).isEqualTo(0.84);

    // EntryExpired / OrderCancelFailed are NOT emitted on the recovery path.
    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(all.getAllValues().stream().anyMatch(e -> "EntryExpired".equals(e.getKind())))
        .isFalse();
    assertThat(all.getAllValues().stream().anyMatch(e -> "OrderCancelFailed".equals(e.getKind())))
        .isFalse();

    // PositionWorkflow was started — startPositionWorkflow's last side-effect is the
    // cachePositionMapping call. Verify with the OCC we resolved.
    verify(positionLookup, atLeastOnce())
        .cachePositionMapping(
            eq("dev"), eq("copytrade-v1"), eq("NVDA  260516C00140000"), anyString());

    // Subsequent STC on the same OCC: positionLookup now serves the cached id, so STC
    // dispatches ExitRequested (partialExit) — no OrphanSTC.
    String posWfId = WorkflowIds.position("dev", "copytrade-v1", "NVDA  260516C00140000", "111:0");
    when(positionLookup.findPositionWorkflowId("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .thenReturn(posWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload stc = btoPayload();
    stc.setAction(CopytradeSignalPayload.Action.STC);
    stc.setTail("half out");
    stc.setSignalId("111:1");
    runWorkflow(stc);

    AuditEvent exit = capture("ExitRequested");
    assertThat(exit.getSubject())
        .containsEntry("signal_id", "111:1")
        .containsEntry("position_workflow_id", posWfId);
    ArgumentCaptor<AuditEvent> eventsAfterStc = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(eventsAfterStc.capture());
    assertThat(
            eventsAfterStc.getAllValues().stream().anyMatch(e -> "OrphanSTC".equals(e.getKind())))
        .isFalse();
  }

  @Test
  void handleTtlExpired_cancelNonFilled_getOrderStatusFilled_spawnsPositionWorkflow() {
    // Copytrade parity for the watchlist Phase-1 fix: the single cancelOrder return can miss a fill
    // that terminalized the journal FILLED a beat later (the WS listener raced the cancel — the SPY
    // 2026-07-06 incident on the sibling watchlist path). cancelOrder here reports CANCELLED, but
    // the defense-in-depth exec.getOrderStatus re-read reports terminal FILLED with a broker qty.
    // The workflow must adopt the lot inline via handleCancelOnFilled (EntryFilled,
    // PositionWorkflow
    // spawned) rather than orphaning it to the 5-min recon sweep with EntryExpired.
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));
    // No onFill signal -> TTL fires -> cancelOrder reports CANCELLED (order was resting at cancel
    // time) -> getOrderStatus re-reads the reconciled journal row: FILLED qty=5.
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "brk-1"));
    OrderIntentResult filledStatus = submittedResult("intent-K", "brk-1");
    filledStatus.setState(OrderIntentResult.State.FILLED);
    filledStatus.setFilledQty(5L);
    filledStatus.setAvgFillPrice(new BigDecimal("0.84"));
    when(exec.getOrderStatus(anyString())).thenReturn(filledStatus);

    runWorkflow(btoPayload());

    // EntryFilled present with the getOrderStatus-reconcile recovery marker (distinct from the
    // inline cancel_on_filled path) and broker-confirmed numbers.
    AuditEvent filled = capture("EntryFilled");
    assertThat(filled.getSubject())
        .containsEntry("outcome", "FILLED")
        .containsEntry("recovery", "getorderstatus_reconcile")
        .containsEntry("broker_order_id", "brk-1")
        .containsEntry("option_symbol", "NVDA  260516C00140000");
    assertThat(((Number) filled.getSubject().get("filled_qty")).longValue()).isEqualTo(5L);
    assertThat(((Number) filled.getSubject().get("avg_fill_price")).doubleValue()).isEqualTo(0.84);

    // EntryExpired / OrderCancelFailed are NOT emitted on the reconcile-adoption path.
    assertNoAudit("EntryExpired");
    assertNoAudit("OrderCancelFailed");

    // PositionWorkflow was started — cachePositionMapping is startPositionWorkflow's last side
    // effect.
    verify(positionLookup, atLeastOnce())
        .cachePositionMapping(
            eq("dev"), eq("copytrade-v1"), eq("NVDA  260516C00140000"), anyString());
  }

  @Test
  void handleTtlExpired_getOrderStatusThrows_fallsThroughToEntryExpired() {
    // The getOrderStatus re-read is best-effort: a broker-down RuntimeException must be swallowed
    // and the workflow must fall through fail-closed to the legacy EntryExpired path (recon settles
    // any orphan). NOTE: cancelOrder itself stays un-try/caught (a throw propagates + Temporal
    // retries the activity — existing behavior preserved), so the "cancelOrder throws" variant is
    // intentionally NOT exercised here; the best-effort guard lives only around getOrderStatus.
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "brk-1"));
    when(exec.getOrderStatus(anyString())).thenThrow(new RuntimeException("broker unreachable"));

    runWorkflow(btoPayload());

    AuditEvent expired = capture("EntryExpired");
    assertThat(expired.getSubject()).containsEntry("outcome", "EXPIRED");
    assertNoAudit("EntryFilled");
  }

  @Test
  void handleTtlExpired_bothNonFilled_emitsEntryExpiredUnchanged() {
    // Legacy behavior preserved: cancelOrder CANCELLED and getOrderStatus also non-FILLED
    // (CANCELLED) -> no adoption, EntryExpired unchanged.
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "brk-1"));
    when(exec.getOrderStatus(anyString())).thenReturn(cancelledResult("intent-K", "brk-1"));

    runWorkflow(btoPayload());

    AuditEvent cancelled = capture("OrderCancelled");
    assertThat(cancelled.getSubject()).containsEntry("reason", "ttl_expired");
    AuditEvent expired = capture("EntryExpired");
    assertThat(expired.getSubject()).containsEntry("outcome", "EXPIRED");
    assertNoAudit("EntryFilled");
  }

  /**
   * Version-id stability pin (mirrors {@link #versionFilledAdoptionConstantNamesAreStable}).
   * Renaming or re-valuing the {@code Workflow.getVersion} change-id after the gate is deployed
   * would leave in-flight histories minted with the OLD id unable to find their marker, replaying
   * through the wrong branch. The string VALUE is load-bearing, so pin it exactly.
   */
  @Test
  void entryGetOrderStatusReconcileVersionIdIsStable() throws Exception {
    Field f =
        CopytradeSignalWorkflowImpl.class.getDeclaredField(
            "VERSION_ENTRY_GETORDERSTATUS_RECONCILE");
    f.setAccessible(true);
    assertThat((String) f.get(null)).isEqualTo("copytrade-entry-getorderstatus-reconcile-v1");
  }

  @Test
  void onFill_signaledTwice_spawnsPositionWorkflowExactlyOnce() throws Exception {
    // Phase 4 of the fill-listener plan pins the at-least-once safety claim: the WS listener and
    // polling fallback may both signal onFill for the same broker fill. The workflow must absorb
    // the duplicate without spawning a second PositionWorkflow.
    setupApprovedMocks();
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("idem-bto-1")
                    .build());
    WorkflowStub.fromTyped(wf).start(btoPayload());

    // Wait until the workflow has placed the order and is sitting at the await — so the first
    // signal lands while still awaiting, and the second arrives after the await wakes. Covers
    // both legs of the idempotency claim in one shot.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, atLeastOnce()).placeOrder(any());
        break;
      } catch (AssertionError ignored) {
        Thread.sleep(50);
      }
    }

    FillSignalPayload fill =
        new FillSignalPayload()
            .withBrokerOrderId("brk-1")
            .withFilledQty(5L)
            .withAvgFillPrice(new BigDecimal("0.84"))
            .withFilledAt(OffsetDateTime.parse("2026-05-24T17:00:00Z"));
    wf.onFill(fill);
    wf.onFill(fill);

    WorkflowStub.fromTyped(wf).getResult(String.class);

    // cachePositionMapping is startPositionWorkflow's last side-effect, so exactly-one invocation
    // proves exactly-one PositionWorkflow start.
    verify(positionLookup, times(1))
        .cachePositionMapping(
            eq("dev"), eq("copytrade-v1"), eq("NVDA  260516C00140000"), anyString());

    // Issue #276: the happy-path EntryFilled audit carries the per-symbol correlation key for new
    // executions (TestWorkflowEnvironment reports getVersion==1) so DailyPnl groups FIFO per
    // symbol.
    AuditEvent filled = capture("EntryFilled");
    assertThat(filled.getSubject())
        .containsEntry("outcome", "FILLED")
        .containsEntry("option_symbol", "NVDA  260516C00140000");
  }

  // ---------- F1 edited-signal supersede / auto-correct ----------

  private static final String SPY_0706 = "SPY   260706P00710000"; // prior wrong-expiry leg
  private static final String SPY_0708 = "SPY   260708P00710000"; // corrected leg

  /** A SPY put BTO for the given expiry/signal, posted at the given time. */
  private CopytradeSignalPayload spyPut(
      LocalDate expiry, String signalId, OffsetDateTime postedAt) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId(signalId);
    p.setMessageId(signalId.split(":")[0]);
    p.setAuthor("acme_trader");
    p.setPostedAt(postedAt);
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker("SPY");
    p.setExpiry(expiry);
    p.setStrike(new BigDecimal("710"));
    p.setRight(CopytradeSignalPayload.Right.P);
    p.setPrice(new BigDecimal("3.00"));
    p.setRawLine("BTO SPY " + expiry + " 710P @ 3.00");
    return p;
  }

  private void setupApprovedSpyMocks(String correctedOcc) {
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(1L);
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                correctedOcc,
                "SPY",
                LocalDate.of(2026, 7, 8),
                new BigDecimal("710"),
                "P",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-corrected"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "brk-corrected"));
  }

  /**
   * Reproduction of the 2026-06-25 incident: a corrected SPY 7/08 710P BTO arriving within the 120s
   * window of a prior SPY 7/06 710P leg AUTO-supersedes (cancels/flattens) the prior wrong-expiry
   * leg, leaving ONE position. The supersede DECISION is audited (BtoCorrectionSuperseded carrying
   * BOTH OCCs) and the prior leg's PositionWorkflow actions the supersede signal
   * (PositionSupersededByCorrection + a force-flatten to zero).
   */
  @Test
  void correctedBto_withinWindow_supersedesPriorWrongExpiryLeg() throws Exception {
    // 1. Start a REAL prior PositionWorkflow (SPY 7/06) and confirm it with a 50-contract fill so
    //    positionState reports a non-null entryAt + partialExited=false.
    String priorWfId = "t-dev/s-copytrade-v1/pos/" + SPY_0706 + "/prior-1";
    PositionWorkflow prior =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(priorWfId)
                    .build());
    com.ohmytradeagent.contract.PositionWorkflowInput priorIn = positionInput();
    priorIn.setContractSymbol(SPY_0706);
    priorIn.setEntrySignalId("700:0");
    priorIn.setQty(50L);
    WorkflowStub.fromTyped(prior).start(priorIn);
    prior.onFill(
        new FillSignalPayload()
            .withBrokerOrderId("brk-prior")
            .withFilledQty(50L)
            .withAvgFillPrice(new BigDecimal("3.00"))
            .withFilledAt(OffsetDateTime.parse("2026-07-05T14:30:00Z")));

    // Wait until the prior leg has confirmed (PositionEntered emitted) so entryAt is stamped.
    awaitAudit("PositionEntered");

    // 2. The lookup activity finds the prior leg as a different-expiry match. Window is satisfied:
    //    the corrected signal is posted ~30s after the prior entry.
    OffsetDateTime priorEntryAt = OffsetDateTime.parse("2026-07-05T14:30:00Z");
    when(positionLookup.findOpenPositionByUnderlyingStrikeRight(
            eq("dev"), eq("copytrade-v1"), eq("SPY"), any(), eq("P"), eq("2026-07-08")))
        .thenReturn(
            new PositionLookupActivities.SupersedeCandidate(
                priorWfId, SPY_0706, priorEntryAt, false));

    setupApprovedSpyMocks(SPY_0708);

    // 3. Run the corrected BTO (SPY 7/08), posted within the window.
    runWorkflow(spyPut(LocalDate.of(2026, 7, 8), "701:0", priorEntryAt.plusSeconds(30)));

    // 4. The supersede DECISION audit carries BOTH OCCs.
    AuditEvent superseded = capture("BtoCorrectionSuperseded");
    assertThat(superseded.getSubject())
        .containsEntry("corrected_option_symbol", SPY_0708)
        .containsEntry("superseded_option_symbol", SPY_0706)
        .containsEntry("superseded_workflow_id", priorWfId)
        .containsEntry("signal_id", "701:0");

    // 5. The prior leg actioned the supersede: PositionSupersededByCorrection audit (the
    // wrong-expiry
    //    leg ties itself to the corrected leg) followed by a force-flatten (cancel/replace). The
    //    flatten cancels any in-flight order then places a MARKET exit (immediacy) — the wrong leg
    // is
    //    being unwound. We assert on the audits rather than blocking on terminal completion (the
    //    flatten then awaits the broker fill, which the SUBMITTED-only mock never delivers).
    awaitAudit("PositionSupersededByCorrection");
    AuditEvent childAudit = capture("PositionSupersededByCorrection");
    assertThat(childAudit.getSubject())
        .containsEntry("contract_symbol", SPY_0706)
        .containsEntry("corrected_option_symbol", SPY_0708);
    // The prior leg attempted the cancel-then-flatten (the auto-cancel of the wrong-expiry leg),
    // audited under the dedicated supersede-flatten kind (NOT the EOD sweep kind).
    AuditEvent flattenReq = capture("BtoCorrectionFlattenRequested");
    assertThat(flattenReq.getSubject())
        .containsEntry("contract_symbol", SPY_0706)
        .containsEntry("reason", "bto_corrected");
  }

  @Test
  void correctedBto_outsideWindow_doesNotSupersede() {
    // The prior leg matches on underlying/strike/right + different expiry, BUT its entry is 10 min
    // before the corrected signal — outside the 120s correction window. NO auto-cancel.
    setupApprovedSpyMocks(SPY_0708);
    OffsetDateTime priorEntryAt = OffsetDateTime.parse("2026-07-05T14:30:00Z");
    when(positionLookup.findOpenPositionByUnderlyingStrikeRight(
            eq("dev"), eq("copytrade-v1"), eq("SPY"), any(), eq("P"), eq("2026-07-08")))
        .thenReturn(
            new PositionLookupActivities.SupersedeCandidate(
                "wf-prior", SPY_0706, priorEntryAt, false));

    runWorkflow(spyPut(LocalDate.of(2026, 7, 8), "702:0", priorEntryAt.plusMinutes(10)));

    assertNoAudit("BtoCorrectionSuperseded");
  }

  @Test
  void correctedBto_priorLegAlreadyPartiallyExited_doesNotSupersede() {
    setupApprovedSpyMocks(SPY_0708);
    OffsetDateTime priorEntryAt = OffsetDateTime.parse("2026-07-05T14:30:00Z");
    when(positionLookup.findOpenPositionByUnderlyingStrikeRight(
            eq("dev"), eq("copytrade-v1"), eq("SPY"), any(), eq("P"), eq("2026-07-08")))
        .thenReturn(
            // partialExited=true → never auto-cancel an already-exiting leg.
            new PositionLookupActivities.SupersedeCandidate(
                "wf-prior", SPY_0706, priorEntryAt, true));

    runWorkflow(spyPut(LocalDate.of(2026, 7, 8), "703:0", priorEntryAt.plusSeconds(30)));

    assertNoAudit("BtoCorrectionSuperseded");
  }

  @Test
  void correctedBto_priorLegNotConfirmed_doesNotSupersede() {
    setupApprovedSpyMocks(SPY_0708);
    when(positionLookup.findOpenPositionByUnderlyingStrikeRight(
            eq("dev"), eq("copytrade-v1"), eq("SPY"), any(), eq("P"), eq("2026-07-08")))
        .thenReturn(
            // entryAt == null → leg not yet confirmed; never supersede an unconfirmed leg.
            new PositionLookupActivities.SupersedeCandidate("wf-prior", SPY_0706, null, false));

    runWorkflow(
        spyPut(LocalDate.of(2026, 7, 8), "704:0", OffsetDateTime.parse("2026-07-05T14:30:30Z")));

    assertNoAudit("BtoCorrectionSuperseded");
  }

  @Test
  void correctedBto_noMatchingPriorLeg_doesNotSupersede() {
    // The lookup activity (which owns the underlying/strike/right + different-expiry filter) finds
    // no match — e.g. a different strike/right, or the same OCC (existing dedup path). NO
    // supersede.
    setupApprovedSpyMocks(SPY_0708);
    when(positionLookup.findOpenPositionByUnderlyingStrikeRight(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(null);

    runWorkflow(
        spyPut(LocalDate.of(2026, 7, 8), "705:0", OffsetDateTime.parse("2026-07-05T14:30:30Z")));

    assertNoAudit("BtoCorrectionSuperseded");
    verify(positionLookup, atLeastOnce())
        .findOpenPositionByUnderlyingStrikeRight(
            eq("dev"), eq("copytrade-v1"), eq("SPY"), any(), eq("P"), eq("2026-07-08"));
  }

  @Test
  void avgAction_skipAvgTrue_emitsAvgSkipped_andNoExecCalls() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.AVG);
    runWorkflow(p);

    AuditEvent skipped = capture("AvgSkipped");
    assertThat(skipped.getSubject()).containsEntry("signal_id", p.getSignalId());
    verify(exec, never()).placeOrder(any());
    verify(positionLookup, never()).findPositionWorkflowId(anyString(), anyString(), anyString());
  }

  @Test
  void signalReceived_subjectIsEnrichedWithParsedSignalFields() {
    // Issue #308: the SignalReceived audit subject must carry the parsed signal fields the Discord
    // feed mirror renders, in addition to the original signal_id. This fires before any risk gates,
    // so use a config that rejects so the workflow returns quickly — the assertion is on the
    // FIRST-emitted SignalReceived event regardless of the downstream outcome.
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(
            RiskDecision.rejected(RejectionReason.AUTHOR_NOT_WHITELISTED, "author=stranger"));

    runWorkflow(btoPayload());

    AuditEvent received = capture("SignalReceived");
    assertThat(received.getSubject())
        .containsEntry("signal_id", "111:0")
        .containsEntry("action", "BTO")
        .containsEntry("ticker", "NVDA")
        .containsEntry("expiry", "2026-05-16")
        .containsEntry("strike", "140")
        .containsEntry("right", "C")
        .containsEntry("price", "2.30")
        .containsEntry("author", "acme_trader");
    // posted_at renders the OffsetDateTime; assert presence + the date portion.
    assertThat(String.valueOf(received.getSubject().get("posted_at"))).contains("2026-05-13");
  }

  @Test
  void stcAction_cacheHit_dispatchesExitRequestedAudit() {
    when(strategy.get(anyString(), anyString())).thenReturn(stcConfig());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));

    // Start a real PositionWorkflow so the external signal has a target. We don't care about
    // its outcome — we only assert the dispatch (audit + lookup call) on the parent side.
    String posWfId = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-1";
    PositionWorkflow posStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(posWfId)
                    .build());
    io.temporal.client.WorkflowStub.fromTyped(posStub).start(positionInput());

    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(posWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("half out");
    p.setSignalId("222:0");
    runWorkflow(p);

    AuditEvent exit = capture("ExitRequested");
    assertThat(exit.getSubject()).containsEntry("signal_id", "222:0");
    assertThat(exit.getSubject()).containsEntry("option_symbol", "NVDA  260516C00140000");
    assertThat(exit.getSubject()).containsEntry("position_workflow_id", posWfId);
    assertThat(((Number) exit.getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);
    verify(positionLookup, atLeastOnce())
        .findPositionWorkflowId("dev", "copytrade-v1", "NVDA  260516C00140000");
  }

  // PLAN-2026-07-25-stc-intent-classifier Phase 3: the workflow shadow-audits the classifier's
  // close_intent on EVERY STC and, ONLY when the tenant's stc_intent_enforce is true, lets it
  // arbitrate full-vs-partial (keyword matcher stays fallback + partial-sizer).

  // The 2026-07-24 incident tail: matched NO keyword → fell through to default_stc_fraction (0.3),
  // under-closing a clear full-exit. Kept as a literal so the tests reproduce the exact miss.
  private static final String INCIDENT_TAIL =
      "bears can't finish taking the W. Don't want to see it go red again";

  @Test
  void stc_enforceOff_usesKeywordFraction_unchanged() {
    // enforce absent (null) → keyword path unchanged (fraction == default 0.3) even though the
    // classifier said "full"; the verdict is still shadow-recorded in the audit subject.
    StrategyConfig cfg = intentConfig(new BigDecimal("0.3"), null); // enforce absent
    AuditEvent exit = runStcWithIntent(cfg, INCIDENT_TAIL, CopytradeSignalPayload.CloseIntent.FULL);

    assertThat(((Number) exit.getSubject().get("fraction")).doubleValue()).isEqualTo(0.3);
    assertThat(((Number) exit.getSubject().get("keyword_fraction")).doubleValue()).isEqualTo(0.3);
    assertThat(exit.getSubject()).containsEntry("close_intent", "full");
    assertThat(exit.getSubject()).containsEntry("intent_source", "keyword");
    assertThat(exit.getSubject()).containsEntry("intent_enforced", false);
  }

  @Test
  void stc_enforceOn_fullIntent_promotesToFullClose() {
    // INCIDENT REPRODUCTION: same tail, close_intent=full, enforce on → the classifier promotes the
    // under-close (keyword 0.3) to a full exit (1.0).
    StrategyConfig cfg = intentConfig(new BigDecimal("0.3"), Boolean.TRUE);
    AuditEvent exit = runStcWithIntent(cfg, INCIDENT_TAIL, CopytradeSignalPayload.CloseIntent.FULL);

    assertThat(((Number) exit.getSubject().get("fraction")).doubleValue()).isEqualTo(1.0);
    assertThat(((Number) exit.getSubject().get("keyword_fraction")).doubleValue()).isEqualTo(0.3);
    assertThat(exit.getSubject()).containsEntry("intent_source", "classifier");
    assertThat(exit.getSubject()).containsEntry("intent_enforced", true);
  }

  @Test
  void stc_intentAbsent_replaySafe_keywordPath() {
    // Old-history shape: close_intent null (absent). Even with enforce on, effectiveFraction ==
    // keywordFraction → byte-identical to today's behavior (replay-safe, no version gate needed).
    StrategyConfig cfg = intentConfig(new BigDecimal("0.3"), Boolean.TRUE);
    AuditEvent exit = runStcWithIntent(cfg, "half out", null); // keyword "half out" → 0.5

    assertThat(((Number) exit.getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);
    assertThat(((Number) exit.getSubject().get("keyword_fraction")).doubleValue()).isEqualTo(0.5);
    assertThat(exit.getSubject()).containsEntry("intent_source", "keyword");
    assertThat(exit.getSubject().get("close_intent")).isNull();
  }

  @Test
  void stc_enforceOn_partialIntent_explicitFullKeyword_defersToKeyword() {
    // PROMOTE-ONLY rule (quant + risk review 2026-07-25): a keyword_fraction of exactly 1.0 ALWAYS
    // means an explicit full-close keyword matched (the default is never 1.0). Even with a PARTIAL
    // classifier verdict + enforce on, the classifier must NOT demote it — it may only PROMOTE a
    // keyword-missed full close, never size an exit smaller than the keyword value. Tail "out"
    // matches ONLY the stcConfig "out" → 1.0 keyword (not "half"/"half out", neither of which is a
    // substring of "out"), resolving to 1.0 with no collision. So the explicit full-close keyword
    // wins (1.0) and the PARTIAL verdict is recorded (deferred), NOT enforced.
    StrategyConfig cfg = intentConfig(new BigDecimal("0.3"), Boolean.TRUE);
    AuditEvent exit = runStcWithIntent(cfg, "out", CopytradeSignalPayload.CloseIntent.PARTIAL);

    assertThat(((Number) exit.getSubject().get("fraction")).doubleValue()).isEqualTo(1.0);
    assertThat(((Number) exit.getSubject().get("keyword_fraction")).doubleValue()).isEqualTo(1.0);
    // Verdict noted for shadow review, but deferred — keyword sizes it.
    assertThat(exit.getSubject()).containsEntry("close_intent", "partial");
    assertThat(exit.getSubject()).containsEntry("intent_source", "keyword");
  }

  @Test
  void stc_enforceOn_partialIntent_keepsKeywordSizing() {
    // "half out": keyword → 0.5, a legitimate partial. close_intent=partial + enforce on keeps the
    // keyword-sized partial untouched (0.5 < 1.0, so no demotion).
    StrategyConfig cfg = intentConfig(new BigDecimal("0.3"), Boolean.TRUE);
    AuditEvent exit = runStcWithIntent(cfg, "half out", CopytradeSignalPayload.CloseIntent.PARTIAL);

    assertThat(((Number) exit.getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);
    assertThat(((Number) exit.getSubject().get("keyword_fraction")).doubleValue()).isEqualTo(0.5);
  }

  /** stcConfig()-shaped config with an explicit default_stc_fraction and stc_intent_enforce. */
  private StrategyConfig intentConfig(BigDecimal defaultFraction, Boolean enforce) {
    StrategyConfig c = stcConfig();
    c.setDefaultStcFraction(defaultFraction);
    c.setStcIntentEnforce(enforce);
    return c;
  }

  /** Drives the STC dispatch path (real target PositionWorkflow) and returns the ExitRequested. */
  private AuditEvent runStcWithIntent(
      StrategyConfig cfg, String tail, CopytradeSignalPayload.CloseIntent intent) {
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));

    String posWfId = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-intent";
    PositionWorkflow posStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(posWfId)
                    .build());
    io.temporal.client.WorkflowStub.fromTyped(posStub).start(positionInput());

    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(posWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail(tail);
    p.setSignalId("stc-intent");
    p.setCloseIntent(intent);
    runWorkflow(p);
    return capture("ExitRequested");
  }

  @Test
  void stcAction_trailOnPartialTrue_emitsChandelierArmRequested() {
    StrategyConfig cfg = stcConfig();
    cfg.setTrailOnPartial(true);
    cfg.setTrailGivebackPct(new BigDecimal("0.15"));
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));

    String posWfId = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-trail";
    PositionWorkflow posStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(posWfId)
                    .build());
    io.temporal.client.WorkflowStub.fromTyped(posStub).start(positionInput());

    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(posWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("half out");
    p.setSignalId("stc-trail-1");
    runWorkflow(p);

    capture("ExitRequested");
    AuditEvent armReq = capture("ChandelierArmRequested");
    assertThat(armReq.getSubject()).containsEntry("signal_id", "stc-trail-1");
    assertThat(armReq.getSubject()).containsEntry("position_workflow_id", posWfId);
    assertThat(((Number) armReq.getSubject().get("peak_premium")).doubleValue()).isEqualTo(2.30);
    assertThat(((Number) armReq.getSubject().get("giveback_pct")).doubleValue()).isEqualTo(0.15);
  }

  @Test
  void stcAction_trailOnPartialFalse_emitsOnlyExitRequested() {
    StrategyConfig cfg = stcConfig();
    cfg.setTrailOnPartial(false);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));

    String posWfId = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-no-trail";
    PositionWorkflow posStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(posWfId)
                    .build());
    io.temporal.client.WorkflowStub.fromTyped(posStub).start(positionInput());

    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(posWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("half out");
    p.setSignalId("stc-no-trail-1");
    runWorkflow(p);

    capture("ExitRequested");
    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(
            all.getAllValues().stream().anyMatch(e -> "ChandelierArmRequested".equals(e.getKind())))
        .isFalse();
  }

  @Test
  void stcAction_cachedPositionWorkflowNotRunning_emitsStcNoOpenPosition() {
    // GAP regression: a stale Redis mapping returns a non-null but DEAD (Failed/terminal)
    // PositionWorkflow id. Change point A (preventive guard) must short-circuit BEFORE the
    // ExitRequested audit and BEFORE signalling the dead workflow, emitting StcNoOpenPosition (the
    // benign "no open position — Site B" kind, NOT the failure OrphanSTC) and letting
    // the CopytradeSignalWorkflow COMPLETE. Without the fix the handler emits ExitRequested then
    // signals a dead id, the SignalExternalWorkflowException propagates, and the workflow FAILS.
    when(strategy.get(anyString(), anyString())).thenReturn(stcConfig());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));

    String deadWfId = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-dead";
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(deadWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("half out");
    p.setSignalId("dead-pos-1");
    p.setAuthor("edtrader");
    // A FAILED workflow throws WorkflowFailedException out of runWorkflow; reaching this line
    // proves the workflow COMPLETED.
    runWorkflow(p);

    AuditEvent benign = capture("StcNoOpenPosition");
    assertThat(benign.getSubject())
        .containsEntry("signal_id", "dead-pos-1")
        .containsEntry("option_symbol", "NVDA  260516C00140000")
        .containsEntry("position_workflow_id", deadWfId)
        .containsEntry("reason", "position_workflow_not_running")
        .containsEntry("author", "edtrader");

    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(all.getAllValues().stream().anyMatch(e -> "ExitRequested".equals(e.getKind())))
        .isFalse();
  }

  @Test
  void stcAction_signalDispatchThrows_emitsOrphanStcAndCompletes() {
    // Defense-in-depth (change point B): the running-guard passes (isPositionWorkflowRunning=true)
    // but the partialExit dispatch still throws SignalExternalWorkflowException (TOCTOU race / the
    // workflow died between the guard and the signal). The handler must catch it SPECIFICALLY,
    // emit OrphanSTC with an "error" key, and COMPLETE. ExitRequested IS emitted here (the guard
    // passed before dispatch), which distinguishes this from the change-point-A path.
    when(strategy.get(anyString(), anyString())).thenReturn(stcConfig());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));

    // A workflow id that was NEVER started: the guard mock says RUNNING, but the actual external
    // signal command fails on the test server with SignalExternalWorkflowException.
    String neverStartedWfId = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-never-started";
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(neverStartedWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("half out");
    p.setSignalId("dispatch-throws-1");
    runWorkflow(p);

    AuditEvent orphan = capture("OrphanSTC");
    assertThat(orphan.getSubject())
        .containsEntry("signal_id", "dispatch-throws-1")
        .containsEntry("option_symbol", "NVDA  260516C00140000")
        .containsEntry("position_workflow_id", neverStartedWfId)
        .containsEntry("reason", "signal_dispatch_failed");
    assertThat(orphan.getSubject()).containsKey("error");

    // ExitRequested was emitted BEFORE the failed dispatch (guard passed) — distinguishes from
    // the change-point-A path which short-circuits before ExitRequested.
    AuditEvent exit = capture("ExitRequested");
    assertThat(exit.getSubject()).containsEntry("signal_id", "dispatch-throws-1");
  }

  // ---------- Phase 5: risk_breach ----------

  @Test
  void riskBreach_btoPath_beforeFill_shortCircuitsAndCancelsEntry() {
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(120L); // generous TTL so we can signal before it expires
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-intent-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-intent-K"));

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("rb-bto-1")
                    .build());
    WorkflowStub.fromTyped(wf).start(btoPayload());

    // Wait until placeOrder was called (workflow now awaiting fill) then risk_breach.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, atLeastOnce()).placeOrder(any());
        break;
      } catch (AssertionError ignored) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    wf.riskBreach(riskBreach("auto:daily_loss", "auto:daily_loss"));
    WorkflowStub.fromTyped(wf).getResult(String.class);

    AuditEvent aborted = capture("SignalAbortedByRiskBreach");
    assertThat(aborted.getSubject()).containsEntry("reason", "auto:daily_loss");
    verify(exec, atLeastOnce()).cancelOrder(anyString());
  }

  @Test
  void riskBreach_btoPath_afterFill_adoptsPositionInsteadOfOrphaning() {
    // Issue #274: a risk_breach landing in the fill-await race window must NOT orphan an
    // already-filled entry. The async onFill signal may not have landed when the breach woke the
    // await, but exec.cancelOrder reconciles broker truth and returns state=FILLED with the
    // broker-confirmed filled_qty/avg_fill_price (ExecActivitiesImpl ALREADY_FILLED → markFilled).
    // The breach-abort branch must capture that result and route to handleCancelOnFilled →
    // startPositionWorkflow (the same recovery the TTL path uses) instead of discarding it.
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(120L); // generous TTL so we can signal before it expires
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));
    // The cancel races the broker fill and loses: broker reports already-filled with 25 contracts.
    OrderIntentResult cancelFilled = submittedResult("intent-K", "brk-1");
    cancelFilled.setState(OrderIntentResult.State.FILLED);
    cancelFilled.setFilledQty(25L);
    cancelFilled.setAvgFillPrice(new BigDecimal("0.88"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelFilled);

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("rb-bto-filled-1")
                    .build());
    WorkflowStub.fromTyped(wf).start(btoPayload());

    // Wait until placeOrder was called (workflow now awaiting fill) then risk_breach with no
    // preceding onFill — fillEvent==null when the breach wakes the await, exactly the live race.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, atLeastOnce()).placeOrder(any());
        break;
      } catch (AssertionError ignored) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    wf.riskBreach(riskBreach("auto:daily_loss", "auto:daily_loss"));
    WorkflowStub.fromTyped(wf).getResult(String.class);

    // EntryFilled emitted with a recovery marker and the broker-confirmed 25 contracts.
    AuditEvent filled = capture("EntryFilled");
    assertThat(filled.getSubject())
        .containsEntry("outcome", "FILLED")
        .containsEntry("recovery", "cancel_on_filled")
        .containsEntry("broker_order_id", "brk-1");
    assertThat(((Number) filled.getSubject().get("filled_qty")).longValue()).isEqualTo(25L);

    // PositionWorkflow was started — cachePositionMapping is startPositionWorkflow's last
    // side-effect — using the canonical WorkflowIds.position id (asserted via the STC cache hit
    // below). The lot is adopted, not orphaned.
    verify(positionLookup, atLeastOnce())
        .cachePositionMapping(
            eq("dev"), eq("copytrade-v1"), eq("NVDA  260516C00140000"), anyString());

    // Subsequent STC on the same OCC routes to partialExit (ExitRequested), not OrphanSTC.
    String posWfId = WorkflowIds.position("dev", "copytrade-v1", "NVDA  260516C00140000", "111:0");
    when(positionLookup.findPositionWorkflowId("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .thenReturn(posWfId);
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);

    CopytradeSignalPayload stc = btoPayload();
    stc.setAction(CopytradeSignalPayload.Action.STC);
    stc.setTail("half out");
    stc.setSignalId("111:1");
    runWorkflow(stc);

    AuditEvent exit = capture("ExitRequested");
    assertThat(exit.getSubject())
        .containsEntry("signal_id", "111:1")
        .containsEntry("position_workflow_id", posWfId);

    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(all.getAllValues().stream().anyMatch(e -> "OrphanSTC".equals(e.getKind())))
        .isFalse();
    // The genuine-pre-fill abort audit must NOT fire on the adopted path.
    assertThat(
            all.getAllValues().stream()
                .anyMatch(e -> "SignalAbortedByRiskBreach".equals(e.getKind())))
        .isFalse();
  }

  @Test
  void riskBreach_btoPath_afterFill_cancelThrows_auditsAndAbortsWithoutStartingPositionWorkflow() {
    // Issue #279: the v>=1 breach-abort path wraps exec.cancelOrder in a try/catch (see
    // CopytradeSignalWorkflowImpl#handleBto). When the broker-truth reconciliation cancel THROWS
    // (rather than returning CANCELLED or FILLED), the workflow must fall back to the genuine
    // audit-and-abort: emit SignalAbortedByRiskBreach and start NO PositionWorkflow. This pins the
    // catch branch that the happy-path adoption test (cancel returns FILLED) and the
    // returns-CANCELLED test do not exercise.
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(120L); // generous TTL so we can signal before it expires
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "brk-1"));
    // The reconciliation cancel throws — the broker is unreachable / errored. The v>=1 branch's
    // catch must route to audit-and-abort, NOT adopt a (nonexistent) filled lot.
    when(exec.cancelOrder(anyString()))
        .thenThrow(new IllegalStateException("broker_unreachable_during_cancel"));

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("rb-bto-cancel-throws-1")
                    .build());
    WorkflowStub.fromTyped(wf).start(btoPayload());

    // Wait until placeOrder was called (workflow now awaiting fill) then risk_breach with no
    // preceding onFill — fillEvent==null when the breach wakes the await, exactly the live race.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, atLeastOnce()).placeOrder(any());
        break;
      } catch (AssertionError ignored) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    wf.riskBreach(riskBreach("auto:daily_loss", "auto:daily_loss"));
    WorkflowStub.fromTyped(wf).getResult(String.class);

    // The cancel threw, so the genuine audit-and-abort fires with the breach reason.
    AuditEvent aborted = capture("SignalAbortedByRiskBreach");
    assertThat(aborted.getSubject()).containsEntry("reason", "auto:daily_loss");
    assertThat(aborted.getSubject()).containsEntry("stage", "bto_pre_fill");
    verify(exec, atLeastOnce()).cancelOrder(anyString());

    // No lot was adopted: no EntryFilled audit, and NO PositionWorkflow started
    // (cachePositionMapping is startPositionWorkflow's last side-effect).
    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(all.getAllValues().stream().anyMatch(e -> "EntryFilled".equals(e.getKind())))
        .isFalse();
    verify(positionLookup, never())
        .cachePositionMapping(anyString(), anyString(), anyString(), anyString());
  }

  /**
   * Issue #279: reflective constant-name pin for the breach/TTL filled-adoption change-ids,
   * mirroring the {@code VERSION_PRE_TRADE_DISPATCH} precedent in {@link
   * CopytradeSignalWorkflowImplLegacyReplayTest#versionPreTradeDispatchConstantNameIsStable}.
   * Renaming or re-valuing either {@code Workflow.getVersion} change-id string after the gate is
   * deployed would re-introduce the non-determinism the gates were added to prevent — in-flight
   * histories minted with the OLD change-id would no longer find their marker and would replay
   * through the wrong branch. The string VALUES are load-bearing, so this test pins them exactly.
   */
  @Test
  void versionFilledAdoptionConstantNamesAreStable() throws Exception {
    Field breach =
        CopytradeSignalWorkflowImpl.class.getDeclaredField("VERSION_BREACH_FILLED_ADOPTION");
    breach.setAccessible(true);
    assertThat((String) breach.get(null)).isEqualTo("breach-filled-adoption-v1");

    Field ttl = CopytradeSignalWorkflowImpl.class.getDeclaredField("VERSION_TTL_FILLED_ADOPTION");
    ttl.setAccessible(true);
    assertThat((String) ttl.get(null)).isEqualTo("ttl-filled-adoption-v1");
  }

  @Test
  void riskBreach_stcPath_shortCircuitsBeforeDispatch() throws Exception {
    StrategyConfig cfg = stcConfig();
    cfg.setPendingTtlPaperSecs(120L); // generous buffer so the STC sleep loop is still in play
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    // Cache miss — the workflow will be sleeping in the look-up loop when we signal.
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(null);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("half out");
    p.setSignalId("rb-stc-1");

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("rb-stc-wf")
                    .build());
    WorkflowStub.fromTyped(wf).start(p);

    // Let the workflow enter the lookup loop, then signal.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(positionLookup, atLeastOnce())
            .findPositionWorkflowId(anyString(), anyString(), anyString());
        break;
      } catch (AssertionError ignored) {
        Thread.sleep(50);
      }
    }
    wf.riskBreach(riskBreach("auto:daily_loss", "auto:daily_loss"));
    WorkflowStub.fromTyped(wf).getResult(String.class);

    AuditEvent aborted = capture("SignalAbortedByRiskBreach");
    assertThat(aborted.getSubject()).containsEntry("signal_id", "rb-stc-1");
  }

  private static RiskBreachPayload riskBreach(String reason, String actor) {
    RiskBreachPayload r = new RiskBreachPayload();
    r.setSchemaVersion(1L);
    r.setReason(reason);
    r.setActor(actor);
    r.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
    return r;
  }

  private com.ohmytradeagent.contract.PositionWorkflowInput positionInput() {
    com.ohmytradeagent.contract.PositionWorkflowInput in =
        new com.ohmytradeagent.contract.PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    in.setEntrySignalId("entry-1");
    in.setContractSymbol("NVDA  260516C00140000");
    in.setQty(5L);
    in.setEntryPremium(new BigDecimal("2.30"));
    return in;
  }

  @Test
  void stcAction_cacheMissAndBufferExpires_emitsStcNoOpenPosition() {
    StrategyConfig cfg = stcConfig();
    cfg.setPendingTtlPaperSecs(10L); // 1 attempt
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(null);

    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail("out");
    p.setSignalId("333:0");
    p.setAuthor("edtrader");
    runWorkflow(p);

    AuditEvent benign = capture("StcNoOpenPosition");
    assertThat(benign.getSubject()).containsEntry("signal_id", "333:0");
    assertThat(benign.getSubject()).containsEntry("author", "edtrader");
    assertThat(((Number) benign.getSubject().get("attempts")).intValue()).isPositive();
  }

  private StrategyConfig stcConfig() {
    StrategyConfig c = config();
    c.setDefaultStcFraction(new BigDecimal("0.5"));
    Map<String, BigDecimal> fractions = new LinkedHashMap<>();
    fractions.put("half", new BigDecimal("0.5"));
    fractions.put("out", new BigDecimal("1.0"));
    fractions.put("half out", new BigDecimal("0.5"));
    c.setPartialFractions(fractions);
    return c;
  }

  private void setupApprovedMocks() {
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(1L); // short TTL so test exits quickly
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), eq(cfg), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
  }

  private OrderIntentResult submittedResult(String intentKey, String brokerOrderId) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey(intentKey);
    r.setBrokerOrderId(brokerOrderId);
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private OrderIntentResult cancelledResult(String intentKey, String brokerOrderId) {
    OrderIntentResult r = submittedResult(intentKey, brokerOrderId);
    r.setState(OrderIntentResult.State.CANCELLED);
    return r;
  }

  private String runWorkflow(CopytradeSignalPayload payload) {
    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    return wf.process(payload);
  }

  private AuditEvent capture(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  /** F1: assert NO audit event of the given kind was ever logged. */
  private void assertNoAudit(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    assertThat(captor.getAllValues().stream().anyMatch(e -> kind.equals(e.getKind())))
        .as("expected NO audit event with kind=%s", kind)
        .isFalse();
  }

  /** F1: poll until an audit event of the given kind has been logged (test-env time-skips). */
  private void awaitAudit(String kind) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        capture(kind);
        return;
      } catch (AssertionError ignored) {
        Thread.sleep(50);
      }
    }
    capture(kind); // final attempt — throws the AssertionError if still absent.
  }

  private CopytradeSignalPayload btoPayload() {
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

  private StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    // Phase 2c.2: align with the broker-alpaca-paper worker registered in setUp() — the factory
    // routes Activities to broker-<broker_target>.
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setAuthorWhitelist(Set.of("acme_trader"));
    // Issue #3: per-side signal-age defaults replace the legacy 1800s default. The workflow
    // test uses generous windows so age never trips the gate in these mocked scenarios.
    c.setMaxSignalAgeBtoSecs(3600L);
    c.setMaxSignalAgeStcSecs(3600L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }

  /**
   * P3-a: a LIVE strategy config (broker_target=alpaca-live). Routes exec Activities to the
   * broker-alpaca-paper worker registered in {@link #setUp()} is NOT used here — the live-promotion
   * gate short-circuits BEFORE placeOrder on a refusal, and the VALID path's exec mock answers
   * regardless of queue routing in these tests (placeOrder is stubbed on the single broker worker).
   * The ALPACA_LIVE value ("alpaca-live") ends with "-live" so StrategyConfigInvariants.isLive is
   * true.
   */
  private StrategyConfig liveConfig() {
    StrategyConfig c = config();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    return c;
  }

  // ---------- STC multi-leg fan-out ----------

  /** Start a real PositionWorkflow at the given id so an external signal has a live target. */
  private void startLeg(String wfId) {
    PositionWorkflow leg =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).setWorkflowId(wfId).build());
    WorkflowStub.fromTyped(leg).start(positionInput());
  }

  private void setupStcMocks() {
    StrategyConfig cfg = config();
    cfg.setPendingTtlPaperSecs(1L);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(true);
  }

  private CopytradeSignalPayload stcPayload(String tail) {
    CopytradeSignalPayload p = btoPayload();
    p.setAction(CopytradeSignalPayload.Action.STC);
    p.setTail(tail);
    p.setSignalId("222:0");
    return p;
  }

  private java.util.List<String> exitRequestedLegs() {
    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    return all.getAllValues().stream()
        .filter(e -> "ExitRequested".equals(e.getKind()))
        .map(e -> String.valueOf(e.getSubject().get("position_workflow_id")))
        .distinct()
        .toList();
  }

  @Test
  void stc_fansOutToEveryOpenLegForTheOcc() {
    // THE case this exists for: two BTOs on the same contract leave two independent
    // PositionWorkflows. Before the fan-out the author's STC closed whichever one the Redis
    // pointer happened to hold and the other kept running its own exits.
    String legA = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-A";
    String legB = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-B";
    setupStcMocks();
    startLeg(legA);
    startLeg(legB);
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(legB); // the cached pointer resolves the NEWER leg
    when(positionLookup.findAllPositionWorkflowIds(anyString(), anyString(), anyString()))
        .thenReturn(java.util.List.of(legA, legB));

    runWorkflow(stcPayload("half out"));

    assertThat(exitRequestedLegs()).containsExactlyInAnyOrder(legA, legB);
  }

  @Test
  void stc_fanoutMarksTheExtraLegsAndAppliesTheFractionPerLeg() {
    String legA = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-A";
    String legB = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-B";
    setupStcMocks();
    startLeg(legA);
    startLeg(legB);
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(legA);
    when(positionLookup.findAllPositionWorkflowIds(anyString(), anyString(), anyString()))
        .thenReturn(java.util.List.of(legA, legB));

    runWorkflow(stcPayload("half out"));

    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    var exits =
        all.getAllValues().stream().filter(e -> "ExitRequested".equals(e.getKind())).toList();
    // The primary leg is NOT tagged as fan-out; the extra leg is — so forensics can tell the two
    // dispatches apart.
    var primary =
        exits.stream()
            .filter(e -> legA.equals(e.getSubject().get("position_workflow_id")))
            .toList();
    var extra =
        exits.stream()
            .filter(e -> legB.equals(e.getSubject().get("position_workflow_id")))
            .toList();
    assertThat(primary).isNotEmpty();
    assertThat(extra).isNotEmpty();
    assertThat(primary.get(0).getSubject()).doesNotContainKey("fanout_leg");
    assertThat(extra.get(0).getSubject()).containsEntry("fanout_leg", true);
    // Same fraction on BOTH — it applies per leg, not split across them.
    assertThat(((Number) primary.get(0).getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);
    assertThat(((Number) extra.get(0).getSubject().get("fraction")).doubleValue()).isEqualTo(0.5);
  }

  @Test
  void stc_singleLeg_dispatchesExactlyOnce() {
    // The common case must be unchanged: one leg, one ExitRequested, no duplicate dispatch from
    // the fan-out re-signalling the primary.
    String legA = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-A";
    setupStcMocks();
    startLeg(legA);
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(legA);
    when(positionLookup.findAllPositionWorkflowIds(anyString(), anyString(), anyString()))
        .thenReturn(java.util.List.of(legA));

    runWorkflow(stcPayload("half out"));

    ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(all.capture());
    assertThat(all.getAllValues().stream().filter(e -> "ExitRequested".equals(e.getKind())).count())
        .isEqualTo(1);
  }

  @Test
  void stc_fanoutSkipsALegThatIsNoLongerRunning() {
    // A leg that closed on its own stop between enumeration and dispatch is not a failure of this
    // STC — skip it, audit why, and still close the others.
    String legA = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-A";
    String deadLeg = "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/entry-DEAD";
    setupStcMocks();
    startLeg(legA);
    when(positionLookup.findPositionWorkflowId(anyString(), anyString(), anyString()))
        .thenReturn(legA);
    when(positionLookup.findAllPositionWorkflowIds(anyString(), anyString(), anyString()))
        .thenReturn(java.util.List.of(legA, deadLeg));
    when(positionLookup.isPositionWorkflowRunning(deadLeg)).thenReturn(false);
    when(positionLookup.isPositionWorkflowRunning(legA)).thenReturn(true);

    runWorkflow(stcPayload("half out"));

    assertThat(exitRequestedLegs()).containsExactly(legA);
    AuditEvent skipped = capture("StcNoOpenPosition");
    assertThat(skipped.getSubject())
        .containsEntry("position_workflow_id", deadLeg)
        .containsEntry("fanout_leg", true);
  }

  // ---------- PLAN-2026-08-10-live-manual-bto: operator-initiated manual entry ----------

  /** An operator-submitted BTO: source=manual + a hand-typed contract count. */
  private CopytradeSignalPayload manualBtoPayload(long qty) {
    CopytradeSignalPayload p = btoPayload();
    p.setSource(CopytradeSignalPayload.Source.MANUAL);
    p.setQtyOverride(qty);
    p.setAuthor("operator@example.com");
    p.setRawLine("MANUAL BTO NVDA  260516C00140000 qty=" + qty + " ask=2.30");
    return p;
  }

  /** Approve every gate for an NVDA entry so the qty math is the only variable under test. */
  private void setupApprovedNvdaMocks(StrategyConfig cfg) {
    cfg.setPendingTtlPaperSecs(1L); // short TTL so the no-fill tests exit quickly
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(risk.checkEntryWithLimit(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    // capital_weight 0.2 of $100k over a $2.30 premium sizes WAY past max_contracts, so auto-sizing
    // lands on the max_contracts clamp (5). Any qty_override below that is therefore visibly the
    // operator's number and not a coincidence of the sizing math.
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));
    when(exec.placeOrder(any())).thenReturn(submittedResult("intent-K", "stub-K"));
    when(exec.cancelOrder(anyString())).thenReturn(cancelledResult("intent-K", "stub-K"));
  }

  /** Run to completion, then Query the terminal entry status (queries answer on CLOSED runs). */
  private CopytradeEntryStatus runAndQueryStatus(CopytradeSignalPayload payload) {
    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(
                        WorkflowIds.copytradeSignal("dev", "copytrade-v1", payload.getSignalId()))
                    .build());
    WorkflowStub.fromTyped(wf).start(payload);
    WorkflowStub.fromTyped(wf).getResult(String.class);
    return wf.entryStatus();
  }

  @Test
  void manualBto_qtyOverride_placesExactlyThatManyContracts() {
    // The operator asked for 3. Auto-sizing would have clamped to max_contracts (5). The override
    // wins, and the SignalAccepted audit records BOTH so forensics can see what was overridden.
    setupApprovedNvdaMocks(config());

    runWorkflow(manualBtoPayload(3L));

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getQty()).isEqualTo(3L);

    AuditEvent accepted = capture("SignalAccepted");
    assertThat(accepted.getSubject())
        .containsEntry("contracts", 3)
        .containsEntry("source", "manual")
        .containsEntry("qty_override", 3)
        .containsEntry("contracts_auto_sized", 5);
  }

  @Test
  void discordBto_withoutOverride_sizesFromCapitalWeightAndCarriesNoManualKeys() {
    // The invariant the whole design rests on: an ordinary Discord signal (source/qty_override
    // absent) is byte-identical to before — auto-sized, and its audit subject gains no new keys.
    setupApprovedNvdaMocks(config());

    runWorkflow(btoPayload());

    ArgumentCaptor<OrderIntent> intentCaptor = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec).placeOrder(intentCaptor.capture());
    assertThat(intentCaptor.getValue().getQty()).isEqualTo(5L);

    AuditEvent accepted = capture("SignalAccepted");
    assertThat(accepted.getSubject())
        .containsEntry("contracts", 5)
        .doesNotContainKey("source")
        .doesNotContainKey("qty_override")
        .doesNotContainKey("contracts_auto_sized");
  }

  @Test
  void manualBto_qtyOverrideAboveMaxContracts_isRejectedAndPlacesNoOrder() {
    // max_contracts is the tenant's per-entry exposure ceiling. Sizing clamps the auto path to it,
    // so the manual path must not become the way around it.
    setupApprovedNvdaMocks(config()); // max_contracts = 5

    runWorkflow(manualBtoPayload(99L));

    verify(exec, never()).placeOrder(any());
    AuditEvent rejected = capture("SignalRejected");
    assertThat(rejected.getSubject())
        .containsEntry("reason_code", "MANUAL_QTY_OUT_OF_BOUNDS")
        .containsEntry("outcome", "REJECTED");
    assertThat((String) rejected.getSubject().get("reason_detail"))
        .contains("requested=99")
        .contains("max_contracts=5");
  }

  @Test
  void manualBto_qtyOverrideBelowMinContracts_isRejectedAndPlacesNoOrder() {
    StrategyConfig cfg = config();
    cfg.setMinContracts(2L);
    setupApprovedNvdaMocks(cfg);

    runWorkflow(manualBtoPayload(1L));

    verify(exec, never()).placeOrder(any());
    assertThat(capture("SignalRejected").getSubject())
        .containsEntry("reason_code", "MANUAL_QTY_OUT_OF_BOUNDS");
  }

  @Test
  void manualBto_doesNotSupersedePriorWrongExpiryLeg() {
    // THE safety case. An operator hand-opening a different expiry of the same underlying/strike/
    // right, inside the 120s correction window of a filled Discord leg, must NOT be read as an
    // edited-signal correction — that would auto-FLATTEN a live position the operator never
    // touched. Same setup as correctedBto_withinWindow_supersedesPriorWrongExpiryLeg, one field
    // different: source=manual.
    setupApprovedSpyMocks(SPY_0708);
    OffsetDateTime priorEntryAt = OffsetDateTime.parse("2026-07-05T14:30:00Z");
    when(positionLookup.findOpenPositionByUnderlyingStrikeRight(
            eq("dev"), eq("copytrade-v1"), eq("SPY"), any(), eq("P"), eq("2026-07-08")))
        .thenReturn(
            new PositionLookupActivities.SupersedeCandidate(
                "wf-prior", SPY_0706, priorEntryAt, false));

    CopytradeSignalPayload manual =
        spyPut(LocalDate.of(2026, 7, 8), "705:0", priorEntryAt.plusSeconds(30));
    manual.setSource(CopytradeSignalPayload.Source.MANUAL);
    manual.setQtyOverride(1L);
    runWorkflow(manual);

    assertNoAudit("BtoCorrectionSuperseded");
    // Suppressed BEFORE the lookup dispatch — no activity call at all, not just no supersede.
    verify(positionLookup, never())
        .findOpenPositionByUnderlyingStrikeRight(
            anyString(), anyString(), anyString(), any(), anyString(), anyString());
    // …and the manual entry itself still went through.
    verify(exec).placeOrder(any());
  }

  @Test
  void entryStatus_startsPendingAndReportsRejectedWithTheGateThatRefused() {
    StrategyConfig cfg = config();
    cfg.setEnabled(false);
    when(strategy.get(anyString(), anyString())).thenReturn(cfg);

    CopytradeEntryStatus status = runAndQueryStatus(manualBtoPayload(1L));

    assertThat(status.getState()).isEqualTo(CopytradeEntryStatus.State.REJECTED);
    assertThat(status.getReasonCode()).isEqualTo("STRATEGY_DISABLED");
    assertThat(status.getReasonDetail()).isEqualTo("strategy_disabled");
  }

  @Test
  void entryStatus_reportsExpiredWhenTheEntryTtlElapsesWithNoFill() {
    setupApprovedNvdaMocks(config());

    CopytradeEntryStatus status = runAndQueryStatus(manualBtoPayload(2L));

    assertThat(status.getState()).isEqualTo(CopytradeEntryStatus.State.EXPIRED);
    // SUBMITTED stamped the placement details on the way through; EXPIRED preserves them.
    assertThat(status.getOptionSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(status.getContracts()).isEqualTo(2L);
    assertThat(status.getBrokerOrderId()).isEqualTo("stub-K");
  }

  @Test
  void entryStatus_reportsFilledWithTheBrokerEconomics() {
    setupApprovedNvdaMocks(config());

    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(WorkflowIds.copytradeSignal("dev", "copytrade-v1", "111:0"))
                    .build());
    WorkflowStub.fromTyped(wf).start(manualBtoPayload(2L));
    wf.onFill(
        new FillSignalPayload()
            .withBrokerOrderId("stub-K")
            .withFilledQty(2L)
            .withAvgFillPrice(new BigDecimal("2.34"))
            .withFilledAt(OffsetDateTime.parse("2026-05-13T17:23:00Z")));
    WorkflowStub.fromTyped(wf).getResult(String.class);

    CopytradeEntryStatus status = wf.entryStatus();
    assertThat(status.getState()).isEqualTo(CopytradeEntryStatus.State.FILLED);
    assertThat(status.getFilledQty()).isEqualTo(2L);
    assertThat(status.getAvgFillPrice()).isEqualByComparingTo("2.34");
    assertThat(status.getOptionSymbol()).isEqualTo("NVDA  260516C00140000");
  }

  // ---------- selectPendingTtlSecs unit tests ----------

  @Test
  void selectPendingTtlSecs_paperBrokerTarget_returnsPaperTtl() {
    CopytradeSignalWorkflowImpl impl = Mockito.mock(CopytradeSignalWorkflowImpl.class);
    Mockito.when(impl.selectPendingTtlSecs(Mockito.any())).thenCallRealMethod();

    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    cfg.setPendingTtlPaperSecs(90L);
    cfg.setPendingTtlLiveSecs(30L);

    assertThat(impl.selectPendingTtlSecs(cfg)).isEqualTo(90L);
  }

  @Test
  void selectPendingTtlSecs_liveBrokerTarget_returnsLiveTtl() {
    CopytradeSignalWorkflowImpl impl = Mockito.mock(CopytradeSignalWorkflowImpl.class);
    Mockito.when(impl.selectPendingTtlSecs(Mockito.any())).thenCallRealMethod();

    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    cfg.setPendingTtlPaperSecs(90L);
    cfg.setPendingTtlLiveSecs(30L);

    assertThat(impl.selectPendingTtlSecs(cfg)).isEqualTo(30L);
  }

  @Test
  void selectPendingTtlSecs_nullBrokerTarget_returnsPaperFallback() {
    CopytradeSignalWorkflowImpl impl = Mockito.mock(CopytradeSignalWorkflowImpl.class);
    Mockito.when(impl.selectPendingTtlSecs(Mockito.any())).thenCallRealMethod();

    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(null);
    cfg.setPendingTtlPaperSecs(90L);
    cfg.setPendingTtlLiveSecs(30L);

    assertThat(impl.selectPendingTtlSecs(cfg)).isEqualTo(90L);
  }

  @Test
  void selectPendingTtlSecs_nullTtlFields_returns90LDefault() {
    CopytradeSignalWorkflowImpl impl = Mockito.mock(CopytradeSignalWorkflowImpl.class);
    Mockito.when(impl.selectPendingTtlSecs(Mockito.any())).thenCallRealMethod();

    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(null);
    cfg.setPendingTtlPaperSecs(null);
    cfg.setPendingTtlLiveSecs(null);

    assertThat(impl.selectPendingTtlSecs(cfg)).isEqualTo(90L);
  }

  // ---------- Issue #15: force_close_0dte_et passthrough onto the child PositionWorkflowInput ----

  @Test
  void startPositionWorkflow_carriesForceClose0dteEt_whenConfigured() throws Exception {
    PositionWorkflowInput child = runBtoCapturingChildInput("14:45");
    assertThat(child.getForceClose0dteEt()).isEqualTo("14:45");
  }

  @Test
  void startPositionWorkflow_passesNullForceClose0dteEt_whenAbsent() throws Exception {
    PositionWorkflowInput child = runBtoCapturingChildInput(null);
    // Null passthrough preserves the legacy 15:30 ET default in PositionWorkflowImpl.
    assertThat(child.getForceClose0dteEt()).isNull();
  }

  // ---------- Plan-2A R-AA-5: bounded-flatten exit floors passthrough onto the child ----------

  @Test
  void startPositionWorkflow_carriesExitFloors_fromStrategyConfig() throws Exception {
    PositionWorkflowInput child = runBtoCapturingChildInput("14:45");
    assertThat(child.getExitFloorAbs()).isEqualByComparingTo("0.05");
    assertThat(child.getExitFloorPct()).isEqualByComparingTo("0.5");
    assertThat(child.getExpiryDayFloor()).isEqualByComparingTo("0.01");
  }

  /**
   * Drives the BTO happy path through {@link CopytradeSignalWorkflowImpl#startPositionWorkflow} on
   * a dedicated env whose child PositionWorkflow is a {@link RecordingPositionWorkflowImpl}, then
   * returns the captured child {@link PositionWorkflowInput}. {@code forceClose0dteEt} is the
   * StrategyConfig.force_close_0dte_et value under test (null exercises the absent/legacy path).
   */
  private PositionWorkflowInput runBtoCapturingChildInput(String forceClose0dteEt)
      throws Exception {
    RecordingPositionWorkflowImpl.STARTED.clear();
    RecordingPositionWorkflowImpl.FILLS.clear();

    TestWorkflowEnvironment localEnv = TestWorkflowEnvironment.newInstance();
    try {
      localEnv.registerSearchAttribute(
          "TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      localEnv.registerSearchAttribute(
          "ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      Worker core = localEnv.newWorker(CORE_QUEUE);
      // Register the recording fake (not the real PositionWorkflowImpl) so the child input is
      // captured at start without running the full position lifecycle.
      core.registerWorkflowImplementationTypes(
          CopytradeSignalWorkflowImpl.class, RecordingPositionWorkflowImpl.class);

      AuditActivities localAudit = Mockito.mock(AuditActivities.class);
      StrategyActivities localStrategy = Mockito.mock(StrategyActivities.class);
      RiskActivities localRisk = Mockito.mock(RiskActivities.class);
      ContractActivities localContract = Mockito.mock(ContractActivities.class);
      ExecActivities localExec = Mockito.mock(ExecActivities.class);
      PositionLookupActivities localLookup = Mockito.mock(PositionLookupActivities.class);
      MarketCalendarActivities localCalendar = Mockito.mock(MarketCalendarActivities.class);
      when(localCalendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(localCalendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);

      StrategyConfig cfg = config();
      cfg.setForceClose0dteEt(forceClose0dteEt);
      // Plan-2A R-AA-5: set the bounded-flatten floors so the passthrough test can assert they
      // reach the child PositionWorkflowInput. Harmless to the force_close_0dte_et tests, which
      // don't read these fields.
      cfg.setExitFloorAbs(new java.math.BigDecimal("0.05"));
      cfg.setExitFloorPct(new java.math.BigDecimal("0.5"));
      cfg.setExpiryDayFloor(new java.math.BigDecimal("0.01"));
      when(localStrategy.get("dev", "copytrade-v1")).thenReturn(cfg);
      when(localStrategy.capitalForStrategy("dev", "copytrade-v1"))
          .thenReturn(new BigDecimal("100000"));
      when(localRisk.checkEntryWithLimit(any(), any(), any(), any(), any()))
          .thenReturn(RiskDecision.approved());
      when(localContract.resolve(any()))
          .thenReturn(
              new ContractResolveResult(
                  "NVDA  260516C00140000",
                  "NVDA",
                  LocalDate.of(2026, 5, 16),
                  new BigDecimal("140"),
                  "C",
                  ContractResolveResult.SOURCE_GENERATED));

      core.registerActivitiesImplementations(
          localAudit, localStrategy, localRisk, localContract, localLookup, localCalendar);
      Worker broker = localEnv.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
      when(localExec.placeOrder(any())).thenReturn(submittedResult("intent-fc", "brk-fc"));
      broker.registerActivitiesImplementations(localExec);
      localEnv.start();

      CopytradeSignalWorkflow wf =
          localEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  CopytradeSignalWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CORE_QUEUE)
                      .setWorkflowId("fc-bto-" + forceClose0dteEt)
                      .build());
      WorkflowStub.fromTyped(wf).start(btoPayload());

      // Forward the BTO fill so the parent spawns the child PositionWorkflow.
      long deadline = System.currentTimeMillis() + 10_000;
      FillSignalPayload fill =
          new FillSignalPayload()
              .withBrokerOrderId("brk-fc")
              .withFilledQty(5L)
              .withAvgFillPrice(new BigDecimal("0.84"))
              .withFilledAt(OffsetDateTime.parse("2026-05-24T17:00:00Z"));
      wf.onFill(fill);

      WorkflowStub.fromTyped(wf).getResult(String.class);

      // The recording child stores its start input keyed by workflow id; exactly one is expected.
      while (RecordingPositionWorkflowImpl.STARTED.isEmpty()
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertThat(RecordingPositionWorkflowImpl.STARTED).hasSize(1);
      return RecordingPositionWorkflowImpl.STARTED.values().iterator().next();
    } finally {
      localEnv.close();
    }
  }

  /** Light PositionWorkflow double: records the start input and parks until terminated. */
  public static final class RecordingPositionWorkflowImpl implements PositionWorkflow {
    static final Map<String, PositionWorkflowInput> STARTED = new ConcurrentHashMap<>();
    static final Map<String, FillSignalPayload> FILLS = new ConcurrentHashMap<>();

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
