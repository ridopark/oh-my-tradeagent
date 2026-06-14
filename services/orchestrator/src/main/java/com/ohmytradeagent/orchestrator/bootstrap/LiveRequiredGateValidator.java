package com.ohmytradeagent.orchestrator.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ohmytradeagent.contract.StrategyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase P2 live-safety: config-load invariant requiring every {@code -live} strategy to declare its
 * loss-control gates before any workflow starts. A real-money strategy that boots with no
 * daily-loss kill-switch threshold or no notional cap is an unbounded-risk misconfiguration —
 * reject it at startup (fail closed) rather than discovering it after a bad day.
 *
 * <p>Mirrors {@link CrossTenantBrokerTargetValidator}: same {@link TenantStrategyScanner#scan} feed
 * and per-strategy {@link StrategyConfig} read. Only {@code broker_target} values ending in {@code
 * -live} are checked; {@code -paper} (and absent) strategies are skipped.
 *
 * <p>Required (FAIL on violation):
 *
 * <ul>
 *   <li>{@code daily_loss_threshold} — non-null and &gt; 0 (KillSwitch auto-trip threshold).
 *   <li>{@code notional_cap_pct_of_capital_base} — non-null (portfolio notional cap).
 * </ul>
 *
 * <p>Advisory ({@code pre_trade_check_enabled} null/false → WARN, not fail): the operator may
 * deliberately run with the PDT/buying-power gate off.
 */
public final class LiveRequiredGateValidator {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private LiveRequiredGateValidator() {}

  /**
   * Throws {@link IllegalStateException} if any {@code -live} strategy is missing a required loss
   * gate. No-op when the tenants dir does not exist.
   */
  public static void validate(Path tenantsDir) {
    if (!Files.exists(tenantsDir)) {
      return;
    }
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      StrategyConfig cfg = readConfig(tenantsDir, ts.tenantId(), ts.strategyId());
      String label = ts.tenantId() + "/" + ts.strategyId();
      StrategyConfigInvariants.validateLiveRequiredGates(cfg, label);
    }
  }

  private static StrategyConfig readConfig(Path tenantsDir, String tenantId, String strategyId) {
    Path file = tenantsDir.resolve(tenantId).resolve("strategies").resolve(strategyId + ".yaml");
    try {
      return YAML.readValue(file.toFile(), StrategyConfig.class);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to read strategy config from " + file.toAbsolutePath(), e);
    }
  }
}
