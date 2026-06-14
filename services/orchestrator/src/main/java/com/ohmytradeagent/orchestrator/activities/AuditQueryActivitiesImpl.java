package com.ohmytradeagent.orchestrator.activities;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issue #206: read-only jOOQ-backed lookups against {@code audit_log} for the reconciliation
 * workflow's debounce + escalation logic.
 *
 * <p>Fails soft: any {@link RuntimeException} during the SQL call logs a warning and returns the
 * "no prior detection" value (0 for counts, null for timestamps). A DB outage during reconciliation
 * cannot block the cycle — at worst, this tick emits one extra duplicate audit row.
 *
 * <p>{@code dsl} may be null in test envs without Postgres; all methods return the no-prior value
 * in that case (matching the {@link AuditActivitiesImpl} dual-mode pattern).
 */
@Component
public class AuditQueryActivitiesImpl implements AuditQueryActivities {

  private static final Logger log = LoggerFactory.getLogger(AuditQueryActivitiesImpl.class);

  private final DSLContext dsl;

  @Autowired
  public AuditQueryActivitiesImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public long countPriorPositionOrphans(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since) {
    if (dsl == null) {
      return 0L;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT COUNT(*) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'PositionOrphan' "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'option_symbol' = ? "
                  + "AND subject ->> 'journal_status' = ?",
              tenantId,
              strategyId,
              Timestamp.from(since.toInstant()),
              optionSymbol,
              journalStatus);
      return r == null ? 0L : r.get(0, Long.class);
    } catch (RuntimeException e) {
      log.warn(
          "countPriorPositionOrphans failed tenant={} strategy={} occ={} status={}; returning 0",
          tenantId,
          strategyId,
          optionSymbol,
          journalStatus,
          e);
      return 0L;
    }
  }

  @Override
  public long countPriorJournalOrphans(
      String tenantId, String strategyId, String intentKey, OffsetDateTime since) {
    if (dsl == null) {
      return 0L;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT COUNT(*) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'JournalOrphan' "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'intent_key' = ?",
              tenantId,
              strategyId,
              Timestamp.from(since.toInstant()),
              intentKey);
      return r == null ? 0L : r.get(0, Long.class);
    } catch (RuntimeException e) {
      log.warn(
          "countPriorJournalOrphans failed tenant={} strategy={} intent_key={}; returning 0",
          tenantId,
          strategyId,
          intentKey,
          e);
      return 0L;
    }
  }

  @Override
  public long countPriorPositionOrphanOngoing(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since) {
    if (dsl == null) {
      return 0L;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT COUNT(*) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'PositionOrphanOngoing' "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'option_symbol' = ? "
                  + "AND subject ->> 'journal_status' = ?",
              tenantId,
              strategyId,
              Timestamp.from(since.toInstant()),
              optionSymbol,
              journalStatus);
      return r == null ? 0L : r.get(0, Long.class);
    } catch (RuntimeException e) {
      log.warn(
          "countPriorPositionOrphanOngoing failed tenant={} strategy={} occ={} status={}; returning 0",
          tenantId,
          strategyId,
          optionSymbol,
          journalStatus,
          e);
      return 0L;
    }
  }

  @Override
  public long countPriorJournalOrphanOngoing(
      String tenantId, String strategyId, String intentKey, OffsetDateTime since) {
    if (dsl == null) {
      return 0L;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT COUNT(*) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'JournalOrphanOngoing' "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'intent_key' = ?",
              tenantId,
              strategyId,
              Timestamp.from(since.toInstant()),
              intentKey);
      return r == null ? 0L : r.get(0, Long.class);
    } catch (RuntimeException e) {
      log.warn(
          "countPriorJournalOrphanOngoing failed tenant={} strategy={} intent_key={}; returning 0",
          tenantId,
          strategyId,
          intentKey,
          e);
      return 0L;
    }
  }

  @Override
  public OffsetDateTime firstSeenPositionOrphan(
      String tenantId,
      String strategyId,
      String optionSymbol,
      String journalStatus,
      OffsetDateTime since) {
    if (dsl == null) {
      return null;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT MIN(occurred_at) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'PositionOrphan' "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'option_symbol' = ? "
                  + "AND subject ->> 'journal_status' = ?",
              tenantId,
              strategyId,
              Timestamp.from(since.toInstant()),
              optionSymbol,
              journalStatus);
      Timestamp ts = r == null ? null : r.get(0, Timestamp.class);
      return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    } catch (RuntimeException e) {
      log.warn(
          "firstSeenPositionOrphan failed tenant={} strategy={} occ={} status={}; returning null",
          tenantId,
          strategyId,
          optionSymbol,
          journalStatus,
          e);
      return null;
    }
  }

  @Override
  public OffsetDateTime firstSeenJournalOrphan(
      String tenantId, String strategyId, String intentKey, OffsetDateTime since) {
    if (dsl == null) {
      return null;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT MIN(occurred_at) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'JournalOrphan' "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'intent_key' = ?",
              tenantId,
              strategyId,
              Timestamp.from(since.toInstant()),
              intentKey);
      Timestamp ts = r == null ? null : r.get(0, Timestamp.class);
      return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    } catch (RuntimeException e) {
      log.warn(
          "firstSeenJournalOrphan failed tenant={} strategy={} intent_key={}; returning null",
          tenantId,
          strategyId,
          intentKey,
          e);
      return null;
    }
  }

  /**
   * P3-a (multi-tenant-broker-credentials): SAFETY GATE — fails CLOSED. This is the deliberate
   * exception to this class's documented fail-soft (return 0) posture: a verify failure must refuse
   * a live order, never let an unapproved one through. Do not "fix" this back to fail-soft.
   *
   * <p>Queries the most-recent {@code LivePromotionApproved} row for {@code (tenant_id,
   * strategy_id)} whose subject {@code broker_target} matches, selecting its {@code occurred_at}
   * (which equals {@code approved_at} for these rows — {@code LivePromotionActivitiesImpl} sets
   * both to the same {@code now} at record time). Classification:
   *
   * <ul>
   *   <li>{@code dsl == null} (test env without Postgres) → {@link
   *       LivePromotionStatus#VERIFY_ERROR}
   *   <li>no matching row → {@link LivePromotionStatus#ABSENT}
   *   <li>{@code occurred_at < notStaleSince} → {@link LivePromotionStatus#STALE}
   *   <li>otherwise → {@link LivePromotionStatus#VALID}
   *   <li>any {@link RuntimeException} → {@link LivePromotionStatus#VERIFY_ERROR} (caught, NOT
   *       rethrown — so the workflow's verify activity does not retry-storm; the refusal is
   *       terminal for the signal)
   * </ul>
   */
  @Override
  public LivePromotionStatus checkLivePromotion(
      String tenantId, String strategyId, String brokerTarget, OffsetDateTime notStaleSince) {
    if (dsl == null) {
      log.warn(
          "checkLivePromotion has no DSLContext tenant={} strategy={} broker_target={};"
              + " failing CLOSED to VERIFY_ERROR",
          tenantId,
          strategyId,
          brokerTarget);
      return LivePromotionStatus.VERIFY_ERROR;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT occurred_at FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'LivePromotionApproved' "
                  + "AND subject ->> 'broker_target' = ? "
                  + "ORDER BY occurred_at DESC LIMIT 1",
              tenantId,
              strategyId,
              brokerTarget);
      if (r == null) {
        return LivePromotionStatus.ABSENT;
      }
      Timestamp ts = r.get(0, Timestamp.class);
      if (ts == null) {
        return LivePromotionStatus.ABSENT;
      }
      OffsetDateTime occurredAt = OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
      if (occurredAt.isBefore(notStaleSince)) {
        return LivePromotionStatus.STALE;
      }
      return LivePromotionStatus.VALID;
    } catch (RuntimeException e) {
      log.warn(
          "checkLivePromotion failed tenant={} strategy={} broker_target={};"
              + " failing CLOSED to VERIFY_ERROR",
          tenantId,
          strategyId,
          brokerTarget,
          e);
      return LivePromotionStatus.VERIFY_ERROR;
    }
  }
}
