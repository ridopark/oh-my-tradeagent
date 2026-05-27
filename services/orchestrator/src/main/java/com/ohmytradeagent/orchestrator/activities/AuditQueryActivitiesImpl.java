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
}
