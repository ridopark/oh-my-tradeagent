package com.ohmytradeagent.orchestrator.platform;

import java.util.List;

/**
 * Phase 6: resolves a tenant's {@link TenantConfig}. Mirrors {@link StrategyRegistry} but scoped to
 * the whole tenant (no strategy id). Consumers are the account-level kill switch and the boot
 * config gate.
 */
public interface TenantRegistry {

  /**
   * Loads the tenant's config. A tenant directory with no {@code tenant.yaml} is treated as a
   * config-absent tenant (returns a TenantConfig with a null threshold => cap disabled) rather than
   * throwing, so a tenant that has not opted into the account cap is inert.
   */
  TenantConfig get(String tenantId);

  /**
   * Enumerates every tenant the registry knows about. Mirrors {@link StrategyRegistry#list()}: the
   * DB-backed registry returns the {@code tenant_config} rows, the YAML registry the mounted {@code
   * tenants/} subdirectories.
   *
   * <p>Deliberately tenant-scoped rather than derived from {@link StrategyRegistry#list()}: a
   * tenant that declares a cap but has no strategies yet must still have its config parsed at boot,
   * or a bad {@code account_daily_loss_pct} would go unvalidated until its first strategy appears.
   * {@code TenantConfigBootstrapper} used to walk the filesystem directly for exactly this reason.
   */
  List<String> list();
}
