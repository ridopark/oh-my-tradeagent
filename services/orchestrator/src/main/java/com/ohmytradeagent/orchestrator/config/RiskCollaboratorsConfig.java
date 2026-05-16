package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.DailyTradeCounter;
import com.ohmytradeagent.orchestrator.activities.DrawdownVelocitySampler;
import com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot;
import com.ohmytradeagent.orchestrator.activities.SectorResolver;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Issue #6: default beans for the portfolio-level risk-gate collaborators. Each is a permissive
 * no-op so the gate stays disabled by default; production wiring replaces these per provider
 * (Temporal Visibility-backed PortfolioSnapshot, audit-log-backed DailyTradeCounter, broker-routed
 * PreTradeCheckActivity, etc.) and the {@code @ConditionalOnMissingBean} marker yields to those.
 *
 * <p>The gates are also strictly opt-in via {@link com.ohmytradeagent.contract.StrategyConfig}, so
 * even a deployment without these beans replaced never accidentally rejects entries.
 */
@Configuration
public class RiskCollaboratorsConfig {

  @Bean
  @ConditionalOnMissingBean
  public PortfolioSnapshot noOpPortfolioSnapshot() {
    return new PortfolioSnapshot() {
      @Override
      public List<OpenPosition> openPositions(String tenantId, String strategyId) {
        return List.of();
      }

      @Override
      public BigDecimal accountEquity(String tenantId, String strategyId) {
        return BigDecimal.ZERO;
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  public SectorResolver configBackedSectorResolver() {
    return SectorResolver.CONFIG_BACKED;
  }

  @Bean
  @ConditionalOnMissingBean
  public DailyTradeCounter noOpDailyTradeCounter() {
    return (tenant, strategy, day) -> 0L;
  }

  @Bean
  @ConditionalOnMissingBean
  public DrawdownVelocitySampler noOpDrawdownVelocitySampler() {
    return (tenant, strategy) -> BigDecimal.ZERO;
  }

  @Bean
  @ConditionalOnMissingBean
  public PreTradeCheckActivity permissivePreTradeCheckActivity() {
    return req -> {
      PreTradeCheckResult r = new PreTradeCheckResult();
      r.setSchemaVersion(1L);
      r.setAllowed(true);
      // Permissive sentinel: a real broker adapter overrides this. The risk gate is also
      // opt-in via StrategyConfig.pre_trade_check_enabled, so an unconfigured deployment never
      // hits this code path unless the operator explicitly enabled the gate.
      r.setBuyingPower(new BigDecimal("1000000000"));
      r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
      r.setMarginSufficient(true);
      return r;
    };
  }
}
