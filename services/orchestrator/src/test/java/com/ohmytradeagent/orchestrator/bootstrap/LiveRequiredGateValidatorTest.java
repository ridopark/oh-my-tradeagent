package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantConfig;
import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry.StrategyNotFoundException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase P2 live-safety: every {@code -live} strategy must declare its notional cap AND its tenant
 * must have an armed account-level daily-loss cap. The repo tree has no {@code -live} strategy, so
 * these build a synthetic tenants dir (mirrors {@link CrossTenantBrokerTargetValidatorTest}).
 *
 * <p>Phase 3 (single-account-loss-rule, 2026-07-15): the account cap is now the sole daily-loss
 * breaker, threaded in per tenant via a {@link TenantRegistry}. The per-strategy {@code
 * daily_loss_threshold} became OPTIONAL; a {@code -live} tenant with no armed account cap fails
 * boot.
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

  private static StrategyRegistry yamlRegistry(Path tenantsDir) {
    return new YamlStrategyRegistry(tenantsDir.toString());
  }

  /** A {@link TenantRegistry} whose every tenant has the account cap armed (pct). */
  private static TenantRegistry armedTenantRegistry() {
    TenantRegistry r = mock(TenantRegistry.class);
    TenantConfig tc = new TenantConfig();
    tc.setAccountDailyLossPct(new BigDecimal("0.10"));
    when(r.get(anyString())).thenReturn(tc);
    return r;
  }

  /** A {@link TenantRegistry} whose tenants have NO armed account cap (both fields null). */
  private static TenantRegistry unarmedTenantRegistry() {
    TenantRegistry r = mock(TenantRegistry.class);
    when(r.get(anyString())).thenReturn(new TenantConfig());
    return r;
  }

  // ---- Phase 3: account cap mandatory; daily_loss optional ----

  /** A live strategy whose tenant has NO armed account cap fails boot (the new invariant). */
  @Test
  void liveStrategyWithNoArmedAccountCapThrows(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", "0.25", true);

    assertThatThrownBy(
            () ->
                LiveRequiredGateValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), unarmedTenantRegistry()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("account loss cap")
        .hasMessageContaining("acme/copytrade-v1");
  }

  /** A live strategy with NO per-strategy daily_loss but an armed account cap boots clean. */
  @Test
  void liveStrategyMissingDailyLossButAccountCapArmedPasses(@TempDir Path tenantsDir)
      throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", null, "0.25", true);

    assertThatCode(
            () ->
                LiveRequiredGateValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), armedTenantRegistry()))
        .doesNotThrowAnyException();
  }

  // ---- unchanged gates (account cap armed so we reach them) ----

  @Test
  void liveStrategyMissingNotionalCapThrows(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", null, true);

    assertThatThrownBy(
            () ->
                LiveRequiredGateValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), armedTenantRegistry()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notional_cap_pct_of_capital_base")
        .hasMessageContaining("acme/copytrade-v1");
  }

  @Test
  void liveStrategyWithBothGatesAndNullPreTradeCheckPasses(@TempDir Path tenantsDir)
      throws Exception {
    // pre_trade_check omitted (null) → WARN logged, NOT a failure.
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", "0.25", null);

    assertThatCode(
            () ->
                LiveRequiredGateValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), armedTenantRegistry()))
        .doesNotThrowAnyException();
  }

  @Test
  void paperStrategyMissingEverythingPasses(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", null, null, null);

    // Paper is skipped even with no account cap.
    assertThatCode(
            () ->
                LiveRequiredGateValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), unarmedTenantRegistry()))
        .doesNotThrowAnyException();
  }

  @Test
  void noOpWhenTenantsDirMissing(@TempDir Path parent) {
    Path missing = parent.resolve("nope");
    // Neither registry is consulted when the dir is missing.
    StrategyRegistry registry = mock(StrategyRegistry.class);
    TenantRegistry tenantRegistry = mock(TenantRegistry.class);
    assertThatCode(() -> LiveRequiredGateValidator.validate(missing, registry, tenantRegistry))
        .doesNotThrowAnyException();
  }

  // ---- db-mode (registry-driven) fail-closed behavior ----

  /** A scanned strategy whose registry row is missing fails boot closed (throw propagates). */
  @Test
  void dbModeFailsClosedOnMissingLiveRow(@TempDir Path tenantsDir) throws Exception {
    // The scan still walks the tenants tree; the registry is the config SOURCE.
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", "0.25", true);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("acme", "copytrade-v1"))
        .thenThrow(new StrategyNotFoundException("Strategy config not found in DB"));

    assertThatThrownBy(
            () -> LiveRequiredGateValidator.validate(tenantsDir, registry, armedTenantRegistry()))
        .isInstanceOf(StrategyNotFoundException.class);
  }

  /** A newer-than-build schema_version row fails boot closed (throw propagates, no skip). */
  @Test
  void dbModeFailsClosedOnNewerSchemaVersion(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", "0.25", true);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("acme", "copytrade-v1"))
        .thenThrow(new IllegalStateException("strategy_config schema_version 2 exceeds build"));

    assertThatThrownBy(
            () -> LiveRequiredGateValidator.validate(tenantsDir, registry, armedTenantRegistry()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schema_version");
  }

  /** A valid seeded row + armed account cap passes the live-gate invariant. */
  @Test
  void dbModePassesOnValidSeededRow(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "500", "0.25", true);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get(any(), any())).thenReturn(liveConfig("alpaca-live", new BigDecimal("500")));

    assertThatCode(
            () -> LiveRequiredGateValidator.validate(tenantsDir, registry, armedTenantRegistry()))
        .doesNotThrowAnyException();
  }

  private static StrategyConfig liveConfig(String brokerTarget, BigDecimal dailyLoss) {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.fromValue(brokerTarget));
    cfg.setDailyLossThreshold(dailyLoss);
    cfg.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    cfg.setPreTradeCheckEnabled(true);
    return cfg;
  }
}
