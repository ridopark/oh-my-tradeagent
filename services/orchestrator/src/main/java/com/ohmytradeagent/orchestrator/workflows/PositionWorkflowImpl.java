package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
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

  // Operator "Trim" (partial_close Update) attribution audits — the partial-exit sibling of the
  // ForceClose* pair. The trim's qty math and fill still ride the existing PartialExit* events;
  // these record WHO asked for it and WHY, which the synthetic PartialExitRequest cannot.
  private static final String KIND_OPERATOR_TRIM_REQUESTED = "OperatorTrimRequested";
  private static final String KIND_OPERATOR_TRIM_NOOP = "OperatorTrimNoop";
  // The reduce-only clamp bit: the requested fraction ceil()-ed up to the WHOLE remaining lot (the
  // position drained between the /live render and the click), so the trim was cut to leave one
  // contract — or skipped entirely on a 1-lot. Records both quantities so an operator who expected
  // N sold and saw N-1 can see exactly why.
  private static final String KIND_OPERATOR_TRIM_CLAMPED = "OperatorTrimClamped";

  /** {@code reason} stamped on the synthetic operator-trim PartialExitRequest. */
  private static final String REASON_OPERATOR_TRIM = "operator_trim";

  // Edited-signal supersede (F1) child-side audit. Emitted by processSupersede when the parent
  // CopytradeSignalWorkflow's supersede signal lands on a confirmed, just-filled, not-partially-
  // exited leg whose expiry was corrected by a follow-up BTO. Records the wrong-expiry leg this
  // workflow holds + the corrected leg's identifiers, then drives flattenRemaining("bto_corrected")
  // (an immediate market exit, like force_close). The PARENT emits BtoCorrectionSuperseded carrying
  // BOTH OCCs as the auditable supersede decision; this child-side kind ties the actual flatten to
  // that decision. Registered in AuditEventKinds.ALL_KINDS.
  private static final String KIND_SUPERSEDED_BY_CORRECTION = "PositionSupersededByCorrection";

  // F1: dedicated flatten request/done kinds for the bto_corrected supersede flatten — parity with
  // the expiry_lead carve-out (which exists precisely so a non-EOD flatten is NOT mislabeled as the
  // blanket EodForceFlatten* sweep). A real-money auto-cancel is at least as audit-sensitive, so
  // its
  // intermediate flatten events get their own kinds rather than falling through to Eod*. The
  // terminal close still rides PositionClosed (closeReason=bto_corrected). Registered in
  // AuditEventKinds.ALL_KINDS.
  private static final String KIND_BTO_CORRECTION_FLATTEN_REQUESTED =
      "BtoCorrectionFlattenRequested";
  private static final String KIND_BTO_CORRECTION_FLATTENED = "BtoCorrectionFlattened";

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

  // Phase 1 (PLAN-2026-06-25-trading-remediation): a partial-target exit whose placeOrder retries
  // were exhausted (PartialExitPlaceFailed) is re-driven ONCE at the next RTH open instead of being
  // silently dropped (the 2026-06-25 QQQ 720p 09:43 incident). KIND_PARTIAL_EXIT_RETRY_REQUESTED
  // (above, #216) is reused as the informational per-attempt marker (does NOT page);
  // KIND_PARTIAL_EXIT_RETRY_EXHAUSTED is the terminal page emitted once the bounded
  // next-session retry budget (MAX_PARTIAL_PLACE_RETRY_SESSIONS) is spent and the partial still
  // failed to place — the discretionary partial is given up and normal exits (STC / chandelier /
  // EOD flatten) continue to govern the lot. Registered in AuditEventKinds.ALL_KINDS and paged by
  // OrderFailureAlerter.
  private static final String KIND_PARTIAL_EXIT_RETRY_EXHAUSTED = "PartialExitRetryExhausted";

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
   * PLAN-2026-07-23 replay gate: scope the no_progress_time_stop to the PRE-take-profit window
   * only. The {@code no_progress_time_stop_secs} timer is a stalled-breakout guard whose documented
   * contract applies only "if neither the take-profit nor the hard stop has triggered"; the code
   * fired it unconditionally, so a post-target time-stop flattened a trailing runner and pre-empted
   * the chandelier trail (TSLA 2026-07-23). Under v&gt;=1 the main-loop time-stop consumer (and its
   * matching {@code Workflow.await} wake predicate) is gated on {@code !exitTargetFired} so once
   * the target arms the trail, the runner is governed only by the chandelier giveback + breakeven
   * stop + EOD/expiry backstops (Fork A). At DEFAULT_VERSION the consumer stays the CURRENT
   * unconditional fire so in-flight histories replay byte-identically — the timer arm is unchanged
   * (still fires into the latch), so the only new command on v=0 is this appended getVersion
   * marker.
   */
  private static final String VERSION_TIMESTOP_PRETARGET_ONLY =
      "watchlist-timestop-pretarget-only-v1";

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
   * F1 replay gate: reconcile the AUTHORITATIVE broker terminal state surfaced by the
   * timeout-branch cancel BEFORE deciding to retry. The {@link
   * #VERSION_EXIT_RETRY_LATE_FILL_RECONCILE} fix only reconciles a late fill that arrived via the
   * buffered {@code onFill} signal; it does NOT re-check the broker/journal. But the exec
   * cancel-on-filled race ({@code ExecActivitiesImpl}) returns {@code state=FILLED} with the
   * broker-confirmed {@code filledQty}/{@code avgFillPrice} when the order had ALREADY_FILLED at
   * cancel time — the orchestrator was DISCARDING that return. The live incident: an STC half of 3
   * ct FILLED 2 @ $1.84 but no onFill arrived before the TTL; the retry over-sold and was
   * broker-rejected (PartialExitPlaceFailed) with NO PartialExitFilled for the 2 that actually sold
   * → phantom next-session retry against a lot that no longer held that qty.
   *
   * <p>v&gt;=1: capture the cancel return; if it reports FILLED (or a positive {@code filledQty}),
   * synthesize a {@link FillSignalPayload} from it and route it through the EXISTING {@link
   * #applyExitFill(PartialExitRequest, FillSignalPayload)} → {@link #emitExitFill(String,
   * FillSignalPayload)} so EXACTLY ONE PartialExitFilled is emitted and {@code remainingQty}
   * decrements from broker truth. Defense-in-depth: if the cancel did NOT surface the fill, fall
   * back to {@code exec.getOrderStatus(intentKey)} for the same recheck. Then the existing {@code
   * retryQty = max(0, remainingQty - targetRemaining)} clamp drives the satisfied-skip ({@link
   * #KIND_PARTIAL_EXIT_RETRY_SKIPPED_SATISFIED}) when the reconciled fill satisfied the intent.
   * v=DEFAULT_VERSION (in-flight pre-F1 workflows) keep the legacy discard-and-retry: the ONLY new
   * commands ({@code getOrderStatus} call + the synthesized PartialExitFilled emit) are strictly
   * behind v&gt;=1, and capturing the existing {@code cancelOrder} return adds no command, so
   * legacy histories replay byte-identically. Rolls independently of the prior session keys.
   */
  private static final String VERSION_EXIT_CANCEL_TERMINAL_RECONCILE =
      "partial-exit-cancel-terminal-state-reconcile-v1";

  /**
   * Phase 1 (PLAN-2026-06-30-flatten-fillrace-and-killswitch-realized) replay gate. Generalizes the
   * proven F1/#503 broker-authoritative-reconcile pattern from {@link
   * #processOne(PartialExitRequest)}'s timeout branch to the FLATTEN path. Pre-this-patch the
   * flatten TTL-timeout branch (and the #481 next-session retry loop) awaited ONLY on {@code
   * lastFillEvent}: on timeout it best-effort {@code exec.cancelOrder(flattenIntentKey)} and
   * DISCARDED the return, then emitted {@code EodForceFlattenFailed} and stayed alive. If the
   * broker had actually FILLED the flatten SELL but the async {@code onFill} signal was lost/late,
   * {@code remainingQty} never zeroed → the PositionWorkflow stayed phantom-alive and the
   * chandelier re-fired the flatten forever (the 2026-06-30 DRAM/INTC/TSLA incident). v&gt;=1
   * captures the cancel return and reconciles the AUTHORITATIVE broker terminal state via {@link
   * #terminalFillFrom(OrderIntentResult)} (defense-in-depth {@code exec.getOrderStatus} fallback
   * only when the cancel did not surface the fill AND no {@code onFill} buffered), books it through
   * the existing {@link #emitExitFill(String, FillSignalPayload)} so exactly ONE PartialExitFilled
   * is emitted and {@code remainingQty} decrements from broker truth, and returns {@code true} when
   * {@code remainingQty == 0} to break the re-arm loop. v=DEFAULT_VERSION (in-flight pre-this-patch
   * workflows — including the already-stuck DRAM/INTC/TSLA lots) keep the legacy discard-and-stay-
   * alive: the ONLY new commands ({@code getOrderStatus} call + the synthesized PartialExitFilled /
   * {@code kindDone} emits) are strictly behind v&gt;=1, and capturing the existing {@code
   * cancelOrder} return adds no command, so legacy flatten-timeout histories replay
   * byte-identically (operator follow-up #1: the deploy does NOT retroactively un-stick them).
   * Rolls independently of the sibling flatten markers.
   */
  private static final String VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE =
      "flatten-cancel-terminal-state-reconcile-v1";

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
   * PLAN-2026-07-23 Phase 2 replay gate. A PositionWorkflow can be started — via recon adoption —
   * on a contract's OWN expiry day AFTER every scheduled-flatten instant has already passed (the
   * 2026-07-22 staging_paper zombie: adopted 14:45 ET, its expiry-close/expiry-lead instants
   * seconds behind). Every {@code durationUntil*} then computes a &le;0 duration and NO timer arms,
   * so {@code eodFired}/{@code expiryFired}/{@code expiryLeadFired} can never latch and {@code
   * run()} blocks forever in the main loop on a signal that never comes — an immortal workflow
   * holding a delisted, unpriceable contract that fail-closes the account cap every heartbeat. When
   * the contract has PHYSICALLY EXPIRED and NOT ONE terminal timer armed, close it worthless now
   * (the P&amp;L-neutral {@link #maybeCloseWorthlessAtExpiry} path) instead of hanging. Gated
   * because it appends an audit + early return to a workflow whose in-flight histories must replay
   * byte-identically; at {@code DEFAULT_VERSION} the guard is skipped (the only new v=0 command is
   * this appended getVersion marker).
   */
  private static final String VERSION_EXPIRE_WORTHLESS_NO_TIMER = "expire-worthless-no-timer-v1";

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
   * Phase 2 (PLAN-2026-07-12-watchlist-flatten-floor-and-expired-readoption, concern B1) replay
   * gate. The original {@link #VERSION_EXPIRE_WORTHLESS} worthless-close fires ONLY for a {@code
   * reason=="expiry"} flatten. A lot whose TERMINAL scheduled flatten is {@code reason=eod} or
   * {@code reason=expiry_lead} on a PHYSICALLY-expired contract that rests unfilled (a 0DTE
   * deep-OTM no-bid lot — the 2026-07-10 AMZN incident) therefore never closed and lingered as a
   * phantom until manual termination. This marker broadens {@link #maybeCloseWorthlessAtExpiry} to
   * worthless-close on {@code eod}/{@code expiry_lead} too, but ONLY on a physically-expired
   * contract (the OCC expiry-date check remains the real guard, unchanged).
   *
   * <p>Under {@code DEFAULT_VERSION} (in-flight histories recorded before this deploy) the
   * broadened {@code eod}/{@code expiry_lead} branch keeps returning {@code false} (legacy
   * stay-ALIVE) so those histories replay byte-identically — the ONLY new command on v=0 is this
   * appended getVersion marker. The existing {@code reason=="expiry"} path keeps reading {@link
   * #VERSION_EXPIRE_WORTHLESS} at the same point, so its command stream is untouched. Long-lived
   * multi-day workflow: in-flight executions replay across the redeploy, so the gate is mandatory.
   */
  private static final String VERSION_EXPIRE_WORTHLESS_SCHEDULED = "expire-worthless-scheduled-v1";

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
   * Edited-signal supersede (F1) replay gate. The {@code supersede} signal handler buffers a
   * directive and the main loop drains it into {@link #processSupersede(SupersedeDirective)}, which
   * emits {@link #KIND_SUPERSEDED_BY_CORRECTION} then drives the shared {@link
   * #flattenRemaining(String)} cancel-then-market-sell with reason {@code bto_corrected}. ALL of
   * those are new commands. v=DEFAULT_VERSION (in-flight pre-F1 PositionWorkflows replaying across
   * a redeploy) take the byte-identical pre-F1 path: the signal handler is a no-op (no buffer
   * append) and the main-loop drain branch is unreachable (the pending deque stays empty and the
   * await predicate's {@code !pendingSupersedes.isEmpty()} term is constant-false), so the recorded
   * command stream is unchanged. Read ONCE in the handler (a stable scope per delivered signal) and
   * gating the drain branch behind the same marker. Mirrors {@link #VERSION_RISK_BREACH} /​ {@link
   * #VERSION_FORCE_CLOSE}.
   */
  private static final String VERSION_BTO_CORRECTION_SUPERSEDE = "bto-correction-supersede-v1";

  /**
   * Phase 4: maximum number of NEXT-SESSION retry attempts for an unfilled force-flatten before the
   * workflow gives up retrying, emits the terminal {@link #KIND_FLATTEN_RETRY_EXHAUSTED} page, and
   * falls back to the legacy await-late-fill (stay-alive) behaviour. Small constant: a flatten that
   * cannot fill across three consecutive sessions needs operator attention, not unbounded
   * re-arming.
   */
  private static final int MAX_FLATTEN_RETRY_SESSIONS = 3;

  /**
   * Phase 1 (PLAN-2026-06-25-trading-remediation) replay gate. When a partial-target exit's {@code
   * placeOrder} retries are exhausted, the {@link #VERSION_EXIT_PLACE_FAILURE_GUARD} v&gt;=1 catch
   * emits {@link #KIND_PARTIAL_EXIT_PLACE_FAILED} (which pages) and {@code return}s out of {@link
   * #processOne(PartialExitRequest)} — the intended partial profit-take is silently DROPPED (the
   * 2026-06-25 QQQ 720p 09:43 incident). Under v&gt;=1 the catch ADDITIONALLY arms a one-shot timer
   * to the NEXT market-session open ({@link
   * MarketCalendarActivities#durationUntilNextRthOpenEt()}); on wake the main loop re-enqueues the
   * failed partial into {@code pendingExits} so the normal {@code processOne} cycle re-attempts it
   * with a fresh {@code :retry-N}-suffixed intent key, bounded to {@link
   * #MAX_PARTIAL_PLACE_RETRY_SESSIONS}. This is a DEFERRED re-drive — NOT the same-session reprice
   * loop ({@code VERSION_EXIT_STEPPED_REPRICE}) — so the lot stays free for a later STC /
   * chandelier / EOD flatten between attempts (a discretionary partial must never block the main
   * loop).
   *
   * <p>v=DEFAULT_VERSION (in-flight workflows started before this patch, incl. the live QQQ-era
   * pods) keep the exact current {@code return} so their recorded histories replay byte-identically
   * — every new command (the next-open Activity call, its timer, the per-attempt re-enqueue, and
   * the PartialExitRetry* audits) is strictly behind this gate. The gate is read ONCE at the catch
   * scope, mirroring how {@link #VERSION_FLATTEN_RETRY_NEXT_SESSION} is read once. This is a
   * long-lived multi-day workflow: in-flight executions replay across a redeploy, so the gate is
   * mandatory.
   */
  private static final String VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION =
      "partial-exit-place-retry-next-session-v1";

  /**
   * Issue #735: Alpaca reports {@code filled_qty} as the CUMULATIVE quantity filled so far on a
   * broker order, NOT the increment since the last event. Every booking site except {@link
   * #bookFlattenDelta} subtracted it as a delta, so a single order filling 2-then-5 booked 7
   * against a 5-lot and drove {@code remainingQty} NEGATIVE (breaking R-AA-1), while the converse —
   * a first partial treated as a completed exit — OVERSTATES the lot and produces the oversized
   * flatten of #716.
   *
   * <p>Under v&gt;=1 every exit booking is converted to a clamped DELTA in {@link
   * #emitExitFill(String, FillSignalPayload)} against a per-broker-order ledger. The ledger is
   * keyed on {@code broker_order_id} rather than {@code intent_key} because the broker order is the
   * unit Alpaca actually cumulates over (an intent_key is a proxy that a {@code :retry-N} placement
   * breaks).
   *
   * <p>MUST be version-gated: a duplicate report now books a delta of 0 and emits NOTHING, where
   * v=0 emitted a full {@code PartialExitFilled}. That is a command-COUNT divergence, not a payload
   * difference — replay-fatal for the in-flight executions without this gate. (Temporal 1.27 replay
   * ignores activity INPUT payloads, which is why the qty change alone would not have needed one.)
   */
  private static final String VERSION_EXIT_CUMULATIVE_LEDGER = "exit-cumulative-ledger-v1";

  /**
   * Issue #735 Phase 2: {@code processOne} booked the FIRST fill, released the exit latch and
   * returned — treating any fill as a completed exit. That was correct while every fill the exit
   * path could see was terminal (the WS has delivered nothing for ~11 weeks and {@code FillPoller}
   * structurally cannot dispatch a partial, so today's quantities are shape-preserving). The moment
   * real partials arrive it silently ABANDONS the remainder: a full-close STC filling 2 of 5
   * releases the latch with 3 outstanding, nothing retries it, and the lot sits unmanaged until EOD
   * — while {@code remainingQty} overstates a position the broker may have already sold, which is
   * the oversized-flatten shape of #716.
   *
   * <p>Under v&gt;=1 the exit drains successive partials of the SAME resting order until it reaches
   * the {@code targetRemaining} captured before placement, a pre-emption wins, or the TTL expires
   * (which then drives the EXISTING cancel/retry machinery instead of a silent completion).
   *
   * <p>Gated because the drain loop changes the command sequence: each additional partial books
   * another {@code PartialExitFilled}, and an unmet target now reaches the timeout branch that v=0
   * never entered after a fill.
   */
  private static final String VERSION_EXIT_PARTIAL_AWAIT_LOOP = "exit-partial-await-loop-v1";

  /**
   * Issue #738: refuse to book a fill of our OWN ENTRY order as an exit.
   *
   * <p>{@code FillSignalPayload} carries only {@code brokerOrderId / filledQty / avgFillPrice /
   * filledAt} — no intent key, no side — and {@code onFill} does nothing but latch it. The workflow
   * retained only {@code currentInFlightBrokerOrderId} (the EXIT placement), so it could not
   * recognise its own entry fill.
   *
   * <p>{@code processOne} clears {@code lastFillEvent} immediately before placing (:3023), which
   * discards a stale entry fill parked BEFORE the placement — and masks this most of the time. The
   * race is the window AFTER placement: an entry fill landing while the exit is resting is
   * indistinguishable from the exit filling. Reproduced — a 50-lot entry that confirms at 10 and
   * then completes mid-await books {@code min(50, 10)} = 10 and drives {@code remainingQty} to ZERO
   * while the broker holds 50, so the workflow reports FLAT and stops managing the lot.
   *
   * <p>That window is exactly when a partially-filled entry is most likely to complete: the price
   * came back, which is also what prompted the STC.
   *
   * <p>Gated: skipping a booking removes a {@code PartialExitFilled} audit command from the stream.
   */
  private static final String VERSION_ENTRY_FILL_NOT_AN_EXIT = "entry-fill-not-an-exit-v1";

  /**
   * Issue #762: an AUTOMATED daily-loss breach must not liquidate a position whose horizon outlives
   * the breaker's.
   *
   * <p>Observed live 2026-08-19: prod-kipark's per-strategy daily-loss switch tripped on a −$4,050
   * loss produced entirely by a **6-DTE** SPY put, and the cascade market-flattened every open
   * position — including {@code DRAM 270319C00100000} at **212 DTE**. A six-day contract's loss
   * liquidated a seven-month contract.
   *
   * <p>A DAILY breaker's window is one session. A 212-DTE position's P&amp;L on any given day is
   * noise against its holding period, and it has its OWN controls — an armed chandelier trail, the
   * EOD sweep, the expiry flatten. On prod_real the same contract carries a trail ~25% away; the
   * cascade would market-sell it before that trail ever acted.
   *
   * <p>Scope, deliberately narrow: this exempts ONLY automated breaches. An operator-initiated risk
   * breach and {@code force_close} always flatten everything — operator intent wins, always. The
   * trip itself is unaffected: the switch still halts new entries for the session, so the
   * protective purpose is preserved without the liquidation.
   */
  private static final String VERSION_RISK_BREACH_EXEMPT_LONG_DATED =
      "risk-breach-exempt-long-dated-v1";

  /**
   * Days-to-expiry above which an AUTOMATED risk breach declines to flatten (issue #762).
   *
   * <p>90 sits deliberately between the real positions this fired on: SPY at 6 DTE and TSLA at 30
   * DTE are ordinary short-dated copytrade legs and still flatten; DRAM at 212 DTE is categorically
   * different and does not. A hard constant rather than config on purpose — adding a
   * strategy-config field would regenerate three artifacts (contract/java, contract/python, the
   * dashboard field manifest) for a value nobody has asked to tune yet.
   */
  private static final long RISK_BREACH_EXEMPT_DTE_DAYS = 90L;

  /** #762 audit: an automated breach declined to flatten a long-dated position. */
  private static final String KIND_RISK_BREACH_FLATTEN_SKIPPED_LONG_DATED =
      "RiskBreachFlattenSkippedLongDated";

  /**
   * Bound on {@link #exitBookedByOrder}. Workflow fields are NOT serialized into Temporal history
   * (history holds commands and events; state is reconstructed by replay), so an unbounded ledger
   * is a worker-heap concern rather than a history-bloat one — but {@code PositionWorkflowImpl} has
   * no continue-as-new and an overnight position accumulates a broker order per exit attempt, so it
   * is bounded anyway. Oldest-first eviction is safe: a broker order old enough to fall out of a
   * 64-entry window is long terminal, and the {@code Math.min(delta, remainingQty)} clamp still
   * prevents an evicted key's re-report from over-booking.
   */
  private static final int EXIT_LEDGER_MAX_ORDERS = 64;

  /**
   * Phase 1: maximum number of NEXT-SESSION re-drives of a failed-to-place partial-target exit
   * before the workflow gives up retrying, emits the terminal {@link
   * #KIND_PARTIAL_EXIT_RETRY_EXHAUSTED} page, and lets normal exits govern the lot. A partial
   * profit-take that missed its target is far less urgent than an unflattened EOD lot, so the
   * default is a SINGLE re-attempt at the next open — not an unbounded loop.
   */
  private static final int MAX_PARTIAL_PLACE_RETRY_SESSIONS = 1;

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

  /**
   * Issue #738: the broker order id of the ENTRY order, captured at confirm. The ONLY thing that
   * lets this workflow recognise a fill of its own entry — the payload carries no side and no
   * intent key.
   */
  private String entryBrokerOrderId;

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

  /**
   * Phase 1 (PLAN-2026-06-30) review fix: the SHARED per-flatten-key booked ledger. Both reconcile
   * sites — {@link #flattenRemaining(String)}'s TTL-timeout branch AND the run()-tail #481
   * retry-loop reconcile — poll the SAME flatten intent_key via the cancel-return / {@code
   * getOrderStatus}, which reports the resting order's CUMULATIVE filledQty. Booking the cumulative
   * qty at each site independently double-counts one broker fill (over-decrements {@code
   * remainingQty}, emits two PartialExitFilled for one fill, and can drive {@code remainingQty}
   * NEGATIVE). This (key, qty) pair records how much of the CURRENT key each site has already
   * booked so the other site books only the un-booked delta. Only one flatten key is active at a
   * time (a fresh :retry-N key rolls in on {@code flattenRetrySessions++}), so a single pair
   * suffices. The ledger resets whenever the active key changes (see {@link #bookFlattenDelta}).
   * Both default null/0 and are written only under {@link
   * #VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE} v&gt;=1, so legacy histories replay unchanged.
   */
  /**
   * Issue #735 (v&gt;=1 only): per-broker-order cumulative-booked ledger. Maps {@code
   * broker_order_id} (falling back to the exit's signal_id when the broker id is absent on a
   * synthesized fill) to the quantity already booked for that order, so a CUMULATIVE broker report
   * is converted to the un-booked delta exactly once. Bounded by {@link #EXIT_LEDGER_MAX_ORDERS}.
   */
  private final Map<String, Long> exitBookedByOrder = new LinkedHashMap<>();

  private String flattenBookedKey;

  private long flattenBookedQty;

  /**
   * Phase 1 (PLAN-2026-06-25-trading-remediation): the failed-to-place partial-target exit latched
   * for a DEFERRED next-session re-drive, armed in {@link #processOne(PartialExitRequest)}'s
   * place-failure catch under {@link #VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION} v&gt;=1. The
   * one-shot next-open timer sets {@code partialPlaceRetryArmed}; the main loop then re-enqueues
   * {@code partialPlaceRetryPending} into {@code pendingExits} so the normal {@code processOne}
   * cycle re-attempts it. {@code partialPlaceRetrySessions} counts the re-drives so the budget
   * terminates at {@link #MAX_PARTIAL_PLACE_RETRY_SESSIONS}; {@code partialPlaceRetryAttempts} maps
   * a signal_id to its re-drive count so the re-driven intent key carries a distinct {@code
   * :retry-N} suffix (no two attempts reuse a broker client_order_id — the same fix as the
   * flatten/{@code :reprice-N} pattern). All stay at their zero/null/empty defaults unless a
   * partial placeOrder actually fails under v&gt;=1, so they are replay-neutral for legacy
   * histories.
   */
  private boolean partialPlaceRetryArmed;

  private PartialExitRequest partialPlaceRetryPending;

  private int partialPlaceRetrySessions;

  private final LinkedHashMap<String, Integer> partialPlaceRetryAttempts = new LinkedHashMap<>();

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
   * Workflow-clock time a tick was last DRAINED, stamped at the route fork regardless of what is
   * armed — distinct from {@link #lastTickAt}, which is the quote's own timestamp and is only
   * written while the trail is armed. Observation-only: nothing reads it yet. See {@link
   * TrailingState#lastTickObservedAt()} for why the age must be computed by the caller.
   */
  private OffsetDateTime lastTickObservedAt;

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

  /**
   * Edited-signal supersede (F1): buffered supersede directives. Same pattern as
   * pendingRiskBreaches/pendingForceCloses — the {@code supersede} signal handler only enqueues
   * (under VERSION_BTO_CORRECTION_SUPERSEDE v>=1); the main loop drains and acts. Stays empty on
   * v=DEFAULT_VERSION (the handler no-ops), so the await predicate term is constant-false and the
   * recorded command stream is unchanged on replay.
   */
  private final ArrayDeque<SupersedeDirective> pendingSupersedes = new ArrayDeque<>();

  /** Internal directive emitted by the force_close Update handler into the main loop. */
  private record ForceCloseDirective(String operatorId, String reason, String exitSignalId) {}

  /**
   * Operator "Trim" directives buffered by the {@code partial_close} Update handler. Buffered
   * (rather than synthesizing the {@link PartialExitRequest} in-handler) for the same reason
   * force_close buffers: the Update can land before {@code run()} has assigned {@code input}, and
   * the synthesized request carries the tenant/strategy from it. The main loop converts and
   * enqueues onto {@code pendingExits}, where {@code input} is guaranteed non-null.
   */
  private final ArrayDeque<PartialCloseDirective> pendingPartialCloses = new ArrayDeque<>();

  /** Internal directive emitted by the partial_close Update handler into the main loop. */
  private record PartialCloseDirective(
      String operatorId, String reason, double fraction, String exitSignalId) {}

  /**
   * Edited-signal supersede (F1) directive: the corrected (replacement) leg's identifiers carried
   * by the parent's supersede signal, recorded on the child-side {@link
   * #KIND_SUPERSEDED_BY_CORRECTION} audit so every auto-cancel is traceable to the BTO that
   * superseded it.
   */
  private record SupersedeDirective(String correctedSignalId, String correctedOcc) {}

  /**
   * Edited-signal supersede (F1) guardrail field: the deterministic instant the position was
   * confirmed (first entry fill). Reported by {@link #positionState()} so the parent's supersede
   * check can enforce the 120s correction-window guardrail against the prior leg's REAL entry time.
   * Null until {@code positionConfirmed} latches.
   */
  private OffsetDateTime entryAt;

  /**
   * Edited-signal supersede (F1) guardrail field: latched true the first time a partial-exit fill
   * decrements {@code remainingQty} (see {@link #emitExitFill}). Reported by {@link
   * #positionState()} so the supersede check NEVER auto-cancels a leg that has already partially
   * exited — only an untouched just-filled leg may be superseded.
   */
  private boolean partialExited;

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
      // F1 supersede guardrail: stamp the confirm instant (deterministic Workflow clock).
      this.entryAt = workflowNow();
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
    // PLAN-2026-07-23: gate the no_progress_time_stop consumer on the target not having fired. Read
    // once here (mirroring watchlistExitVersion) and consulted in the main-loop await predicate and
    // the exit-backstop flatten branch below. DEFAULT_VERSION keeps the current unconditional fire.
    int timestopPretargetOnlyVersion =
        Workflow.getVersion(VERSION_TIMESTOP_PRETARGET_ONLY, Workflow.DEFAULT_VERSION, 1);
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

    // PLAN-2026-07-23 Phase 2: track whether each terminal-flatten timer actually armed. A timer is
    // NOT armed when its computed duration is <=0 (its instant already passed) — the condition the
    // expiry-day zombie hits on every one of them. Read once below to decide the worthless-close.
    boolean eodTimerArmed = false;
    boolean expiryTimerArmed = false;
    boolean expiryLeadTimerArmed = false;

    if (armEodTimer && !eodIn.isZero() && !eodIn.isNegative()) {
      Promise<Void> eodTimer = Workflow.newTimer(eodIn);
      eodTimer.thenApply(
          v -> {
            eodFired = true;
            return null;
          });
      eodTimerArmed = true;
    }
    if (!expiryIn.isZero() && !expiryIn.isNegative()) {
      Promise<Void> expiryTimer = Workflow.newTimer(expiryIn);
      expiryTimer.thenApply(
          v -> {
            expiryFired = true;
            return null;
          });
      expiryTimerArmed = true;
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
        expiryLeadTimerArmed = true;
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
      // #738: remember WHICH broker order is ours on the entry side, so a later fill of it can
      // never be mistaken for an exit fill.
      this.entryBrokerOrderId = lastFillEvent.getBrokerOrderId();
      this.positionConfirmed = true;
      // F1 supersede guardrail: stamp the confirm instant (deterministic Workflow clock).
      this.entryAt = workflowNow();
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

    // PLAN-2026-07-23 Phase 2: guard against the immortal expiry-day zombie. If the contract has
    // PHYSICALLY EXPIRED and NOT ONE terminal-flatten timer armed (every durationUntil* was <=0
    // because the workflow was adopted after its own expiry instants), the loop below has no fired
    // flag that can ever latch and would block forever, leaving a delisted lot that fail-closes the
    // account cap. Close it worthless now (P&L-neutral, reason=worthless_expiry) and terminate.
    // Read the gate once at this stable scope (reached on every path that would enter the loop); at
    // DEFAULT_VERSION the whole guard is skipped so in-flight histories replay byte-identically.
    int expireWorthlessNoTimerVersion =
        Workflow.getVersion(VERSION_EXPIRE_WORTHLESS_NO_TIMER, Workflow.DEFAULT_VERSION, 1);
    if (expireWorthlessNoTimerVersion >= 1
        && remainingQty > 0
        && !eodTimerArmed
        && !expiryTimerArmed
        && !expiryLeadTimerArmed
        && expiryDate != null
        && !expiryDate.isAfter(currentEtDate())
        && maybeCloseWorthlessAtExpiry("expiry")) {
      return Workflow.getInfo().getWorkflowId();
    }

    while (remainingQty > 0 && !eodFired && !expiryFired && !expiryLeadFired) {
      Workflow.await(
          () ->
              !pendingExits.isEmpty()
                  || !pendingArms.isEmpty()
                  || !pendingTicks.isEmpty()
                  || !pendingRiskBreaches.isEmpty()
                  || !pendingForceCloses.isEmpty()
                  // Wake on a buffered operator trim. Predicate-only addition (not a recorded
                  // command); the deque is empty for every legacy history, whose dispatchers never
                  // called partial_close — so this term is constant-false on replay.
                  || !pendingPartialCloses.isEmpty()
                  // F1: wake on a buffered supersede. Constant-false on v=DEFAULT_VERSION (the
                  // handler never appends at that version), so this predicate term is
                  // replay-neutral
                  // for legacy histories.
                  || !pendingSupersedes.isEmpty()
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
                  // PLAN-2026-07-23: post-target the time-stop is ignored (Fork A). Narrowed in
                  // lock-step with the :flatten branch so a post-target fire does not wake the loop
                  // to a no-op. At DEFAULT_VERSION this stays the plain exitTimeStopFired term.
                  || (exitTimeStopFired && !(timestopPretargetOnlyVersion >= 1 && exitTargetFired))
                  || exitFeedStaleFired
                  // Plan-2A R-AA-1: an in-loop flatten (risk_breach/force_close/chandelier) whose
                  // bounded limit rested unfilled leaves the workflow alive; wake on a LATE fill of
                  // that resting order so it drains rather than hanging open. Predicate-only — not
                  // a
                  // recorded command, so this addition is replay-neutral for v=0 histories.
                  || (flattenAwaitingLateFill && lastFillEvent != null)
                  // Phase 1 (PLAN-2026-06-25-trading-remediation): wake on the next-session
                  // re-drive timer for a failed-to-place partial so the main loop re-enqueues it
                  // into pendingExits. Predicate-only — partialPlaceRetryArmed latches only under
                  // VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION v>=1, where the timer was armed — so
                  // this addition is replay-neutral for v=0 histories.
                  || partialPlaceRetryArmed);
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
      if (exitStopFireRequested
          // PLAN-2026-07-23: the no_progress_time_stop is a PRE-take-profit stalled-breakout guard
          // (per its documented contract). Once the target fires and the chandelier trail is armed,
          // the runner is governed by the trail giveback + breakeven stop + EOD/expiry backstops
          // (Fork A) — the time-stop no longer flattens it. Version-gated: at DEFAULT_VERSION this
          // stays the current unconditional fire so in-flight histories replay byte-identically.
          || (exitTimeStopFired && !(timestopPretargetOnlyVersion >= 1 && exitTargetFired))
          || exitFeedStaleFired) {
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
      // F1: a supersede (auto-cancel of a corrected wrong-expiry leg) is operator/automation intent
      // of the same priority class as force_close — drain it ahead of the normal STC pipeline. The
      // deque is only ever non-empty under VERSION_BTO_CORRECTION_SUPERSEDE v>=1 (the handler
      // no-ops at DEFAULT_VERSION), so this branch is unreachable on legacy-history replay.
      if (!pendingSupersedes.isEmpty()) {
        SupersedeDirective sd = pendingSupersedes.poll();
        processSupersede(sd);
        continue;
      }
      // Drain arms first so a co-arriving tick sees armed=true.
      while (!pendingArms.isEmpty()) {
        processArm(pendingArms.poll());
      }
      // Then drain ticks. For a watchlist-exit-active position (exit armed, or the target already
      // armed the runner's trail) processExitTick owns the tick: it evaluates the bid-based stop /
      // target AND feeds the chandelier trail on the BID (per spec). Otherwise processTick runs the
      // copytrade chandelier path unchanged on the mid. NOT a smoothed mid — the schema claimed a
      // 5-10s smoothing window that has never existed (corrected 2026-08-16); this is a plain
      // (bid+ask)/2 from one REST snapshot, so a one-sided NBBO collapse halves it in a single
      // tick.
      while (!pendingTicks.isEmpty()) {
        PremiumTick t = pendingTicks.poll();
        // Stamp BEFORE the route fork and regardless of what is armed. This is the only point both
        // paths pass through, and it is deliberately not inside either handler: processTick
        // early-returns on !trailingArmed, so lastTickAt is blank exactly when a position is
        // unarmed, and processExitTick records only a boolean. Nothing else answers "when did we
        // last hear anything at all" — which is the question a staleness backstop has to ask.
        // Observation-only in this phase: no timer reads it yet.
        lastTickObservedAt = workflowNow();
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
      // Phase 1 (PLAN-2026-06-25-trading-remediation): the next-session re-drive timer for a
      // failed-to-place partial fired. Count the re-drive, bump the per-signal attempt so the
      // re-attempt's intent key carries a distinct :retry-N suffix (no duplicate client_order_id),
      // emit the informational per-attempt marker, and re-enqueue the latched partial into
      // pendingExits so the normal processOne cycle re-attempts it. The latch is one-shot; the
      // re-attempt's own place-failure (if any) re-arms under the bounded budget.
      if (partialPlaceRetryArmed) {
        partialPlaceRetryArmed = false;
        PartialExitRequest pending = partialPlaceRetryPending;
        partialPlaceRetryPending = null;
        if (pending != null) {
          partialPlaceRetrySessions++;
          int attempt = partialPlaceRetryAttempts.merge(pending.getSignalId(), 1, Integer::sum);
          auditLog(
              KIND_PARTIAL_EXIT_RETRY_REQUESTED,
              subject(
                  "signal_id",
                  pending.getSignalId(),
                  "retry_attempt",
                  attempt,
                  "source_premium",
                  "next_session_place_retry",
                  "intent_key",
                  Workflow.getInfo().getWorkflowId()
                      + ":exit:"
                      + pending.getSignalId()
                      + ":retry-"
                      + attempt));
          pendingExits.add(pending);
        }
        continue;
      }
      // Operator "Trim" (partial_close Update): convert each buffered directive into the synthetic
      // MARKET PartialExitRequest and enqueue it onto pendingExits. Done HERE (not in the handler)
      // because `input` is guaranteed assigned inside the main loop. No `continue`: the trim is a
      // normal partial, so it drains through the FIFO pendingExits pipeline below — unlike
      // force_close/risk_breach, which pre-empt it because they flatten the whole lot.
      while (!pendingPartialCloses.isEmpty()) {
        PartialExitRequest trim = operatorTrimRequest(pendingPartialCloses.poll());
        // Register the synthetic id with the SAME dedupe set the partialExit signal handler uses.
        // Enqueuing here bypasses that handler, so without this the trim would have no duplicate
        // backstop at all behind the id-uniqueness assumption. add() returning false means an
        // identical id already ran — drop it rather than place a second broker order.
        if (!processedSignalIds.add(trim.getSignalId())) {
          auditLog(
              KIND_EXIT_DUPLICATE_SUPPRESSED,
              subject("signal_id", trim.getSignalId(), "note", "duplicate_operator_trim"));
          continue;
        }
        pendingExits.add(trim);
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
        // Phase 1 (PLAN-2026-06-30): read the broker-reconcile gate ONCE here, outside the loop, so
        // the command count is stable (mirrors retryVersion above and the flattenRemaining read).
        int flattenReconcileVersion =
            Workflow.getVersion(
                VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE, Workflow.DEFAULT_VERSION, 1);
        // Guardrail #5 (cumulative-vs-delta): getOrderStatus returns the resting order's CUMULATIVE
        // filledQty. An onFill drain (below) may ALREADY have booked a partial of THIS key, and —
        // the review-fix root cause — flattenRemaining's OWN timeout-branch reconcile may have
        // already booked a partial of this SAME key before the loop was even entered. Booking the
        // cumulative qty on top would DOUBLE-count → over-decrement remainingQty → over-sell /
        // naked
        // short (or drive remainingQty negative → PositionClosed with remaining < 0). Both this
        // site
        // and flattenRemaining now share the instance-scoped flattenBookedKey/flattenBookedQty
        // ledger (via bookFlattenDelta), so each books only the positive un-booked delta of the
        // CURRENT key; the ledger resets on a key roll (fresh :retry-N placement).
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
            // The intent_key of the order currently resting at the broker: <wf>:exit:flatten-
            // <reason>[:retry-N], N = flattenRetrySessions at placement. Guardrail #1: computed
            // with
            // the CURRENT (pre-increment) flattenRetrySessions so it matches the order actually
            // resting. Used by BOTH the onFill drain and the getOrderStatus reconcile below to key
            // the shared booked ledger; getting it wrong would reconcile the WRONG key while a live
            // SELL still rests → double SELL → naked short.
            String restingKey =
                Workflow.getInfo().getWorkflowId()
                    + ":exit:flatten-"
                    + reason
                    + (flattenRetrySessions > 0 ? ":retry-" + flattenRetrySessions : "");
            // Wake on either a late fill of the resting order OR the next-session retry timer.
            Workflow.await(() -> lastFillEvent != null || retryFlattenArmed);
            if (lastFillEvent != null) {
              // A late fill drained (some of) the resting order before the next session — apply it
              // and re-evaluate; no retry needed for what already filled. Advance the shared ledger
              // for THIS key by the incremental onFill qty (resetting on a key roll) so a
              // subsequent
              // getOrderStatus reconcile of the SAME key — here OR back in flattenRemaining — books
              // only the delta, not the cumulative total.
              if (!restingKey.equals(flattenBookedKey)) {
                flattenBookedKey = restingKey;
                flattenBookedQty = 0L;
              }
              flattenBookedQty += lastFillEvent.getFilledQty();
              emitExitFill("flatten-" + reason, lastFillEvent);
              lastFillEvent = null;
              continue;
            }
            // Phase 1 (PLAN-2026-06-30): the next-session timer woke us with lastFillEvent == null.
            // Before spending a retry (re-placing a NEW live SELL), reconcile broker truth on the
            // RESTING order — the onFill for its fill may have been lost/late.
            boolean bookedProgressThisPoll = false;
            if (flattenReconcileVersion >= 1 && lastFillEvent == null) {
              OrderIntentResult restingStatus = null;
              try {
                restingStatus = exec.getOrderStatus(restingKey);
              } catch (RuntimeException ignored) {
                // Best-effort; a failed recheck falls through to the normal retry below.
              }
              FillSignalPayload terminalFill = terminalFillFrom(restingStatus);
              if (terminalFill != null) {
                // Review fix: book ONLY the un-booked delta of this key's CUMULATIVE fill via the
                // shared ledger (clamped to remainingQty, lastFillEvent cleared inside). delta may
                // be 0 when the fill was already fully accounted (booked by an onFill drain or by
                // flattenRemaining's own reconcile of this SAME key).
                long booked = bookFlattenDelta(reason, restingKey, terminalFill);
                bookedProgressThisPoll = booked > 0;
              }
            }
            // Blocker B fix: only skip the retry (continue) when there was REAL progress this poll
            // (delta > 0 → the resting order advanced) OR the lot is now fully drained
            // (remainingQty == 0 → the outer while exits and PositionClosed emits). When the
            // resting
            // order is TERMINAL (e.g. CANCELLED-with-partial-fill, so its cumulative filledQty
            // never
            // changes again) and its fill is fully accounted (delta == 0) but a residual remains,
            // do
            // NOT poll it forever — fall through to the genuine retry path below and re-place a
            // FRESH order for the residual under a NEW key. Otherwise flattenRetrySessions would
            // never advance and the residual would be stuck (the exact "loops forever, never
            // closes"
            // failure this PR eliminates, moved down a level).
            if (bookedProgressThisPoll || remainingQty == 0) {
              // Re-evaluate remainingQty at the loop top WITHOUT incrementing flattenRetrySessions
              // or re-placing a new flatten order — a stuck lot self-heals from broker truth.
              continue;
            }
            // Next-session timer woke us with the lot still genuinely unfilled (or the resting
            // order
            // is terminal and cannot fill further): re-attempt the flatten. The key rolls to
            // :retry-<new N>, so reset the shared per-key booked ledger.
            flattenRetrySessions++;
            flattenBookedKey = null;
            flattenBookedQty = 0L;
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
  public void supersede(String correctedSignalId, String correctedOcc) {
    int v = Workflow.getVersion(VERSION_BTO_CORRECTION_SUPERSEDE, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      // Pre-F1 replay: no-op so the pending deque stays empty and the main-loop drain branch is
      // unreachable — byte-identical command stream for in-flight pre-F1 PositionWorkflows.
      return;
    }
    // Buffer only — the main loop emits the audit + drives flattenRemaining. Keeps signal handlers
    // free of activity calls (deterministic), matching partialExit / riskBreach.
    pendingSupersedes.add(new SupersedeDirective(correctedSignalId, correctedOcc));
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

  @Override
  public void partialCloseValidator(PartialCloseRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request_required");
    }
    if (request.getOperatorId() == null || request.getOperatorId().isBlank()) {
      throw new IllegalArgumentException("operator_id_required");
    }
    if (request.getReason() == null || request.getReason().isBlank()) {
      throw new IllegalArgumentException("reason_required");
    }
    // Reduce-only by construction: 0 sells nothing and 1.0 is a FULL close, which must go through
    // force_close (whose confirm UI names the whole position). Rejecting here means the operator
    // gets a synchronous 4xx instead of a silent ExitDuplicateSuppressed(bad_fraction) audit.
    if (request.getFraction() == null
        || request.getFraction().doubleValue() <= 0.0
        || request.getFraction().doubleValue() >= 1.0) {
      throw new IllegalArgumentException("fraction_must_be_between_0_and_1_exclusive");
    }
  }

  @Override
  public PartialCloseResult partialClose(PartialCloseRequest request) {
    // No Workflow.getVersion gate. This Update is brand new: no recorded history contains a
    // partial_close invocation, so replaying a legacy history never reaches this handler and its
    // command stream is unchanged. (Same reasoning as CopytradeDeriskWorkflowImpl's reuse of the
    // partialExit signal; a gate here would be an inert marker.)
    // UUID, NOT currentTimeMillis(): Workflow.currentTimeMillis() is the workflow-TASK start time,
    // so two partial_close Updates batched into one workflow task (double submit, two tabs, two
    // users on a multi-user tenant) would mint the SAME id — hence the same exit intent_key — and
    // the second placeOrder would hit the broker with a duplicate client_order_id (422 → zombie
    // journal row → next-session re-drive selling MORE contracts). force_close can share an id
    // harmlessly because its duplicate no-ops on remainingQty==0; a trim leaves the position open,
    // so its duplicate executes. Workflow.randomUUID() is deterministic under replay.
    String exitSignalId = "trim:" + request.getOperatorId() + ":" + Workflow.randomUUID();
    PartialCloseResult result = new PartialCloseResult();
    result.setSchemaVersion(1L);
    result.setExitSignalId(exitSignalId);

    // Update-before-run() race: buffer without auditing (auditLog dereferences `input`). The main
    // loop emits nothing extra — processOne's PartialExitRequested carries the trim's qty math.
    if (input == null) {
      pendingPartialCloses.add(
          new PartialCloseDirective(
              request.getOperatorId(),
              request.getReason(),
              request.getFraction().doubleValue(),
              exitSignalId));
      result.setStatus(PartialCloseResult.Status.ACCEPTED);
      return result;
    }

    // Already drained (only meaningful once the position is confirmed — pre-first-fill remainingQty
    // is legitimately 0). Mirrors the force_close no-op so the dashboard can say "already closed"
    // rather than reporting a trim that will never place an order.
    if (positionConfirmed && remainingQty <= 0) {
      auditLog(
          KIND_OPERATOR_TRIM_NOOP,
          subject(
              "operator_id", request.getOperatorId(),
              "reason", request.getReason(),
              "exit_signal_id", exitSignalId));
      result.setStatus(PartialCloseResult.Status.NOOP_ALREADY_CLOSED);
      return result;
    }

    auditLog(
        KIND_OPERATOR_TRIM_REQUESTED,
        subject(
            "operator_id",
            request.getOperatorId(),
            "reason",
            request.getReason(),
            "exit_signal_id",
            exitSignalId,
            "fraction",
            request.getFraction(),
            "remaining_qty",
            remainingQty));
    pendingPartialCloses.add(
        new PartialCloseDirective(
            request.getOperatorId(),
            request.getReason(),
            request.getFraction().doubleValue(),
            exitSignalId));
    result.setStatus(PartialCloseResult.Status.ACCEPTED);
    return result;
  }

  /**
   * Convert a buffered operator-trim directive into the synthetic {@link PartialExitRequest} that
   * the existing partial-exit pipeline consumes. {@code market=true} makes {@link #processOne}
   * place the SELL at MARKET (exit-NOW, like force_close) instead of resting a ref_premium-seeded
   * limit; {@code ref_premium} is deliberately left null since it would only be the limit seed. The
   * Discord-shaped fields carry the operator identity so the existing audits stay self-describing.
   */
  private PartialExitRequest operatorTrimRequest(PartialCloseDirective d) {
    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(input.getTenantId());
    req.setStrategyId(input.getStrategyId());
    req.setSignalId(d.exitSignalId());
    req.setPositionWorkflowId(Workflow.getInfo().getWorkflowId());
    req.setFraction(BigDecimal.valueOf(d.fraction()));
    req.setMarket(true);
    req.setReason(REASON_OPERATOR_TRIM);
    req.setAuthor(d.operatorId());
    req.setRawLine(d.reason());
    req.setOccurredAt(workflowNow());
    return req;
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
    // #762: decline to liquidate a LONG-DATED position on an AUTOMATED daily-loss breach. The
    // breaker governs one session; this position's horizon outlives it by months and it has its
    // own controls. Operator-initiated breaches are never exempt — see the change-id javadoc.
    if (Workflow.getVersion(VERSION_RISK_BREACH_EXEMPT_LONG_DATED, Workflow.DEFAULT_VERSION, 1) >= 1
        && isAutomatedBreach(payload.getActor())) {
      Long dte = daysToExpiry();
      if (dte != null && dte > RISK_BREACH_EXEMPT_DTE_DAYS) {
        auditLog(
            KIND_RISK_BREACH_FLATTEN_SKIPPED_LONG_DATED,
            subject(
                "reason",
                payload.getReason(),
                "actor",
                payload.getActor(),
                "remaining_qty",
                remainingQty,
                "contract_symbol",
                input.getContractSymbol(),
                "days_to_expiry",
                dte,
                "exempt_above_dte",
                RISK_BREACH_EXEMPT_DTE_DAYS));
        return; // stays ALIVE — trail / EOD / expiry still govern it.
      }
    }
    closeReason = "risk_breach";
    flattenRemaining("risk_breach");
  }

  /**
   * #762: is this breach machine-generated? Automated actors are namespaced {@code auto:*} (e.g.
   * {@code auto:daily_loss}, {@code auto:account_mtm_unavailable}); an operator carries {@code
   * manual:*} or an operator id. Fail CLOSED on anything unrecognised — an unknown actor flattens,
   * because the exemption must never be the default for an actor nobody classified.
   */
  private static boolean isAutomatedBreach(String actor) {
    return actor != null && actor.startsWith("auto:");
  }

  /**
   * #762: days from the workflow's deterministic ET "today" to the managed contract's OCC expiry.
   * {@code null} when the OCC has no parseable expiry — the caller then flattens, fail-closed.
   */
  private Long daysToExpiry() {
    LocalDate expiry = expiryDateFromOcc(input.getContractSymbol());
    if (expiry == null) {
      return null;
    }
    return java.time.temporal.ChronoUnit.DAYS.between(currentEtDate(), expiry);
  }

  /** Main-loop force-close processor. Cancel-then-flatten via the shared flatten helper. */
  private void processForceClose(ForceCloseDirective d) {
    closeReason = "force_close";
    flattenRemaining("force_close");
  }

  /**
   * Edited-signal supersede (F1) main-loop processor. Emits the child-side {@link
   * #KIND_SUPERSEDED_BY_CORRECTION} audit tying THIS wrong-expiry leg to the corrected leg that
   * superseded it, then drives the shared cancel-then-MARKET-sell via {@link
   * #flattenRemaining(String)} with reason {@code bto_corrected}. {@code flattenRemaining} treats
   * {@code bto_corrected} like {@code force_close} (immediacy=true → market exit) so the wrong leg
   * is closed NOW, not rested as a bounded limit. {@code closeReason} is set so the run()-tail
   * close-disposition audit reflects the cause.
   */
  private void processSupersede(SupersedeDirective d) {
    auditLog(
        KIND_SUPERSEDED_BY_CORRECTION,
        subject(
            "entry_signal_id", input.getEntrySignalId(),
            "contract_symbol", input.getContractSymbol(),
            "remaining_qty", remainingQty,
            "corrected_signal_id", d.correctedSignalId(),
            "corrected_option_symbol", d.correctedOcc()));
    closeReason = "bto_corrected";
    flattenRemaining("bto_corrected");
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
    String rejection = chandelierArmRejection(peak, gb);
    if (rejection != null) {
      auditLog(
          KIND_CHANDELIER_ARM_REJECTED,
          subject(
              "reason",
              rejection,
              "source_signal_id",
              p.getSourceSignalId(),
              "peak_premium",
              peak,
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
   * What makes a chandelier arm ILLEGAL, shared by the automatic signal path ({@link #processArm})
   * and the operator Update ({@link #armTrail}). Extracted so the two can never diverge on what
   * they accept: a giveback the operator's button offers but the automatic path would refuse is
   * exactly the kind of split that gets discovered in production.
   *
   * @return the rejection reason, or {@code null} when the arm is legal
   */
  private static String chandelierArmRejection(BigDecimal peak, BigDecimal giveback) {
    if (peak == null || peak.signum() <= 0) {
      return "invalid_peak";
    }
    if (giveback == null || giveback.signum() <= 0 || giveback.compareTo(MAX_GIVEBACK) > 0) {
      return "invalid_giveback";
    }
    return null;
  }

  @Override
  public void armTrailValidator(ArmTrailRequest request) {
    // Validator rejections never enter history — the cheapest possible refusal, and it keeps a
    // malformed operator request from costing a workflow task.
    if (request == null || request.getOperatorId() == null || request.getOperatorId().isBlank()) {
      throw new IllegalArgumentException("operator_id is required");
    }
    BigDecimal gb = request.getGivebackPct();
    if (gb == null || gb.signum() <= 0 || gb.compareTo(MAX_GIVEBACK) > 0) {
      throw new IllegalArgumentException(
          "giveback_pct must be in (0, " + MAX_GIVEBACK + "]: " + gb);
    }
    if (request.getPeakPremium() != null && request.getPeakPremium().signum() <= 0) {
      throw new IllegalArgumentException("peak_premium must be > 0 when supplied");
    }
  }

  @Override
  public ArmTrailResult armTrail(ArmTrailRequest request) {
    // No Workflow.getVersion gate, for the same reason partial_close has none: this Update is brand
    // new, so no recorded history contains an arm_trail invocation and a legacy replay never
    // reaches this handler. A marker here would be inert.
    ArmTrailResult result = new ArmTrailResult();
    result.setSchemaVersion(1L);

    if (trailingArmed) {
      // Idempotent, and deliberately does NOT re-arm: a double-click (or two operators, or two
      // tabs) must never widen a stop that is already protecting this lot. Echoes what is actually
      // in force, not what was asked for. Mirrors processArm's armed-path no-op.
      result.setStatus(ArmTrailResult.Status.ALREADY_ARMED);
      result.setPeakPremium(peakPremium);
      result.setGivebackPct(givebackPct);
      result.setStopPrice(trailStopPrice(peakPremium, givebackPct));
      return result;
    }

    // Anchor: the operator's override if they pinned one, else resolved HERE rather than trusted
    // from the browser, because a page-rendered premium is seconds stale.
    //
    // The original rationale added "and an anchor that is too low sets the stop too low", which
    // reads as a preference for erring HIGH. Do not extend that reasoning: this file's own
    // asymmetry is the opposite way round. A low anchor is ratcheted away by the first tick and
    // self-heals; a high one never does, because peakPremium never falls. Erring high is the
    // unrecoverable direction.
    //
    // The max(lastBid, freshBid) in resolveTrailAnchor is not that mistake either, but NOT because
    // it is a high-water mark — it is not one. `lastBid` is assigned unconditionally on every exit
    // tick, so it is the PREVIOUS bid, and the max is over two adjacent observations ~2s apart.
    // The genuine running max is `exitBidMfe`, and the anchor deliberately does NOT read it: a
    // position that has come off its lifetime high by more than the giveback would arm with a stop
    // above the current bid every time, which fires on the next honest tick. Anchoring on a RECENT
    // price is the point. Do not "tidy" this into exitBidMfe to match a high-water-mark reading.
    //
    // Its real hazard is staleness: with no rolling feed-staleness backstop, a quiet feed freezes
    // lastBid at an arbitrarily old value and it can anchor a stop above where the market now is.
    // Bounded by tick cadence while the feed is alive; unbounded when it is not. That is the
    // silence gap in another guise and closes when that does, not here.
    BigDecimal peak =
        request.getPeakPremium() != null ? request.getPeakPremium() : resolveTrailAnchor();
    BigDecimal gb = request.getGivebackPct();

    String rejection = chandelierArmRejection(peak, gb);
    if (rejection != null) {
      // peak == null here means no anchor could be resolved at all — name that precisely rather
      // than reporting it as a bad peak, because the operator's remedy is different (retry when a
      // quote is available, vs. nothing they can do about a malformed value).
      String reason = peak == null ? "anchor_unresolvable" : rejection;
      auditLog(
          KIND_CHANDELIER_ARM_REJECTED,
          subject(
              "reason", reason,
              "source", "operator",
              "operator_id", request.getOperatorId(),
              "peak_premium", peak,
              "giveback_pct", gb));
      result.setStatus(ArmTrailResult.Status.REJECTED);
      result.setReason(reason);
      return result;
    }

    SubscribePremiumRequest req = new SubscribePremiumRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(input.getTenantId());
    req.setStrategyId(input.getStrategyId());
    req.setContractSymbol(input.getContractSymbol());
    req.setPositionWorkflowId(Workflow.getInfo().getWorkflowId());
    SubscribePremiumResult res = marketData.subscribePremium(req);
    if (res.getStatus() == SubscribePremiumResult.Status.FAILED) {
      // Without a tick feed the trail can never fire, so this MUST reject rather than arm. The
      // signal path only audits here and returns silently; the operator path has someone waiting
      // on an answer, and telling them a stop exists when no feed backs it is the one outcome
      // this feature must never produce.
      auditLog(
          KIND_CHANDELIER_SUBSCRIPTION_FAILED,
          subject(
              "source", "operator",
              "operator_id", request.getOperatorId(),
              "error", res.getError()));
      result.setStatus(ArmTrailResult.Status.REJECTED);
      result.setReason("subscription_failed");
      return result;
    }

    trailingArmed = true;
    peakPremium = peak;
    givebackPct = gb;
    auditLog(
        KIND_CHANDELIER_ARMED,
        subject(
            "source",
            "operator",
            "operator_id",
            request.getOperatorId(),
            "peak_premium",
            peak,
            "giveback_pct",
            gb,
            "subscription_id",
            res.getSubscriptionId()));

    result.setStatus(ArmTrailResult.Status.ARMED);
    result.setPeakPremium(peak);
    result.setGivebackPct(gb);
    result.setStopPrice(trailStopPrice(peak, gb));
    return result;
  }

  /**
   * Best anchor this workflow can justify, IN THE PRICE SPACE THE TICK LOOP WILL COMPARE AGAINST.
   *
   * <p><b>The invariant, which is the whole reason this method is not simply "the bid".</b> The
   * peak must be in the same price space as the ticks it will be compared against. The main loop
   * routes a tick to {@link #processExitTick} — which trails on the BID via {@code
   * bidAsPremiumTick} — when {@code exitArmed || (exitTargetFired && trailingArmed)}, and otherwise
   * to {@link #processTick}, which compares the raw premium: the MID. So a copytrade position,
   * which is what the operator Stop-loss button mostly targets, evaluates the mid.
   *
   * <p>Anchoring on the bid regardless would be wrong in a way the operator can see. Since ask >=
   * bid, the mid always exceeds a bid anchor, so the FIRST tick ratchets the peak into mid space
   * and the stop silently moves up from the number the Update returned. On a wide 0DTE book 2.00 x
   * 3.00 at 35% giveback that is 1.30 promised and 1.625 delivered — tighter, so safe for money,
   * but a stop that did not do what the operator was told is the exact failure this feature exists
   * to prevent.
   *
   * <p>The route predicate is re-evaluated per tick, but {@code trailingArmed} is still false here
   * (we are arming it), so the condition to mirror is what it WILL be once armed: {@code exitArmed
   * || exitTargetFired}.
   *
   * <p>The space can change mid-life, but only in the harmless direction. BID to MID is reachable:
   * {@code exitArmed} is cleared in the flatten branch and a flatten leaving a residual keeps the
   * position open, so a bid-anchored peak starts meeting mid ticks — and since mids exceed bids,
   * the first tick ratchets the peak into the new space. Self-correcting. MID to BID would be
   * permanent (a bid never ratchets a mid-anchored peak), but it needs the fork to go false to
   * true, which means either {@code armWatchlistExit} on first fill — before any trail exists, and
   * never on a copytrade position — or {@code fireExitTarget}, which sets both flags AND re-anchors
   * the peak on the target bid in the same step. No stale peak crosses that boundary.
   *
   * <p>{@code lastBid} tracks every exit tick (unlike {@code lastTickPremium}, which only moves
   * once the trail is armed), so it is usually populated even before any arm — but it is bid-space,
   * so it only seeds the anchor when the ticks will also be bid-space.
   *
   * @return the anchor, or {@code null} when no source yields a usable price — the caller then
   *     REJECTS rather than arming at a guessed level
   */
  private BigDecimal resolveTrailAnchor() {
    boolean bidSpace = exitArmed || exitTargetFired;
    BigDecimal best = bidSpace && lastBid != null && lastBid.signum() > 0 ? lastBid : null;
    GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
    qreq.setSchemaVersion(1L);
    qreq.setTenantId(input.getTenantId());
    qreq.setStrategyId(input.getStrategyId());
    qreq.setContractSymbol(input.getContractSymbol());
    OptionQuoteResult quote = optionQuote.getOptionQuote(qreq);
    if (quote != null && quote.getStatus() == OptionQuoteResult.Status.OK) {
      if (crossed(quote)) {
        // Discard the crossed QUOTE, but keep whatever was already established. On a copytrade
        // position best is null here, so the arm is refused outright with anchor_unresolvable; on
        // the watchlist path best is lastBid — the most RECENT real observation, in the right bid
        // space — and falling back to one honest tick ago beats refusing on a book that is merely
        // momentarily crossed. Not "refuse everything": only one of the two paths refuses.
        return best;
      }
      BigDecimal fresh = bidSpace ? quote.getBid() : usableMid(quote);
      if (fresh != null && fresh.signum() > 0 && (best == null || fresh.compareTo(best) > 0)) {
        best = fresh;
      }
    }
    return best;
  }

  /**
   * A bid above the ask is not a book. Its mid is inflated relative to anything realisable, and its
   * bid cannot be used as a sanity reference either — a blown bid would be vouching for itself.
   */
  private static boolean crossed(OptionQuoteResult quote) {
    return quote.getBid() != null
        && quote.getAsk() != null
        && quote.getBid().compareTo(quote.getAsk()) > 0;
  }

  /**
   * The quote's mid, or {@code null} when there is no bid to make it a price.
   *
   * <p><b>A BLOWN ASK IS NOT GUARDED HERE, AND CANNOT BE.</b> This path is unfiltered — the
   * market-data outlier guard runs in the premium POLL only, and {@code snapshotQuote} (which
   * {@code getOptionQuote} reads) is deliberately unguarded because the kill-switch MTM read shares
   * it and fail-closes on a missing quote. A phantom ask inflates the mid, {@code peakPremium}
   * never falls, and the trail is then permanently anchored above the market. No single-snapshot
   * test detects it soundly: the true mid can only be bounded BELOW (by the bid), so "is this
   * anchor inflated" is unanswerable from one quote, and every proxy tried was worse than the
   * disease — a ratio test is scale-dependent and fires on ordinary cheap books, a
   * threshold-versus-bid test compares a mid-space threshold against a bid and rejects any book
   * whose relative half-spread exceeds the giveback, and an entry-premium band rejects exactly the
   * big winners a trail is most wanted on.
   *
   * <p>Two things actually close it, both out of scope here: the feed guard, which stops a phantom
   * from reaching the tick stream at all, and giving this method a FILTERED price to anchor on
   * rather than a raw snapshot. Note the tick stream is the larger exposure regardless — {@code
   * processTick} ratchets the peak on any tick that exceeds it with no plausibility test, so a
   * phantom refused here would walk in two seconds later anyway.
   *
   * <p>Deliberately NOT a plausibility test. An earlier version fell back to the bid when the ask
   * exceeded twice it, which is a RATIO test on a series with a fixed $0.01 tick and is therefore
   * scale-dependent: at $5.00 a 2x ask means a $5 spread and is absurd, but at $0.05 it means a
   * $0.05 spread — one to five ticks, and an entirely healthy book. 0.05 x 0.11, 0.10 x 0.25 and
   * 0.20 x 0.45 are all ordinary quotes on the cheap decayed contracts a 0DTE copytrade position
   * turns into, and all three tripped it. It fired constantly where premium is small and
   * essentially never where it is large, which is inverted.
   *
   * <p>The silent substitution was the worse half. Falling back to the bid corrupts the operator-
   * facing stop price — 0.05 x 0.11 at 35% returns 0.03 and then ratchets to a real stop of 0.052 —
   * and that number being honest is the entire reason the anchor is resolved here instead of
   * trusted from the browser. Implausible books are now REJECTED rather than quietly re-anchored,
   * which is this feature's own stated principle and makes the failure visible instead of degrading
   * in silence. See {@link #crossed}.
   */
  private static BigDecimal usableMid(OptionQuoteResult quote) {
    BigDecimal bid = quote.getBid();
    if (bid == null || bid.signum() <= 0) {
      return null; // no bid: the mid is an arithmetic artifact, and nothing here is anchorable
    }
    return quote.getMid();
  }

  /** {@code peak * (1 - giveback)}, penny-rounded — the same threshold the tick loop fires on. */
  private static BigDecimal trailStopPrice(BigDecimal peak, BigDecimal giveback) {
    if (peak == null || giveback == null) {
      return null;
    }
    return OptionTick.round(peak.multiply(BigDecimal.ONE.subtract(giveback)));
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
   * PremiumTick#getBid()}); when the bid is null it falls back to the mid ({@link
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
    // comparison for THIS flow uses bid, not the mid). Reuses the existing processTick
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
        lastTickObservedAt,
        ticksReceived);
  }

  @Override
  public PositionState positionState() {
    // input may be null if the query races run() before `this.input = in` (same guard the
    // killswitch-state read uses); report an empty contract + zero qty in that window.
    if (input == null) {
      return new PositionState("", 0L, null, null, false);
    }
    // F1: entryAt + partialExited let the parent's supersede check enforce the correction-window
    // and not-already-exiting guardrails authoritatively against THIS leg's real state.
    return new PositionState(
        input.getContractSymbol(), remainingQty, input.getEntryPremium(), entryAt, partialExited);
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

    // Operator trim (partial_close Update): place the partial SELL at MARKET (limitPrice=null),
    // exit-NOW like force_close, instead of resting the ref_premium-seeded bounded limit. Purely
    // data-driven off a field no recorded PartialExitRequest carries (every legacy dispatcher —
    // STC and the de-risk cue — omits `market`), so replaying any existing history takes the
    // identical branch and needs no getVersion marker.
    boolean marketNow = Boolean.TRUE.equals(req.getMarket());

    // REDUCE-ONLY CLAMP for the operator trim. `fraction < 1` does NOT imply `qty < remaining`:
    // qtyToClose is ceil()-ed, so 75% of a 3-lot is 3 and 75% of a 2-lot is 2 — a "trim" that
    // FLATTENS. The dashboard hides such presets, but it filters against the RENDERED qty, and a
    // partial fill between render and click shrinks the real remainder underneath it (the same
    // race the runner-quantum branch above handles for a 1-lot). The client cannot be the guard
    // for a real-money invariant, so enforce it here where remainingQty is authoritative: an
    // operator trim NEVER sells the last contract. Flattening stays force_close's job — it is the
    // control whose confirm names the whole position.
    //
    // Also intentionally overrides the FULL_CLOSE runner-quantum behavior above: that config
    // governs an author-driven STC, where flushing the runner is the intended reading of the
    // author's exit. It is not a mandate to flatten on a discretionary operator trim.
    //
    // Replay-safe with no getVersion marker on the same argument as marketNow: the clamp is
    // reachable ONLY when `market == true`, and no recorded PartialExitRequest carries that field.
    if (marketNow && qtyToClose >= remainingQty) {
      long clamped = remainingQty - 1;
      auditLog(
          KIND_OPERATOR_TRIM_CLAMPED,
          subject(
              "signal_id", req.getSignalId(),
              "fraction", req.getFraction(),
              "qty_requested", qtyToClose,
              "qty_clamped", clamped,
              "remaining_qty", remainingQty));
      if (clamped <= 0) {
        // A 1-lot has nothing to trim — every fraction resolves to the whole position. Place no
        // order and release the latch so pendingExits drains (mirrors the SKIP branch above).
        exitInFlight = false;
        return;
      }
      qtyToClose = clamped;
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
    // F1 cancel-terminal-state reconcile gate, read ONCE here (mirrors the lateFillReconcile read)
    // so the marker resolves at a stable scope. v=DEFAULT_VERSION keeps the legacy
    // discard-and-retry
    // — the getOrderStatus call + the synthesized PartialExitFilled emit are strictly behind v>=1.
    int cancelTerminalReconcileVersion =
        Workflow.getVersion(VERSION_EXIT_CANCEL_TERMINAL_RECONCILE, Workflow.DEFAULT_VERSION, 1);
    // Issue #735 Phase 2 gate, read ONCE here alongside the sibling exit gates so the marker
    // resolves at a stable scope.
    int partialAwaitLoopVersion =
        Workflow.getVersion(VERSION_EXIT_PARTIAL_AWAIT_LOOP, Workflow.DEFAULT_VERSION, 1);
    int retryCount = 0;
    long exitFillTtlSecs = 0L;

    // Place-order + bounded-await cycle. Loop body executes once (original order) plus up to
    // maxRetries additional iterations under #216 v>=1 when the prior cycle timed out.
    while (true) {
      String intentKey;
      OrderIntent intent;
      if (retryCount == 0) {
        // Phase 1 (PLAN-2026-06-25-trading-remediation): a partial re-driven at the next RTH open
        // after a prior placeOrder FAILURE carries a :retry-N suffix so it does not reuse the prior
        // attempt's client_order_id (the same fix as the flatten/:reprice-N pattern). For a
        // first-time partial (no prior place-failure) the attempt count is 0 and the key is the
        // legacy un-suffixed workflowId:exit:<signalId>, so this is replay-neutral.
        int placeRetryAttempt = partialPlaceRetryAttempts.getOrDefault(req.getSignalId(), 0);
        intentKey = Workflow.getInfo().getWorkflowId() + ":exit:" + req.getSignalId();
        if (placeRetryAttempt > 0) {
          intentKey = intentKey + ":retry-" + placeRetryAttempt;
        }
        intent = exitIntent(req, qtyToClose, intentKey, marketNow ? null : req.getRefPremium());
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
        // An operator trim re-places at MARKET rather than walking the ladder: reaching a retry
        // means the MARKET sell did not report a fill within the TTL, and the exit-NOW contract
        // still holds. Skipping computeSteppedRepriceLimit also skips its quote Activity call.
        BigDecimal stepLimit = marketNow ? null : computeSteppedRepriceLimit(retryCount);
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
        if (marketNow) {
          // Operator trim on a position old enough that the stepped-reprice gate is at
          // DEFAULT_VERSION (a multi-day lot started before that code shipped). The exit-NOW
          // contract still wins over every premium source below.
          freshLimit = null;
          source = "operator_market";
        } else if (lastTickPremium != null && lastTickPremium.signum() > 0) {
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
          // Phase 1 (PLAN-2026-06-25-trading-remediation): instead of silently dropping the
          // intended partial (the 2026-06-25 QQQ 720p 09:43 incident), re-drive it ONCE at the
          // next RTH open. The gate is read ONCE here at the catch scope (mirrors how
          // VERSION_FLATTEN_RETRY_NEXT_SESSION is read once). v=DEFAULT_VERSION keeps the exact
          // legacy releaseExitInFlightLatches()+return so in-flight histories replay
          // byte-identically — every command below (the next-open Activity call, its timer) is
          // strictly behind this gate. Bounded by MAX_PARTIAL_PLACE_RETRY_SESSIONS; on budget
          // exhaustion emit the terminal page and give the partial up (normal exits still govern).
          int partialRetryVersion =
              Workflow.getVersion(
                  VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION, Workflow.DEFAULT_VERSION, 1);
          if (partialRetryVersion >= 1) {
            if (marketNow) {
              // An operator trim is DISCRETIONARY and time-sensitive: the operator clicked "sell
              // this fraction now, at market". Re-driving it at the next RTH open would fire an
              // unattended market-on-open sell, into an overnight gap, that they never
              // re-authorized — and /live is reachable 24/7, so an out-of-hours click reaches this
              // path routinely. Fail the trim loudly instead; the operator can click again when
              // the market is open. Reachable only when `market == true`, so no getVersion marker
              // is needed (same argument as the marketNow placement branches).
              auditLog(
                  KIND_PARTIAL_EXIT_RETRY_EXHAUSTED,
                  subject(
                      "signal_id", req.getSignalId(),
                      "option_symbol", input.getContractSymbol(),
                      "qty", intent.getQty(),
                      "note", "operator_trim_not_redriven"));
            } else if (partialPlaceRetrySessions >= MAX_PARTIAL_PLACE_RETRY_SESSIONS) {
              // Budget spent: the partial failed to place across all allotted sessions. Page the
              // terminal exhausted audit and give the discretionary partial up — the lot stays
              // managed and free for a later STC / chandelier / EOD flatten.
              auditLog(
                  KIND_PARTIAL_EXIT_RETRY_EXHAUSTED,
                  subject(
                      "signal_id", req.getSignalId(),
                      "option_symbol", input.getContractSymbol(),
                      "qty", intent.getQty(),
                      "attempts", partialPlaceRetrySessions));
            } else {
              // Arm a one-shot timer to the NEXT session open. durationUntilNextRthOpenEt always
              // advances to a STRICTLY-FUTURE open (across the close and weekends). On fire, the
              // main loop re-enqueues the latched partial into pendingExits for a fresh
              // processOne cycle. A null/zero duration (no sane next open) falls through to the
              // legacy give-up (no re-drive).
              Duration untilNextOpen = calendar.durationUntilNextRthOpenEt();
              if (untilNextOpen != null && !untilNextOpen.isZero() && !untilNextOpen.isNegative()) {
                partialPlaceRetryPending = req;
                partialPlaceRetryArmed = false;
                Workflow.newTimer(untilNextOpen)
                    .thenApply(
                        v -> {
                          partialPlaceRetryArmed = true;
                          return null;
                        });
              }
            }
          }
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
      // #735 P2: drain successive PARTIAL fills of this ONE resting order. Under
      // partialAwaitLoopVersion=0 the body runs exactly ONCE and breaks at the bottom, so the
      // legacy command sequence is unchanged; under v>=1 a partial that has not yet reached
      // targetRemaining loops back to await the rest of the SAME order rather than returning as
      // though the exit were complete.
      while (true) {
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
                          // Plan-2B R-AB-2: yield the stepped reprice to the R-AB-1 flatten timer
                          // so
                          // the bounded flatten is the unambiguous final owner (deadline pinned at
                          // or
                          // before the lead trigger; no overlapping double-place). Predicate-only
                          // addition (expiryLeadFired latched only under v>=1) — replay-neutral for
                          // v=0 histories.
                          || expiryLeadFired
                          || !pendingRiskBreaches.isEmpty()
                          || !pendingForceCloses.isEmpty());
        }

        if (partialAwaitLoopVersion < 1 || lastFillEvent == null) {
          break;
        }
        // A fill landed. Book it and CLEAR it — the pre-#735 code left lastFillEvent set here, so
        // a stale cumulative could be drained a second time downstream.
        applyExitFill(req, lastFillEvent);
        lastFillEvent = null;
        // Termination: each iteration blocks on the SAME bounded await, so this is driven by
        // inbound broker signals rather than spinning, and a non-fill wakeup breaks above. A
        // duplicate cumulative books 0 under the #735 ledger — it emits no audit command, so it
        // cannot grow history either; it only re-arms the TTL, which the timeout branch then
        // handles normally. An explicit no-progress break was tried and removed: no test could
        // justify it, and its only reachable path returned SILENTLY holding the exit latch, which
        // is worse than the TTL timeout it was preventing.
        if (remainingQty <= targetRemaining
            || eodFired
            || expiryFired
            || expiryLeadFired
            || !pendingRiskBreaches.isEmpty()
            || !pendingForceCloses.isEmpty()) {
          break;
        }
      }

      if (partialAwaitLoopVersion >= 1) {
        if (remainingQty <= targetRemaining) {
          releaseExitInFlightLatches();
          if (remainingQty == 0 && closeReason == null) {
            closeReason = "normal_stc";
          }
          return;
        }
        // Target unmet. Fall through to the EXISTING timeout / cancel / retry machinery with
        // remainingQty already reflecting whatever actually filled — instead of returning as if
        // the exit were done.
      } else if (lastFillEvent != null) {
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
        // F1: capture the cancel return (previously discarded). The exec cancel-on-filled race
        // ({@code ExecActivitiesImpl}) returns state=FILLED with the broker-confirmed
        // filledQty/avgFillPrice when the order had ALREADY_FILLED at cancel time — the
        // authoritative terminal state. Capturing the existing return adds NO command, so v=0
        // replays byte-identically.
        OrderIntentResult cancelled = null;
        try {
          cancelled = exec.cancelOrder(intentKey);
        } catch (RuntimeException ignored) {
          // Cancel is best-effort; the broker may have already filled or rejected the order.
          // Reconciliation closes the loop on the real broker-side state.
        }
        // F1 (VERSION_EXIT_CANCEL_TERMINAL_RECONCILE v>=1): reconcile the AUTHORITATIVE broker
        // terminal state surfaced by the cancel BEFORE deciding to retry. If the cancel reports
        // FILLED, the order actually sold — synthesize a FillSignalPayload from the broker truth
        // and
        // route it through the EXISTING applyExitFill so exactly ONE PartialExitFilled is emitted
        // and
        // remainingQty decrements from the broker fill. Defense-in-depth: if the cancel did NOT
        // surface the fill (returned CANCELLED but the journal is FILLED), fall back to
        // getOrderStatus for the same recheck. Booking the fill here means the existing
        // remainingQty-based retryQty clamp below skips the retry (no over-sell). Strictly behind
        // v>=1: the synthesized emit and the getOrderStatus call are the only new commands; v=0
        // keeps
        // the legacy discard-and-retry for byte-identical replay.
        if (cancelTerminalReconcileVersion >= 1) {
          FillSignalPayload terminalFill = terminalFillFrom(cancelled);
          // Defense-in-depth getOrderStatus fallback ONLY when the cancel did not surface the fill
          // AND no onFill buffered for it. If lastFillEvent != null the late fill is already known
          // (the lateFillReconcile block below books it), so the extra getOrderStatus round-trip
          // would be wasted work that could surface the SAME fill a second time — guard it out.
          if (terminalFill == null && lastFillEvent == null) {
            OrderIntentResult status = null;
            try {
              status = exec.getOrderStatus(intentKey);
            } catch (RuntimeException ignored) {
              // Best-effort authoritative recheck; absence of a terminal fill leaves the genuine
              // retry path to handle a true timeout below.
            }
            terminalFill = terminalFillFrom(status);
          }
          if (terminalFill != null) {
            applyExitFill(req, terminalFill);
            // Same fill, two evidences: the cancel-on-filled race return AND a buffered onFill can
            // BOTH describe this one broker fill. We just booked it from the authoritative cancel
            // return; clear lastFillEvent so the lateFillReconcile block below does NOT book it a
            // second time (which would double-decrement remainingQty and emit two PartialExitFilled
            // for one fill). The reconciled remainingQty already reflects broker truth.
            lastFillEvent = null;
          }
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
   *   <li>{@code reason} is a scheduled expiry-equivalent flatten: {@code expiry} (the 0DTE
   *       physical-expiry close), {@code eod} (the blanket end-of-day sweep), or {@code
   *       expiry_lead} (the multi-day lead flatten). Phase 2 (PLAN-2026-07-12, B1) broadened this
   *       from {@code expiry}-only: a terminal {@code eod}/{@code expiry_lead} flatten on a
   *       physically-expired no-bid lot used to linger forever (the 2026-07-10 AMZN incident). NOT
   *       {@code risk_breach}/{@code force_close}/{@code chandelier}/{@code bto_corrected} — a
   *       no-fill there is not a worthless-at-expiry condition.
   *   <li>The contract has PHYSICALLY expired: its OCC expiry date &lt;= the workflow's current ET
   *       date, derived deterministically from {@link Workflow#currentTimeMillis()} (never {@code
   *       LocalDate.now()}). This stays the REAL guard, unchanged: a non-expiry-day {@code eod}
   *       flatten still returns {@code false} because the contract has not physically expired, so
   *       there is no behavior change off the expiry date. The expiry timer can fire slightly
   *       before midnight-of-expiry in degenerate configs, so this re-checks physical expiry rather
   *       than trusting the timer alone.
   *   <li>The reason-scoped version marker v&gt;=1. {@code expiry} reads the original {@link
   *       #VERSION_EXPIRE_WORTHLESS} (unchanged command stream); {@code eod}/{@code expiry_lead}
   *       read the NEW {@link #VERSION_EXPIRE_WORTHLESS_SCHEDULED}. v=DEFAULT_VERSION (in-flight
   *       histories) returns {@code false} → unchanged lingering behavior; the only new command on
   *       v=0 is the appended getVersion marker for whichever reason this execution took.
   * </ul>
   *
   * <p>On close: zero {@code remainingQty}, clear the late-fill flag, and emit the terminal {@link
   * #KIND_POSITION_EXPIRED} (P&amp;L-neutral — a worthless expiry realizes no exit credit).
   */
  private boolean maybeCloseWorthlessAtExpiry(String reason) {
    // Phase 2 (PLAN-2026-07-12, B1): the physical-expiry close ("expiry") plus the two scheduled
    // flattens that can be the TERMINAL flatten on an expired lot ("eod"/"expiry_lead"). The
    // physical-expiry date check below remains the real guard for all three.
    boolean physicalExpiryReason = "expiry".equals(reason);
    boolean scheduledExpiryReason = "eod".equals(reason) || "expiry_lead".equals(reason);
    if (!physicalExpiryReason && !scheduledExpiryReason) {
      return false;
    }
    LocalDate expiryDate = expiryDateFromOcc(input.getContractSymbol());
    if (expiryDate == null || expiryDate.isAfter(currentEtDate())) {
      return false;
    }
    // Reason-scoped version read: "expiry" keeps its original marker so its recorded command stream
    // is byte-identical; "eod"/"expiry_lead" gate behind the NEW marker so pre-Phase-2 in-flight
    // histories replay identically (stay ALIVE at DEFAULT_VERSION). `reason` is deterministic and
    // fixed for this execution's terminal flatten, so exactly one marker is read, at the same point
    // on every replay.
    int v =
        physicalExpiryReason
            ? Workflow.getVersion(VERSION_EXPIRE_WORTHLESS, Workflow.DEFAULT_VERSION, 1)
            : Workflow.getVersion(VERSION_EXPIRE_WORTHLESS_SCHEDULED, Workflow.DEFAULT_VERSION, 1);
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
    } else if ("bto_corrected".equals(reason)) {
      // F1: dedicated supersede-flatten kinds (parity with expiry_lead) — do NOT fall through to
      // the
      // Eod* kinds, which would mislabel the edited-signal auto-cancel as the blanket EOD sweep and
      // inflate EodForceFlattened dashboard counts.
      kindReq = KIND_BTO_CORRECTION_FLATTEN_REQUESTED;
      kindDone = KIND_BTO_CORRECTION_FLATTENED;
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
    // Phase 1 (PLAN-2026-06-30): read the broker-reconcile gate ONCE here, before any branch, so
    // the
    // command count is stable on every flattenRemaining invocation (mirrors how processOne captures
    // cancelTerminalReconcileVersion once). The reconcile itself lives inside the
    // flattenAwaitVersion
    // >= 1 timeout branch below — the only path that can leave a filled-but-signal-lost SELL stuck.
    int flattenCancelReconcileVersion =
        Workflow.getVersion(VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE, Workflow.DEFAULT_VERSION, 1);
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
            // F1: a superseded wrong-expiry leg must hit the market NOW (like force_close), not
            // rest
            // a bounded limit that could linger past the correction it is cancelling.
            || "bto_corrected".equals(reason)
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

    // TTL timeout — the bounded limit rests UNFILLED (as far as the onFill signal knows).
    // Best-effort
    // cancel. Phase 1 (PLAN-2026-06-30): capture the cancel return (previously DISCARDED). The exec
    // cancel-on-filled race returns state=FILLED with the broker-confirmed filledQty/avgFillPrice
    // when the SELL had ALREADY_FILLED at cancel time — the authoritative terminal state that the
    // lost/late onFill never surfaced. Capturing the existing return adds NO command, so v=0
    // replays
    // byte-identically.
    OrderIntentResult cancelled = null;
    try {
      cancelled = exec.cancelOrder(flattenIntentKey);
    } catch (RuntimeException ignored) {
      // Best-effort; reconciliation closes the loop on the real broker-side state.
    }
    // Phase 1 (VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE v>=1): reconcile the AUTHORITATIVE broker
    // terminal state surfaced by the cancel BEFORE emitting the loud failure + staying alive. If
    // the
    // cancel reports FILLED, the flatten SELL actually sold — synthesize a FillSignalPayload from
    // broker truth and route it through the SHARED bookFlattenDelta so exactly ONE
    // PartialExitFilled
    // is emitted for the un-booked delta (enters realized P&L) and remainingQty decrements from the
    // broker fill.
    // Cumulative-vs-delta (guardrail #5): terminalFillFrom carries the order's CUMULATIVE
    // filledQty.
    // Review fix (PLAN-2026-06-30): the #481 retry loop later polls this SAME flattenIntentKey via
    // getOrderStatus; if this branch booked the raw cumulative qty and then the retry loop
    // re-polled
    // and booked it again, one broker fill would be double-counted (and a large enough double-book
    // drives remainingQty NEGATIVE → PositionClosed with remaining < 0). Route through the shared
    // flattenBookedKey/flattenBookedQty ledger so BOTH sites book only the un-booked delta of this
    // key.
    if (flattenCancelReconcileVersion >= 1) {
      FillSignalPayload terminalFill = terminalFillFrom(cancelled);
      // Defense-in-depth getOrderStatus fallback ONLY when the cancel did not surface the fill AND
      // no
      // onFill buffered for it (guardrail #4). If lastFillEvent != null we would not be in this
      // timeout branch at all; the guard is belt-and-suspenders — it also avoids a wasted
      // round-trip
      // and prevents surfacing the SAME fill a second time.
      if (terminalFill == null && lastFillEvent == null) {
        OrderIntentResult status = null;
        try {
          status = exec.getOrderStatus(flattenIntentKey);
        } catch (RuntimeException ignored) {
          // Best-effort authoritative recheck; absence of a terminal fill leaves the genuine
          // stay-alive path below to handle a truly-unfilled rest.
        }
        terminalFill = terminalFillFrom(status);
      }
      if (terminalFill != null) {
        // R-AA-6 / review fix: book ONLY the un-booked delta of this key via the shared ledger
        // (clamped to remainingQty, lastFillEvent cleared inside bookFlattenDelta). bookedThisFill
        // == 0 means the fill was already fully accounted (e.g. via an onFill drain) — the residual
        // still stays alive below.
        long bookedThisFill = bookFlattenDelta(reason, flattenIntentKey, terminalFill);
        flattenAwaitingLateFill = false;
        if (remainingQty == 0) {
          // Guardrail #2: the terminal lifecycle marker is gated on POST-decrement remainingQty ==
          // 0
          // ONLY (never on terminalFill != null). Mirror the success block above; run() emits
          // PositionClosed once flattenRemaining returns true and remainingQty == 0 breaks its
          // loop.
          auditLog(
              kindDone,
              subject(
                  "entry_signal_id",
                  input.getEntrySignalId(),
                  "contract_symbol",
                  input.getContractSymbol(),
                  "qty_flattened",
                  bookedThisFill,
                  "reason",
                  reason));
          return true;
        }
        // Guardrail #2: partial fill — residual remains. Stay alive for it (mirror the success
        // block's residual branch); do NOT emit the terminal lifecycle marker.
        flattenAwaitingLateFill = true;
        return false;
      }
    }
    // Guardrail #7: ONLY a genuine unfilled rest (terminalFill == null) reaches here. Emit the loud
    // failure audit and stay ALIVE (never zero remainingQty, never emit PositionClosed). The caller
    // re-arms / the main loop applies a late fill of the resting order.
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
  private long applyExitFill(PartialExitRequest req, FillSignalPayload fillEvent) {
    return emitExitFill(req.getSignalId(), fillEvent);
  }

  /**
   * F1 (VERSION_EXIT_CANCEL_TERMINAL_RECONCILE): translate an authoritative exec {@link
   * OrderIntentResult} terminal state into a {@link FillSignalPayload} the existing {@link
   * #emitExitFill(String, FillSignalPayload)} path can book. Returns the synthesized fill iff the
   * result reports a real broker fill ({@code state==FILLED} OR a positive {@code filledQty}); else
   * {@code null} (no fill to reconcile → the genuine-timeout retry path proceeds). Pure
   * transform/no command: {@code filledAt} uses {@link #workflowNow()} ({@code
   * Workflow.currentTimeMillis()}) so it is deterministic on replay. The fill price falls back to 0
   * if the exec result omits {@code avgFillPrice} (FillSignalPayload requires non-null fields); a
   * zero price never under-counts realized losses (it only realizes less credit, the conservative
   * direction) and the qty/broker_order_id — the load-bearing fields for the remainingQty decrement
   * and the audit correlation — always come from broker truth.
   */
  private static FillSignalPayload terminalFillFrom(OrderIntentResult result) {
    // Book a fill only when the broker confirmed a positive filled quantity. A null/zero filledQty
    // (a SUBMITTED/CANCELLED result, or a FILLED row that has not yet surfaced its fill detail) is
    // NOT bookable — return null so the genuine-timeout retry path proceeds. The exec
    // cancel-on-filled race that this reconciles always populates filledQty alongside state=FILLED,
    // so a positive filledQty is the authoritative signal; gating on state too would be redundant.
    if (result == null || result.getFilledQty() == null || result.getFilledQty() <= 0L) {
      return null;
    }
    return new FillSignalPayload()
        .withBrokerOrderId(result.getBrokerOrderId())
        .withFilledQty(result.getFilledQty())
        .withAvgFillPrice(
            result.getAvgFillPrice() != null ? result.getAvgFillPrice() : BigDecimal.ZERO)
        .withFilledAt(workflowNow());
  }

  /**
   * Phase 1 (PLAN-2026-06-30) review fix: book ONLY the un-booked delta of a flatten intent_key's
   * CUMULATIVE broker fill into realized P&L, using the shared {@link #flattenBookedKey}/{@link
   * #flattenBookedQty} ledger so the two reconcile sites (flattenRemaining's TTL-timeout branch and
   * the #481 retry-loop) never double-book the SAME broker fill.
   *
   * <p>{@code terminalFill.getFilledQty()} is CUMULATIVE for {@code intentKey}. We reset the ledger
   * to 0 whenever the active key changes (a fresh :retry-N placement, or a first sighting), then
   * compute {@code delta = cumulative - alreadyBookedForThisKey}. Only a positive delta is
   * bookable. Belt-and-suspenders clamp: never book more than the outstanding {@code remainingQty},
   * so a stale/duplicate broker report can never drive {@code remainingQty} negative (which would
   * break R-AA-1 by emitting PositionClosed with remaining &lt; 0). After booking we advance the
   * ledger by the booked qty and clear {@code lastFillEvent} (guardrail #3) so the onFill drains do
   * not re-book the same fill.
   *
   * @return the qty actually booked (0 when nothing new to book — the caller uses this to decide
   *     between "real progress this poll" and "fully accounted, residual remains").
   */
  private long bookFlattenDelta(String reason, String intentKey, FillSignalPayload terminalFill) {
    // Issue #735 v>=1: emitExitFill now owns the cumulative->delta conversion for EVERY booking
    // site, keyed on the broker order. Running this method's own intent_key ledger on top would
    // double-convert — it would hand emitExitFill an already-differenced qty, which the order
    // ledger would then difference AGAIN against the same broker order. Delegate instead; the
    // guardrail #3 lastFillEvent clear is preserved below.
    if (Workflow.getVersion(VERSION_EXIT_CUMULATIVE_LEDGER, Workflow.DEFAULT_VERSION, 1) >= 1) {
      long booked = emitExitFill("flatten-" + reason, terminalFill);
      lastFillEvent = null;
      return booked;
    }
    if (!intentKey.equals(flattenBookedKey)) {
      // Active key rolled (fresh :retry-N) or first sighting: this key's cumulative count starts at
      // 0.
      flattenBookedKey = intentKey;
      flattenBookedQty = 0L;
    }
    long delta = terminalFill.getFilledQty() - flattenBookedQty;
    if (delta <= 0) {
      return 0L;
    }
    // Belt-and-suspenders: a stale/duplicate broker report can report MORE than the lot; never book
    // past the outstanding remainingQty so remainingQty can never go negative.
    long bookable = Math.min(delta, remainingQty);
    if (bookable <= 0) {
      return 0L;
    }
    flattenBookedQty += bookable;
    emitExitFill(
        "flatten-" + reason,
        new FillSignalPayload()
            .withBrokerOrderId(terminalFill.getBrokerOrderId())
            .withFilledQty(bookable)
            .withAvgFillPrice(terminalFill.getAvgFillPrice())
            .withFilledAt(terminalFill.getFilledAt()));
    // Guardrail #3: clear lastFillEvent so the L1110/L1318/L1356 onFill drains do NOT re-book this
    // same broker fill (double-decrement).
    lastFillEvent = null;
    return bookable;
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
  private long emitExitFill(String signalId, FillSignalPayload fillEvent) {
    // #738: a fill of our OWN ENTRY order is not an exit. Booking it would decrement the position
    // we just bought — and #740's clamp turns that into a clean zero, so the workflow reports FLAT
    // while the broker still holds the lot. Refuse it here, at the single site every booking path
    // funnels through.
    if (Workflow.getVersion(VERSION_ENTRY_FILL_NOT_AN_EXIT, Workflow.DEFAULT_VERSION, 1) >= 1
        && entryBrokerOrderId != null
        && entryBrokerOrderId.equals(fillEvent.getBrokerOrderId())) {
      Workflow.getLogger(PositionWorkflowImpl.class)
          .warn(
              "#738: refusing to book a fill of the ENTRY order as an exit"
                  + " broker_order_id={} filled_qty={} remaining_qty={} signal_id={}",
              fillEvent.getBrokerOrderId(),
              fillEvent.getFilledQty(),
              remainingQty,
              signalId);
      return 0L;
    }
    long filled = fillEvent.getFilledQty();
    // Issue #735: convert the broker's CUMULATIVE filled_qty into the un-booked delta for this
    // broker order, clamped to the outstanding lot. Returning 0 here emits NO audit event — that
    // command-count change is precisely what VERSION_EXIT_CUMULATIVE_LEDGER fences off from the
    // in-flight v=0 executions.
    if (Workflow.getVersion(VERSION_EXIT_CUMULATIVE_LEDGER, Workflow.DEFAULT_VERSION, 1) >= 1) {
      filled = bookableExitDelta(fillEvent);
      if (filled <= 0) {
        return 0L;
      }
    }
    remainingQty -= filled;
    // F1 supersede guardrail: any exit fill that reduces the lot marks it as no-longer-untouched.
    // Conservative by direction — it only makes the supersede check MORE restrictive (a leg that
    // has begun exiting is never auto-cancelled). Set unconditionally (no version gate): it mutates
    // a query-only field, appends no command, and is replay-neutral.
    if (filled > 0) {
      partialExited = true;
    }
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
    return filled;
  }

  /**
   * Issue #735: the un-booked, clamped delta of {@code fillEvent}'s CUMULATIVE {@code filled_qty}
   * for its broker order, advancing {@link #exitBookedByOrder} by whatever it returns.
   *
   * <p>Two guards, both load-bearing:
   *
   * <ul>
   *   <li>a non-positive delta books NOTHING — a duplicate or stale report of a cumulative already
   *       seen (the same fill arriving via two evidences, e.g. a cancel-on-filled return AND a
   *       buffered onFill) must not decrement the lot twice;
   *   <li>{@code Math.min(delta, remainingQty)} — a broker report larger than the outstanding lot
   *       can never drive {@code remainingQty} negative, which would emit PositionClosed with
   *       remaining &lt; 0 and break R-AA-1.
   * </ul>
   *
   * <p>The ledger records {@code alreadyBooked + bookable} rather than the raw cumulative, so a
   * clamped booking leaves the un-bookable remainder permanently unbooked instead of silently
   * absorbing it.
   */
  private long bookableExitDelta(FillSignalPayload fillEvent) {
    String key = fillEvent.getBrokerOrderId();
    if (key == null || key.isBlank()) {
      // No order identity, so no ledger is possible. Book the reported qty, still clamped. This
      // deliberately errs toward OVER-booking: an under-book leaves remainingQty stuck above zero
      // and the position never reaches its terminal close — a live lot that hangs unflattened,
      // which is worse than an early close that reconciliation will surface as an orphan. Real
      // Alpaca fills always carry an order id; synthesized fills inherit one from the broker
      // result, so this is a defensive branch rather than an expected path.
      return Math.max(0L, Math.min(fillEvent.getFilledQty(), remainingQty));
    }
    long alreadyBooked = exitBookedByOrder.getOrDefault(key, 0L);
    long delta = fillEvent.getFilledQty() - alreadyBooked;
    if (delta <= 0) {
      return 0L;
    }
    long bookable = Math.min(delta, remainingQty);
    if (bookable <= 0) {
      return 0L;
    }
    exitBookedByOrder.put(key, alreadyBooked + bookable);
    // Bounded, oldest-first. LinkedHashMap preserves insertion order, so replay is deterministic.
    while (exitBookedByOrder.size() > EXIT_LEDGER_MAX_ORDERS) {
      exitBookedByOrder.remove(exitBookedByOrder.keySet().iterator().next());
    }
    return bookable;
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
