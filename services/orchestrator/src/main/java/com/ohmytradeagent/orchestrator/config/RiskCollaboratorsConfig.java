package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.DailyTradeCounter;
import com.ohmytradeagent.orchestrator.activities.DrawdownVelocitySampler;
import com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot;
import com.ohmytradeagent.orchestrator.activities.RiskCollaboratorDefaults;
import com.ohmytradeagent.orchestrator.activities.RoutablePreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.SectorResolver;
import com.ohmytradeagent.orchestrator.activities.TenantStrategies;
import com.ohmytradeagent.orchestrator.activities.VisibilityPortfolioSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Issue #6: default beans for the portfolio-level risk-gate collaborators. Each delegates to {@link
 * RiskCollaboratorDefaults} so the {@code @Bean} fallback and the {@link
 * com.ohmytradeagent.orchestrator.activities.RiskActivitiesImpl} 3-arg back-compat constructor
 * share the same no-op behavior. Production wiring replaces these per provider
 * (Temporal-Visibility-backed PortfolioSnapshot, audit-log-backed DailyTradeCounter, etc.) and the
 * {@code @ConditionalOnMissingBean} marker yields to those.
 */
@Configuration
public class RiskCollaboratorsConfig {

  /**
   * Issue #318: Temporal Advanced Visibility–backed PortfolioSnapshot — lists running
   * PositionWorkflows for the {@code (tenant, strategy)} scope so the {@code same_underlying_count}
   * and {@code notional_cap_pct_of_equity} gates observe the real open book. Overrides {@link
   * #noOpPortfolioSnapshot()} (its {@code @ConditionalOnMissingBean} yields to this primary bean).
   */
  @Bean
  public PortfolioSnapshot visibilityPortfolioSnapshot(
      WorkflowClient workflowClient,
      MeterRegistry meterRegistry,
      TenantStrategies tenantStrategies) {
    // #323: the cap basis is tenant-account-wide — the resolver enumerates the requesting tenant's
    // full strategy set so the snapshot runs the proven TenantStrategy='...' equality query once
    // per
    // strategy and unions all of the tenant's running PositionWorkflows on the shared
    // broker_target.
    //
    // Inject the SHARED TenantStrategies bean (DB-backed when strategy.config.source=db, scanner
    // otherwise; wired in AccountKillSwitchConfig alongside the account-cap consumers) rather than
    // hard-constructing a ScannerTenantStrategies. This closes the same ConfigMap-tree dependency
    // the account cap had (PR #604): a DB-onboarded tenant absent from the tenants tree would
    // otherwise resolve to an empty set here — and since the requesting strategy is always added,
    // that silently undercounts sum_open_notional to ONLY the requesting strategy for a MULTI-
    // strategy tenant, loosening notional_cap_pct_of_equity fail-OPEN. One config source now flips
    // every consumer together.
    return new VisibilityPortfolioSnapshot(workflowClient, meterRegistry, tenantStrategies);
  }

  @Bean
  @ConditionalOnMissingBean
  public PortfolioSnapshot noOpPortfolioSnapshot() {
    return RiskCollaboratorDefaults.permissivePortfolioSnapshot();
  }

  @Bean
  @ConditionalOnMissingBean
  public SectorResolver configBackedSectorResolver() {
    return SectorResolver.CONFIG_BACKED;
  }

  @Bean
  @ConditionalOnMissingBean
  public DailyTradeCounter noOpDailyTradeCounter() {
    return RiskCollaboratorDefaults.zeroDailyTradeCounter();
  }

  @Bean
  @ConditionalOnMissingBean
  public DrawdownVelocitySampler noOpDrawdownVelocitySampler() {
    return RiskCollaboratorDefaults.zeroDrawdownSampler();
  }

  /**
   * Operator opt-in routability marker for the pre-trade check. When {@code
   * orchestrator.pre-trade-check.routing-enabled=true} this non-permissive bean is created, so it
   * (not the {@code @ConditionalOnMissingBean} permissive default below) is injected into {@link
   * com.ohmytradeagent.orchestrator.activities.RiskActivitiesImpl}. That makes {@code
   * assertPreTradeCheckRoutable} pass and lets an enabled {@code pre_trade_check_enabled} strategy
   * dispatch the check to exec. It must be declared before the permissive default so its presence
   * backs the {@code @ConditionalOnMissingBean} default off. Default off (property absent/false)
   * keeps the guard fail-closed, so enabling the gate is a deliberate two-key action (DB flag AND
   * this orchestrator property).
   */
  @Bean
  @ConditionalOnProperty(
      name = "orchestrator.pre-trade-check.routing-enabled",
      havingValue = "true")
  public PreTradeCheckActivity routablePreTradeCheckActivity() {
    return new RoutablePreTradeCheckActivity();
  }

  @Bean
  @ConditionalOnMissingBean
  public PreTradeCheckActivity permissivePreTradeCheckActivity() {
    return RiskCollaboratorDefaults.permissivePreTradeCheck();
  }
}
