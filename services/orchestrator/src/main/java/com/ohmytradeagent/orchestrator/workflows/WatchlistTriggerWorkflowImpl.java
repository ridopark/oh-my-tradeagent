package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.EquityTick;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.FireDecision;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribeEquityActivity;
import com.ohmytradeagent.orchestrator.activities.TriggerFireDecider;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine.Decision;
import com.ohmytradeagent.orchestrator.domain.ExpirySelector;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.domain.Sizing.SizingOutcome;
import com.ohmytradeagent.orchestrator.domain.TradingCalendar;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Child workflow: one watchlist-trigger leg, fires at most once.
 *
 * <p>Carries {@code Workflow.getVersion} gates ({@code watchlist-equity-resubscribe} for the
 * pre-open equity-subscribe deferral; {@code watchlist-arm-occ-resolve} for the display-only OCC
 * resolution at arm); otherwise net-new. Determinism: the body reads no wall clock and no RNG
 * except via {@code Workflow.*} helpers and Activity results.
 *
 * <p>Loop discipline mirrors {@code PositionWorkflowImpl}: the {@code equityTick}/{@code cancel}
 * signal handlers only enqueue/flag; one main loop {@code Workflow.await}s the predicate union and
 * drains the buffered ticks in a fixed order, feeding non-stale ticks to the pure {@link
 * EntryStateMachine}. History bounding mirrors {@code KillSwitchWorkflowImpl}: continue-as-new at a
 * history watermark, carrying {@code {state, prev, et_date, fired}} — never mid-fire.
 */
public class WatchlistTriggerWorkflowImpl implements WatchlistTriggerWorkflow {

  static final String MARKET_DATA_TASK_QUEUE = "market-data";

  // Backstop history bound (mirrors KillSwitchWorkflowImpl.historyLengthWatermark).
  // Package-private,
  // non-final so tests can lower it to exercise the continue-as-new path.
  static long historyLengthWatermark = 10_000L;

  private static final String KIND_TRIGGER_ARMED = "TriggerArmed";
  private static final String KIND_TRIGGER_SKIPPED = "TriggerSkipped";
  private static final String KIND_TRIGGER_CANCELLED = "TriggerCancelled";
  private static final String KIND_TRIGGER_FIRE_REJECTED = "TriggerFireRejected";
  private static final String KIND_ORDER_SUBMITTED = "OrderSubmitted";
  private static final String KIND_ENTRY_FILLED = "EntryFilled";
  private static final String KIND_ENTRY_UNFILLED = "TriggerEntryUnfilled";
  private static final String KIND_FEED_STALE = "TriggerFeedStale";
  private static final String KIND_TRIGGER_SUBSCRIPTION_UNAVAILABLE =
      "TriggerSubscriptionUnavailable";
  private static final String KIND_TRIGGER_SUBSCRIPTION_DEFERRED = "TriggerSubscriptionDeferred";

  // getVersion change id for the pre-open equity-subscribe deferral. In-flight/old legs replay on
  // DEFAULT_VERSION (no new timer command); new legs get the deferred re-subscribe.
  private static final String VERSION_EQUITY_RESUBSCRIBE = "watchlist-equity-resubscribe";
  // getVersion change id for resolving the leg's OCC at arm (display-only, for entryProximity).
  private static final String VERSION_ARM_OCC_RESOLVE = "watchlist-arm-occ-resolve";
  // Issue #165 port to the watchlist path: gate the new EntryFilled-log + child-start commands the
  // cancel-on-filled adoption emits. Pre-fix histories (DEFAULT_VERSION) replay the legacy
  // TriggerEntryUnfilled path deterministically; new legs (v>=1) inspect the cancel result and
  // adopt the lot inline when the broker filled inside the TTL/cancel race. Capturing the
  // cancelOrder result does NOT add a command (cancelOrder is already on this path); only the
  // adoption branch's audit + child start are new and thus gated.
  private static final String VERSION_TTL_FILLED_ADOPTION = "watchlist-ttl-filled-adoption-v1";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private static final ActivityOptions MARKET_DATA_OPTIONS =
      ActivityOptions.newBuilder()
          .setTaskQueue(MARKET_DATA_TASK_QUEUE)
          .setStartToCloseTimeout(Duration.ofSeconds(10))
          .build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final RiskActivities risk =
      Workflow.newActivityStub(RiskActivities.class, DEFAULT_OPTIONS);
  private final ContractActivities contract =
      Workflow.newActivityStub(ContractActivities.class, DEFAULT_OPTIONS);
  private final TriggerFireDecider fireDecider =
      Workflow.newActivityStub(TriggerFireDecider.class, DEFAULT_OPTIONS);
  private final SubscribeEquityActivity subscribeEquity =
      Workflow.newActivityStub(SubscribeEquityActivity.class, MARKET_DATA_OPTIONS);
  private final GetOptionQuoteActivity optionQuote =
      Workflow.newActivityStub(GetOptionQuoteActivity.class, MARKET_DATA_OPTIONS);
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(PositionLookupActivities.class, DEFAULT_OPTIONS);

