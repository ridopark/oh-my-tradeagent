package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Resolves OCC option symbols to the currently-running {@link
 * com.ohmytradeagent.orchestrator.workflows.PositionWorkflow} workflow_id for a given (tenant,
 * strategy). Hot path is Redis-cached; cache miss falls back to Temporal Visibility query keyed on
 * {@code TenantStrategy + ContractSymbol + ExecutionStatus + WorkflowType}.
 */
@ActivityInterface
public interface PositionLookupActivities {

  /**
   * @return the matching PositionWorkflow workflow_id, or {@code null} when no open position exists
   *     for the OCC under this tenant/strategy.
   */
  String findPositionWorkflowId(String tenantId, String strategyId, String occ);

  /** Write-through cache hook called by CopytradeSignalWorkflow once a PositionWorkflow starts. */
  void cachePositionMapping(String tenantId, String strategyId, String occ, String workflowId);

  /**
   * Seeds the armed-watchlist Redis set ({@link
   * com.ohmytradeagent.contract.identity.WorkflowIds#armedWatchlistCacheKey}) with this leg's
   * workflow id when a {@code WatchlistTriggerWorkflow} arms. The BFF enumerates this set instead
   * of a lagging {@code listExecutions} visibility query. BEST-EFFORT: a Redis failure is swallowed
   * and logged so it can never fail or stall arming (the cache is a hint, never a gate); SADD is
   * idempotent and the key expires after 2 days.
   */
  void cacheArmedLeg(
      String tenantId, String strategyId, java.time.LocalDate etDate, String workflowId);

  /**
   * Issue #165 Phase 3: returns {@code true} iff a Temporal workflow with this id is currently
   * RUNNING. Returns {@code false} on {@code NotFound} (no execution by that id), and for any
   * non-RUNNING terminal status (COMPLETED, FAILED, TERMINATED, CANCELED, TIMED_OUT,
   * CONTINUED_AS_NEW). Used by {@code ReconciliationWorkflow} to detect filled-but-no-workflow
   * orphans without leaning on Visibility (which lags behind the durable history).
   */
  boolean isPositionWorkflowRunning(String workflowId);

  /**
   * Account-scoped (any-strategy) sibling-owner coverage probe for the cross-strategy
   * recon-orphan-suppression fix. Multiple strategies under one tenant route to the SAME broker
   * account, so a broker-held OCC managed by a DIFFERENT strategy's running {@code
   * PositionWorkflow} would otherwise false-page as a {@code PositionOrphan} in this strategy's
   * recon. Returns the summed {@code remainingQty} across every confirmed-RUNNING PositionWorkflow
   * (under ANY strategy of {@code tenantId}) that manages {@code occPadded}.
   *
   * <p>Resolution is cache-driven (Redis SCAN of {@code pos:{tenant}:*:{occPadded}}), confirmed
   * RUNNING per owner via {@link #isPositionWorkflowRunning}, with remaining qty read from each
   * owner's {@code positionState} query. BEST-EFFORT / read-only: any error returns {@code 0L}
   * (zero coverage → recon pages → safe degrade to today's behavior). {@code occPadded} must
   * already be in the padded canonical form (see {@code OccSymbol.padded}).
   */
  long sumRunningOwnerRemainingQtyForOcc(String tenantId, String occPadded);

  /**
   * Phase 3 (2026-06-24 remediation): Temporal Visibility fallback for cross-strategy recon-orphan
   * suppression when the Redis cross-strategy SCAN ({@link #sumRunningOwnerRemainingQtyForOcc})
   * returns 0 on a cache miss/lag. Unlike that SCAN (which reads only the Redis {@code pos:*}
   * cache), this probe queries Temporal Visibility for ANY RUNNING {@code PositionWorkflow} keyed
   * on {@code ContractSymbol = occPadded} across ALL strategies of {@code tenantId} (no {@code
   * TenantStrategy} predicate), so a sibling-strategy owner that was never cached (or whose cache
   * key lags) is still found.
   *
   * <p>Returns {@code true} iff at least one RUNNING PositionWorkflow on the tenant's shared broker
   * account manages {@code occPadded}. BEST-EFFORT / read-only: any error returns {@code false} (no
   * owner found → recon proceeds to page → safe degrade to pre-fix behavior, never masks a genuine
   * orphan). {@code occPadded} must already be in the padded canonical form (see {@code
   * OccSymbol.padded}).
   */
  boolean hasRunningOwnerForOcc(String tenantId, String occPadded);

  /**
   * Edited-signal supersede (F1): finds an open prior leg whose contract matches the corrected BTO
   * on underlying + strike + right but carries a DIFFERENT expiry — the wrong-expiry leg a
   * corrected signal supersedes. Temporal Visibility's {@code ContractSymbol} is EQUALITY-ONLY (no
   * STARTS_WITH/LIKE/IN), so an expiry-agnostic match cannot be a Visibility predicate; this
   * enumerates RUNNING PositionWorkflows for {@code (tenantId, strategyId)} keyed on {@code
   * TenantStrategy} (the proven idiom in {@link #hasRunningOwnerForOcc}) and filters in-process via
   * each owner's {@code positionState} query.
   *
   * <p>Returns the earliest-started matching candidate (the leg most likely to be the
   * just-placed-then-corrected one), or {@code null} when none matches. BEST-EFFORT / read-only:
   * any error returns {@code null} (no supersede — safe degrade, never auto-cancels on a probe
   * failure). The window (entryAt) and partial-exited guardrails are NOT applied here — the caller
   * (CopytradeSignalWorkflow) applies them deterministically against the returned candidate's
   * {@code entryAt} / {@code partialExited} so the 120s-window check uses the deterministic
   * workflow clock.
   *
   * @param underlying the corrected signal's underlying ticker (root), e.g. {@code SPY}
   * @param strike the corrected signal's strike (dollars), compared to each candidate's OCC strike
   * @param right the corrected signal's right ({@code C} or {@code P})
   * @param correctedExpiryDay the corrected signal's expiry as {@code yyyy-MM-dd}; a candidate
   *     whose OCC expiry EQUALS this is excluded (only a DIFFERENT expiry is a supersede target —
   *     same OCC is the existing dedup path)
   */
  SupersedeCandidate findOpenPositionByUnderlyingStrikeRight(
      String tenantId,
      String strategyId,
      String underlying,
      BigDecimal strike,
      String right,
      String correctedExpiryDay);

  /**
   * Edited-signal supersede (F1) candidate. {@code entryAt} / {@code partialExited} are read from
   * the owner's {@code positionState} query so the caller can apply the correction-window and
   * not-already-exiting guardrails deterministically.
   *
   * @param workflowId the matching prior leg's PositionWorkflow id (the supersede signal target)
   * @param occ the matching prior leg's OCC option symbol (the WRONG-expiry leg)
   * @param entryAt the prior leg's confirm instant ({@code null} if not yet confirmed)
   * @param partialExited whether the prior leg has already partially exited
   */
  record SupersedeCandidate(
      String workflowId, String occ, OffsetDateTime entryAt, boolean partialExited) {}
}
