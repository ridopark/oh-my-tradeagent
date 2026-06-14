package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Source-agnostic live-safety invariants for a single {@link StrategyConfig}. Extracted from {@link
 * LiveRequiredGateValidator} so the same per-strategy gate checks can run regardless of where the
 * config came from (YAML on disk in P0b, the {@code strategy_config} DB store in P0c). Pure
 * extraction — conditions, exception type, and message text are byte-for-byte the validator's.
 */
public final class StrategyConfigInvariants {

  private static final Logger log = LoggerFactory.getLogger(StrategyConfigInvariants.class);

  private StrategyConfigInvariants() {}

  /**
   * Enforces the {@code -live} loss-control gates for one strategy. Non-{@code -live} (paper or
   * absent) {@code broker_target} → no-op. Throws {@link IllegalStateException} if a {@code -live}
   * strategy is missing {@code daily_loss_threshold} (must be non-null and &gt; 0) or {@code
   * notional_cap_pct_of_capital_base} (must be non-null). A null/false {@code
   * pre_trade_check_enabled} logs a WARNING (advisory) and does not throw.
   *
   * @param cfg the strategy config to validate
   * @param label the {@code "tenantId/strategyId"} string used in messages
   */
  public static void validateLiveRequiredGates(StrategyConfig cfg, String label) {
    if (!isLive(cfg)) {
      return;
    }
    String brokerTarget = cfg.getBrokerTarget().value();

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

  /**
   * The single canonical "is this a live (real-money) strategy?" predicate: a non-null {@code
   * broker_target} whose value ends with {@code -live}. The kill-switch heartbeat floor and this
   * boot validator both gate on it — keep exactly one definition so a real-money strategy can never
   * be misclassified as paper (which would silently skip the loss-gate enforcement). Pure (no I/O,
   * no Temporal API), so it is safe to call from replay-sensitive workflow code.
   */
  public static boolean isLive(StrategyConfig cfg) {
    StrategyConfig.BrokerTarget target = cfg.getBrokerTarget();
    return target != null && target.value() != null && target.value().endsWith("-live");
  }
}
