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
}
