package com.ohmytradeagent.audit;

import java.util.Set;

/**
 * Registry of all known audit-event {@code kind} values emitted by orchestrator workflows and
 * activities. Issue #90 (audit-completeness verifier) uses this registry for two purposes:
 *
 * <ol>
 *   <li>Build-time guardrail (CI lint): {@link com.ohmytradeagent.audit.lint.KindRegistryGuardTest}
 *       scans every {@code services/orchestrator/.../*Impl.java} for {@code KIND_X = "..."}
 *       constants and asserts each literal is present in {@link #ALL_KINDS}. Adding a new
 *       state-changing Activity without registering its kind here fails the build — the "developer
 *       forgot the audit event" failure mode from the issue body, caught at compile-time.
 *   <li>Run-time classification: {@link LedgerRederiver} groups kinds into {@link #ENTRY_KINDS},
 *       {@link #PARTIAL_EXIT_REQUEST_KINDS}, {@link #PARTIAL_EXIT_FILL_KINDS}, and {@link
 *       #TERMINAL_CLOSE_KINDS}. Any kind not in one of these groups but still in {@link #ALL_KINDS}
 *       is a "neutral" event (signals, kill-switch, etc.) — the verifier ignores it for ledger
 *       re-derivation. Kinds not in {@link #ALL_KINDS} at all are reported as {@code UnknownKind}
 *       findings at run time so the verifier flags drift between the orchestrator and this registry
 *       on production data.
 * </ol>
 *
 * <p>This registry is intentionally hand-maintained rather than reflected from the orchestrator
 * source. Reflection would couple build order (audit-svc would need orchestrator-svc on the compile
 * classpath, which it does not), and the build-time scan is the explicit forgot-to-register
 * tripwire — making it a registry write-and-test invites the developer to actually think about
 * which group a new kind belongs to.
 */
public final class AuditEventKinds {

  private AuditEventKinds() {}

  // ---- Position-lifecycle ledger kinds (grouped) ----

  /** Kinds that open a position lifecycle. Keyed on the audit event's correlation_id. */
  public static final Set<String> ENTRY_KINDS =
      Set.of(
          // CopytradeSignalWorkflowImpl: confirmed broker fill of the entry leg.
          "EntryFilled",
          // PositionWorkflowImpl: position object created from the filled entry.
          "PositionEntered");

  /**
   * Kinds that request a partial exit on a position; each must be matched by a fill or suppression
   * kind. Only {@code PartialExitRequested} (emitted by {@code PositionWorkflowImpl}) belongs here.
   * The upstream {@code ExitRequested} (emitted by {@code CopytradeSignalWorkflowImpl} as it
   * dispatches the STC signal to the position workflow) is the same logical request observed one
   * layer up — it is fulfilled by the same {@code PartialExitFilled} event, so counting it would
   * inflate the request count and double-fault every valid lifecycle. {@code ExitRequested} is
   * therefore a "neutral" event (in {@link #ALL_KINDS} but not in any lifecycle-checking group).
   */
  public static final Set<String> PARTIAL_EXIT_REQUEST_KINDS = Set.of("PartialExitRequested");

  /** Kinds that fulfill a partial-exit request (either by broker fill or by suppression). */
  public static final Set<String> PARTIAL_EXIT_FILL_KINDS =
      Set.of(
          "PartialExitFilled",
          // Idempotency: duplicate exit signal arriving after a fill is suppressed, not re-filled.
          "ExitDuplicateSuppressed",
          // The position queued a duplicate exit signal while the prior one was inflight;
          // it counts as fulfilling the duplicate request because the original will fill the
          // next time the position is signal-ready.
          "ExitQueued",
          // Issue #205: runner-quantum partial-exit skip. When remainingQty<=1 and floor(qty *
          // fraction)==0 and StrategyConfig.min_partial_qty_behavior=skip (default), the partial
          // is fulfilled by deciding-not-to-fill — same pattern as ExitDuplicateSuppressed.
          "PartialExitSkippedMinQty");

