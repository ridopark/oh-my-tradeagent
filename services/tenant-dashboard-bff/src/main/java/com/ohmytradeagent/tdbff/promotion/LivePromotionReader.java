package com.ohmytradeagent.tdbff.promotion;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader.TenantStrategyBrokerTarget;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.LivePromotionState;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>The classification is delegated to {@link LivePromotionStateReader}</b>, which mirrors the
 * gate's ordering (TTL, then a later deactivation, then a later risk-relevant config change). A
 * second copy of that logic here once reported "active" for a promotion the gate was voiding — the
 * one failure this class exists to prevent — so this class only enumerates and maps.
 *
 * <p>A read failure yields {@code unknown}, NEVER {@code active}, following {@code
 * FloorBreachController}'s rule that a monitoring failure must never look like an all-clear.
 */
@Component
public class LivePromotionReader {

  private static final Logger log = LoggerFactory.getLogger(LivePromotionReader.class);

  /**
   * A live strategy's promotion state. {@code expiresAt} and {@code daysRemaining} are null for
   * {@code absent} and {@code unknown}.
   */
  public record PromotionStatus(
      String strategyId,
      String brokerTarget,
      String status,
      OffsetDateTime expiresAt,
      Long daysRemaining) {}

  private final LivePromotionStateReader stateReader;
  private final DbStrategyConfigReader configReader;

  public LivePromotionReader(
      LivePromotionStateReader stateReader, DbStrategyConfigReader configReader) {
    this.stateReader = stateReader;
    this.configReader = configReader;
  }

  /**
   * One row per ENABLED live strategy of {@code tenantId}. Paper strategies are omitted entirely —
   * the gate does not apply to them, so a banner about them would be noise. Returns an empty list
   * for a tenant with no live strategies.
   *
   * <p>Throws if the strategies themselves cannot be enumerated: an empty list would read as "no
   * live strategies", i.e. an all-clear, so the caller must surface the failure instead.
   */
  public List<PromotionStatus> statuses(String tenantId, OffsetDateTime now) {
    List<TenantStrategyBrokerTarget> live =
        configReader.listAll().stream()
            .filter(s -> tenantId.equals(s.tenantId()))
            .filter(TenantStrategyBrokerTarget::enabled)
            // Mirrors StrategyConfigInvariants.isLive: a target is live iff it ends with "-live".
            .filter(s -> s.brokerTarget() != null && s.brokerTarget().endsWith("-live"))
            .toList();

    List<PromotionStatus> out = new ArrayList<>(live.size());
    for (TenantStrategyBrokerTarget s : live) {
      out.add(status(tenantId, s, now));
    }
    return out;
  }

  private PromotionStatus status(
      String tenantId, TenantStrategyBrokerTarget s, OffsetDateTime now) {
    LivePromotionState st;
    try {
      st = stateReader.stateOf(tenantId, s.strategyId(), s.brokerTarget(), now);
    } catch (RuntimeException e) {
      // NOT "absent": collapsing the two would report an unreadable database as an un-activated
      // tenant.
      log.warn(
          "live-promotion state read failed tenant={} strategy={}: {}",
          tenantId,
          s.strategyId(),
          e.toString());
      return new PromotionStatus(s.strategyId(), s.brokerTarget(), "unknown", null, null);
    }
    String status =
        switch (st.state()) {
          case VALID -> st.atRisk() ? "expiring" : "active";
          case STALE -> "stale";
          case DEACTIVATED -> "deactivated";
          case CONFIG_CHANGED -> "config_changed";
          case ABSENT -> "absent";
        };
    Long daysRemaining =
        st.expiresAt() == null ? null : Duration.between(now, st.expiresAt()).toDays();
    return new PromotionStatus(
        s.strategyId(), s.brokerTarget(), status, st.expiresAt(), daysRemaining);
  }
}
