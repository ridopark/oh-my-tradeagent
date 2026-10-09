package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.DisarmTrailRequest;
import com.ohmytradeagent.contract.DisarmTrailResult;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * Issue #752 (PLAN-2026-10-08 Phase 3, G4): the continue-as-new roll against a REAL Temporal
 * server. Every other roll test runs on the in-memory {@code TestWorkflowEnvironment}; this one
 * proves search-attribute carryover, the Visibility window the account-cap and tenant-delete
 * queries see, and that an Update or Signal racing the roll is never lost.
 *
 * <p>Opt-in, skipped unless {@code -Dtemporal.it.target=host:port}. The server needs the two
 * production search attributes, e.g. {@code temporal server start-dev --port 17233
 * --search-attribute TenantStrategy=Keyword --search-attribute ContractSymbol=Keyword}. Workers run
 * in this JVM, so the watermark is lowered reflectively as in {@code
 * PositionWorkflowImplContinueAsNewTest}. Each test uses a fresh tenant id so Visibility queries
 * see only its own workflow.
 */
@EnabledIfSystemProperty(named = "temporal.it.target", matches = ".+")
class PositionWorkflowContinueAsNewRealServerIT {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String STRATEGY = "copytrade-v1";
  private static final long WATERMARK = 60L;
  private static final String OCC =
      "NVDA  "
          + LocalDate.now(ZoneId.of("America/New_York"))
              .plusYears(2)
              .format(DateTimeFormatter.ofPattern("yyMMdd"))
          + "C00140000";

  private TestWorkflowEnvironment env;
  private WorkflowClient client;
  private AuditActivities audit;
  private ExecActivities exec;
  private ScheduledExecutorService broker;
  private final ConcurrentLinkedQueue<OrderIntent> placed = new ConcurrentLinkedQueue<>();
  private final List<String> started = new ArrayList<>();
  private String tenant;
  private long originalWatermark;

  @BeforeEach
  void setUp() throws Exception {
    originalWatermark = PositionWorkflowImpl.historyLengthWatermark;
    setWatermark(WATERMARK);
    tenant = "it752" + UUID.randomUUID().toString().substring(0, 8);
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setUseExternalService(true)
                .setTarget(System.getProperty("temporal.it.target"))
                .build());
    client = env.getWorkflowClient();
    broker = Executors.newSingleThreadScheduledExecutor();

    audit = Mockito.mock(AuditActivities.class);
    exec = Mockito.mock(ExecActivities.class);
    MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
    SubscribePremiumActivity marketData = Mockito.mock(SubscribePremiumActivity.class);
    GetOptionQuoteActivity optionQuote = Mockito.mock(GetOptionQuoteActivity.class);
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
    when(calendar.durationUntilExpiryFlattenEt(any(), Mockito.anyLong(), any()))
        .thenReturn(Duration.ZERO);
    when(marketData.subscribePremium(any())).thenReturn(subscribed());
    when(optionQuote.getOptionQuote(any())).thenReturn(quote());
    // A broker that fills every order in full ~300ms after placement, signalled by workflow id
    // only (no run id) — exactly how the production fill listener reaches a rolled position.
    when(exec.placeOrder(any()))
        .thenAnswer(
            inv -> {
              OrderIntent intent = inv.getArgument(0);
              placed.add(intent);
              String wfId = intent.getIntentKey().substring(0, intent.getIntentKey().indexOf(':'));
              broker.schedule(
                  () ->
                      client
                          .newUntypedWorkflowStub(wfId)
                          .signal(
                              "onFill",
                              fill(brokerId(intent), intent.getQty(), new BigDecimal("2.50"))),
                  300,
                  TimeUnit.MILLISECONDS);
              OrderIntentResult r = new OrderIntentResult();
              r.setSchemaVersion(1L);
              r.setIntentKey(intent.getIntentKey());
              r.setBrokerOrderId(brokerId(intent));
              r.setState(OrderIntentResult.State.SUBMITTED);
              r.setLastStateAt(OffsetDateTime.now());
              return r;
            });