  // Lazily built from config.broker_target (mirrors CopytradeSignalWorkflowImpl.exec): the order
  // path and the Alpaca trading-calendar lookup both route to broker-<broker_target>.
  private ExecActivities exec;
  private MarketCalendarActivity tradingCalendar;

  // Signal-buffered state: handlers only enqueue/flag.
  private final ArrayDeque<EquityTick> pendingTicks = new ArrayDeque<>();
  private boolean cancelRequested;
  private FillSignalPayload fillEvent;

  // Hoisted from run() so entryProximity() can read live entry state. Rebuilt + re-seeded from
  // carried state on every run (incl. each continue-as-new), so the query stays correct after a
  // resume. Null only in the window before run() assigns them; the query is null-safe.
  private EntryStateMachine machine;
  private WatchlistTriggerPayload activePayload;
  // OCC the leg would buy, resolved at arm (display-only, for entryProximity's indicative option
  // premium). Null until resolved / on a resolution failure. Re-derived on each run from the same
  // deterministic inputs, so it stays correct across continue-as-new.
  private String resolvedOcc;

  @Override
  public void equityTick(EquityTick tick) {
    pendingTicks.add(tick);
  }

  @Override
  public void cancel() {
    cancelRequested = true;
  }

  @Override
  public void onFill(FillSignalPayload event) {
    this.fillEvent = event;
  }

  @Override
  public EntryProximityView entryProximity() {
    // Null window: query raced run() before it assigned the machine/payload. Same guard pattern as
    // PositionWorkflowImpl.positionState().
    if (machine == null || activePayload == null) {
      return new EntryProximityView("", "", null, null, null, null, "INITIALIZING", null);
    }
    return new EntryProximityView(
        activePayload.getTicker(),
        machine.direction().name(),
        machine.level(),
        machine.bandLow(),
        machine.bandHigh(),
        machine.prev(),
        machine.state().name(),
        resolvedOcc);
  }

  @Override
  public String run(WatchlistTriggerWorkflowInput input) {
    WatchlistTriggerPayload payload = input.getPayload();
    this.activePayload = payload;
    StrategyConfig config = input.getConfig();
    this.exec = ExecActivitiesFactory.forTarget(config.getBrokerTarget().value());
    this.tradingCalendar =
        Workflow.newActivityStub(
            MarketCalendarActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(config.getBrokerTarget().value()))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .build());

    machine =
        new EntryStateMachine(
            entryMode(config),
            EntryStateMachine.directionOf(payload.getDirection()),
            payload.getTrigger(),
            gapTolerance(config));
    machine.seed(input.getCarriedState(), input.getCarriedPrev());

    // Resolve the OCC the leg would buy (the same resolution fire() uses) so entryProximity can
    // show
    // an indicative option premium for the un-fired leg. Display-only and best-effort: a resolution
    // failure leaves resolvedOcc null (the dashboard shows "-"); it never blocks arming or changes
    // the fire path (which re-resolves independently). Runs every run (incl. continue-as-new) from
    // the same deterministic inputs. getVersion-gated so in-flight/old legs replay unchanged.
    if (Workflow.getVersion(VERSION_ARM_OCC_RESOLVE, Workflow.DEFAULT_VERSION, 1) >= 1) {
      try {
        LocalDate expiry = resolveExpiry(payload, config);
        ContractResolveResult resolved =
            contract.resolve(
                new ContractResolveInput(
                    payload.getTenantId(),
                    payload.getTicker(),
                    expiry,
                    payload.getStrike(),
                    payload.getRight().value()));
        resolvedOcc = resolved.optionSymbol();
      } catch (RuntimeException e) {
        // Best-effort display resolution: leave resolvedOcc null on any failure.
        resolvedOcc = null;
      }
    }

