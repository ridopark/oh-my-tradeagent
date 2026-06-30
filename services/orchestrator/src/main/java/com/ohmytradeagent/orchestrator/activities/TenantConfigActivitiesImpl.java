package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 6: {@link TenantConfigActivities} backed by the {@link TenantRegistry} (YAML). FAIL-CLOSED
 * is not required here the way the per-strategy live-floor is: a missing tenant.yaml resolves to a
 * null threshold (cap disabled / inert), which is the documented opt-out — there is no real-money
 * control being bypassed because the account cap is a NEW, additive safety layer that is off until
 * a tenant opts in.
 *
 * <p><b>Broker-target resolution.</b> The account cap is tenant-scoped, but the start-of-day
 * account-equity snapshot must route to the tenant's {@code broker-<target>} queue. The
 * broker_target lives on the strategy config (the tenant's strategies share one broker_target per
 * the #323 one-tenant-per-broker_target invariant), so {@link #tenantBrokerTarget(String)} reads
 * the first resolvable strategy's {@code broker_target}. It returns {@code null} (not a throw) when
 * nothing resolves: the caller treats a null target as "SOD equity unavailable" and fails SAFE
 * (defers the pct check) rather than crashing the heartbeat.
 */
public class TenantConfigActivitiesImpl implements TenantConfigActivities {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigActivitiesImpl.class);

  private final TenantRegistry registry;
  private final TenantStrategies tenantStrategies;
  private final StrategyRegistry strategyRegistry;

  public TenantConfigActivitiesImpl(
      TenantRegistry registry,
      TenantStrategies tenantStrategies,
      StrategyRegistry strategyRegistry) {
    this.registry = registry;
    this.tenantStrategies = tenantStrategies;
    this.strategyRegistry = strategyRegistry;
  }

  @Override
  public BigDecimal accountDailyLossThreshold(String tenantId) {
    return registry.get(tenantId).getAccountDailyLossThreshold();
  }

  @Override
  public BigDecimal accountDailyLossPct(String tenantId) {
    return registry.get(tenantId).getAccountDailyLossPct();
  }

  @Override
  public String tenantBrokerTarget(String tenantId) {
    List<String> strategyIds;
    try {
      strategyIds = tenantStrategies.strategyIdsForTenant(tenantId);
    } catch (RuntimeException e) {
      // Fail SAFE: an unreadable tenants tree means we cannot resolve a route. Returning null lets
      // the kill switch DEFER the pct check (no trip on an unknown base) rather than crash.
      log.warn(
          "tenantBrokerTarget: could not enumerate strategies for tenant={} err={}",
          tenantId,
          e.getMessage());
      return null;
    }
    for (String sid : strategyIds) {
      if (sid == null || sid.isBlank()) {
        continue;
      }
      try {
        StrategyConfig cfg = strategyRegistry.get(tenantId, sid);
        StrategyConfig.BrokerTarget target = cfg == null ? null : cfg.getBrokerTarget();
        if (target != null && target.value() != null && !target.value().isBlank()) {
          return target.value();
        }
      } catch (RuntimeException e) {
        log.warn(
            "tenantBrokerTarget: strategy config read failed tenant={} strategy={} err={}",
            tenantId,
            sid,
            e.getMessage());
      }
    }
    return null;
  }
}
