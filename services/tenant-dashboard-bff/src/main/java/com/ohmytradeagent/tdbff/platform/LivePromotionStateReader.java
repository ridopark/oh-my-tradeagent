package com.ohmytradeagent.tdbff.platform;

import com.ohmytradeagent.contract.identity.RiskRelevantConfigKeys;
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
 * AuditQueryActivitiesImpl#checkLivePromotion} in the SAME order (newest {@code
 * LivePromotionApproved}, then the staleness floor, then a {@code LivePromotionDeactivated}
 * strictly after it, then a risk-relevant {@code TenantConfigChanged} strictly after it) so the
 * admin tenant list renders the SAME disposition the gate computes — "Live · valid until
 * &lt;expires_at&gt;" / "at risk" / "stale" / "deactivated" / "config changed" — WITHOUT calling
 * the orchestrator. It deliberately depends only on the audit kind strings + subject keys (the
 * stable contract), not on orchestrator Java; the ordering and risk-key set are kept in sync with
 * the gate.
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

  // Mirrors AuditQueryActivitiesImpl.RISK_RELEVANT_CONFIG_KEYS — the strategy_config keys whose
  // post-approval change voids a live promotion (the gate returns CONFIG_CHANGED and refuses live
  // orders). Kept local so the BFF read does not depend on orchestrator Java; keep in sync.
  // Single source of truth in contract-java. This file previously kept its OWN copy, and on
  // 2026-08-15 it drifted from the orchestrator's: repeg_ceiling_pct was added to the trading gate
  // and not here, so live BTOs failed closed while this page still rendered "● live — valid until
  // <date>" and offered only Deactivate. The operator could neither see the halt nor clear it.
  // Whatever the gate treats as promotion-voiding, this reader MUST treat identically.
  private static final String RISK_KEYS_SQL_ARRAY_LITERAL =
      RiskRelevantConfigKeys.sqlArrayLiteral();

  private final DSLContext orchestratorDsl;

  public LivePromotionStateReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  public enum State {
    VALID,
    STALE,
    DEACTIVATED,
    CONFIG_CHANGED,
    ABSENT
  }

  /**
   * The live-promotion state for one (tenant, strategy, broker_target).
   *
   * @param state classification
   * @param expiresAt {@code approved_at + TTL}; present whenever an approval exists (VALID, STALE,
   *     DEACTIVATED, CONFIG_CHANGED), {@code null} for ABSENT
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
    Timestamp approvedTs = Timestamp.from(approvedAt.toInstant());

    // Mirror checkLivePromotion's ordering EXACTLY (the gate is the authority): STALE floor first,
    // then an explicit deactivation, then a risk-relevant config change — all post-approval
    // invalidations. (expiresAt < now) ⇔ the gate's (approvedAt < now - TTL).
    if (expiresAt.isBefore(now)) {
      return new LivePromotionState(State.STALE, expiresAt, false);
    }

    // A LivePromotionDeactivated strictly AFTER the matched approval is an explicit operator
    // revocation. occurred_at > ? is strictly-after, so a fresh re-activation (newer approved_at,
    // selected above) is NOT voided.
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
            approvedTs);
    if (deact != null) {
      return new LivePromotionState(State.DEACTIVATED, expiresAt, false);
    }

    // A risk-relevant TenantConfigChanged strictly AFTER the matched approval voids it (the gate
    // refuses live orders). TenantConfigChanged is (tenant, strategy)-keyed — NO broker_target
    // filter, matching the authority. jsonb_exists_any(target, text[]) is the functional equivalent
    // of `?|` that avoids jOOQ's `?`-as-bind misparse.
    Record cfg =
        orchestratorDsl.fetchOne(
            "SELECT 1 FROM audit_log WHERE tenant_id = ? AND strategy_id = ? "
                + "AND kind = 'TenantConfigChanged' AND occurred_at > ? "
                + "AND jsonb_exists_any(subject -> 'changed_keys', "
                + RISK_KEYS_SQL_ARRAY_LITERAL
                + ") LIMIT 1",
            tenantId,
            strategyId,
            approvedTs);
    if (cfg != null) {
      return new LivePromotionState(State.CONFIG_CHANGED, expiresAt, false);
    }

    boolean atRisk = !expiresAt.isAfter(now.plus(AT_RISK_WINDOW));
    return new LivePromotionState(State.VALID, expiresAt, atRisk);
  }
}
