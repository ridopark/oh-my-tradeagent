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

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
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
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class CopytradeSignalWorkflowImplTest {

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
    // Register the custom search attributes startPositionWorkflow sets on the child workflow.
    // Production registers these at cluster bootstrap; the test server requires per-test setup.
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
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
    // ExecActivities lives on the exec-svc task queue; register a separate worker.
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec);
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
  void stcAction_cacheMissAndBufferExpires_emitsOrphanStc() {
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
    runWorkflow(p);

    AuditEvent orphan = capture("OrphanSTC");
    assertThat(orphan.getSubject()).containsEntry("signal_id", "333:0");
    assertThat(((Number) orphan.getSubject().get("attempts")).intValue()).isPositive();
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
}