  /**
   * "Hard" terminal kinds that must follow a real entry. A hard close without a preceding {@link
   * #ENTRY_KINDS} event is reported as {@code OrphanCloseWithoutEntry}.
   */
  public static final Set<String> HARD_TERMINAL_CLOSE_KINDS =
      Set.of(
          "PositionClosed",
          "EodForceFlattened",
          "ExpiryForceFlattened",
          // Plan-2B R-AB-1: the multi-day expiry-lead bounded flatten terminal marker. Same
          // hard-terminal-close family as Eod/Expiry force-flatten — it closes a lifecycle that
          // had a real entry.
          "ExpiryLeadForceFlattened",
          "SignalAbortedByRiskBreach");

  /**
   * "Soft" terminal kinds that legitimately close a lifecycle that never opened — pre-fill
   * rejections, expiries, and cancels. {@code SignalRejected} fires before any broker activity;
   * {@code EntryExpired} fires when the entry leg's TTL elapses without a fill; {@code
   * OrderCancelled} fires when the order is cancelled before fill; {@code PositionNeverFilled}
   * (Issue #203) fires when a PositionWorkflow's first-fill TTL elapses without an {@code onFill}
   * signal — same family of "never opened" terminations, one layer deeper in the workflow stack.
   */
  public static final Set<String> SOFT_TERMINAL_CLOSE_KINDS =
      Set.of("EntryExpired", "OrderCancelled", "SignalRejected", "PositionNeverFilled");

  /**
   * Every kind that terminates a lifecycle. An open lifecycle (one with an {@link #ENTRY_KINDS}
   * event) must have at least one of these inside the verifier's date range, or it is reported as
   * {@code MissingTerminalClose}.
   */
  public static final Set<String> TERMINAL_CLOSE_KINDS =
      unionOf(HARD_TERMINAL_CLOSE_KINDS, SOFT_TERMINAL_CLOSE_KINDS);

  private static Set<String> unionOf(Set<String> a, Set<String> b) {
    java.util.Set<String> out = new java.util.HashSet<>(a);
    out.addAll(b);
    return Set.copyOf(out);
  }

