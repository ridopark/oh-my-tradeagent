package com.ohmytradeagent.orchestrator.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ohmytradeagent.contract.StrategyConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(LiveRequiredGateValidator.class);

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
      StrategyConfig.BrokerTarget target = cfg.getBrokerTarget();
      String brokerTarget = target == null ? null : target.value();
      if (brokerTarget == null || !brokerTarget.endsWith("-live")) {
        continue;
      }

      String label = ts.tenantId() + "/" + ts.strategyId();

      BigDecimal dailyLoss = cfg.getDailyLossThreshold();
      if (dailyLoss == null || dailyLoss.signum() <= 0) {
        throw new IllegalStateException(
            "live strategy "
                + label
                + " (broker_target="
                + brokerTarget
                + ") is missing a required loss gate: daily_loss_threshold must be set and > 0"
                + " (got "
                + dailyLoss
                + "). A real-money strategy must declare a kill-switch loss threshold.");
      }

      if (cfg.getNotionalCapPctOfCapitalBase() == null) {
        throw new IllegalStateException(
            "live strategy "
                + label
                + " (broker_target="
                + brokerTarget
                + ") is missing a required loss gate: notional_cap_pct_of_capital_base must be set."
                + " A real-money strategy must declare a portfolio notional cap.");
      }

      Boolean preTrade = cfg.getPreTradeCheckEnabled();
      if (preTrade == null || !preTrade) {
        log.warn(
            "live strategy {} has pre_trade_check disabled — PDT/buying-power gate is OFF"
                + " (operator decision)",
            label);
      }
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
