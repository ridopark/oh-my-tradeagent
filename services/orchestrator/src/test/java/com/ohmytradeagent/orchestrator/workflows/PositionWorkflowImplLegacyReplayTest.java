package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
  /**
   * Issue #735: the {@code exit-cumulative-ledger-v1} changeId is the ONLY thing standing between
   * ~12 in-flight real-money PositionWorkflow executions and a non-deterministic replay, because
   * under v&gt;=1 a duplicate broker report books a delta of 0 and emits NO {@code
   * PartialExitFilled} where v=0 emitted one — a command-COUNT divergence. Renaming the constant
   * silently resets every in-flight execution to the new branch, so pin the literal.
   *
   * <p>This guards the changeId's IDENTITY, not the gate's replay behaviour. See the class javadoc
   * note on why no fixture currently discriminates that.
   */
  @Test
  void versionExitCumulativeLedgerConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXIT_CUMULATIVE_LEDGER");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("exit-cumulative-ledger-v1");
  }

  @Test
  void versionExitFilledOptionSymbolConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXIT_FILLED_OPTION_SYMBOL");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("exit-filled-option-symbol-v1");
  }

  /**
   * Pins the exit-retry late-fill reconcile version-marker literal so a rename fails loudly. The
   * literal is part of the workflow's replay contract: a getVersion change-point keyed on a changed
   * string re-resolves to DEFAULT_VERSION for legacy histories, which would silently alter v=0
   * behavior.
   */
  @Test
  void versionExitRetryLateFillReconcileConstantNameIsStable() throws Exception {
    Field marker =
        PositionWorkflowImpl.class.getDeclaredField("VERSION_EXIT_RETRY_LATE_FILL_RECONCILE");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("exit-retry-late-fill-reconcile");
  }

  /**
   * F1: pins the cancel-terminal-state reconcile gate literal. This getVersion change-point keys
   * the discard-and-retry-vs-reconcile fork in processOne's timeout branch; a renamed string
   * re-resolves to DEFAULT_VERSION for legacy histories, silently reverting the authoritative
   * cancel-fill reconcile on in-flight workflows (back to the over-sell-on-late-fill behavior). The
   * pre-fix legacy history (recorded with NO {@code
   * partial-exit-cancel-terminal-state-reconcile-v1} marker) doubles as the v=0 replay regression:
   * {@link #legacyPre276HistoryReplaysAgainstCurrentImplWithoutNonDeterminism} drives the recorded
   * {@code processOne} command stream (PartialExitRequested -> placeOrder -> PartialExitFilled)
   * through the current impl, where this gate resolves to DEFAULT_VERSION so the new getOrderStatus
   * call + the synthesized PartialExitFilled emit stay OFF and the recorded sequence is reproduced
   * byte-identically.
   */
  @Test
  void versionExitCancelTerminalReconcileConstantNameIsStable() throws Exception {
    Field marker =
        PositionWorkflowImpl.class.getDeclaredField("VERSION_EXIT_CANCEL_TERMINAL_RECONCILE");
    marker.setAccessible(true);
    assertThat((String) marker.get(null))
        .isEqualTo("partial-exit-cancel-terminal-state-reconcile-v1");
  }

  /**
   * Plan-2A R-AA-1: pins the flatten-fill-await gate literal. This getVersion change-point keys the
   * legacy-vs-bounded flatten fork; a renamed string re-resolves to DEFAULT_VERSION for legacy
   * histories, which would silently revert the silent-loss fix on in-flight workflows.
   */
  @Test
  void versionFlattenFillAwaitConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_FLATTEN_FILL_AWAIT");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("flatten-fill-await");
  }

  /** Plan-2A R-AA-3: pins the bounded-limit gate literal. */
  @Test
  void versionFlattenBoundedLimitConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_FLATTEN_BOUNDED_LIMIT");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("flatten-bounded-limit");
  }

  /**
   * Plan-2B R-AB-1: pins the expiry-lead-flatten gate literal. This getVersion change-point keys
   * the arm-vs-no-arm fork for the guaranteed multi-day flatten timer; a renamed string re-resolves
   * to DEFAULT_VERSION for legacy histories (which never recorded the lead timer), silently
   * re-arming it on in-flight workflows and tripping a NonDeterministicWorkflowError.
   */
  @Test
  void versionExpiryLeadFlattenConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXPIRY_LEAD_FLATTEN");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("expiry-lead-flatten");
  }

  /**
   * Plan-2B R-AB-2: pins the stepped-reprice gate literal. This getVersion change-point keys the
   * single-shot-retry-vs-stepped-reprice fork in processOne; a renamed string re-resolves to
   * DEFAULT_VERSION for legacy histories, silently reverting the stepped reprice on in-flight
   * workflows.
   */
  @Test
  void versionExitSteppedRepriceConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXIT_STEPPED_REPRICE");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("exit-stepped-reprice");
  }

  /**
   * Issue #434: pins the expire-worthless gate literal. This getVersion change-point keys the
   * physical-expiry worthless-close fork in run()'s v&gt;=1 epilogue; a renamed string re-resolves
   * to DEFAULT_VERSION for legacy histories, silently reverting the worthless-close fix on
   * in-flight workflows (back to the lingering-open behavior).
   */
  @Test
  void versionExpireWorthlessConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXPIRE_WORTHLESS");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("expire-worthless-v1");
  }

  /**
   * PLAN-2026-07-23 Phase 2: pins the no-timer expiry-day self-close gate literal. This getVersion
   * change-point keys the entry-time worthless-close of a physically-expired lot that armed NO
   * terminal-flatten timer; a renamed string re-resolves to DEFAULT_VERSION for legacy histories,
   * silently reverting the zombie self-close on in-flight workflows (back to blocking ALIVE
   * forever).
   */
  @Test
  void versionExpireWorthlessNoTimerConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_EXPIRE_WORTHLESS_NO_TIMER");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("expire-worthless-no-timer-v1");
  }

  /**
   * Phase 1 (PLAN-2026-06-25-trading-remediation): pins the partial-place-retry-next-session gate
   * literal. This getVersion change-point keys the return-vs-re-drive fork in processOne's
   * place-failure catch; a renamed string re-resolves to DEFAULT_VERSION for legacy histories (the
   * live QQQ-era pods), silently arming a next-session timer + re-enqueue that those histories
   * never recorded — tripping a NonDeterministicWorkflowError. The pre-fix legacy history above
   * (recorded with NO {@code partial-exit-place-retry-next-session-v1} marker) doubles as the v=0
   * replay regression: on replay this gate resolves to DEFAULT_VERSION so the legacy {@code
   * releaseExitInFlightLatches()+return} branch runs and the recorded command stream is reproduced
   * byte-identically.
   */
  @Test
  void versionPartialPlaceRetryNextSessionConstantNameIsStable() throws Exception {
    Field marker =
        PositionWorkflowImpl.class.getDeclaredField("VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("partial-exit-place-retry-next-session-v1");
  }

  /**
   * Phase 1 (PLAN-2026-06-30-flatten-fillrace-and-killswitch-realized): pins the
   * flatten-cancel-terminal-state-reconcile gate literal. This getVersion change-point keys the
   * discard-and-stay-alive-vs-reconcile fork in flattenRemaining's timeout branch AND the #481
   * next-session retry loop; a renamed string re-resolves to DEFAULT_VERSION for legacy histories,
   * silently reverting the broker-authoritative flatten reconcile on in-flight workflows (back to
   * the phantom-stuck-forever behaviour — the 2026-06-30 DRAM/INTC/TSLA incident). The pre-fix
   * legacy flatten-timeout history ({@code position-pre-flatten-reconcile-legacy-history.json})
   * doubles as the v=0 replay regression in {@link
   * #legacyFlattenTimeoutHistoryReplaysAgainstCurrentImplWithoutNonDeterminism}.
   */
  @Test
  void versionFlattenCancelTerminalReconcileConstantNameIsStable() throws Exception {
    Field marker =
        PositionWorkflowImpl.class.getDeclaredField("VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("flatten-cancel-terminal-state-reconcile-v1");
  }

  /**
   * PLAN-2026-07-23: pins the no_progress-time-stop pre-target-only gate literal. This getVersion
   * change-point keys the WHEN of a {@code flattenRemaining("time_stop")} command on a running
   * PositionWorkflow (post-target the runner is no longer flattened by the time-stop); a renamed
   * string re-resolves to DEFAULT_VERSION for legacy histories, silently reverting the guard on
   * in-flight watchlist runners (back to the trail-pre-empting behaviour — the TSLA 2026-07-23
   * incident). The pre-#276 legacy history above (recorded with NO {@code
   * watchlist-timestop-pretarget-only-v1} marker) doubles as the v=0 replay regression in {@link
   * #legacyPre276HistoryReplaysAgainstCurrentImplWithoutNonDeterminism}: on replay this gate
   * resolves to DEFAULT_VERSION so the current impl keeps the CURRENT unconditional time-stop fire
   * and reproduces the recorded command stream byte-identically.
   */
  @Test
  void versionTimestopPretargetOnlyConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_TIMESTOP_PRETARGET_ONLY");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("watchlist-timestop-pretarget-only-v1");
  }

  /**
   * Issue #1023: pins the drain-dedupe gate literal. A renamed string re-resolves to
   * DEFAULT_VERSION for legacy histories, silently reverting the suppression on in-flight workflows
   * and placing a duplicate sell order — the exact bug this gate was added to prevent.
   */
  @Test
  void versionPreinitStcDrainDedupeConstantNameIsStable() throws Exception {
    Field marker = PositionWorkflowImpl.class.getDeclaredField("VERSION_PREINIT_STC_DRAIN_DEDUPE");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("preinit-stc-drain-dedupe-v1");
  }

  /**
   * The main replay assertion: replays the pre-#276 history against the current impl and verifies
   * no {@code NonDeterministicWorkflowError}. The recorded {@code PartialExitFilled} subject has no
   * {@code option_symbol} key; the current impl's v=DEFAULT_VERSION branch must reproduce that
   * command sequence exactly. Issue #434: this same pre-#434 history (recorded with NO {@code
   * expire-worthless-v1} marker) doubles as the worthless-close replay regression — on replay every
   * gate, including {@code VERSION_EXPIRE_WORTHLESS}, resolves to DEFAULT_VERSION so the legacy
   * (lingering) branch runs and the recorded command stream is reproduced byte-identically.
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
  // Issue #752 G3: carried (post-continue-as-new) run
  // ---------------------------------------------------------------------------

  private static final String CARRIED_FIXTURE_RESOURCE =
      "temporal/replay/position-carried-run-history.json";
  private static final Path CARRIED_FIXTURE_SOURCE_PATH =
      Path.of("src/test/resources/temporal/replay/position-carried-run-history.json");
  private static final String CARRIED_WORKFLOW_ID = "carried-run-position";
  private static final String CARRIED_OCC = "NVDA  281215C00140000";

  /**
   * The SECOND run of a rolled position: carried input with an armed trail, the carried-run search
   * attribute upsert, neutral ticks, one ratchet, and a chandelier fire with its filled flatten.
   * The {@code if (carriedRun)} upsert and the {@code !carriedRun} first-fill bypass in {@code
   * run()} are un-gated because "a carried history is always fresh" — true only until the first
   * real roll. This fixture is the tripwire for an un-gated edit to either branch afterwards.
   *
   * <p>NEVER re-record this fixture after merge: a change that breaks it needs a version gate, not
   * a new fixture (PLAN-2026-10-08 Phase 2). Falsified both ways when added — deleting the upsert
   * block, or dropping {@code !carriedRun} from the first-fill await, each fails this test.
   */
  @Test
  void carriedRunHistoryReplaysAgainstCurrentImplWithoutNonDeterminism() throws Exception {
    assertThat(getClass().getClassLoader().getResource(CARRIED_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Record ONCE with"
                + " `mvn -pl services/orchestrator test -Dgenerate.carried.fixture=true"
                + " -Dtest=PositionWorkflowImplLegacyReplayTest#regenerateCarriedRunFixture`",
            CARRIED_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowExecutionHistory history;
    try (var in = getClass().getClassLoader().getResourceAsStream(CARRIED_FIXTURE_RESOURCE)) {
      history =
          WorkflowExecutionHistory.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    var started = history.getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
    // A fixture without the carried marker replays the PARENT path and proves nothing.
    assertThat(started.getContinuedExecutionRunId()).as("recorded run is a roll").isNotEmpty();
    assertThat(started.getInput().getPayloads(0).getData().toStringUtf8())
        .contains("\"carried_remaining_qty\"")
        .contains("\"carried_trailing_armed\":true");

    WorkflowReplayer.replayWorkflowExecution(history, PositionWorkflowImpl.class);
  }

  /**
   * A fresh-run history recorded on the code BEFORE {@code VERSION_PREINIT_STC_DEDUPE}: an STC
   * buffered pre-init (signalWithStart), a redelivery while it was still queued, and one after it
   * filled — both processed as new, so the history carries their placements. Replaying it against
   * the current impl must take the DEFAULT branch at both gate sites. Recorded once (via a
   * throwaway test on main @ 6924983) and NEVER re-recorded: a change that breaks it needs a gate.
   */
  @Test
  void preInitRedeliveryLegacyHistoryReplaysWithoutNonDeterminism() throws Exception {
    WorkflowExecutionHistory history;
    try (var in =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "temporal/replay/position-preinit-redelivery-legacy-history.json")) {
      history =
          WorkflowExecutionHistory.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    WorkflowReplayer.replayWorkflowExecution(history, PositionWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Issue #1023: preinit-drain double-place legacy fixture
  // ---------------------------------------------------------------------------

  private static final String PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_RESOURCE =
      "temporal/replay/position-preinit-drain-double-place-legacy-history.json";
  private static final Path PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "position-preinit-drain-double-place-legacy-history.json");
  private static final String PREINIT_DRAIN_DOUBLE_PLACE_WORKFLOW_ID =
      "preinit-drain-double-place-recorder";
  // Must be a FUTURE expiry date so VERSION_EXPIRE_WORTHLESS_NO_TIMER's worthless-close guard
  // (gated on !eodTimerArmed && !expiryDate.isAfter(currentEtDate())) does not fire after the
  // entry fill. An expired symbol trips this guard and exits the workflow before the drain runs.
  private static final String PREINIT_DRAIN_OCC_SYMBOL =
      "NVDA  "
          + LocalDate.now(ZoneId.of("America/New_York"))
              .plusYears(2)
              .format(DateTimeFormatter.ofPattern("yyMMdd"))
          + "C00140000";

  /**
   * Fixture precondition check and replay. The history was recorded on {@code b6ad6321} (before
   * {@code VERSION_PREINIT_STC_DRAIN_DEDUPE} existed) by driving the REAL {@link
   * PositionWorkflowImpl} through the all-copies-pre-init ordering: three 0.5 copies of {@code
   * sig-y} land before any worker polls, so the drain places two orders (copies 1 and 2; copy 3
   * hits the runner-quantum SKIP), then a {@code sig-full} 1.0 closes the remaining 1 lot.
   *
   * <p>Preconditions verified here (toothiness proof):
   *
   * <ul>
   *   <li>Exactly two {@code ActivityTaskScheduled} events whose input contains {@code :exit:sig-y}
   *       — the double placement the fix removes.
   *   <li>Zero {@code MarkerRecorded} events with {@code changeId=preinit-stc-drain-dedupe-v1} —
   *       the marker did not exist when the fixture was recorded.
   * </ul>
   *
   * <p>Replay verifies no {@code NonDeterministicException} on the CURRENT (un-gated) code path. In
   * Step 2, defeating the {@code VERSION_PREINIT_STC_DRAIN_DEDUPE} gate (setting {@code >= 1} to
   * {@code >= DEFAULT_VERSION}) causes replay to fail with a {@code NonDeterministicException}
   * because the new gate replaces one {@code placeOrder} ScheduleActivityTask with an {@code
   * audit.log} ScheduleActivityTask — a command-shape divergence the SDK catches:
   *
   * <pre>
   * io.temporal.worker.NonDeterministicException: [TMPRL1100] Failure handling event 93 of type
   * 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay. [TMPRL1100] Command
   * COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK doesn't match event
   * EVENT_TYPE_ACTIVITY_TASK_SCHEDULED with EventId=93 on check activityId with an expected
   * value '600b75e1-1e43-3e48-8d99-163991e497dc' and an actual value
   * '6f153761-bd67-3545-95e5-bfad25214cdc'. {WorkflowTaskStartedEventId=9223372036854775807,
   * CurrentStartedEventId=91}
   * </pre>
   *
   * <p>NEVER re-record this fixture after merge: a change that breaks it needs a version gate.
   *
   * <p>Not the only tripwire for this gate: {@code position-preinit-redelivery-legacy-history.json}
   * (recorded pre-#964) also reaches the duplicate branch and fails replay if the gate is defeated.
   * Both are intended; do not "fix" either as a duplicate test.
   */
  @Test
  void preInitDrainDoublePlaceLegacyHistoryReplaysWithoutNonDeterminism() throws Exception {
    assertThat(getClass().getClassLoader().getResource(PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_RESOURCE))
        .as(
            "Missing fixture %s. Record ONCE with"
                + " `mvn -pl services/orchestrator test"
                + " -Dgenerate.preinit.drain.fixture=true"
                + " -Dtest=PositionWorkflowImplLegacyReplayTest"
                + "#regeneratePreInitDrainDoublePlaceFixture`",
            PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_RESOURCE)
        .isNotNull();

    String json;
    try (var in =
        getClass()
            .getClassLoader()
            .getResourceAsStream(PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_RESOURCE)) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(json);

    // Precondition: two :exit:sig-y placements, no drain-dedupe marker.
    // (Recorded on b6ad6321 before VERSION_PREINIT_STC_DRAIN_DEDUPE existed.)
    long sigYPlacements =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
            .filter(
                e ->
                    e
                        .getActivityTaskScheduledEventAttributes()
                        .getInput()
                        .getPayloadsList()
                        .stream()
                        .anyMatch(p -> p.getData().toStringUtf8().contains(":exit:sig-y")))
            .count();
    long drainDedupeMarkers =
        history.getEvents().stream()
            .filter(HistoryEvent::hasMarkerRecordedEventAttributes)
            .filter(
                e ->
                    e.getMarkerRecordedEventAttributes().getDetailsMap().entrySet().stream()
                        .anyMatch(
                            kv ->
                                "changeId".equals(kv.getKey())
                                    && kv.getValue().getPayloadsList().stream()
                                        .anyMatch(
                                            p ->
                                                p.getData()
                                                    .toStringUtf8()
                                                    .contains("preinit-stc-drain-dedupe-v1"))))
            .count();
    assertThat(sigYPlacements)
        .as("fixture has the double placement: two :exit:sig-y ActivityTaskScheduled events")
        .isEqualTo(2L);
    assertThat(drainDedupeMarkers)
        .as("fixture has no preinit-stc-drain-dedupe-v1 marker (recorded before the fix)")
        .isEqualTo(0L);

    WorkflowReplayer.replayWorkflowExecution(history, PositionWorkflowImpl.class);
  }

  /**
   * One-shot fixture generator. Drives the REAL {@link PositionWorkflowImpl} through the
   * all-copies-pre-init ordering: three 0.5 copies of {@code sig-y} are sent before any worker
   * polls (forced by rebuilding the env without starting), then the three placements / skips are
   * filled in order, then a {@code sig-full} (fraction 1.0) closes the remaining 1 lot.
   *
   * <p>The history precondition (3 {@code WorkflowExecutionSignaled}, 0 {@code
   * WorkflowTaskStarted}) is asserted before {@code env.start()} — if it cannot be met, stop; the
   * recorder relies on the in-memory service starting in {@code newInstance()}, with {@code
   * start()} only starting the worker factory.
   *
   * <p>Run ONCE on unmodified production code ({@code b6ad6321}) via:
   *
   * <pre>
   * mvn -pl services/orchestrator test -Dgenerate.preinit.drain.fixture=true \
   *   -Dtest=PositionWorkflowImplLegacyReplayTest#regeneratePreInitDrainDoublePlaceFixture
   * </pre>
   *
   * <p>NEVER re-record after merge.
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.preinit.drain.fixture", matches = "true")
  void regeneratePreInitDrainDoublePlaceFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      Worker coreWorker = env.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      ExecActivities exec = Mockito.mock(ExecActivities.class);
      SubscribePremiumActivity marketData = Mockito.mock(SubscribePremiumActivity.class);
      GetOptionQuoteActivity optionQuote = Mockito.mock(GetOptionQuoteActivity.class);

      when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
      when(calendar.durationUntilExpiryFlattenEt(any(), Mockito.anyLong(), any()))
          .thenReturn(Duration.ZERO);
      when(exec.placeOrder(any())).thenReturn(submitted());

      SubscribePremiumResult subscribed = new SubscribePremiumResult();
      subscribed.setSchemaVersion(1L);
      subscribed.setSubscriptionId("sub-preinit-drain");
      subscribed.setSubscribedAt(OffsetDateTime.now());
      subscribed.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
      when(marketData.subscribePremium(any())).thenReturn(subscribed);

      OptionQuoteResult quote = new OptionQuoteResult();
      quote.setSchemaVersion(1L);
      quote.setContractSymbol(PREINIT_DRAIN_OCC_SYMBOL);
      quote.setBid(new BigDecimal("2.50"));
      quote.setMid(new BigDecimal("2.55"));
      quote.setAsk(new BigDecimal("2.60"));
      quote.setRetrievedAt(OffsetDateTime.now());
      quote.setStatus(OptionQuoteResult.Status.OK);
      when(optionQuote.getOptionQuote(any())).thenReturn(quote);

      coreWorker.registerActivitiesImplementations(audit, calendar);
      env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER)
          .registerActivitiesImplementations(exec);
      env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE)
          .registerActivitiesImplementations(marketData, optionQuote);
      // Do NOT call env.start() yet — forced ordering requires all signals in history first.

      WorkflowClient client = env.getWorkflowClient();
      PositionWorkflow wf =
          client.newWorkflowStub(
              PositionWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(PREINIT_DRAIN_DOUBLE_PLACE_WORKFLOW_ID)
                  .setTypedSearchAttributes(
                      SearchAttributes.newBuilder()
                          .set(
                              SearchAttributeKey.forKeyword("TenantStrategy"),
                              "t-dev/s-copytrade-v1")
                          .set(
                              SearchAttributeKey.forKeyword("ContractSymbol"),
                              PREINIT_DRAIN_OCC_SYMBOL)
                          .build())
                  .build());

      // Send all three copies before any worker polls.
      WorkflowStub.fromTyped(wf)
          .signalWithStart(
              "partialExit",
              new Object[] {preinitDrainPartialExitRequest("sig-y", 0.5)},
              new Object[] {preinitDrainInput()});
      wf.partialExit(preinitDrainPartialExitRequest("sig-y", 0.5));
      wf.partialExit(preinitDrainPartialExitRequest("sig-y", 0.5));

      // History precondition: all three copies in history before any task starts.
      WorkflowExecutionHistory preHistory =
          client.fetchHistory(PREINIT_DRAIN_DOUBLE_PLACE_WORKFLOW_ID);
      long signalCount =
          preHistory.getEvents().stream()
              .filter(e -> e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
              .count();
      long taskStartedCount =
          preHistory.getEvents().stream()
              .filter(e -> e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)
              .count();
      assertThat(signalCount).as("3 WorkflowExecutionSignaled before any task").isEqualTo(3L);
      assertThat(taskStartedCount).as("0 WorkflowTaskStarted before env.start()").isEqualTo(0L);

      // Now start workers.
      env.start();

      // Entry fill: sent after workers start. The 90-second entry-fill TTL is a SIMULATED timer;
      // TestWorkflowEnvironment does not auto-advance past it while the test thread is active and
      // the signal is in flight, so delivering it after env.start() is safe here.
      wf.onFill(fill("brk-entry", 6L, "2.30"));

      // Wait for copy-1 placement (ceil(6 * 0.5) = 3 lots).
      long placeOrderDeadline = System.currentTimeMillis() + 50_000;
      while (System.currentTimeMillis() < placeOrderDeadline) {
        try {
          Mockito.verify(exec, Mockito.atLeast(1)).placeOrder(Mockito.any());
          break;
        } catch (AssertionError ignored) {
          Thread.sleep(50);
        }
      }
      Mockito.verify(exec, Mockito.atLeast(1)).placeOrder(Mockito.any());
      wf.onFill(fill("brk-exit-1", 3L, "2.60"));

      // Wait for copy-2 placement (ceil(3 * 0.5) = 2 lots).
      awaitCondition(
          () ->
              Mockito.mockingDetails(exec).getInvocations().stream()
                      .filter(i -> i.getMethod().getName().equals("placeOrder"))
                      .count()
                  >= 2,
          "second placement (copy 2)");
      wf.onFill(fill("brk-exit-2", 2L, "2.60"));

      // Copy 3: remainingQty=1, fraction=0.5 → runner-quantum SKIP (no placeOrder). Send sig-full
      // to drain remaining 1 lot and complete the workflow.
      wf.partialExit(preinitDrainPartialExitRequest("sig-full", 1.0));

      // Wait for sig-full placement (1 lot).
      awaitCondition(
          () ->
              Mockito.mockingDetails(exec).getInvocations().stream()
                      .filter(i -> i.getMethod().getName().equals("placeOrder"))
                      .count()
                  >= 3,
          "third placement (sig-full)");
      wf.onFill(fill("brk-exit-full", 1L, "2.60"));

      // Wait for workflow completion.
      client.newUntypedWorkflowStub(PREINIT_DRAIN_DOUBLE_PLACE_WORKFLOW_ID).getResult(String.class);

      json = client.fetchHistory(PREINIT_DRAIN_DOUBLE_PLACE_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(PREINIT_DRAIN_DOUBLE_PLACE_FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  private static PositionWorkflowInput preinitDrainInput() {
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    in.setEntrySignalId("preinit-drain-entry-1");
    in.setContractSymbol(PREINIT_DRAIN_OCC_SYMBOL);
    in.setQty(6L);
    in.setEntryPremium(new BigDecimal("2.30"));
    return in;
  }

  private static PartialExitRequest preinitDrainPartialExitRequest(
      String signalId, double fraction) {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setSignalId(signalId);
    req.setPositionWorkflowId(PREINIT_DRAIN_DOUBLE_PLACE_WORKFLOW_ID);
    req.setFraction(BigDecimal.valueOf(fraction));
    req.setRefPremium(new BigDecimal("2.85"));
    req.setReason("stc_signal");
    req.setAuthor("acme_trader");
    req.setRawLine("STC NVDA 5/16 140C @ 2.85");
    req.setOccurredAt(OffsetDateTime.of(2026, 5, 13, 17, 45, 0, 0, ZoneOffset.UTC));
    return req;
  }

  /**
   * One-shot recorder for {@link #CARRIED_FIXTURE_RESOURCE}, run against the REAL {@link
   * PositionWorkflowImpl}. Lowers the watermark (the {@code PositionWorkflowImplContinueAsNewTest}
   * technique) so neutral ticks roll an armed position, restores it so the carried run cannot roll
   * again, then drives ticks, a ratchet and a fire on the carried run and records ITS history.
   */
  @Test
  @EnabledIfSystemProperty(named = "generate.carried.fixture", matches = "true")
  void regenerateCarriedRunFixture() throws Exception {
    Field watermark = PositionWorkflowImpl.class.getDeclaredField("historyLengthWatermark");
    watermark.setAccessible(true);
    long originalWatermark = watermark.getLong(null);
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      Worker coreWorker = env.newWorker(CORE_QUEUE);
      coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      ExecActivities exec = Mockito.mock(ExecActivities.class);
      SubscribePremiumActivity marketData = Mockito.mock(SubscribePremiumActivity.class);
      GetOptionQuoteActivity optionQuote = Mockito.mock(GetOptionQuoteActivity.class);
      when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
      when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
      when(calendar.durationUntilExpiryFlattenEt(any(), Mockito.anyLong(), any()))
          .thenReturn(Duration.ZERO);
      when(exec.placeOrder(any())).thenReturn(submitted());
      SubscribePremiumResult subscribed = new SubscribePremiumResult();
      subscribed.setSchemaVersion(1L);
      subscribed.setSubscriptionId("sub-carried");
      subscribed.setSubscribedAt(OffsetDateTime.now());
      subscribed.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
      when(marketData.subscribePremium(any())).thenReturn(subscribed);
      OptionQuoteResult quote = new OptionQuoteResult();
      quote.setSchemaVersion(1L);
      quote.setContractSymbol(CARRIED_OCC);
      quote.setBid(new BigDecimal("2.50"));
      quote.setMid(new BigDecimal("2.55"));
      quote.setAsk(new BigDecimal("2.60"));
      quote.setRetrievedAt(OffsetDateTime.now());
      quote.setStatus(OptionQuoteResult.Status.OK);
      when(optionQuote.getOptionQuote(any())).thenReturn(quote);

      coreWorker.registerActivitiesImplementations(audit, calendar);
      env.newWorker(CopytradeSignalWorkflowImpl.EXEC_TASK_QUEUE_ALPACA_PAPER)
          .registerActivitiesImplementations(exec);
      env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE)
          .registerActivitiesImplementations(marketData, optionQuote);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      PositionWorkflow wf =
          client.newWorkflowStub(
              PositionWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(CARRIED_WORKFLOW_ID)
                  .setTypedSearchAttributes(
                      SearchAttributes.newBuilder()
                          .set(
                              SearchAttributeKey.forKeyword("TenantStrategy"),
                              "t-dev/s-copytrade-v1")
                          .set(SearchAttributeKey.forKeyword("ContractSymbol"), CARRIED_OCC)
                          .build())
                  .build());
      PositionWorkflowInput in = input();
      in.setEntrySignalId("carried-entry-1");
      in.setContractSymbol(CARRIED_OCC);
      in.setExitFloorPct(new BigDecimal("0.50"));
      watermark.setLong(null, 60L);
      WorkflowStub.fromTyped(wf).start(in);
      wf.onFill(fill("brk-entry", 5L, "2.30"));
      ArmChandelierPayload arm = new ArmChandelierPayload();
      arm.setSchemaVersion(1L);
      arm.setTenantId("dev");
      arm.setStrategyId("copytrade-v1");
      arm.setPositionWorkflowId(CARRIED_WORKFLOW_ID);
      arm.setSourceSignalId("carried-src-1");
      arm.setPeakPremium(new BigDecimal("3.00"));
      arm.setGivebackPct(new BigDecimal("0.20"));
      wf.armChandelier(arm);
      awaitCondition(() -> wf.trailingState().armed(), "trail armed");

      // Neutral ticks (above threshold 2.40, below peak 3.00) until the run rolls.
      awaitCondition(
          () -> {
            wf.chandelierTick(carriedTick("2.50"));
            return !client
                .fetchHistory(CARRIED_WORKFLOW_ID)
                .getEvents()
                .get(0)
                .getWorkflowExecutionStartedEventAttributes()
                .getContinuedExecutionRunId()
                .isEmpty();
          },
          "continue-as-new");
      watermark.setLong(null, originalWatermark);

      // Carried run: neutral tick, ratchet 3.00 -> 3.20 (threshold 2.56), neutral, fire at 2.50.
      wf.chandelierTick(carriedTick("2.60"));
      wf.chandelierTick(carriedTick("3.20"));
      wf.chandelierTick(carriedTick("2.70"));
      wf.chandelierTick(carriedTick("2.50"));
      awaitCondition(
          () ->
              Mockito.mockingDetails(exec).getInvocations().stream()
                  .anyMatch(i -> i.getMethod().getName().equals("placeOrder")),
          "chandelier flatten placed");
      wf.onFill(fill("brk-exit", 5L, "2.50"));
      client.newUntypedWorkflowStub(CARRIED_WORKFLOW_ID).getResult(String.class);

      json = client.fetchHistory(CARRIED_WORKFLOW_ID).toJson(true);
    } finally {
      watermark.setLong(null, originalWatermark);
      env.close();
    }

    Files.createDirectories(CARRIED_FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(CARRIED_FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  private static PremiumTick carriedTick(String premium) {
    PremiumTick t = new PremiumTick();
    t.setSchemaVersion(1L);
    t.setContractSymbol(CARRIED_OCC);
    t.setPremium(new BigDecimal(premium));
    t.setRetrievedAt(OffsetDateTime.now());
    return t;
  }

  private static FillSignalPayload fill(String brokerOrderId, long qty, String avg) {
    return new FillSignalPayload()
        .withBrokerOrderId(brokerOrderId)
        .withFilledQty(qty)
        .withAvgFillPrice(new BigDecimal(avg))
        .withFilledAt(OffsetDateTime.now());
  }

  private static void awaitCondition(java.util.function.BooleanSupplier cond, String what)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 50_000;
    while (!cond.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(50);
    }
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
    public void supersede(String correctedSignalId, String correctedOcc) {}

    @Override
    public TrailingState trailingState() {
      return new TrailingState(false, null, null, null, null, null, null, 0L);
    }

    @Override
    public PositionState positionState() {
      return new PositionState("", 0L, null);
    }

    @Override
    public ExitProximityView exitProximity() {
      return new ExitProximityView(
          "", null, null, null, null, null, null, false, null, false, null);
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

    @Override
    public void partialCloseValidator(PartialCloseRequest request) {}

    @Override
    public PartialCloseResult partialClose(PartialCloseRequest request) {
      return null;
    }

    // PLAN-2026-08-16 arm_trail: test double only — never invoked by these suites. Returning null
    // is the same convention the sibling Updates above use.
    @Override
    public void armTrailValidator(ArmTrailRequest request) {}

    @Override
    public ArmTrailResult armTrail(ArmTrailRequest request) {
      return null;
    }

    @Override
    public void correctBookedLotValidator(
        com.ohmytradeagent.contract.CorrectBookedLotRequest request) {}

    @Override
    public com.ohmytradeagent.contract.CorrectBookedLotResult correctBookedLot(
        com.ohmytradeagent.contract.CorrectBookedLotRequest request) {
      return null;
    }

    @Override
    public void disarmTrailValidator(com.ohmytradeagent.contract.DisarmTrailRequest request) {}

    @Override
    public com.ohmytradeagent.contract.DisarmTrailResult disarmTrail(
        com.ohmytradeagent.contract.DisarmTrailRequest request) {
      return null;
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
