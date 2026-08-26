package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.time.OffsetDateTime;

/**
 * Issue #206: read-side companion to {@link AuditActivities} for cross-cycle state lookups inside
 * the reconciliation workflow.
 *
 * <p>Reconciliation runs cron-fresh per tick ({@code recon/alpaca-paper/{{.ScheduledRunID}}-...}) —
 * each invocation is a brand new workflow execution and cannot carry in-workflow state across
 * ticks. To detect that the *same* orphan was already reported within the last hour (so we can
 * suppress duplicate audits and emit a single {@code PositionOrphanOngoing} / {@code
 * JournalOrphanOngoing} escalation event instead), the workflow queries {@code audit_log} for prior
 * detections of the same orphan key.
 *
 * <p>Queries are read-only and stateless; the activity is safe to fail soft (return 0) on DB outage
 * — the worst case is one extra duplicate audit row this tick.
 */
@ActivityInterface
public interface AuditQueryActivities {

  /**
   * Counts prior {@code PositionOrphan} audit rows for {@code (tenant_id, strategy_id)} where the
   * subject's {@code option_symbol} AND {@code journal_status} both match the supplied keys, and
   * {@code occurred_at >= since} (typically {@code now - 1h}).
   *
   * <p>Used to debounce per-cycle re-emission and to drive the ≥3-consecutive escalation threshold.
   *
   * @param tenantId audit tenant scope
   * @param strategyId audit strategy scope
   * @param optionSymbol the OCC the broker position belongs to (PositionOrphan's debounce key part)
   * @param journalStatus {@code "missing"} or {@code "filled"} — distinguishes the two subspecies
   *     of PositionOrphan so a flipped status emits a fresh detection rather than being debounced
   * @param since lower bound on {@code occurred_at} (inclusive)
   * @return count of matching prior detections; 0 if none / DB unavailable
   */
  long countPriorPositionOrphans(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since);

  /**
   * Phase 3 (2026-06-24 remediation): counts prior non-paging {@code PositionOrphanObserved} marker
   * rows for {@code (tenant_id, strategy_id)} with the same debounce key (option_symbol +
   * journal_status) within the window. Drives the FIRST-page debounce on the {@code "missing"}
   * branch: the first sweep that observes a missing-no-owner position writes a marker and
   * suppresses the page; the page only fires once a prior marker proves the position was observed
   * on a prior consecutive sweep. This absorbs the entry-race transient (a sweep that fires just
   * before EntryFilled + the position-cache seed). Distinct from {@link
   * #countPriorPositionOrphans}, which counts the actual paging rows and continues to drive the
   * {@code Ongoing} escalation.
   */
  long countPriorPositionOrphanObserved(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since);

  /**
   * Counts prior {@code JournalOrphan} audit rows for {@code (tenant_id, strategy_id)} where the
   * subject's {@code intent_key} matches and {@code occurred_at >= since}.
   *
   * <p>The intent_key is already globally unique (it carries tenant/strategy/signal anchors) so it
   * alone is enough to debounce.
   *
   * @param tenantId audit tenant scope
   * @param strategyId audit strategy scope
   * @param intentKey the journal entry's intent key (JournalOrphan's debounce key)
   * @param since lower bound on {@code occurred_at} (inclusive)
   * @return count of matching prior detections; 0 if none / DB unavailable
   */
  long countPriorJournalOrphans(
      String tenantId, String strategyId, String intentKey, OffsetDateTime since);

  /**
   * #817 partial-coverage debounce: prior {@code audit_log} rows of an arbitrary {@code kind} for
   * this (tenant, strategy, option_symbol) since {@code since}. Generic sibling of the
   * orphan-specific counters above so new debounced kinds stop needing a bespoke method each.
   * Fail-open to 0 like the others (a query outage must degrade to "first sweep", never wedge
   * recon).
   */
  long countPriorByKind(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String kind,
      java.time.OffsetDateTime since);

  /**
   * Counts prior {@code PositionOrphanOngoing} audit rows for {@code (tenant_id, strategy_id)} with
   * the same debounce key (option_symbol + journal_status) within the window. Used to enforce
   * "escalate once per window": if any {@code PositionOrphanOngoing} row already exists for this
   * key, the workflow must NOT emit another one even when the time-since-first-seen escalation gate
   * (issue #219) would otherwise re-trigger every subsequent tick within the debounce window.
   */
  long countPriorPositionOrphanOngoing(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since);

  /**
   * Counts prior {@code JournalOrphanOngoing} audit rows for {@code (tenant_id, strategy_id)} with
   * the same {@code intent_key} debounce key within the window. Same once-per-window enforcement as
   * {@link #countPriorPositionOrphanOngoing}.
   */
  long countPriorJournalOrphanOngoing(
      String tenantId, String strategyId, String intentKey, OffsetDateTime since);

  /**
   * Returns the earliest (oldest) {@code occurred_at} among matching prior {@code PositionOrphan}
   * rows. Used to populate {@code first_seen_at} on the {@code PositionOrphanOngoing} escalation
   * audit. Returns {@code null} when no matching row exists.
   */
  OffsetDateTime firstSeenPositionOrphan(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since);

  /**
   * Returns the earliest (oldest) {@code occurred_at} among matching prior {@code JournalOrphan}
   * rows. Used to populate {@code first_seen_at} on the {@code JournalOrphanOngoing} escalation
   * audit. Returns {@code null} when no matching row exists.
   */
  OffsetDateTime firstSeenJournalOrphan(
      String tenantId, String strategyId, String intentKey, OffsetDateTime since);

  /**
   * P3-a (multi-tenant-broker-credentials): SAFETY-GATE verify for the live-promotion dispatch gate
   * in {@code CopytradeSignalWorkflowImpl#handleBto}. Looks up the most-recent {@code
   * LivePromotionApproved} audit row for {@code (tenant_id, strategy_id)} whose subject {@code
   * broker_target} matches, and classifies it relative to a staleness floor.
   *
   * <p><b>This method is the deliberate fail-CLOSED exception to this interface's documented
   * fail-soft posture.</b> Where the orphan-debounce reads return the no-prior value (0 / null) on
   * a DB outage, this one returns {@link LivePromotionStatus#VERIFY_ERROR} — which the workflow
   * treats as a REFUSAL — so a verify failure can never let an unapproved live order through.
   *
   * @param tenantId audit tenant scope
   * @param strategyId audit strategy scope
   * @param brokerTarget the live broker_target the BTO would route to (e.g. {@code alpaca-live});
   *     matched against the approval row's {@code subject->>'broker_target'}
   * @param notStaleSince the staleness floor — an approval whose {@code occurred_at} is strictly
   *     before this is {@link LivePromotionStatus#STALE}
   * @return {@link LivePromotionStatus#VALID} only when a fresh matching approval exists; {@link
   *     LivePromotionStatus#ABSENT} / {@link LivePromotionStatus#STALE} / {@link
   *     LivePromotionStatus#VERIFY_ERROR} otherwise — all of which refuse the live order
   */
  LivePromotionStatus checkLivePromotion(
      String tenantId, String strategyId, String brokerTarget, OffsetDateTime notStaleSince);
}