    // Only audit + (re)subscribe on the first run, not on each continue-as-new resumption. On a
    // resumed run the existing subscription (keyed to the SAME workflow id) keeps signalling ticks
    // into the new run, so re-subscribing here would stack a duplicate listener per resume.
    boolean firstRun = input.getEtDate() == null;
    if (firstRun) {
      logAudit(
          payload,
          KIND_TRIGGER_ARMED,
          subject(
              "ticker", payload.getTicker(),
              "direction", payload.getDirection().value(),
              "trigger", payload.getTrigger(),
              "entry_mode", entryMode(config).value(),
              "size_multiplier", input.getSizeMultiplier()));

      // Start the streaming equity subscription (async); it signals equityTick back into this id.
      // The activity does NOT throw on GATED/FAILED — it returns the disposition — so inspect the
      // Promise synchronously (it resolves fast) and emit a LOUD audit on any non-SUBSCRIBED
      // status.
      // GATED is the default ship posture (stock WS URL unset): no ticks ever arrive, so without
      // this audit the leg would silently await until EOD. We still continue to the await/EOD path
      // (the fail-safe is the EOD cancel), but the dead-feed condition is now observable.
      SubscribeEquityResult subResult = subscribeAsync(payload, config).get();
      if (subResult == null || subResult.getStatus() != SubscribeEquityResult.Status.SUBSCRIBED) {
        boolean gated =
            subResult != null && subResult.getStatus() == SubscribeEquityResult.Status.GATED;
        int resubVersion =
            Workflow.getVersion(VERSION_EQUITY_RESUBSCRIBE, Workflow.DEFAULT_VERSION, 1);
        Duration untilOpen = gated ? calendar.durationUntilRthOpenEt() : null;
        if (resubVersion >= 1
            && gated
            && untilOpen != null
            && !untilOpen.isZero()
            && !untilOpen.isNegative()) {
          // Armed PRE-OPEN: SubscribeEquityActivity gates equity subscriptions to RTH, so the leg
          // would otherwise sit feedless (and unfireable) until EOD. Defer and re-attach the feed
          // at
          // the open in a side coroutine (same pattern as the EOD timer below); the main loop keeps
          // awaiting ticks, which start flowing once the retry subscribes at 09:30 ET.
          logAudit(
              payload,
              KIND_TRIGGER_SUBSCRIPTION_DEFERRED,
              subject("ticker", payload.getTicker(), "defer_secs", untilOpen.toSeconds()));
          final Duration deferUntilOpen = untilOpen;
          Async.procedure(
              () -> {
                Workflow.newTimer(deferUntilOpen).get();
                SubscribeEquityResult retry = subscribeAsync(payload, config).get();
                if (retry == null || retry.getStatus() != SubscribeEquityResult.Status.SUBSCRIBED) {
                  logAudit(
                      payload,
                      KIND_TRIGGER_SUBSCRIPTION_UNAVAILABLE,
                      subject(
                          "ticker",
                          payload.getTicker(),
                          "status",
                          retry == null ? "null" : retry.getStatus().value(),
                          "detail",
                          retry == null ? "" : retry.getError(),
                          "phase",
                          "rth_open_retry"));
                }
              });
        } else {
          logAudit(
              payload,
              KIND_TRIGGER_SUBSCRIPTION_UNAVAILABLE,
              subject(
                  "ticker",
                  payload.getTicker(),
                  "status",
                  subResult == null ? "null" : subResult.getStatus().value(),
                  "detail",
                  subResult == null ? "" : subResult.getError()));
        }
      }
    }

    // EOD timer: a Workflow.newTimer over the calendar-supplied duration (no wall-clock read).
    // Fail-safe on a null/zero/negative duration (mirrors WatchlistTriggerSessionWorkflowImpl):
    // treat as "EOD now" so the leg cancels un-fired instead of NPEing on eodIn.isZero().
    final boolean[] eodFired = {false};
    Duration eodIn = calendar.durationUntilEodEt();
    if (eodIn == null || eodIn.isZero() || eodIn.isNegative()) {
      eodFired[0] = true;
    } else {
      // Side-effecting timer callback in its own coroutine: await the timer, then set the flag.
      // Avoids thenApply, whose value-returning lambda completes the derived promise with any
      // thrown
      // exception INSTEAD of running the side-effect, silently swallowing the eodFired flag.
      final Duration eod = eodIn;
      Async.procedure(
          () -> {
            Workflow.newTimer(eod).get();
            eodFired[0] = true;
          });
    }