    Worker core = env.newWorker(CORE_QUEUE);
    core.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);
    core.registerActivitiesImplementations(audit, calendar);
    env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER)
        .registerActivitiesImplementations(exec);
    env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE)
        .registerActivitiesImplementations(marketData, optionQuote);
    env.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    for (String wfId : started) {
      try {
        client.newUntypedWorkflowStub(wfId).terminate("IT cleanup");
      } catch (RuntimeException ignored) {
        // already closed
      }
    }
    broker.shutdownNow();
    setWatermark(originalWatermark);
    env.close();
  }

  // ---------- tests ----------

  /**
   * Search attributes on the carried run, and the Visibility window. A poller runs the account-cap
   * query ({@code AccountPnlActivitiesImpl}) and the tenant-delete guard query ({@code
   * OpenPositionWorkflowChecker}) every 50ms across the roll and records the longest stretch in
   * which the position was invisible to them.
   */
  @Test
  void realServer_searchAttributesPresentOnNewRun() throws Exception {
    String wfId = startConfirmedArmedPosition(5, "3.00", "0.20");
    String ts = WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenant, STRATEGY));
    String accountCapQuery = accountCapQuery();
    String tenantDeleteQuery =
        "TenantStrategy = '"
            + ts
            + "' AND WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'WORKFLOW_EXECUTION_STATUS_RUNNING'";
    awaitCondition(() -> visible(accountCapQuery), "initial Visibility of the first run");

    VisibilityGapProbe capProbe = new VisibilityGapProbe(accountCapQuery);
    VisibilityGapProbe deleteProbe = new VisibilityGapProbe(tenantDeleteQuery);
    // Control: no search-attribute predicate, so its gap is pure Visibility lag on the roll.
    VisibilityGapProbe idProbe =
        new VisibilityGapProbe("WorkflowId='" + wfId + "' AND ExecutionStatus='Running'");
    capProbe.start();
    deleteProbe.start();
    idProbe.start();
    String firstRun = currentRunId(wfId);
    String newRun = tickUntilRolled(wfId, firstRun);
    // Keep probing well past the roll so a late re-appearance is measured, not missed.
    Thread.sleep(3_000);
    capProbe.stop();
    deleteProbe.stop();
    idProbe.stop();

    assertThat(newRun).isNotEqualTo(firstRun);
    Map<String, io.temporal.api.common.v1.Payload> sa =
        describe(wfId).getWorkflowExecutionInfo().getSearchAttributes().getIndexedFieldsMap();
    assertThat(sa).containsKeys("TenantStrategy", "ContractSymbol");
    boolean inheritedAtStart =
        startedEventOf(wfId, newRun)
            .getWorkflowExecutionStartedEventAttributes()
            .getSearchAttributes()
            .getIndexedFieldsMap()
            .containsKey("TenantStrategy");
    System.out.printf(
        "[IT-752] server inherited TenantStrategy on the CAN start event: %s%n", inheritedAtStart);
    System.out.printf(
        "[IT-752] account-cap query: polls=%d zeroHitPolls=%d longestInvisibleMs=%d%n",
        capProbe.polls, capProbe.zeroPolls, capProbe.longestGapMs);
    System.out.printf(
        "[IT-752] tenant-delete query: polls=%d zeroHitPolls=%d longestInvisibleMs=%d%n",
        deleteProbe.polls, deleteProbe.zeroPolls, deleteProbe.longestGapMs);
    System.out.printf(
        "[IT-752] id-only control query: polls=%d zeroHitPolls=%d longestInvisibleMs=%d%n",
        idProbe.polls, idProbe.zeroPolls, idProbe.longestGapMs);
    assertThat(capProbe.polls).as("the probe actually ran across the roll").isGreaterThan(10);
    assertThat(visible(accountCapQuery)).as("the carried run is visible after the roll").isTrue();
  }

  /** The trail and the five positionState fields read identically across the roll, then fire. */
  @Test
  void realServer_trailAndPositionStateIdenticalAcrossRoll() throws Exception {
    String wfId = startConfirmedArmedPosition(5, "3.00", "0.20");
    PositionWorkflow stub = byId(wfId);
    TrailingState trailBefore = stub.trailingState();
    PositionState stateBefore = stub.positionState();

    String firstRun = currentRunId(wfId);
    assertThat(tickUntilRolled(wfId, firstRun)).isNotEqualTo(firstRun);

    TrailingState trailAfter = stub.trailingState();
    PositionState stateAfter = stub.positionState();
    assertThat(trailAfter.armed()).isTrue();
    assertThat(trailAfter.peakPremium()).isEqualByComparingTo(trailBefore.peakPremium());
    assertThat(trailAfter.thresholdPremium()).isEqualByComparingTo(trailBefore.thresholdPremium());
    assertThat(stateAfter.contractSymbol()).isEqualTo(stateBefore.contractSymbol());
    assertThat(stateAfter.remainingQty()).isEqualTo(stateBefore.remainingQty());
    assertThat(stateAfter.entryPremium()).isEqualByComparingTo(stateBefore.entryPremium());
    assertThat(stateAfter.entryAt()).isEqualTo(stateBefore.entryAt());
    assertThat(stateAfter.partialExited()).isEqualTo(stateBefore.partialExited());

    stub.chandelierTick(tick("2.40"));
    awaitCondition(() -> !placed.isEmpty(), "chandelier flatten placed");
    OrderIntent flatten = placed.peek();
    assertThat(flatten.getSide()).isEqualTo(OrderIntent.Side.SELL);
    assertThat(flatten.getQty()).isEqualTo(5L);
    awaitCondition(() -> isClosed(wfId), "position closed after the flatten fill");
  }

  /**
   * An operator {@code partial_close} and {@code disarm_trail} sent while a tick burst drives the
   * position across the watermark. Parameterised on how many events short of the watermark the race
   * starts, to land the Updates on different sides of the roll. Each Update must return a success
   * status, its effect must be on the final run, and its audit row must exist exactly once.
   */
  @ParameterizedTest
  @ValueSource(ints = {12, 4, 0})
  void realServer_updateRacingTheRoll_isNeverLost(int eventsShortOfWatermark) throws Exception {
    String wfId = startConfirmedArmedPosition(6, "3.00", "0.20");
    String firstRun = currentRunId(wfId);
    tickUntilHistoryAtLeast(wfId, WATERMARK - eventsShortOfWatermark);

    AtomicBoolean bursting = new AtomicBoolean(true);
    CompletableFuture<Void> burst = CompletableFuture.runAsync(() -> tickBurst(wfId, bursting));
    CompletableFuture<PartialCloseResult> trim =
        CompletableFuture.supplyAsync(
            () ->
                client
                    .newUntypedWorkflowStub(wfId)
                    .update("partial_close", PartialCloseResult.class, trimRequest()));
    CompletableFuture<DisarmTrailResult> disarm =
        CompletableFuture.supplyAsync(
            () ->
                client
                    .newUntypedWorkflowStub(wfId)
                    .update("disarm_trail", DisarmTrailResult.class, disarmRequest()));
    PartialCloseResult trimResult = trim.get(60, TimeUnit.SECONDS);
    DisarmTrailResult disarmResult = disarm.get(60, TimeUnit.SECONDS);
    String runWhenUpdatesReturned = currentRunId(wfId);
    bursting.set(false);
    burst.get(60, TimeUnit.SECONDS);

    assertThat(trimResult.getStatus()).isEqualTo(PartialCloseResult.Status.ACCEPTED);
    assertThat(disarmResult.getStatus()).isEqualTo(DisarmTrailResult.Status.DISARMED);

    PositionWorkflow stub = byId(wfId);
    awaitCondition(() -> stub.positionState().remainingQty() == 3L, "trim filled");
    String finalRun = tickUntilRolled(wfId, firstRun);
    System.out.printf(
        "[IT-752] update race (short=%d): rolled before Updates returned=%s, rolled at all=%s%n",
        eventsShortOfWatermark,
        !runWhenUpdatesReturned.equals(firstRun),
        !finalRun.equals(firstRun));

    assertThat(finalRun).as("the position must still roll once quiet").isNotEqualTo(firstRun);
    assertThat(stub.positionState().remainingQty())
        .as("trim effect on the final run")
        .isEqualTo(3L);
    assertThat(stub.trailingState().armed()).as("disarm effect on the final run").isFalse();
    assertThat(distinctAuditRows(wfId, "OperatorTrimRequested")).isEqualTo(1);
    assertThat(distinctAuditRows(wfId, "TrailDisarmed")).isEqualTo(1);
  }

  /**
   * An STC {@code partialExit} sent in the same burst that crosses the watermark places exactly one
   * order, and a redelivery once the carried run is running is swallowed by the carried {@code
   * processedSignalIds}.
   */
  @ParameterizedTest
  @ValueSource(ints = {4, 0})
  void realServer_signalRacingTheRoll_processedOnce(int eventsShortOfWatermark) throws Exception {
    String wfId = stcFilledAcrossTheRoll(eventsShortOfWatermark);
    String carried = currentRunId(wfId);
    // run() has assigned input once the carried run has recorded its startup upsert.
    awaitCondition(
        () ->
            historyOf(wfId, carried).stream()
                .anyMatch(
                    e ->
                        e.getEventType() == EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES),
        "carried run past its startup upsert");
    byId(wfId).partialExit(stc(wfId, "sig-race"));
    Thread.sleep(2_000);

    assertThat(sigRaceOrders()).as("one STC, one order — across the roll").hasSize(1);
    assertThat(byId(wfId).positionState().remainingQty()).isEqualTo(3L);
  }

  /**
   * #958: a redelivery that lands in the carried run's FIRST workflow task, before {@code run()}
   * assigns {@code input}, must still be deduped against the carried processedSignalIds — exactly
   * one placement whether or not a given run hits the window. The intent-key pin stays as the
   * backstop: a re-placement under the SAME key is absorbed by exec's journal and the broker's
   * client_order_id.
   */
  @Test
  void realServer_redeliveryAtCarriedRunStart_neverPlacesUnderANewIntentKey() throws Exception {
    String wfId = "it752-pos-" + UUID.randomUUID();
    startConfirmedArmedPosition(wfId, 6, "3.00", "0.20");
    PositionWorkflow stub = byId(wfId);
    String firstRun = currentRunId(wfId);
    stub.partialExit(stc(wfId, "sig-race"));
    awaitCondition(() -> stub.positionState().remainingQty() == 3L, "STC filled");
    String carried = tickUntilRolled(wfId, firstRun);
    stub.partialExit(stc(wfId, "sig-race"));
    assertThat(carried).isNotEqualTo(firstRun);
    Thread.sleep(3_000);

    List<String> orders = sigRaceOrders();
    System.out.printf(
        "[IT-752] redelivery at carried-run start: placements=%d %s%n", orders.size(), orders);
    assertThat(orders).as("one STC, one order — even in the carried run's first task").hasSize(1);
    assertThat(placed.stream().filter(i -> i.getIntentKey().contains(":exit:sig-race")))
        .extracting(OrderIntent::getIntentKey)
        .as("every placement for one STC must share one intent key")
        .containsOnly(wfId + ":exit:sig-race");
  }

  private String stcFilledAcrossTheRoll(int eventsShortOfWatermark) throws Exception {
    String wfId = "it752-pos-" + UUID.randomUUID();
    startConfirmedArmedPosition(wfId, 6, "3.00", "0.20");
    String firstRun = currentRunId(wfId);
    tickUntilHistoryAtLeast(wfId, WATERMARK - eventsShortOfWatermark);

    AtomicBoolean bursting = new AtomicBoolean(true);
    CompletableFuture<Void> burst = CompletableFuture.runAsync(() -> tickBurst(wfId, bursting));
    byId(wfId).partialExit(stc(wfId, "sig-race"));
    PositionWorkflow stub = byId(wfId);
    awaitCondition(() -> stub.positionState().remainingQty() == 3L, "STC filled");
    bursting.set(false);
    burst.get(60, TimeUnit.SECONDS);
    assertThat(tickUntilRolled(wfId, firstRun)).isNotEqualTo(firstRun);
    return wfId;
  }

  private List<String> sigRaceOrders() {
    return placed.stream()
        .filter(i -> i.getIntentKey().contains(":exit:sig-race"))
        .map(i -> i.getIntentKey() + " qty=" + i.getQty())
        .toList();
  }

  // ---------- plumbing ----------

  private String startConfirmedArmedPosition(long qty, String peak, String giveback)
      throws Exception {
    String wfId = "it752-pos-" + UUID.randomUUID();
    startConfirmedArmedPosition(wfId, qty, peak, giveback);
    return wfId;
  }

  private void startConfirmedArmedPosition(String wfId, long qty, String peak, String giveback)
      throws Exception {
    started.add(wfId);
    PositionWorkflow stub =
        client.newWorkflowStub(
            PositionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(wfId)
                .setTypedSearchAttributes(
                    SearchAttributes.newBuilder()
                        .set(
                            SearchAttributeKey.forKeyword("TenantStrategy"),
                            WorkflowIds.tenantStrategy(tenant, STRATEGY))
                        .set(SearchAttributeKey.forKeyword("ContractSymbol"), OCC)
                        .build())
                .build());
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(tenant);
    in.setStrategyId(STRATEGY);
    in.setEntrySignalId("entry-" + wfId);
    in.setContractSymbol(OCC);
    in.setQty(qty);
    in.setEntryPremium(new BigDecimal("2.30"));
    in.setExitFloorPct(new BigDecimal("0.50"));
    WorkflowStub.fromTyped(stub).start(in);
    stub.onFill(fill("brk-entry", qty, new BigDecimal("2.30")));
    ArmChandelierPayload arm = new ArmChandelierPayload();
    arm.setSchemaVersion(1L);
    arm.setTenantId(tenant);
    arm.setStrategyId(STRATEGY);
    arm.setPositionWorkflowId(wfId);
    arm.setSourceSignalId("src-" + wfId);
    arm.setPeakPremium(new BigDecimal(peak));
    arm.setGivebackPct(new BigDecimal(giveback));
    stub.armChandelier(arm);
    PositionWorkflow byId = byId(wfId);
    awaitCondition(() -> byId.trailingState().armed(), "trail armed");
  }

  /** Typed stub bound to the workflow id only, so it follows the roll like production callers. */
  private PositionWorkflow byId(String wfId) {
    return client.newWorkflowStub(PositionWorkflow.class, wfId);
  }

  private String tickUntilRolled(String wfId, String firstRun) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      String run = currentRunId(wfId);
      if (!run.equals(firstRun)) {
        return run;
      }
      byId(wfId).chandelierTick(tick("2.50"));
      Thread.sleep(50);
    }
    return currentRunId(wfId);
  }

  private void tickUntilHistoryAtLeast(String wfId, long length) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    while (describe(wfId).getWorkflowExecutionInfo().getHistoryLength() < length) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("history never reached " + length);
      }
      byId(wfId).chandelierTick(tick("2.50"));
      Thread.sleep(50);
    }
  }

  private void tickBurst(String wfId, AtomicBoolean bursting) {
    PositionWorkflow stub = byId(wfId);
    for (int i = 0; i < 400 && bursting.get(); i++) {
      stub.chandelierTick(tick("2.50"));
    }
  }

  /** The account-cap form, verbatim from {@code AccountPnlActivitiesImpl}. */
  private String accountCapQuery() {
    return "WorkflowType='PositionWorkflow' AND TenantStrategy='"
        + WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenant, STRATEGY))
        + "' AND ExecutionStatus='Running'";
  }

  private boolean visible(String query) {
    return client.listExecutions(query).findAny().isPresent();
  }

  private boolean isClosed(String wfId) {
    return describe(wfId).getWorkflowExecutionInfo().getStatus()
        != io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
  }

  private long distinctAuditRows(String wfId, String kind) {
    return Mockito.mockingDetails(audit).getInvocations().stream()
        .map(i -> (AuditEvent) i.getArgument(0))
        .filter(e -> kind.equals(e.getKind()) && wfId.equals(e.getWorkflowId()))
        .map(AuditEvent::getEventId)
        .distinct()
        .count();
  }

  private DescribeWorkflowExecutionResponse describe(String wfId) {
    return client
        .getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wfId).build())
                .build());
  }

  private String currentRunId(String wfId) {
    return describe(wfId).getWorkflowExecutionInfo().getExecution().getRunId();
  }

  private List<io.temporal.api.history.v1.HistoryEvent> historyOf(String wfId, String runId) {
    return client
        .getWorkflowServiceStubs()
        .blockingStub()
        .getWorkflowExecutionHistory(
            GetWorkflowExecutionHistoryRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(wfId).setRunId(runId).build())
                .build())
        .getHistory()
        .getEventsList();
  }

  private io.temporal.api.history.v1.HistoryEvent startedEventOf(String wfId, String runId) {
    var event = historyOf(wfId, runId).get(0);
    assertThat(event.getEventType()).isEqualTo(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    return event;
  }

  private static void awaitCondition(BooleanSupplier cond, String what)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    while (!cond.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(50);
    }
  }

  private static void setWatermark(long v) throws Exception {
    Field f = PositionWorkflowImpl.class.getDeclaredField("historyLengthWatermark");
    f.setAccessible(true);
    f.set(null, v);
  }

  /** Polls one Visibility query every 50ms and records the longest zero-hit stretch. */
  private final class VisibilityGapProbe {
    private final String query;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private CompletableFuture<Void> loop;
    volatile int polls;
    volatile int zeroPolls;
    volatile long longestGapMs;

    VisibilityGapProbe(String query) {
      this.query = query;
    }

    void start() {
      loop =
          CompletableFuture.runAsync(
              () -> {
                long gapStart = -1;
                while (running.get()) {
                  long now = System.currentTimeMillis();
                  boolean hit = visible(query);
                  polls++;
                  if (!hit) {
                    zeroPolls++;
                    if (gapStart < 0) {
                      gapStart = now;
                    }
                  } else if (gapStart >= 0) {
                    longestGapMs = Math.max(longestGapMs, now - gapStart);
                    gapStart = -1;
                  }
                  try {
                    Thread.sleep(50);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
                if (gapStart >= 0) {
                  longestGapMs = Math.max(longestGapMs, System.currentTimeMillis() - gapStart);
                }
              });
    }

    void stop() throws Exception {
      running.set(false);
      loop.get(10, TimeUnit.SECONDS);
    }
  }

  // ---------- payloads ----------

  private static String brokerId(OrderIntent intent) {
    return "brk-" + intent.getIntentKey();
  }

  private static PremiumTick tick(String premium) {
    PremiumTick t = new PremiumTick();
    t.setSchemaVersion(1L);
    t.setContractSymbol(OCC);
    t.setPremium(new BigDecimal(premium));
    t.setRetrievedAt(OffsetDateTime.now());
    return t;
  }

  private static FillSignalPayload fill(String brokerOrderId, long qty, BigDecimal avg) {
    return new FillSignalPayload()
        .withBrokerOrderId(brokerOrderId)
        .withFilledQty(qty)
        .withAvgFillPrice(avg)
        .withFilledAt(OffsetDateTime.now());
  }

  private PartialExitRequest stc(String wfId, String signalId) {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(tenant);
    req.setStrategyId(STRATEGY);
    req.setSignalId(signalId);
    req.setPositionWorkflowId(wfId);
    req.setFraction(new BigDecimal("0.5"));
    req.setRefPremium(new BigDecimal("2.50"));
    req.setReason("stc_signal");
    req.setAuthor("acme_trader");
    req.setRawLine("STC NVDA 140C half");
    req.setOccurredAt(OffsetDateTime.now());
    return req;
  }

  private static PartialCloseRequest trimRequest() {
    PartialCloseRequest r = new PartialCloseRequest();
    r.setSchemaVersion(1L);
    r.setOperatorId("ops-it752");
    r.setReason("IT-752 trim racing the roll");
    r.setFraction(new BigDecimal("0.5"));
    return r;
  }

  private static DisarmTrailRequest disarmRequest() {
    DisarmTrailRequest r = new DisarmTrailRequest();
    r.setSchemaVersion(1L);
    r.setOperatorId("ops-it752");
    r.setReason("IT-752 disarm racing the roll");
    return r;
  }

  private static SubscribePremiumResult subscribed() {
    SubscribePremiumResult r = new SubscribePremiumResult();
    r.setSchemaVersion(1L);
    r.setSubscriptionId("sub-it752");
    r.setSubscribedAt(OffsetDateTime.now());
    r.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    return r;
  }

  private static OptionQuoteResult quote() {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol(OCC);
    r.setBid(new BigDecimal("2.50"));
    r.setMid(new BigDecimal("2.55"));
    r.setAsk(new BigDecimal("2.60"));
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.OK);
    return r;
  }
}
