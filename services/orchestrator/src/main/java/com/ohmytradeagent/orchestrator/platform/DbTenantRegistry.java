package com.ohmytradeagent.orchestrator.platform;

import java.math.BigDecimal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * account-loss-cap-db epic (Phase 1): a {@link TenantRegistry} backed by the {@code tenant_config}
 * table (V8). Reads the two cap columns for {@code tenant_id} into a {@link TenantConfig}. Mirrors
 * {@link DbStrategyRegistry}.
 *
 * <p><b>NOT the active bean by default.</b> Guarded behind {@code @ConditionalOnProperty(name =
 * "tenant.config.source", havingValue = "db")} (default OFF), so it is only constructed when an
 * operator explicitly opts in. With the property absent/unset, {@link YamlTenantRegistry} remains
 * the sole {@link TenantRegistry} bean and stays wired into {@code TenantConfigActivitiesImpl} —
 * the account kill switch reads the cap from YAML, unchanged. An operator flips {@code
 * tenant.config.source=db} to cut over.
 *
 * <p>Transparent swap: a missing row returns a default {@link TenantConfig} (null threshold => cap
 * inert), matching {@link YamlTenantRegistry#get(String)}'s missing-file semantics, so the reader
 * swap is invisible to {@code TenantConfigActivitiesImpl}. A stored {@code account_daily_loss_pct}
 * outside {@code (0,1]} throws via {@link TenantConfig#setAccountDailyLossPct} — fail-loud,
 * matching the YAML parse guard.
 */
@Component
@ConditionalOnProperty(name = "tenant.config.source", havingValue = "db")
public class DbTenantRegistry implements TenantRegistry {

  private final DSLContext dsl;

  @Autowired
  public DbTenantRegistry(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public TenantConfig get(String tenantId) {
    Record row =
        dsl.fetchOne(
            "SELECT account_daily_loss_threshold, account_daily_loss_pct "
                + "FROM tenant_config WHERE tenant_id = ?",
            tenantId);

    if (row == null) {
      // Missing row => cap disabled/inert, matching YamlTenantRegistry's missing-file semantics.
      return new TenantConfig();
    }

    TenantConfig cfg = new TenantConfig();
    cfg.setAccountDailyLossThreshold(row.get("account_daily_loss_threshold", BigDecimal.class));
    // Reuses TenantConfig.setAccountDailyLossPct's (0,1] range guard — a bad stored value throws.
    cfg.setAccountDailyLossPct(row.get("account_daily_loss_pct", BigDecimal.class));
    return cfg;
  }
}
