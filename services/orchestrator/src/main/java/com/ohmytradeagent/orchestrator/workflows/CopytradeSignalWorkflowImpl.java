package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeEntryStatus;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AccountSnapshotMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.LivePromotionStatus;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import com.ohmytradeagent.orchestrator.domain.BtoPricing;
import com.ohmytradeagent.orchestrator.domain.BtoPricing.PricedLimit;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.KeywordPartialMatcher;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.domain.StrategyConfigs;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CopytradeSignalWorkflowImpl implements CopytradeSignalWorkflow {

  private static final String KIND_SIGNAL_RECEIVED = "SignalReceived";
  private static final String KIND_SIGNAL_ACCEPTED = "SignalAccepted";
  private static final String KIND_SIGNAL_REJECTED = "SignalRejected";
  private static final String KIND_ORDER_SUBMITTED = "OrderSubmitted";
  private static final String KIND_ORDER_CANCEL_REQUESTED = "OrderCancelRequested";
  private static final String KIND_ORDER_CANCELLED = "OrderCancelled";
  private static final String KIND_ORDER_CANCEL_FAILED = "OrderCancelFailed";
  private static final String KIND_ENTRY_EXPIRED = "EntryExpired";
  private static final String KIND_ENTRY_FILLED = "EntryFilled";
  private static final String KIND_EXIT_REQUESTED = "ExitRequested";
  private static final String KIND_ORPHAN_STC = "OrphanSTC";
  // PLAN-2026-07-21-benign-stc-no-position: benign counterpart to OrphanSTC. Emitted at Sites A/B
  // in
  // handleStc when the STC arrived after the position was ALREADY FULLY CLOSED (no PositionWorkflow
  // found, or found-but-not-RUNNING). "No position left, nothing to sell" — a BENIGN informational
  // event that must NOT page RED. StcNoOpenPositionAlerter posts a YELLOW note; it is absent from
  // OrderFailureAlerter's failure-kinds. Site C (a genuine dispatch failure to a still-RUNNING
  // position) keeps emitting KIND_ORPHAN_STC and keeps paging RED. Registered in
  // AuditEventKinds.ALL_KINDS. No version gate: the KIND is Activity input (logAudit is an activity
  // call), not a Temporal command shape, so it is not replay-checked on 1.27.
  private static final String KIND_STC_NO_OPEN_POSITION = "StcNoOpenPosition";
  private static final String KIND_AVG_SKIPPED = "AvgSkipped";
  // Phase 4: distinct from PositionWorkflow's "ChandelierArmed" — this audit is the dispatch
  // (parent-side); the apply (child-side) emits its own ChandelierArmed when the subscribe
  // activity succeeds. Both useful for forensics.
  private static final String KIND_CHANDELIER_ARM_REQUESTED = "ChandelierArmRequested";
  // Phase 5: kill-switch cascade short-circuit audit.
  private static final String KIND_SIGNAL_ABORTED_BY_RISK_BREACH = "SignalAbortedByRiskBreach";
  // P3-a (multi-tenant-broker-credentials): emitted when a LIVE BTO is refused at the
  // live-promotion gate (no VALID LivePromotionApproved row for the broker_target). Registered in
  // AuditEventKinds.ALL_KINDS + SOFT_TERMINAL_CLOSE_KINDS (refused before any broker activity).
  private static final String KIND_LIVE_PROMOTION_MISSING = "LivePromotionMissing";

  // Edited-signal supersede (F1): emitted by handleBto when a corrected BTO auto-cancels a prior
  // wrong-expiry leg. This is the AUDITABLE supersede DECISION — its subject carries BOTH legs' OCC
  // (the superseded prior leg + this corrected leg) plus both signal_ids so every auto-cancel of a
  // REAL trade is traceable. Pages via OrderFailureAlerter (IMAGE default). The child
  // PositionWorkflow
  // emits its own PositionSupersededByCorrection + the flatten kinds when it actions the signal.
  // Registered in AuditEventKinds.ALL_KINDS.
  private static final String KIND_BTO_CORRECTION_SUPERSEDED = "BtoCorrectionSuperseded";

  /**
   * PLAN-2026-08-10-live-manual-bto: {@code reason_code} for an operator qty_override outside the
   * strategy's [min_contracts, max_contracts]. A distinct code (not the generic
   * NOTIONAL_CAP_EXCEEDED) because the operator can fix this one by typing a different number,
   * whereas a cap breach depends on the book.
   */
  private static final String REASON_MANUAL_QTY_OUT_OF_BOUNDS = "MANUAL_QTY_OUT_OF_BOUNDS";

  /**
   * Gate for the STC multi-leg FAN-OUT. One OCC can have several open legs (two BTOs on the same
   * contract each start their own PositionWorkflow — {@code WorkflowIds.position} keys on the entry
   * signal id), but {@code findPositionWorkflowId} returns exactly ONE, and which one depends on
   * Redis cache state. Every other leg silently survived the author's exit.
   *
   * <p>This marker is MANDATORY, unlike the manual-BTO branches: the fan-out turns one {@code
   * signal} command into N, so an in-flight STC execution replaying a pre-change history would
   * diverge on command count. v=DEFAULT_VERSION keeps the single-leg dispatch byte-identical; only
   * v>=1 enumerates and signals the extra legs.
   */
  private static final String VERSION_STC_MULTI_LEG_FANOUT = "stc-multi-leg-fanout-v1";

  // Phase 2 (PLAN-2026-07-06-pretrade-check-orchestrator-wiring): the top-level failure-audit kind.
  // The 2026-07-06 incident: pre_trade_check_enabled=true with the orchestrator routability bean
  // unwired made assertPreTradeCheckRoutable throw a non-retryable ApplicationFailure
  // (PreTradeCheckMisconfigured) BEFORE any audit row was written. OrderFailureAlerter is
  // audit-driven (fires AFTER an audit row), so 3 real prod_real BTOs black-holed with only
  // "Signal received" on Discord and NO failure alert. This kind is emitted by process()'s
  // top-level catch when the entry workflow fails non-retryably (the guard, the
  // ExecActivitiesFactory
  // invalid-target throw, or any other unhandled TemporalFailure), giving the misconfig a page
  // BEFORE the workflow re-throws and stays FAILED. Pure observability — NOT a position-lifecycle
  // event; registered in AuditEventKinds.ALL_KINDS ONLY, plus
  // OrderFailureAlerter.DEFAULT_FAILURE_KINDS
  // and application.yml's failure-kinds IMAGE default so it pages.
  private static final String KIND_ENTRY_WORKFLOW_FAILED = "EntryWorkflowFailed";

  private static final String REASON_TTL_EXPIRED = "ttl_expired";
  // Issue: orphan-STC alerting. handleStc's stale Redis lookup can return a DEAD (terminal)
  // PositionWorkflow id; these reason codes tag the OrphanSTC audit emitted instead of crashing.
  private static final String REASON_POSITION_WF_NOT_RUNNING = "position_workflow_not_running";
  private static final String REASON_SIGNAL_DISPATCH_FAILED = "signal_dispatch_failed";
  private static final String VERSION_POSITION_HANDOFF = "position-handoff";
  private static final String VERSION_RISK_BREACH = "risk-breach-v1";
  // Issue #165 phase 2: gate the FILLED branch in handleTtlExpired so pre-fix replay histories
  // deterministically take the legacy CANCELLED/else paths. Mirrors VERSION_POSITION_HANDOFF.
  private static final String VERSION_TTL_FILLED_ADOPTION = "ttl-filled-adoption-v1";
  // Issue #274: gate the FILLED branch in the BTO risk-breach abort so pre-fix replay histories
  // deterministically take the legacy audit-and-abort path. When a breach wakes the fill-await
  // before the async onFill signal lands but the broker has already filled, exec.cancelOrder
  // reconciles broker truth and returns state=FILLED; the v>=1 branch adopts the lot via
  // handleCancelOnFilled instead of discarding the result and orphaning. Mirrors
  // VERSION_TTL_FILLED_ADOPTION.
  private static final String VERSION_BREACH_FILLED_ADOPTION = "breach-filled-adoption-v1";
  // Copytrade-entry parity for the watchlist Phase-1 fix: defense-in-depth getOrderStatus reconcile
  // on the TTL-expiry branch. The single exec.cancelOrder return can miss a fill that terminalized
  // the journal FILLED a beat later (WS listener raced the cancel — the SPY 2026-07-06 incident on
  // the sibling watchlist path). When inline cancel-on-filled did NOT adopt, re-read broker truth
  // via exec.getOrderStatus and route a terminal FILLED through the SAME handleCancelOnFilled path
  // instead of emitting EntryExpired and deferring to the 5-min recon sweep. Gated: the new
  // getOrderStatus activity call + resulting adoption commands are a command-shape change on the
  // timeout path, so pre-fix histories (DEFAULT_VERSION) must replay byte-identically via the
  // legacy
  // path. Mirrors VERSION_TTL_FILLED_ADOPTION.
  private static final String VERSION_ENTRY_GETORDERSTATUS_RECONCILE =
      "copytrade-entry-getorderstatus-reconcile-v1";
  // Distinct `recovery` subject labels on the EntryFilled adoption evidence so incident forensics
  // can tell the two timeout-branch adoption paths apart: `cancel_on_filled` = the single
  // cancelOrder call itself reported FILLED; `getorderstatus_reconcile` = cancelOrder returned
  // non-FILLED but the getOrderStatus re-check caught the race a beat later (the rarer, more
  // interesting signal). Mirrors WatchlistTriggerWorkflowImpl's RECOVERY_* distinction.
  private static final String RECOVERY_CANCEL_ON_FILLED = "cancel_on_filled";
  private static final String RECOVERY_GETORDERSTATUS_RECONCILE = "getorderstatus_reconcile";

  // #818: the entry ended (TTL cancel / breach cancel) with the broker reporting NO terminal fill
  // through the reconcile probes, but the workflow LOCALLY holds a positive-qty partial in
  // fillEvent (the WS partial_fill slices — broker's own cumulative for OUR order; superseded-
  // order fills are diverted to droppedSupersededFill and can never land here). Book that lot:
  // an under-booked-by-a-late-slice position (#801 growth + #817 page absorb it) beats a fully
  // unmanaged one. Pre-#818 these were nearly unreachable (any slice unblocked the await).
  private static final String RECOVERY_PARTIAL_AT_TTL = "partial_at_ttl";

  private static final String RECOVERY_PARTIAL_AT_BREACH = "breach_partial_fill";
  // Issue #112: Gate the 3-activity pre-trade dispatch (assertPreTradeCheckRoutable →
  // dispatchPreTradeCheck → checkEntryWithLimit(payload, config, preTradeResult, limit))
  // introduced in PR #111 and tightened in #198 to thread the slip-adjusted limit through.
  // Pre-#111 in-flight CopytradeSignalWorkflow executions had a single checkEntry(payload, config)
  // call; the v=DEFAULT_VERSION branch preserves that shape via the legacy 3-arg checkEntry
  // overload with null preTradeResult so replays of legacy histories remain deterministic. The
  // v>=1 branch dispatches the pre-trade check; the inner VERSION_CHECK_ENTRY_WITH_LIMIT gate
  // then routes to checkEntryWithLimit (CheckEntryWithLimit Activity type) for new executions so
  // notional-cap + buying-power gates see the slip-adjusted limit rather than the mirror price.
  // Retires the deploy-time drain mitigation documented in #111.
  private static final String VERSION_PRE_TRADE_DISPATCH = "pre-trade-dispatch-v2";

  // In-flight v>=1 workflows recorded a CheckEntry activity-task call before this gate was added.
  // After deploy, the new code scheduling CheckEntryWithLimit would mismatch the recorded history
  // and trip a Temporal non-determinism error. v=DEFAULT_VERSION (open workflows) keeps the legacy
  // checkEntry call; v>=1 (new workflows) routes to checkEntryWithLimit with the slip-adjusted
  // limit so notional-cap + BP gates see the max-acceptable cost.
  private static final String VERSION_CHECK_ENTRY_WITH_LIMIT = "check-entry-with-limit";

  // Issue #203: gate the new child.onFill(fill) call that forwards the BTO fill to the child
  // PositionWorkflow so its v=1 first-fill await wakes. In-flight CopytradeSignalWorkflow
  // executions
  // that already passed through startPositionWorkflow without this call must keep replaying without
  // the extra signal command; new executions (v>=1) forward the fill so the child confirms entry.
  private static final String VERSION_FORWARD_BTO_FILL = "forward-bto-fill-v1";

  // Issue #276: gate the option_symbol field added to the EntryFilled audit subject (both the
  // happy-path fill branch and the cancel-on-filled recovery). The DailyPnl realized-P&L consumer
  // groups FIFO by option_symbol so each exited contract realizes against its OWN symbol's basis;
  // emitting the key on EntryFilled supplies the producer-side correlation key. Replay-gated so
  // pre-change CopytradeSignalWorkflow histories reproduce the legacy subject exactly (no
  // option_symbol key) and stay deterministic; only new executions (v>=1) emit it. Mirrors
  // VERSION_TTL_FILLED_ADOPTION / VERSION_BREACH_FILLED_ADOPTION.
  private static final String VERSION_ENTRY_FILLED_OPTION_SYMBOL = "entry-filled-option-symbol-v1";

  // Gate the new account-snapshot dispatch + the 5-arg checkEntryWithLimit overload that
  // threads the broker-supplied CASH (the #323 cost-basis capital-base component, not net-liq
  // equity) into the notional-cap gate. In-flight v>=1 workflows recorded
  // a 4-arg CheckEntryWithLimit call (and no AccountSnapshot activity-task) before this landed;
  // replaying them must keep the legacy path or Temporal trips a non-determinism error.
  // v=DEFAULT_VERSION replays the prior 4-arg call (cash from the PortfolioSnapshot seam); v>=1
  // dispatches AccountSnapshot and passes the cash down. The marker string is a Temporal replay
  // identifier and must NOT be renamed even though the threaded value is now cash, not equity.
  private static final String VERSION_ACCOUNT_EQUITY_DISPATCH = "account-equity-dispatch-v1";

  // Issue: orphan-STC alerting. Gate the two STC running-guards (preventive: check
  // isPositionWorkflowRunning before ExitRequested+dispatch; defense-in-depth: catch
  // SignalExternalWorkflowException around partialExit) behind a single marker so v=0 in-flight
  // handleStc replays are BYTE-IDENTICAL. v=DEFAULT_VERSION: the preventive guard short-circuits
  // via
  // && (no isPositionWorkflowRunning activity scheduled) and the dispatch takes the bare-signal
  // branch — identical command stream. v>=1: both guards active so a stale Redis mapping to a dead
  // PositionWorkflow emits OrphanSTC instead of crashing the CopytradeSignalWorkflow.
  private static final String VERSION_STC_RUNNING_GUARD = "stc-running-guard-v1";

  // P3-a (multi-tenant-broker-credentials): gate the LIVE-only live-promotion dispatch check added
  // in handleBto (after the SignalAccepted audit, before newIntent/placeOrder). v=DEFAULT_VERSION
  // (legacy in-flight histories) is byte-identical to the pre-P3a path — the new branch is
  // reachable
  // only at v>=1, and the paper path (!isLive) schedules ZERO verify activities at any version.
  private static final String VERSION_LIVE_PROMOTION_GATE = "live-promotion-gate-v1";

  // P3-a: the live-promotion staleness window. A LivePromotionApproved older than this window is
  // refused as STALE. Hardcoded by design — making it config-tunable is P3-b, NOT this phase.
  private static final Duration LIVE_PROMOTION_TTL = Duration.ofDays(30);

  // dynamic-account-cash-sizing: gate the ONE place capital_source=account_cash adds a command to
  // the history — the account-snapshot dispatch fired for a strategy that enabled cash-sizing but
  // configured NO notional cap. Pre-this-change, dispatchAccountSnapshot returned null (no command)
  // for a no-notional-cap config, so a legacy history of such a strategy has no AccountSnapshot
  // command. Reading this marker unconditionally and only WIDENING the dispatch enablement at v>=1
  // keeps every legacy execution's command stream byte-identical: a no-cap legacy history replays
  // at
  // DEFAULT_VERSION (no dispatch), a notional-cap history already dispatched (so its enablement is
  // unchanged at either version), and only a freshly-started account_cash+no-cap execution takes
  // the
  // v>=1 dispatch. Sizing itself reads the resulting cash as an Activity-INPUT to computeContracts
  // (the `contracts` value) — Temporal replay ignores activity-input payloads, so the static→cash
  // sizing switch needs NO marker; only the command-ordering widening above does. Mirrors
  // VERSION_LIVE_PROMOTION_GATE's "read unconditionally, branch only at v>=1" discipline.
  private static final String VERSION_ACCOUNT_CASH_SIZING = "account-cash-sizing-v1";

  // #790 capital_source=account_equity: same shape as VERSION_ACCOUNT_CASH_SIZING, one version
  // later. NOT to be confused with VERSION_ACCOUNT_EQUITY_DISPATCH ("account-equity-dispatch-v1"),
  // the frozen legacy marker whose threaded value is nowadays CASH — this one gates equity SIZING.
  // later. Gates ONLY the dispatch-enablement widening (an account_equity+no-cap strategy adds an
  // AccountSnapshot command a legacy history never recorded); the equity-vs-cash-vs-static sizing
  // branch itself feeds activity INPUTS only and needs no marker of its own. Read unconditionally,
  // branch only at v>=1. No recorded history can carry capital_source=account_equity (the enum
  // value did not exist), so every legacy replay takes DEFAULT_VERSION paths untouched.
  private static final String VERSION_ACCOUNT_EQUITY_SIZING = "account-equity-sizing-v1";

  // Phase 7 (per-tenant strategy enable toggle): gate the fail-safe enable check added in process()
  // immediately after the strategy.get() fetch. A strategy whose StrategyConfig.enabled is
  // explicitly false admits no new entries — the signal is rejected before any exec stub is built
  // or order placed (SignalRejected, reason strategy_disabled). FAIL-SAFE: absent/null/true ALL
  // proceed unchanged (StrategyConfig.enabled defaults to true in schema; older blobs may carry
  // null), so existing tenants see no behavior change. Read UNCONDITIONALLY (before the
  // Boolean.FALSE test) and branch only at v>=1, mirroring VERSION_LIVE_PROMOTION_GATE: legacy
  // in-flight histories (DEFAULT_VERSION) take the byte-identical pre-change path with no new
  // command, and only freshly-started executions evaluate the gate.
  private static final String VERSION_STRATEGY_ENABLED_GATE = "strategy-enabled-gate-v1";

  // Phase F4B (clamp-to-fit headroom): gate the clamp-to-notional-cap-headroom step added to the
  // sizing block. Pre-this-change, an over-cap entry was REJECTED by checkNotionalCap (lever B was
  // not applied here). At v>=1 the workflow dispatches notionalCapHeadroomContracts (a NEW command)
  // ONLY when a notional cap is configured, then sizes the entry to MIN(cash-weight sizing,
  // cap-headroom, max_contracts) — flooring to fit, and rejecting NOTIONAL_CAP_EXCEEDED only when
  // the clamped qty falls below min_contracts. Read UNCONDITIONALLY (before any branch) and branch
  // only at v>=1: legacy in-flight histories (DEFAULT_VERSION) take the byte-identical pre-change
  // path — no headroom dispatch, the existing checkNotionalCap reject stands. Mirrors
  // VERSION_LIVE_PROMOTION_GATE's read-once discipline. The headroom dispatch is gated on the cap
  // being configured so a no-cap strategy adds NO command at v>=1 either.
  private static final String VERSION_NOTIONAL_CAP_CLAMP = "notional-cap-clamp-to-fit-v1";

  // Edited-signal supersede (F1): gate the corrected-BTO auto-supersede block added in handleBto
  // (after the SignalAccepted audit, BEFORE newIntent/placeOrder). The block dispatches a lookup
  // Activity (findOpenPositionByUnderlyingStrikeRight) and, when ALL guardrails hold, signals the
  // prior leg's PositionWorkflow to flatten + emits BtoCorrectionSuperseded — ALL new commands.
  // Read UNCONDITIONALLY (before any branch) and branch only at v>=1: legacy in-flight histories
  // (DEFAULT_VERSION) take the byte-identical pre-F1 path (no lookup, no signal, no audit). The
  // guardrails are conservative-by-design (see SUPERSEDE_WINDOW): supersede fires ONLY when same
  // tenant+strategy+underlying+strike+right, DIFFERENT expiry, prior leg confirmed within the
  // window
  // and NOT already partially exited. Mirrors VERSION_LIVE_PROMOTION_GATE's read-once discipline.
  private static final String VERSION_BTO_CORRECTION_SUPERSEDE = "bto-correction-supersede-v1";

  // Phase 2 (PLAN-2026-07-06-pretrade-check-orchestrator-wiring): gate the top-level failure-audit.
  // Read UNCONDITIONALLY at the very top of process() (before any command) so the command stream is
  // identical across versions. The new EntryWorkflowFailed logAudit is a NEW activity command on
  // the
  // failure path, so pre-fix in-flight histories (DEFAULT_VERSION) must replay byte-identically
  // WITHOUT it — at DEFAULT_VERSION the catch re-throws WITHOUT emitting. All previously-FAILED
  // workflows are terminal (never replay). Only v>=1 emits the audit.
  private static final String VERSION_ENTRY_FAILURE_AUDIT = "entry-workflow-failure-audit-v1";

  // PLAN-2026-08-04-bto-entry-repeg Phase 3: the single bounded re-peg of the BTO entry toward the
  // live ask. NEW commands behind this marker — the re-peg-delay timer, the GetOptionQuote
  // dispatch, the cancel/place pair, and their audits — so a workflow already mid-entry when the
  // pod rolls replays its recorded stream (one placeOrder, one await(ttl), one handleTtlExpired).
  //
  // This marker is NOT a feature flag. The feature ships ACTIVE on the defaults below; the marker
  // exists solely so in-flight histories stay deterministic. Removing it wedges live entries.
  // Read UNCONDITIONALLY at a stable scope, mirroring VERSION_NOTIONAL_CAP_CLAMP.
  private static final String VERSION_BTO_ENTRY_REPEG = "bto-entry-repeg-v1";

  /** Issue #579: live-aware entry TTL selection — see {@link #entryPendingTtlSecs}. */
  private static final String VERSION_ENTRY_TTL_LIVE_AWARE = "entry-ttl-live-aware-v1";

  // #818: the entry await must unblock on the TERMINAL cumulative fill, not the first partial
  // slice. Alpaca WS fill events carry filled_qty as the cumulative-so-far and onFill is
  // last-write-wins, so waiting for filledQty >= contracts books the whole lot (2026-08-25:
  // prod_real booked 2 of a 21-lot sliced fill; the other 19 rode unmanaged). Gates: the await
  // conditions, the breach-branch fill test, the repeg partial-fill skip, and the
  // startPositionWorkflow expectedQty feed. At DEFAULT_VERSION every site is byte-identical to
  // the first-slice behavior. Lost-terminal-event net: if the terminal WS event never arrives,
  // the TTL path's cancelOrder reconciles broker truth (ALREADY_FILLED -> full-qty adoption);
  // a genuinely partial lot at TTL is cancelled remainder-only and its filled portion surfaces
  // via the #817 partial-coverage page until #819 teaches the cancel result to carry it.
  private static final String VERSION_ENTRY_AWAIT_TERMINAL = "entry-await-terminal-fill-v1";

  // Edited-signal supersede (F1) correction window: a prior leg may be auto-superseded ONLY when
  // its
  // confirmed entry is within this window of the corrected signal's posted_at. A CODE CONSTANT by
  // design (NOT a strategy-config field) — keeps F1 Java-only, no schema/ConfigMap/live-YAML
  // surface.
  // Conservative: a correction posted minutes later (a deliberate new position) is OUT of window
  // and
  // never auto-cancels a real trade.
  private static final Duration SUPERSEDE_WINDOW = Duration.ofSeconds(120);

  /** Used when StrategyConfig.pending_ttl_paper_secs is null. */
  static final long DEFAULT_PENDING_TTL_PAPER_SECS = 90L;

  /**
   * Used when {@code StrategyConfig.repeg_after_ms} is null. 30s of the 90s TTL at the tight
   * initial limit, then the balance at the re-peg. Unset means "use this", NOT "disabled" — the
   * re-peg ships active; {@code repeg_after_ms = 0} is the per-tenant off-switch (see {@link
   * #repegAfterMs}).
   */
  static final long DEFAULT_REPEG_AFTER_MS = 30_000L;

  /**
   * Total wall-clock the re-peg will spend trying to get a live ask, across every attempt. Small on
   * purpose: it is spent out of the remaining TTL, and giving up merely costs the re-peg, whereas
   * over-waiting risks the un-cancellable working order described on {@link #optionQuote}.
   */
  private static final Duration REPEG_QUOTE_BUDGET = Duration.ofSeconds(10);

  /** Used when StrategyConfig.default_stc_fraction is null. */
  static final double DEFAULT_STC_FRACTION = 0.5;

  /**
   * Legacy default task queue used when a workflow runs before {@code cfg.broker_target} is
   * resolved (e.g. when the early reject path fires before {@code strategy.get} is called) or by
   * tests that don't go through the factory. Phase 2c.2 routes by {@code cfg.broker_target} via
   * {@link ExecActivitiesFactory}; this constant is retained for back-compat with the existing test
   * scaffolding that registers a single broker worker on this queue.
   */
  static final String EXEC_TASK_QUEUE_ALPACA_PAPER = "broker-alpaca-paper";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);

  /**
   * Hard-bounded audit stub for {@link #logAuditWhileOrderLive}. Total budget across every attempt,
   * so neither an unlimited retry chain nor an unstarted task can hold a live order hostage.
   */
  private final AuditActivities auditBounded =
      Workflow.newActivityStub(
          AuditActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(45))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  // P3-a: the live-promotion safety-gate verify. On the orchestrator-core queue (DEFAULT_OPTIONS),
  // same as the other read-side stubs.
  private final AuditQueryActivities auditQuery =
      Workflow.newActivityStub(AuditQueryActivities.class, DEFAULT_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, DEFAULT_OPTIONS);
  private final RiskActivities risk =
      Workflow.newActivityStub(RiskActivities.class, DEFAULT_OPTIONS);
  private final ContractActivities contract =
      Workflow.newActivityStub(ContractActivities.class, DEFAULT_OPTIONS);
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(PositionLookupActivities.class, DEFAULT_OPTIONS);
  private final AccountSnapshotMetricsActivities accountSnapshotMetrics =
      Workflow.newActivityStub(AccountSnapshotMetricsActivities.class, DEFAULT_OPTIONS);

  /**
   * PLAN-2026-08-04-bto-entry-repeg: the live-ask snapshot the entry re-peg anchors on, on the
   * market-data task queue.
   *
   * <p>HARD-BOUNDED ON PURPOSE. A BTO entry holds a LIVE limit order at the broker whose only
   * cancel is this workflow's own TTL path, and the sidecar starts these workflows with NO run
   * timeout. An unbounded wait here is therefore not merely "slow": it leaves real money working at
   * the broker with nothing left to cancel it, free to fill hours later against an anchor that has
   * long gone stale. Two Temporal defaults would each cause exactly that:
   *
   * <ul>
   *   <li>{@code ScheduleToStart} is unlimited by default, so if the market-data worker is not
   *       polling the task is never STARTED and {@code StartToCloseTimeout} never begins counting.
   *   <li>{@code RetryOptions} default to unlimited attempts, so a throwing Activity retries with
   *       backoff forever.
   * </ul>
   *
   * <p>{@code ScheduleToClose} bounds the whole attempt chain whichever one bites, and {@link
   * #repegEntry} treats ANY failure as "no quote" → no re-peg → today's plain TTL expiry. The
   * exit-side stub in {@link PositionWorkflowImpl} is deliberately NOT the model here: a stuck
   * flatten holds a POSITION, which reconciliation can still see and settle, whereas a stuck entry
   * holds an un-cancellable WORKING ORDER.
   */
  private final GetOptionQuoteActivity optionQuote =
      Workflow.newActivityStub(
          GetOptionQuoteActivity.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(REPEG_QUOTE_BUDGET)
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
              .build());

  /**
   * Phase 2c.2: built lazily inside {@link #handleBto} / {@link #handleStc} from the loaded {@code
   * StrategyConfig.broker_target}, so a paper BTO with {@code broker_target=alpaca-paper} routes to
   * {@code broker-alpaca-paper}. Determinism: factory input comes from a deterministic Activity
   * lookup, so replays rebuild the same stub.
   */
  private ExecActivities exec;

  private FillSignalPayload fillEvent;

  /**
   * Broker order ids of entry orders this workflow cancelled in order to re-peg.
   * PLAN-2026-08-04-bto-entry-repeg: the re-peg makes a workflow carry TWO entry orders over its
   * life, so {@link #onFill} needs to tell a fill for the standing order from a late fill for the
   * superseded one. Deterministic: only ever tested with {@code contains}, never iterated.
   */
  private final Set<String> supersededEntryOrderIds = new HashSet<>();

  /**
   * A fill signal for a superseded order that {@link #onFill} refused. Non-null means contracts may
   * be held with no PositionWorkflow managing them, which recon cannot auto-adopt (the journal row
   * is CANCELLED). Audited by the main path so it pages instead of vanishing.
   */
  private FillSignalPayload droppedSupersededFill;

  /**
   * Intent key of the order {@link #droppedSupersededFill} belongs to. Captured at supersede time
   * because {@code handleBto}'s local {@code intentKey} is REASSIGNED to the replacement — pointing
   * an unmanaged-lot page at the replacement would send the operator to the wrong order.
   */
  private String supersededEntryIntentKey;

  private boolean riskBreachReceived;
  private String riskBreachReason;
  private String riskBreachActor;

  /**
   * PLAN-2026-08-10-live-manual-bto: entry outcome reported by the {@link #entryStatus()} Query.
   * Plain mutable workflow state stamped by the paths below — assigning a field is NOT a command,
   * so none of this affects replay determinism or the recorded history.
   */
  private final CopytradeEntryStatus entryStatus = pendingEntryStatus();

  private static CopytradeEntryStatus pendingEntryStatus() {
    CopytradeEntryStatus s = new CopytradeEntryStatus();
    s.setSchemaVersion(1L);
    s.setState(CopytradeEntryStatus.State.PENDING);
    return s;
  }

  @Override
  public CopytradeEntryStatus entryStatus() {
    return entryStatus;
  }

  /**
   * Stamp the terminal REJECTED status alongside the {@code SignalRejected} audit every refusal
   * already emits. Kept as a helper so a new gate cannot emit the audit and forget the Query state
   * (which would leave the dashboard spinning on PENDING for an entry that will never happen).
   */
  private void rejectStatus(String reasonCode, String reasonDetail) {
    entryStatus.setState(CopytradeEntryStatus.State.REJECTED);
    entryStatus.setReasonCode(reasonCode);
    entryStatus.setReasonDetail(reasonDetail);
  }

  /**
   * Stamp the terminal FILLED status from a broker fill. Shared by the happy-path fill branch and
   * the two cancel-on-filled recoveries, so an adopted lot reports FILLED rather than the EXPIRED
   * its enclosing TTL branch would otherwise leave behind.
   */
  private void filledStatus(ContractResolveResult resolved, FillSignalPayload fill) {
    entryStatus.setState(CopytradeEntryStatus.State.FILLED);
    entryStatus.setOptionSymbol(resolved.optionSymbol());
    entryStatus.setBrokerOrderId(fill.getBrokerOrderId());
    entryStatus.setFilledQty(fill.getFilledQty());
    entryStatus.setAvgFillPrice(fill.getAvgFillPrice());
  }

  /**
   * PLAN-2026-08-10-live-manual-bto: true when this signal was hand-submitted by an operator from
   * the /live dashboard rather than parsed off Discord. Null/absent {@code source} (every
   * sidecar-emitted signal, and every payload in a pre-deploy replay history) is NOT manual.
   */
  private static boolean isManual(CopytradeSignalPayload payload) {
    return payload.getSource() == CopytradeSignalPayload.Source.MANUAL;
  }

  @Override
  public void onFill(FillSignalPayload event) {
    // PLAN-2026-08-04-bto-entry-repeg: drop a fill that belongs to a SUPERSEDED entry order.
    //
    // Before the re-peg a workflow had exactly one entry order, so accepting any fill was safe.
    // The re-peg cancels the first order and places a second, and the fill signal is at-least-once
    // and asynchronous — so a late (or duplicated) fill for the cancelled order can arrive AFTER
    // the replacement is standing. Adopting it would thread the wrong broker_order_id and
    // avg_fill_price into EntryFilled and into the PositionWorkflow that manages the exit, i.e. the
    // position would be tracked against an order that never filled.
    //
    // The synchronous cancel-on-filled path (cancelOrder returning FILLED) covers the case where
    // the broker reports the fill AT cancel time; this guard covers the signal that races past it.
    //
    // Fail-safe by construction, and deliberately NOT versioned: signal handlers must not call
    // Workflow.getVersion. The set is empty unless a re-peg actually superseded an order, so with
    // no re-peg every fill is accepted exactly as before. A fill carrying no broker order id is
    // likewise accepted rather than dropped — a listener that omits the field must never silently
    // strand a filled position.
    if (event != null
        && event.getBrokerOrderId() != null
        && supersededEntryOrderIds.contains(event.getBrokerOrderId())) {
      // Contradicts the broker's own cancel ack, so it cannot be adopted in place of the
      // replacement's fill. But a fill event means contracts may really have been bought, and a
      // silently dropped fill is a live options position with no stop, no trail and no time-stop:
      // recon will NOT auto-adopt it either, because the journal row is CANCELLED and
      // maybeAutoAdopt only runs for a FILLED row. Record it so the main path can page a human.
      // Signal handlers must not dispatch Activities, hence the flag rather than a logAudit here.
      droppedSupersededFill = event;
      return;
    }
    this.fillEvent = event;
  }

  @Override
  public void riskBreach(RiskBreachPayload payload) {
    int v = Workflow.getVersion(VERSION_RISK_BREACH, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // Signal handlers only set flags; the main path checks them at await/dispatch points.
    this.riskBreachReceived = true;
    this.riskBreachReason = payload.getReason();
    this.riskBreachActor = payload.getActor();
  }

  @Override
  public String process(CopytradeSignalPayload payload) {
    // Phase 2 (PLAN-2026-07-06): read the failure-audit version marker UNCONDITIONALLY at the very
    // top, BEFORE any command (the first command is the SignalReceived audit inside
    // processInternal). This keeps the command stream identical across versions: a NEW execution
    // records the marker then the SignalReceived audit; a pre-fix in-flight history (no marker)
    // replays getVersion -> DEFAULT_VERSION with no marker command, then matches the SignalReceived
    // audit. Only v>=1 emits the EntryWorkflowFailed audit on the failure path below.
    int failureAuditVersion =
        Workflow.getVersion(VERSION_ENTRY_FAILURE_AUDIT, Workflow.DEFAULT_VERSION, 1);
    try {
      return processInternal(payload);
    } catch (CanceledFailure cf) {
      // Temporal cancellation must propagate untouched (mirrors the dispatchAccountSnapshot /
      // metrics-emit CanceledFailure carve-outs) — it is not a failure to alert on.
      throw cf;
    } catch (TemporalFailure e) {
      // Genuine unhandled failure that terminates the workflow EXECUTION as FAILED: the
      // PreTradeCheckMisconfigured guard throw (the 2026-07-06 incident), the ExecActivitiesFactory
      // invalid-broker_target throw, or an unhandled ActivityFailure/ChildWorkflowFailure. Emit an
      // alertable audit BEFORE re-throwing so the failure pages (OrderFailureAlerter is
      // audit-driven and fires AFTER the audit row) — the workflow still FAILS (we only add
      // visibility, never swallow). Plain RuntimeException/NPE is deliberately NOT caught: those
      // are workflow-TASK failures (retried, non-terminal, commands discarded) whose existing loud
      // retry behavior must be preserved — catching them would only enqueue a discarded audit each
      // retry with no committed page.
      if (failureAuditVersion >= 1) {
        logAudit(payload, KIND_ENTRY_WORKFLOW_FAILED, entryFailureSubject(payload, e));
      }
      // PLAN-2026-08-10-live-manual-bto: mirror the alertable audit into the Query state so a
      // manual entry that died on an unhandled failure reports FAILED rather than a stale PENDING.
      // Stamped at EVERY version (unlike the audit) — it adds no command, so there is nothing to
      // gate, and a v=DEFAULT_VERSION in-flight execution deserves the same honest answer.
      entryStatus.setState(CopytradeEntryStatus.State.FAILED);
      entryStatus.setReasonDetail(e.getClass().getSimpleName());
      throw e;
    }
  }

  private String processInternal(CopytradeSignalPayload payload) {
    // Issue #308: enrich the SignalReceived subject with the parsed signal fields so the Discord
    // signal-feed mirror (SignalFeedAlerter) can render a "received" message at the fastest point —
    // before any risk gates. Carries action/ticker/expiry/strike/right/price/author/posted_at from
    // the CopytradeSignalPayload. Enums are stored as their wire string; dates/decimals are stored
    // as deterministic String renderings so the JSONB subject is replay-stable and self-describing.
    logAudit(payload, KIND_SIGNAL_RECEIVED, receivedSubject(payload));

    StrategyConfig config = strategy.get(payload.getTenantId(), payload.getStrategyId());

    // Phase 7: per-tenant strategy enable toggle. Read the marker UNCONDITIONALLY so the command
    // stream is identical across versions (see VERSION_STRATEGY_ENABLED_GATE). FAIL-SAFE: only an
    // explicit enabled==false blocks; absent/null/true proceed. Rejecting BEFORE the exec-stub
    // build means a disabled strategy is cleanly turned away regardless of broker_target
    // routability
    // — it never reaches placeOrder and starts no PositionWorkflow, mirroring the live-promotion
    // refusal shape (SignalRejected + return signal_id).
    int enabledGateVersion =
        Workflow.getVersion(VERSION_STRATEGY_ENABLED_GATE, Workflow.DEFAULT_VERSION, 1);
    if (enabledGateVersion >= 1 && Boolean.FALSE.equals(config.getEnabled())) {
      logAudit(
          payload,
          KIND_SIGNAL_REJECTED,
          subject(
              "signal_id", payload.getSignalId(),
              "reason_code", "STRATEGY_DISABLED",
              "reason_detail", "strategy_disabled",
              "outcome", "REJECTED"));
      rejectStatus("STRATEGY_DISABLED", "strategy_disabled");
      return payload.getSignalId();
    }

    // Phase 2c.2: route exec Activities to broker-<broker_target>. The factory throws a
    // non-retryable ApplicationFailure on invalid targets; let it propagate so the workflow
    // fails fast (an unroutable broker_target is a config bug, not a transient error).
    this.exec = ExecActivitiesFactory.forTarget(config.getBrokerTarget().value());

    // PLAN-2026-08-10-live-manual-bto: entryStatus reports the ENTRY. A non-BTO signal opens none,
    // so give it a terminal answer here rather than letting a caller poll PENDING forever. Covers
    // STC, AVG and the defensive default branch in one place. Field assignment, not a command.
    if (payload.getAction() != CopytradeSignalPayload.Action.BTO) {
      rejectStatus("NOT_AN_ENTRY", "action=" + payload.getAction().value());
    }

    switch (payload.getAction()) {
      case BTO:
        return handleBto(payload, config);
      case STC:
        return handleStc(payload, config);
      case AVG:
        return handleAvg(payload, config);
      default:
        return payload.getSignalId();
    }
  }

  private String handleBto(CopytradeSignalPayload payload, StrategyConfig config) {
    // Computed once at the top: the same limit feeds pre-trade notional, sizing, and the
    // OrderIntent/audit subject — guarantees those three views agree on max-acceptable cost.
    PricedLimit priced = BtoPricing.computeBtoLimit(payload, config);
    // PLAN-2026-08-04-bto-entry-repeg: read UNCONDITIONALLY at this stable scope (the same
    // discipline as VERSION_NOTIONAL_CAP_CLAMP below) so the marker resolves identically on every
    // replay regardless of which branches the config happens to take.
    int repegVersion = Workflow.getVersion(VERSION_BTO_ENTRY_REPEG, Workflow.DEFAULT_VERSION, 1);
    long repegAfterMs = repegAfterMs(config);
    // 0 DISABLES on BOTH fields. Without this, zeroing repeg_ceiling_pct would fall through
    // BtoPricing's null/ZERO-means-unset convention and silently grant the 10% DEFAULT — i.e. an
    // operator zeroing the budget to remove it would get the widest budget instead. Same sentinel,
    // same meaning, on both halves of the feature.
    BigDecimal repegCeilingPct = config.getRepegCeilingPct();
    boolean ceilingDisabled = repegCeilingPct != null && repegCeilingPct.signum() == 0;
    boolean repegActive =
        repegVersion >= 1
            && repegAfterMs > 0
            && !ceilingDisabled
            && repegAfterMs < entryPendingTtlSecs(config) * 1000L;
    // The TRUE max cost of this entry: the re-peg may reach the ceiling, so the risk gates and
    // sizing must budget against the ceiling rather than the (tighter) initial peg. Gating against
    // the max is what lets the re-peg itself skip a re-check — it is already covered by the
    // decision taken here. On the inactive path this is priced.limit(), byte-identical to before.
    //
    // These flow into Activity INPUTS only, so they add no command to the history and need no
    // marker of their own (Temporal compares command type/ordering, not payloads).
    BigDecimal maxEntryCost =
        repegActive ? BtoPricing.computeRepegCeiling(payload, config) : priced.limit();
    // Issue #112: Version gate retires the PR #111 deploy-time-drain mitigation. Pre-#111
    // in-flight workflows replay through the v=DEFAULT_VERSION branch (single checkEntry with
    // null preTradeResult); new executions take v>=1 and run the full assert → dispatch →
    // checkEntry sequence.
    int preTradeDispatchVersion =
        Workflow.getVersion(VERSION_PRE_TRADE_DISPATCH, Workflow.DEFAULT_VERSION, 1);
    RiskDecision decision;
    // The broker CASH read for the notional-cap gate, hoisted so account_cash sizing reuses the
    // SAME value below (one dispatch, two consumers). Stays null on the legacy paths that never
    // dispatch (DEFAULT_VERSION pre-trade / check-entry / account-equity branches); those paths are
    // also incompatible with account_cash sizing only on already-running legacy histories, where
    // capital_source physically could not have been account_cash anyway.
    BigDecimal accountCash = null;
    // #790: the net-liquidation EQUITY from the SAME single snapshot dispatch (never a second
    // command). null = never dispatched; ZERO = dispatched but unavailable (fail closed below).
    BigDecimal accountEquity = null;
    if (preTradeDispatchVersion == Workflow.DEFAULT_VERSION) {
      // Legacy pre-#111 path: single checkEntry call with null preTradeResult. Reachable only by
      // CopytradeSignalWorkflow executions that began before the pre-trade-dispatch-v2 patch was
      // deployed. New executions take the v>=1 branch.
      decision = risk.checkEntry(payload, config, null);
    } else {
      // Skip the assertion round-trip when the gate is off; the Activity itself short-circuits but
      // the dispatch cost is paid regardless.
      if (Boolean.TRUE.equals(config.getPreTradeCheckEnabled())) {
        risk.assertPreTradeCheckRoutable(config);
      }
      PreTradeCheckResult preTradeResult = dispatchPreTradeCheck(payload, config, priced.limit());
      int checkEntryWithLimitVersion =
          Workflow.getVersion(VERSION_CHECK_ENTRY_WITH_LIMIT, Workflow.DEFAULT_VERSION, 1);
      if (checkEntryWithLimitVersion == Workflow.DEFAULT_VERSION) {
        // In-flight v>=1 workflows whose history recorded a CheckEntry call before this gate
        // landed.
        decision = risk.checkEntry(payload, config, preTradeResult);
      } else {
        // Dispatch the broker-<broker_target> account read and thread the equity into
        // the notional-cap gate. Replay-gated: in-flight v>=1 histories that recorded the 4-arg
        // CheckEntryWithLimit call keep the legacy path (equity from the PortfolioSnapshot seam).
        int accountEquityVersion =
            Workflow.getVersion(VERSION_ACCOUNT_EQUITY_DISPATCH, Workflow.DEFAULT_VERSION, 1);
        if (accountEquityVersion == Workflow.DEFAULT_VERSION) {
          decision = risk.checkEntryWithLimit(payload, config, preTradeResult, maxEntryCost, null);
        } else {
          // One dispatch feeds the notional-cap gate AND account_cash AND account_equity (#790)
          // sizing — the snapshot is fetched once and reused at the sizing block below, never
          // dispatched twice. The cash normalization (null result/cash -> ZERO) is byte-identical
          // to the pre-#790 in-method normalization.
          // null snapshot = never dispatched (no cap, no tracking source) and MUST stay null:
          // checkEntryWithLimit treats null cash as no-cap-context, ZERO as fail-closed-reject.
          AccountSnapshotResult accountSnapshot = dispatchAccountSnapshot(payload, config);
          if (accountSnapshot != null) {
            accountCash =
                accountSnapshot.getCash() == null ? BigDecimal.ZERO : accountSnapshot.getCash();
            accountEquity =
                accountSnapshot.getEquity() == null ? BigDecimal.ZERO : accountSnapshot.getEquity();
          }
          decision =
              risk.checkEntryWithLimit(payload, config, preTradeResult, maxEntryCost, accountCash);
        }
      }
    }
    if (!decision.allowed()) {
      Map<String, Object> rejectSubject =
          subject(
              "signal_id", payload.getSignalId(),
              "reason_code", decision.reason().name(),
              "outcome", "REJECTED");
      if (decision.detail() != null) {
        rejectSubject.put("reason_detail", decision.detail());
      }
      logAudit(payload, KIND_SIGNAL_REJECTED, rejectSubject);
      rejectStatus(decision.reason().name(), decision.detail());
      return payload.getSignalId();
    }

    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));

    // capital_source sizing switch. Read the marker UNCONDITIONALLY (before any branch) so the
    // command stream is identical across versions; see VERSION_ACCOUNT_CASH_SIZING. 'static'
    // (default; null/absent) is BYTE-IDENTICAL to the pre-change path: same capitalForStrategy
    // read,
    // same computeContracts, no new command. 'account_cash' sizes from the broker CASH already read
    // for the notional-cap gate (accountCash above) and is FAIL-CLOSED: a null/zero cash (broker
    // outage → the dispatchAccountSnapshot ZERO sentinel, or a genuinely empty account) REJECTS the
    // entry — no placeOrder, no PositionWorkflow — and NEVER falls back to the static $100k.
    int cashSizingVersion =
        Workflow.getVersion(VERSION_ACCOUNT_CASH_SIZING, Workflow.DEFAULT_VERSION, 1);
    int equitySizingVersion =
        Workflow.getVersion(VERSION_ACCOUNT_EQUITY_SIZING, Workflow.DEFAULT_VERSION, 1);
    BigDecimal capital;
    if (cashSizingVersion >= 1 && StrategyConfigs.accountCashSizing(config)) {
      if (accountCash == null || accountCash.signum() <= 0) {
        rejectCapitalUnavailable(payload);
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
      capital = accountCash;
    } else if (equitySizingVersion >= 1 && StrategyConfigs.accountEquitySizing(config)) {
      // #790: size from live net-liquidation equity — capital_weight is a plain fraction of the
      // account (0.10 = 10% of equity), auto-tracking instead of the frozen static-weight
      // encoding. Identical fail-closed contract to account_cash: null/zero equity (broker outage
      // -> the zeroSnapshot sentinel, or a malformed result) REJECTS the entry and NEVER falls
      // back to the static base.
      if (accountEquity == null || accountEquity.signum() <= 0) {
        rejectCapitalUnavailable(payload);
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
      capital = accountEquity;
    } else {
      capital = strategy.capitalForStrategy(payload.getTenantId(), payload.getStrategyId());
    }
    // Cash-weight sizing + scale-in reduction in one pass. The EntrySizing outcome carries the
    // pre-scale-in base and the matched cue, so the audit below reads them from Sizing's actual
    // decision rather than re-deriving (which could drift). scale-in reduces the qty by
    // entry_scale_in_fraction when the BTO tail carries a scale-in cue (see Sizing#computeEntry).
    Sizing.EntrySizing sized = Sizing.computeEntry(payload, config, capital, maxEntryCost);
    long contracts = sized.contracts();

    // PLAN-2026-08-10-live-manual-bto: an operator-typed contract count REPLACES capital-weight
    // sizing for this entry. Sizing.computeEntry above still runs — it is a pure function (no
    // command), and the SignalAccepted audit below reports its pre-override base so forensics can
    // still see what auto-sizing WOULD have chosen next to what the operator asked for.
    //
    // The bounds check is UNCONDITIONAL, deliberately: Sizing already clamps the auto-sized path to
    // [min_contracts, max_contracts], so without this the manual path would be the one way for an
    // entry to exceed the tenant's per-entry exposure ceiling. Reject rather than clamp — the
    // operator named a size, and quietly filling a different one is not an acceptable answer to a
    // real-money instruction.
    //
    // Replay: reachable ONLY when qty_override is non-null, which no pre-deploy history can carry
    // (nothing emitted the field before this change), so a legacy replay never reaches the new
    // logAudit command and needs no getVersion marker.
    if (payload.getQtyOverride() != null) {
      long requested = payload.getQtyOverride();
      if (requested < config.getMinContracts() || requested > config.getMaxContracts()) {
        logAudit(
            payload,
            KIND_SIGNAL_REJECTED,
            subject(
                "signal_id",
                payload.getSignalId(),
                "reason_code",
                REASON_MANUAL_QTY_OUT_OF_BOUNDS,
                "reason_detail",
                "requested="
                    + requested
                    + " min_contracts="
                    + config.getMinContracts()
                    + " max_contracts="
                    + config.getMaxContracts(),
                "outcome",
                "REJECTED"));
        rejectStatus(
            REASON_MANUAL_QTY_OUT_OF_BOUNDS,
            "requested="
                + requested
                + " min_contracts="
                + config.getMinContracts()
                + " max_contracts="
                + config.getMaxContracts());
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
      contracts = requested;
    }

    // Phase F4B (clamp-to-fit headroom): instead of letting checkNotionalCap reject an over-cap
    // entry, SIZE IT DOWN to the largest qty that fits the remaining notional-cap headroom. The
    // final qty is MIN(cash-weight sizing above, cap-headroom contracts, max_contracts), floored;
    // when that clamped qty falls below min_contracts the entry is STILL rejected
    // (NOTIONAL_CAP_EXCEEDED) — a sub-minimum entry isn't worth placing. The headroom dispatch is a
    // NEW command, gated behind VERSION_NOTIONAL_CAP_CLAMP (read UNCONDITIONALLY): legacy in-flight
    // histories (DEFAULT_VERSION) take the byte-identical pre-change path (no dispatch, the
    // existing
    // reject stands). The dispatch is further gated on the cap being configured, so a no-cap
    // strategy adds NO command at v>=1 either. The cap-headroom math reuses the SAME cash already
    // read for the notional-cap gate (accountCash), so no second account read is incurred.
    int clampVersion = Workflow.getVersion(VERSION_NOTIONAL_CAP_CLAMP, Workflow.DEFAULT_VERSION, 1);
    if (clampVersion >= 1 && StrategyConfigs.notionalCapConfigured(config)) {
      long headroom =
          risk.notionalCapHeadroomContracts(
              config, maxEntryCost, accountCash, payload.getTenantId(), payload.getStrategyId());
      // MIN(cash-weight sizing, cap-headroom, max_contracts). The max_contracts term is
      // redundant-but-defensive: Sizing.computeContracts already clamped `contracts` to
      // max_contracts, so it is a no-op today — kept as belt-and-suspenders against a future Sizing
      // change that stops clamping. The active constraints here are the cash sizing and the
      // headroom.
      long clamped = Math.min(contracts, Math.min(headroom, config.getMaxContracts()));
      if (clamped < config.getMinContracts()) {
        logAudit(
            payload,
            KIND_SIGNAL_REJECTED,
            subject(
                "signal_id",
                payload.getSignalId(),
                "reason_code",
                "NOTIONAL_CAP_EXCEEDED",
                "reason_detail",
                "clamp_below_min headroom=" + headroom + " min=" + config.getMinContracts(),
                "outcome",
                "REJECTED"));
        rejectStatus(
            "NOTIONAL_CAP_EXCEEDED",
            "clamp_below_min headroom=" + headroom + " min=" + config.getMinContracts());
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
      // PLAN-2026-08-10-live-manual-bto: clamping DOWN is right for an auto-sized signal (the
      // capital-weight qty is our estimate, so fitting it to the headroom is a refinement). It is
      // wrong for an operator-typed qty: silently buying 2 when the operator asked for 10 is a
      // different trade than the one authorized. Reject and let them re-decide against the
      // refreshed headroom. Reachable only on the manual path, so the auto-sized behavior is
      // byte-identical to before.
      if (payload.getQtyOverride() != null && clamped < contracts) {
        logAudit(
            payload,
            KIND_SIGNAL_REJECTED,
            subject(
                "signal_id",
                payload.getSignalId(),
                "reason_code",
                "NOTIONAL_CAP_EXCEEDED",
                "reason_detail",
                "manual_qty_exceeds_headroom requested=" + contracts + " headroom=" + headroom,
                "outcome",
                "REJECTED"));
        rejectStatus(
            "NOTIONAL_CAP_EXCEEDED",
            "manual_qty_exceeds_headroom requested=" + contracts + " headroom=" + headroom);
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
      contracts = clamped;
    }

    Map<String, Object> acceptedSubject =
        subject(
            "signal_id",
            payload.getSignalId(),
            "option_symbol",
            resolved.optionSymbol(),
            "contracts",
            contracts,
            "contracts_pre_scale_in",
            sized.base(),
            "scale_in_applied",
            sized.scaleInApplied(),
            "scale_in_phrase",
            sized.scaleInPhrase(),
            "scale_in_fraction",
            config.getEntryScaleInFraction(),
            "ref_premium",
            payload.getPrice());
    // PLAN-2026-08-10-live-manual-bto: tag the manual path so forensics can separate operator
    // entries from copied ones, and record the qty the operator actually asked for next to the
    // auto-sizing this overrode. Added ONLY on the manual path so a Discord signal's audit subject
    // is byte-identical to before (no null-valued keys appearing on every row).
    if (isManual(payload)) {
      acceptedSubject.put("source", CopytradeSignalPayload.Source.MANUAL.value());
      acceptedSubject.put("qty_override", payload.getQtyOverride());
      acceptedSubject.put("contracts_auto_sized", sized.contracts());
    }
    logAudit(payload, KIND_SIGNAL_ACCEPTED, acceptedSubject);

    // Edited-signal supersede (F1): when this corrected BTO matches a prior just-filled leg on
    // tenant+strategy+underlying+strike+right but a DIFFERENT expiry, and the prior leg's entry is
    // within the correction window, AUTO cancel/replace the wrong-expiry leg before placing this
    // one. Version-gated (read unconditionally): legacy in-flight histories take the byte-identical
    // pre-F1 path. Conservative — supersede fires ONLY when ALL guardrails hold.
    maybeSupersedePriorLeg(payload, config, resolved);

    // P3-a (multi-tenant-broker-credentials): the LIVE-only live-promotion dispatch gate. A real-
    // money BTO may only place an order when a fresh (not-stale) LivePromotionApproved row exists
    // for (tenant_id, strategy_id, broker_target). The verify fails CLOSED — ABSENT/STALE/VERIFY_
    // ERROR all refuse the order (no placeOrder, no PositionWorkflow), emit LivePromotionMissing,
    // and return the signal_id (the same fail-closed return shape as the reject path).
    //
    // Replay: getVersion is read UNCONDITIONALLY (before the isLive test). The new branch is
    // reachable only at gateV>=1; for DEFAULT_VERSION (legacy in-flight histories) the path is
    // byte-identical (only the marker is added). The paper path (!isLive) schedules ZERO verify
    // activities and emits NO new command at any version. Staleness uses Workflow.currentTimeMillis
    // (NOT OffsetDateTime.now) so the window is deterministic across replays.
    int gateV = Workflow.getVersion(VERSION_LIVE_PROMOTION_GATE, Workflow.DEFAULT_VERSION, 1);
    if (gateV >= 1 && StrategyConfigInvariants.isLive(config)) {
      long sinceMillis = Workflow.currentTimeMillis() - LIVE_PROMOTION_TTL.toMillis();
      OffsetDateTime notStaleSince =
          OffsetDateTime.ofInstant(Instant.ofEpochMilli(sinceMillis), ZoneOffset.UTC);
      LivePromotionStatus status =
          auditQuery.checkLivePromotion(
              payload.getTenantId(),
              payload.getStrategyId(),
              config.getBrokerTarget().value(),
              notStaleSince);
      if (status != LivePromotionStatus.VALID) {
        logAudit(
            payload,
            KIND_LIVE_PROMOTION_MISSING,
            subject(
                "signal_id", payload.getSignalId(),
                "tenant_id", payload.getTenantId(),
                "strategy_id", payload.getStrategyId(),
                "broker_target", config.getBrokerTarget().value(),
                "reason", status.name().toLowerCase(Locale.ROOT),
                "outcome", "REJECTED"));
        rejectStatus("live_promotion_missing", status.name().toLowerCase(Locale.ROOT));
        // Fail-closed: NO placeOrder, NO PositionWorkflow.
        return payload.getSignalId();
      }
    }

    // #818: read once on the main path before the order exists; startPositionWorkflow re-reads
    // the same ID (cached, consistent within an execution). orderedQty is the effectively-final
    // capture of the (possibly operator-overridden) contract count for the await lambdas.
    int entryAwaitTerminalVersion =
        Workflow.getVersion(VERSION_ENTRY_AWAIT_TERMINAL, Workflow.DEFAULT_VERSION, 1);
    final long orderedQty = contracts;
    String intentKey = Workflow.getInfo().getWorkflowId() + ":entry";
    OrderIntent intent = newIntent(payload, config, resolved, contracts, intentKey, priced.limit());
    OrderIntentResult placed = exec.placeOrder(intent);

    logAuditWhileOrderLive(
        payload,
        KIND_ORDER_SUBMITTED,
        subject(
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "option_symbol", resolved.optionSymbol(),
            "side", "BUY",
            "qty", contracts,
            "broker_target", config.getBrokerTarget().value(),
            "limit_price_strategy", priced.strategy().wireKey()));

    entryStatus.setState(CopytradeEntryStatus.State.SUBMITTED);
    entryStatus.setOptionSymbol(resolved.optionSymbol());
    entryStatus.setContracts(contracts);
    entryStatus.setBrokerOrderId(placed.getBrokerOrderId());

    long ttlSecs = entryPendingTtlSecs(config);
    // Phase 5: also wake on risk_breach so the cascade can short-circuit the BTO.
    boolean filled;
    if (!repegActive) {
      filled =
          Workflow.await(
              Duration.ofSeconds(ttlSecs),
              () -> entryFillComplete(orderedQty, entryAwaitTerminalVersion) || riskBreachReceived);
    } else {
      // PLAN-2026-08-04-bto-entry-repeg: split the TTL into [initial peg | re-peg] windows. The
      // first window is today's behavior verbatim — the tight limit gets first refusal. Only if it
      // has NOT filled do we spend the wider budget, and then only as far as the live ask.
      filled =
          Workflow.await(
              Duration.ofMillis(repegAfterMs),
              () -> entryFillComplete(orderedQty, entryAwaitTerminalVersion) || riskBreachReceived);
      if (!filled) {
        // #818: a PARTIAL fill on the original order must SKIP the re-peg. Cancelling a
        // partially-filled order and placing a full-size replacement would over-buy (partial +
        // replacement > ordered). Keep the original order standing and spend the remaining TTL
        // waiting for its terminal fill. Reachable only at v>=1: at DEFAULT_VERSION any fill made
        // filled=true above, so this branch preserves the legacy command stream exactly.
        boolean partialFillPresent = entryAwaitTerminalVersion >= 1 && fillEvent != null;
        if (!partialFillPresent) {
          RepegOutcome outcome =
              repegEntry(payload, config, resolved, contracts, intentKey, placed, priced.limit());
          if (outcome.adopted()) {
            // The original order filled in the cancel race; repegEntry already emitted EntryFilled
            // and started the PositionWorkflow. No replacement was placed — nothing left to await.
            return payload.getSignalId();
          }
          if (outcome.replacement() != null) {
            // The replacement is now the order of record: the TTL-expiry and cancel-on-filled
            // paths below must act on ITS key/broker id, not the cancelled original's.
            placed = outcome.replacement();
            intentKey = placed.getIntentKey();
          }
        }
        filled =
            Workflow.await(
                Duration.ofSeconds(ttlSecs).minus(Duration.ofMillis(repegAfterMs)),
                () ->
                    entryFillComplete(orderedQty, entryAwaitTerminalVersion) || riskBreachReceived);
      }
    }

    if (droppedSupersededFill != null) {
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_FAILED,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              resolved.optionSymbol(),
              "intent_key",
              supersededEntryIntentKey,
              "broker_order_id",
              droppedSupersededFill.getBrokerOrderId(),
              "filled_qty",
              droppedSupersededFill.getFilledQty(),
              "avg_fill_price",
              droppedSupersededFill.getAvgFillPrice(),
              "reason",
              "repeg",
              "severity",
              "ERROR",
              "note",
              "superseded_order_fill_dropped_possible_unmanaged_lot"));
    }

    // #818: at v>=1 a breach with only a PARTIAL fill also takes the cancel-reconcile path — the
    // broker-confirmed cancel result is the fill truth (full fill in the race -> adopt; partial ->
    // #819's gap, surfaced by the #817 page). At DEFAULT_VERSION this is byte-identical to the
    // old fillEvent == null test.
    if (riskBreachReceived && !entryFillComplete(orderedQty, entryAwaitTerminalVersion)) {
      // Cascade arrived before the onFill signal landed — but the broker may have already filled
      // (the async onFill races the breach and can lose). Issue #274: reconcile broker truth via
      // cancelOrder, which returns state=FILLED with broker-confirmed filled_qty/avg_fill_price on
      // a cancel-on-filled race (ExecActivitiesImpl ALREADY_FILLED → markFilled), and adopt the
      // filled lot through the existing handleCancelOnFilled → startPositionWorkflow recovery (the
      // TTL-expiry path already does this) instead of discarding the result and orphaning.
      //
      // Versioned for replay determinism. The legacy (v=DEFAULT_VERSION) branch preserves the
      // pre-fix command order EXACTLY — auditRiskBreachAbort (Log) before cancelOrder (CancelOrder)
      // — so replays of in-flight pre-fix histories that already took this branch match their
      // recorded ActivityTaskScheduled sequence. The new (v>=1) branch reorders to capture the
      // cancel result first, which is safe because it only ever runs against histories minted with
      // the version marker present.
      int breachAdoptionVersion =
          Workflow.getVersion(VERSION_BREACH_FILLED_ADOPTION, Workflow.DEFAULT_VERSION, 1);
      if (breachAdoptionVersion == Workflow.DEFAULT_VERSION) {
        // Legacy path: audit-and-abort, best-effort cancel (result discarded), unchanged ordering.
        auditRiskBreachAbort(payload, "bto_pre_fill", intentKey);
        try {
          exec.cancelOrder(intentKey);
        } catch (RuntimeException ignored) {
          // Best-effort: reconciliation closes any orphan broker order.
        }
        return payload.getSignalId();
      }
      OrderIntentResult cancelResult;
      try {
        cancelResult = exec.cancelOrder(intentKey);
      } catch (RuntimeException ignored) {
        // Best-effort cancel failed: audit-and-abort; reconciliation closes any orphan broker
        // order.
        auditRiskBreachAbort(payload, "bto_pre_fill", intentKey);
        return payload.getSignalId();
      }
      if (cancelResult.getState() == OrderIntentResult.State.FILLED) {
        handleCancelOnFilled(payload, config, resolved, cancelResult, RECOVERY_CANCEL_ON_FILLED);
        return payload.getSignalId();
      }
      // #818: the cancel reported no fill, but slices already landed locally — book them, THEN
      // record the breach abort for the cancelled remainder. Both events tell the true story;
      // discarding fillEvent here left a real filled lot with no workflow (risk-review finding).
      // Reachable only at v>=1: at DEFAULT_VERSION this branch required fillEvent == null.
      if (entryAwaitTerminalVersion >= 1
          && fillEvent != null
          && fillEvent.getFilledQty() != null
          && fillEvent.getFilledQty() > 0L) {
        // Order matters (PR #824 review, Major): auditRiskBreachAbort STAMPS entryStatus=ABORTED
        // as well as writing the audit row, so the abort must be recorded FIRST and the adoption
        // LAST — adoptLocalPartialFill's filledStatus then leaves the operator-facing
        // entryStatus() Query reporting FILLED for a lot that is genuinely booked and managed
        // (the same adopted-lot-reports-FILLED contract as the TTL/cancel-on-filled paths). Both
        // audit rows are emitted either way; only the final mutable status differs.
        auditRiskBreachAbort(payload, "bto_pre_fill_partial_adopted", intentKey);
        adoptLocalPartialFill(payload, config, resolved, RECOVERY_PARTIAL_AT_BREACH);
        return payload.getSignalId();
      }
      // Genuinely cancelled (or any non-FILLED state): keep audit-and-abort.
      auditRiskBreachAbort(payload, "bto_pre_fill", intentKey);
      return payload.getSignalId();
    }

    if (filled) {
      Map<String, Object> entrySubject =
          subject(
              "signal_id", payload.getSignalId(),
              "intent_key", placed.getIntentKey(),
              "broker_order_id", fillEvent.getBrokerOrderId(),
              "filled_qty", fillEvent.getFilledQty(),
              "avg_fill_price", fillEvent.getAvgFillPrice(),
              "outcome", "FILLED");
      // Issue #276: emit the per-symbol correlation key for the DailyPnl FIFO grouping, gated so
      // legacy replay histories reproduce the old subject (no option_symbol) deterministically.
      if (Workflow.getVersion(VERSION_ENTRY_FILLED_OPTION_SYMBOL, Workflow.DEFAULT_VERSION, 1)
          >= 1) {
        entrySubject.put("option_symbol", resolved.optionSymbol());
      }
      logAudit(payload, KIND_ENTRY_FILLED, entrySubject);
      filledStatus(resolved, fillEvent);

      // Phase 3: start PositionWorkflow + cache OCC → workflow_id mapping. Versioned so
      // Phase 2b workflows in flight on replay don't attempt to spawn a child.
      int v = Workflow.getVersion(VERSION_POSITION_HANDOFF, Workflow.DEFAULT_VERSION, 1);
      if (v >= 1) {
        startPositionWorkflow(payload, config, resolved, fillEvent);
      }
      return payload.getSignalId();
    }

    handleTtlExpired(payload, config, resolved, placed, intentKey, ttlSecs);
    return payload.getSignalId();
  }

  /**
   * Edited-signal supersede (F1). Conservative auto-cancel of a prior wrong-expiry leg when a
   * corrected BTO arrives. Fires the supersede ONLY when ALL guardrails hold:
   *
   * <ul>
   *   <li>same tenant + strategy + underlying + strike + right, DIFFERENT expiry (enforced by the
   *       lookup Activity);
   *   <li>the prior leg's confirmed entry ({@code candidate.entryAt}) is within {@link
   *       #SUPERSEDE_WINDOW} of this corrected signal's {@code posted_at};
   *   <li>the prior leg is confirmed (non-null {@code entryAt}) and has NOT already partially
   *       exited.
   * </ul>
   *
   * <p>Replay: {@link #VERSION_BTO_CORRECTION_SUPERSEDE} is read UNCONDITIONALLY at the top; the
   * lookup Activity + supersede signal + audit are reachable only at v&gt;=1, so legacy in-flight
   * histories (DEFAULT_VERSION) emit no new command. Required signal fields missing (no strike /
   * right / expiry / posted_at) short-circuit BEFORE the lookup dispatch so the command stream
   * stays stable for malformed signals. The window comparison uses the deterministic {@code
   * posted_at} and the candidate's deterministic {@code entryAt} — no wall-clock read.
   */
  private void maybeSupersedePriorLeg(
      CopytradeSignalPayload payload, StrategyConfig config, ContractResolveResult resolved) {
    int v = Workflow.getVersion(VERSION_BTO_CORRECTION_SUPERSEDE, Workflow.DEFAULT_VERSION, 1);
    if (v == Workflow.DEFAULT_VERSION) {
      return;
    }
    // PLAN-2026-08-10-live-manual-bto: NEVER auto-supersede off a hand-typed entry. This whole
    // mechanism exists to undo an author's EDITED Discord signal — the corrected line arrives
    // seconds after the wrong one, so a same-underlying/strike/right + different-expiry match
    // inside SUPERSEDE_WINDOW is strong evidence of a correction. An operator manually opening a
    // different expiry carries no such meaning, and treating it as a correction would silently
    // FLATTEN a live Discord-sourced leg the operator never touched. Placed AFTER the version read
    // so the command stream for legacy histories is unchanged, and reachable only for payloads
    // carrying source=manual (which no pre-deploy history can).
    if (isManual(payload)) {
      return;
    }
    // Required fields for an expiry-correction match. Any missing → no lookup, no command.
    if (payload.getStrike() == null
        || payload.getRight() == null
        || payload.getExpiry() == null
        || payload.getPostedAt() == null) {
      return;
    }
    PositionLookupActivities.SupersedeCandidate candidate =
        positionLookup.findOpenPositionByUnderlyingStrikeRight(
            payload.getTenantId(),
            payload.getStrategyId(),
            payload.getTicker(),
            payload.getStrike(),
            payload.getRight().value(),
            payload.getExpiry().toString());
    if (candidate == null) {
      return;
    }
    // Guardrail: prior leg must be confirmed (entryAt stamped) and NOT already partially exited.
    if (candidate.entryAt() == null || candidate.partialExited()) {
      return;
    }
    // Guardrail: prior leg's entry within the 120s correction window of the corrected posted_at.
    Duration delta = Duration.between(candidate.entryAt(), payload.getPostedAt()).abs();
    if (delta.compareTo(SUPERSEDE_WINDOW) > 0) {
      return;
    }

    // All guardrails held — emit the auditable supersede DECISION (BOTH OCCs) then signal the prior
    // leg's PositionWorkflow to cancel/flatten the wrong-expiry leg.
    logAudit(
        payload,
        KIND_BTO_CORRECTION_SUPERSEDED,
        subject(
            "signal_id", payload.getSignalId(),
            "corrected_option_symbol", resolved.optionSymbol(),
            "superseded_option_symbol", candidate.occ(),
            "superseded_workflow_id", candidate.workflowId(),
            "window_secs", SUPERSEDE_WINDOW.toSeconds(),
            "delta_secs", delta.toSeconds(),
            // option_symbol mirrors the corrected leg so OrderFailureAlerter's BTO embed renders
            // it.
            "option_symbol", resolved.optionSymbol()));

    ExternalWorkflowStub priorLeg = Workflow.newUntypedExternalWorkflowStub(candidate.workflowId());
    try {
      priorLeg.signal("supersede", payload.getSignalId(), resolved.optionSymbol());
    } catch (SignalExternalWorkflowException | ApplicationFailure e) {
      // The prior leg died between the lookup and the signal (TOCTOU) — the wrong leg is already
      // gone; the supersede decision audit above stands. Do NOT fail the corrected BTO over it.
      Workflow.getLogger(CopytradeSignalWorkflowImpl.class)
          .warn(
              "supersede signal dispatch failed (prior leg likely already closed) wf_id={} err={}",
              candidate.workflowId(),
              e.getMessage());
    }
  }

  private void auditRiskBreachAbort(
      CopytradeSignalPayload payload, String stage, String intentKey) {
    Map<String, Object> s =
        subject(
            "signal_id",
            payload.getSignalId(),
            "stage",
            stage,
            "reason",
            riskBreachReason == null ? "" : riskBreachReason,
            "actor",
            riskBreachActor == null ? "" : riskBreachActor);
    if (intentKey != null) {
      s.put("intent_key", intentKey);
    }
    logAudit(payload, KIND_SIGNAL_ABORTED_BY_RISK_BREACH, s);
    // Every audit-and-abort path routes through here, so one stamp covers them all.
    entryStatus.setState(CopytradeEntryStatus.State.ABORTED);
    entryStatus.setReasonDetail(riskBreachReason);
  }

  /**
   * #818: adopt the LOCALLY-held partial fill (the {@code fillEvent} field) when the entry ended
   * without a broker-confirmed terminal fill. Same EntryFilled/filledStatus/startPositionWorkflow
   * trio as the happy path, with the {@code recovery} label carrying why. Caller gates on the
   * terminal-await version and a positive filledQty.
   */
  private void adoptLocalPartialFill(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      String recovery) {
    Map<String, Object> subj =
        subject(
            "signal_id", payload.getSignalId(),
            "broker_order_id", fillEvent.getBrokerOrderId(),
            "filled_qty", fillEvent.getFilledQty(),
            "avg_fill_price", fillEvent.getAvgFillPrice(),
            "outcome", "FILLED",
            "recovery", recovery);
    if (Workflow.getVersion(VERSION_ENTRY_FILLED_OPTION_SYMBOL, Workflow.DEFAULT_VERSION, 1) >= 1) {
      subj.put("option_symbol", resolved.optionSymbol());
    }
    logAudit(payload, KIND_ENTRY_FILLED, subj);
    filledStatus(resolved, fillEvent);
    startPositionWorkflow(payload, config, resolved, fillEvent);
  }

  /**
   * #818: is the entry fill COMPLETE enough to book? At {@code DEFAULT_VERSION}: any fill (the
   * legacy first-slice behavior, byte-identical). At v&gt;=1: only a cumulative filled_qty covering
   * the ordered contracts. {@code onFill} is last-write-wins and Alpaca's filled_qty is cumulative,
   * so intermediate slices simply re-arm the await until the terminal one lands.
   */
  private boolean entryFillComplete(long contracts, int terminalAwaitVersion) {
    if (fillEvent == null) {
      return false;
    }
    if (terminalAwaitVersion < 1) {
      return true;
    }
    Long filledQty = fillEvent.getFilledQty();
    return filledQty != null && filledQty >= contracts;
  }

  private void startPositionWorkflow(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      FillSignalPayload fill) {
    String tenant = payload.getTenantId();
    String strategyId = payload.getStrategyId();
    String posWfId =
        WorkflowIds.position(tenant, strategyId, resolved.optionSymbol(), payload.getSignalId());

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
    posInput.setEntrySignalId(payload.getSignalId());
    posInput.setContractSymbol(resolved.optionSymbol());
    // #818: input.qty is the child's EXPECTED qty (#203) and the #801 entry-growth cap. Feed the
    // ORDERED contracts (entryStatus.contracts, set at submit on every spawn path) so a late
    // straggler slice can still grow the lot to the ceiling; the legacy branch keeps the fill's
    // own qty byte-identically. Falls back to the fill qty if contracts is unset (defensive —
    // every path sets it before any spawn).
    Long orderedContracts = entryStatus.getContracts();
    boolean expectedQtyFromOrder =
        Workflow.getVersion(VERSION_ENTRY_AWAIT_TERMINAL, Workflow.DEFAULT_VERSION, 1) >= 1
            && orderedContracts != null
            && orderedContracts > 0;
    posInput.setQty(expectedQtyFromOrder ? orderedContracts : fill.getFilledQty());
    posInput.setEntryPremium(
        fill.getAvgFillPrice() != null ? fill.getAvgFillPrice() : payload.getPrice());
    posInput.setSourceSignalWorkflowId(Workflow.getInfo().getWorkflowId());
    // Phase 2c.2: carry broker_target so the child routes its exit/flatten Activities to the
    // same broker-<value> queue as the parent's entry.
    posInput.setBrokerTarget(
        PositionWorkflowInput.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    // Issue #202: carry eod_force_flatten so copytrade positions (and any future strategy that
    // mirrors an external author) skip the 15:55 ET EOD timer in PositionWorkflowImpl. Null is
    // passed through unchanged; PositionWorkflowImpl treats null as the default-true policy.
    posInput.setEodForceFlatten(config.getEodForceFlatten());
    // Issue #15: carry force_close_0dte_et so the child's 0DTE expiry-close timer fires at the
    // per-strategy time. Null/absent passes through unchanged; PositionWorkflowImpl defaults to
    // 15:30.
    posInput.setForceClose0dteEt(config.getForceClose0dteEt());
    // Issue #205: carry min_partial_qty_behavior so PositionWorkflow's runner-quantum gate can
    // decide between SKIP (default) and FULL_CLOSE when a partial signal would round to zero
    // contracts. Null/absent passes through unchanged; PositionWorkflowImpl treats null as SKIP.
    if (config.getMinPartialQtyBehavior() != null) {
      posInput.setMinPartialQtyBehavior(
          PositionWorkflowInput.MinPartialQtyBehavior.fromValue(
              config.getMinPartialQtyBehavior().value()));
    }
    // Issue #212: carry per-strategy first-fill / exit-fill TTLs so PositionWorkflowImpl's
    // bounded awaits (#203 entry-fill, #204 exit-fill) use the configured value selected by
    // broker_target instead of the hardcoded 90s constants. Paper broker_targets receive
    // pending_ttl_paper_secs; live broker_targets receive pending_ttl_live_secs; the StrategyConfig
    // fallback to 90L lives in {@link #selectPendingTtlSecs}. Both fields are passed regardless of
    // null-ness so the child sees a deterministic value once it crosses the VERSION_TTL_FROM_INPUT
    // v>=1 gate (the child still handles a null input field defensively for replays of
    // PositionWorkflowInput payloads minted by a pre-#212 parent).
    long ttlSecsForChild = selectPendingTtlSecs(config);
    posInput.setFirstFillTtlSecs(ttlSecsForChild);
    posInput.setExitFillTtlSecs(ttlSecsForChild);
    // Plan-2A R-AA-5: carry the bounded-flatten exit floors so PositionWorkflow's scheduled flatten
    // (R-AA-3, next chunk) can anchor a bounded LIMIT instead of a market dump. All three pass
    // through verbatim (null preserved); PositionWorkflowImpl treats null as the marketable
    // fail-safe fallback. Not consumed in workflow logic yet — plumb + default only.
    posInput.setExitFloorAbs(config.getExitFloorAbs());
    posInput.setExitFloorPct(config.getExitFloorPct());
    posInput.setExpiryDayFloor(config.getExpiryDayFloor());
    // Plan-2B R-AB-1/R-AB-2: carry the guaranteed-flatten lead + bounded stepped-reprice tunables
    // so
    // PositionWorkflow arms the multi-day expiry-lead timer (R-AB-1) and walks the bounded exit
    // reprice (R-AB-2). All pass through verbatim (null preserved → in-code defaults in the child:
    // flatten_lead_minutes 30, exit_reprice_steps 3, exit_reprice_tick 0.05). Consumption is
    // version-gated in the child so in-flight pre-2B workflows replay deterministically.
    posInput.setFlattenLeadMinutes(config.getFlattenLeadMinutes());
    posInput.setExitRepriceSteps(config.getExitRepriceSteps());
    posInput.setExitRepriceTick(config.getExitRepriceTick());

    Async.function(child::run, posInput);
    // Wait until the child is durably scheduled before returning.
    Workflow.getWorkflowExecution(child).get();

    // Issue #203: forward the BTO fill into the child so its v=1 first-fill await gate wakes and
    // PositionEntered fires with the real filled qty. Gated by a dedicated version so in-flight
    // workflows that already executed startPositionWorkflow without this command preserve their
    // recorded history on replay.
    int forwardFill = Workflow.getVersion(VERSION_FORWARD_BTO_FILL, Workflow.DEFAULT_VERSION, 1);
    if (forwardFill >= 1) {
      child.onFill(fill);
    }

    positionLookup.cachePositionMapping(tenant, strategyId, resolved.optionSymbol(), posWfId);
  }

  private String handleStc(CopytradeSignalPayload payload, StrategyConfig config) {
    if (riskBreachReceived) {
      auditRiskBreachAbort(payload, "stc_pre_resolve", null);
      return payload.getSignalId();
    }
    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));
    String tenant = payload.getTenantId();
    String strategyId = payload.getStrategyId();
    String occ = resolved.optionSymbol();

    long bufferSecs = entryPendingTtlSecs(config);
    int maxAttempts = (int) Math.max(1L, bufferSecs / 10L);
    String positionId = positionLookup.findPositionWorkflowId(tenant, strategyId, occ);
    int attempts = 0;
    while (positionId == null && attempts < maxAttempts && !riskBreachReceived) {
      // Use await-with-timeout so a co-arriving risk_breach signal wakes the loop early.
      Workflow.await(Duration.ofSeconds(10), () -> riskBreachReceived);
      if (riskBreachReceived) {
        break;
      }
      positionId = positionLookup.findPositionWorkflowId(tenant, strategyId, occ);
      attempts++;
    }
    if (riskBreachReceived) {
      auditRiskBreachAbort(payload, "stc_pre_dispatch", null);
      return payload.getSignalId();
    }
    if (positionId == null) {
      logAudit(
          payload,
          KIND_STC_NO_OPEN_POSITION,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              occ,
              "attempts",
              attempts,
              "author",
              payload.getAuthor()));
      return payload.getSignalId();
    }

    int stcGuardVersion =
        Workflow.getVersion(VERSION_STC_RUNNING_GUARD, Workflow.DEFAULT_VERSION, 1);

    // Fraction resolution is hoisted ABOVE the primary-dispatch block because the FAN-OUT needs it
    // too — and must still have it when the primary bails out. Every line of it is pure computation
    // (keyword matcher + config/payload reads, no Workflow.* call and no activity), so moving it
    // emits no command and cannot reorder the recorded history.
    // PLAN-2026-07-01-unrecognized-stc-tail-alert: compute the match REPORT (fraction + winning
    // key) rather than just the fraction. The fraction is unchanged — matchReporting resolves the
    // identical value as match() — but the winning key (empty when the default was applied) is
    // carried into the ExitRequested audit subject below so an out-of-workflow alerter can page
    // when a non-empty tail matched nothing. Observability only: sizing/dispatch are untouched.
    KeywordPartialMatcher.MatchResult matchResult =
        KeywordPartialMatcher.matchReporting(
            payload.getTail(),
            toDoubleMap(config.getPartialFractions()),
            defaultStcFraction(config));
    double keywordFraction = matchResult.fraction();

    // PLAN-2026-07-25-stc-intent-classifier: the close-intent classifier arbitrates full-vs-partial
    // ONLY behind the per-tenant stc_intent_enforce flag; the keyword matcher above stays the
    // permanent fallback AND the partial sizer. Shadow-mode when enforce is off: close_intent is
    // still recorded in the audit subject below, but effectiveFraction == keywordFraction.
    //
    // Replay safety — NO Workflow.getVersion gate (same precedent as the two audit enrichments at
    // the ExitRequested subject below, PLAN-2026-07-01 / PLAN-2026-07-20): effectiveFraction feeds
    // the SAME single stub.signal("partialExit", req) command with a different payload VALUE, and
    // signal/activity-input payloads are NOT replay-checked on Temporal 1.27. Old histories carry
    // no close_intent → intent == null → effectiveFraction == keywordFraction → byte-identical
    // replay. The audit change is subject-only (activity input). No new command, no version gate.
    // PROMOTE-ONLY classifier (quant + risk review 2026-07-25): the classifier may only PROMOTE a
    // keyword-missed FULL close; it NEVER sizes an exit smaller than the keyword value. A keyword
    // fraction of exactly 1.0 ALWAYS means an explicit full-close keyword matched (the default is
    // never 1.0), so a PARTIAL classifier verdict must NOT demote it — that would silently override
    // an explicit author "out"/"all out"/"dumped" on a possibly-wrong classifier verdict and
    // reintroduce the money-losing under-close. #600's smallest-fraction-wins already resolves the
    // "partial. taking profit" collision at the keyword layer, so PARTIAL simply defers to keyword.
    // Net property: effectiveFraction >= keywordFraction always; the PARTIAL verdict is still
    // recorded in the audit subject (close_intent) for shadow review even though it defers.
    boolean enforce = Boolean.TRUE.equals(config.getStcIntentEnforce());
    CopytradeSignalPayload.CloseIntent intent = payload.getCloseIntent();
    // Promote a keyword-missed full exit to a full close (fixes the under-close incident) ONLY when
    // enforce is on and the classifier says FULL; otherwise PARTIAL (defer to keyword — no
    // demotion), no verdict, or shadow-mode all size it from the keyword fraction. intentApplied
    // still drives intent_source in the audit subject below.
    boolean intentApplied = enforce && intent == CopytradeSignalPayload.CloseIntent.FULL;
    double effectiveFraction = intentApplied ? 1.0 : keywordFraction;

    // The primary dispatch is wrapped in a labeled block so its two bail-outs (target not RUNNING,
    // signal dispatch failed) skip the REST OF THE PRIMARY PATH without skipping the multi-leg
    // fan-out below. They used to `return`, which meant a dead or dying primary leg silently
    // cancelled the exit for every OTHER leg of the same OCC — the exact guarantee the fan-out
    // exists to provide, defeated by the likeliest case: the Redis pointer resolves the most recent
    // leg, so a leg closed by a force-exit or its own stop leaves the pointer stale, the guard
    // fails, and the author's STC never reached the legs that were still running.
    //
    // A labeled break (rather than extracting a method) keeps the command stream provably
    // unchanged: the enclosed code is byte-identical and merely re-scoped, so a v=DEFAULT_VERSION
    // replay emits exactly the commands it emitted before.
    primaryDispatch:
    {

      // Change point A (preventive): a stale Redis mapping can return a non-null but DEAD
      // (terminal)
      // PositionWorkflow id. Verify it's RUNNING before emitting ExitRequested or signalling it.
      // The
      // && short-circuit guarantees v=0 schedules NO isPositionWorkflowRunning activity (the v=0
      // command stream stays byte-identical).
      if (stcGuardVersion >= 1 && !positionLookup.isPositionWorkflowRunning(positionId)) {
        logAudit(
            payload,
            KIND_STC_NO_OPEN_POSITION,
            subject(
                "signal_id", payload.getSignalId(),
                "option_symbol", occ,
                "position_workflow_id", positionId,
                "reason", REASON_POSITION_WF_NOT_RUNNING,
                "author", payload.getAuthor()));
        break primaryDispatch;
      }

      PartialExitRequest req = new PartialExitRequest();
      req.setSchemaVersion(1L);
      req.setTenantId(tenant);
      req.setStrategyId(strategyId);
      req.setSignalId(payload.getSignalId());
      req.setPositionWorkflowId(positionId);
      req.setFraction(BigDecimal.valueOf(effectiveFraction));
      req.setRefPremium(payload.getPrice());
      req.setReason("stc_signal");
      req.setAuthor(payload.getAuthor());
      req.setRawLine(payload.getRawLine());
      req.setOccurredAt(workflowNow());

      // Audit BEFORE dispatch so the intent is durably recorded even if the target workflow has
      // already closed (race) — reconciliation in Phase 5 reads these to detect orphan STCs.
      logAudit(
          payload,
          KIND_EXIT_REQUESTED,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              occ,
              "position_workflow_id",
              positionId,
              // "fraction" = the exit fraction we DISPATCHED (legacy key; correct ExitRequested
              // semantics). keyword_fraction = the keyword/default-resolved value (what a
              // no-keyword-match tail fell back to). The classifier shadow fields below
              // (close_intent/intent_source/intent_enforced) explain any divergence between them.
              "fraction",
              effectiveFraction,
              // PLAN-2026-07-25-stc-intent-classifier: subject-only shadow enrichment (same
              // replay-safety rationale — no new command, no version gate). close_intent/
              // close_confidence are the classifier's verdict on EVERY STC; keyword_fraction is the
              // matcher's value; intent_source is "classifier" only when the enforce override
              // actually
              // arbitrated the fraction, else "keyword"; intent_enforced is the per-tenant flag.
              // This
              // gives the shadow comparison (classifier vs keyword) for free on every STC.
              "close_intent",
              intent != null ? intent.value() : null,
              "close_confidence",
              payload.getCloseConfidence(),
              "keyword_fraction",
              keywordFraction,
              "intent_source",
              intentApplied ? "classifier" : "keyword",
              "intent_enforced",
              enforce,
              // PLAN-2026-07-01-unrecognized-stc-tail-alert: subject-only enrichment (no new
              // command,
              // no version gate — activity-input payloads are ignored on Temporal 1.27 replay). The
              // out-of-workflow UnrecognizedStcTailAlerter reads these to page when a non-empty
              // tail
              // matched no keyword. matched_keyword is null when the default fraction was applied.
              "matched_keyword",
              matchResult.matchedKey().orElse(null),
              "tail",
              payload.getTail(),
              "author",
              payload.getAuthor(),
              "raw_line",
              payload.getRawLine(),
              // PLAN-2026-07-20-stc-fraction-keyword-collision: subject-only enrichment (same
              // replay-safety rationale — no new command, no version gate). fraction_collision
              // flags
              // a tail that matched ≥2 keywords with DIFFERENT fractions (auto-resolved to the
              // smallest); matched_keywords lists every phrase that fired so the alerter can page.
              "fraction_collision",
              matchResult.fractionCollision(),
              "matched_keywords",
              String.join(",", matchResult.matchedKeys())));

      ExternalWorkflowStub stub = Workflow.newUntypedExternalWorkflowStub(positionId);
      // Change point B (defense-in-depth): even past the running-guard the target can die between
      // the
      // guard and the signal (TOCTOU). v=0 keeps the bare single command (byte-identical replay);
      // v>=1
      // catches the dispatch failure and emits OrphanSTC instead of crashing.
      //
      // The catch is narrow by construction: the try wraps ONLY the single stub.signal command, so
      // the
      // only exception that can originate here is a signal-external-workflow dispatch failure. The
      // Temporal Java SDK 1.27 surfaces a NOT_FOUND/terminal target as
      // io.temporal.failure.ApplicationFailure (type SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_*),
      // converted from the server Failure proto by DataConverter.failureToException — NOT as
      // SignalExternalWorkflowException (the SDK only constructs that type on the cancel-external
      // path
      // in this version). We catch both so the production crash is actually prevented; we
      // deliberately
      // do NOT catch bare RuntimeException so genuine bugs still fail the workflow loudly.
      if (stcGuardVersion == Workflow.DEFAULT_VERSION) {
        stub.signal("partialExit", req);
      } else {
        try {
          stub.signal("partialExit", req);
        } catch (SignalExternalWorkflowException | ApplicationFailure e) {
          logAudit(
              payload,
              KIND_ORPHAN_STC,
              subject(
                  "signal_id", payload.getSignalId(),
                  "option_symbol", occ,
                  "position_workflow_id", positionId,
                  "reason", REASON_SIGNAL_DISPATCH_FAILED,
                  "error", String.valueOf(e.getMessage())));
          break primaryDispatch;
        }
      }

      // Phase 4: arm CHANDELIER_TRAIL when the strategy opts in.
      if (Boolean.TRUE.equals(config.getTrailOnPartial())) {
        ArmChandelierPayload arm = new ArmChandelierPayload();
        arm.setSchemaVersion(1L);
        arm.setTenantId(tenant);
        arm.setStrategyId(strategyId);
        arm.setPositionWorkflowId(positionId);
        arm.setSourceSignalId(payload.getSignalId());
        arm.setPeakPremium(payload.getPrice());
        arm.setGivebackPct(config.getTrailGivebackPct());
        stub.signal("armChandelier", arm);
        logAudit(
            payload,
            KIND_CHANDELIER_ARM_REQUESTED,
            subject(
                "signal_id", payload.getSignalId(),
                "position_workflow_id", positionId,
                "peak_premium", payload.getPrice(),
                "giveback_pct", config.getTrailGivebackPct()));
      }
    } // end primaryDispatch — the fan-out below runs whether or not the primary leg was reached

    // ---- multi-leg fan-out -------------------------------------------------------------------
    // Everything above dispatched to ONE leg — whichever findPositionWorkflowId happened to
    // resolve. When the same OCC has more open legs (a second BTO, or an operator manual entry on
    // a contract the author already holds), those legs never saw the author's exit and kept
    // running their own stop/target/trail. Close them too.
    //
    // Placed AFTER the single-leg path rather than replacing it, so v=DEFAULT_VERSION replays the
    // pre-change command stream byte-for-byte and the fan-out is purely additive.
    //
    // The fraction applies PER LEG, not to the combined size: each leg reuses its own qty math,
    // min_partial_qty_behavior and dedupe. "Half out" across legs of 3 and 2 therefore sells 2 and
    // 1. Over-selling is impossible by construction — each leg only ever sells what it itself
    // tracks, and the broker holds the sum of all legs.
    int fanoutVersion =
        Workflow.getVersion(VERSION_STC_MULTI_LEG_FANOUT, Workflow.DEFAULT_VERSION, 1);
    if (fanoutVersion >= 1) {
      for (String legId : positionLookup.findAllPositionWorkflowIds(tenant, strategyId, occ)) {
        if (legId.equals(positionId)) {
          continue; // already dispatched above
        }
        dispatchStcToLeg(payload, config, occ, legId, effectiveFraction);
      }
    }

    return payload.getSignalId();
  }

  /**
   * Dispatch the SAME exit to one additional leg of a multi-leg OCC. Mirrors the single-leg path's
   * shape: verify the target is RUNNING, audit the intent BEFORE dispatch (so a lost race is still
   * recorded), signal {@code partialExit}, then arm the chandelier when the strategy opts in.
   *
   * <p>A dead or dying leg is skipped rather than fatal: the legs are independent, and one that
   * closed on its own stop between the enumeration and the signal is not a failure of this STC.
   * Each outcome is audited so the fan-out is reconstructable — {@code fanout_leg=true} separates
   * these rows from the primary dispatch.
   */
  private void dispatchStcToLeg(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      String occ,
      String legId,
      double effectiveFraction) {
    if (!positionLookup.isPositionWorkflowRunning(legId)) {
      logAudit(
          payload,
          KIND_STC_NO_OPEN_POSITION,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              occ,
              "position_workflow_id",
              legId,
              "reason",
              REASON_POSITION_WF_NOT_RUNNING,
              "fanout_leg",
              true,
              "author",
              payload.getAuthor()));
      return;
    }

    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(payload.getTenantId());
    req.setStrategyId(payload.getStrategyId());
    req.setSignalId(payload.getSignalId());
    req.setPositionWorkflowId(legId);
    req.setFraction(BigDecimal.valueOf(effectiveFraction));
    req.setRefPremium(payload.getPrice());
    req.setReason("stc_signal");
    req.setAuthor(payload.getAuthor());
    req.setRawLine(payload.getRawLine());
    req.setOccurredAt(workflowNow());

    logAudit(
        payload,
        KIND_EXIT_REQUESTED,
        subject(
            "signal_id",
            payload.getSignalId(),
            "option_symbol",
            occ,
            "position_workflow_id",
            legId,
            "fraction",
            effectiveFraction,
            "fanout_leg",
            true,
            "author",
            payload.getAuthor(),
            "raw_line",
            payload.getRawLine()));

    ExternalWorkflowStub legStub = Workflow.newUntypedExternalWorkflowStub(legId);
    try {
      legStub.signal("partialExit", req);
    } catch (SignalExternalWorkflowException | ApplicationFailure e) {
      logAudit(
          payload,
          KIND_ORPHAN_STC,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              occ,
              "position_workflow_id",
              legId,
              "reason",
              REASON_SIGNAL_DISPATCH_FAILED,
              "fanout_leg",
              true,
              "error",
              String.valueOf(e.getMessage())));
      return;
    }

    if (Boolean.TRUE.equals(config.getTrailOnPartial())) {
      ArmChandelierPayload arm = new ArmChandelierPayload();
      arm.setSchemaVersion(1L);
      arm.setTenantId(payload.getTenantId());
      arm.setStrategyId(payload.getStrategyId());
      arm.setPositionWorkflowId(legId);
      arm.setSourceSignalId(payload.getSignalId());
      arm.setPeakPremium(payload.getPrice());
      arm.setGivebackPct(config.getTrailGivebackPct());
      legStub.signal("armChandelier", arm);
      logAudit(
          payload,
          KIND_CHANDELIER_ARM_REQUESTED,
          subject(
              "signal_id", payload.getSignalId(),
              "position_workflow_id", legId,
              "peak_premium", payload.getPrice(),
              "fanout_leg", true,
              "giveback_pct", config.getTrailGivebackPct()));
    }
  }

  private String handleAvg(CopytradeSignalPayload payload, StrategyConfig config) {
    if (Boolean.TRUE.equals(config.getSkipAvg())) {
      logAudit(
          payload,
          KIND_AVG_SKIPPED,
          subject("signal_id", payload.getSignalId(), "note", "skip_avg_true"));
      return payload.getSignalId();
    }
    // Phase 3 does not act on AVG when not skipped — Phase 5+ resolves Open Question #10 further.
    logAudit(
        payload,
        KIND_AVG_SKIPPED,
        subject("signal_id", payload.getSignalId(), "note", "avg_not_implemented"));
    return payload.getSignalId();
  }

  private void handleTtlExpired(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      OrderIntentResult placed,
      String intentKey,
      long ttlSecs) {
    logAuditWhileOrderLive(
        payload,
        KIND_ORDER_CANCEL_REQUESTED,
        subject(
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "reason", REASON_TTL_EXPIRED));

    OrderIntentResult cancelResult = exec.cancelOrder(intentKey);

    // Issue #165 phase 2: when the broker filled inside the TTL/cancel race, the exec sidecar
    // now reconciles the journal to FILLED and returns the broker-confirmed fill detail. Adopt
    // the orphan position by spawning the PositionWorkflow instead of emitting EntryExpired.
    // Versioned so replays of pre-fix histories deterministically take the legacy paths below.
    int adoptionVersion =
        Workflow.getVersion(VERSION_TTL_FILLED_ADOPTION, Workflow.DEFAULT_VERSION, 1);
    if (adoptionVersion >= 1 && cancelResult.getState() == OrderIntentResult.State.FILLED) {
      handleCancelOnFilled(payload, config, resolved, cancelResult, RECOVERY_CANCEL_ON_FILLED);
      return;
    }

    // Watchlist Phase-1 parity: defense-in-depth broker-truth reconcile. The single cancelOrder
    // return above can miss a fill that terminalized the journal FILLED a beat later (the WS
    // listener raced the cancel — exactly the SPY 2026-07-06 incident on the sibling watchlist
    // path). When inline cancel-on-filled did NOT adopt, re-read the reconciled journal row via
    // exec.getOrderStatus; if it reports terminal FILLED with a broker-confirmed qty, route through
    // the SAME handleCancelOnFilled path instead of orphaning the lot to the 5-min recon sweep.
    // Best-effort: a RuntimeException (broker down) falls through fail-closed to the legacy
    // EntryExpired path, where reconciliation settles the orphan. Read UNCONDITIONALLY at this
    // point in the timeout branch so the marker is deterministic on replay; pre-fix histories
    // (DEFAULT_VERSION) take the byte-identical legacy path below with no getOrderStatus command.
    int reconcileVersion =
        Workflow.getVersion(VERSION_ENTRY_GETORDERSTATUS_RECONCILE, Workflow.DEFAULT_VERSION, 1);
    if (reconcileVersion >= 1) {
      OrderIntentResult status = null;
      try {
        status = exec.getOrderStatus(intentKey);
      } catch (RuntimeException ignored) {
        // Best-effort authoritative recheck; fall through to the legacy EntryExpired path.
      }
      if (status != null
          && status.getState() == OrderIntentResult.State.FILLED
          && status.getFilledQty() != null
          && status.getFilledQty() > 0L) {
        handleCancelOnFilled(payload, config, resolved, status, RECOVERY_GETORDERSTATUS_RECONCILE);
        return;
      }
    }

    // #818: neither reconcile probe found a terminal fill, but partial slices already landed
    // locally in fillEvent (the cancel destroyed the remainder; exec's cancel path does not yet
    // carry the filled portion — #819). Book the partial instead of expiring to ZERO booked
    // contracts, which would leave a genuinely-filled lot fully unmanaged (arch-review finding:
    // a regression vs the old first-slice booking). Gated: at DEFAULT_VERSION fillEvent here is
    // always null (any slice made filled=true upstream), so the legacy path is byte-identical.
    if (Workflow.getVersion(VERSION_ENTRY_AWAIT_TERMINAL, Workflow.DEFAULT_VERSION, 1) >= 1
        && fillEvent != null
        && fillEvent.getFilledQty() != null
        && fillEvent.getFilledQty() > 0L) {
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCELLED,
          subject(
              "intent_key",
              cancelResult.getIntentKey(),
              "broker_order_id",
              cancelResult.getBrokerOrderId(),
              "reason",
              REASON_TTL_EXPIRED,
              "note",
              "remainder_cancelled_partial_booked"));
      adoptLocalPartialFill(payload, config, resolved, RECOVERY_PARTIAL_AT_TTL);
      return;
    }

    if (cancelResult.getState() == OrderIntentResult.State.CANCELLED) {
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCELLED,
          subject(
              "intent_key", placed.getIntentKey(),
              "broker_order_id", placed.getBrokerOrderId(),
              "reason", REASON_TTL_EXPIRED));
    } else {
      // Issue #165 phase 2: drop the `orphan_position_until_phase_3` note value. The orphan case
      // is now either recovered by the FILLED branch above or detected by Phase 3 reconciliation.
      // Keep the `note` key so audit consumers don't break.
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_FAILED,
          subject(
              "signal_id", payload.getSignalId(),
              "option_symbol", resolved.optionSymbol(),
              "intent_key", placed.getIntentKey(),
              "broker_order_id", placed.getBrokerOrderId(),
              "broker_reason", cancelResult.getLastError(),
              "severity", "ERROR",
              "note", "cancel_failed"));
    }

    logAuditWhileOrderLive(
        payload,
        KIND_ENTRY_EXPIRED,
        subject(
            "signal_id", payload.getSignalId(),
            "intent_key", placed.getIntentKey(),
            "broker_order_id", placed.getBrokerOrderId(),
            "ttl_secs", ttlSecs,
            "outcome", "EXPIRED"));
    // Reached only when neither cancel-on-filled recovery adopted a lot (both return early after
    // stamping FILLED), so this cannot overwrite a genuine fill.
    entryStatus.setState(CopytradeEntryStatus.State.EXPIRED);
    entryStatus.setReasonDetail("ttl_secs=" + ttlSecs);
  }

  /**
   * Issue #165 phase 2: recover from the cancel-on-filled race by synthesising a {@link
   * FillSignalPayload} from the broker-confirmed cancel result, emitting {@code EntryFilled} with a
   * {@code recovery=cancel_on_filled} marker, and spawning the missing PositionWorkflow. Mirrors
   * the happy-path fill branch's audit + child-workflow handoff so subsequent STCs route to {@code
   * partialExit} rather than producing {@code OrphanSTC}.
   */
  private void handleCancelOnFilled(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      OrderIntentResult cancelResult,
      String recovery) {
    long filledQty = cancelResult.getFilledQty() != null ? cancelResult.getFilledQty() : 0L;
    BigDecimal avgFillPrice =
        cancelResult.getAvgFillPrice() != null
            ? cancelResult.getAvgFillPrice()
            : payload.getPrice();
    FillSignalPayload synth =
        new FillSignalPayload()
            .withBrokerOrderId(cancelResult.getBrokerOrderId())
            .withFilledQty(filledQty)
            .withAvgFillPrice(avgFillPrice)
            .withFilledAt(workflowNow());

    Map<String, Object> recoverySubject =
        subject(
            "signal_id", payload.getSignalId(),
            "intent_key", cancelResult.getIntentKey(),
            "broker_order_id", synth.getBrokerOrderId(),
            "filled_qty", synth.getFilledQty(),
            "avg_fill_price", synth.getAvgFillPrice(),
            "outcome", "FILLED",
            "recovery", recovery);
    // Issue #276: same option_symbol correlation key as the happy-path fill, same replay gate so
    // the cancel-on-filled recovery audit also groups per symbol in DailyPnl for new executions.
    if (Workflow.getVersion(VERSION_ENTRY_FILLED_OPTION_SYMBOL, Workflow.DEFAULT_VERSION, 1) >= 1) {
      recoverySubject.put("option_symbol", resolved.optionSymbol());
    }
    logAudit(payload, KIND_ENTRY_FILLED, recoverySubject);
    filledStatus(resolved, synth);

    startPositionWorkflow(payload, config, resolved, synth);
  }

  /**
   * Dispatches the cross-service {@code PreTradeCheckActivity}. Returns {@code null} when the gate
   * is disabled — the downstream {@code checkPreTradeCheck} branch short-circuits in that case.
   *
   * <p>Fail-closed semantics: any exception (after Temporal's own retries) is converted to a
   * sentinel {@link PreTradeCheckResult} with {@code allowed=false} and {@code
   * rejectReason="dispatch_failed:<ExceptionSimpleName>"}. {@code RiskActivitiesImpl.checkEntry}
   * then surfaces {@code PRE_TRADE_CHECK_FAILED} via the existing {@code allowed=false} branch.
   *
   * <p>Determinism: the request is built from {@code payload + config + Sizing.CONTRACT_MULTIPLIER}
   * only — no clock reads, no random IDs (the correlation id is the deterministic {@code
   * signalId}). Safe to call inside the workflow body.
   */
  private PreTradeCheckResult dispatchPreTradeCheck(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal estimatedLimitPrice) {
    if (!Boolean.TRUE.equals(config.getPreTradeCheckEnabled())) {
      return null;
    }
    if (config.getBrokerTarget() == null) {
      return PreTradeCheckSentinels.dispatchFailed("NullBrokerTarget");
    }
    // Bound retries so a persistently-failing pre-trade endpoint surfaces as the dispatch-failed
    // sentinel within the workflow's TTL window rather than retrying forever. 3 attempts mirrors
    // the kill-switch read tolerance — enough to absorb a transient broker hiccup, short enough
    // that a real outage fails closed quickly.
    //
    // Schedule-to-close envelope: start-to-close (15s) × maxAttempts (3) = 45s of pure run time,
    // plus exponential-backoff jitter between attempts can push the wall-clock total past 45s
    // before the sentinel is produced. A literal 60s schedule-to-close caps the worst case so
    // the fail-closed latency is explicit and predictable vs the workflow TTL (issue #115).
    PreTradeCheckActivity preTradeStub =
        Workflow.newActivityStub(
            PreTradeCheckActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(config.getBrokerTarget().value()))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    PreTradeCheckRequest request = buildPreTradeCheckRequest(payload, config, estimatedLimitPrice);
    try {
      return preTradeStub.preTradeCheck(request);
    } catch (CanceledFailure cf) {
      throw cf;
    } catch (Exception e) {
      return PreTradeCheckSentinels.dispatchFailed(e.getClass().getSimpleName());
    }
  }

  /**
   * Builds the {@link PreTradeCheckRequest} the workflow dispatches to exec-svc. {@code
   * estimated_notional} is the 1-contract floor computed against the slip-adjusted limit
   * (max-acceptable cost) so the risk-svc cap sees the realistic worst-case rather than the
   * optimistic mirror. The workflow sizes down later if needed. {@code correlation_id} is the
   * deterministic {@code signal_id} so audit traces stitch end-to-end.
   */
  private static PreTradeCheckRequest buildPreTradeCheckRequest(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal estimatedLimitPrice) {
    PreTradeCheckRequest r = new PreTradeCheckRequest();
    r.setSchemaVersion(1L);
    r.setTenantId(payload.getTenantId());
    r.setStrategyId(payload.getStrategyId());
    r.setBrokerTarget(
        PreTradeCheckRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    r.setOptionSymbol(payload.getTicker());
    r.setSide(PreTradeCheckRequest.Side.BUY);
    Long minContracts = config.getMinContracts();
    r.setQty(minContracts == null ? 1L : Math.max(1L, minContracts));
    BigDecimal limit = estimatedLimitPrice == null ? BigDecimal.ZERO : estimatedLimitPrice;
    r.setEstimatedNotional(limit.multiply(Sizing.CONTRACT_MULTIPLIER));
    r.setCorrelationId(payload.getSignalId());
    return r;
  }

  /** The shared capital-sizing fail-closed rejection (account_cash and account_equity, #790). */
  private void rejectCapitalUnavailable(CopytradeSignalPayload payload) {
    logAudit(
        payload,
        KIND_SIGNAL_REJECTED,
        subject(
            "signal_id", payload.getSignalId(),
            "reason_code", "CAPITAL_UNAVAILABLE",
            "reason_detail", "capital_unavailable",
            "outcome", "REJECTED"));
    rejectStatus("CAPITAL_UNAVAILABLE", "capital_unavailable");
  }

  /**
   * Dispatches the cross-service {@code AccountSnapshotActivity} over the {@code
   * broker-<broker_target>} task queue and returns the full {@link AccountSnapshotResult} — one
   * dispatch feeding THREE consumers: the notional-cap gate's cash term ({@code cash +
   * sum_open_notional}, issue #323), {@code account_cash} sizing, and {@code account_equity} sizing
   * (#790, the {@code equity} field). Returns {@code null} when nothing enables the dispatch (no
   * notional cap per {@link StrategyConfigs#notionalCapConfigured}, and neither tracking source
   * selected at its v&gt;=1 marker) so the cross-service round-trip only fires when a consumer
   * exists — callers MUST preserve that null (never-dispatched) distinct from the ZERO sentinel.
   *
   * <p>Fail-closed semantics: any exception (after Temporal's own retries), a null/blank {@code
   * broker_target}, or a null result yields the {@link #zeroSnapshot()} sentinel (cash and equity
   * both ZERO). The downstream {@code checkNotionalCap} gate and both sizing branches reject on a
   * zero/missing figure, so a broker outage rejects entries rather than passing an unbounded cap or
   * a fabricated base — mirroring {@code dispatchPreTradeCheck}. Null FIELDS inside a non-null
   * result are normalized to ZERO at the call site.
   *
   * <p>Determinism: the request is built from {@code config.broker_target + signal_id} only — no
   * clock reads, no random IDs. Safe to call inside the workflow body. The account figures are
   * account-level, so the request carries no tenant/strategy. Which FIELD a consumer reads off the
   * result is a payload concern — no history-shape change, so field choice stays within the
   * existing {@code VERSION_ACCOUNT_EQUITY_DISPATCH} gate; only the dispatch-ENABLEMENT widening
   * carries its own markers (see {@code VERSION_ACCOUNT_CASH_SIZING} / {@code
   * VERSION_ACCOUNT_EQUITY_SIZING}).
   */
  private AccountSnapshotResult dispatchAccountSnapshot(
      CopytradeSignalPayload payload, StrategyConfig config) {
    // Enablement mirrors RiskActivitiesImpl#resolveNotionalCapPct: the cap (and so the cash
    // dispatch) is on when notional_cap_pct_of_capital_base is set. Pre-#336 this tested the
    // now-removed equity alias only, so a config setting ONLY the canonical field (the migration
    // end-state, and since #338 the only state) skipped the dispatch and checkNotionalCap rejected
    // every BTO with cash_unavailable. Sharing StrategyConfigs.notionalCapConfigured keeps
    // guard and resolver in lockstep.
    //
    // Replay-safe by construction (no new Workflow.getVersion marker needed): `config` reaches this
    // method as the deterministically recorded result of the strategy.get() Activity (read once in
    // process(), threaded down — never re-resolved here on replay). A history recorded before #336
    // physically cannot carry notional_cap_pct_of_capital_base (the field did not exist when that
    // Activity result was serialized), so for any pre-existing execution capBase is null on replay
    // and this guard reduces to the old equity-only decision — the command stream is unchanged. The
    // new branch is reachable only by executions whose history was recorded post-#336.
    //
    // dynamic-account-cash-sizing: account_cash sizing ALSO needs this dispatch even when no
    // notional cap is configured. WIDENING the enablement (cap OR cash-sizing) adds an
    // AccountSnapshot
    // command for an account_cash+no-cap strategy that previously emitted none, so the widening is
    // gated behind VERSION_ACCOUNT_CASH_SIZING (read unconditionally): a legacy no-cap history
    // replays at DEFAULT_VERSION and still returns null here (no command), while a notional-cap
    // history is unaffected (notionalCapConfigured already true at either version). Only a freshly-
    // started account_cash+no-cap execution takes the new dispatch.
    int cashSizingVersion =
        Workflow.getVersion(VERSION_ACCOUNT_CASH_SIZING, Workflow.DEFAULT_VERSION, 1);
    boolean cashSizingDispatch =
        cashSizingVersion >= 1 && StrategyConfigs.accountCashSizing(config);
    // #790: account_equity widens the enablement exactly like account_cash did — same marker
    // discipline (read unconditionally, widen only at v>=1), one version marker later.
    int equitySizingVersion =
        Workflow.getVersion(VERSION_ACCOUNT_EQUITY_SIZING, Workflow.DEFAULT_VERSION, 1);
    boolean equitySizingDispatch =
        equitySizingVersion >= 1 && StrategyConfigs.accountEquitySizing(config);
    if (!StrategyConfigs.notionalCapConfigured(config)
        && !cashSizingDispatch
        && !equitySizingDispatch) {
      return null;
    }
    if (config.getBrokerTarget() == null) {
      return zeroSnapshot();
    }
    AccountSnapshotActivity accountStub =
        Workflow.newActivityStub(
            AccountSnapshotActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(config.getBrokerTarget().value()))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    AccountSnapshotRequest request = buildAccountSnapshotRequest(payload, config);
    try {
      AccountSnapshotResult result = accountStub.accountSnapshot(request);
      // A null result fails closed to the ZERO sentinel; null FIELDS inside a non-null result are
      // normalized to ZERO at the call site, preserving the pre-#790 cash semantics exactly.
      return result == null ? zeroSnapshot() : result;
    } catch (CanceledFailure cf) {
      throw cf;
    } catch (Exception e) {
      // Fail-closed dispatch failure (#323): emit a diagnostic warn + the symmetric
      // accountsnapshot_dispatch_failures_total counter (mirrors the #329 openpositions
      // value-failures counter) so a persistent broker outage that drives the cash term to the ZERO
      // sentinel is distinguishable in metrics from a legitimate zero-cash account. The counter
      // lives in an Activity (replay-safe; MeterRegistry cannot be touched from the deterministic
      // workflow body); the emit is wrapped non-fatal so a metrics outage cannot change the
      // fail-closed ZERO outcome below.
      Workflow.getLogger(CopytradeSignalWorkflowImpl.class)
          .warn(
              "accountSnapshot dispatch failed; failing closed to ZERO cash broker_target={} err={}",
              config.getBrokerTarget().value(),
              e.getMessage(),
              e);
      try {
        accountSnapshotMetrics.recordDispatchFailure(config.getBrokerTarget().value());
      } catch (RuntimeException metricsError) {
        if (metricsError instanceof CanceledFailure cf) throw cf;
        // observability-only; never let a metrics failure flip the fail-closed outcome.
      }
      return zeroSnapshot();
    }
  }

  /**
   * The fail-closed dispatch sentinel: cash and equity both ZERO ("dispatched but unavailable"),
   * driving every downstream consumer — the notional-cap gate, account_cash sizing, and
   * account_equity sizing (#790) — to reject rather than size from a fabricated figure.
   */
  private static AccountSnapshotResult zeroSnapshot() {
    AccountSnapshotResult zero = new AccountSnapshotResult();
    zero.setSchemaVersion(1L);
    zero.setEquity(BigDecimal.ZERO);
    zero.setCash(BigDecimal.ZERO);
    return zero;
  }

  /**
   * Builds the {@link AccountSnapshotRequest} the workflow dispatches to exec-svc. P4-c-b: carries
   * the payload's {@code tenant_id} so exec resolves THIS tenant's broker and the cap-basis cash
   * reads the tenant's own brokerage account (under env-fallback creds this is the same single
   * account, so it is behavior-preserving until per-tenant file creds are active). {@code
   * correlation_id} is the deterministic {@code signal_id} so audit traces stitch end-to-end.
   */
  private static AccountSnapshotRequest buildAccountSnapshotRequest(
      CopytradeSignalPayload payload, StrategyConfig config) {
    AccountSnapshotRequest r = new AccountSnapshotRequest();
    r.setSchemaVersion(1L);
    r.setBrokerTarget(
        AccountSnapshotRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    r.setTenantId(payload.getTenantId());
    r.setCorrelationId(payload.getSignalId());
    return r;
  }

  private long pendingTtlSecs(StrategyConfig config) {
    Long configured = config.getPendingTtlPaperSecs();
    return configured != null ? configured : DEFAULT_PENDING_TTL_PAPER_SECS;
  }

  /**
   * Issue #579 Finding 2: the entry path read the PAPER-named TTL field regardless of broker_target
   * — an operator tuning {@code pending_ttl_live_secs} changed the child-position fill gates but
   * NOT the live entry window, silently. At {@code v>=1} every entry-path consumer (the BTO entry
   * await, the repeg-active predicate, the STC position-lookup buffer) resolves through the same
   * live-aware selection the position path always used. {@code getVersion} is read HERE (idempotent
   * per change-id) so each execution path records it at its own first use; at {@code
   * DEFAULT_VERSION} the paper-named read is byte-identical — the await duration is a recorded
   * timer command, so in-flight histories must keep their original TTL.
   *
   * <p>No live behavior change at cutover: every live tenant currently carries {@code
   * pending_ttl_live_secs = 90}, equal to the paper-field/default value the entry read before —
   * this makes the KNOB honest, it does not move it.
   */
  private long entryPendingTtlSecs(StrategyConfig config) {
    int v = Workflow.getVersion(VERSION_ENTRY_TTL_LIVE_AWARE, Workflow.DEFAULT_VERSION, 1);
    return v >= 1 ? selectPendingTtlSecs(config) : pendingTtlSecs(config);
  }

  /**
   * Delay before the single entry re-peg. Unset applies {@link #DEFAULT_REPEG_AFTER_MS} — the
   * feature is ACTIVE by default, so null means "use the default", not "off". {@code 0} is the
   * explicit per-tenant off-switch and restores the one-shot entry with no redeploy.
   */
  private long repegAfterMs(StrategyConfig config) {
    Long configured = config.getRepegAfterMs();
    return configured != null ? configured : DEFAULT_REPEG_AFTER_MS;
  }

  /**
   * Result of the single entry re-peg attempt.
   *
   * @param replacement the newly placed order when the re-peg went through, else {@code null} (the
   *     original order still stands and should ride out the rest of the TTL)
   * @param adopted {@code true} when the original filled in the cancel race and was adopted — the
   *     entry is COMPLETE and no replacement exists
   */
  private record RepegOutcome(OrderIntentResult replacement, boolean adopted) {}

  /** True when {@code t} is, or wraps, a workflow cancellation — which must always propagate. */
  private static boolean isCancellation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof CanceledFailure) {
        return true;
      }
    }
    return false;
  }

  /**
   * Audit label for why a re-peg had no usable quote: the status, or the failure that replaced it.
   */
  private static String quoteStatusLabel(OptionQuoteResult quote, String quoteError) {
    if (quote != null) {
      return quote.getStatus().value();
    }
    return quoteError != null ? "ERROR:" + quoteError : "NULL";
  }

  /**
   * PLAN-2026-08-04-bto-entry-repeg: cancel the unfilled entry and re-place it one penny past the
   * live ask, bounded by the re-peg ceiling. At most ONE of these runs per entry — the ceiling is
   * where the chase stops, by design.
   *
   * <p>Skips the re-peg (returning a null replacement, leaving the original order standing) when
   * the quote is unavailable or the ask offers nothing above the initial peg. That is the ENTRY
   * fail-safe and it is the inverse of the exit path's: a force-close with no quote falls back to a
   * marketable order because the position must be closed, whereas an entry with no quote simply
   * does not buy.
   *
   * <p>No risk re-check here: the gates and sizing already ran against the ceiling this walks
   * toward (see {@code maxEntryCost} in {@link #handleBto}), so the higher limit is pre-approved.
   */
  private RepegOutcome repegEntry(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      ContractResolveResult resolved,
      long contracts,
      String intentKey,
      OrderIntentResult placed,
      BigDecimal initialPeg) {

    GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
    qreq.setSchemaVersion(1L);
    qreq.setTenantId(payload.getTenantId());
    qreq.setStrategyId(payload.getStrategyId());
    qreq.setContractSymbol(resolved.optionSymbol());
    // Any failure = no quote = no re-peg. The Activity is contracted to RETURN FAILED/UNAVAILABLE
    // rather than throw, but this catch is what makes the guarantee hold when that contract does
    // not: a ScheduleToClose timeout (market-data not polling) and a retries-exhausted failure both
    // arrive here as exceptions, and letting either escape would fail the workflow and strand the
    // live entry order with no TTL cancel. CanceledFailure is rethrown so workflow cancellation is
    // never swallowed.
    OptionQuoteResult quote;
    String quoteError = null;
    try {
      quote = optionQuote.getOptionQuote(qreq);
    } catch (RuntimeException e) {
      // Workflow cancellation must NEVER be swallowed. With an activity in flight the SDK wraps it
      // as ActivityFailure(cause=CanceledFailure), so testing the top-level type alone misses it.
      if (isCancellation(e)) {
        throw e;
      }
      quote = null;
      quoteError = e.getClass().getSimpleName();
    }

    BigDecimal ask =
        quote != null && quote.getStatus() == OptionQuoteResult.Status.OK ? quote.getAsk() : null;
    BigDecimal ceiling = BtoPricing.computeRepegCeiling(payload, config);
    BigDecimal repegLimit = BtoPricing.computeRepegLimit(ask, ceiling, initialPeg);

    if (repegLimit == null) {
      // Explains an EntryExpired that never re-pegged, which would otherwise look identical to the
      // pre-change behavior in the audit trail. Rides the already-registered OrderCancelRequested
      // kind (no KindRegistryGuard change) with reason=repeg_skipped and NO cancel following it —
      // the distinguishing detail is that no OrderCancelled/OrderSubmitted pair comes after.
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_REQUESTED,
          subject(
              "intent_key",
              intentKey,
              "broker_order_id",
              placed.getBrokerOrderId(),
              "reason",
              "repeg_skipped",
              "quote_status",
              quoteStatusLabel(quote, quoteError),
              "ask",
              ask,
              "ceiling",
              ceiling,
              "initial_peg",
              initialPeg,
              "note",
              "no_repeg_original_order_stands"));
      return new RepegOutcome(null, false);
    }

    logAuditWhileOrderLive(
        payload,
        KIND_ORDER_CANCEL_REQUESTED,
        subject(
            "intent_key",
            intentKey,
            "broker_order_id",
            placed.getBrokerOrderId(),
            "reason",
            "repeg"));

    OrderIntentResult cancelResult;
    try {
      cancelResult = exec.cancelOrder(intentKey);
    } catch (RuntimeException e) {
      // Cancel failed: do NOT place a second order — that is how a workflow ends up long twice.
      // Leave the original standing; the TTL path below still cancels and audits it.
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_FAILED,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              resolved.optionSymbol(),
              "intent_key",
              intentKey,
              "broker_order_id",
              placed.getBrokerOrderId(),
              "reason",
              "repeg",
              "severity",
              "WARN",
              "note",
              "repeg_abandoned_original_order_stands"));
      return new RepegOutcome(null, false);
    }

    if (cancelResult.getState() == OrderIntentResult.State.FILLED) {
      // The tight peg filled while we were cancelling it — the good outcome. Adopt the lot through
      // the SAME recovery path the TTL branch uses, and place NO replacement.
      handleCancelOnFilled(payload, config, resolved, cancelResult, RECOVERY_CANCEL_ON_FILLED);
      return new RepegOutcome(null, true);
    }

    if (cancelResult.getState() != OrderIntentResult.State.CANCELLED) {
      // THE CANCEL FAILED WITHOUT THROWING, so the catch above does not cover this. A broker
      // cancel rejection (404 on a stale broker_order_id, 422 "not cancelable" while pending_new)
      // routes to ExecActivitiesImpl's FAILED branch -> JooqOrderIntentJournal#markCancelFailed,
      // which writes only last_error and LEAVES THE ROW SUBMITTED. The activity returns normally
      // with state=SUBMITTED and the original order is STILL LIVE at the broker.
      //
      // Placing a replacement here is how one signal ends up with two working orders and, if both
      // fill, double the sized position. Abandon instead: no supersede, no replacement. The
      // original keeps standing and handleTtlExpired cancels and audits it as it always has --
      // that method has had this exact CANCELLED/else split all along; the re-peg copied its
      // FILLED branch and missed this one.
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_FAILED,
          subject(
              "signal_id",
              payload.getSignalId(),
              "option_symbol",
              resolved.optionSymbol(),
              "intent_key",
              intentKey,
              "broker_order_id",
              placed.getBrokerOrderId(),
              "broker_reason",
              cancelResult.getLastError(),
              "reason",
              "repeg",
              "severity",
              "ERROR",
              "note",
              "repeg_abandoned_original_order_still_live"));
      return new RepegOutcome(null, false);
    }

    logAuditWhileOrderLive(
        payload,
        KIND_ORDER_CANCELLED,
        subject(
            "intent_key",
            intentKey,
            "broker_order_id",
            placed.getBrokerOrderId(),
            "reason",
            "repeg"));

    if (fillEvent != null) {
      // A fill signal landed while the cancel was in flight. Those contracts were REALLY BOUGHT --
      // the dispatcher only signals against a broker fill event -- so this is evidence, not noise.
      // An earlier version of this method DISCARDED it to avoid threading the wrong order id. That
      // was wrong in the dangerous direction: discarding orphans a real position AND stacks a
      // full-size replacement on top of it. Abandon the re-peg and let the caller's await adopt
      // the fill exactly as it would have without this feature.
      //
      // This also covers the PARTIAL fill the journal cannot see: markFilled only terminalizes at
      // filledQty >= qty, so a partial leaves the row CANCELLED with filled_qty null, invisible to
      // cancelResult -- but the dispatcher signals partials deliberately, so it surfaces here.
      // OrderCancelRequested, NOT OrderCancelFailed: OrderCancelFailed pages (it is in the alert
      // allowlist) and NOTHING FAILED HERE — the entry filled, which is the outcome we wanted.
      // Paging on it would train the operator to ignore the kind that does mean a live order is
      // stuck. Same reasoning as the repeg_skipped note above.
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_REQUESTED,
          subject(
              "intent_key", intentKey,
              "broker_order_id", placed.getBrokerOrderId(),
              "reason", "repeg",
              "outcome", "filled_during_cancel",
              "note", "repeg_abandoned_fill_landed_during_cancel"));
      return new RepegOutcome(null, false);
    }

    if (riskBreachReceived) {
      // A kill-switch / daily-loss cascade landed while we were cancelling. The risk decision that
      // authorised this entry is now void, and the original order is already cancelled, so the
      // safe move is to stop here rather than open a NEW real-money position into a breach. The
      // caller's breach branch handles the abort. Without this check the re-peg is a hole in the
      // cascade: the gates only ran at t=0.
      // Also NOT a failure: the cascade did exactly what it exists to do. The breach itself is
      // already audited and alerted on its own path; a second "order FAILED" page here would be
      // duplicate noise attached to the wrong story.
      logAuditWhileOrderLive(
          payload,
          KIND_ORDER_CANCEL_REQUESTED,
          subject(
              "intent_key", intentKey,
              "broker_order_id", placed.getBrokerOrderId(),
              "reason", "repeg",
              "outcome", "risk_breach",
              "note", "repeg_abandoned_risk_breach"));
      return new RepegOutcome(null, false);
    }

    // Only now is the original definitively dead: broker-confirmed CANCELLED, and no fill was
    // signalled. Mark it superseded BEFORE placing the replacement so a LATER fill claiming it --
    // which would contradict the cancel ack -- cannot be adopted in place of the replacement's.
    supersededEntryOrderIds.add(placed.getBrokerOrderId());
    supersededEntryIntentKey = intentKey;

    // Distinct intent key: the original was cancelled and exec is idempotent BY intent key, so
    // reusing it would collide with the cancelled client_order_id. Mirrors the exit ladder's
    // :reprice-N suffix.
    String repegKey = intentKey + ":repeg-1";
    OrderIntent repegIntent = newIntent(payload, config, resolved, contracts, repegKey, repegLimit);
    OrderIntentResult repegPlaced = exec.placeOrder(repegIntent);

    logAuditWhileOrderLive(
        payload,
        KIND_ORDER_SUBMITTED,
        subject(
            "intent_key",
            repegPlaced.getIntentKey(),
            "broker_order_id",
            repegPlaced.getBrokerOrderId(),
            "option_symbol",
            resolved.optionSymbol(),
            "side",
            "BUY",
            "qty",
            contracts,
            "broker_target",
            config.getBrokerTarget().value(),
            "limit_price_strategy",
            "repeg",
            "limit_price",
            repegLimit,
            "ask",
            ask,
            "ceiling",
            ceiling,
            "initial_peg",
            initialPeg,
            "source_premium",
            "live_quote"));

    entryStatus.setBrokerOrderId(repegPlaced.getBrokerOrderId());
    return new RepegOutcome(repegPlaced, false);
  }

  /**
   * Issue #212: selects the per-strategy pending TTL value to forward into PositionWorkflowInput's
   * first_fill_ttl_secs / exit_fill_ttl_secs. Inspects {@code config.broker_target.value()}: a
   * "live" substring uses {@code pending_ttl_live_secs}; otherwise (any "paper" or unknown variant)
   * uses {@code pending_ttl_paper_secs}. Falls back to {@link #DEFAULT_PENDING_TTL_PAPER_SECS}
   * (90L) when the selected StrategyConfig field is null/absent. The substring match keeps the
   * helper open to future broker_target enum additions (e.g. {@code ibkr-paper}, {@code
   * tradier-live}) without re-touching this method.
   */
  long selectPendingTtlSecs(StrategyConfig config) {
    String target = config.getBrokerTarget() != null ? config.getBrokerTarget().value() : "";
    boolean isLive = target.contains("live");
    Long configured = isLive ? config.getPendingTtlLiveSecs() : config.getPendingTtlPaperSecs();
    return configured != null ? configured : DEFAULT_PENDING_TTL_PAPER_SECS;
  }

  private double defaultStcFraction(StrategyConfig config) {
    BigDecimal configured = config.getDefaultStcFraction();
    return configured != null ? configured.doubleValue() : DEFAULT_STC_FRACTION;
  }

  private static Map<String, Double> toDoubleMap(Map<String, BigDecimal> in) {
    if (in == null || in.isEmpty()) {
      return Map.of();
    }
    Map<String, Double> out = new LinkedHashMap<>(in.size());
    for (Map.Entry<String, BigDecimal> e : in.entrySet()) {
      if (e.getValue() != null) {
        out.put(e.getKey(), e.getValue().doubleValue());
      }
    }
    return out;
  }

  private OrderIntent newIntent(
      CopytradeSignalPayload payload,
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
    i.setSignalId(payload.getSignalId());
    i.setBrokerTarget(OrderIntent.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    // P4-c-b-2: carry the config-declared account so exec cross-checks it against the account the
    // per-tenant creds authenticate (fail-closed on a mismatch). Null for today's tenants (no
    // broker_account_id) → the cross-check is skipped → behavior-preserving.
    i.setBrokerAccountId(config.getBrokerAccountId());
    i.setOptionSymbol(resolved.optionSymbol());
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(contracts);
    i.setLimitPrice(limitPrice);
    i.setRecordedAt(workflowNow());
    return i;
  }

  private void logAudit(CopytradeSignalPayload payload, String kind, Map<String, Object> subject) {
    audit.log(auditEvent(payload, kind, subject));
  }

  /**
   * Audit an event emitted WHILE A LIVE ORDER IS WORKING AT THE BROKER, where the write must never
   * be allowed to wedge the workflow.
   *
   * <p>The default audit stub carries only a start-to-close timeout, so Temporal's default retry
   * policy applies: UNLIMITED attempts. {@link
   * com.ohmytradeagent.orchestrator.activities.AuditActivitiesImpl} writes to Postgres in-process,
   * so a database outage makes every attempt fail and the workflow blocks — indefinitely — at
   * whichever audit call it happened to be on. On this path that is not a stalled workflow, it is
   * an un-cancellable working order: the TTL cancel lives further down the same method, so it never
   * runs, and the order stays live at the broker until the outage clears or Alpaca expires it at
   * the close. The worst instance is the {@code OrderCancelRequested} write that IMMEDIATELY
   * PRECEDES {@code exec.cancelOrder} — an audit outage there is precisely what stops the cancel.
   *
   * <p>Giving up on the row is the right trade because the event is NOT lost: {@code
   * AuditActivitiesImpl.log} writes the full event to the application log BEFORE it touches the
   * database, so a dropped write costs the queryable, hash-chained copy while forensics keeps the
   * log line. Nor does a skipped insert corrupt the chain — {@code prev_hash}/{@code row_hash} are
   * computed at insert time under an advisory lock, so the chain over the rows that DID land stays
   * valid and verifiable. A completeness gap, not a validity break.
   *
   * <p>Cancellation still propagates: only a genuine activity failure is swallowed.
   */
  private void logAuditWhileOrderLive(
      CopytradeSignalPayload payload, String kind, Map<String, Object> subject) {
    try {
      auditBounded.log(auditEvent(payload, kind, subject));
    } catch (RuntimeException e) {
      if (isCancellation(e)) {
        throw e;
      }
      // Swallowed deliberately — see the javadoc. Reaching the order-safety path below matters
      // more than this row, and the event is already in the application log.
    }
  }

  /**
   * Issue #308: builds the enriched {@code SignalReceived} subject. Beyond the original {@code
   * signal_id}, it carries the parsed signal fields the feed mirror renders. Each value is rendered
   * to a deterministic, null-safe String (enums to their wire value; date/decimal to {@code
   * toString()}) so the JSONB subject is replay-stable regardless of Jackson type handling.
   */
  private static Map<String, Object> receivedSubject(CopytradeSignalPayload payload) {
    return subject(
        "signal_id", payload.getSignalId(),
        "action", str(payload.getAction()),
        "ticker", str(payload.getTicker()),
        "expiry", str(payload.getExpiry()),
        "strike", str(payload.getStrike()),
        "right", str(payload.getRight()),
        "price", str(payload.getPrice()),
        "author", str(payload.getAuthor()),
        "posted_at", str(payload.getPostedAt()));
  }

  /**
   * Phase 2 (PLAN-2026-07-06): builds the {@code EntryWorkflowFailed} subject for the top-level
   * failure-audit. Carries {@code signal_id} (so the page stitches to the "Signal received" message
   * already on Discord) and the failure forensics as {@code reason_code} / {@code reason_detail} —
   * the exact shape {@link com.ohmytradeagent.orchestrator.alert.OrderFailureAlerter}'s default
   * order-failure embed reads ({@code reasonOf} -> "reason_code — reason_detail"). For an {@link
   * ApplicationFailure} (the guard's {@code PreTradeCheckMisconfigured} throw, the
   * ExecActivitiesFactory invalid-target throw) the {@code reason_code} is the failure type and
   * {@code reason_detail} the original message; any other {@link TemporalFailure} falls back to the
   * class simple-name + message. Also carries {@code op} — the operation label ({@code BTO (entry)}
   * / {@code STC (exit)} / {@code AVG (add)}) derived from the action so the page's title is
   * correct on the STC/AVG path (the catch wraps all three), omitted for an absent action — and
   * {@code ticker} (the underlying) as the best available symbol identifier: this failure can fire
   * BEFORE contract resolution, so no OCC exists to render. Every value is rendered
   * deterministically (this runs only on the terminal failure path, so it commits once with the
   * workflow's FAILED completion).
   */
  private static Map<String, Object> entryFailureSubject(
      CopytradeSignalPayload payload, TemporalFailure t) {
    // assertPreTradeCheckRoutable is an ACTIVITY, so its non-retryable ApplicationFailure surfaces
    // at the workflow boundary wrapped in an ActivityFailure (t here). Unwrap the cause chain to
    // the
    // ApplicationFailure so reason_code carries the meaningful type (PreTradeCheckMisconfigured),
    // not the generic wrapper. failure_type keeps the top-level type for forensics.
    ApplicationFailure app = findApplicationFailure(t);
    String reasonCode;
    String reasonDetail;
    if (app != null) {
      reasonCode = app.getType();
      reasonDetail = app.getOriginalMessage();
    } else {
      reasonCode = t.getClass().getSimpleName();
      reasonDetail = t.getMessage();
    }
    Map<String, Object> s =
        subject(
            "signal_id",
            payload.getSignalId(),
            "ticker",
            str(payload.getTicker()),
            "reason_code",
            reasonCode == null || reasonCode.isBlank() ? t.getClass().getSimpleName() : reasonCode,
            "reason_detail",
            reasonDetail == null ? "" : reasonDetail,
            "failure_type",
            t.getClass().getName(),
            "outcome",
            "FAILED");
    // op is omitted for an absent action; OrderFailureAlerter then falls back to its default label.
    String op = opLabel(payload.getAction());
    if (op != null) {
      s.put("op", op);
    }
    return s;
  }

  /**
   * Maps the signal action to the human operation label the failure page's title carries: {@code
   * BTO} -> {@code "BTO (entry)"}, {@code STC} -> {@code "STC (exit)"}, {@code AVG} -> {@code "AVG
   * (add)"}; {@code null} for an absent action (defensive — the {@code op} subject field is then
   * omitted and {@link com.ohmytradeagent.orchestrator.alert.OrderFailureAlerter} falls back to its
   * {@code STC_KINDS} default label). Pure switch, no clock/random — deterministic / replay-safe.
   * Visible for testing.
   */
  static String opLabel(CopytradeSignalPayload.Action action) {
    if (action == null) {
      return null;
    }
    switch (action) {
      case BTO:
        return "BTO (entry)";
      case STC:
        return "STC (exit)";
      case AVG:
        return "AVG (add)";
      default:
        return null;
    }
  }

  /** Walks up to five cause levels looking for the underlying {@link ApplicationFailure}. */
  private static ApplicationFailure findApplicationFailure(Throwable t) {
    Throwable cur = t;
    for (int i = 0; i < 5 && cur != null; i++) {
      if (cur instanceof ApplicationFailure af) {
        return af;
      }
      cur = cur.getCause();
    }
    return null;
  }

  /** Null-safe stringifier used to render the enriched SignalReceived subject deterministically. */
  private static String str(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof CopytradeSignalPayload.Action action) {
      return action.value();
    }
    if (value instanceof CopytradeSignalPayload.Right right) {
      return right.value();
    }
    return String.valueOf(value);
  }

  /** Builds an insertion-ordered subject map from alternating key/value varargs. */
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

  private AuditEvent auditEvent(
      CopytradeSignalPayload payload, String kind, Map<String, ?> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(payload.getTenantId());
    event.setStrategyId(payload.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:CopytradeSignalWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(payload.getSignalId());
    return event;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
