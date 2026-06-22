package com.ohmytradeagent.orchestrator.platform;

/**
 * Phase 6: resolves a tenant's {@link TenantConfig}. Mirrors {@link StrategyRegistry} but scoped to
 * the whole tenant (no strategy id). The only consumer is the account-level kill switch.
 */
public interface TenantRegistry {

  /**
   * Loads the tenant's config. A tenant directory with no {@code tenant.yaml} is treated as a
   * config-absent tenant (returns a TenantConfig with a null threshold => cap disabled) rather than
   * throwing, so a tenant that has not opted into the account cap is inert.
   */
  TenantConfig get(String tenantId);
}
