package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase P2 live-safety: every {@code -live} strategy must declare its loss gates (daily-loss
 * threshold and notional cap). The repo tree has no {@code -live} strategy, so these build a
 * synthetic tenants dir (mirrors {@link CrossTenantBrokerTargetValidatorTest}).
 */
class LiveRequiredGateValidatorTest {

  /** Writes a strategy YAML; null gate values are simply omitted (so they deserialize as null). */
  private void writeStrategy(
      Path tenantsDir,
      String tenant,
      String strategy,
      String brokerTarget,
      String dailyLoss,
      String notionalCap,
      Boolean preTradeCheck)
      throws Exception {
    Path file = tenantsDir.resolve(tenant).resolve("strategies").resolve(strategy + ".yaml");
    Files.createDirectories(file.getParent());
    StringBuilder sb = new StringBuilder();
    sb.append("schema_version: 1\n")
        .append("tenant_id: ")
        .append(tenant)
        .append('\n')
        .append("strategy_id: ")
        .append(strategy)
        .append('\n')
        .append("broker_target: ")
        .append(brokerTarget)
        .append('\n')
        .append("author_whitelist:\n  - acme_trader\n")
        .append("max_signal_age_bto_secs: 30\n")
        .append("max_signal_age_stc_secs: 60\n")
        .append("max_positions: 5\n")
        .append("capital_weight: 0.2\n")
        .append("min_contracts: 1\n")
        .append("max_contracts: 5\n");
    if (dailyLoss != null) {
      sb.append("daily_loss_threshold: ").append(dailyLoss).append('\n');
    }
    if (notionalCap != null) {
      sb.append("notional_cap_pct_of_capital_base: ").append(notionalCap).append('\n');
    }
    if (preTradeCheck != null) {
      sb.append("pre_trade_check_enabled: ").append(preTradeCheck).append('\n');
    }
    Files.writeString(file, sb.toString());
  }

  @Test
  void liveStrategyMissingDailyLossThrows(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", null, "0.25", true);

    assertThatThrownBy(() -> LiveRequiredGateValidator.validate(tenantsDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("daily_loss_threshold")
        .hasMessageContaining("acme/copytrade-v1");
  }

  @Test
  void liveStrategyZeroDailyLossThrows(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "0", "0.25", true);

    assertThatThrownBy(() -> LiveRequiredGateValidator.validate(tenantsDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("daily_loss_threshold");
  }

  @Test
  void liveStrategyMissingNotionalCapThrows(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", null, true);

    assertThatThrownBy(() -> LiveRequiredGateValidator.validate(tenantsDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notional_cap_pct_of_capital_base")
        .hasMessageContaining("acme/copytrade-v1");
  }

  @Test
  void liveStrategyWithBothGatesAndNullPreTradeCheckPasses(@TempDir Path tenantsDir)
      throws Exception {
    // pre_trade_check omitted (null) → WARN logged, NOT a failure.
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", "0.25", null);

    assertThatCode(() -> LiveRequiredGateValidator.validate(tenantsDir)).doesNotThrowAnyException();
  }

  @Test
  void paperStrategyMissingEverythingPasses(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", null, null, null);

    assertThatCode(() -> LiveRequiredGateValidator.validate(tenantsDir)).doesNotThrowAnyException();
  }

  @Test
  void noOpWhenTenantsDirMissing(@TempDir Path parent) {
    Path missing = parent.resolve("nope");
    assertThatCode(() -> LiveRequiredGateValidator.validate(missing)).doesNotThrowAnyException();
  }
}
