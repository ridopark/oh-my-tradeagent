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
          "ExitQueued");

  /**
   * "Hard" terminal kinds that must follow a real entry. A hard close without a preceding {@link
   * #ENTRY_KINDS} event is reported as {@code OrphanCloseWithoutEntry}.
   */
  public static final Set<String> HARD_TERMINAL_CLOSE_KINDS =
      Set.of(
          "PositionClosed",
          "EodForceFlattened",
          "ExpiryForceFlattened",
          "SignalAbortedByRiskBreach");

  /**
   * "Soft" terminal kinds that legitimately close a lifecycle that never opened — pre-fill
   * rejections, expiries, and cancels. {@code SignalRejected} fires before any broker activity;
   * {@code EntryExpired} fires when the entry leg's TTL elapses without a fill; {@code
   * OrderCancelled} fires when the order is cancelled before fill.
   */
  public static final Set<String> SOFT_TERMINAL_CLOSE_KINDS =
      Set.of("EntryExpired", "OrderCancelled", "SignalRejected");

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
          "PartialExitRequested",
          "PartialExitFilled",
          "ExitDuplicateSuppressed",
          "ExitQueued",
          "EodForceFlattenRequested",
          "EodForceFlattened",
          "EodForceFlattenFailed",
          "ExpiryForceFlattenRequested",
          "ExpiryForceFlattened",
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
          "ReconciliationMetricsRecordFailed",
          // LivePromotionActivitiesImpl
          "LivePromotionApproved",
          // TenantConfigChangedEmitter (no KIND_ constant; literal string in the emitter)
          "TenantConfigChanged");
}
