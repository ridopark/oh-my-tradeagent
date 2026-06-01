package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.DailyTradeCounter;
import com.ohmytradeagent.orchestrator.activities.DrawdownVelocitySampler;
import com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot;
import com.ohmytradeagent.orchestrator.activities.RiskCollaboratorDefaults;
import com.ohmytradeagent.orchestrator.activities.SectorResolver;
import com.ohmytradeagent.orchestrator.activities.VisibilityPortfolioSnapshot;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
  public PortfolioSnapshot visibilityPortfolioSnapshot(WorkflowClient workflowClient) {
    return new VisibilityPortfolioSnapshot(workflowClient);
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

  @Bean
  @ConditionalOnMissingBean
  public PreTradeCheckActivity permissivePreTradeCheckActivity() {
    return RiskCollaboratorDefaults.permissivePreTradeCheck();
  }
}
