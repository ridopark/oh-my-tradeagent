package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/**
 * Issue #279: {@link WorkflowReplayer}-based coverage for the legacy {@code v == DEFAULT_VERSION}
 * branch of the exit-side {@link PositionWorkflowImpl#VERSION_EXIT_FILLED_OPTION_SYMBOL} gate,
 * mirroring {@link CopytradeSignalWorkflowImplLegacyReplayTest}.
 *
 * <p>PR #281 (issue #276) added an {@code option_symbol} field to the {@code PartialExitFilled}
 * audit subject, fenced with {@code Workflow.getVersion(VERSION_EXIT_FILLED_OPTION_SYMBOL, DEFAULT,
 * 1)} so pre-#276 in-flight PositionWorkflow executions replay deterministically through the legacy
 * subject (no {@code option_symbol} key). {@link TestWorkflowEnvironment} always reports {@code
 * getVersion(...) == 1} for fresh workflows, so the legacy branch is unreachable from a round-trip
 * test — only {@link WorkflowReplayer} against a recorded pre-#276 history exercises it. No {@code
 * PositionWorkflowImplLegacyReplayTest} existed before this issue.
 *
 * <p>The fixture {@code position-pre-276-legacy-history.json} is synthesised by running a
 * stripped-down {@link LegacyPositionEmulatorWorkflowImpl} that mirrors the fully-legacy (v=0)
 * PositionWorkflow happy-path command sequence (PositionEntered at start from {@code input.qty} ->
 * arm EOD/expiry timers -> a single full-close partial exit -> PartialExitFilled WITHOUT {@code
 * option_symbol} -> PositionClosed) under {@link TestWorkflowEnvironment}, then capturing the
 * resulting history via {@link WorkflowClient#fetchHistory(String)}. The emulator records NO
 * version markers, so on replay through the current {@link PositionWorkflowImpl} every gate
 * (including {@code exit-filled-option-symbol-v1}) resolves to {@code DEFAULT_VERSION} and the
 * legacy branches run. Regenerate with {@code -Dgenerate.legacy.fixture=true}.
 *
 * <p>Replay against the current {@link PositionWorkflowImpl} verifies:
 *
 * <ul>
 *   <li>No {@code NonDeterministicWorkflowError} — the {@code VERSION_EXIT_FILLED_OPTION_SYMBOL}
 *       gate (and the sibling v=0 gates) preserve determinism for legacy histories.
 *   <li>The legacy {@code PartialExitFilled} branch emits its audit WITHOUT the {@code
 *       option_symbol} key — the fixture's recorded subject has no such key, and the current impl's
 *       v=DEFAULT_VERSION branch must reproduce that exact command sequence.
 *   <li>The {@link PositionWorkflowImpl#VERSION_EXIT_FILLED_OPTION_SYMBOL} constant name is
 *       reflectively asserted — renaming it breaks this test.
 * </ul>
 */
class PositionWorkflowImplLegacyReplayTest {

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/position-pre-276-legacy-history.json";
  private static final Path FIXTURE_SOURCE_PATH =
      Path.of("src/test/resources/temporal/replay/position-pre-276-legacy-history.json");

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";
  private static final String LEGACY_EMULATOR_WORKFLOW_ID = "legacy-pre-276-position-emulator";
  private static final String CONTRACT_SYMBOL = "NVDA  260516C00140000";

