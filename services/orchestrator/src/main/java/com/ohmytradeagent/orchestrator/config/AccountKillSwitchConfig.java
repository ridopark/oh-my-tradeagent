package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.ScannerTenantStrategies;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.TenantStrategies;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlTenantRegistry;
import io.temporal.client.WorkflowClient;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 6: beans for the account-level (tenant-wide) loss cap. The {@link TenantStrategies}
 * resolver and {@link TenantRegistry} are scanner/YAML-backed off the same {@code
 * orchestrator.tenants-dir} the rest of the orchestrator reads (#323 resolver + {@code
 * YamlStrategyRegistry} precedent). The three activity impls are wired here (rather than
 * {@code @Component}) because they take collaborator constructor args that are not all Spring beans
 * by default.
 */
@Configuration
public class AccountKillSwitchConfig {

  @Bean
  public TenantStrategies tenantStrategies(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    return new ScannerTenantStrategies(Path.of(tenantsDir));
  }

  @Bean
  public TenantRegistry tenantRegistry(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    return new YamlTenantRegistry(Path.of(tenantsDir));
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
      DailyPnlActivities dailyPnl, TenantStrategies tenantStrategies, WorkflowClient client) {
    return new AccountPnlActivitiesImpl(dailyPnl, tenantStrategies, client);
  }

  @Bean
  public AccountKillSwitchCascadeActivities accountKillSwitchCascadeActivities(
      WorkflowClient client, TenantStrategies tenantStrategies, Clock clock) {
    return new AccountKillSwitchCascadeActivitiesImpl(client, tenantStrategies, clock);
  }
}