    while (true) {
      Workflow.await(() -> !pendingTicks.isEmpty() || cancelRequested || eodFired[0]);

      // Fixed drain order: cancel and EOD are terminal; otherwise feed buffered ticks. Note the EOD
      // branch below requires pendingTicks to be empty, so any ticks buffered before the EOD timer
      // fired are drained through the state machine first (a last in-band cross can still FIRE).
      if (cancelRequested) {
        logAudit(payload, KIND_TRIGGER_CANCELLED, subject("ticker", payload.getTicker()));
        return outcome(payload, "cancelled");
      }
      if (eodFired[0] && pendingTicks.isEmpty()) {
        logAudit(
            payload,
            KIND_TRIGGER_CANCELLED,
            subject("ticker", payload.getTicker(), "reason", "eod"));
        return outcome(payload, "eod_cancelled");
      }

      Decision decision = Decision.NONE;
      while (!pendingTicks.isEmpty() && decision == Decision.NONE) {
        EquityTick tick = pendingTicks.poll();
        if (Boolean.TRUE.equals(tick.getStale())) {
          // Never transition on a stale/halted print; a loud audit (no silent dead feed).
          logAudit(
              payload,
              KIND_FEED_STALE,
              subject("ticker", payload.getTicker(), "last", tick.getLast()));
          continue;
        }
        decision = machine.onTick(tick.getLast());
      }

      if (decision == Decision.FIRE) {
        // Fire exactly once, then complete. Never continue-as-new mid-fire.
        return fire(payload, config, input.getSizeMultiplier());
      }
      if (decision == Decision.SKIP) {
        logAudit(
            payload,
            KIND_TRIGGER_SKIPPED,
            subject("ticker", payload.getTicker(), "state", machine.state().name()));
        return outcome(payload, "skipped");
      }

      // Backstop history bound (mirror KillSwitchWorkflowImpl). Safe here: we have NOT fired (the
      // FIRE branch returns above before reaching this point), so continue-as-new is never
      // mid-fire.
      if (Workflow.getInfo().getHistoryLength() > historyLengthWatermark) {
        Workflow.continueAsNew(carryForward(input, machine));
        // continueAsNew throws DestroyWorkflowThreadError — unreachable below.
      }
    }
  }

  private WatchlistTriggerWorkflowInput carryForward(
      WatchlistTriggerWorkflowInput input, EntryStateMachine machine) {
    WatchlistTriggerWorkflowInput carry =
        new WatchlistTriggerWorkflowInput(
            input.getPayload(), input.getConfig(), input.getSizeMultiplier());
    carry.setCarriedState(machine.state());
    carry.setCarriedPrev(machine.prev());
    carry.setEtDate(input.getPayload().getEtDate());
    carry.setFired(false);
    return carry;
  }

  private Promise<SubscribeEquityResult> subscribeAsync(
      WatchlistTriggerPayload payload, StrategyConfig config) {
    SubscribeEquityRequest req = new SubscribeEquityRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(payload.getTenantId());
    req.setStrategyId(payload.getStrategyId());
    req.setTicker(payload.getTicker());
    req.setTargetWorkflowId(Workflow.getInfo().getWorkflowId());
    req.setSignalName("equityTick");
    req.setTriggerLevel(payload.getTrigger());
    req.setEquityEmitDeltaPct(emitDeltaPct(config));
    return Async.function(subscribeEquity::subscribeEquity, req);
  }

  /**
   * The FIRE path. Idempotency key {@code workflowId + ":entry"} guards against duplicate broker
   * orders on retry/replay; exactly one {@code placeOrder} per child. Every fail-closed branch
   * audits and completes with NO order.
   */
  private String fire(WatchlistTriggerPayload payload, StrategyConfig config, BigDecimal armMult) {
    // 1. Fire decider.
    // cash is null here: the account snapshot is fetched in step 2 below, after the fire decider
    // runs, so the ArmContext cannot carry a cash figure at this point.
    ArmContext armCtx = new ArmContext().withEtDate(payload.getEtDate()).withCash(null);
    FireDecision fd = fireDecider.evaluateTriggerFire(payload, armCtx);
    if (!Boolean.TRUE.equals(fd.getProceed())) {
      logAudit(
          payload,
          KIND_TRIGGER_FIRE_REJECTED,
          subject(
              "ticker", payload.getTicker(), "reason", "fire_decider", "detail", fd.getReason()));
      return outcome(payload, "fire_rejected");
    }
    BigDecimal fireMult = fd.getSizeMultiplier();

    // 2. Account cash (fail-closed on null/zero).
    BigDecimal cash = dispatchAccountSnapshot(payload, config);
    if (cash == null || cash.signum() <= 0) {
      logAudit(
          payload,
          KIND_TRIGGER_FIRE_REJECTED,
          subject("ticker", payload.getTicker(), "reason", "capital_unavailable"));
      return outcome(payload, "capital_unavailable");
    }

    // 3. Resolve expiry + OCC (needed for the option quote in step 4).
    LocalDate expiry = resolveExpiry(payload, config);
    ContractResolveResult resolved =
        contract.resolve(
            new ContractResolveInput(
                payload.getTenantId(),
                payload.getTicker(),
                expiry,
                payload.getStrike(),
                payload.getRight().value()));

    // 4. Option quote -> premium (fail-closed if no quote).
    BigDecimal premium = fetchPremium(payload, resolved.optionSymbol());
    if (premium == null || premium.signum() <= 0) {
      logAudit(
          payload,
          KIND_TRIGGER_FIRE_REJECTED,
          subject("ticker", payload.getTicker(), "reason", "no_option_quote"));
      return outcome(payload, "no_option_quote");
    }

    // 5. Strategy-agnostic risk gates (premium is the BTO max-cost limit). The risk gate runs here,
    // after the quote, because checkWatchlistEntry's notional cap needs the fetched premium: the
    // notional-cap sub-gate (RiskActivitiesImpl.checkNotionalCap) sizes the entry notional from the
    // fetched premium, so the gate is premium-dependent and cannot run before the quote exists.
    RiskDecision riskDecision = risk.checkWatchlistEntry(payload, config, null, premium, cash);
    if (!riskDecision.allowed()) {
      logAudit(
          payload,
          KIND_TRIGGER_FIRE_REJECTED,
          subject(
              "ticker",
              payload.getTicker(),
              "reason",
              "risk",
              "reason_code",
              riskDecision.reason() == null ? "" : riskDecision.reason().name(),
              "detail",
              riskDecision.detail()));
      return outcome(payload, "risk_rejected");
    }

    // 6. Size with deciders (skip below-min / decider-zero => no order, NOT size 0).
    SizingOutcome sizing =
        Sizing.computeContractsWithDeciders(config, cash, premium, armMult, fireMult);
    if (sizing.skip()) {
      logAudit(
          payload,
          KIND_TRIGGER_FIRE_REJECTED,
          subject(
              "ticker", payload.getTicker(), "reason", "sizing_skip", "detail", sizing.reason()));
      return outcome(payload, "sizing_skip");
    }
    long contracts = sizing.contracts();

    // 7. Place order (exactly once) -> await fill -> start PositionWorkflow.
    String intentKey = Workflow.getInfo().getWorkflowId() + ":entry";
    OrderIntent intent = newIntent(payload, config, resolved, contracts, intentKey, premium);
    OrderIntentResult placed = exec.placeOrder(intent);
    logAudit(
        payload,
        KIND_ORDER_SUBMITTED,
        subject(
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "option_symbol", resolved.optionSymbol(),
            "side", "BUY",
            "qty", contracts,
            "limit_price", premium,
            "broker_target", config.getBrokerTarget().value()));

    long ttlSecs = pendingTtlSecs(config);
    boolean filled = Workflow.await(Duration.ofSeconds(ttlSecs), () -> fillEvent != null);
    if (!filled) {
      // No fill within the entry TTL. Best-effort cancel the resting order. Issue #165 port: when
      // the broker filled inside the TTL/cancel race, exec.cancelOrder reconciles the journal to
      // FILLED and returns the broker-confirmed fill detail — adopt the lot inline instead of
      // orphaning it to recon. Capture the result (capturing it adds no command vs the prior
      // result-discarded call). On a RuntimeException fall through fail-closed to the legacy
      // TriggerEntryUnfilled path (broker down -> recon settles the orphan).
      OrderIntentResult cancelResult = null;
      try {
        cancelResult = exec.cancelOrder(intentKey);
      } catch (RuntimeException ignored) {
        // Best-effort: reconciliation closes any orphan broker order.
      }

      int adoptionVersion =
          Workflow.getVersion(VERSION_TTL_FILLED_ADOPTION, Workflow.DEFAULT_VERSION, 1);
      if (adoptionVersion >= 1
          && cancelResult != null
          && cancelResult.getState() == OrderIntentResult.State.FILLED) {
        String adopted =
            handleTtlFilledAdoption(payload, config, resolved, cancelResult, contracts, premium);
        if (adopted != null) {
          return adopted;
        }
      }

      logAudit(
          payload,
          KIND_ENTRY_UNFILLED,
          subject(
              "intent_key", placed.getIntentKey(),
              "broker_order_id", placed.getBrokerOrderId(),
              "option_symbol", resolved.optionSymbol(),
              "ttl_secs", ttlSecs,
              "outcome", "UNFILLED"));
      return outcome(payload, "entry_unfilled");
    }

    FillSignalPayload fill = fillEvent;
    logAudit(
        payload,
        KIND_ENTRY_FILLED,
        subject(
            "intent_key",
            placed.getIntentKey(),
            "option_symbol",
            resolved.optionSymbol(),
            "broker_order_id",
            fill.getBrokerOrderId(),
            "filled_qty",
            fill.getFilledQty(),
            "avg_fill_price",
            fill.getAvgFillPrice(),
            "outcome",
            "FILLED"));

    startPositionWorkflow(payload, config, resolved, fill, contracts, premium);
    return outcome(payload, "fired");
  }

  /**
   * Issue #165 port: recover from the cancel-on-filled race on the watchlist path. The broker
   * filled inside the TTL/cancel window; {@code exec.cancelOrder} reconciled the journal to FILLED
   * and returned the broker-confirmed fill. Synthesise a {@link FillSignalPayload}, emit {@code
   * EntryFilled(recovery=cancel_on_filled)}, and spawn the missing PositionWorkflow so the lot is
   * managed inline rather than waiting on the 5-min reconciliation auto-adopt.
   *
   * <p>Returns the {@code "fired"} outcome on adoption, or {@code null} to fall through to the
   * legacy {@code TriggerEntryUnfilled} path when adoption is declined: a FILLED-with-zero-qty
   * result, an active kill-switch/halt (strategy OR account scope), or a precheck that the owner
   * PositionWorkflow is already running (recon won the race).
   */
  private String handleTtlFilledAdoption(
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      OrderIntentResult cancelResult,
      long placedQty,
      BigDecimal premium) {
    long filledQty = cancelResult.getFilledQty() != null ? cancelResult.getFilledQty() : 0L;
    if (filledQty <= 0L) {
      // FILLED state but no broker-confirmed quantity: no real lot to adopt.
      return null;
    }

    // KILL-SWITCH/HALT GUARD (load-bearing safety): re-check kill-switch/halt for this
    // tenant/strategy AND the ACCOUNT scope (the auto:account_daily_loss incident tripped an
    // account-scoped switch, which the per-strategy read alone never reflects). Kill-switch-ONLY:
    // NOT checkWatchlistEntry, whose max_positions/notional gates would wrongly refuse adoption of
    // a
    // lot that already exists at the broker. A halt -> decline inline adoption; recon's
    // AdoptionWorkflow re-confirms broker truth and settles the lot safely.
    RiskDecision halt = risk.checkKillSwitchHalt(payload.getTenantId(), payload.getStrategyId());
    if (halt != null && !halt.allowed()) {
      return null;
    }

    // Owner-running precheck: if the PositionWorkflow for this OCC is already running (recon
    // adopted
    // it first), do not start a second child. Log a no-op and fall through to the unfilled path.
    String posWfId =
        WorkflowIds.position(
            payload.getTenantId(),
            payload.getStrategyId(),
            resolved.optionSymbol(),
            payload.getSourceMessageId());
    if (positionLookup.isPositionWorkflowRunning(posWfId)) {
      logAudit(
          payload,
          KIND_ENTRY_FILLED,
          subject(
              "option_symbol",
              resolved.optionSymbol(),
              "broker_order_id",
              cancelResult.getBrokerOrderId(),
              "recovery",
              "cancel_on_filled",
              "note",
              "owner_already_running"));
      return null;
    }

    BigDecimal avgFillPrice =
        cancelResult.getAvgFillPrice() != null ? cancelResult.getAvgFillPrice() : premium;
    FillSignalPayload synth =
        new FillSignalPayload()
            .withBrokerOrderId(cancelResult.getBrokerOrderId())
            .withFilledQty(filledQty)
            .withAvgFillPrice(avgFillPrice)
            .withFilledAt(workflowNow());

    logAudit(
        payload,
        KIND_ENTRY_FILLED,
        subject(
            "option_symbol", resolved.optionSymbol(),
            "broker_order_id", synth.getBrokerOrderId(),
            "filled_qty", synth.getFilledQty(),
            "avg_fill_price", synth.getAvgFillPrice(),
            "outcome", "FILLED",
            "recovery", "cancel_on_filled"));

    startPositionWorkflow(payload, config, resolved, synth, placedQty, premium, true);
    return outcome(payload, "fired");
  }

  private void startPositionWorkflow(
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      FillSignalPayload fill,
      long placedQty,
      BigDecimal premium) {
    startPositionWorkflow(payload, config, resolved, fill, placedQty, premium, false);
  }

  private void startPositionWorkflow(
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      FillSignalPayload fill,
      long placedQty,
      BigDecimal premium,
      boolean tolerateDuplicate) {
    String tenant = payload.getTenantId();
    String strategyId = payload.getStrategyId();
    String entrySignalId = payload.getSourceMessageId();
    String posWfId =
        WorkflowIds.position(tenant, strategyId, resolved.optionSymbol(), entrySignalId);

    Map<String, Object> sa = new HashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenant, strategyId));
    sa.put("ContractSymbol", resolved.optionSymbol());

    ChildWorkflowOptions opts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId(posWfId)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .setSearchAttributes(sa)
            .build();
    PositionWorkflow child = Workflow.newChildWorkflowStub(PositionWorkflow.class, opts);

    PositionWorkflowInput posInput = new PositionWorkflowInput();
    posInput.setSchemaVersion(1L);
    posInput.setTenantId(tenant);
    posInput.setStrategyId(strategyId);
    posInput.setEntrySignalId(entrySignalId);
    posInput.setContractSymbol(resolved.optionSymbol());
    posInput.setQty(fill == null ? placedQty : fill.getFilledQty());
    posInput.setEntryPremium(
        fill != null && fill.getAvgFillPrice() != null ? fill.getAvgFillPrice() : premium);
    posInput.setSourceSignalWorkflowId(Workflow.getInfo().getWorkflowId());
    posInput.setBrokerTarget(
        PositionWorkflowInput.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    posInput.setEodForceFlatten(config.getEodForceFlatten());
    // Phase 5: forward the same exit/flatten tunables Copytrade/Adoption carry so a watchlist
    // position gets exit parity. Floor/lead/reprice fields plumb the bounded-flatten machinery;
    // null passes through verbatim (PositionWorkflowImpl applies its in-code defaults).
    posInput.setForceClose0dteEt(config.getForceClose0dteEt());
    posInput.setExitFloorAbs(config.getExitFloorAbs());
    posInput.setExitFloorPct(config.getExitFloorPct());
    posInput.setExpiryDayFloor(config.getExpiryDayFloor());
    posInput.setFlattenLeadMinutes(config.getFlattenLeadMinutes());
    posInput.setExitRepriceSteps(config.getExitRepriceSteps());
    posInput.setExitRepriceTick(config.getExitRepriceTick());
    // Phase 3 premium TP/SL/trail exit: enabled in PositionWorkflowImpl only when tp_ratio != null.
    // Without these the watchlist position never arms the bid-based
    // stop/target/chandelier/time-stop.
    posInput.setTpRatio(config.getTpRatio());
    posInput.setSlPct(config.getSlPct());
    posInput.setTpPartialFraction(config.getTpPartialFraction());
    posInput.setTrailGivebackPct(config.getTrailGivebackPct());
    posInput.setNoProgressTimeStopSecs(config.getNoProgressTimeStopSecs());
    posInput.setForceCloseEodEt(config.getForceCloseEodEt());

    Async.function(child::run, posInput);
    if (!tolerateDuplicate) {
      // Happy-path fire: byte-identical to the original command stream (block on the start, then
      // forward the fill).
      Workflow.getWorkflowExecution(child).get();
      if (fill != null) {
        child.onFill(fill);
      }
      return;
    }
    // Cancel-on-filled adoption: the owner-running precheck already guards the common race, but a
    // sub-second TOCTOU vs recon's AdoptionWorkflow can still lose to a child already started under
    // this id. Degrade that to a logged no-op (deterministic Promise.handle, NOT a forbidden
    // client-side catch) instead of faulting this workflow. On a clean start, block until the child
    // is durably scheduled, then forward the synthesised fill.
    final boolean[] startFailed = {false};
    Workflow.getWorkflowExecution(child)
        .handle(
            (exec, err) -> {
              if (err != null) {
                startFailed[0] = true;
              }
              return null;
            })
        .get();
    if (startFailed[0]) {
      logAudit(
          payload,
          KIND_ENTRY_FILLED,
          subject(
              "option_symbol",
              resolved.optionSymbol(),
              "broker_order_id",
              fill == null ? null : fill.getBrokerOrderId(),
              "recovery",
              "cancel_on_filled",
              "note",
              "child_already_started"));
      return;
    }
    if (fill != null) {
      child.onFill(fill);
    }
  }

  private LocalDate resolveExpiry(WatchlistTriggerPayload payload, StrategyConfig config) {
    LocalDate reference = payload.getEtDate();
    // Window the calendar lookup [reference, reference + 14d] to cover this/next weekly Friday plus
    // a holiday back-walk; cache it as one Activity call per workflow for determinism.
    List<LocalDate> days = tradingCalendar.tradingDays(reference, reference.plusDays(14));
    Set<LocalDate> tradingDays = new HashSet<>(days);
    TradingCalendar oracle = tradingDays::contains;
    // afterClose=false: the trigger date is the session day; EOD cancel handles after-close legs.
    return ExpirySelector.resolveNearestWeekly(reference, false, oracle);
  }

  private BigDecimal fetchPremium(WatchlistTriggerPayload payload, String occSymbol) {
    GetOptionQuoteRequest req =
        new GetOptionQuoteRequest()
            .withTenantId(payload.getTenantId())
            .withStrategyId(payload.getStrategyId())
            .withContractSymbol(occSymbol);
    OptionQuoteResult quote = optionQuote.getOptionQuote(req);
    if (quote == null || quote.getStatus() != OptionQuoteResult.Status.OK) {
      return null;
    }
    if (quote.getMid() != null && quote.getMid().signum() > 0) {
      return quote.getMid();
    }
    return quote.getAsk();
  }

  /**
   * Dispatches the broker account snapshot for cash sizing on the broker-&lt;target&gt; queue.
   * Fail-closed: a null broker_target, null result, or null cash yields {@code BigDecimal.ZERO}, so
   * the FIRE path rejects rather than sizing against unknown capital.
   */
  private BigDecimal dispatchAccountSnapshot(
      WatchlistTriggerPayload payload, StrategyConfig config) {
    if (config.getBrokerTarget() == null) {
      return BigDecimal.ZERO;
    }
    com.ohmytradeagent.contract.activities.AccountSnapshotActivity accountStub =
        Workflow.newActivityStub(
            com.ohmytradeagent.contract.activities.AccountSnapshotActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(config.getBrokerTarget().value()))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    com.ohmytradeagent.contract.AccountSnapshotRequest request =
        new com.ohmytradeagent.contract.AccountSnapshotRequest();
    request.setSchemaVersion(1L);
    request.setBrokerTarget(
        com.ohmytradeagent.contract.AccountSnapshotRequest.BrokerTarget.fromValue(
            config.getBrokerTarget().value()));
    request.setTenantId(payload.getTenantId());
    request.setCorrelationId(payload.getSourceMessageId());
    com.ohmytradeagent.contract.AccountSnapshotResult result = accountStub.accountSnapshot(request);
    return result == null || result.getCash() == null ? BigDecimal.ZERO : result.getCash();
  }

  private OrderIntent newIntent(
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      long contracts,
      String intentKey,
      BigDecimal limitPrice) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(payload.getTenantId());
    i.setStrategyId(payload.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId(payload.getSourceMessageId());
    i.setBrokerTarget(OrderIntent.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    i.setBrokerAccountId(config.getBrokerAccountId());
    i.setOptionSymbol(resolved.optionSymbol());
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(contracts);
    i.setLimitPrice(limitPrice);
    i.setRecordedAt(workflowNow());
    return i;
  }

  private static StrategyConfig.EntryMode entryMode(StrategyConfig config) {
    return config.getEntryMode() != null
        ? config.getEntryMode()
        : StrategyConfig.EntryMode.BREAKOUT;
  }

  private static BigDecimal gapTolerance(StrategyConfig config) {
    return config.getGapTolerancePct() != null
        ? config.getGapTolerancePct()
        : new BigDecimal("0.005");
  }

  private static BigDecimal emitDeltaPct(StrategyConfig config) {
    return config.getEquityEmitDeltaPct() != null
        ? config.getEquityEmitDeltaPct()
        : new BigDecimal("0.0005");
  }

  private long pendingTtlSecs(StrategyConfig config) {
    Long configured = config.getPendingTtlPaperSecs();
    return configured != null ? configured : 90L;
  }

  private String outcome(WatchlistTriggerPayload payload, String status) {
    return payload.getSourceMessageId() + ":" + status;
  }

  private void logAudit(WatchlistTriggerPayload payload, String kind, Map<String, Object> subject) {
    audit.log(auditEvent(payload, kind, subject));
  }

  private AuditEvent auditEvent(
      WatchlistTriggerPayload payload, String kind, Map<String, ?> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(payload.getTenantId());
    event.setStrategyId(payload.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:WatchlistTriggerWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(payload.getSourceMessageId());
    return event;
  }

  private static Map<String, Object> subject(Object... kv) {
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
}
