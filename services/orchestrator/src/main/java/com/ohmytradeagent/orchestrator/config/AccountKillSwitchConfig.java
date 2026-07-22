package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.DbTenantStrategies;
import com.ohmytradeagent.orchestrator.activities.ScannerTenantStrategies;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.TenantStrategies;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import io.temporal.client.WorkflowClient;
import java.nio.file.Path;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 6: beans for the account-level (tenant-wide) loss cap. All FOUR consumers below — {@link
 * TenantConfigActivities#tenantBrokerTarget} (SOD-equity route), {@link AccountPnlActivities}
 * (realized + open book), and {@link AccountKillSwitchCascadeActivities} (the flatten/halt action)
 * — share the SINGLE active {@link TenantStrategies} bean (injected by type), so a trip that
 * RESOLVES on one source can never CASCADE on another.
 *
 * <p><b>Enumeration source (PLAN-2026-07-22).</b> In live (db-source) mode the resolver is {@link
 * DbTenantStrategies} (DB {@code strategy_config}); otherwise it is {@link ScannerTenantStrategies}
 * (the {@code tenants/} ConfigMap tree) for dev/tests. The condition is deliberately keyed to the
 * SAME {@code strategy.config.source=db} that order-routing and recon already run under, rather
 * than a separate cap-only flag: an independent flag would be one more ConfigMap value a live
 * cluster could forget to set, silently reverting the cap to the tree scan (the exact 2026-07-21
 * prod-kipark silent-unprotect). db-mode-conditional needs no new flag.
 */
@Configuration
public class AccountKillSwitchConfig {

  /**
   * Live (db-source) resolver: enumerates the tenant's strategies from {@code strategy_config} so a
   * DB-onboarded tenant's cap arms without a per-tenant ConfigMap tree entry.
   */
  @Bean
  @ConditionalOnProperty(name = "strategy.config.source", havingValue = "db")
  public TenantStrategies dbTenantStrategies(DSLContext dsl) {
    return new DbTenantStrategies(dsl);
  }

  /** dev/tests (yaml/absent) resolver: unchanged {@code tenants/} tree scan. */
  @Bean
  @ConditionalOnProperty(
      name = "strategy.config.source",
      havingValue = "yaml",
      matchIfMissing = true)
  public TenantStrategies scannerTenantStrategies(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    return new ScannerTenantStrategies(Path.of(tenantsDir));
  }

  @Bean
  public TenantConfigActivities tenantConfigActivities(
      TenantRegistry tenantRegistry,
      TenantStrategies tenantStrategies,
      StrategyRegistry strategyRegistry) {
    return new TenantConfigActivitiesImpl(tenantRegistry, tenantStrategies, strategyRegistry);
  }

  @Bean
  public AccountPnlActivities accountPnlActivities(
      DailyPnlActivities dailyPnl,
      TenantStrategies tenantStrategies,
      WorkflowClient client,
      StrategyRegistry strategyRegistry) {
    return new AccountPnlActivitiesImpl(dailyPnl, tenantStrategies, client, strategyRegistry);
  }

  @Bean
  public AccountKillSwitchCascadeActivities accountKillSwitchCascadeActivities(
      WorkflowClient client, TenantStrategies tenantStrategies, Clock clock) {
    return new AccountKillSwitchCascadeActivitiesImpl(client, tenantStrategies, clock);
  }
}
