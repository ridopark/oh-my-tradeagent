package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.RiskRelevantConfigKeys;
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

  /**
   * P3-b: the {@code StrategyConfig} keys whose post-approval change voids a {@code
   * LivePromotionApproved} sign-off now live in {@link RiskRelevantConfigKeys} (contract-java),
   * shared with tenant-dashboard-bff's {@code LivePromotionStateReader}. See that class for the
   * membership rationale — including why {@code daily_loss_threshold} is deliberately absent, which
   * this file's own {@code AuditQueryLivePromotionIT} pins.
   */
  // Single source of truth in contract-java: this set and the tenant-dashboard-bff's copy DRIFTED
  // on 2026-08-15 (repeg_ceiling_pct added here and not there), which halted live trading behind an
  // admin page still showing "valid until". See RiskRelevantConfigKeys for the full account.
  private static final String RISK_KEYS_SQL_ARRAY_LITERAL =
      RiskRelevantConfigKeys.sqlArrayLiteral();

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
  public long countPriorPositionOrphanObserved(
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
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'PositionOrphanObserved' "
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
          "countPriorPositionOrphanObserved failed tenant={} strategy={} occ={} status={};"
              + " returning 0",
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
  public long countPriorByKind(
      String tenantId, String strategyId, String optionSymbol, String kind, OffsetDateTime since) {
    if (dsl == null) {
      return 0L;
    }
    try {
      Record r =
          dsl.fetchOne(
              "SELECT COUNT(*) FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = ? "
                  + "AND occurred_at >= ? "
                  + "AND subject ->> 'option_symbol' = ?",
              tenantId,
              strategyId,
              kind,
              Timestamp.from(since.toInstant()),
              optionSymbol);
      return r == null ? 0L : r.get(0, Long.class);
    } catch (RuntimeException e) {
      log.warn(
          "countPriorByKind failed tenant={} strategy={} occ={} kind={}; returning 0",
          tenantId,
          strategyId,
          optionSymbol,
          kind,
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
   *   <li>a risk-relevant {@code TenantConfigChanged} with {@code occurred_at} strictly after the
   *       matched approval → {@link LivePromotionStatus#CONFIG_CHANGED} (P3-b)
   *   <li>otherwise → {@link LivePromotionStatus#VALID}
   *   <li>any {@link RuntimeException} → {@link LivePromotionStatus#VERIFY_ERROR} (caught, NOT
   *       rethrown — so the workflow's verify activity does not retry-storm; the refusal is
   *       terminal for the signal)
   * </ul>
   *
   * <p>P3-b config-change invalidation: after confirming a fresh (not-stale) approval, this also
   * checks whether any {@code TenantConfigChanged} touching a {@link RiskRelevantConfigKeys#ALL}
   * key landed AFTER that approval — if so the risk envelope the approvers signed off on no longer
   * holds and the gate returns {@link LivePromotionStatus#CONFIG_CHANGED}. It inherits this
   * method's fail-CLOSED posture: the 2nd query runs inside the same try/catch, so any DB error
   * there → {@link LivePromotionStatus#VERIFY_ERROR}, never VALID. P3-b protects the
   * configmap-reload path (edit YAML → restart), which the P0c-a {@code StrategyConfigWriter} API
   * guard does not cover; it complements that guard. NOTE: {@code TenantConfigChangedEmitter} emits
   * no event on first-ever boot for a (tenant,strategy), so a risk edit folded into a first boot
   * has no audit row to detect — P3-b detects post-approval changes that emitted an event.
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
      OffsetDateTime approvedAt = OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
      if (approvedAt.isBefore(notStaleSince)) {
        return LivePromotionStatus.STALE;
      }
      // Phase F (operator-account-onboarding): a LivePromotionDeactivated row for the same
      // (tenant, strategy, broker_target) emitted strictly AFTER the matched approval is an
      // explicit operator revocation of the live promotion → fail CLOSED to DEACTIVATED. Checked
      // BEFORE the P3-b config-change probe (both are post-approval invalidations; an explicit
      // deactivation is the more specific disposition). occurred_at > ? is strictly-after, matching
      // the config-change probe so a deactivation followed by a fresh re-activation (newer
      // approved_at) is NOT voided — the newest LivePromotionApproved selected above wins.
      Record deact =
          dsl.fetchOne(
              "SELECT 1 FROM audit_log "
                  + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'LivePromotionDeactivated' "
                  + "AND subject ->> 'broker_target' = ? AND occurred_at > ? "
                  + "LIMIT 1",
              tenantId,
              strategyId,
              brokerTarget,
              Timestamp.from(approvedAt.toInstant()));
      if (deact != null) {
        return LivePromotionStatus.DEACTIVATED;
      }
      // P3-b: a risk-relevant TenantConfigChanged strictly AFTER the matched approval voids it.
      // Use the FUNCTION jsonb_exists_any(target, text[]) — NOT the `?|` operator — because jOOQ
      // plain SQL treats every `?` as a JDBC bind, so `?|` would misparse. jsonb_exists_any is the
      // exact functional equivalent and contains no `?`. occurred_at > ? is strictly-after.
      Record cfg =
          dsl.fetchOne(
              "SELECT 1 FROM audit_log WHERE tenant_id = ? AND strategy_id = ? "
                  + "AND kind = 'TenantConfigChanged' AND occurred_at > ? "
                  + "AND jsonb_exists_any(subject -> 'changed_keys', "
                  + RISK_KEYS_SQL_ARRAY_LITERAL
                  + ") "
                  + "LIMIT 1",
              tenantId,
              strategyId,
              Timestamp.from(approvedAt.toInstant()));
      if (cfg != null) {
        return LivePromotionStatus.CONFIG_CHANGED;
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
