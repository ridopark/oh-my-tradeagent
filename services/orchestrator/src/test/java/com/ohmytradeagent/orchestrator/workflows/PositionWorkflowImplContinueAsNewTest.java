package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Issue #752 Phase 2: continue-as-new at the quiet-position barrier. The watermark is lowered
 * reflectively (the precedent's stated reason for keeping it package-private and non-final) so a
 * handful of trail ticks pushes history across it. The flagship assertion is behavioral, not
 * structural: the carried trail must still FIRE AT THE SAME PRICE on the new run — a field-equality
 * check alone would pass a build whose carried threshold is recomputed wrongly.
 */
class PositionWorkflowImplContinueAsNewTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private static final String FUTURE_OCC_SYMBOL =
      "NVDA  "
          + LocalDate.now(ZoneId.of("America/New_York"))
              .plusYears(2)
              .format(DateTimeFormatter.ofPattern("yyMMdd"))
          + "C00140000";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private ExecActivities exec;
  private MarketCalendarActivities calendar;
  private SubscribePremiumActivity marketData;
  private GetOptionQuoteActivity optionQuote;
  private long originalWatermark;

  @BeforeEach
  void setUp() throws Exception {
    originalWatermark = PositionWorkflowImpl.historyLengthWatermark;
    env = TestWorkflowEnvironment.newInstance();
    // Production registers these at namespace setup; the carried run re-asserts them via
    // Workflow.upsertTypedSearchAttributes, and an unregistered attribute fails that command.
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    exec = Mockito.mock(ExecActivities.class);
    marketData = Mockito.mock(SubscribePremiumActivity.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);

    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ZERO);
    when(marketData.subscribePremium(any())).thenReturn(subscribedResult());
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(
            quoteOk(new BigDecimal("2.50"), new BigDecimal("2.55"), new BigDecimal("2.60")));

    coreWorker.registerActivitiesImplementations(audit, calendar);
    Worker brokerWorker = env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER);
    brokerWorker.registerActivitiesImplementations(exec);
    Worker mdWorker = env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(marketData, optionQuote);

    env.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    setWatermark(originalWatermark);
    env.close();
  }

  private static void setWatermark(long v) throws Exception {
    Field f = PositionWorkflowImpl.class.getDeclaredField("historyLengthWatermark");
    f.setAccessible(true);
    f.set(null, v);
  }

  // ---------- fixture helpers (mirroring PositionWorkflowImplTest) ----------

  /**
   * Mirrors production: every real PositionWorkflow is started (by the parent or by adoption) WITH
   * the TenantStrategy/ContractSymbol search attributes, so the roll test must too — the question
   * under test is whether they SURVIVE the roll.
   */
  private PositionWorkflow newStub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            PositionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .setTypedSearchAttributes(
                    io.temporal.common.SearchAttributes.newBuilder()
                        .set(
                            io.temporal.common.SearchAttributeKey.forKeyword("TenantStrategy"),
                            "t-dev/s-copytrade-v1")
                        .set(
                            io.temporal.common.SearchAttributeKey.forKeyword("ContractSymbol"),
                            FUTURE_OCC_SYMBOL)
                        .build())
                .build());
  }

  private PositionWorkflowInput futureInput(long qty) {
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    in.setEntrySignalId("entry-1");
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setQty(qty);
    in.setEntryPremium(new BigDecimal("2.30"));
    return in;
  }

  private static FillSignalPayload fill(String brokerOrderId, long qty, BigDecimal avg) {
    return new FillSignalPayload()
        .withBrokerOrderId(brokerOrderId)
        .withFilledQty(qty)
        .withAvgFillPrice(avg)
        .withFilledAt(OffsetDateTime.now());
  }

  private static void confirmEntry(PositionWorkflow stub, long qty) {
    stub.onFill(fill("brk-entry", qty, new BigDecimal("2.30")));
  }

  private ArmChandelierPayload armPayload(
      String posWfId, String sourceSignalId, BigDecimal peak, BigDecimal giveback) {
    ArmChandelierPayload p = new ArmChandelierPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setPositionWorkflowId(posWfId);
    p.setSourceSignalId(sourceSignalId);
    p.setPeakPremium(peak);
    p.setGivebackPct(giveback);
    return p;
  }

  private PremiumTick tick(BigDecimal premium) {
    PremiumTick t = new PremiumTick();
    t.setSchemaVersion(1L);
    t.setContractSymbol(FUTURE_OCC_SYMBOL);
    t.setPremium(premium);
    t.setRetrievedAt(OffsetDateTime.now());
    return t;
  }

  private PartialExitRequest partialExitRequest(String signalId, String posWfId, double fraction) {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setSignalId(signalId);
    req.setPositionWorkflowId(posWfId);
    req.setFraction(BigDecimal.valueOf(fraction));
    req.setRefPremium(new BigDecimal("2.85"));
    req.setReason("stc_signal");
    req.setAuthor("acme_trader");
    req.setRawLine("STC NVDA 140C @ 2.85");
    req.setOccurredAt(OffsetDateTime.of(2026, 5, 13, 17, 45, 0, 0, ZoneOffset.UTC));
    return req;
  }

  private OrderIntentResult submittedResult() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("exit-key");
    r.setBrokerOrderId("brk-exit");
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private static OptionQuoteResult quoteOk(BigDecimal bid, BigDecimal mid, BigDecimal ask) {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol(FUTURE_OCC_SYMBOL);
    r.setBid(bid);
    r.setMid(mid);
    r.setAsk(ask);
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.OK);
    return r;
  }

  private static SubscribePremiumResult subscribedResult() {
    SubscribePremiumResult r = new SubscribePremiumResult();
    r.setSchemaVersion(1L);
    r.setSubscriptionId("sub-test");
    r.setSubscribedAt(OffsetDateTime.now());
    r.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    return r;
  }

  // ---------- roll plumbing ----------

  private DescribeWorkflowExecutionResponse describe(String wfId) {
    return env.getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(env.getWorkflowClient().getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wfId).build())
                .build());
  }

  private String currentRunId(String wfId) {
    return describe(wfId).getWorkflowExecutionInfo().getExecution().getRunId();
  }

  /**
   * Feeds neutral ticks (above the trail threshold, below the peak — no ratchet, no fire) until the
   * run id changes, i.e. the roll happened. Each tick costs the signal quartet, so a lowered
   * watermark is crossed within a couple dozen ticks. Bounded; a timeout returns the unchanged run
   * id so the caller's assertion reports the real failure.
   */
  private String feedTicksUntilRolled(PositionWorkflow stub, String wfId, String initialRunId)
      throws InterruptedException {
    return feedTicksUntilRolled(stub, wfId, initialRunId, new BigDecimal("2.50"));
  }

  private String feedTicksUntilRolled(
      PositionWorkflow stub, String wfId, String initialRunId, BigDecimal neutralPremium)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      stub.chandelierTick(tick(neutralPremium));
      Thread.sleep(50);
      String runId = currentRunId(wfId);
      if (!runId.equals(initialRunId)) {
        return runId;
      }
    }
    return currentRunId(wfId);
  }

  private TrailingState waitForArmed(PositionWorkflow stub) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    TrailingState st = stub.trailingState();
    while (System.currentTimeMillis() < deadline && !st.armed()) {
      Thread.sleep(50);
      st = stub.trailingState();
    }
    return st;
  }

  /** Invocation count of {@code exec.placeOrder} so far — for delta (not absolute) assertions. */
  private long placeOrderInvocations() {
    return Mockito.mockingDetails(exec).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("placeOrder"))
        .count();
  }

  private void waitForPlaceOrderAtLeast(int n) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, org.mockito.Mockito.atLeast(n)).placeOrder(any());
        return;
      } catch (AssertionError ignored) {
        Thread.sleep(50);
      }
    }
    verify(exec, org.mockito.Mockito.atLeast(n)).placeOrder(any());
  }

  private void waitForPlaceOrderCount(int n) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        verify(exec, times(n)).placeOrder(any());
        return;
      } catch (AssertionError ignored) {
        Thread.sleep(50);
      }
    }
    verify(exec, times(n)).placeOrder(any());
  }

  private List<AuditEvent> auditKinds(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream().filter(e -> kind.equals(e.getKind())).toList();
  }

  // ---------- tests ----------

  /**
   * THE flagship: an armed trail rolls and the stop still fires at the same price. peak=3.00,
   * giveback=0.20 → threshold 2.40. Neutral 2.50 ticks push history across the lowered watermark;
   * post-roll the new run must report the SAME peak/threshold, carry the tick counter forward, and
   * a 2.40 tick must flatten. Also proves the carried run bypasses the first-fill gate: no new
   * onFill is ever sent to the new run before the trail work, and no PositionNeverFilled is
   * emitted.
   */
  @Test
  void rollCarriesTrail_stopStillFiresAtSamePrice() throws Exception {
    setWatermark(60L);
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-trail";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 5L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    TrailingState before = waitForArmed(stub);
    assertThat(before.armed()).isTrue();
    assertThat(before.thresholdPremium()).isEqualByComparingTo("2.40");

    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId).as("continue-as-new must mint a new run id").isNotEqualTo(firstRunId);

    TrailingState after = stub.trailingState();
    assertThat(after.armed()).as("trail must not silently disarm across the roll").isTrue();
    assertThat(after.peakPremium()).isEqualByComparingTo(before.peakPremium());
    assertThat(after.thresholdPremium()).isEqualByComparingTo(before.thresholdPremium());
    assertThat(after.ticksReceived())
        .as("tick counter carries (a reset reads as a dead feed)")
        .isGreaterThan(0L);

    // The carried stop must FIRE at the carried threshold.
    stub.chandelierTick(tick(new BigDecimal("2.40")));
    waitForPlaceOrderCount(1);
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    assertThat(intent.getValue().getSide()).isEqualTo(OrderIntent.Side.SELL);
    assertThat(intent.getValue().getQty()).isEqualTo(5L);

    // The carried run never awaited a first fill and never declared the position dead.
    assertThat(auditKinds("PositionNeverFilled")).isEmpty();

    // Search attributes were passed explicitly across the roll (STC dispatch's Visibility
    // fallback keys on ContractSymbol; losing it degrades silently).
    var sa = describe(wfId).getWorkflowExecutionInfo().getSearchAttributes().getIndexedFieldsMap();
    assertThat(sa).containsKeys("TenantStrategy", "ContractSymbol");
  }

  /**
   * Carried-field behavior beyond the trail: remainingQty survives, the STC dedupe set survives (a
   * redelivered pre-roll STC must NOT place a second sell), and the entry broker-order id survives
   * (#738: a late report on the ENTRY order must not be booked as an exit fill).
   */
  @Test
  void rollCarriesDedupe_remainingQty_andEntryOrderId() throws Exception {
    setWatermark(60L);
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-dedupe";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(6));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 6L);

    // A processed pre-roll STC: half exit, filled. remainingQty 6 -> 3.
    stub.partialExit(partialExitRequest("sig-pre-roll", wfId, 0.5));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-exit", 3L, new BigDecimal("2.60")));

    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);
    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId).isNotEqualTo(firstRunId);

    PositionState state = stub.positionState();
    assertThat(state.remainingQty()).as("remaining lot carries").isEqualTo(3L);

    // Redelivered pre-roll STC: the carried dedupe set must swallow it — NO NEW placement.
    // Delta assertion, not an absolute times(1): under CI load Temporal can retry the FIRST
    // placement's activity (same logical order, two mock invocations), which flaked the absolute
    // count on 2026-08-22. A real dedupe failure still trips this — it would place AFTER the
    // redelivery, moving the count.
    long placementsBeforeRedelivery = placeOrderInvocations();
    stub.partialExit(partialExitRequest("sig-pre-roll", wfId, 0.5));
    Thread.sleep(1_000);
    assertThat(placeOrderInvocations())
        .as("a redelivered pre-roll STC must not place a new order")
        .isEqualTo(placementsBeforeRedelivery);

    // A late broker report on the ENTRY order id must not be booked as an exit.
    stub.onFill(fill("brk-entry", 6L, new BigDecimal("2.30")));
    Thread.sleep(1_000);
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
  }

  /**
   * Barrier conjunct {@code tp_ratio == null}: a watchlist-exit position must NEVER roll — its
   * carry surface is deliberately out of scope. History is pushed well past the lowered watermark
   * via exit ticks; the run id must not change.
   */
  @Test
  void watchlistExitPosition_neverRolls() throws Exception {
    setWatermark(40L);
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-watchlist";
    PositionWorkflow stub = newStub(wfId);
    PositionWorkflowInput in = futureInput(4);
    in.setTpRatio(new BigDecimal("2.0"));
    in.setSlPct(new BigDecimal("0.30"));
    in.setTpPartialFraction(new BigDecimal("0.5"));
    in.setTrailGivebackPct(new BigDecimal("0.30"));
    WorkflowStub.fromTyped(stub).start(in);
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 4L);

    // Neutral exit ticks (between stop 1.61 and target 3.68 for entry 2.30): history grows far
    // past watermark 40, and the barrier must hold the roll shut every iteration.
    for (int i = 0; i < 25; i++) {
      stub.chandelierTick(tick(new BigDecimal("2.35")));
      Thread.sleep(20);
    }
    Thread.sleep(500);
    assertThat(currentRunId(wfId))
        .as("a watchlist-exit position must never continue-as-new")
        .isEqualTo(firstRunId);
  }

  /**
   * Barrier in-flight conjunct: with an exit order resting unfilled ({@code exitInFlight}), tick
   * pressure past the watermark must NOT roll — a roll would discard the in-flight order state.
   * After the fill arrives and the position is quiet again, the roll is permitted.
   */
  @Test
  void exitInFlight_blocksRoll_untilDrained() throws Exception {
    setWatermark(60L);
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-inflight";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(6));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 6L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);

    // Rest an exit order (no fill yet) — exitInFlight stays true.
    stub.partialExit(partialExitRequest("sig-rest", wfId, 0.5));
    waitForPlaceOrderCount(1);

    // Tick pressure past the watermark: the barrier must hold.
    for (int i = 0; i < 30; i++) {
      stub.chandelierTick(tick(new BigDecimal("2.50")));
      Thread.sleep(20);
    }
    Thread.sleep(500);
    assertThat(currentRunId(wfId))
        .as("an in-flight exit must block the roll")
        .isEqualTo(firstRunId);

    // Drain: the exit fills; the position is quiet again — now the roll may proceed.
    stub.onFill(fill("brk-exit", 3L, new BigDecimal("2.60")));
    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId).as("once quiet, the roll proceeds").isNotEqualTo(firstRunId);
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
  }

  /**
   * Review blocker on this PR: a partial-exit placement FAILURE schedules a next-RTH-open re-drive
   * with {@code partialPlaceRetryPending} set but {@code partialPlaceRetryArmed} still false (the
   * armed bit only flips in the instant between the timer firing and the loop draining it). That
   * scheduled-but-unfired window can span hours or a weekend — and a roll during it would discard
   * both the pending request and its timer, silently dropping the discretionary partial (the
   * 2026-06-25 QQQ incident class). The barrier must treat pending-retry as busy for the whole
   * window, then reopen once the retry resolves.
   */
  @Test
  void scheduledPartialRetry_blocksRoll_untilResolved() throws Exception {
    setWatermark(60L);
    // Long next-open so the pending-but-unfired window persists across the tick pressure below.
    when(calendar.durationUntilNextRthOpenEt()).thenReturn(Duration.ofHours(12));
    // First placement (the partial) fails non-retryably -> schedules the re-drive; the re-driven
    // placement succeeds.
    when(exec.placeOrder(any()))
        .thenThrow(
            io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                "broker 500", "test-failure"))
        .thenReturn(submittedResult());
    when(exec.cancelOrder(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            inv -> {
              OrderIntentResult r = submittedResult();
              r.setState(OrderIntentResult.State.CANCELLED);
              return r;
            });

    String wfId = "pos-can-retry-pending";
    PositionWorkflow stub = newStub(wfId);
    PositionWorkflowInput in = futureInput(6);
    // Long exit-fill TTL: env.sleep only advances virtual time to just past the retry timer, so
    // the re-driven placement is still awaiting its fill (clock frozen) when the test delivers it —
    // otherwise the TTL elapses inside the sleep and the reprice/cancel cascade consumes the retry.
    in.setExitFillTtlSecs(600L);
    WorkflowStub.fromTyped(stub).start(in);
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 6L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);

    // The partial fails to place -> pending retry latched, timer armed 12h out.
    stub.partialExit(partialExitRequest("sig-fail", wfId, 0.5));
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline
        && auditKinds("PartialExitPlaceFailed").isEmpty()) {
      Thread.sleep(50);
    }
    assertThat(auditKinds("PartialExitPlaceFailed")).isNotEmpty();

    // Tick pressure far past the watermark during the scheduled-but-unfired window: the barrier
    // must hold, else the pending retry and its timer are silently lost at the run boundary.
    for (int i = 0; i < 30; i++) {
      stub.chandelierTick(tick(new BigDecimal("2.50")));
      Thread.sleep(20);
    }
    Thread.sleep(500);
    assertThat(currentRunId(wfId))
        .as("a scheduled partial-exit retry must block the roll for its whole pending window")
        .isEqualTo(firstRunId);

    // The retry timer fires; the re-driven partial places and (virtual clock frozen inside its
    // fill window) fills; the position is quiet again — the barrier must reopen.
    env.sleep(Duration.ofHours(12).plusMinutes(1));
    waitForPlaceOrderAtLeast(2);
    stub.onFill(fill("brk-exit", 3L, new BigDecimal("2.60")));
    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId).as("once the retry resolved, the roll proceeds").isNotEqualTo(firstRunId);
    assertThat(stub.positionState().remainingQty()).isEqualTo(3L);
  }

  // ---------- #807 breakeven-floor state across the roll ----------

  /**
   * PR #807 review blocker: {@code trailArmedByOperator} must survive the roll. An OPERATOR-armed
   * UNDERWATER trail (threshold far below cost — the operator chose that) whose flag is lost at the
   * roll becomes subject to the auto breakeven floor, which raises the threshold to basis and stops
   * the position out on the next tick. This is the exact live DRAM shape (basis ~3.3, 45% trail
   * ~1.9).
   */
  @Test
  void operatorArmedUnderwaterTrail_rollDoesNotSubjectItToTheBreakevenFloor() throws Exception {
    setWatermark(60L);
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-807-operator";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 5L); // basis 2.30

    // OPERATOR arm at 45%: anchor resolves off the mocked quote (mid 2.55) -> threshold ~1.40,
    // far below basis — deliberate, the operator was quoted this stop.
    ArmTrailRequest arm = new ArmTrailRequest();
    arm.setSchemaVersion(1L);
    arm.setOperatorId("ops-1");
    arm.setGivebackPct(new BigDecimal("0.45"));
    stub.armTrail(arm);
    waitForArmed(stub);

    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId).isNotEqualTo(firstRunId);

    // A tick well below basis but ABOVE the operator threshold: the exemption must hold — no fire.
    stub.chandelierTick(tick(new BigDecimal("2.00")));
    Thread.sleep(1_000);
    verify(exec, org.mockito.Mockito.never()).placeOrder(any());
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
  }

  /**
   * PR #807 review blocker, other half: {@code entryFillPrice} (the FILL basis) must survive the
   * roll. The entry fills at 2.60 while the signal quoted 2.30; post-roll the floor must still bind
   * at 2.60 — a lost fill price falls back to the 2.30 quote and a 2.50 tick would ride 30 cents
   * under water without firing.
   */
  @Test
  void autoArmedTrail_rollKeepsTheFillBasis_floorStillBindsAtCost() throws Exception {
    setWatermark(60L);
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-807-basis";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    String firstRunId = currentRunId(wfId);
    // Entry fills ABOVE the signal quote: fill truth 2.60, input entry_premium 2.30.
    stub.onFill(fill("brk-entry", 5L, new BigDecimal("2.60")));

    // AUTO arm (the de-risk/chandelier signal path — floor applies): peak 3.20, gb 0.20 ->
    // raw threshold 2.56, floored to the 2.60 basis.
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.20"), new BigDecimal("0.20")));
    waitForArmed(stub);

    // Neutral ticks must clear the FLOORED threshold (2.60), not just the raw one — 2.70.
    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId, new BigDecimal("2.70"));
    assertThat(newRunId).isNotEqualTo(firstRunId);

    // 2.58: above the raw threshold AND above the 2.30 quote-fallback floor, below the 2.60 fill
    // basis — fires ONLY if the fill basis survived the roll.
    stub.chandelierTick(tick(new BigDecimal("2.58")));
    waitForPlaceOrderCount(1);
    ArgumentCaptor<OrderIntent> intent = ArgumentCaptor.forClass(OrderIntent.class);
    verify(exec, atLeastOnce()).placeOrder(intent.capture());
    assertThat(intent.getValue().getSide()).isEqualTo(OrderIntent.Side.SELL);
  }

  /**
   * Issue #826: an operator Update parked on its audit activity must block the roll. The disarm
   * handler resets the trail FIRST and audits AFTER, so while the audit is latched the position
   * looks perfectly quiet to every other barrier conjunct — without {@code
   * Workflow.isEveryHandlerFinished()} the roll fires here and continue-as-new aborts the parked
   * handler: the operator's call errors even though the disarm already applied. The test parks the
   * handler, pushes history far past the watermark (no roll may happen), then releases the audit
   * and requires BOTH that the update completes with DISARMED and that the roll then proceeds.
   */
  @Test
  void inFlightUpdateHandler_blocksRoll_untilFinished() throws Exception {
    setWatermark(60L);
    java.util.concurrent.CountDownLatch auditGate = new java.util.concurrent.CountDownLatch(1);
    Mockito.doAnswer(
            inv -> {
              AuditEvent e = inv.getArgument(0);
              if ("TrailDisarmed".equals(e.getKind())) {
                auditGate.await(60, java.util.concurrent.TimeUnit.SECONDS);
              }
              return null;
            })
        .when(audit)
        .log(any());

    String wfId = "pos-can-update-parked";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 5L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);

    com.ohmytradeagent.contract.DisarmTrailRequest disarm =
        new com.ohmytradeagent.contract.DisarmTrailRequest();
    disarm.setSchemaVersion(1L);
    disarm.setOperatorId("ops-1");
    disarm.setReason("826 barrier test");
    io.temporal.client.WorkflowUpdateHandle<com.ohmytradeagent.contract.DisarmTrailResult> handle =
        WorkflowStub.fromTyped(stub)
            .startUpdate(
                "disarm_trail",
                io.temporal.client.WorkflowUpdateStage.ACCEPTED,
                com.ohmytradeagent.contract.DisarmTrailResult.class,
                disarm);

    // The handler is genuinely mid-flight: its state reset is already visible while its audit
    // activity is still latched open.
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline && stub.trailingState().armed()) {
      Thread.sleep(50);
    }
    assertThat(stub.trailingState().armed()).isFalse();

    // Tick pressure far past the watermark: everything else is quiet, so only the unfinished
    // handler stands between this position and a roll that would abort the operator's update.
    for (int i = 0; i < 30; i++) {
      stub.chandelierTick(tick(new BigDecimal("2.50")));
      Thread.sleep(20);
    }
    Thread.sleep(500);
    assertThat(currentRunId(wfId))
        .as("a parked update handler must block the roll")
        .isEqualTo(firstRunId);

    auditGate.countDown();
    com.ohmytradeagent.contract.DisarmTrailResult result =
        handle.getResultAsync().get(50, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(result.getStatus())
        .as("the update must complete, not be aborted by a roll")
        .isEqualTo(com.ohmytradeagent.contract.DisarmTrailResult.Status.DISARMED);

    // Handler finished — the barrier reopens and the roll proceeds.
    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId)
        .as("once the handler finishes, the roll proceeds")
        .isNotEqualTo(firstRunId);
    assertThat(stub.trailingState().armed()).isFalse();
    assertThat(stub.positionState().remainingQty()).isEqualTo(5L);
  }

  /**
   * Issue #826 companion: the ARM window. Unlike disarm, a parked {@code arm_trail} handler runs
   * with {@code trailingArmed} already true (state is set before its ChandelierArmed audit), so
   * this pins the barrier for the opposite trail state — a mutant that opens the barrier whenever
   * {@code trailingArmed} is true (e.g. {@code trailingArmed || isEveryHandlerFinished()}) passes
   * the disarm test but is killed here.
   */
  @Test
  void inFlightArmTrailHandler_blocksRoll_untilFinished() throws Exception {
    setWatermark(60L);
    java.util.concurrent.CountDownLatch auditGate = new java.util.concurrent.CountDownLatch(1);
    Mockito.doAnswer(
            inv -> {
              AuditEvent e = inv.getArgument(0);
              if ("ChandelierArmed".equals(e.getKind())) {
                auditGate.await(60, java.util.concurrent.TimeUnit.SECONDS);
              }
              return null;
            })
        .when(audit)
        .log(any());

    String wfId = "pos-can-arm-parked";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 5L);

    ArmTrailRequest arm = new ArmTrailRequest();
    arm.setSchemaVersion(1L);
    arm.setOperatorId("ops-1");
    arm.setPeakPremium(new BigDecimal("3.00"));
    arm.setGivebackPct(new BigDecimal("0.20"));
    io.temporal.client.WorkflowUpdateHandle<com.ohmytradeagent.contract.ArmTrailResult> handle =
        WorkflowStub.fromTyped(stub)
            .startUpdate(
                "arm_trail",
                io.temporal.client.WorkflowUpdateStage.ACCEPTED,
                com.ohmytradeagent.contract.ArmTrailResult.class,
                arm);

    // Mid-flight discriminator for THIS window: armed is already true while the ChandelierArmed
    // audit is still latched open.
    waitForArmed(stub);
    assertThat(stub.trailingState().armed()).isTrue();

    // Neutral ticks (below peak 3.00, above threshold 2.40) push history past the watermark while
    // the handler is parked WITH an armed trail — the arm-window twin of the disarm test.
    for (int i = 0; i < 30; i++) {
      stub.chandelierTick(tick(new BigDecimal("2.50")));
      Thread.sleep(20);
    }
    Thread.sleep(500);
    assertThat(currentRunId(wfId))
        .as("a parked arm_trail handler must block the roll")
        .isEqualTo(firstRunId);

    auditGate.countDown();
    com.ohmytradeagent.contract.ArmTrailResult result =
        handle.getResultAsync().get(50, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(result.getStatus())
        .as("the update must complete, not be aborted by a roll")
        .isEqualTo(com.ohmytradeagent.contract.ArmTrailResult.Status.ARMED);

    // Handler finished — the roll proceeds and the just-armed trail survives it intact.
    String newRunId = feedTicksUntilRolled(stub, wfId, firstRunId);
    assertThat(newRunId)
        .as("once the handler finishes, the roll proceeds")
        .isNotEqualTo(firstRunId);
    TrailingState after = stub.trailingState();
    assertThat(after.armed()).isTrue();
    assertThat(after.peakPremium()).isEqualByComparingTo(new BigDecimal("3.00"));
  }

  /**
   * Issue #837 (companion to the #826 barrier tests above, reusing this file's parked-handler
   * machinery): NORMAL COMPLETION must also wait for an in-flight handler. A full exit drains
   * {@code remainingQty} to 0 while a {@code disarm_trail} handler is parked on its audit activity
   * — without the completion await, {@code run()} returns and the parked handler is abandoned
   * (state applied, operator's call errors), the exact #826 symptom at a different exit door. The
   * watermark stays at its 10k default here, so the roll barrier never participates.
   */
  @Test
  void completion_awaitsParkedUpdateHandler() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    java.util.concurrent.CountDownLatch auditGate = new java.util.concurrent.CountDownLatch(1);
    Mockito.doAnswer(
            inv -> {
              AuditEvent e = inv.getArgument(0);
              if ("TrailDisarmed".equals(e.getKind())) {
                auditGate.await(60, java.util.concurrent.TimeUnit.SECONDS);
              }
              return null;
            })
        .when(audit)
        .log(any());

    String wfId = "pos-close-update-parked";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(4));
    confirmEntry(stub, 4L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);

    com.ohmytradeagent.contract.DisarmTrailRequest disarm =
        new com.ohmytradeagent.contract.DisarmTrailRequest();
    disarm.setSchemaVersion(1L);
    disarm.setOperatorId("ops-1");
    disarm.setReason("837 completion test");
    io.temporal.client.WorkflowUpdateHandle<com.ohmytradeagent.contract.DisarmTrailResult> handle =
        WorkflowStub.fromTyped(stub)
            .startUpdate(
                "disarm_trail",
                io.temporal.client.WorkflowUpdateStage.ACCEPTED,
                com.ohmytradeagent.contract.DisarmTrailResult.class,
                disarm);
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline && stub.trailingState().armed()) {
      Thread.sleep(50);
    }
    assertThat(stub.trailingState().armed()).isFalse();

    // Full exit while the handler is parked: places, fills, drains remainingQty to 0 — the run()
    // epilogue is now free to return, and only the completion await stands between it and
    // abandoning the operator's update.
    stub.partialExit(partialExitRequest("sig-full", wfId, 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-exit", 4L, new BigDecimal("2.60")));

    Thread.sleep(1000);
    assertThat(describe(wfId).getWorkflowExecutionInfo().getStatus())
        .as("the workflow must stay RUNNING while an update handler is parked")
        .isEqualTo(
            io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

    auditGate.countDown();
    com.ohmytradeagent.contract.DisarmTrailResult result =
        handle.getResultAsync().get(50, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(result.getStatus())
        .as("the update must complete, not be abandoned by workflow completion")
        .isEqualTo(com.ohmytradeagent.contract.DisarmTrailResult.Status.DISARMED);
    // And the workflow now completes normally.
    WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(describe(wfId).getWorkflowExecutionInfo().getStatus())
        .isEqualTo(
            io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
  }

  /**
   * Issue #837, never-filled door (review catch: a mutant deleting the awaits at ONLY the
   * never-filled + worthless-expiry sites survived the suite). {@code arm_trail} has no fill gate,
   * so an operator arm can park on its ChandelierArmed audit while the first-fill TTL expires — the
   * never-filled return must wait for it exactly like the epilogue doors.
   */
  @Test
  void neverFilledCompletion_awaitsParkedArmTrailHandler() throws Exception {
    java.util.concurrent.CountDownLatch auditGate = new java.util.concurrent.CountDownLatch(1);
    Mockito.doAnswer(
            inv -> {
              AuditEvent e = inv.getArgument(0);
              if ("ChandelierArmed".equals(e.getKind())) {
                auditGate.await(60, java.util.concurrent.TimeUnit.SECONDS);
              }
              return null;
            })
        .when(audit)
        .log(any());

    String wfId = "pos-neverfilled-arm-parked";
    PositionWorkflow stub = newStub(wfId);
    PositionWorkflowInput in = futureInput(5);
    in.setFirstFillTtlSecs(2L);
    WorkflowStub.fromTyped(stub).start(in);
    // NO confirmEntry — the position never fills and the 2s TTL expires underneath the parked arm.

    ArmTrailRequest arm = new ArmTrailRequest();
    arm.setSchemaVersion(1L);
    arm.setOperatorId("ops-1");
    arm.setPeakPremium(new BigDecimal("3.00"));
    arm.setGivebackPct(new BigDecimal("0.20"));
    io.temporal.client.WorkflowUpdateHandle<com.ohmytradeagent.contract.ArmTrailResult> handle =
        WorkflowStub.fromTyped(stub)
            .startUpdate(
                "arm_trail",
                io.temporal.client.WorkflowUpdateStage.ACCEPTED,
                com.ohmytradeagent.contract.ArmTrailResult.class,
                arm);
    waitForArmed(stub);
    assertThat(stub.trailingState().armed()).isTrue();

    // Let the first-fill TTL fire (PositionNeverFilled audits and run() reaches the never-filled
    // return) while the arm handler is still parked on its latched audit.
    Thread.sleep(3500);
    assertThat(describe(wfId).getWorkflowExecutionInfo().getStatus())
        .as("the never-filled return must stay RUNNING while an update handler is parked")
        .isEqualTo(
            io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

    auditGate.countDown();
    com.ohmytradeagent.contract.ArmTrailResult result =
        handle.getResultAsync().get(50, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(result.getStatus())
        .isEqualTo(com.ohmytradeagent.contract.ArmTrailResult.Status.ARMED);
    WorkflowStub.fromTyped(stub).getResult(String.class);
    assertThat(describe(wfId).getWorkflowExecutionInfo().getStatus())
        .isEqualTo(
            io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
  }

  /**
   * Issue #840 (found by #837's review): an {@code arm_trail} landing in the alive-but-done window
   * — position drained to zero while a sibling handler holds the workflow open at the completion
   * await — must be REJECTED ({@code position_drained}) and must open NO premium subscription,
   * instead of returning ARMED on a position that no longer exists and leaking the subscription.
   */
  @Test
  void armTrail_onDrainedPosition_rejectsAndOpensNoSubscription() throws Exception {
    when(exec.placeOrder(any())).thenReturn(submittedResult());
    java.util.concurrent.CountDownLatch auditGate = new java.util.concurrent.CountDownLatch(1);
    Mockito.doAnswer(
            inv -> {
              AuditEvent e = inv.getArgument(0);
              if ("TrailDisarmed".equals(e.getKind())) {
                auditGate.await(60, java.util.concurrent.TimeUnit.SECONDS);
              }
              return null;
            })
        .when(audit)
        .log(any());

    String wfId = "pos-drained-arm-rejected";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(4));
    confirmEntry(stub, 4L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);

    com.ohmytradeagent.contract.DisarmTrailRequest disarm =
        new com.ohmytradeagent.contract.DisarmTrailRequest();
    disarm.setSchemaVersion(1L);
    disarm.setOperatorId("ops-1");
    disarm.setReason("hold the workflow open");
    io.temporal.client.WorkflowUpdateHandle<com.ohmytradeagent.contract.DisarmTrailResult>
        disarmHandle =
            WorkflowStub.fromTyped(stub)
                .startUpdate(
                    "disarm_trail",
                    io.temporal.client.WorkflowUpdateStage.ACCEPTED,
                    com.ohmytradeagent.contract.DisarmTrailResult.class,
                    disarm);
    long deadline = System.currentTimeMillis() + 50_000;
    while (System.currentTimeMillis() < deadline && stub.trailingState().armed()) {
      Thread.sleep(50);
    }
    assertThat(stub.trailingState().armed()).isFalse();

    // Drain the lot: the workflow is now alive-but-done, held open only by the parked disarm.
    stub.partialExit(partialExitRequest("sig-full", wfId, 1.0));
    waitForPlaceOrderCount(1);
    stub.onFill(fill("brk-exit", 4L, new BigDecimal("2.60")));
    Thread.sleep(500);
    assertThat(stub.positionState().remainingQty()).isZero();

    long subscriptionsBefore =
        Mockito.mockingDetails(marketData).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("subscribePremium"))
            .count();

    ArmTrailRequest arm = new ArmTrailRequest();
    arm.setSchemaVersion(1L);
    arm.setOperatorId("ops-2");
    arm.setPeakPremium(new BigDecimal("3.00"));
    arm.setGivebackPct(new BigDecimal("0.20"));
    com.ohmytradeagent.contract.ArmTrailResult armResult =
        WorkflowStub.fromTyped(stub)
            .update("arm_trail", com.ohmytradeagent.contract.ArmTrailResult.class, arm);

    assertThat(armResult.getStatus())
        .as("arming a drained position must reject, not report ARMED")
        .isEqualTo(com.ohmytradeagent.contract.ArmTrailResult.Status.REJECTED);
    assertThat(armResult.getReason()).isEqualTo("position_drained");
    long subscriptionsAfter =
        Mockito.mockingDetails(marketData).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("subscribePremium"))
            .count();
    assertThat(subscriptionsAfter)
        .as("a rejected arm must open NO premium subscription")
        .isEqualTo(subscriptionsBefore);
    assertThat(stub.trailingState().armed()).isFalse();

    auditGate.countDown();
    assertThat(
            disarmHandle
                .getResultAsync()
                .get(50, java.util.concurrent.TimeUnit.SECONDS)
                .getStatus())
        .isEqualTo(com.ohmytradeagent.contract.DisarmTrailResult.Status.DISARMED);
    WorkflowStub.fromTyped(stub).getResult(String.class);
  }

  /** Below the watermark nothing rolls, no matter how quiet the position is. */
  @Test
  void belowWatermark_neverRolls() throws Exception {
    // Default 10k watermark: a handful of ticks stays far below it.
    when(exec.placeOrder(any())).thenReturn(submittedResult());

    String wfId = "pos-can-below";
    PositionWorkflow stub = newStub(wfId);
    WorkflowStub.fromTyped(stub).start(futureInput(5));
    String firstRunId = currentRunId(wfId);
    confirmEntry(stub, 5L);
    stub.armChandelier(armPayload(wfId, "src-1", new BigDecimal("3.00"), new BigDecimal("0.20")));
    waitForArmed(stub);
    for (int i = 0; i < 10; i++) {
      stub.chandelierTick(tick(new BigDecimal("2.50")));
      Thread.sleep(20);
    }
    Thread.sleep(500);
    assertThat(currentRunId(wfId)).isEqualTo(firstRunId);
  }
}
