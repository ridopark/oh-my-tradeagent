package com.ohmytradeagent.tdbff.promotion;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader.TenantStrategyBrokerTarget;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Whether each of a tenant's LIVE strategies is actually cleared to place real-money orders.
 *
 * <p><b>Why this exists.</b> The orchestrator refuses a live BTO unless a non-stale {@code
 * LivePromotionApproved} audit row exists for (tenant, strategy, broker_target) — it emits {@code
 * LivePromotionMissing} and places nothing. That refusal was, until now, visible only as a
 * transient Discord page and an audit row. Between 2026-09-21 and 2026-09-28 the approval for ALL
 * THREE live tenants aged past its TTL and every copytrade entry was refused {@code reason=stale}
 * for seven days, while /live looked entirely normal. This reader is the level-triggered half: a
 * state the operator can SEE, rather than an event they had to catch.
 *
 * <p><b>The TTL is duplicated from the orchestrator and must stay in sync</b> — {@code
 * CopytradeSignalWorkflowImpl.LIVE_PROMOTION_TTL}. The BFF deliberately has no orchestrator compile
 * dependency (see {@code PositionStateView}), so this is a literal, exactly as {@code TradesReader}
 * mirrors the audit kind strings. If the orchestrator's TTL changes and this does not, the banner
 * reports "active" while the trading path refuses orders — the one failure this class exists to
 * prevent.
 *
 * <p><b>Four states plus unknown</b>, following {@code FloorBreachController}'s rule that a
 * monitoring failure must never look like an all-clear: a read error yields {@code unknown}, NEVER
 * {@code active}. {@code expiring} exists because the 7-day outage above was an EXPIRY, not an
 * absence — a banner that only fired once trading was already blocked would not have prevented it.
 */
@Component
public class LivePromotionReader {

  private static final Logger log = LoggerFactory.getLogger(LivePromotionReader.class);

  /** Mirrors {@code CopytradeSignalWorkflowImpl.LIVE_PROMOTION_TTL}. Keep in sync. */
  static final Duration TTL = Duration.ofDays(30);

  /** How long before expiry the banner starts warning. Covers a long weekend plus slack. */
  static final Duration WARN_WINDOW = Duration.ofDays(7);

  /**
   * Separator for the (strategy, broker_target) map key. Neither field can contain it: both are
   * config identifiers drawn from {@code [A-Za-z0-9_-]}.
   */
  private static final String KEY_SEP = "::";

  /**
   * A live strategy's promotion state. {@code approvedAt}, {@code expiresAt}, {@code daysRemaining}
   * and {@code operatorId} are null for {@code absent} and {@code unknown}.
   */
  public record PromotionStatus(
      String strategyId,
      String brokerTarget,
      String status,
      OffsetDateTime approvedAt,
      OffsetDateTime expiresAt,
      Long daysRemaining,
      String operatorId) {}

  private final DSLContext orchestratorDsl;
  private final DbStrategyConfigReader configReader;

  public LivePromotionReader(
      @Qualifier("orchestratorDsl") DSLContext orchestratorDsl,
      DbStrategyConfigReader configReader) {
    this.orchestratorDsl = orchestratorDsl;
    this.configReader = configReader;
  }

  /**
   * One row per ENABLED live strategy of {@code tenantId}. Paper strategies are omitted entirely —
   * the gate does not apply to them, so a banner about them would be noise. Returns an empty list
   * for a tenant with no live strategies, which the caller renders as nothing rather than as a
   * problem.
   */
  public List<PromotionStatus> statuses(String tenantId, OffsetDateTime now) {
    List<TenantStrategyBrokerTarget> live;
    try {
      live =
          configReader.listAll().stream()
              .filter(s -> tenantId.equals(s.tenantId()))
              .filter(TenantStrategyBrokerTarget::enabled)
              // Mirrors StrategyConfigInvariants.isLive: a target is live iff it ends with "-live".
              .filter(s -> s.brokerTarget() != null && s.brokerTarget().endsWith("-live"))
              .toList();
    } catch (RuntimeException e) {
      log.warn("live-promotion strategy enumeration failed tenant={}: {}", tenantId, e.toString());
      return List.of();
    }
    if (live.isEmpty()) {
      return List.of();
    }

    Map<String, Approval> latest = latestApprovals(tenantId);
    List<PromotionStatus> out = new ArrayList<>(live.size());
    for (TenantStrategyBrokerTarget s : live) {
      // A null map means the READ failed, which is NOT "nothing approved" — collapsing the two
      // would report an unreadable database as an un-activated tenant.
      if (latest == null) {
        out.add(
            new PromotionStatus(
                s.strategyId(), s.brokerTarget(), "unknown", null, null, null, null));
        continue;
      }
      Approval a = latest.get(key(s.strategyId(), s.brokerTarget()));
      if (a == null || a.approvedAt() == null) {
        out.add(
            new PromotionStatus(
                s.strategyId(), s.brokerTarget(), "absent", null, null, null, null));
        continue;
      }
      OffsetDateTime expiresAt = a.approvedAt().plus(TTL);
      Duration remaining = Duration.between(now, expiresAt);
      String status;
      if (!expiresAt.isAfter(now)) {
        status = "stale";
      } else if (remaining.compareTo(WARN_WINDOW) <= 0) {
        status = "expiring";
      } else {
        status = "active";
      }
      out.add(
          new PromotionStatus(
              s.strategyId(),
              s.brokerTarget(),
              status,
              a.approvedAt(),
              expiresAt,
              remaining.toDays(),
              a.operatorId()));
    }
    return out;
  }

  /**
   * Newest approval per (strategy, broker_target), or {@code null} when the read itself failed.
   *
   * <p>Raw SQL for {@code DISTINCT ON}, which the jOOQ DSL expresses far less legibly and which
   * gets the newest row per group in ONE pass rather than a {@code max()} plus a re-read to recover
   * its operator. Every value is bound, never concatenated.
   */
  private Map<String, Approval> latestApprovals(String tenantId) {
    try {
      Map<String, Approval> m = new HashMap<>();
      orchestratorDsl
          .fetch(
              "SELECT DISTINCT ON (strategy_id, subject->>'broker_target')"
                  + "       strategy_id,"
                  + "       subject->>'broker_target' AS broker_target,"
                  + "       occurred_at,"
                  + "       subject->>'operator_id'  AS operator_id"
                  + "  FROM audit_log"
                  + " WHERE tenant_id = ? AND kind = 'LivePromotionApproved'"
                  + " ORDER BY strategy_id, subject->>'broker_target', occurred_at DESC",
              tenantId)
          .forEach(
              r ->
                  m.put(
                      key(r.get(0, String.class), r.get(1, String.class)),
                      new Approval(r.get(2, OffsetDateTime.class), r.get(3, String.class))));
      return m;
    } catch (RuntimeException e) {
      log.warn("live-promotion approval read failed tenant={}: {}", tenantId, e.toString());
      return null;
    }
  }

  private static String key(String strategyId, String brokerTarget) {
    return strategyId + KEY_SEP + brokerTarget;
  }

  private record Approval(OffsetDateTime approvedAt, String operatorId) {}
}