  /**
   * Pins the version-marker constant name so a rename in {@link PositionWorkflowImpl} fails this
   * test loudly. Mirrors {@link
   * CopytradeSignalWorkflowImplLegacyReplayTest#versionPreTradeDispatchConstantNameIsStable}.
   */
  @Test
  void versionExitFilledOptionSymbolConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXIT_FILLED_OPTION_SYMBOL");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("exit-filled-option-symbol-v1");
  }

  /**
   * The main replay assertion: replays the pre-#276 history against the current impl and verifies
   * no {@code NonDeterministicWorkflowError}. The recorded {@code PartialExitFilled} subject has no
   * {@code option_symbol} key; the current impl's v=DEFAULT_VERSION branch must reproduce that
   * command sequence exactly.
   */
  @Test
  void legacyPre276HistoryReplaysAgainstCurrentImplWithoutNonDeterminism() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=PositionWorkflowImplLegacyReplayTest#regenerateLegacyFixture`",
            FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, PositionWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  /**
   * One-shot fixture generator. Disabled by default; run via {@code -Dgenerate.legacy.fixture=true}
   * after a meaningful change to the legacy emulator (or if the fixture is missing). Writes the
   * recorded history JSON to {@link #FIXTURE_SOURCE_PATH} so the regenerated file is committed
   * alongside this test.
   *
   * <p>The emulator mirrors the fully-legacy (v=0) {@link PositionWorkflowImpl} happy-path command
   * sequence with NO version markers:
   *
   * <ol>
   *   <li>{@code audit.log(PositionEntered)} (Log) — emitted at workflow start from {@code
   *       input.qty} (legacy VERSION_DEFER_POSITION_ENTERED branch)
   *   <li>{@code calendar.durationUntilEodEt()} (DurationUntilEodEt) — EOD timer arm
   *   <li>[main loop awaits the partialExit signal]
   *   <li>{@code audit.log(PartialExitRequested)} (Log)
   *   <li>{@code exec.placeOrder} (PlaceOrder)
   *   <li>[untimed await for the exit fill — legacy VERSION_EXIT_FILL_TIMEOUT branch]
   *   <li>{@code audit.log(PartialExitFilled)} (Log) — WITHOUT option_symbol (legacy
   *       VERSION_EXIT_FILLED_OPTION_SYMBOL branch)
   *   <li>{@code audit.log(PositionClosed)} (Log)
   * </ol>
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateLegacyFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyPositionEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      LegacyExecActivities legacyExec = Mockito.mock(LegacyExecActivities.class);

      // Generous EOD so the timer never fires; ZERO expiry so no expiry timer is armed (matching
      // PositionWorkflowImplTest's calendar stubs). The partial exit drives the close.
      when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
      when(legacyExec.placeOrder(any())).thenReturn(submitted());

      worker.registerActivitiesImplementations(audit, calendar);
      Worker brokerWorker = env.newWorker(BROKER_QUEUE);
      brokerWorker.registerActivitiesImplementations(legacyExec);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      PositionWorkflow wf =
          client.newWorkflowStub(
              PositionWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(LEGACY_EMULATOR_WORKFLOW_ID)
                  .build());
      WorkflowStub.fromTyped(wf).start(input());

      // Full close on the partial exit: signal partialExit (buffered/processed by the main loop),
      // which drives exec.placeOrder. Wait until placeOrder ran (workflow now on the untimed
      // exit-fill await) then deliver the exit fill so remainingQty drains to zero and the loop
      // exits through PositionClosed.
      wf.partialExit(partialExitRequest());
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline) {
        try {
          Mockito.verify(legacyExec, Mockito.atLeastOnce()).placeOrder(any());
          break;
        } catch (AssertionError ignored) {
          Thread.sleep(50);
        }
      }
      wf.onFill(exitFill());
      WorkflowStub.fromTyped(wf).getResult(String.class);

      json = client.fetchHistory(LEGACY_EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static PositionWorkflowInput input() {
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    in.setEntrySignalId("legacy-entry-1");
    in.setContractSymbol(CONTRACT_SYMBOL);
    in.setQty(5L);
    in.setEntryPremium(new BigDecimal("2.30"));
    return in;
  }

  private static PartialExitRequest partialExitRequest() {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setSignalId("legacy-stc-1");
    req.setPositionWorkflowId(LEGACY_EMULATOR_WORKFLOW_ID);
    req.setFraction(BigDecimal.ONE); // full close -> remainingQty drains to 0
    req.setRefPremium(new BigDecimal("2.85"));
    req.setReason("stc_signal");
    req.setAuthor("acme_trader");
    req.setRawLine("STC NVDA 5/16 140C @ 2.85");
    req.setOccurredAt(OffsetDateTime.of(2026, 5, 13, 17, 45, 0, 0, ZoneOffset.UTC));
    return req;
  }

  private static FillSignalPayload exitFill() {
    return new FillSignalPayload()
        .withBrokerOrderId("brk-exit")
        .withFilledQty(5L)
        .withAvgFillPrice(new BigDecimal("2.85"))
        .withFilledAt(OffsetDateTime.now());
  }

  private static OrderIntentResult submitted() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("exit-key");
    r.setBrokerOrderId("brk-exit");
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  /**
   * Pre-#276 {@link com.ohmytradeagent.orchestrator.activities.ExecActivities} shape used by the
   * emulator. Method names {@code placeOrder} / {@code cancelOrder} derive activity types {@code
   * PlaceOrder} / {@code CancelOrder} — matching the production stub regardless of interface name.
   */
  @ActivityInterface
  public interface LegacyExecActivities {
    OrderIntentResult placeOrder(OrderIntent intent);

    OrderIntentResult cancelOrder(String intentKey);
  }

  /**
   * Emulator mirroring the fully-legacy (v=0) {@link PositionWorkflowImpl} happy-path. Implements
   * the production {@link PositionWorkflow} interface so the recorded {@code workflowType.name} is
   * {@code PositionWorkflow} — what {@link WorkflowReplayer} expects when registering {@link
   * PositionWorkflowImpl}. The exec stub is pinned to {@code broker-alpaca-paper} to match the task
   * queue {@code ExecActivitiesFactory.forTarget("alpaca-paper")} routes to in production. No
   * version markers are recorded, so on replay every gate resolves to {@code DEFAULT_VERSION}.
   */
  public static class LegacyPositionEmulatorWorkflowImpl implements PositionWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
    private static final ActivityOptions EXEC_OPTS =
        ActivityOptions.newBuilder()
            .setTaskQueue(BROKER_QUEUE)
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();
    private final AuditActivities audit = Workflow.newActivityStub(AuditActivities.class, OPTS);
    private final MarketCalendarActivities calendar =
        Workflow.newActivityStub(MarketCalendarActivities.class, OPTS);
    private final LegacyExecActivities exec =
        Workflow.newActivityStub(LegacyExecActivities.class, EXEC_OPTS);

    private PositionWorkflowInput input;
    private long remainingQty;
    private PartialExitRequest pendingExit;
    private FillSignalPayload lastFillEvent;

    @Override
    public String run(PositionWorkflowInput in) {
      this.input = in;
      // Legacy VERSION_DEFER_POSITION_ENTERED branch: assign remainingQty from input.qty and emit
      // PositionEntered at workflow start.
      this.remainingQty = in.getQty();
      audit.log(auditEvent("PositionEntered"));

      // Mirror the legacy run() activity + timer order exactly: durationUntilEodEt ->
      // durationUntilExpiryCloseEt (the OCC has a parseable expiry, so the production impl calls
      // it)
      // -> arm EOD timer -> (arm expiry timer only if expiryIn is non-zero). The test stubs return
      // generous EOD (never fires) and ZERO expiry (no expiry timer), matching
      // PositionWorkflowImpl.
      Duration eodIn = calendar.durationUntilEodEt();
      Duration expiryIn = Duration.ZERO;
      java.time.LocalDate expiryDate =
          PositionWorkflowImpl.expiryDateFromOcc(in.getContractSymbol());
      if (expiryDate != null) {
        // Issue #15: the legacy stream called the single-arg activity (15:30 ET default). The
        // activity TYPE NAME (DurationUntilExpiryCloseEt) is unchanged by the added arg, so passing
        // null here reproduces the legacy command/value and the recorded history still matches.
        expiryIn = calendar.durationUntilExpiryCloseEt(expiryDate, null);
      }
      if (!eodIn.isZero() && !eodIn.isNegative()) {
        Workflow.newTimer(eodIn);
      }
      if (!expiryIn.isZero() && !expiryIn.isNegative()) {
        Workflow.newTimer(expiryIn);
      }

      // Main loop: await the partial-exit signal, then process it.
      while (remainingQty > 0) {
        Workflow.await(() -> pendingExit != null);
        PartialExitRequest req = pendingExit;
        pendingExit = null;
        processOne(req);
      }

      audit.log(auditEvent("PositionClosed"));
      return Workflow.getInfo().getWorkflowId();
    }

    private void processOne(PartialExitRequest req) {
      audit.log(auditEvent("PartialExitRequested"));
      long qtyToClose = remainingQty; // fraction=1.0 -> full close
      String intentKey = Workflow.getInfo().getWorkflowId() + ":exit:" + req.getSignalId();
      exec.placeOrder(exitIntent(req, qtyToClose, intentKey));
      // Legacy VERSION_EXIT_FILL_TIMEOUT branch: untimed await for the exit fill.
      Workflow.await(() -> lastFillEvent != null);
      remainingQty -= lastFillEvent.getFilledQty();
      // Legacy VERSION_EXIT_FILLED_OPTION_SYMBOL branch: PartialExitFilled WITHOUT option_symbol.
      audit.log(auditEvent("PartialExitFilled"));
      lastFillEvent = null;
    }

    @Override
    public void partialExit(PartialExitRequest req) {
      this.pendingExit = req;
    }

    @Override
    public void onFill(FillSignalPayload event) {
      this.lastFillEvent = event;
    }

    @Override
    public void armChandelier(ArmChandelierPayload payload) {}

    @Override
    public void chandelierTick(PremiumTick tick) {}

    @Override
    public void riskBreach(RiskBreachPayload payload) {}

    @Override
    public TrailingState trailingState() {
      return new TrailingState(false, null, null, null, null, null, 0L);
    }

    @Override
    public PositionState positionState() {
      return new PositionState("", 0L, null);
    }

    @Override
    public void forceCloseValidator(ForceCloseRequest request) {}

    @Override
    public ForceCloseResult forceClose(ForceCloseRequest request) {
      ForceCloseResult r = new ForceCloseResult();
      r.setSchemaVersion(1L);
      r.setStatus(ForceCloseResult.Status.NOOP_ALREADY_CLOSED);
      r.setExitSignalId("force:noop:legacy");
      return r;
    }

    private OrderIntent exitIntent(PartialExitRequest req, long qty, String intentKey) {
      OrderIntent i = new OrderIntent();
      i.setSchemaVersion(1L);
      i.setTenantId(input.getTenantId());
      i.setStrategyId(input.getStrategyId());
      i.setIntentKey(intentKey);
      i.setSignalId(req.getSignalId());
      i.setOptionSymbol(input.getContractSymbol());
      i.setSide(OrderIntent.Side.SELL);
      i.setQty(qty);
      i.setLimitPrice(req.getRefPremium());
      i.setRecordedAt(
          OffsetDateTime.ofInstant(
              Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
      return i;
    }

    private AuditEvent auditEvent(String kind) {
      AuditEvent e = new AuditEvent();
      e.setSchemaVersion(1L);
      e.setTenantId(input.getTenantId());
      e.setStrategyId(input.getStrategyId());
      e.setEventId(Workflow.randomUUID().toString());
      e.setOccurredAt(
          OffsetDateTime.ofInstant(
              Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC));
      e.setKind(kind);
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("entry_signal_id", input.getEntrySignalId());
      e.setSubject(s);
      e.setActor("workflow:LegacyEmulator");
      e.setWorkflowId(Workflow.getInfo().getWorkflowId());
      e.setCorrelationId(input.getEntrySignalId());
      return e;
    }
  }
}
