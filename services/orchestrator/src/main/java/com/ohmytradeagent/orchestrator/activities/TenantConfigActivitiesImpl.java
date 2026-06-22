package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import java.math.BigDecimal;

/**
 * Phase 6: {@link TenantConfigActivities} backed by the {@link TenantRegistry} (YAML). FAIL-CLOSED
 * is not required here the way the per-strategy live-floor is: a missing tenant.yaml resolves to a
 * null threshold (cap disabled / inert), which is the documented opt-out — there is no real-money
 * control being bypassed because the account cap is a NEW, additive safety layer that is off until
 * a tenant opts in.
 */
public class TenantConfigActivitiesImpl implements TenantConfigActivities {

  private final TenantRegistry registry;

  public TenantConfigActivitiesImpl(TenantRegistry registry) {
    this.registry = registry;
  }

  @Override
  public BigDecimal accountDailyLossThreshold(String tenantId) {
    return registry.get(tenantId).getAccountDailyLossThreshold();
  }
}