  /**
   * Complete enumeration of every audit kind the orchestrator may emit, drawn from the {@code
   * KIND_X} constants in:
   *
   * <ul>
   *   <li>{@code orchestrator/workflows/CopytradeSignalWorkflowImpl.java}
   *   <li>{@code orchestrator/workflows/PositionWorkflowImpl.java}
   *   <li>{@code orchestrator/workflows/KillSwitchWorkflowImpl.java}
   *   <li>{@code orchestrator/workflows/ReconciliationWorkflowImpl.java}
   *   <li>{@code orchestrator/activities/LivePromotionActivitiesImpl.java}
   * </ul>
   *
   * <p>Plus the {@code TenantConfigChanged} kind emitted by {@code TenantConfigChangedEmitter}. The
   * guardrail test (see {@link com.ohmytradeagent.audit.lint.KindRegistryGuardTest}) re-scans those
   * source files on every build to assert this set stays in sync.
   */
  public static final Set<String> ALL_KINDS =
      Set.of(
          // CopytradeSignalWorkflowImpl
          "SignalReceived",
          "SignalAccepted",
          "SignalRejected",
          "OrderSubmitted",
          "OrderCancelRequested",
          "OrderCancelled",
          "OrderCancelFailed",
          "EntryExpired",
          "EntryFilled",
          "ExitRequested",
          "OrphanSTC",
          "AvgSkipped",
          "ChandelierArmRequested",
          "SignalAbortedByRiskBreach",
          // PositionWorkflowImpl
          "PositionEntered",
          "PositionNeverFilled",
          "PartialExitRequested",
          "PartialExitFilled",
          // Issue #204: per-exit-cycle timeout event emitted by PositionWorkflowImpl when an
          // exit order does not receive a fill within the bounded EXIT_FILL_TTL_SECS. NOT a
          // lifecycle terminator (the workflow continues to drain pendingExits); intentionally
          // placed in ALL_KINDS only, not in ENTRY_KINDS, PARTIAL_EXIT_FILL_KINDS, or any
          // *_TERMINAL_CLOSE_KINDS set. Distinct from PositionNeverFilled (#203), which IS a
          // soft-terminal because it ends the workflow.
          "PartialExitFillTimeout",
          // Issue #216: per-retry-cycle event emitted by PositionWorkflowImpl when the v=1
          // exit-fill timeout fires AND a single retry is dispatched with a fresh limit price
          // and a fresh intent_key. NOT a lifecycle event — the original signal_id is still
          // in flight; PartialExitFilled (on retry success) or PartialExitFillTimeout (on
          // second timeout, after which the STC is dropped) closes the cycle. Intentionally
          // placed in ALL_KINDS only, not in PARTIAL_EXIT_REQUEST_KINDS or _FILL_KINDS.
          "PartialExitRetryRequested",
          // Exit-retry late-fill reconcile (VERSION_EXIT_RETRY_LATE_FILL_RECONCILE): emitted by
          // PositionWorkflowImpl when a timed-out exit order fills LATE during the best-effort
          // cancel and that late fill already satisfies the exit intent, so the retry is SKIPPED
          // (no order placed). Observability-only — the actual fill is the accompanying
          // PartialExitFilled audit; intentionally placed in ALL_KINDS only, NOT in
          // PARTIAL_EXIT_FILL_KINDS (it must not inflate the realized-P&L ledger atop the real
          // PartialExitFilled) nor any terminal/fill group.
          "PartialExitRetrySkippedSatisfied",
          // B2 (PLAN-exit-place-duplicate-422-crash): emitted by PositionWorkflowImpl when an exit
          // placeOrder activity FAILS (e.g. a duplicate-client_order_id 422 misclassified as a
          // non-retryable InvalidRequestError). Under VERSION_EXIT_PLACE_FAILURE_GUARD v>=1 the
          // catch emits this kind, releases the in-flight latch, and returns WITHOUT decrementing
          // remainingQty so the live lot stays managed instead of being orphaned by a crashed
          // workflow (the QQQ-725 incident). NOT a lifecycle/fill event (nothing was sold) —
          // intentionally placed in ALL_KINDS only for observability, not in any lifecycle group.
          // Paged by OrderFailureAlerter (B3).
          "PartialExitPlaceFailed",
          // Plan-2A R-AA-3: emitted by PositionWorkflowImpl's bounded scheduled-flatten when the
          // exit_floor is unusable (exit_floor_abs/exit_floor_pct null/absent/unresolvable, or the
          // resolved floor sits ABOVE the live bid). The flatten FAILS SAFE to a marketable exit
          // and
          // emits this loud kind. Observability-only — registered in ALL_KINDS only, NOT a
          // lifecycle/fill kind.
          "FlattenFloorConfigError",
          // Plan-2A R-AA-3: emitted by PositionWorkflowImpl's bounded scheduled-flatten when
          // GetOptionQuoteActivity returns FAILED/UNAVAILABLE so the bounded limit has no live-bid
          // anchor. The flatten FAILS SAFE to a marketable exit (not a stale ref-premium limit) and
          // emits this loud kind. Observability-only — registered in ALL_KINDS only.
          "FlattenQuoteUnavailable",
          // Issue #205: emitted by PositionWorkflowImpl when a partial-exit signal lands on a
          // remainingQty<=1 runner with floor(qty * fraction)==0 and config selects SKIP. Also
          // classified in PARTIAL_EXIT_FILL_KINDS (it fulfills the partial-exit request by
          // deciding-not-to-fill, same pattern as ExitDuplicateSuppressed).
          "PartialExitSkippedMinQty",
          "ExitDuplicateSuppressed",
          "ExitQueued",
          "EodForceFlattenRequested",
          "EodForceFlattened",
          "EodForceFlattenFailed",
          "ExpiryForceFlattenRequested",
          "ExpiryForceFlattened",
          // Plan-2B R-AB-1: dedicated expiry-LEAD flatten kinds. PositionWorkflow arms a guaranteed
          // bounded flatten timer for EVERY lot (multi-day included) at (expiry_close -
          // flatten_lead_minutes) ET; on fire it runs 2A's bounded reason-scoped flatten with
          // reason=expiry_lead. These dedicated kinds keep the lead-flatten lifecycle event from
          // being mislabeled as the EOD sweep (the legacy reason-else fallthrough used the Eod*
          // kinds). ExpiryLeadForceFlattened is the broker-confirmed terminal marker (P&L rides the
          // accompanying PartialExitFilled); ExpiryLeadFlattenRequested is the cause-of-flatten
          // marker. ExpiryLeadForceFlattened is a hard-terminal-close kind below.
          "ExpiryLeadFlattenRequested",
          "ExpiryLeadForceFlattened",
          "PositionClosed",
          "ChandelierArmed",
          "ChandelierTrailFired",
          "ChandelierArmRejected",
          "ChandelierSubscriptionFailed",
          "ChandelierUnarmedByExit",
          "RiskBreachReceived",
          "RiskBreachActed",
          "ForceCloseRequested",
          "ForceCloseNoop",
          // KillSwitchWorkflowImpl
          "KillSwitchTripped",
          "KillSwitchResetApproved",
          "KillSwitchHeartbeatError",
          // ReconciliationWorkflowImpl
          "ReconciliationStarted",
          "ReconciliationCompleted",
          "JournalOrphan",
          "BrokerOrphan",
          "PositionOrphan",
          // Issue #206 + #219: time-based escalation kinds. Emitted by
          // ReconciliationWorkflowImpl when the earliest matching *Orphan row in audit_log is
          // older than ORPHAN_ESCALATION_WINDOW (30m) AND no prior *OrphanOngoing row exists in
          // the debounce window — i.e. the orphan has been continuously observed for at least
          // 30 minutes. NOT lifecycle events — pure observability signals (dashboard-alert on
          // these instead of the per-cycle PositionOrphan/JournalOrphan noise). Intentionally
          // placed in ALL_KINDS only, not in any *_KINDS group.
          "PositionOrphanOngoing",
          "JournalOrphanOngoing",
          "ReconciliationMetricsRecordFailed",
          // Plan-2A R-AA-4: emitted by ReconciliationWorkflowImpl when a recon cycle detects a
          // PositionOrphan(journal_status='filled') and issues an ABANDON-child AdoptionWorkflow
          // start to re-attach the orphaned-but-legit lot to a managing PositionWorkflow. Pure
          // provenance / observability for the auto-adopt path — NOT a lifecycle event (the real
          // ledger ENTRY is the adopted workflow's PositionEntered). Intentionally placed in
          // ALL_KINDS only, not in ENTRY_KINDS or any *_TERMINAL_CLOSE_KINDS group.
          "ReconAutoAdoptionInitiated",
          // Issue #239/#285: emitted by AdoptionWorkflow when an operator-triggered
          // adoption reconstructs + starts a PositionWorkflow owner for a confirmed orphan. Pure
          // provenance / observability — NOT a lifecycle event. The real ledger ENTRY is the
          // adopted workflow's PositionEntered (fired by its first-fill gate after the onFill
          // signal); PositionAdopted only records orphan-detection -> broker-truth ->
          // reconstructed-input. Intentionally placed in ALL_KINDS only, not in ENTRY_KINDS or
          // any *_TERMINAL_CLOSE_KINDS group.
          "PositionAdopted",
          // LivePromotionActivitiesImpl
          "LivePromotionApproved",
          // TenantConfigChangedEmitter (no KIND_ constant; literal string in the emitter)
          "TenantConfigChanged");
}
