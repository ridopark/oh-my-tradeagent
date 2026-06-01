package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import java.math.BigDecimal;
import java.util.List;

/**
 * Issue #6: permissive defaults for the portfolio-level risk-gate collaborators. Used by both the
 * Spring {@code @Bean} fallback wiring and the {@link RiskActivitiesImpl} back-compat 3-arg
 * constructor so the two paths stay in sync.
 *
 * <p>Each default returns the values an Issue #6 gate would treat as "no signal" — empty open
 * positions, zero equity (causes the notional cap to fail closed when actually enabled), zero daily
 * trade count, zero drawdown velocity, and a permissive pre-trade check. The gates are also
 * strictly opt-in via {@link com.ohmytradeagent.contract.StrategyConfig}, so even an unwired
 * deployment never accidentally rejects entries.
 */
public final class RiskCollaboratorDefaults {

  /** Sentinel buying-power for the permissive pre-trade check (above any realistic notional). */
  private static final BigDecimal LARGE_BUYING_POWER = new BigDecimal("1000000000");

  private RiskCollaboratorDefaults() {}

  public static PortfolioSnapshot permissivePortfolioSnapshot() {
    return new PortfolioSnapshot() {
      @Override
      public List<OpenPosition> openPositions(String tenantId, String strategyId) {
        return List.of();
      }

      @Override
      public BigDecimal accountEquity(String brokerTarget) {
        return BigDecimal.ZERO;
      }
    };
  }

  public static DailyTradeCounter zeroDailyTradeCounter() {
    return (tenant, strategy, day) -> 0L;
  }

  public static DrawdownVelocitySampler zeroDrawdownSampler() {
    return (tenant, strategy) -> BigDecimal.ZERO;
  }

  public static PreTradeCheckActivity permissivePreTradeCheck() {
    return new PermissiveDefaultPreTradeCheckActivity();
  }

  /**
   * Named class (not lambda) so it can implement the {@link PermissiveDefaultPreTradeCheck} marker.
   */
  private static final class PermissiveDefaultPreTradeCheckActivity
      implements PreTradeCheckActivity, PermissiveDefaultPreTradeCheck {
    @Override
    public PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request) {
      PreTradeCheckResult r = new PreTradeCheckResult();
      r.setSchemaVersion(1L);
      r.setAllowed(true);
      r.setBuyingPower(LARGE_BUYING_POWER);
      r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
      r.setMarginSufficient(true);
      return r;
    }
  }
}
