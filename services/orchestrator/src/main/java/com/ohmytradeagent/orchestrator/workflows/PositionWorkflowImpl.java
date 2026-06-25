package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
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
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
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
import java.time.LocalTime;
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
  // Phase 4 (PLAN-2026-06-24-trading-remediation): a force-flatten that rested UNFILLED is
  // re-attempted at the NEXT market-session open. KIND_FLATTEN_RETRY_SCHEDULED is the
  // informational per-attempt marker (does NOT page); KIND_FLATTEN_RETRY_EXHAUSTED is the terminal
  // page emitted once the bounded session-retry budget is spent and the lot is still unfilled.
  private static final String KIND_FLATTEN_RETRY_SCHEDULED = "FlattenRetryScheduled";
  private static final String KIND_FLATTEN_RETRY_EXHAUSTED = "FlattenRetryExhausted";
  private static final String KIND_EXPIRY_FORCE_FLATTEN_REQUESTED = "ExpiryForceFlattenRequested";
  private static final String KIND_EXPIRY_FORCE_FLATTENED = "ExpiryForceFlattened";

  // Plan-2B R-AB-1 audit kinds: a guaranteed bounded flatten timer armed at (expiry_close -
  // flatten_lead_minutes) ET for EVERY lot (multi-day included) fired with reason=expiry_lead.
  // Dedicated kinds (not the Eod* fallthrough) so the lead-flatten lifecycle event is labeled
  // correctly. ExpiryLeadFlattenRequested mirrors the *ForceFlattenRequested cause markers;
  // ExpiryLeadForceFlattened is the broker-confirmed terminal marker (P&L rides the accompanying
  // PartialExitFilled emitted by emitExitFill).
  private static final String KIND_EXPIRY_LEAD_FLATTEN_REQUESTED = "ExpiryLeadFlattenRequested";
  private static final String KIND_EXPIRY_LEAD_FORCE_FLATTENED = "ExpiryLeadForceFlattened";

  private static final String KIND_POSITION_CLOSED = "PositionClosed";

  // Issue #434: terminal marker for a position whose option physically expired with no closing fill
  // (worthless expiry — a no-bid contract has no buyer, so the scheduled expiry flatten's SELL
  // never fills). The lot is closed as worthless (remainingQty -> 0) rather than lingering "open"
  // forever waiting for a fill that can never come. Subject carries reason=worthless_expiry.
  private static final String KIND_POSITION_EXPIRED = "PositionExpired";

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

  // Exit-retry late-fill reconcile audit kind (VERSION_EXIT_RETRY_LATE_FILL_RECONCILE v>=1):
  // emitted by processOne()'s v=1 timeout branch when, AFTER the best-effort cancel of a timed-out
  // exit order, a LATE fill of the original order is reconciled and already satisfies the exit
  // intent (computed retry qty <= 0). The workflow places NO retry order and releases the in-flight
  // latch. Observability-only — NOT a lifecycle/fill event (the real fill is the PartialExitFilled
  // audit emitted alongside it); registered in AuditEventKinds.ALL_KINDS only (NOT in
  // PARTIAL_EXIT_FILL_KINDS) so it does not inflate the realized-P&L ledger.
  private static final String KIND_PARTIAL_EXIT_RETRY_SKIPPED_SATISFIED =
      "PartialExitRetrySkippedSatisfied";

  // B2 (PLAN-exit-place-duplicate-422-crash) audit kind: an exit-order placeOrder activity FAILED
  // (e.g. a duplicate-client_order_id 422 misclassified by the broker adapter as a non-retryable
  // InvalidRequestError). Pre-this-patch the uncaught failure propagated out of processOne() and
  // FAILED the whole PositionWorkflow with no audit, orphaning the still-live lot (the QQQ-725
  // incident). Under VERSION_EXIT_PLACE_FAILURE_GUARD v>=1 the catch emits this kind (carrying
  // intent_key, option_symbol, qty, signal_id, and the error message), releases the in-flight
  // latch,
  // and returns WITHOUT decrementing remainingQty — the position stays managed and alive. NOT a
  // lifecycle/fill event (nothing was sold; the lot survives for a later STC / EOD flatten /
  // re-drive); registered in AuditEventKinds.ALL_KINDS only. Paged by OrderFailureAlerter (B3).
  private static final String KIND_PARTIAL_EXIT_PLACE_FAILED = "PartialExitPlaceFailed";

  // PLAN-over-exit-422 audit kind: an exit/flatten placeOrder Activity returned a BENIGN
  // already-closed outcome — the SELL/STC drew Alpaca's "position intent mismatch, inferred:
  // sell_to_open" 422 AND the broker's /v2/positions CONFIRMED the OCC was already flat (nothing to
  // sell). The exec Activity terminalized the journal (RECORDED -> CANCELLED) and surfaced
  // state=CANCELLED; the workflow zeroes remainingQty from broker truth, releases the in-flight
  // latch, and returns — NO PartialExitPlaceFailed page, NO crash, NO orphan. Carries
  // remaining_qty_before so a divergence (contracts zeroed without a booked PartialExitFilled) is
  // visible: when remaining_qty_before>0 the workflow ALSO logs a WARN and increments a metric. NOT
  // a lifecycle/fill event (P&L-neutral — the lot was closed by an already-booked sibling exit);
  // registered in AuditEventKinds.ALL_KINDS only and NOT in OrderFailureAlerter's failure kinds, so
  // it does not page. Distinct from PartialExitPlaceFailed (a genuine failure that DOES page).
  private static final String KIND_PARTIAL_EXIT_ALREADY_FLAT = "PartialExitAlreadyFlat";

  // PLAN-over-exit-422 metric: counts benign broker-confirmed over-exits whose remaining_qty_before
  // was >0 (contracts zeroed without a booked fill — a divergence worth alerting on, distinct from
  // the expected remaining_qty_before==0 trim-after-flat case).
  private static final String METRIC_OVER_EXIT_FLAT_DIVERGENCE =
      "position_workflow.over_exit_already_flat.qty_divergence";

  // Plan-2A R-AA-3 audit kind: the bounded scheduled-flatten (eod/expiry/chandelier_trail) computed
  // an exit_floor that is UNUSABLE for a bounded limit — exit_floor_abs/exit_floor_pct were
  // null/absent/unresolvable, or the resolved floor sits ABOVE the live bid (a floor that high
  // would
  // forbid selling at any executable price). The flatten FAILS SAFE by falling back to a marketable
  // exit (never "no sell") and emits this loud kind so a misconfigured floor is visible.
  // Observability-only — registered in AuditEventKinds.ALL_KINDS only (NOT a lifecycle/fill kind).
  private static final String KIND_FLATTEN_FLOOR_CONFIG_ERROR = "FlattenFloorConfigError";

  // Plan-2A R-AA-3 audit kind: GetOptionQuoteActivity returned status=FAILED or UNAVAILABLE on a
  // scheduled/expiry flatten path, so the bounded flatten has no live-bid anchor. The flatten FAILS
  // SAFE by falling back to a marketable exit (NOT a stale ref-premium limit) and emits this loud
  // kind so a market-data outage during a force-close is visible. Observability-only — registered
  // in
  // AuditEventKinds.ALL_KINDS only (NOT a lifecycle/fill kind).
  private static final String KIND_FLATTEN_QUOTE_UNAVAILABLE = "FlattenQuoteUnavailable";

  // Phase 3 watchlist-trigger EXIT audit kinds. The bid-based STOP and TIME-STOP reuse the EOD
  // force-flatten kinds in flattenRemaining (subject.reason disambiguates: stop_loss / time_stop) —
  // mirroring the chandelier_trail convention — so only the TARGET-fire and feed-staleness/bid-
  // degradation lifecycle markers get dedicated kinds here.
  private static final String KIND_WATCHLIST_EXIT_ARMED = "WatchlistExitArmed";
  private static final String KIND_WATCHLIST_EXIT_TARGET_FIRED = "WatchlistExitTargetFired";
  private static final String KIND_WATCHLIST_EXIT_FEED_STALE = "WatchlistExitFeedStale";
  private static final String KIND_WATCHLIST_EXIT_BID_DEGRADED = "WatchlistExitBidDegraded";

  // Phase 7 measurement audit kind. Emitted on EACH exit leg of a watchlist-trigger position (the
  // target partial AND the terminal close) so the realized 2:1 payoff ratio is computable from the
  // audit log. Inert when tp_ratio == null (copytrade emits nothing new). Carries entry/exit
  // premium, realized_R, premium_mfe/premium_mae, exit_rule, partial_fraction, hold_minutes, and
  // dte_at_exit. Observability-only — registered in AuditEventKinds.ALL_KINDS only.
  private static final String KIND_WATCHLIST_EXIT_MEASURED = "WatchlistExitMeasured";

  private static final String VERSION_CHANDELIER = "chandelier-v1";
  private static final String VERSION_RISK_BREACH = "risk-breach-v1";
  private static final String VERSION_FORCE_CLOSE = "force-close-v1";

  /**
   * Phase 3 replay gate for the watchlist-trigger options EXIT on the long option. Every new
   * Workflow command the exit introduces — the at-first-fill premium subscribe, the
   * no_progress_time_stop timer, the feed-staleness timer, and the swap of the blanket-EOD timer
   * from the legacy no-arg {@code durationUntilEodEt()} to the configured {@code
   * durationUntilEodCloseEt(LocalTime)} — is gated behind this marker so a watchlist-trigger
   * position spawned BEFORE this change replays deterministically (it records none of the new
   * commands; the only new command on v=0 is this getVersion marker). ORTHOGONAL to {@code
   * tp_ratio}: the entire exit path is additionally inert at runtime whenever {@code
   * input.getTpRatio() == null} (the copytrade-byte-identical invariant), so a copytrade position —
   * which never sets tp_ratio — takes none of the new branches even under v&gt;=1.
   */
  private static final String VERSION_WATCHLIST_EXIT = "watchlist-exit-v1";

  /**
   * Phase 3 bid-based STOP debounce: require N consecutive ticks whose evaluated bid is at/below
   * the stop level before flattening, so a single outlier bid print (bad NBBO, halted side) does
   * not fire the stop. Reset on any tick at/above the stop level. Mirrors the {@code
   * trail_debounce_ticks} default (2) documented in {@code contract/schemas/strategy-config.json};
   * that StrategyConfig key is not yet plumbed onto PositionWorkflowInput, so the default is
   * applied as a constant here rather than inventing a new input field.
   */
  private static final int EXIT_STOP_DEBOUNCE_TICKS = 2;

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
   * Issue #276: gate the {@code option_symbol} field added to the {@link #KIND_PARTIAL_EXIT_FILLED}
   * audit subject. The DailyPnl realized-P&amp;L consumer groups FIFO by {@code option_symbol} so
   * each exited contract realizes against its OWN symbol's entry basis; emitting the key here
   * (value {@code input.getContractSymbol()}) supplies the exit-side half of the correlation key
   * that pairs with the EntryFilled key emitted by {@code CopytradeSignalWorkflowImpl}.
   * Replay-gated so pre-change PositionWorkflow histories reproduce the legacy subject exactly (no
   * {@code option_symbol}) and stay deterministic; only new executions (v&gt;=1) emit it. Mirrors
   * the existing version-gate constants.
   */
  private static final String VERSION_EXIT_FILLED_OPTION_SYMBOL = "exit-filled-option-symbol-v1";

  /**
   * Issue #202 hardening replay gate. The blanket 15:55 ET EOD flatten is a per-strategy opt-IN: a
   * copytrade author-mirror must ride to its STC and must never be force-flattened. Pre-this-patch
   * the arming treated a {@code null} {@code eod_force_flatten} as {@code true} ("fail open"), so
   * when the tenants ConfigMap drifted and dropped the key the flatten silently re-armed and closed
   * a non-0DTE copytrade position. v&gt;=1 is fail-CLOSED: arm only when the flag is explicitly
   * {@code true}; null/false do not arm. v=DEFAULT_VERSION (in-flight workflows started before this
   * patch) keep the legacy null-as-true semantics so their EOD-timer command replays identically.
   * The expiry-close timer is unaffected (0DTE physical expiry is not a tunable), so "EOD close
   * only when the contract expires today" still holds regardless of this flag.
   */
  private static final String VERSION_EOD_FLATTEN_OPT_IN = "eod-flatten-opt-in";

  /**
   * Exit-retry late-fill reconcile gate. The #216 retry loop cancels a timed-out exit limit order
   * and retries with the SAME qtyToClose. But the original order can fill LATE — its onFill signal
   * buffers and is delivered when the timeout-branch {@code exec.cancelOrder} activity completes.
   * Pre-this-patch the retry-iteration top reset {@code lastFillEvent = null}, DISCARDING that
   * buffered fill, so {@code remainingQty} stayed stale and the retry re-sent the full qty → naked
   * short → Alpaca 403 "uncovered". v&gt;=1 reconciles after the cancel: it applies any late fill
   * exactly once, recomputes the retry qty from {@code remainingQty - targetRemaining} (inherently
   * ≤ remainingQty — the anti-naked-short guarantee), and SKIPs the retry (emitting {@link
   * #KIND_PARTIAL_EXIT_RETRY_SKIPPED_SATISFIED}) when the late fill already satisfied the intent.
   * v=DEFAULT_VERSION (in-flight workflows started before this patch) keep the original
   * discard-and-retry-with-qtyToClose behavior; the only new command on v=0 is the appended
   * getVersion marker (resolving to DEFAULT_VERSION for legacy histories).
   */
  private static final String VERSION_EXIT_RETRY_LATE_FILL_RECONCILE =
      "exit-retry-late-fill-reconcile";

  /**
   * B2 (PLAN-exit-place-duplicate-422-crash) replay gate. Pre-this-patch the exit {@code
   * exec.placeOrder(intent)} inside {@link #processOne(PartialExitRequest)} was UNCAUGHT: a
   * non-retryable {@code ApplicationFailure} (e.g. the duplicate-client_order_id 422 misclassified
   * as {@code InvalidRequestError}) propagated out of processOne and FAILED the whole
   * PositionWorkflow with no audit, orphaning a live lot (the QQQ-725 incident). v&gt;=1 (new
   * executions) wrap the placeOrder call in try/catch: on a {@code RuntimeException} (covers
   * Temporal {@code ActivityFailure}/{@code ApplicationFailure}) the catch emits {@link
   * #KIND_PARTIAL_EXIT_PLACE_FAILED}, releases the in-flight latch via {@link
   * #releaseExitInFlightLatches()}, and {@code return;}s out of processOne — it does NOT fall
   * through to {@code placed.getBrokerOrderId()} (a never-assigned reference) and does NOT enter
   * the fill-await (a never-placed order never fills → wedge). {@code remainingQty} is unchanged
   * (nothing was sold) so the lot stays managed and the workflow stays alive for a later STC / EOD
   * flatten. v=DEFAULT_VERSION (in-flight workflows started before this patch) keep the original
   * uncaught call so their recorded histories replay byte-identically — the try/catch wrapper and
   * the new audit command exist ONLY under v&gt;=1. Byte-identity is by construction: on v=0 the
   * only new command is the appended {@code getVersion} marker (resolving to DEFAULT_VERSION for
   * legacy histories). Distinct from the prior session keys so it rolls independently.
   */
  private static final String VERSION_EXIT_PLACE_FAILURE_GUARD = "exit-place-failure-guard";

  /**
   * Plan-2A R-AA-1 replay gate (the core silent-loss fix). Pre-this-patch {@link
   * #flattenRemaining(String)} set {@code remainingQty=0} on {@code placeOrder} SUCCESS (no
   * fill-await) and {@code run()} then emitted {@link #KIND_POSITION_CLOSED} unconditionally —
   * benign only while the flatten was a MARKET order, but R-AA-3 turns it into a bounded LIMIT that
   * can rest UNFILLED, re-opening the QQQ-725 silent-loss class. v&gt;=1 (new executions): after
   * {@code placeOrder} the flatten {@code Workflow.await}s on {@code lastFillEvent} up to a TTL and
   * zeroes {@code remainingQty} ONLY from the actual fill; the run()-tail epilogue becomes a
   * GUARDED loop that emits {@code PositionClosed} ONLY when {@code remainingQty==0}
   * (broker-confirmed) and stays ALIVE (re-arm / block) on a TTL timeout. v=DEFAULT_VERSION
   * (in-flight workflows started before this patch) keep the legacy zero-at-placement +
   * unconditional-close path so their recorded histories replay byte-identically — the only new
   * command on v=0 is the appended {@code getVersion} marker (resolving to DEFAULT_VERSION for
   * legacy histories). Redefined invariant: {@code KIND_POSITION_CLOSED ⟹ broker-confirmed
   * remaining == 0}.
   */
  private static final String VERSION_FLATTEN_FILL_AWAIT = "flatten-fill-await";

  /**
   * Plan-2A R-AA-3 replay gate. Routes the scheduled flatten by CLASSIFICATION: any reason ∉
   * {{@code risk_breach}, {@code force_close}} is BOUNDED (a marketable LIMIT anchored on the live
   * bid from {@link GetOptionQuoteActivity}, bounded by {@code exit_floor_abs}/{@code
   * exit_floor_pct}, with the expiry-session {@code expiry_day_floor} collapse); {@code
   * risk_breach}/{@code force_close} keep exit-NOW (MARKET, {@code limitPrice=null}). Only
   * consulted inside the v&gt;=1 branch of {@link #VERSION_FLATTEN_FILL_AWAIT}, so legacy replays
   * never record this marker; fresh executions resolve it to v&gt;=1. Anchor chain: live bid → mid
   * → {@code lastTickPremium} → {@code peakPremium} → ref → marketable.
   */
  private static final String VERSION_FLATTEN_BOUNDED_LIMIT = "flatten-bounded-limit";

  /**
   * Plan-2B R-AB-1 replay gate. Arms a GUARANTEED bounded flatten timer at {@code (expiry_close -
   * flatten_lead_minutes)} ET for EVERY lot — multi-day included — so a position with no STC is
   * sold via the bounded reason-scoped flatten (reason={@code expiry_lead}) before expiry rather
   * than ridden to $0 (the QQQ-725 ride-to-expiry class). Independent of {@code eod_force_flatten}
   * (which only governs the blanket 15:55 ET sweep) and of the 0DTE-only {@code
   * durationUntilExpiryCloseEt} timer. The timer-arm command (a {@code
   * durationUntilExpiryFlattenEt} Activity call + a {@code Workflow.newTimer}) is recorded ONLY
   * under v&gt;=1, so legacy histories — which never recorded it — replay through the
   * v=DEFAULT_VERSION branch byte-identically (the only new command on v=0 is this appended
   * getVersion marker, resolving to DEFAULT_VERSION). The fire is routed through 2A's existing
   * bounded flatten with reason={@code expiry_lead}, which 2A's classification router already
   * treats as bounded (it is ∉ {{@code risk_breach}, {@code force_close}}) — no edit to 2A's
   * switch. Long-lived multi-day workflow: in-flight executions replay across a redeploy, so the
   * gate is mandatory.
   */
  private static final String VERSION_EXPIRY_LEAD_FLATTEN = "expiry-lead-flatten";

  /**
   * Plan-2B R-AB-2 replay gate (a second gate layered over 2A's single-shot exit machinery).
   * Redesigns {@link #processOne(PartialExitRequest)}'s exit retry from a single attempt (the #216
   * {@code maxRetries=1} path) into a BOUNDED STEPPED reprice: up to {@code exit_reprice_steps}
   * re-places, each anchored on a fresh {@link GetOptionQuoteActivity} bid/mid and bounded by
   * {@code exit_floor} (the same fail-safe as 2A's flatten), walking toward the market by {@code
   * exit_reprice_tick} per step. Each step RE-RUNS the existing late-fill reconcile BEFORE
   * re-placing so {@code remainingQty} is recomputed and the #357 naked-short guard holds across
   * ALL N steps; {@code targetRemaining} is captured ONCE. Per-step intent keys use a distinct
   * {@code :reprice-N} suffix (deterministic loop counter, separate from the original {@code
   * :exit:} and the #216 {@code :retry} keys) so no two steps reuse a {@code client_order_id}.
   * v=DEFAULT_VERSION (in-flight workflows started before this patch) keep the #216 single-shot
   * retry path so their recorded histories replay byte-identically — the only new command on v=0 is
   * this appended getVersion marker. The reprice deadline terminates at or before the R-AB-1
   * flatten-lead trigger so the bounded flatten is unambiguously the final owner (no overlapping
   * double-place).
   */
  private static final String VERSION_EXIT_STEPPED_REPRICE = "exit-stepped-reprice";

  /**
   * Issue #434 replay gate. When the PHYSICAL-expiry flatten (reason={@code expiry} only — NOT
   * {@code expiry_lead}, which fires BEFORE expiry and must keep its real expiry-close attempt)
   * completes its fill-await with NO fill (the bounded limit / marketable sell rested unfilled,
   * {@code remainingQty} still &gt; 0) AND the contract has physically expired (its OCC expiry date
   * &lt;= the workflow's current ET date, derived deterministically from {@link
   * Workflow#currentTimeMillis()}), close the lot as WORTHLESS: zero {@code remainingQty}, emit the
   * terminal {@link #KIND_POSITION_EXPIRED}, and let {@code run()} complete normally. A worthless
   * option has no buyer, so the prior behavior — block ALIVE forever on a late fill that can never
   * come — lingered the workflow "open" past physical expiry, where recon re-adopts it and the
   * dashboard counts it (the TSLA 260618P incident). v=DEFAULT_VERSION (in-flight workflows started
   * before this patch) keep the existing lingering behavior so their recorded histories replay
   * byte-identically — the only new command on v=0 is this appended getVersion marker (resolving to
   * DEFAULT_VERSION). Long-lived multi-day workflow: in-flight executions replay across a redeploy,
   * so the gate is mandatory.
   */
  private static final String VERSION_EXPIRE_WORTHLESS = "expire-worthless-v1";

  /**
   * Phase 4 (PLAN-2026-06-24-trading-remediation) replay gate. When a force-flatten
   * (stop_loss/time_stop/eod/expiry) rests UNFILLED — typically because the orders were submitted
   * at/after the 16:00 close — the legacy behaviour is to emit {@link
   * #KIND_EOD_FORCE_FLATTEN_FAILED} and block ALIVE indefinitely awaiting a late fill, with NO
   * alert and NO retry (the 2026-06-24 overnight-hold incident). Under v&gt;=1 the run()-tail
   * alive-block arms a one-shot timer to the NEXT market-session open ({@link
   * MarketCalendarActivities#durationUntilNextRthOpenEt()}); on wake it re-attempts the bounded
   * flatten, bounded to {@link #MAX_FLATTEN_RETRY_SESSIONS}. The alert itself is config-only (the
   * {@link #KIND_EOD_FORCE_FLATTEN_FAILED} audit already pages via OrderFailureAlerter).
   *
   * <p>v=DEFAULT_VERSION (in-flight workflows started before this patch) keep the legacy
   * await-late-fill behaviour so their recorded histories replay byte-identically — every new
   * command (the next-open Activity call, its timer, the extra await condition, and the
   * FlattenRetry* audits) is strictly behind this gate. The gate is read ONCE per run()-tail entry,
   * outside the retry loop, so the command count is stable across replays. This is a long-lived
   * multi-day workflow: in-flight executions replay across a redeploy, so the gate is mandatory.
   */
  private static final String VERSION_FLATTEN_RETRY_NEXT_SESSION = "flatten-retry-next-session-v1";

  /**
   * Phase 4: maximum number of NEXT-SESSION retry attempts for an unfilled force-flatten before the
   * workflow gives up retrying, emits the terminal {@link #KIND_FLATTEN_RETRY_EXHAUSTED} page, and
   * falls back to the legacy await-late-fill (stay-alive) behaviour. Small constant: a flatten that
   * cannot fill across three consecutive sessions needs operator attention, not unbounded
   * re-arming.
   */
  private static final int MAX_FLATTEN_RETRY_SESSIONS = 3;

  /** Plan-2B R-AB-1 default: minutes before expiry close to arm the guaranteed flatten timer. */
  private static final long FLATTEN_LEAD_MINUTES_DEFAULT = 30L;

  /** Plan-2B R-AB-2 default: number of bounded stepped exit repricings. */
  private static final long EXIT_REPRICE_STEPS_DEFAULT = 3L;

  /** Plan-2B R-AB-2 default: per-step price concession the exit reprice walks toward the market. */
  private static final BigDecimal EXIT_REPRICE_TICK_DEFAULT = new BigDecimal("0.05");

  // Plan-2A R-AA-6 (realized-P&L for flatten fills) needs no separate gate: the flatten fill is
  // routed through the shared emitExitFill -> PartialExitFilled only inside the v>=1 branch of
  // VERSION_FLATTEN_FILL_AWAIT, so legacy replays never reach it.

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

  /**
   * Issue #288: the resolved broker target (same value used to route {@link #exec} via {@link
   * ExecActivitiesFactory#forTarget}), threaded onto exit/flatten {@link OrderIntent}s so {@code
   * ExecActivitiesImpl.validateIntent} passes. Adopted positions never place an entry, so their
   * first {@code exec.placeOrder} is the exit — without this the exit intent would carry {@code
   * brokerTarget=null} and the workflow would re-orphan. Set once in {@link
   * #run(PositionWorkflowInput)} alongside {@link #exec}.
   */
  private String brokerTarget;

  private final SubscribePremiumActivity marketData =
      Workflow.newActivityStub(
          SubscribePremiumActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .build());

  /**
   * Plan-2A R-AA-2: one-shot live-bid quote anchor for the bounded scheduled flatten (R-AA-3).
   * Routed to the {@code market-data} task queue (same as {@link #marketData}). Short
   * start-to-close + the SDK default bounded retry so a market-data hiccup can't wedge a
   * force-close — the activity returns {@code status=FAILED/UNAVAILABLE} (never throws) so the
   * flatten falls back to a marketable exit + a loud {@link #KIND_FLATTEN_QUOTE_UNAVAILABLE} audit.
   */
  private final GetOptionQuoteActivity optionQuote =
      Workflow.newActivityStub(
          GetOptionQuoteActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(5))
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

  /**
   * Plan-2B R-AB-1: latched true when the guaranteed expiry-lead flatten timer fires (armed under
   * {@link #VERSION_EXPIRY_LEAD_FLATTEN} v&gt;=1 for EVERY lot, multi-day included). Drives the
   * main-loop break + the run()-tail flatten with reason {@code expiry_lead}.
   */
  private boolean expiryLeadFired;

  /**
   * Plan-2A R-AA-1: set true when an in-loop flatten (risk_breach/force_close/chandelier) placed a
   * bounded limit that rested UNFILLED within its TTL. The main loop then stays alive and applies a
   * LATE fill of that resting order when it arrives, instead of hanging the close open. Reset once
   * the lot drains.
   */
  private boolean flattenAwaitingLateFill;

  /**
   * Phase 4 (PLAN-2026-06-24-trading-remediation): latched true by the one-shot next-session timer
   * armed in the run()-tail alive-block under {@link #VERSION_FLATTEN_RETRY_NEXT_SESSION} v&gt;=1.
   * On wake the loop re-attempts the bounded flatten and resets the latch. {@code
   * flattenRetrySessions} counts the attempts so the loop terminates at {@link
   * #MAX_FLATTEN_RETRY_SESSIONS}.
   */
  private boolean retryFlattenArmed;

  private int flattenRetrySessions;

  // Phase 3: watchlist-trigger EXIT state (premium-space, bid-evaluated on the long option). All of
  // this stays at its zero/false defaults — and none of the timers/subscription below is armed —
  // unless the exit is enabled (input.getTpRatio() != null) under VERSION_WATCHLIST_EXIT v>=1.
  private boolean exitArmed;
  private BigDecimal exitStopLevel; // entry_premium*(1 - sl_pct), moves to breakeven after target
  private BigDecimal exitTargetLevel; // entry_premium*(1 + tp_ratio*sl_pct)
  private BigDecimal exitTpPartialFraction;
  private BigDecimal exitTrailGiveback;
  private boolean exitTargetFired; // target partial+arm is single-shot
  private int exitSubThresholdStreak; // consecutive sub-stop ticks for the debounce
  private boolean exitBidDegradedAudited; // one-shot audit when bid is null and we fall back to mid
  private boolean exitStopFireRequested; // latched by processExitTick; main loop flattens stop_loss
  private boolean exitTimeStopFired; // no_progress_time_stop timer fired
  private boolean exitFeedStaleFired; // staleness backstop timer fired with no tick since arm
  private boolean exitTickSeen; // any exit tick observed since arm (staleness check)

  // Phase 7 measurement state. The entry basis (actual first-fill price) and the BID
  // max-favorable / max-adverse excursion over the position life, all in premium $. MFE/MAE are
  // fed from the same exit-tick stream processExitTick already evaluates (no extra subscription).
  // exitFirstFillAt anchors hold_minutes. All inert unless the exit is enabled (tp_ratio != null).
  private BigDecimal exitEntryBasis;
  private BigDecimal exitBidMfe;
  private BigDecimal exitBidMae;
  private OffsetDateTime exitFirstFillAt;
  // Most recent evaluated exit price (live bid, or mid fallback when the bid is null). Unlike
  // lastTickPremium (only updated once the trail arms) this tracks every exit tick, so the
  // exitProximity() query has a live price to compare against stop/target before the trail arms.
  private BigDecimal lastBid;

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
    this.brokerTarget =
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
    // position. v>=1 (VERSION_EOD_FLATTEN_OPT_IN) is fail-CLOSED: the blanket EOD timer
    // arms only on an EXPLICIT eod_force_flatten=true, so a missing/null flag (e.g. the
    // tenants ConfigMap dropping the key) can no longer silently re-arm the flatten. The
    // legacy null-as-true branch is retained only for v=DEFAULT_VERSION in-flight replays.
    // The expiry-close timer below still arms unconditionally (0DTE physical expiry is not
    // a tunable), so 0DTE positions are still closed at expiry regardless of this flag.
    boolean armEodTimer =
        Workflow.getVersion(VERSION_EOD_FLATTEN_OPT_IN, Workflow.DEFAULT_VERSION, 1)
                == Workflow.DEFAULT_VERSION
            ? !Boolean.FALSE.equals(in.getEodForceFlatten())
            : Boolean.TRUE.equals(in.getEodForceFlatten());

    // Phase 3: the watchlist-trigger exit can drive the blanket EOD flatten at a configured ET time
    // (e.g. 15:30) via the distinct durationUntilEodCloseEt(LocalTime) activity, replacing the
    // hardcoded 15:55 path. Gated under VERSION_WATCHLIST_EXIT so the swapped activity command is
    // recorded only for fresh executions; legacy histories keep the no-arg durationUntilEodEt().
    // Null force_close_eod_et keeps the legacy 15:55 path even under v>=1.
    int watchlistExitVersion =
        Workflow.getVersion(VERSION_WATCHLIST_EXIT, Workflow.DEFAULT_VERSION, 1);
    String eodCfg = in.getForceCloseEodEt();
    Duration eodIn;
    if (watchlistExitVersion >= 1 && eodCfg != null && !eodCfg.isBlank()) {
      eodIn = calendar.durationUntilEodCloseEt(LocalTime.parse(eodCfg));
    } else {
      eodIn = calendar.durationUntilEodEt();
    }
    Duration expiryIn = Duration.ZERO;
    LocalDate expiryDate = expiryDateFromOcc(in.getContractSymbol());
    if (expiryDate != null) {
      // Issue #15: drive the 0DTE expiry-close timer from the per-strategy force_close_0dte_et
      // override (carried over from StrategyConfig). LocalTime.parse is deterministic/replay-safe;
      // null/blank falls back to the activity's legacy 15:30 ET default so pre-change in-flight
      // replays (whose input lacks this field) keep firing at 15:30.
      String fc = in.getForceClose0dteEt();
      LocalTime closeTime = (fc == null || fc.isBlank()) ? null : LocalTime.parse(fc);
      expiryIn = calendar.durationUntilExpiryCloseEt(expiryDate, closeTime);
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

    // Plan-2B R-AB-1: arm a GUARANTEED bounded flatten timer at (expiry_close -
    // flatten_lead_minutes)
    // ET for EVERY lot (multi-day included), independent of eod_force_flatten and of the 0DTE-only
    // expiry-close timer above. On fire -> 2A's bounded reason-scoped flatten with
    // reason=expiry_lead.
    // Version-gated and appended AFTER the legacy timer-arm block so v=0 histories (which never
    // recorded the durationUntilExpiryFlattenEt Activity call or its timer) replay
    // byte-identically;
    // the only new command on v=0 is this getVersion marker (resolving to DEFAULT_VERSION).
    int expiryLeadVersion =
        Workflow.getVersion(VERSION_EXPIRY_LEAD_FLATTEN, Workflow.DEFAULT_VERSION, 1);
    if (expiryLeadVersion >= 1 && expiryDate != null) {
      String fc = in.getForceClose0dteEt();
      LocalTime closeTime = (fc == null || fc.isBlank()) ? null : LocalTime.parse(fc);
      long leadMinutes = resolveFlattenLeadMinutes(in);
      Duration flattenLeadIn =
          calendar.durationUntilExpiryFlattenEt(expiryDate, leadMinutes, closeTime);
      if (!flattenLeadIn.isZero() && !flattenLeadIn.isNegative()) {
        Promise<Void> flattenLeadTimer = Workflow.newTimer(flattenLeadIn);
        flattenLeadTimer.thenApply(
            v -> {
              expiryLeadFired = true;
              return null;
            });
      }
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

      // Phase 3: arm the watchlist-trigger exit on the long option. Inert unless the exit is
      // enabled
      // (tp_ratio != null) under VERSION_WATCHLIST_EXIT v>=1 — a copytrade position never sets
      // tp_ratio, so this opens no premium subscription and arms no timers for it. The R basis is
      // the actual first-fill price (firstFillPrice above), so the stop/target levels track the lot
      // we really hold.
      if (watchlistExitVersion >= 1 && in.getTpRatio() != null) {
        armWatchlistExit(firstFillPrice);
      }
    }

    while (remainingQty > 0 && !eodFired && !expiryFired && !expiryLeadFired) {
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
                  // Plan-2B R-AB-1: wake on the guaranteed expiry-lead flatten timer so the run()
                  // tail flattens the lot. Predicate-only addition (expiryLeadFired is latched only
                  // under v>=1, where the timer was armed) — replay-neutral for v=0 histories.
                  || expiryLeadFired
                  || remainingQty == 0
                  // Phase 3: wake on a watchlist-exit bid-stop / no-progress time-stop / feed-stale
                  // backstop so the run() loop flattens. Predicate-only (these latch only under the
                  // exit-enabled v>=1 path) — replay-neutral for v=0 / copytrade histories.
                  || exitStopFireRequested
                  || exitTimeStopFired
                  || exitFeedStaleFired
                  // Plan-2A R-AA-1: an in-loop flatten (risk_breach/force_close/chandelier) whose
                  // bounded limit rested unfilled leaves the workflow alive; wake on a LATE fill of
                  // that resting order so it drains rather than hanging open. Predicate-only — not
                  // a
                  // recorded command, so this addition is replay-neutral for v=0 histories.
                  || (flattenAwaitingLateFill && lastFillEvent != null));
      if (eodFired || expiryFired || expiryLeadFired || remainingQty == 0) {
        break;
      }
      // Plan-2A R-AA-1: apply a LATE fill of a resting in-loop-flatten bounded limit, so a
      // close-in-progress position drains on the broker fill instead of hanging. Guarded by the
      // flatten-await flag (set only when an in-loop flatten returned unfilled) so it never races
      // processOne's own fill handling.
      if (flattenAwaitingLateFill && lastFillEvent != null) {
        emitExitFill("flatten-" + (closeReason != null ? closeReason : "flatten"), lastFillEvent);
        lastFillEvent = null;
        if (remainingQty == 0) {
          flattenAwaitingLateFill = false;
          break;
        }
        continue;
      }
      // Phase 3: a watchlist-exit bid-stop, no-progress time-stop, or feed-staleness backstop
      // flattens the WHOLE remaining lot MARKETABLE (reason stop_loss / time_stop). Handled before
      // the STC pipeline so a bracket exit is not blocked behind a queued STC, and ABOVE
      // risk_breach/force_close is intentionally avoided — operator/kill-switch intent still wins.
      if (exitStopFireRequested || exitTimeStopFired || exitFeedStaleFired) {
        String reason = exitStopFireRequested ? "stop_loss" : "time_stop";
        // Feed-blind failsafe: whenever we reach the time-based backstop (the staleness timer OR a
        // no-progress time-stop) and NO exit tick has ever arrived since arm, the bid-feed went
        // blind. Audit it so the degradation is visible (never a silent blind hold), then fall back
        // to the time-based flatten. Only the bid-stop path (exitStopFireRequested) implies a live
        // feed, so it is excluded.
        if (!exitStopFireRequested && exitArmed && !exitTickSeen) {
          auditLog(
              KIND_WATCHLIST_EXIT_FEED_STALE,
              subject(
                  "contract_symbol",
                  input.getContractSymbol(),
                  "remaining_qty",
                  remainingQty,
                  "note",
                  "feed_stale_failsafe_flatten"));
        }
        exitStopFireRequested = false;
        exitTimeStopFired = false;
        exitFeedStaleFired = false;
        exitArmed = false;
        closeReason = reason;
        flattenRemaining(reason);
        continue; // flatten drained (or awaits a late fill) -> next iteration re-evaluates.
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
      // Then drain ticks. For a watchlist-exit-active position (exit armed, or the target already
      // armed the runner's trail) processExitTick owns the tick: it evaluates the bid-based stop /
      // target AND feeds the chandelier trail on the BID (per spec). Otherwise processTick runs the
      // copytrade chandelier path unchanged on the smoothed mid.
      while (!pendingTicks.isEmpty()) {
        PremiumTick t = pendingTicks.poll();
        if (exitArmed || (exitTargetFired && trailingArmed)) {
          processExitTick(t);
        } else {
          processTick(t);
        }
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

    // Plan-2A R-AA-1: the run()-tail / EOD-expiry epilogue is a GUARDED loop, not straight-line
    // flatten-once -> unconditional PositionClosed. v=DEFAULT_VERSION (in-flight workflows started
    // before this patch) keep the legacy flatten-once + unconditional-close path so their recorded
    // histories replay byte-identically (the only new command on v=0 is the appended getVersion
    // marker). v>=1: emit PositionClosed ONLY when remainingQty==0 (broker-confirmed); on a
    // bounded-flatten TTL timeout, re-arm/re-place rather than return; the workflow stays ALIVE
    // (blocked on a late fill) when the bounded limit rests unfilled — never silently completes
    // with
    // a live lot. The ONLY terminal conditions that let run() return are broker-confirmed remaining
    // == 0 OR a visible non-retryable ApplicationFailure thrown out of placeOrder.
    int flattenAwaitVersion =
        Workflow.getVersion(VERSION_FLATTEN_FILL_AWAIT, Workflow.DEFAULT_VERSION, 1);
    if (flattenAwaitVersion == Workflow.DEFAULT_VERSION) {
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

    // v>=1 guarded epilogue.
    if (eodFired || expiryFired || expiryLeadFired) {
      // Plan-2B R-AB-1: an expiry-lead timer fire flattens with reason=expiry_lead so the dedicated
      // ExpiryLead* kinds (not the Eod* fallthrough) label the lifecycle event. eod/expiry keep
      // their reasons; eod takes precedence if both an eod sweep and a lead timer fired in the same
      // task (the lead window precedes EOD by design, so this only matters in degenerate configs).
      String reason = eodFired ? "eod" : expiryFired ? "expiry" : "expiry_lead";
      // Re-place the bounded flatten until broker-confirmed flat. A place exception (visible
      // non-retryable ApplicationFailure) propagates out of flattenRemaining -> run() (an allowed
      // terminal). An unfilled bounded limit returns false; for the expiry session the next attempt
      // collapses to a marketable sell (R-AA-3), so this loop drives toward a fill. If the limit
      // still rests unfilled after a placement attempt, fall through to the alive-block below
      // rather
      // than spin: the workflow stays running (no silent complete) until a late fill drains the lot
      // or an operator force-closes.
      boolean flat = flattenRemaining(reason);
      if (!flat && !maybeCloseWorthlessAtExpiry(reason)) {
        // Phase 4 (PLAN-2026-06-24-trading-remediation): the bounded flatten rested UNFILLED — most
        // commonly because the orders were submitted at/after the 16:00 close (the 2026-06-24
        // overnight-hold incident). flattenRemaining already emitted the loud EodForceFlattenFailed
        // audit (which pages via OrderFailureAlerter, Phase-4 config). Under v>=1 re-attempt the
        // flatten at the NEXT market-session open, bounded to MAX_FLATTEN_RETRY_SESSIONS. v=DEFAULT
        // (legacy in-flight histories) keeps the await-late-fill-forever behaviour
        // byte-identically.
        // The gate is read ONCE here, outside the retry loop, so the command count is stable.
        int retryVersion =
            Workflow.getVersion(VERSION_FLATTEN_RETRY_NEXT_SESSION, Workflow.DEFAULT_VERSION, 1);
        // Stay ALIVE: block until a late fill (delivered via onFill -> a subsequent flatten cycle)
        // or some other path drains the lot. The bounded limit is resting at the broker; we never
        // emit PositionClosed with remaining > 0. Under v>=1 a next-session timer also wakes the
        // await so the unfilled flatten is re-attempted instead of held silently overnight.
        while (remainingQty > 0) {
          // Arm a one-shot timer to the NEXT session open. durationUntilNextRthOpenEt always
          // advances to a STRICTLY-FUTURE open (across the close and weekends) — distinct from
          // durationUntilRthOpenEt, which returns ZERO once past today's open and would spin.
          // Computed unconditionally only when a retry is still budgeted; a null/zero duration
          // (no sane next open) falls through to the legacy await-late-fill below.
          boolean retryBudgeted =
              retryVersion >= 1 && flattenRetrySessions < MAX_FLATTEN_RETRY_SESSIONS;
          Duration untilNextOpen = retryBudgeted ? calendar.durationUntilNextRthOpenEt() : null;
          boolean timerArmed =
              untilNextOpen != null && !untilNextOpen.isZero() && !untilNextOpen.isNegative();
          if (timerArmed) {
            retryFlattenArmed = false;
            Workflow.newTimer(untilNextOpen)
                .thenApply(
                    v -> {
                      retryFlattenArmed = true;
                      return null;
                    });
            // Wake on either a late fill of the resting order OR the next-session retry timer.
            Workflow.await(() -> lastFillEvent != null || retryFlattenArmed);
            if (lastFillEvent != null) {
              // A late fill drained (some of) the resting order before the next session — apply it
              // and re-evaluate; no retry needed for what already filled.
              emitExitFill("flatten-" + reason, lastFillEvent);
              lastFillEvent = null;
              continue;
            }
            // Next-session timer woke us with the lot still unfilled: re-attempt the flatten.
            flattenRetrySessions++;
            retryFlattenArmed = false;
            auditLog(
                KIND_FLATTEN_RETRY_SCHEDULED,
                subject(
                    "entry_signal_id", input.getEntrySignalId(),
                    "contract_symbol", input.getContractSymbol(),
                    "reason", reason,
                    "remaining_qty", remainingQty,
                    "attempt", flattenRetrySessions));
            boolean retried = flattenRemaining(reason);
            if (retried) {
              break; // broker-confirmed flat -> exit the alive-block.
            }
            if (flattenRetrySessions >= MAX_FLATTEN_RETRY_SESSIONS) {
              // Budget exhausted: emit the terminal page and fall through to the legacy
              // await-late-fill (stay-alive) behaviour below.
              auditLog(
                  KIND_FLATTEN_RETRY_EXHAUSTED,
                  subject(
                      "entry_signal_id", input.getEntrySignalId(),
                      "contract_symbol", input.getContractSymbol(),
                      "reason", reason,
                      "remaining_qty", remainingQty,
                      "attempts", flattenRetrySessions));
            }
            continue;
          }
          // Legacy (v=DEFAULT) OR retry budget spent: wait for a late fill of the resting bounded
          // limit; apply it and re-evaluate. A re-arm cycle re-places on the next late fill.
          Workflow.await(() -> lastFillEvent != null);
          if (lastFillEvent != null) {
            emitExitFill("flatten-" + reason, lastFillEvent);
            lastFillEvent = null;
          }
        }
      }
    }

    // Phase 4: if the position closed via a non-chandelier path while the trail was armed, audit
    // that the trail was torn down by the exit.
    if (trailingArmed) {
      String reason = closeReason != null ? closeReason : "normal_stc";
      if (!"chandelier_trail".equals(reason)) {
        auditLog(KIND_CHANDELIER_UNARMED_BY_EXIT, subject("reason", reason));
      }
    }

    // R-AA-1 invariant: PositionClosed ⟹ broker-confirmed remaining == 0. The guarded paths above
    // only fall through here once remainingQty == 0; the alive-block never exits with remaining >
    // 0.
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

  /**
   * Phase 3: arm the watchlist-trigger exit on the long option from the actual first-fill basis. R
   * = sl_pct * entry_premium; the hard stop level is {@code entry*(1 - sl_pct)} and the target
   * level is {@code entry*(1 + tp_ratio*sl_pct)}. Subscribes the premium feed (reusing {@link
   * #subscribeExitPremium()}) and arms the no_progress_time_stop + feed-staleness timers. Only
   * reached when the exit is enabled (tp_ratio != null) under VERSION_WATCHLIST_EXIT v>=1.
   */
  private void armWatchlistExit(BigDecimal entryBasis) {
    BigDecimal tpRatio = input.getTpRatio();
    BigDecimal slPct = input.getSlPct();
    if (entryBasis == null
        || entryBasis.signum() <= 0
        || tpRatio == null
        || slPct == null
        || slPct.signum() <= 0) {
      // Misconfigured exit DNA — do not arm (leaves the lot on its STC/EOD/expiry backstops).
      return;
    }
    exitStopLevel = entryBasis.multiply(BigDecimal.ONE.subtract(slPct));
    exitTargetLevel = entryBasis.multiply(BigDecimal.ONE.add(tpRatio.multiply(slPct)));
    exitTpPartialFraction = input.getTpPartialFraction();
    exitTrailGiveback = input.getTrailGivebackPct();

    // Phase 7: seed the measurement basis + MFE/MAE excursion trackers from the entry basis so a
    // leg that exits before any tick still reports a coherent excursion (== entry).
    exitEntryBasis = entryBasis;
    exitBidMfe = entryBasis;
    exitBidMae = entryBasis;
    exitFirstFillAt = workflowNow();

    if (!subscribeExitPremium()) {
      // Subscription failed: do NOT arm a blind bid-stop. The time-based timers below still arm as
      // the backstop, and subscribeExitPremium already audited the failure.
      exitArmed = false;
    } else {
      exitArmed = true;
    }

    // no_progress_time_stop: flatten remaining if neither stop nor target fires within the window.
    Long timeStopSecs = input.getNoProgressTimeStopSecs();
    if (timeStopSecs != null && timeStopSecs > 0) {
      Workflow.newTimer(Duration.ofSeconds(timeStopSecs))
          .thenApply(
              v -> {
                exitTimeStopFired = true;
                return null;
              });
    }

    // Feed-staleness backstop: if the exit is armed on the premium feed but no tick has arrived by
    // the staleness window, fail safe to the time-based flatten rather than hold blind. Bounded by
    // the exit-fill TTL (the same per-strategy knob the rest of the exit machinery uses).
    if (exitArmed) {
      long staleSecs = resolveExitFillTtlSecs();
      Workflow.newTimer(Duration.ofSeconds(staleSecs))
          .thenApply(
              v -> {
                if (!exitTickSeen) {
                  exitFeedStaleFired = true;
                }
                return null;
              });
    }

    auditLog(
        KIND_WATCHLIST_EXIT_ARMED,
        subject(
            "contract_symbol", input.getContractSymbol(),
            "entry_basis", entryBasis,
            "stop_level", exitStopLevel,
            "target_level", exitTargetLevel));
  }

  /**
   * Phase 3: subscribe the premium feed for the watchlist exit. Reuses the same {@link
   * SubscribePremiumActivity} + request shape as {@link #processArm(ArmChandelierPayload)}; ticks
   * arrive via {@link #chandelierTick(PremiumTick)} and are evaluated by {@link
   * #processExitTick(PremiumTick)}. Returns {@code true} on success.
   */
  private boolean subscribeExitPremium() {
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
          subject("source_signal_id", "watchlist_exit", "error", res.getError()));
      return false;
    }
    return true;
  }

  /**
   * Phase 3 main-loop exit-tick processor. Evaluates against the BID ({@link
   * PremiumTick#getBid()}); when the bid is null it falls back to the smoothed mid ({@link
   * PremiumTick#getPremium()}) and emits a one-time degradation audit. STOP (debounced, {@link
   * #EXIT_STOP_DEBOUNCE_TICKS} consecutive sub-threshold ticks): latch a stop_loss flatten. TARGET
   * (single-shot): partial-close {@code tp_partial_fraction}, move the remainder stop to breakeven,
   * and arm the chandelier on the runner with peak=current bid + giveback=trail_giveback_pct.
   */
  private void processExitTick(PremiumTick tick) {
    exitTickSeen = true;
    BigDecimal evalBid = tick.getBid();
    if (evalBid == null) {
      evalBid = tick.getPremium();
      if (!exitBidDegradedAudited && evalBid != null) {
        exitBidDegradedAudited = true;
        auditLog(
            KIND_WATCHLIST_EXIT_BID_DEGRADED,
            subject(
                "contract_symbol",
                input.getContractSymbol(),
                "note",
                "bid_null_fallback_to_mid",
                "eval_premium",
                evalBid));
      }
    }
    if (evalBid == null) {
      return; // no usable price on this tick
    }
    lastBid = evalBid; // expose the evaluated exit price to exitProximity()

    // Phase 7: ratchet the BID max-favorable / max-adverse excursion over the position life
    // (premium
    // $), reusing this tick stream. Guarded for null so a pre-arm tick race can't NPE.
    if (exitBidMfe == null || evalBid.compareTo(exitBidMfe) > 0) {
      exitBidMfe = evalBid;
    }
    if (exitBidMae == null || evalBid.compareTo(exitBidMae) < 0) {
      exitBidMae = evalBid;
    }

    // TARGET (single-shot): fires while the bracket is still whole (before any partial). bid >=
    // target -> partial + breakeven + chandelier arm.
    if (!exitTargetFired && evalBid.compareTo(exitTargetLevel) >= 0) {
      fireExitTarget(evalBid);
      return; // do not also evaluate the (now-breakeven) stop on the same tick
    }

    // STOP (debounced): bid <= stop level for N consecutive ticks. Reset the streak on any tick
    // at/above the stop so a single outlier print cannot fire.
    if (evalBid.compareTo(exitStopLevel) <= 0) {
      exitSubThresholdStreak++;
      if (exitSubThresholdStreak >= EXIT_STOP_DEBOUNCE_TICKS && !exitStopFireRequested) {
        exitStopFireRequested = true;
      }
    } else {
      exitSubThresholdStreak = 0;
    }

    // After the target armed the runner's trail, feed the chandelier on the BID (per spec the trail
    // comparison for THIS flow uses bid, not the smoothed mid). Reuses the existing processTick
    // ratchet/fire by handing it a tick whose premium IS the evaluated bid.
    if (trailingArmed && !exitStopFireRequested) {
      processTick(bidAsPremiumTick(tick, evalBid));
    }
  }

  /**
   * Phase 3: a copy of {@code tick} whose {@code premium} field is the evaluated BID, so the
   * existing chandelier {@link #processTick(PremiumTick)} (which compares on {@code getPremium()})
   * trails on the bid for the watchlist-exit runner. Deterministic — no command.
   */
  private static PremiumTick bidAsPremiumTick(PremiumTick tick, BigDecimal bid) {
    PremiumTick t = new PremiumTick();
    t.setSchemaVersion(tick.getSchemaVersion());
    t.setContractSymbol(tick.getContractSymbol());
    t.setPremium(bid);
    t.setBid(tick.getBid());
    t.setAsk(tick.getAsk());
    t.setRetrievedAt(tick.getRetrievedAt());
    return t;
  }

  /**
   * Phase 3 TARGET handler. Partial-closes {@code tp_partial_fraction} of the remaining qty via the
   * existing partial path ({@link #processOne(PartialExitRequest)}), moves the remainder stop to
   * BREAKEVEN (entry basis), and arms the chandelier on the runner (trail state set directly off
   * the exit feed armWatchlistExit already subscribed — no re-subscribe) with peak=current bid and
   * giveback=trail_giveback_pct so the runner trails. Single-shot via {@code exitTargetFired}.
   */
  private void fireExitTarget(BigDecimal targetBid) {
    exitTargetFired = true;
    long remainingBefore = remainingQty;
    BigDecimal breakeven = input.getEntryPremium();
    // Default to half (book the 2:1 tier, trail the runner) when tp_partial_fraction is unset —
    // matches the StrategyConfig.tp_partial_fraction schema default. ONE would close the entire
    // position at the target and never arm the runner/trail.
    BigDecimal fraction =
        (exitTpPartialFraction != null && exitTpPartialFraction.signum() > 0)
            ? exitTpPartialFraction
            : new BigDecimal("0.5");

    long qtyToClose =
        Math.min(remainingQty, (long) Math.ceil(remainingQty * fraction.doubleValue()));

    auditLog(
        KIND_WATCHLIST_EXIT_TARGET_FIRED,
        subject(
            "contract_symbol", input.getContractSymbol(),
            "target_bid", targetBid,
            "remaining_qty_before", remainingBefore,
            "qty_to_close_intended", qtyToClose,
            "fraction", fraction,
            "breakeven", breakeven));

    // Partial-close via the existing exit path. A synthetic STC request anchored on the target bid.
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(input.getTenantId());
    req.setStrategyId(input.getStrategyId());
    req.setSignalId(Workflow.getInfo().getWorkflowId() + ":watchlist-target");
    req.setPositionWorkflowId(Workflow.getInfo().getWorkflowId());
    req.setFraction(fraction);
    req.setRefPremium(targetBid);
    req.setReason("watchlist_target");
    req.setOccurredAt(workflowNow());
    processedSignalIds.add(req.getSignalId());
    processOne(req);

    if (remainingQty <= 0) {
      // The partial took the whole lot (e.g. fraction=1.0 or a 1-contract runner): nothing to
      // trail.
      return;
    }

    // Move the remainder stop to breakeven so the runner can only give back to scratch.
    if (breakeven != null && breakeven.signum() > 0) {
      exitStopLevel = breakeven;
      exitSubThresholdStreak = 0;
    }

    // Arm the chandelier on the runner: peak = target bid, giveback = trail_giveback_pct. The
    // runner
    // already has the exit premium feed from armWatchlistExit()/subscribeExitPremium(), and
    // processExitTick drives the chandelier via processTick(bidAsPremiumTick(...)), so set the
    // trail
    // state directly instead of calling processArm — re-subscribing would open a SECOND live
    // premium subscription (SubscribePremiumActivity does not dedup), double-delivering every NBBO
    // print and letting one market print satisfy the post-target breakeven-stop debounce. The trail
    // comparison in processTick uses tick.getPremium(); the bid-based breakeven stop above guards
    // the downside.
    // Same accept criteria as processArm (gb in (0, MAX_GIVEBACK]) so a misconfigured
    // trail_giveback_pct does not arm a looser-than-documented trail; out of bounds = no trail
    // (the runner rides its breakeven bid-stop + EOD/expiry backstops), matching the replaced path.
    if (exitTrailGiveback != null
        && exitTrailGiveback.signum() > 0
        && exitTrailGiveback.compareTo(MAX_GIVEBACK) <= 0) {
      trailingArmed = true;
      peakPremium = targetBid;
      givebackPct = exitTrailGiveback;
      auditLog(
          KIND_CHANDELIER_ARMED,
          subject(
              "source_signal_id", "watchlist_target",
              "peak_premium", targetBid,
              "giveback_pct", exitTrailGiveback));
    }
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

  @Override
  public PositionState positionState() {
    // input may be null if the query races run() before `this.input = in` (same guard the
    // killswitch-state read uses); report an empty contract + zero qty in that window.
    if (input == null) {
      return new PositionState("", 0L, null);
    }
    return new PositionState(input.getContractSymbol(), remainingQty, input.getEntryPremium());
  }

  @Override
  public ExitProximityView exitProximity() {
    // input may be null if the query races run() before `this.input = in` (same guard the
    // positionState() read uses).
    if (input == null) {
      return new ExitProximityView(
          "", null, null, null, null, null, null, false, null, false, null);
    }
    return new ExitProximityView(
        input.getContractSymbol(),
        input.getEntryPremium(),
        exitStopLevel,
        exitTargetLevel,
        lastBid,
        lastTickPremium,
        peakPremium,
        trailingArmed,
        givebackPct,
        exitArmed,
        lastTickAt);
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

    // Exit target captured BEFORE any fill mutates remainingQty (pure local, no command — unused on
    // VERSION_EXIT_RETRY_LATE_FILL_RECONCILE v=0). The retry under v>=1 drives remainingQty back
    // down to this target rather than re-sending the full qtyToClose; on v=0 retryQty stays ==
    // qtyToClose so the retry exitIntent is byte-identical.
    long targetRemaining = remainingQty - qtyToClose;
    long retryQty = qtyToClose;

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
    // Plan-2B R-AB-2: bounded STEPPED reprice gate (second gate layered over the #216 single-shot).
    // v=DEFAULT_VERSION keeps maxRetries=1 (legacy single retry) AND the legacy fresh-limit source
    // chain for byte-identical replay. v>=1 raises the cap to exit_reprice_steps and re-anchors
    // each
    // step on a fresh GetOptionQuoteActivity bid/mid bounded by exit_floor, using :reprice-N keys.
    int steppedRepriceVersion =
        Workflow.getVersion(VERSION_EXIT_STEPPED_REPRICE, Workflow.DEFAULT_VERSION, 1);
    int maxRetries;
    if (steppedRepriceVersion >= 1) {
      // exit_reprice_steps is the count of re-places AFTER the original placement, so the retry cap
      // equals the configured step count. The R-AB-1 flatten timer is the backstop once the walk
      // exhausts; the deadline is naturally bounded by exitFillTtlSecs * (steps+1) << the lead.
      maxRetries = (int) Math.max(1L, resolveExitRepriceSteps());
    } else {
      maxRetries = retryVersion >= 1 ? 1 : 0;
    }
    // Issue #227: gate the fresh-limit source-order swap. v=DEFAULT_VERSION (in-flight workflows
    // that already entered the retry block under PR #226) keep the original lastTick → peak → ref
    // chain for replay safety; v>=1 (new executions) use the corrected lastTick → ref → peak chain.
    int sourceOrderVersion =
        Workflow.getVersion(VERSION_EXIT_RETRY_SOURCE_ORDER, Workflow.DEFAULT_VERSION, 1);
    // Exit-retry late-fill reconcile gate. v=0 keeps the discard-and-retry-with-qtyToClose behavior
    // (only the appended getVersion marker is a new command; it resolves to DEFAULT_VERSION for
    // legacy histories). v>=1 reconciles any late fill after the timeout-branch cancel.
    int lateFillReconcileVersion =
        Workflow.getVersion(VERSION_EXIT_RETRY_LATE_FILL_RECONCILE, Workflow.DEFAULT_VERSION, 1);
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
      } else if (steppedRepriceVersion >= 1) {
        // Plan-2B R-AB-2: bounded STEPPED reprice. Per-step intent key uses a distinct :reprice-N
        // suffix (deterministic loop counter, separate from the original :exit: and the legacy
        // :retry keys) so no two steps reuse a client_order_id. The step's limit is anchored on a
        // fresh GetOptionQuoteActivity bid/mid, walked toward the market by exit_reprice_tick per
        // step, and BOUNDED by exit_floor (same fail-safe as 2A: null/unresolvable/above-bid →
        // marketable). retryQty is the late-fill-reconciled remaining (never re-ceil'd) so the #357
        // naked-short guard holds across ALL N steps.
        intentKey =
            Workflow.getInfo().getWorkflowId()
                + ":exit:"
                + req.getSignalId()
                + ":reprice-"
                + retryCount;
        BigDecimal stepLimit = computeSteppedRepriceLimit(retryCount);
        auditLog(
            KIND_PARTIAL_EXIT_RETRY_REQUESTED,
            subject(
                "signal_id",
                req.getSignalId(),
                "retry_attempt",
                retryCount,
                "fresh_limit_price",
                stepLimit,
                "source_premium",
                stepLimit == null ? "marketable_fallback" : "live_quote_stepped",
                "intent_key",
                intentKey));
        intent = exitIntent(req, retryQty, intentKey, stepLimit);
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
        // VERSION_EXIT_RETRY_LATE_FILL_RECONCILE: retryQty is clamped to remainingQty - target
        // after
        // a late fill is reconciled in the timeout branch; on v=0 it stays == qtyToClose so this
        // exitIntent is byte-identical.
        intent = exitIntent(req, retryQty, intentKey, freshLimit);
      }

      lastFillEvent = null;
      currentInFlightIntentKey = intentKey;
      // B2 (PLAN-exit-place-duplicate-422-crash) replay gate. v=DEFAULT_VERSION keeps the original
      // UNCAUGHT placeOrder call so in-flight histories replay byte-identically (the only new
      // command on v=0 is this getVersion marker). v>=1 wraps the call so a non-retryable
      // ApplicationFailure (e.g. the duplicate-cid 422 misclassified as InvalidRequestError) no
      // longer FAILS the workflow and orphans the live lot.
      int placeFailureGuardVersion =
          Workflow.getVersion(VERSION_EXIT_PLACE_FAILURE_GUARD, Workflow.DEFAULT_VERSION, 1);
      OrderIntentResult placed;
      if (placeFailureGuardVersion == Workflow.DEFAULT_VERSION) {
        placed = exec.placeOrder(intent);
      } else {
        try {
          placed = exec.placeOrder(intent);
        } catch (RuntimeException e) {
          // The exit placement failed (covers Temporal ActivityFailure / ApplicationFailure,
          // including a non-retryable InvalidRequestError). Pre-B2 this propagated out of
          // processOne
          // and FAILED the whole workflow, orphaning the live lot with no audit. Instead: audit the
          // failure, release the in-flight latch, and return — WITHOUT decrementing remainingQty
          // (nothing was sold) and WITHOUT entering the fill-await (a never-placed order never
          // fills → wedge). The lot stays managed; a later STC / EOD flatten / re-drive can act.
          auditLog(
              KIND_PARTIAL_EXIT_PLACE_FAILED,
              subject(
                  "signal_id",
                  req.getSignalId(),
                  "intent_key",
                  intentKey,
                  "option_symbol",
                  input.getContractSymbol(),
                  "qty",
                  intent.getQty(),
                  "error",
                  e.getMessage()));
          releaseExitInFlightLatches();
          return;
        }
      }
      // PLAN-over-exit-422: a BENIGN broker-confirmed already-closed outcome (the SELL/STC drew an
      // over-exit 422 AND /v2/positions confirmed the lot was flat) surfaces as state=CANCELLED
      // with
      // a null brokerOrderId. Treat it as already-closed: drain any in-flight fill, emit the
      // visible
      // non-paging PartialExitAlreadyFlat audit (with the divergence WARN+metric when qty was
      // non-zero), zero remainingQty from broker truth, set the normal close-reason, release the
      // in-flight latch, and return — never a PartialExitPlaceFailed page, never a wedge in the
      // fill-await (a never-placed order never fills). Gated under the same B2 version marker so
      // v=0
      // histories replay byte-identically: the branch only fires when placeOrder RETURNS CANCELLED,
      // which a pre-patch exec could never produce, so it is dead on legacy replay; resolving it
      // here (v>=1 only) keeps the command stream identical for v=0.
      if (placeFailureGuardVersion >= 1 && placed.getState() == OrderIntentResult.State.CANCELLED) {
        handleBenignAlreadyFlatExit(req.getSignalId(), intentKey);
        releaseExitInFlightLatches();
        if (closeReason == null) {
          closeReason = "normal_stc";
        }
        return;
      }
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
                        // Plan-2B R-AB-2: yield the stepped reprice to the R-AB-1 flatten timer so
                        // the bounded flatten is the unambiguous final owner (deadline pinned at or
                        // before the lead trigger; no overlapping double-place). Predicate-only
                        // addition (expiryLeadFired latched only under v>=1) — replay-neutral for
                        // v=0 histories.
                        || expiryLeadFired
                        || !pendingRiskBreaches.isEmpty()
                        || !pendingForceCloses.isEmpty());
      }

      if (lastFillEvent != null) {
        applyExitFill(req, lastFillEvent);
        releaseExitInFlightLatches();
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
        // VERSION_EXIT_RETRY_LATE_FILL_RECONCILE v>=1: the original exit order can fill LATE — its
        // onFill signal buffers during the in-flight cancelOrder activity above and is delivered
        // (lastFillEvent != null) when this workflow task resumes. Pre-this-patch the retry
        // iteration top reset lastFillEvent=null and DISCARDED that fill, so remainingQty stayed
        // stale and the retry re-sent the full qtyToClose → naked short → Alpaca 403. Reconcile it
        // here exactly once, then drive the retry to the captured target instead of re-sending the
        // full qty.
        if (lateFillReconcileVersion >= 1) {
          if (lastFillEvent != null) {
            applyExitFill(req, lastFillEvent);
            lastFillEvent =
                null; // processed exactly once — never re-counted on the retry iteration
          }
          // remainingQty - targetRemaining is inherently <= remainingQty (the anti-naked-short
          // guarantee): clamped at 0 below, never re-ceil'd. If the late fill already drove
          // remainingQty down to (or past) the target, no retry is needed.
          retryQty = Math.max(0, remainingQty - targetRemaining);
          if (retryQty <= 0) {
            auditLog(
                KIND_PARTIAL_EXIT_RETRY_SKIPPED_SATISFIED,
                subject(
                    "signal_id",
                    req.getSignalId(),
                    "remaining_qty",
                    remainingQty,
                    "target_remaining",
                    targetRemaining));
            // The original filled late and satisfied the intent: place NO retry order and release
            // the in-flight latch exactly like the drop path below.
            releaseExitInFlightLatches();
            return;
          }
        }
        if (retryCount < maxRetries) {
          retryCount++;
          continue; // Issue #216: place the retry order with a fresh limit price + intent_key.
        }
        releaseExitInFlightLatches();
      }
      // On EOD/expiry/risk_breach/force_close pre-emption (filledInTime=true but lastFillEvent
      // still null) we leave exitInFlight/currentInFlightSignalId set so flattenRemaining() can
      // cancel the still-open broker order.
      return;
    }
  }

  /**
   * Issue #434: close the lot as WORTHLESS when the PHYSICAL-expiry flatten did not fill by expiry.
   * Returns {@code true} iff it closed the position (caller must then skip the alive-block and let
   * {@code run()} complete); {@code false} to fall through to the existing stay-ALIVE behavior.
   *
   * <p>Gated on three conditions, ALL required:
   *
   * <ul>
   *   <li>{@code reason == "expiry"} — ONLY the physical-expiry flatten. NOT {@code expiry_lead}
   *       (fires before expiry; its no-fill must keep the real expiry-close attempt) nor {@code
   *       eod}/{@code risk_breach}/{@code force_close}/{@code chandelier} (a no-fill there is not a
   *       worthless-at-expiry condition).
   *   <li>The contract has PHYSICALLY expired: its OCC expiry date &lt;= the workflow's current ET
   *       date, derived deterministically from {@link Workflow#currentTimeMillis()} (never {@code
   *       LocalDate.now()}). The expiry timer can fire slightly before midnight-of-expiry in
   *       degenerate configs, so this re-checks physical expiry rather than trusting the timer
   *       alone.
   *   <li>{@link #VERSION_EXPIRE_WORTHLESS} v&gt;=1. v=DEFAULT_VERSION (in-flight pre-#434
   *       workflows) returns {@code false} → unchanged lingering behavior; the only new command on
   *       v=0 is this appended getVersion marker.
   * </ul>
   *
   * <p>On close: zero {@code remainingQty}, clear the late-fill flag, and emit the terminal {@link
   * #KIND_POSITION_EXPIRED} (P&amp;L-neutral — a worthless expiry realizes no exit credit).
   */
  private boolean maybeCloseWorthlessAtExpiry(String reason) {
    if (!"expiry".equals(reason)) {
      return false;
    }
    LocalDate expiryDate = expiryDateFromOcc(input.getContractSymbol());
    if (expiryDate == null || expiryDate.isAfter(currentEtDate())) {
      return false;
    }
    int v = Workflow.getVersion(VERSION_EXPIRE_WORTHLESS, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return false;
    }
    long remainingBefore = remainingQty;
    remainingQty = 0;
    flattenAwaitingLateFill = false;
    auditLog(
        KIND_POSITION_EXPIRED,
        subject(
            "entry_signal_id",
            input.getEntrySignalId(),
            "option_symbol",
            input.getContractSymbol(),
            "remaining_qty_before",
            remainingBefore,
            "reason",
            "worthless_expiry"));
    return true;
  }

  /**
   * The workflow's "today" in the US options market timezone, derived deterministically from {@link
   * Workflow#currentTimeMillis()} (never {@code LocalDate.now()}, which is non-deterministic in
   * workflow code). Used to decide whether the managed contract has physically expired.
   */
  private static LocalDate currentEtDate() {
    return Instant.ofEpochMilli(Workflow.currentTimeMillis())
        .atZone(java.time.ZoneId.of("America/New_York"))
        .toLocalDate();
  }

  /**
   * Force-flatten the remaining quantity for {@code reason}. Returns {@code true} iff the position
   * is now broker-confirmed flat ({@code remainingQty == 0}).
   *
   * <p>Plan-2A R-AA-1/R-AA-3: under {@link #VERSION_FLATTEN_FILL_AWAIT} v&gt;=1 the place is
   * followed by a bounded {@code Workflow.await} on the fill and {@code remainingQty} is zeroed
   * ONLY from the actual fill (never at placement). The pricing is reason-scoped (R-AA-3):
   * scheduled reasons (eod/expiry/chandelier_trail) place a BOUNDED marketable LIMIT anchored on
   * the live bid; risk_breach/force_close keep exit-NOW MARKET. The fill is routed through {@link
   * #emitExitFill(String, FillSignalPayload)} so it enters realized P&amp;L (R-AA-6).
   * v=DEFAULT_VERSION keeps the legacy zero-at-placement MARKET path so recorded histories replay
   * byte-identically.
   */
  private boolean flattenRemaining(String reason) {
    String kindReq;
    String kindDone;
    if ("eod".equals(reason)) {
      kindReq = KIND_EOD_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EOD_FORCE_FLATTENED;
    } else if ("expiry".equals(reason)) {
      kindReq = KIND_EXPIRY_FORCE_FLATTEN_REQUESTED;
      kindDone = KIND_EXPIRY_FORCE_FLATTENED;
    } else if ("expiry_lead".equals(reason)) {
      // Plan-2B R-AB-1: dedicated lead-flatten kinds — do NOT fall through to the Eod* kinds, which
      // would mislabel the multi-day expiry-lead flatten as the blanket EOD sweep.
      kindReq = KIND_EXPIRY_LEAD_FLATTEN_REQUESTED;
      kindDone = KIND_EXPIRY_LEAD_FORCE_FLATTENED;
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
      return true;
    }

    // Phase 4 (PLAN-2026-06-24-trading-remediation): each next-session retry MUST use a DISTINCT
    // intent_key so it derives a fresh client_order_id. Reusing the first attempt's key would
    // re-POST a duplicate client_order_id — Alpaca rejects it (or the by-cid lookup resolves the
    // prior terminal order), so the 2nd retry would FAIL the workflow instead of gracefully
    // exhausting its budget. The first attempt (flattenRetrySessions == 0) keeps the original key
    // byte-identically so legacy histories replay unchanged.
    String flattenIntentKey =
        Workflow.getInfo().getWorkflowId()
            + ":exit:flatten-"
            + reason
            + (flattenRetrySessions > 0 ? ":retry-" + flattenRetrySessions : "");

    // Plan-2A R-AA-1 decision point. The getVersion marker is appended AFTER the shared prologue
    // (kindReq audit + best-effort cancel) and BEFORE the place/zero, so legacy histories — which
    // recorded kindReq/cancel/placeOrder/kindDone with no marker — replay through the v=DEFAULT
    // branch byte-identically (the only new command on v=0 is this appended marker).
    int flattenAwaitVersion =
        Workflow.getVersion(VERSION_FLATTEN_FILL_AWAIT, Workflow.DEFAULT_VERSION, 1);
    if (flattenAwaitVersion == Workflow.DEFAULT_VERSION) {
      // LEGACY: place a MARKET flatten, zero remainingQty at placement SUCCESS, audit kindDone.
      OrderIntent intent = flattenIntent(flattenIntentKey, reason);
      try {
        OrderIntentResult flattenPlaced = exec.placeOrder(intent);
        // PLAN-over-exit-422: a benign broker-confirmed already-flat outcome (state=CANCELLED) is
        // NOT a failure — a flatten on a lot that is already flat is satisfied. Zero remainingQty,
        // emit PartialExitAlreadyFlat, and exit the alive-loop. Replay-neutral: a pre-patch exec
        // could never return CANCELLED, so this branch is dead on legacy histories (no new
        // command).
        if (flattenPlaced.getState() == OrderIntentResult.State.CANCELLED) {
          handleBenignAlreadyFlatExit("flatten-" + reason, flattenIntentKey);
          return remainingQty == 0;
        }
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
      return remainingQty == 0;
    }

    // v>=1 (R-AA-1 + R-AA-3 + R-AA-6).
    // Phase 3: the watchlist-exit hard stop and no-progress time-stop route MARKET
    // (limitPrice=null)
    // alongside risk_breach/force_close — a triggered bracket exit must hit the market NOW, not
    // rest
    // a bounded limit. The target partial + chandelier trail keep the existing bounded/stepped
    // path.
    boolean immediacy =
        "risk_breach".equals(reason)
            || "force_close".equals(reason)
            || "stop_loss".equals(reason)
            || "time_stop".equals(reason);
    BigDecimal flattenLimit = immediacy ? null : computeBoundedFlattenLimit(reason);
    OrderIntent intent = flattenIntent(flattenIntentKey, reason, flattenLimit);

    lastFillEvent = null;
    // R-AA-1: a placeOrder exception (a visible non-retryable ApplicationFailure) propagates out of
    // run() as a visible workflow failure — an ALLOWED terminal. We do NOT swallow it on v>=1: a
    // silently-swallowed failure plus a re-arm loop would spin forever, and the safety contract is
    // "broker-confirmed flat OR visible failure", never "silent complete".
    OrderIntentResult flattenPlaced = exec.placeOrder(intent);
    // PLAN-over-exit-422: a benign broker-confirmed already-flat flatten (state=CANCELLED) zeroes
    // remainingQty from broker truth and exits the alive-loop — broker-confirmed flat IS the
    // satisfying terminal, so it is neither a "silent complete" nor a failure. Skip the fill-await
    // (a never-placed order never fills → wedge).
    if (flattenPlaced.getState() == OrderIntentResult.State.CANCELLED) {
      handleBenignAlreadyFlatExit("flatten-" + reason, flattenIntentKey);
      flattenAwaitingLateFill = false;
      return remainingQty == 0;
    }

    long ttl = resolveExitFillTtlSecs();
    Workflow.await(Duration.ofSeconds(ttl), () -> lastFillEvent != null);

    if (lastFillEvent != null) {
      // R-AA-1: zero remainingQty ONLY from the actual fill. R-AA-6: route through the shared
      // fill-applier so the flatten fill emits PartialExitFilled (enters realized P&L). A synthetic
      // signal_id flatten-<reason> matches flattenIntent's signal_id.
      long flattenedThisFill = lastFillEvent.getFilledQty();
      emitExitFill("flatten-" + reason, lastFillEvent);
      lastFillEvent = null;
      flattenAwaitingLateFill = false;
      if (remainingQty == 0) {
        // Lifecycle marker (P&L-neutral): the realized-P&L credit rode the PartialExitFilled above.
        auditLog(
            kindDone,
            subject(
                "entry_signal_id",
                input.getEntrySignalId(),
                "contract_symbol",
                input.getContractSymbol(),
                "qty_flattened",
                flattenedThisFill,
                "reason",
                reason));
        return true;
      }
      // Partial fill: stay alive for the residual; do not emit the terminal lifecycle marker.
      flattenAwaitingLateFill = true;
      return false;
    }

    // TTL timeout — the bounded limit rests UNFILLED. Best-effort cancel, emit a loud failure
    // audit,
    // and stay ALIVE (never zero remainingQty, never emit PositionClosed). The caller re-arms / the
    // main loop applies a late fill of the resting order.
    try {
      exec.cancelOrder(flattenIntentKey);
    } catch (RuntimeException ignored) {
      // Best-effort; reconciliation closes the loop on the real broker-side state.
    }
    auditLog(
        KIND_EOD_FORCE_FLATTEN_FAILED,
        subject(
            "entry_signal_id",
            input.getEntrySignalId(),
            "contract_symbol",
            input.getContractSymbol(),
            "reason",
            reason,
            "remaining_qty",
            remainingQty,
            "note",
            "bounded_flatten_unfilled_workflow_stays_alive"));
    flattenAwaitingLateFill = true;
    return false;
  }

  /**
   * Plan-2A R-AA-3: compute the bounded marketable-LIMIT price for a scheduled flatten. Anchors on
   * the live bid from {@link GetOptionQuoteActivity} (chain: live bid → mid → {@code
   * lastTickPremium} → {@code peakPremium} → ref), bounded by {@code exit_floor_abs}/{@code
   * exit_floor_pct}. Returns {@code null} (= a marketable exit, {@code limitPrice=null}) on every
   * FAIL-SAFE branch:
   *
   * <ul>
   *   <li>quote FAILED/UNAVAILABLE → marketable fallback + {@link #KIND_FLATTEN_QUOTE_UNAVAILABLE};
   *   <li>no usable anchor at all → marketable;
   *   <li>floor null/absent/unresolvable, or floor &gt; live bid → marketable + {@link
   *       #KIND_FLATTEN_FLOOR_CONFIG_ERROR};
   *   <li>EXPIRY session with {@code bid <= 0} → fully marketable (a no-bid contract expires
   *       worthless; do NOT rest a $0.01 limit that never fills).
   * </ul>
   */
  private BigDecimal computeBoundedFlattenLimit(String reason) {
    int boundedVersion =
        Workflow.getVersion(VERSION_FLATTEN_BOUNDED_LIMIT, Workflow.DEFAULT_VERSION, 1);
    if (boundedVersion == Workflow.DEFAULT_VERSION) {
      // Defensive: only reachable on a fresh execution (the caller is already inside v>=1 of
      // VERSION_FLATTEN_FILL_AWAIT); a marketable exit is the safe default.
      return null;
    }

    boolean expirySession = "expiry".equals(reason);

    GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
    qreq.setSchemaVersion(1L);
    qreq.setTenantId(input.getTenantId());
    qreq.setStrategyId(input.getStrategyId());
    qreq.setContractSymbol(input.getContractSymbol());
    OptionQuoteResult quote = optionQuote.getOptionQuote(qreq);

    if (quote == null || quote.getStatus() != OptionQuoteResult.Status.OK) {
      // Quote FAILED/UNAVAILABLE on a scheduled path → marketable fallback (NOT a stale ref-premium
      // limit). Loud audit so a market-data outage during a force-close is visible.
      auditLog(
          KIND_FLATTEN_QUOTE_UNAVAILABLE,
          subject(
              "contract_symbol",
              input.getContractSymbol(),
              "reason",
              reason,
              "quote_status",
              quote == null ? "NULL" : quote.getStatus().value(),
              "note",
              "marketable_fallback"));
      return null;
    }

    BigDecimal liveBid = quote.getBid();

    // EXPIRY session: when bid <= 0 go fully marketable (no-bid contract expires worthless).
    if (expirySession && (liveBid == null || liveBid.signum() <= 0)) {
      return null;
    }

    // Anchor chain: live bid → mid → lastTickPremium → peakPremium → ref.
    BigDecimal anchor = firstPositive(liveBid, quote.getMid(), lastTickPremium, peakPremium);
    if (anchor == null) {
      // No usable anchor → marketable.
      return null;
    }

    // Resolve the floor. exit_floor = max(exit_floor_abs, anchor * exit_floor_pct). On the expiry
    // session the floor collapses to expiry_day_floor (applied only because a live bid exists
    // here).
    BigDecimal floor = resolveExitFloor(anchor, expirySession);
    if (floor == null) {
      // Floor null/absent/unresolvable → marketable fallback + loud config-error audit.
      auditLog(
          KIND_FLATTEN_FLOOR_CONFIG_ERROR,
          subject(
              "contract_symbol",
              input.getContractSymbol(),
              "reason",
              reason,
              "note",
              "no_resolvable_floor_marketable_fallback"));
      return null;
    }

    // A floor ABOVE the live bid would forbid selling at any executable price → marketable
    // fallback.
    if (liveBid != null && liveBid.signum() > 0 && floor.compareTo(liveBid) > 0) {
      auditLog(
          KIND_FLATTEN_FLOOR_CONFIG_ERROR,
          subject(
              "contract_symbol", input.getContractSymbol(),
              "reason", reason,
              "floor", floor,
              "live_bid", liveBid,
              "note", "floor_above_live_bid_marketable_fallback"));
      return null;
    }

    // Bounded marketable LIMIT: anchor at/through the live bid, but never below the floor. Round to
    // a
    // penny tick (same deterministic helper the entry/exit paths use).
    BigDecimal limit = anchor.max(floor);
    return OptionTick.round(limit);
  }

  /**
   * Plan-2B R-AB-2: compute the bounded LIMIT for stepped exit reprice step {@code step} (1-based:
   * step 1 is the first re-place after the original placement). Anchors on a FRESH {@link
   * GetOptionQuoteActivity} bid/mid (re-fetched each step so the walk tracks the live market),
   * walks toward the market by {@code step * exit_reprice_tick}, and is BOUNDED by {@code
   * exit_floor} (the same fail-safe as 2A's {@link #computeBoundedFlattenLimit(String)} —
   * null/unresolvable/above-bid → marketable). Returns {@code null} (= a marketable exit) on every
   * fail-safe branch so the stepped reprice can never rest above an executable price:
   *
   * <ul>
   *   <li>quote FAILED/UNAVAILABLE → marketable + {@link #KIND_FLATTEN_QUOTE_UNAVAILABLE};
   *   <li>no usable anchor → marketable;
   *   <li>floor null/unresolvable, or floor &gt; live bid → marketable + {@link
   *       #KIND_FLATTEN_FLOOR_CONFIG_ERROR}.
   * </ul>
   *
   * <p>Uses the NORMAL-session floor (never the expiry-day collapse) — a normal STC reprice is not
   * an expiry-day event.
   */
  private BigDecimal computeSteppedRepriceLimit(int step) {
    GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
    qreq.setSchemaVersion(1L);
    qreq.setTenantId(input.getTenantId());
    qreq.setStrategyId(input.getStrategyId());
    qreq.setContractSymbol(input.getContractSymbol());
    OptionQuoteResult quote = optionQuote.getOptionQuote(qreq);

    if (quote == null || quote.getStatus() != OptionQuoteResult.Status.OK) {
      auditLog(
          KIND_FLATTEN_QUOTE_UNAVAILABLE,
          subject(
              "contract_symbol",
              input.getContractSymbol(),
              "reason",
              "exit_reprice",
              "quote_status",
              quote == null ? "NULL" : quote.getStatus().value(),
              "note",
              "marketable_fallback"));
      return null;
    }

    BigDecimal liveBid = quote.getBid();
    // Anchor chain: live bid → mid → lastTickPremium → peakPremium → ref (same as the flatten
    // path).
    BigDecimal anchor =
        firstPositive(
            liveBid, quote.getMid(), lastTickPremium, peakPremium, input.getEntryPremium());
    if (anchor == null) {
      return null; // No usable anchor → marketable.
    }

    BigDecimal floor = resolveExitFloor(anchor, false);
    if (floor == null) {
      auditLog(
          KIND_FLATTEN_FLOOR_CONFIG_ERROR,
          subject(
              "contract_symbol",
              input.getContractSymbol(),
              "reason",
              "exit_reprice",
              "note",
              "no_resolvable_floor_marketable_fallback"));
      return null;
    }
    if (liveBid != null && liveBid.signum() > 0 && floor.compareTo(liveBid) > 0) {
      auditLog(
          KIND_FLATTEN_FLOOR_CONFIG_ERROR,
          subject(
              "contract_symbol",
              input.getContractSymbol(),
              "reason",
              "exit_reprice",
              "floor",
              floor,
              "live_bid",
              liveBid,
              "note",
              "floor_above_live_bid_marketable_fallback"));
      return null;
    }

    // Walk toward the market: limit = max(floor, anchor - step * exit_reprice_tick). The walk never
    // crosses the configured fail-safe floor.
    BigDecimal tick = resolveExitRepriceTick();
    BigDecimal concession = tick.multiply(BigDecimal.valueOf(step));
    BigDecimal limit = anchor.subtract(concession).max(floor);
    return OptionTick.round(limit);
  }

  /**
   * Resolve the exit floor for a bounded flatten. Normal session: {@code max(exit_floor_abs, anchor
   * * exit_floor_pct)}. Expiry session: {@code expiry_day_floor} (a near-zero floor applied only
   * when a live bid exists — the caller has already routed bid&lt;=0 to fully marketable). Returns
   * {@code null} when no floor field resolves (fail-safe: caller falls back to marketable).
   */
  private BigDecimal resolveExitFloor(BigDecimal anchor, boolean expirySession) {
    if (expirySession) {
      BigDecimal edf = input.getExpiryDayFloor();
      return (edf != null && edf.signum() >= 0) ? edf : null;
    }
    BigDecimal abs = input.getExitFloorAbs();
    BigDecimal pct = input.getExitFloorPct();
    BigDecimal pctFloor =
        (pct != null && pct.signum() > 0 && anchor != null) ? anchor.multiply(pct) : null;
    if (abs != null && abs.signum() > 0 && pctFloor != null) {
      return abs.max(pctFloor);
    }
    if (abs != null && abs.signum() > 0) {
      return abs;
    }
    return pctFloor;
  }

  /** First strictly-positive BigDecimal in the list, or null if none. Deterministic. */
  private static BigDecimal firstPositive(BigDecimal... candidates) {
    for (BigDecimal c : candidates) {
      if (c != null && c.signum() > 0) {
        return c;
      }
    }
    return null;
  }

  /**
   * Clear the exit in-flight latch + the tracked broker-order/signal/intent keys. Called when an
   * exit resolves and no further retry order is pending (fill complete, retry skipped/satisfied, or
   * retries exhausted). Pure state reset — no Temporal command, so it is replay-neutral.
   */
  private void releaseExitInFlightLatches() {
    exitInFlight = false;
    currentInFlightBrokerOrderId = null;
    currentInFlightSignalId = null;
    currentInFlightIntentKey = null;
  }

  /**
   * Apply a single partial-exit fill: decrement {@code remainingQty} and emit the {@link
   * #KIND_PARTIAL_EXIT_FILLED} audit. Thin wrapper over the shared {@link #emitExitFill(String,
   * FillSignalPayload)} keyed on {@code req.getSignalId()}. Shared by the normal-path fill block
   * and the VERSION_EXIT_RETRY_LATE_FILL_RECONCILE timeout-branch reconcile so the two audits are
   * byte-identical. Does NOT touch the in-flight latches or {@code closeReason} — the caller owns
   * lifecycle transitions.
   */
  private void applyExitFill(PartialExitRequest req, FillSignalPayload fillEvent) {
    emitExitFill(req.getSignalId(), fillEvent);
  }

  /**
   * Plan-2A R-AA-6: the SHARED fill-applier used by BOTH the partial-exit path (via {@link
   * #applyExitFill(PartialExitRequest, FillSignalPayload)}) and the scheduled-flatten path (with a
   * synthetic {@code flatten-<reason>} signal_id). Decrements {@code remainingQty} by the actual
   * fill and emits {@link #KIND_PARTIAL_EXIT_FILLED} carrying {@code qty_filled} + {@code
   * avg_fill_price} (+ {@code option_symbol} under {@link #VERSION_EXIT_FILLED_OPTION_SYMBOL}
   * v&gt;=1) so {@code DailyPnlActivitiesImpl} enters every exit — STC AND force-flatten — into
   * realized P&L. Before this, force-flatten exits emitted only {@code qty_flattened} (no price)
   * and contributed ZERO realized P&L, so the daily-loss kill-switch under-counted losses on the
   * eod/expiry/chandelier paths. Single P&L source: {@code EodForceFlattened}/{@code
   * ExpiryForceFlattened} stay P&L-neutral lifecycle markers.
   */
  private void emitExitFill(String signalId, FillSignalPayload fillEvent) {
    long filled = fillEvent.getFilledQty();
    remainingQty -= filled;
    Map<String, Object> exitSubject =
        subject(
            "signal_id",
            signalId,
            "qty_filled",
            filled,
            "remaining_qty_after",
            remainingQty,
            "broker_order_id",
            fillEvent.getBrokerOrderId(),
            "avg_fill_price",
            fillEvent.getAvgFillPrice());
    // Issue #276 / Plan-2A R-AA-6: emit the per-symbol correlation key so the DailyPnl FIFO
    // grouping
    // matches this exit against its OWN symbol's entry basis. Replay-gated so legacy
    // PositionWorkflow
    // histories reproduce the old subject (no option_symbol) deterministically.
    if (Workflow.getVersion(VERSION_EXIT_FILLED_OPTION_SYMBOL, Workflow.DEFAULT_VERSION, 1) >= 1) {
      exitSubject.put("option_symbol", input.getContractSymbol());
    }
    auditLog(KIND_PARTIAL_EXIT_FILLED, exitSubject);

    // Phase 7: emit the per-leg payoff MEASUREMENT alongside every watchlist-trigger exit fill (the
    // target partial AND the terminal close). filled is the leg qty; remainingQty was just
    // decremented, so remainingQty + filled is the qty before this leg. Inert for copytrade
    // (tp_ratio == null) and replay-gated under VERSION_WATCHLIST_EXIT.
    emitWatchlistMeasurement(signalId, filled, remainingQty + filled, fillEvent.getAvgFillPrice());
  }

  /**
   * Phase 7: emit {@link #KIND_WATCHLIST_EXIT_MEASURED} for one exit leg so the realized 2:1 payoff
   * ratio is computable from the audit log. INERT unless the watchlist exit is enabled ({@code
   * input.getTpRatio() != null}) under {@link #VERSION_WATCHLIST_EXIT} v&gt;=1 — a copytrade
   * position emits nothing new (its event history stays byte-identical). The {@code exit_rule} is
   * derived from the leg's {@code signalId}: {@code <wfId>:watchlist-target} -&gt; {@code target};
   * {@code flatten-<reason>} -&gt; {@code reason} (stop_loss / time_stop / chandelier_trail / eod /
   * expiry / expiry_lead). {@code realized_R = (exit - entry) / (sl_pct * entry)}; {@code
   * partial_fraction = legQty / remainingBefore}; {@code hold_minutes} from the first-fill anchor;
   * {@code dte_at_exit} from the OCC expiry vs now. Pure read of measurement state — the only new
   * command is the {@code auditLog} below, recorded only under v&gt;=1.
   */
  private void emitWatchlistMeasurement(
      String signalId, long legQty, long remainingBefore, BigDecimal exitPremium) {
    if (input.getTpRatio() == null
        || Workflow.getVersion(VERSION_WATCHLIST_EXIT, Workflow.DEFAULT_VERSION, 1) < 1) {
      return;
    }
    String exitRule = watchlistExitRule(signalId);
    if (exitRule == null) {
      return; // not a recognized watchlist exit leg (defensive — no such fill on a measured lot)
    }

    BigDecimal entry = exitEntryBasis;
    BigDecimal slPct = input.getSlPct();
    BigDecimal realizedR = null;
    if (entry != null
        && entry.signum() > 0
        && slPct != null
        && slPct.signum() > 0
        && exitPremium != null) {
      BigDecimal rDollar = slPct.multiply(entry);
      realizedR = exitPremium.subtract(entry).divide(rDollar, 6, java.math.RoundingMode.HALF_UP);
    }

    BigDecimal partialFraction =
        remainingBefore > 0
            ? BigDecimal.valueOf(legQty)
                .divide(BigDecimal.valueOf(remainingBefore), 6, java.math.RoundingMode.HALF_UP)
            : null;

    long holdMinutes =
        exitFirstFillAt != null ? Duration.between(exitFirstFillAt, workflowNow()).toMinutes() : 0L;

    Long dteAtExit = null;
    LocalDate expiry = expiryDateFromOcc(input.getContractSymbol());
    if (expiry != null) {
      dteAtExit = java.time.temporal.ChronoUnit.DAYS.between(workflowNow().toLocalDate(), expiry);
    }

    auditLog(
        KIND_WATCHLIST_EXIT_MEASURED,
        subject(
            "contract_symbol", input.getContractSymbol(),
            "exit_rule", exitRule,
            "entry_premium", entry,
            "exit_premium", exitPremium,
            "realized_R", realizedR,
            "premium_mfe", exitBidMfe,
            "premium_mae", exitBidMae,
            "partial_fraction", partialFraction,
            "hold_minutes", holdMinutes,
            "dte_at_exit", dteAtExit));
  }

  /**
   * Phase 7: map an exit-leg {@code signalId} to its {@code exit_rule}. The target partial signal
   * is {@code <wfId>:watchlist-target}; every scheduled/bracket flatten uses {@code
   * flatten-<reason>} (see {@link #flattenIntent(String, String)} / {@link #emitExitFill(String,
   * FillSignalPayload)}). Returns {@code null} for any other shape (no measurement emitted).
   */
  private static String watchlistExitRule(String signalId) {
    if (signalId == null) {
      return null;
    }
    if (signalId.endsWith(":watchlist-target")) {
      return "target";
    }
    if (signalId.startsWith("flatten-")) {
      return signalId.substring("flatten-".length());
    }
    return null;
  }

  /**
   * PLAN-over-exit-422: shared handler for a BENIGN broker-confirmed already-closed exit (the
   * placeOrder Activity returned state=CANCELLED). DRAINS any in-flight {@code lastFillEvent} FIRST
   * (so a real fill that landed between placement and this point is booked into realized P&L before
   * we zero from broker truth), captures {@code remaining_qty_before}, emits the visible non-paging
   * {@link #KIND_PARTIAL_EXIT_ALREADY_FLAT} audit, and zeroes {@code remainingQty}. When {@code
   * remaining_qty_before>0} (contracts zeroed without a booked PartialExitFilled — a real
   * divergence) it ALSO logs a WARN and increments {@link #METRIC_OVER_EXIT_FLAT_DIVERGENCE}. Does
   * NOT touch the in-flight latches or {@code closeReason} / the alive-loop — the caller owns those
   * lifecycle transitions (they differ between the partial-exit and flatten sites).
   */
  private void handleBenignAlreadyFlatExit(String signalId, String intentKey) {
    if (lastFillEvent != null) {
      // Drain a real in-flight fill before zeroing — emitExitFill decrements remainingQty by the
      // booked fill and emits PartialExitFilled (enters realized P&L).
      emitExitFill(signalId, lastFillEvent);
      lastFillEvent = null;
    }
    long remainingQtyBefore = remainingQty;
    auditLog(
        KIND_PARTIAL_EXIT_ALREADY_FLAT,
        subject(
            "signal_id",
            signalId,
            "intent_key",
            intentKey,
            "option_symbol",
            input.getContractSymbol(),
            "remaining_qty_before",
            remainingQtyBefore));
    if (remainingQtyBefore > 0) {
      Workflow.getMetricsScope().counter(METRIC_OVER_EXIT_FLAT_DIVERGENCE).inc(1);
      Workflow.getLogger(PositionWorkflowImpl.class)
          .warn(
              "over-exit already-flat divergence: broker confirmed flat but remaining_qty_before={} "
                  + "was non-zero (contracts zeroed without a booked PartialExitFilled) signal_id={} "
                  + "intent_key={} option_symbol={}",
              remainingQtyBefore,
              signalId,
              intentKey,
              input.getContractSymbol());
    }
    remainingQty = 0;
  }

  private OrderIntent exitIntent(
      PartialExitRequest req, long qty, String intentKey, BigDecimal limitPrice) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(input.getTenantId());
    i.setStrategyId(input.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId(req.getSignalId());
    // Issue #288: thread the resolved broker target onto the exit intent so validateIntent passes
    // and PlaceOrder reaches the exec broker (adopted positions surface this on their first STC).
    i.setBrokerTarget(OrderIntent.BrokerTarget.fromValue(brokerTarget));
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

  /** Legacy v=0 flatten intent: always a MARKET order ({@code limitPrice=null}). */
  private OrderIntent flattenIntent(String intentKey, String reason) {
    return flattenIntent(intentKey, reason, null);
  }

  /**
   * Plan-2A R-AA-3 flatten intent. {@code limitPrice == null} → MARKET (immediacy reasons, or a
   * bounded path that FAILED SAFE to marketable); a non-null {@code limitPrice} → a bounded
   * marketable LIMIT (rounded to a penny tick via the shared deterministic helper, in lock-step
   * with the exit/entry paths).
   */
  private OrderIntent flattenIntent(String intentKey, String reason, BigDecimal limitPrice) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setTenantId(input.getTenantId());
    i.setStrategyId(input.getStrategyId());
    i.setIntentKey(intentKey);
    i.setSignalId("flatten-" + reason);
    // Issue #288: thread the resolved broker target onto the flatten intent so validateIntent
    // passes and the force-close/EOD-flatten PlaceOrder reaches the exec broker.
    i.setBrokerTarget(OrderIntent.BrokerTarget.fromValue(brokerTarget));
    i.setOptionSymbol(input.getContractSymbol());
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(remainingQty);
    i.setLimitPrice(limitPrice == null ? null : OptionTick.round(limitPrice));
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

  /**
   * Plan-2B R-AB-1: resolve the flatten-lead minutes for the guaranteed expiry-lead timer. Sourced
   * from {@code input.flatten_lead_minutes}; null/absent falls back to {@link
   * #FLATTEN_LEAD_MINUTES_DEFAULT}. Pure read (no Temporal command) — only reached under {@link
   * #VERSION_EXPIRY_LEAD_FLATTEN} v&gt;=1.
   */
  private long resolveFlattenLeadMinutes(PositionWorkflowInput in) {
    Long configured = in.getFlattenLeadMinutes();
    return configured != null ? configured : FLATTEN_LEAD_MINUTES_DEFAULT;
  }

  /**
   * Plan-2B R-AB-2: resolve the bounded stepped-reprice step count. Sourced from {@code
   * input.exit_reprice_steps}; null/absent falls back to {@link #EXIT_REPRICE_STEPS_DEFAULT}. Pure
   * read — only consulted under {@link #VERSION_EXIT_STEPPED_REPRICE} v&gt;=1.
   */
  private long resolveExitRepriceSteps() {
    Long configured = input.getExitRepriceSteps();
    return configured != null ? configured : EXIT_REPRICE_STEPS_DEFAULT;
  }

  /**
   * Plan-2B R-AB-2: resolve the per-step price concession the bounded stepped reprice walks toward
   * the market. Sourced from {@code input.exit_reprice_tick}; null/absent falls back to {@link
   * #EXIT_REPRICE_TICK_DEFAULT}. Pure read — only consulted under {@link
   * #VERSION_EXIT_STEPPED_REPRICE} v&gt;=1.
   */
  private BigDecimal resolveExitRepriceTick() {
    BigDecimal configured = input.getExitRepriceTick();
    return (configured != null && configured.signum() > 0) ? configured : EXIT_REPRICE_TICK_DEFAULT;
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
