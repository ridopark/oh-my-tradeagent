package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the extracted {@link StrategyConfigInvariants#validateLiveRequiredGates}.
 * Same conditions, exception type, and message text the {@code LiveRequiredGateValidator} enforced
 * inline — these guard against drift in the extraction.
 */
class StrategyConfigInvariantsTest {

  private static StrategyConfig live() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("acme");
    c.setStrategyId("strat-a");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    return c;
  }

  @Test
  void liveMissingDailyLossThrows() {
    StrategyConfig cfg = live();
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    // daily_loss_threshold left null

    assertThatThrownBy(
            () -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("daily_loss_threshold must be set and > 0");
  }

  @Test
  void liveDailyLossNonPositiveThrows() {
    StrategyConfig cfg = live();
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    cfg.setDailyLossThreshold(BigDecimal.ZERO);

    assertThatThrownBy(
            () -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("daily_loss_threshold must be set and > 0");
  }

  @Test
  void liveMissingNotionalCapThrows() {
    StrategyConfig cfg = live();
    cfg.setDailyLossThreshold(new BigDecimal("500"));
    // notional_cap left null

    assertThatThrownBy(
            () -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notional_cap_pct_of_capital_base must be set");
  }

  @Test
  void liveWithBothGatesPresentPasses() {
    StrategyConfig cfg = live();
    cfg.setDailyLossThreshold(new BigDecimal("500"));
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    cfg.setPreTradeCheckEnabled(true);

    assertThatCode(() -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .doesNotThrowAnyException();
  }

  @Test
  void paperMissingGatesIsSkipped() {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setTenantId("acme");
    cfg.setStrategyId("strat-paper");
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    // no gates set

    assertThatCode(
            () -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-paper"))
        .doesNotThrowAnyException();
  }

  @Test
  void livePreTradeDisabledIsAdvisoryNotFatal() {
    StrategyConfig cfg = live();
    cfg.setDailyLossThreshold(new BigDecimal("500"));
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    cfg.setPreTradeCheckEnabled(false);

    assertThatCode(() -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .as("pre_trade_check_enabled=false is advisory (warn only)")
        .doesNotThrowAnyException();

    // null is treated identically.
    cfg.setPreTradeCheckEnabled(null);
    assertThatCode(() -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .as("pre_trade_check_enabled=null is advisory (warn only)")
        .doesNotThrowAnyException();
  }
}
