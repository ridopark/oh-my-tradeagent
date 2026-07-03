package com.ohmytradeagent.apigateway.web;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) P1 ACTIVE_LIVE_ACTIVATION guard. Replicates the
 * BFF {@code LivePromotionStateReader} live-promotion read against api-gateway's orchestrator DB
 * ({@code audit_log}): a {@code (tenant, strategy, broker_target)} has an ACTIVE live promotion iff
 * a {@code LivePromotionApproved} exists, is NOT past its 30-day TTL, and has NO strictly-later
 * {@code LivePromotionDeactivated}. Deletable requires the state ABSENT or fully
 * deactivated+expired — i.e. NOT active.
 *
 * <p>Read-only. Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class LiveActivationStateReader {

  // Mirrors the BFF LivePromotionStateReader.LIVE_PROMOTION_TTL (30d): an approval older than this
  // has lapsed and is no longer an active live activation.
  static final Duration LIVE_PROMOTION_TTL = Duration.ofDays(30);

  private final DSLContext dsl;

  public LiveActivationStateReader(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** True iff an ACTIVE (approved, un-expired, un-deactivated) live promotion exists right now. */
  public boolean isActive(String tenantId, String strategyId, String brokerTarget) {
    return isActive(tenantId, strategyId, brokerTarget, OffsetDateTime.now(ZoneOffset.UTC));
  }

  boolean isActive(String tenantId, String strategyId, String brokerTarget, OffsetDateTime now) {
    Record approved =
        dsl.fetchOne(
            "SELECT occurred_at FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'LivePromotionApproved' "
                + "AND subject ->> 'broker_target' = ? "
                + "ORDER BY occurred_at DESC LIMIT 1",
            tenantId,
            strategyId,
            brokerTarget);
    if (approved == null) {
      return false; // ABSENT — never promoted.
    }
    Timestamp ts = approved.get(0, Timestamp.class);
    if (ts == null) {
      return false;
    }
    OffsetDateTime approvedAt = OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    if (approvedAt.plus(LIVE_PROMOTION_TTL).isBefore(now)) {
      return false; // EXPIRED (lapsed past TTL).
    }
    Timestamp approvedTs = Timestamp.from(approvedAt.toInstant());
    Record deactivated =
        dsl.fetchOne(
            "SELECT 1 FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? AND kind = 'LivePromotionDeactivated' "
                + "AND subject ->> 'broker_target' = ? AND occurred_at > ? LIMIT 1",
            tenantId,
            strategyId,
            brokerTarget,
            approvedTs);
    // Deactivated strictly after approval → not active. Otherwise an un-expired, un-deactivated
    // approval is ACTIVE.
    return deactivated == null;
  }
}
