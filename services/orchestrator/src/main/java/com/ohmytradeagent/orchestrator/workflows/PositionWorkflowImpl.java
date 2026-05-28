package com.ohmytradeagent.orchestrator.workflows;

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
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import com.ohmytradeagent.orchestrator.domain.OptionTick;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Long-running position lifecycle. Receives STC dispatches via {@link
 * #partialExit(PartialExitRequest)}, fills via {@link #onFill(FillSignalPayload)}, and
 * force-flattens on EOD (15:55 ET) or expiry close (15:30 ET for 0DTE). Deterministic by
 * construction — all time reads go through {@link MarketCalendarActivities} or {@link Workflow}.
 *
 * <p>Phase 4 adds CHANDELIER_TRAIL: the CopytradeSignalWorkflow's STC branch may signal {@link
 * #armChandelier(ArmChandelierPayload)} after the partial exit, which subscribes a premium stream
 * via market-data-svc. Each {@link #chandelierTick(PremiumTick)} updates the peak and fires a
 * flatten when the tick falls to or below {@code peak * (1 - giveback_pct)}.
 */
public class PositionWorkflowImpl implements PositionWorkflow {

  // Audit kinds
  private static final String KIND_POSITION_ENTERED = "PositionEntered";
  private static final String KIND_PARTIAL_EXIT_REQUESTED = "PartialExitRequested";
  private static final String KIND_PARTIAL_EXIT_FILLED = "PartialExitFilled";
  private static final String KIND_EXIT_DUPLICATE_SUPPRESSED = "ExitDuplicateSuppressed";
  private static final String KIND_EXIT_QUEUED = "ExitQueued";
  private static final String KIND_EOD_FORCE_FLATTEN_REQUESTED = "EodForceFlattenRequested";
  private static final String KIND_EOD_FORCE_FLATTENED = "EodForceFlattened";
  private static final String KIND_EOD_FORCE_FLATTEN_FAILED = "EodForceFlattenFailed";
  private static final String KIND_EXPIRY_FORCE_FLATTEN_REQUESTED = "ExpiryForceFlattenRequested";
  private static final String KIND_EXPIRY_FORCE_FLATTENED = "ExpiryForceFlattened";
  private static final String KIND_POSITION_CLOSED = "PositionClosed";

  // Phase 4 audit kinds
  private static final String KIND_CHANDELIER_ARMED = "ChandelierArmed";
  private static final String KIND_CHANDELIER_TRAIL_FIRED = "ChandelierTrailFired";
  private static final String KIND_CHANDELIER_ARM_REJECTED = "ChandelierArmRejected";
  private static final String KIND_CHANDELIER_SUBSCRIPTION_FAILED = "ChandelierSubscriptionFailed";
  private static final String KIND_CHANDELIER_UNARMED_BY_EXIT = "ChandelierUnarmedByExit";

  // Phase 5 audit kinds
  private static final String KIND_RISK_BREACH_RECEIVED = "RiskBreachReceived";
  private static final String KIND_RISK_BREACH_ACTED = "RiskBreachActed";
  private static final String KIND_FORCE_CLOSE_REQUESTED = "ForceCloseRequested";
  private static final String KIND_FORCE_CLOSE_NOOP = "ForceCloseNoop";

  // Issue #203 audit kind: BTO submission never reached FILLED within the bounded
  // first-fill TTL. Reconciliation uses this signal to prune the stale SUBMITTED
  // journal row instead of leaving an orphan that downstream STCs could target.
  private static final String KIND_POSITION_NEVER_FILLED = "PositionNeverFilled";

  // Issue #204 audit kind: an exit order placed by processOne() did not receive a fill
  // event within the bounded exit-fill TTL. The handler best-effort cancels the
  // broker order and releases the exitInFlight latch so subsequent STCs can drain;
  // remainingQty is NOT decremented (no fill happened). Reconciliation closes the loop
  // on the broker-side order state.
  private static final String KIND_PARTIAL_EXIT_FILL_TIMEOUT = "PartialExitFillTimeout";

  // Issue #205 audit kind: a partial-exit signal arrived for a remainingQty<=1 runner where
  // floor(remainingQty * fraction) == 0 (i.e. the integer broker quantum cannot honestly
  // represent the requested partial). When StrategyConfig.min_partial_qty_behavior is SKIP
  // (or null/absent → SKIP per the YAML-documented default), processOne emits this audit
  // and places NO close order — the runner survives for trail/EOD/STC drain. Classified as
  // a PARTIAL_EXIT_FILL_KINDS member in AuditEventKinds because it fulfills the partial-exit
  // request by deciding-not-to-fill, the same pattern as ExitDuplicateSuppressed.
  private static final String KIND_PARTIAL_EXIT_SKIPPED_MIN_QTY = "PartialExitSkippedMinQty";

  // Issue #216 audit kind: emitted by processOne()'s v=1 timeout branch when the original
  // exit order timed out without a fill AND retry_count==0, immediately before placing the
  // retry order with a fresh limit price and a fresh intent_key (suffix ":retry"). Subject
  // carries signal_id (unchanged — same logical STC), retry_attempt=1, the fresh limit price,
  // and source_premium describing where the fresh price came from (last_tick / peak / ref).
  // NOT a lifecycle event — the next PartialExitFilled (on retry success) or second
  // PartialExitFillTimeout (on retry timeout, after which the STC is dropped) closes the
  // exit cycle. Registered in AuditEventKinds.ALL_KINDS only.
  private static final String KIND_PARTIAL_EXIT_RETRY_REQUESTED = "PartialExitRetryRequested";

  private static final String VERSION_CHANDELIER = "chandelier-v1";
  private static final String VERSION_RISK_BREACH = "risk-breach-v1";
  private static final String VERSION_FORCE_CLOSE = "force-close-v1";

  /**
   * Issue #204 replay gate. v=DEFAULT_VERSION (in-flight workflows started before this patch) keep
   * the original untimed {@code Workflow.await} in {@link #processOne(PartialExitRequest)} so their
   * recorded histories replay without a Temporal non-determinism error. v>=1 (new executions) take
   * the bounded await that emits {@link #KIND_PARTIAL_EXIT_FILL_TIMEOUT}, cancels the broker order,
   * and releases the in-flight latch so {@code pendingExits} drains on the next iteration. Distinct
   * from {@link #VERSION_DEFER_POSITION_ENTERED} (issue #203, the entry-side analogue) so the two
   * patches roll independently.
   */
  private static final String VERSION_EXIT_FILL_TIMEOUT = "exit-fill-timeout";

  /**
   * Issue #203 replay gate. v=DEFAULT_VERSION (in-flight workflows started before this patch)
   * preserve the legacy "PositionEntered emitted at workflow start with qty=input.qty" behavior so
   * their replays don't trip a Temporal non-determinism error. v>=1 (new executions) defer
   * PositionEntered and remainingQty until the first onFill arrives, and emit PositionNeverFilled +
   * terminate if no fill arrives within {@link #FIRST_FILL_TTL_SECS_DEFAULT}.
   */
  private static final String VERSION_DEFER_POSITION_ENTERED = "position-entered-on-fill";

  /**
   * Issue #205 replay gate. v=DEFAULT_VERSION (in-flight workflows started before this patch) keep
   * the legacy {@code qtyToClose = ceil(remainingQty * fraction)} path inside {@link
   * #processOne(PartialExitRequest)} so their recorded histories replay without a Temporal
   * non-determinism error — pre-#205 a remainingQty=1 + fraction=0.5 STC always closed the runner
   * (ceil(0.5)=1), regardless of YAML config. v>=1 (new executions) consult {@code
   * input.min_partial_qty_behavior}: SKIP (default when null/absent) emits {@link
   * #KIND_PARTIAL_EXIT_SKIPPED_MIN_QTY} and places no order when the rounded-down qty would be
   * zero; FULL_CLOSE sets {@code qtyToClose=remainingQty} and continues the normal partial-exit
   * flow. Closes the dead-config gap from issue #205 — the YAML key was declared in {@code
   * copytrade-v1.yaml} but no Java code read it.
   */
  private static final String VERSION_MIN_PARTIAL_QTY_SKIP = "min-partial-qty-skip";

  /**
   * Issue #212 replay gate. v=DEFAULT_VERSION (in-flight workflows started before this patch) keep
   * the hardcoded {@link #FIRST_FILL_TTL_SECS_DEFAULT} / {@link #EXIT_FILL_TTL_SECS_DEFAULT}
   * constants in the two bounded {@code Workflow.await} calls so their recorded histories replay
   * without a Temporal non-determinism error. v>=1 (new executions) read {@code
   * input.first_fill_ttl_secs} and {@code input.exit_fill_ttl_secs} so per-strategy paper/live TTLs
   * from {@code StrategyConfig.pending_ttl_paper_secs} / {@code pending_ttl_live_secs} actually
   * drive the runtime behavior. Null input fields under v>=1 still fall back to the constants so a
   * pre-#212 PositionWorkflowInput payload (e.g. from a re-played CopytradeSignal) keeps the legacy
   * 90s behavior. Distinct from {@link #VERSION_DEFER_POSITION_ENTERED} (#203) and {@link
   * #VERSION_EXIT_FILL_TIMEOUT} (#204) so the TTL-source change rolls independently of the
   * underlying timeout mechanisms.
   */
  private static final String VERSION_TTL_FROM_INPUT = "ttl-from-input";

  /**
   * Issue #216 replay gate. v=DEFAULT_VERSION (in-flight workflows started before this patch) keep
   * the PR #214 behavior of silently dropping the STC on {@link #KIND_PARTIAL_EXIT_FILL_TIMEOUT}.
   * v>=1 (new executions) retry the timed-out STC exactly once with a fresh limit price and a fresh
   * {@code intent_key} (suffix {@code ":retry"}) before falling back to the drop. Distinct from the
   * six prior session keys ({@link #VERSION_DEFER_POSITION_ENTERED} #203, {@link
   * #VERSION_EXIT_FILL_TIMEOUT} #204, {@link #VERSION_MIN_PARTIAL_QTY_SKIP} #205, {@link
   * #VERSION_TTL_FROM_INPUT} #212, the chandelier / risk-breach / force-close gates) so the retry
   * policy rolls independently of the underlying timeout machinery.
   */
  private static final String VERSION_EXIT_RETRY_ON_TIMEOUT = "exit-retry-on-timeout";

  /**
   * Issue #227 replay gate. v=DEFAULT_VERSION (in-flight workflows that already entered the #216
   * retry block under PR #226 v=1) keep the original {@code lastTickPremium → peakPremium →
   * refPremium} fresh-limit fallback order for byte-identical replay. v>=1 (new executions) use the
   * corrected order {@code lastTickPremium → refPremium → peakPremium} — {@code peakPremium} is a
   * chandelier high-water-mark biased high for SELL exits, so it is the last-resort fallback rather
   * than a preferred source. Distinct from the eight prior session keys ({@link
   * #VERSION_DEFER_POSITION_ENTERED} #203, {@link #VERSION_EXIT_FILL_TIMEOUT} #204, {@link
   * #VERSION_MIN_PARTIAL_QTY_SKIP} #205, {@link #VERSION_TTL_FROM_INPUT} #212, {@link
   * #VERSION_EXIT_RETRY_ON_TIMEOUT} #216, the chandelier / risk-breach / force-close gates) so the
   * source-order change rolls independently of the underlying retry machinery.
   */
  private static final String VERSION_EXIT_RETRY_SOURCE_ORDER = "exit-retry-source-order";

  /**
   * Issue #203 / #212 fallback: bounded wait for the first onFill before the workflow gives up and
   * emits PositionNeverFilled. Matches {@code pending_ttl_paper_secs} in {@code copytrade-v1.yaml}
   * (90s paper default). Used (a) for v=DEFAULT_VERSION replays under {@link
   * #VERSION_TTL_FROM_INPUT}, and (b) under v>=1 when {@code input.first_fill_ttl_secs} is null
   * (e.g. PositionWorkflowInput payload was minted by a pre-#212 CopytradeSignalWorkflow).
   */
  private static final long FIRST_FILL_TTL_SECS_DEFAULT = 90L;

  /**
   * Issue #204 / #212 fallback: bounded wait for an exit-order fill event inside {@link
   * #processOne(PartialExitRequest)} before the workflow times out, cancels the broker order and
   * releases the in-flight latch so subsequent STCs can drain. Used (a) for v=DEFAULT_VERSION
   * replays under {@link #VERSION_TTL_FROM_INPUT}, and (b) under v>=1 when {@code
   * input.exit_fill_ttl_secs} is null.
   */
  private static final long EXIT_FILL_TTL_SECS_DEFAULT = 90L;

  private static final BigDecimal MAX_GIVEBACK = new BigDecimal("0.5");

  /**
   * Phase 2c.2 default broker_target used when a {@link PositionWorkflowInput} arrives without one
   * (e.g. minted by a pre-2c.2 CopytradeSignalWorkflow). Matches the {@code
   * tenants/dev/strategies/copytrade-v1.yaml} default.
   */
  private static final String DEFAULT_BROKER_TARGET = "alpaca-paper";

  static final String MARKET_DATA_TASK_QUEUE = "market-data";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);

  /**
   * Phase 2c.2: built lazily inside {@link #run(PositionWorkflowInput)} from {@code
   * input.broker_target}. Pre-2c.2 inputs (broker_target absent) fall back to {@link
   * #DEFAULT_BROKER_TARGET}.
   */
  private ExecActivities exec;

  private final SubscribePremiumActivity marketData =
      Workflow.newActivityStub(
          SubscribePremiumActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .build());

  private PositionWorkflowInput input;
  private long remainingQty;

  /**
   * Issue #203: original BTO size from input.qty. Recorded only for the PositionNeverFilled audit
   * subject — never used for sizing under v=1, where remainingQty derives from the first onFill.
   */
  private long expectedQty;

  /**
   * Issue #203: latched true by {@link #onFill(FillSignalPayload)} on the first fill that arrives.
   * Drives the v=1 first-fill await gate in {@link #run(PositionWorkflowInput)}. Distinct from
   * {@code lastFillEvent} (which is cleared and re-used by every {@link #processOne} cycle).
   */
  private boolean firstFillReceived;

  /**
   * Issue #203: latched true by {@link #run(PositionWorkflowInput)} once remainingQty has been
   * authoritatively assigned (v=0 from input.qty; v=1 from the first onFill). The {@link
   * #partialExit(PartialExitRequest)} handler buffers signals into pendingExits while this is
   * false, so an STC racing the entry-fill confirmation is processed only after the position is
   * real. Independent of {@link #firstFillReceived} because the signal handler may run before
   * run()'s main thread has woken from the first-fill await.
   */
  private boolean positionConfirmed;

  private final LinkedHashSet<String> processedSignalIds = new LinkedHashSet<>();
  private boolean exitInFlight;
  private final ArrayDeque<PartialExitRequest> pendingExits = new ArrayDeque<>();
  private FillSignalPayload lastFillEvent;
  private String currentInFlightBrokerOrderId;
  private String currentInFlightSignalId;
  // Issue #216: track the live intent_key of the in-flight exit order. Pre-#216 the key was
  // always {@code workflowId:exit:<signalId>} and {@link #flattenRemaining(String)} could
  // reconstruct it from {@link #currentInFlightSignalId}. The retry path uses a ":retry"-suffixed
  // key, so flattenRemaining must read the actual key rather than reconstruct it; otherwise an
  // EOD/expiry/risk_breach/force_close preemption during the retry window would cancel the wrong
  // (non-existent) intent_key and leave the retry broker order orphaned.
  private String currentInFlightIntentKey;
  private boolean eodFired;
  private boolean expiryFired;

  // Phase 4: chandelier-trail state
  private boolean trailingArmed;
  private BigDecimal peakPremium;
  private BigDecimal givebackPct;
  private long ticksReceived;
  private BigDecimal lastTickPremium;
  private OffsetDateTime lastTickAt;

  /**
   * Buffered arm payloads. Signal handlers only enqueue (no activity calls); the main loop drains
   * and executes the subscribe activity. Keeps signal-processing deterministic and avoids two
   * concurrent arm signals racing through {@code marketData.subscribePremium}.
   */
  private final ArrayDeque<ArmChandelierPayload> pendingArms = new ArrayDeque<>();

  /**
   * Buffered ticks. Drained by the main loop AFTER arm processing so the arm vs tick race ("arm and
   * tick signals arrive in the same workflow task") never drops a fire-worthy tick.
   */
  private final ArrayDeque<PremiumTick> pendingTicks = new ArrayDeque<>();

  /** True once a tick crosses the threshold; main loop fires the flatten. */
  private boolean chandelierFireRequested;

  /** Tick that triggered the fire — recorded so the audit subject carries trigger_premium. */
  private PremiumTick fireTriggerTick;

  /** Threshold at fire time — recorded for the audit subject. */
  private BigDecimal fireThreshold;

  /** Set when the workflow's own close logic flattens. Drives the un-armed-by-exit audit. */
  private String closeReason;

  // Phase 5: buffered risk-breach + force-close directives. Same pattern as
  // pendingExits/pendingArms
  // — signal/Update handlers only enqueue; the main loop drains and acts. Keeps Updates fast
  // (handler returns after enqueue) and keeps handlers free of activity calls (deterministic).
  private final ArrayDeque<RiskBreachPayload> pendingRiskBreaches = new ArrayDeque<>();
  private final ArrayDeque<ForceCloseDirective> pendingForceCloses = new ArrayDeque<>();

  /** Internal directive emitted by the force_close Update handler into the main loop. */
  private record ForceCloseDirective(String operatorId, String reason, String exitSignalId) {}

  @Override
  public String run(PositionWorkflowInput in) {
    this.input = in;
    // Issue #203: input.qty is the *expected* quantity (sourced from the parent
    // CopytradeSignalWorkflow's BTO fill in normal flow). Under v=1 we no longer treat it as
    // proof-of-fill — remainingQty stays 0 until the first onFill arrives.
    this.expectedQty = in.getQty();

    // Phase 2c.2: route exec Activities to broker-<broker_target>. Falls back to the
    // 2c.2 default broker when the input was minted by a pre-2c.2 CopytradeSignalWorkflow that
    // didn't populate broker_target.
    String brokerTarget =
        in.getBrokerTarget() != null ? in.getBrokerTarget().value() : DEFAULT_BROKER_TARGET;
    this.exec = ExecActivitiesFactory.forTarget(brokerTarget);

    int deferVersion =
        Workflow.getVersion(VERSION_DEFER_POSITION_ENTERED, Workflow.DEFAULT_VERSION, 1);
    if (deferVersion == Workflow.DEFAULT_VERSION) {
      // Legacy in-flight workflows: preserve the original ordering — assign remainingQty from
      // input.qty and emit PositionEntered at workflow start so their recorded histories replay
      // without a non-determinism error.
      this.remainingQty = in.getQty();
      this.positionConfirmed = true;
      auditLog(
          KIND_POSITION_ENTERED,
          subject(
              "entry_signal_id", in.getEntrySignalId(),
              "contract_symbol", in.getContractSymbol(),
              "qty", in.getQty(),
              "entry_premium", in.getEntryPremium()));
    }
    // v>=1: PositionEntered + remainingQty assignment are deferred to the awaitFirstFill step
    // below.

    // Issue #202: copytrade strategies set eod_force_flatten=false because the only
    // normal exit for an author-mirror position is an STC message from the Discord
    // author; forcing a flatten at 15:55 ET would diverge from the author's actual
    // position. Null is treated as true to preserve pre-#202 behavior for replays
    // of positions spawned before this field existed. The expiry-close timer below
    // still arms unconditionally (0DTE physical expiry is not a tunable).
    boolean armEodTimer = !Boolean.FALSE.equals(in.getEodForceFlatten());

    Duration eodIn = calendar.durationUntilEodEt();
    Duration expiryIn = Duration.ZERO;
    LocalDate expiryDate = expiryDateFromOcc(in.getContractSymbol());
    if (expiryDate != null) {
      expiryIn = calendar.durationUntilExpiryCloseEt(expiryDate);
    }

    if (armEodTimer && !eodIn.isZero() && !eodIn.isNegative()) {
      Promise<Void> eodTimer = Workflow.newTimer(eodIn);
      eodTimer.thenApply(
          v -> {
            eodFired = true;
            return null;
          });
    }
    if (!expiryIn.isZero() && !expiryIn.isNegative()) {
      Promise<Void> expiryTimer = Workflow.newTimer(expiryIn);
      expiryTimer.thenApply(
          v -> {
            expiryFired = true;
            return null;
          });
    }

    // Issue #203: v>=1 awaits the first onFill before declaring PositionEntered. If no fill
    // arrives within the resolved first-fill TTL (or EOD/expiry pre-empt first), emit
    // PositionNeverFilled and terminate so reconciliation can prune the stale SUBMITTED journal
    // row. Issue #212: the TTL value is sourced from input under VERSION_TTL_FROM_INPUT v>=1
    // (carried over from StrategyConfig.pending_ttl_paper_secs / pending_ttl_live_secs by the
    // spawning CopytradeSignalWorkflow); under v=DEFAULT_VERSION or when the input field is null,
    // it falls back to FIRST_FILL_TTL_SECS_DEFAULT to preserve pre-#212 replay semantics.
    long firstFillTtlSecs = resolveFirstFillTtlSecs(in);
    if (deferVersion >= 1) {
      boolean filled =
          Workflow.await(
              Duration.ofSeconds(firstFillTtlSecs),
              () -> firstFillReceived || eodFired || expiryFired);
      if (!filled || !firstFillReceived) {
        auditLog(
            KIND_POSITION_NEVER_FILLED,
            subject(
                "entry_signal_id",
                in.getEntrySignalId(),
                "contract_symbol",
                in.getContractSymbol(),
                "expected_qty",
                expectedQty,
                "ttl_secs",
                firstFillTtlSecs));
        return Workflow.getInfo().getWorkflowId();
      }
      // First fill confirms the position. remainingQty MUST come from the fill, not input.qty —
      // partial fills are possible and the audit + downstream logic must reflect the real qty.
      long firstFilledQty = lastFillEvent.getFilledQty();
      BigDecimal firstFillPrice =
          lastFillEvent.getAvgFillPrice() != null
              ? lastFillEvent.getAvgFillPrice()
              : in.getEntryPremium();
      this.remainingQty = firstFilledQty;
      this.positionConfirmed = true;
      auditLog(
          KIND_POSITION_ENTERED,
          subject(
              "entry_signal_id",
              in.getEntrySignalId(),
              "contract_symbol",
              in.getContractSymbol(),
              "qty",
              firstFilledQty,
              "entry_premium",
              firstFillPrice));
      // Clear lastFillEvent so the next processOne()'s await for the partial-exit fill doesn't
      // immediately observe the stale entry fill.
      this.lastFillEvent = null;
    }

    while (remainingQty > 0 && !eodFired && !expiryFired) {
      Workflow.await(
          () ->
              !pendingExits.isEmpty()
                  || !pendingArms.isEmpty()
                  || !pendingTicks.isEmpty()
                  || !pendingRiskBreaches.isEmpty()
                  || !pendingForceCloses.isEmpty()
                  || chandelierFireRequested
                  || eodFired
                  || expiryFired
                  || remainingQty == 0);
      if (eodFired || expiryFired || remainingQty == 0) {
        break;
      }
      // Phase 5: risk_breach + force_close take priority over the normal exit pipeline so
      // operator intent and kill-switch cascades are not blocked behind a queued STC.
      if (!pendingRiskBreaches.isEmpty()) {
        RiskBreachPayload rb = pendingRiskBreaches.poll();
        processRiskBreach(rb);
        continue; // flattenRemaining drained remainingQty -> next iteration exits.
      }
      if (!pendingForceCloses.isEmpty()) {
        ForceCloseDirective fc = pendingForceCloses.poll();
        processForceClose(fc);
        continue;
      }
      // Drain arms first so a co-arriving tick sees armed=true.
      while (!pendingArms.isEmpty()) {
        processArm(pendingArms.poll());
      }
      // Then drain ticks; processTick latches chandelierFireRequested on threshold breach.
      while (!pendingTicks.isEmpty()) {
        processTick(pendingTicks.poll());
      }
      if (chandelierFireRequested) {
        chandelierFireRequested = false;
        fireChandelier();
        // flattenRemaining sets remainingQty=0 -> next iteration exits the loop.
        continue;
      }
      if (!pendingExits.isEmpty()) {
        PartialExitRequest req = pendingExits.poll();
        processOne(req);
      }
    }

    if (eodFired || expiryFired) {
      flattenRemaining(eodFired ? "eod" : "expiry");
    }

    // Phase 4: if the position closed via a non-chandelier path while the trail was armed, audit
    // that the trail was torn down by the exit.
    if (trailingArmed) {
      String reason = closeReason != null ? closeReason : "normal_stc";
      if (!"chandelier_trail".equals(reason)) {
        auditLog(KIND_CHANDELIER_UNARMED_BY_EXIT, subject("reason", reason));
      }
    }

    auditLog(
        KIND_POSITION_CLOSED,
        subject(
            "entry_signal_id", input.getEntrySignalId(),
            "contract_symbol", input.getContractSymbol(),
            "remaining_qty", remainingQty));

    return Workflow.getInfo().getWorkflowId();
  }

  @Override
  public void partialExit(PartialExitRequest req) {
    // Temporal can dispatch signals before the @WorkflowMethod body has executed (the constructor
    // ran, but `run(input)` hasn't reached `this.input = in` yet). In that race, `input` is null
    // and every auditLog call below would NPE. Defer the legacy null-input case to the main loop;
    // the rest of the validation runs in-handler so duplicate / fraction audits fire promptly even
    // for signals that arrive before run()'s main thread has resumed.
    if (input == null) {
      pendingExits.add(req);
      return;
    }
    // Issue #203: when v=1 has not yet confirmed the position (positionConfirmed=false), still run
    // duplicate / fraction validation in-handler so the audit trail matches v=0 semantics. The
    // position-confirmed gate only changes WHERE remainingQty is consulted: for the
    // "position_already_drained" audit (which requires a real position to be drained from), defer
    // that check to processOne via the main loop. If the v=1 first-fill TTL elapses without an
    // entry fill, run() returns via PositionNeverFilled without entering the main loop, so
    // buffered exits are dropped (no broker placeOrder, no credit against a phantom position).
    // Drained-position check only applies once the position has been confirmed — otherwise we'd
    // misclassify a pre-fill STC as a duplicate of a phantom position.
    if (positionConfirmed && remainingQty <= 0) {
      auditLog(
          KIND_EXIT_DUPLICATE_SUPPRESSED,
          subject("signal_id", req.getSignalId(), "note", "position_already_drained"));
      return;
    }
    if (!processedSignalIds.add(req.getSignalId())) {
      auditLog(
          KIND_EXIT_DUPLICATE_SUPPRESSED,
          subject("signal_id", req.getSignalId(), "note", "duplicate_signal_id"));
      return;
    }
    double fraction = req.getFraction() == null ? 0.0 : req.getFraction().doubleValue();
    if (fraction <= 0.0 || fraction > 1.0) {
      auditLog(
          KIND_EXIT_DUPLICATE_SUPPRESSED,
          subject(
              "signal_id", req.getSignalId(),
              "note", "bad_fraction",
              "fraction", req.getFraction()));
      return;
    }
    boolean wasBusy = exitInFlight || !pendingExits.isEmpty();
    pendingExits.add(req);
    if (wasBusy) {
      auditLog(
          KIND_EXIT_QUEUED,
          subject(
              "signal_id", req.getSignalId(),
              "queue_depth", pendingExits.size()));
    }
  }

  @Override
  public void onFill(FillSignalPayload event) {
    this.lastFillEvent = event;
    // Issue #203: latch on the first fill so run()'s v>=1 await wakes. Subsequent fills (exit fills
    // dispatched into processOne) still update lastFillEvent but don't reset the latch — the
    // latch's sole purpose is the entry-confirmation gate in run().
    this.firstFillReceived = true;
  }

  @Override
  public void armChandelier(ArmChandelierPayload p) {
    int v = Workflow.getVersion(VERSION_CHANDELIER, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // Buffer only — main loop performs validation and the subscribe activity to keep signal
    // handlers free of activity calls (deterministic-by-default pattern, matches partialExit).
    pendingArms.add(p);
  }

  @Override
  public void chandelierTick(PremiumTick tick) {
    int v = Workflow.getVersion(VERSION_CHANDELIER, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // Buffer only — main loop drains AFTER arms so a co-arriving arm+tick pair fires correctly.
    pendingTicks.add(tick);
  }

  @Override
  public void riskBreach(RiskBreachPayload payload) {
    int v = Workflow.getVersion(VERSION_RISK_BREACH, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    pendingRiskBreaches.add(payload);
    // auditLog dereferences `input` — only safe once run() has assigned it. Skip the
    // "received" audit on the signal-before-run race; processRiskBreach still emits ActedOn
    // in the main loop after init.
    if (input != null) {
      auditLog(
          KIND_RISK_BREACH_RECEIVED,
          subject("reason", payload.getReason(), "actor", payload.getActor()));
    }
  }

  @Override
  public void forceCloseValidator(ForceCloseRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request_required");
    }
    if (request.getOperatorId() == null || request.getOperatorId().isBlank()) {
      throw new IllegalArgumentException("operator_id_required");
    }
    if (request.getReason() == null || request.getReason().isBlank()) {
      throw new IllegalArgumentException("reason_required");
    }
  }

  @Override
  public ForceCloseResult forceClose(ForceCloseRequest request) {
    int v = Workflow.getVersion(VERSION_FORCE_CLOSE, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      // Pre-v1 replay: surface a no-op so the caller still gets a structured response. Not
      // expected on fresh starts since this Update is only added in Phase 5.
      ForceCloseResult r = new ForceCloseResult();
      r.setSchemaVersion(1L);
      r.setStatus(ForceCloseResult.Status.NOOP_ALREADY_CLOSED);
      r.setExitSignalId("force:noop:legacy");
      return r;
    }
    String exitSignalId = "force:" + request.getOperatorId() + ":" + Workflow.currentTimeMillis();
    ForceCloseResult result = new ForceCloseResult();
    result.setSchemaVersion(1L);
    result.setExitSignalId(exitSignalId);

    // Update can land before run() body executes; buffer the directive so the main loop processes
    // it after init. ACCEPTED is the right semantic — the operator's exit_signal_id is the dedupe
    // key, and the actual flatten happens once the workflow is fully initialized.
    if (input == null) {
      pendingForceCloses.add(
          new ForceCloseDirective(request.getOperatorId(), request.getReason(), exitSignalId));
      result.setStatus(ForceCloseResult.Status.ACCEPTED);
      return result;
    }

    // Issue #203: only treat remainingQty<=0 as "already closed" once the position has been
    // confirmed. Under v=1 pre-first-fill, remainingQty stays 0 even though the operator's intent
    // is to flatten a position that's about to be confirmed. Buffer the directive so the main
    // loop applies it after the first-fill await unblocks. If the TTL elapses without an entry
    // fill, run() returns via PositionNeverFilled and the buffered directive is dropped.
    if (positionConfirmed && remainingQty <= 0) {
      auditLog(
          KIND_FORCE_CLOSE_NOOP,
          subject(
              "operator_id", request.getOperatorId(),
              "reason", request.getReason(),
              "exit_signal_id", exitSignalId));
      result.setStatus(ForceCloseResult.Status.NOOP_ALREADY_CLOSED);
      return result;
    }
    // Emit ForceCloseRequested in-handler so the activity is scheduled in the same workflow task
    // as the Update — this matches the pre-#203 recorded command sequence and keeps in-flight v=0
    // workflows replay-safe (their histories already have this audit scheduled at handler time).
    // remaining_qty reflects the value at request time: it's 0 under the v=1 buffered path
    // (positionConfirmed=false) and the real remaining count under the confirmed path.
    auditLog(
        KIND_FORCE_CLOSE_REQUESTED,
        subject(
            "operator_id",
            request.getOperatorId(),
            "reason",
            request.getReason(),
            "exit_signal_id",
            exitSignalId,
            "remaining_qty",
            remainingQty));
    pendingForceCloses.add(
        new ForceCloseDirective(request.getOperatorId(), request.getReason(), exitSignalId));
    result.setStatus(ForceCloseResult.Status.ACCEPTED);
    return result;
  }

  /**
   * Main-loop risk-breach processor. Re-uses {@link #flattenRemaining(String)} so cancel-then-sell
   * semantics match EOD/expiry; emits a RiskBreachActed audit before the flatten so dashboards see
   * the cause-of-flatten before the EodForceFlatten* events.
   */
  private void processRiskBreach(RiskBreachPayload payload) {
    auditLog(
        KIND_RISK_BREACH_ACTED,
        subject(
            "reason", payload.getReason(),
            "actor", payload.getActor(),
            "remaining_qty", remainingQty));
    closeReason = "risk_breach";
    flattenRemaining("risk_breach");
  }

  /** Main-loop force-close processor. Cancel-then-flatten via the shared flatten helper. */
  private void processForceClose(ForceCloseDirective d) {
    closeReason = "force_close";
    flattenRemaining("force_close");
  }

  /** Main-loop tick processor: drops ticks while unarmed, ratchets the peak, latches on breach. */
  private void processTick(PremiumTick tick) {
    if (!trailingArmed) {
      return;
    }
    ticksReceived++;
    lastTickPremium = tick.getPremium();
    lastTickAt = tick.getRetrievedAt();

    if (tick.getPremium().compareTo(peakPremium) > 0) {
      peakPremium = tick.getPremium();
    }
    BigDecimal threshold = peakPremium.multiply(BigDecimal.ONE.subtract(givebackPct));
    if (tick.getPremium().compareTo(threshold) <= 0 && !chandelierFireRequested) {
      chandelierFireRequested = true;
      fireTriggerTick = tick;
      fireThreshold = threshold;
    }
  }

  /** Main-loop arm processor: validates, calls the subscribe activity, mutates state. */
  private void processArm(ArmChandelierPayload p) {
    if (trailingArmed) {
      // Idempotent — second arm is a silent no-op (no audit, KISS).
      return;
    }
    BigDecimal peak = p.getPeakPremium();
    BigDecimal gb = p.getGivebackPct();
    if (peak == null || peak.signum() <= 0) {
      auditLog(
          KIND_CHANDELIER_ARM_REJECTED,
          subject(
              "reason",
              "invalid_peak",
              "source_signal_id",
              p.getSourceSignalId(),
              "peak_premium",
              peak));
      return;
    }
    if (gb == null || gb.signum() <= 0 || gb.compareTo(MAX_GIVEBACK) > 0) {
      auditLog(
          KIND_CHANDELIER_ARM_REJECTED,
          subject(
              "reason",
              "invalid_giveback",
              "source_signal_id",
              p.getSourceSignalId(),
              "giveback_pct",
              gb));
      return;
    }

    SubscribePremiumRequest req = new SubscribePremiumRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(input.getTenantId());
    req.setStrategyId(input.getStrategyId());
    req.setContractSymbol(input.getContractSymbol());
    req.setPositionWorkflowId(Workflow.getInfo().getWorkflowId());

    SubscribePremiumResult res = marketData.subscribePremium(req);
    if (res.getStatus() == SubscribePremiumResult.Status.FAILED) {
      auditLog(
          KIND_CHANDELIER_SUBSCRIPTION_FAILED,
          subject(
              "source_signal_id", p.getSourceSignalId(),
              "error", res.getError()));
      return;
    }

    trailingArmed = true;
    peakPremium = peak;
    givebackPct = gb;
    auditLog(
        KIND_CHANDELIER_ARMED,
        subject(
            "source_signal_id",
            p.getSourceSignalId(),
            "peak_premium",
            peak,
            "giveback_pct",
            gb,
            "subscription_id",
            res.getSubscriptionId()));
  }

  /** Main-loop chandelier fire handler. Emits the audit then flattens the remaining quantity. */
  private void fireChandelier() {
    auditLog(
        KIND_CHANDELIER_TRAIL_FIRED,
        subject(
            "peak_premium", peakPremium,
            "trigger_premium", fireTriggerTick.getPremium(),
            "threshold", fireThreshold,
            "giveback_pct", givebackPct,
            "remaining_qty", remainingQty));
    closeReason = "chandelier_trail";
    flattenRemaining("chandelier_trail");
  }

  @Override
  public TrailingState trailingState() {
    BigDecimal threshold =
        (trailingArmed && peakPremium != null && givebackPct != null)
            ? peakPremium.multiply(BigDecimal.ONE.subtract(givebackPct))
            : null;
    return new TrailingState(
        trailingArmed,
        peakPremium,
        givebackPct,
        threshold,
        lastTickPremium,
        lastTickAt,
        ticksReceived);
  }

  private void processOne(PartialExitRequest req) {
    double fraction = req.getFraction().doubleValue();
    long qtyToClose;

    // Issue #205: runner-quantum gate. When remainingQty <= 1 and floor(remainingQty * fraction)
    // == 0, the integer broker quantum cannot honestly represent the requested partial. The
    // configured behavior decides whether to skip (default) or flush the runner via a full close.
    // v=DEFAULT_VERSION keeps the legacy ceil() path for replay safety; v>=1 honors the config.
    int minQtyVersion =
        Workflow.getVersion(VERSION_MIN_PARTIAL_QTY_SKIP, Workflow.DEFAULT_VERSION, 1);
    boolean atRunnerQuantum =
        minQtyVersion >= 1 && remainingQty <= 1 && (long) Math.floor(remainingQty * fraction) == 0;
    if (atRunnerQuantum) {
      // Null / absent treated as SKIP per the YAML-documented default in copytrade-v1.yaml.
      PositionWorkflowInput.MinPartialQtyBehavior behavior = input.getMinPartialQtyBehavior();
      boolean skip =
          behavior == null || behavior == PositionWorkflowInput.MinPartialQtyBehavior.SKIP;
      if (skip) {
        auditLog(
            KIND_PARTIAL_EXIT_SKIPPED_MIN_QTY,
            subject(
                "signal_id",
                req.getSignalId(),
                "remaining_qty",
                remainingQty,
                "fraction",
                req.getFraction()));
        // No order placed; clear the in-flight latch so pendingExits drains on the next iteration.
        // (exitInFlight was never set to true on this code path — defensive reset for symmetry
        // with the FULL_CLOSE / normal exit return paths.)
        exitInFlight = false;
        return;
      }
      // FULL_CLOSE: flush the runner on this partial signal.
      qtyToClose = remainingQty;
    } else {
      qtyToClose = Math.min(remainingQty, (long) Math.ceil(remainingQty * fraction));
    }

    auditLog(
        KIND_PARTIAL_EXIT_REQUESTED,
        subject(
            "signal_id",
            req.getSignalId(),
            "qty_to_close",
            qtyToClose,
            "remaining_qty_before",
            remainingQty,
            "fraction",
            req.getFraction()));

    exitInFlight = true;
    currentInFlightSignalId = req.getSignalId();

    // Issue #204: gate the await on a version flag so v=0 (in-flight) workflows keep their
    // original untimed semantics for replay safety; v>=1 (new executions) take the bounded await
    // that recovers from a non-filling broker order instead of wedging pendingExits forever.
    int exitTimeoutVersion =
        Workflow.getVersion(VERSION_EXIT_FILL_TIMEOUT, Workflow.DEFAULT_VERSION, 1);
    // Issue #216: gate retry-on-timeout. v=DEFAULT_VERSION preserves PR #214 single-cycle drop;
    // v>=1 retries the timed-out STC exactly once with a fresh limit price and intent_key.
    int retryVersion =
        Workflow.getVersion(VERSION_EXIT_RETRY_ON_TIMEOUT, Workflow.DEFAULT_VERSION, 1);
    int maxRetries = retryVersion >= 1 ? 1 : 0;
    // Issue #227: gate the fresh-limit source-order swap. v=DEFAULT_VERSION (in-flight workflows
    // that already entered the retry block under PR #226) keep the original lastTick → peak → ref
    // chain for replay safety; v>=1 (new executions) use the corrected lastTick → ref → peak chain.
    int sourceOrderVersion =
        Workflow.getVersion(VERSION_EXIT_RETRY_SOURCE_ORDER, Workflow.DEFAULT_VERSION, 1);
    int retryCount = 0;
    long exitFillTtlSecs = 0L;

    // Place-order + bounded-await cycle. Loop body executes once (original order) plus up to
    // maxRetries additional iterations under #216 v>=1 when the prior cycle timed out.
    while (true) {
      String intentKey;
      OrderIntent intent;
      if (retryCount == 0) {
        intentKey = Workflow.getInfo().getWorkflowId() + ":exit:" + req.getSignalId();
        intent = exitIntent(req, qtyToClose, intentKey, req.getRefPremium());
      } else {
        // Issue #216 retry: fresh intent_key (the prior one was cancelled) and fresh limit price.
        // Source preference under VERSION_EXIT_RETRY_SOURCE_ORDER v>=1 (#227):
        //   lastTickPremium (most recent chandelier mid) > req.getRefPremium() (author-posted price
        //   treated as a fresh quote) > peakPremium (chandelier high-water-mark; last-resort
        //   because it is biased high for SELL exits and so over-quotes the bid).
        // Under v=DEFAULT_VERSION (in-flight workflows that already executed the retry branch
        // under PR #226) the original lastTick → peak → ref chain is preserved for byte-identical
        // replay. Both branches are deterministic and need no activity call.
        intentKey = Workflow.getInfo().getWorkflowId() + ":exit:" + req.getSignalId() + ":retry";
        BigDecimal freshLimit;
        String source;
        if (lastTickPremium != null && lastTickPremium.signum() > 0) {
          freshLimit = lastTickPremium;
          source = "last_tick_premium";
        } else if (sourceOrderVersion >= 1) {
          // #227 v>=1: ref before peak.
          if (req.getRefPremium() != null && req.getRefPremium().signum() > 0) {
            freshLimit = req.getRefPremium();
            source = "ref_premium";
          } else {
            freshLimit = peakPremium;
            source = "peak_premium";
          }
        } else {
          // v=DEFAULT_VERSION: legacy peak before ref (PR #226 ordering).
          if (peakPremium != null && peakPremium.signum() > 0) {
            freshLimit = peakPremium;
            source = "peak_premium";
          } else {
            freshLimit = req.getRefPremium();
            source = "ref_premium";
          }
        }
        auditLog(
            KIND_PARTIAL_EXIT_RETRY_REQUESTED,
            subject(
                "signal_id",
                req.getSignalId(),
                "retry_attempt",
                retryCount,
                "fresh_limit_price",
                freshLimit,
                "source_premium",
                source,
                "intent_key",
                intentKey));
        intent = exitIntent(req, qtyToClose, intentKey, freshLimit);
      }

      lastFillEvent = null;
      currentInFlightIntentKey = intentKey;
      OrderIntentResult placed = exec.placeOrder(intent);
      currentInFlightBrokerOrderId = placed.getBrokerOrderId();

      boolean filledInTime;
      if (exitTimeoutVersion == Workflow.DEFAULT_VERSION) {
        Workflow.await(
            () ->
                lastFillEvent != null
                    || eodFired
                    || expiryFired
                    || !pendingRiskBreaches.isEmpty()
                    || !pendingForceCloses.isEmpty());
        filledInTime = true; // v=0 has no timeout; only an await wakeup gets us here.
      } else {
        // Issue #212: per-strategy TTL sourced from input under VERSION_TTL_FROM_INPUT v>=1;
        // falls back to EXIT_FILL_TTL_SECS_DEFAULT under v=DEFAULT_VERSION or null input field.
        exitFillTtlSecs = resolveExitFillTtlSecs();
        filledInTime =
            Workflow.await(
                Duration.ofSeconds(exitFillTtlSecs),
                () ->
                    lastFillEvent != null
                        || eodFired
                        || expiryFired
                        || !pendingRiskBreaches.isEmpty()
                        || !pendingForceCloses.isEmpty());
      }

      if (lastFillEvent != null) {
        long filled = lastFillEvent.getFilledQty();
        remainingQty -= filled;
        auditLog(
            KIND_PARTIAL_EXIT_FILLED,
            subject(
                "signal_id",
                req.getSignalId(),
                "qty_filled",
                filled,
                "remaining_qty_after",
                remainingQty,
                "broker_order_id",
                lastFillEvent.getBrokerOrderId(),
                "avg_fill_price",
                lastFillEvent.getAvgFillPrice()));
        exitInFlight = false;
        currentInFlightBrokerOrderId = null;
        currentInFlightSignalId = null;
        currentInFlightIntentKey = null;
        if (remainingQty == 0 && closeReason == null) {
          closeReason = "normal_stc";
        }
        return;
      }

      // Issue #204: v>=1 timeout path — no fill arrived within the resolved exit-fill TTL and no
      // EOD/expiry/risk_breach/force_close preemption. Best-effort cancel the broker order and
      // audit the timeout. Under #216 v>=1, if retry budget remains, re-loop with a fresh limit
      // price and intent_key. Otherwise (v=0 or retry exhausted), release the in-flight latch so
      // pendingExits can drain on the next iteration; do NOT decrement remainingQty (no fill
      // happened). On EOD/expiry pre-emption (filledInTime=true but lastFillEvent=null) we
      // leave exitInFlight/currentInFlightSignalId set so flattenRemaining() can cancel the
      // still-open broker order — same as the v=0 behavior.
      if (exitTimeoutVersion >= 1 && !filledInTime) {
        auditLog(
            KIND_PARTIAL_EXIT_FILL_TIMEOUT,
            subject(
                "signal_id",
                req.getSignalId(),
                "broker_order_id",
                currentInFlightBrokerOrderId,
                "intent_key",
                intentKey,
                "remaining_qty",
                remainingQty,
                "ttl_secs",
                exitFillTtlSecs));
        try {
          exec.cancelOrder(intentKey);
        } catch (RuntimeException ignored) {
          // Cancel is best-effort; the broker may have already filled or rejected the order.
          // Reconciliation closes the loop on the real broker-side state.
        }
        if (retryCount < maxRetries) {
          retryCount++;
          continue; // Issue #216: place the retry order with a fresh limit price + intent_key.
        }
        exitInFlight = false;
        currentInFlightBrokerOrderId = null;
        currentInFlightSignalId = null;
        currentInFlightIntentKey = null;
      }
      // On EOD/expiry/risk_breach/force_close pre-emption (filledInTime=true but lastFillEvent
      // still null) we leave exitInFlight/currentInFlightSignalId set so flattenRemaining() can
      // cancel the still-open broker order.
      return;
    }
  }

  private void flattenRemaining(String reason) {
    String kindReq;
    String kindDone;
    if ("eod".equals(reason)) {
      kindReq = KIND_EOD_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EOD_FORCE_FLATTENED;
    } else if ("expiry".equals(reason)) {
      kindReq = KIND_EXPIRY_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EXPIRY_FORCE_FLATTENED;
    } else {
      // chandelier_trail or other Phase 4+ reasons: re-use the EOD audit kinds so downstream
      // dashboards see a single force-flatten pattern (the audit subject carries `reason` for
      // disambiguation via the existing ChandelierTrailFired event).
      kindReq = KIND_EOD_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EOD_FORCE_FLATTENED;
    }

    auditLog(
        kindReq,
        subject(
            "entry_signal_id",
            input.getEntrySignalId(),
            "contract_symbol",
            input.getContractSymbol(),
            "remaining_qty",
            remainingQty,
            "reason",
            reason));

    if (exitInFlight && currentInFlightSignalId != null) {
      // Issue #216: read the live intent_key rather than reconstructing it — the in-flight order
      // may be a retry attempt whose key carries the ":retry" suffix. Fall back to reconstruction
      // for the (impossible-in-practice) case where the field is unset, to preserve pre-#216
      // behavior under a replay anomaly.
      String intentKey =
          currentInFlightIntentKey != null
              ? currentInFlightIntentKey
              : Workflow.getInfo().getWorkflowId() + ":exit:" + currentInFlightSignalId;
      try {
        exec.cancelOrder(intentKey);
      } catch (RuntimeException ignored) {
        // Cancellation best-effort; reconciliation closes the loop.
      }
    }

    if (remainingQty == 0) {
      return;
    }

    String flattenIntentKey = Workflow.getInfo().getWorkflowId() + ":exit:flatten-" + reason;
    OrderIntent intent = flattenIntent(flattenIntentKey, reason);
    try {
      exec.placeOrder(intent);
      long flattened = remainingQty;
      remainingQty = 0;
      auditLog(
          kindDone,
          subject(
              "entry_signal_id",
              input.getEntrySignalId(),
              "contract_symbol",
              input.getContractSymbol(),
              "qty_flattened",
              flattened,
              "reason",
              reason));
    } catch (RuntimeException e) {
      auditLog(
          KIND_EOD_FORCE_FLATTEN_FAILED,
          subject(
              "entry_signal_id", input.getEntrySignalId(),
              "contract_symbol", input.getContractSymbol(),
              "error", e.getMessage(),
              "reason", reason,
              "note", "orphan_until_phase_5_reconcile"));
    }
  }

  private OrderIntent exitIntent(
      PartialExitRequest req, long qty, String intentKey, BigDecimal limitPrice) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(input.getTenantId());
    i.setStrategyId(input.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId(req.getSignalId());
    i.setOptionSymbol(input.getContractSymbol());
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(qty);
    // Issue #266 (trading-critical): round the exit/STC limit to a penny tick before placement.
    // limitPrice flows in from req.getRefPremium()/freshLimit (the chandelier mid, author-posted
    // ref, or peak), any of which can be >2 dp; an unrounded SELL limit draws a non-retryable
    // Alpaca 422 = FAILED position close. The shared, deterministic OptionTick.round() keeps this
    // in lock-step with the entry path (BtoPricing) and is replay-safe inside workflow code.
    i.setLimitPrice(OptionTick.round(limitPrice));
    i.setRecordedAt(workflowNow());
    return i;
  }

  private OrderIntent flattenIntent(String intentKey, String reason) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(input.getTenantId());
    i.setStrategyId(input.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId("flatten-" + reason);
    i.setOptionSymbol(input.getContractSymbol());
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(remainingQty);
    i.setLimitPrice(null);
    i.setRecordedAt(workflowNow());
    return i;
  }

  private void auditLog(String kind, Map<String, Object> subject) {
    audit.log(auditEvent(kind, subject));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subject) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(input.getStrategyId());
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subject));
    e.setActor("workflow:PositionWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getEntrySignalId());
    return e;
  }

  /**
   * Issue #212: resolves the first-fill TTL used by run()'s entry-confirmation await. Under
   * VERSION_TTL_FROM_INPUT v>=1 consults input.first_fill_ttl_secs; null/absent or v=0 falls back
   * to FIRST_FILL_TTL_SECS_DEFAULT (90s). Called once at the top of run() so the resolved value is
   * stable for both the await call and the PositionNeverFilled audit subject.
   */
  private long resolveFirstFillTtlSecs(PositionWorkflowInput in) {
    int v = Workflow.getVersion(VERSION_TTL_FROM_INPUT, Workflow.DEFAULT_VERSION, 1);
    if (v < 1) {
      return FIRST_FILL_TTL_SECS_DEFAULT;
    }
    Long configured = in.getFirstFillTtlSecs();
    return configured != null ? configured : FIRST_FILL_TTL_SECS_DEFAULT;
  }

  /**
   * Issue #212: resolves the exit-fill TTL used by processOne()'s bounded await on the exit-order
   * fill. Same version-gate semantics as {@link #resolveFirstFillTtlSecs(PositionWorkflowInput)}.
   * Sourced from {@link #input} (assigned in run()) so processOne can call this without re-passing
   * the input.
   */
  private long resolveExitFillTtlSecs() {
    int v = Workflow.getVersion(VERSION_TTL_FROM_INPUT, Workflow.DEFAULT_VERSION, 1);
    if (v < 1) {
      return EXIT_FILL_TTL_SECS_DEFAULT;
    }
    Long configured = input.getExitFillTtlSecs();
    return configured != null ? configured : EXIT_FILL_TTL_SECS_DEFAULT;
  }

  private static Map<String, Object> subject(Object... kv) {
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException("subject() requires an even number of key/value args");
    }
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }

  /**
   * Parses the OCC option symbol's 6-digit YYMMDD (chars 6..12 after the 6-char root) into a
   * LocalDate. Returns null on any parse failure — the workflow then arms no expiry timer.
   */
  static LocalDate expiryDateFromOcc(String occ) {
    if (occ == null || occ.length() < 15) {
      return null;
    }
    try {
      String yymmdd = occ.substring(6, 12);
      int yy = Integer.parseInt(yymmdd.substring(0, 2));
      int mm = Integer.parseInt(yymmdd.substring(2, 4));
      int dd = Integer.parseInt(yymmdd.substring(4, 6));
      return LocalDate.of(2000 + yy, mm, dd);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
