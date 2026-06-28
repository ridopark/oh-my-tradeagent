package com.ohmytradeagent.tdbff.platform;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Read-only, operator-scoped view of a (tenant, strategy, broker_target)'s live-promotion state
 * from the orchestrator-owned {@code audit_log}. Replicates the classification of {@code
 * AuditQueryActivitiesImpl#checkLivePromotion} (newest {@code LivePromotionApproved}, then a {@code
 * LivePromotionDeactivated} strictly after it, then the staleness floor) so the admin tenant list
 * can render "Live · valid until &lt;expires_at&gt;" / "at risk" / "deactivated" WITHOUT calling
 * the orchestrator. It deliberately depends only on the audit kind strings and subject keys — the
 * stable contract — not on orchestrator Java.
 *
 * <p>This reads NO secret material: only {@code occurred_at} from {@code audit_log}.
 *
 * <p>Only meaningful for a LIVE {@code broker_target} (one that hit the promotion gate); paper
 * targets never have these rows, so the caller skips this reader for paper.
 */
@Component
public class LivePromotionStateReader {

  // Mirrors CopytradeSignalWorkflowImpl.LIVE_PROMOTION_TTL (30d): an approval older than this is
  // STALE and the gate would refuse it. Kept as a local constant so the BFF read does not depend on
  // orchestrator Java; if the workflow's TTL changes, update both.
  static final Duration LIVE_PROMOTION_TTL = Duration.ofDays(30);

  // An approval whose expiry is within this window is flagged at-risk so the dashboard can warn the
  // operator to re-approve before the live promotion silently goes stale.
  static final Duration AT_RISK_WINDOW = Duration.ofDays(3);

  private static final String KIND_APPROVED = "LivePromotionApproved";
  private static final String KIND_DEACTIVATED = "LivePromotionDeactivated";

  private final DSLContext orchestratorDsl;

  public LivePromotionStateReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  public enum State {
    VALID,
    STALE,
    DEACTIVATED,
    ABSENT
  }

  /**
   * The live-promotion state for one (tenant, strategy, broker_target).
   *
   * @param state classification
   * @param expiresAt {@code approved_at + TTL}; present only when an approval exists (VALID, STALE,
   *     DEACTIVATED), {@code null} for ABSENT
   * @param atRisk true iff VALID and within {@link #AT_RISK_WINDOW} of {@code expiresAt}
   */
  public record LivePromotionState(State state, OffsetDateTime expiresAt, boolean atRisk) {

    static LivePromotionState absent() {
      return new LivePromotionState(State.ABSENT, null, false);
    }
  }

  /**
   * Classify the newest live promotion for the triple. {@code now} is injected so the caller (and
   * tests) control the staleness/at-risk clock.
   */
  public LivePromotionState stateOf(
      String tenantId, String strategyId, String brokerTarget, OffsetDateTime now) {
    Record approved =
        orchestratorDsl.fetchOne(
            "SELECT occurred_at FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? AND kind = '"
                + KIND_APPROVED
                + "' AND subject ->> 'broker_target' = ? "
                + "ORDER BY occurred_at DESC LIMIT 1",
            tenantId,
            strategyId,
            brokerTarget);
    if (approved == null) {
      return LivePromotionState.absent();
    }
    Timestamp ts = approved.get(0, Timestamp.class);
    if (ts == null) {
      return LivePromotionState.absent();
    }
    OffsetDateTime approvedAt = OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    OffsetDateTime expiresAt = approvedAt.plus(LIVE_PROMOTION_TTL);

    // A deactivation strictly AFTER the matched approval is an explicit operator revocation — wins
    // over the staleness check (matches checkLivePromotion's ordering: deactivation before stale).
    Record deact =
        orchestratorDsl.fetchOne(
            "SELECT 1 FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? AND kind = '"
                + KIND_DEACTIVATED
                + "' AND subject ->> 'broker_target' = ? AND occurred_at > ? "
                + "LIMIT 1",
            tenantId,
            strategyId,
            brokerTarget,
            Timestamp.from(approvedAt.toInstant()));
    if (deact != null) {
      return new LivePromotionState(State.DEACTIVATED, expiresAt, false);
    }

    if (expiresAt.isBefore(now)) {
      return new LivePromotionState(State.STALE, expiresAt, false);
    }

    boolean atRisk = !expiresAt.isAfter(now.plus(AT_RISK_WINDOW));
    return new LivePromotionState(State.VALID, expiresAt, atRisk);
  }
}
