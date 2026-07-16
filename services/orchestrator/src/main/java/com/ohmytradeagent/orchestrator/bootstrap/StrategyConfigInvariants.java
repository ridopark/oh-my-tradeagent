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
   * Per-strategy {@code -live} loss-control gates WITHOUT tenant-level account-cap context.
   * Non-{@code -live} (paper or absent) {@code broker_target} → no-op. Throws {@link
   * IllegalStateException} if a {@code -live} strategy is missing {@code daily_loss_threshold}
   * (must be non-null and &gt; 0) or {@code notional_cap_pct_of_capital_base} (must be non-null). A
   * null/false {@code pre_trade_check_enabled} logs a WARNING (advisory) and does not throw.
   *
   * <p>This 2-arg form is the CONSERVATIVE gate that requires {@code daily_loss_threshold} without
   * account-cap context. As of Phase 3b it is retained ONLY as the {@code DEFAULT_VERSION} legacy
   * branch of {@code LiveActivationWorkflowImpl.activateLive} (behind the {@code
   * live-activation-account-cap-aware-v1} version gate, so in-flight histories replay
   * byte-identically). ALL live callers — the BOOT path ({@link LiveRequiredGateValidator}), {@code
   * LiveActivationWorkflowImpl} (at {@code v>=1}, reading the cap via an Activity), and {@code
   * StrategyConfigWriter} (a plain component that reads the cap directly) — now read the tenant's
   * account cap and use {@link #validateLiveRequiredGates(StrategyConfig, BigDecimal, BigDecimal,
   * String)}, which treats {@code daily_loss_threshold} as optional when the account cap is armed
   * (Phase 3).
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

    requireNotionalCap(cfg, brokerTarget, label);
    warnIfPreTradeDisabled(cfg, label);
  }

  /**
   * Phase 3 (single-account-loss-rule, 2026-07-15) BOOT invariant: the tenant's account-level cap
   * is now the sole daily-loss breaker for a {@code -live} strategy. Non-{@code -live} → no-op. For
   * a {@code -live} strategy:
   *
   * <ul>
   *   <li>The tenant's account cap MUST be armed — {@code accountDailyLossPct > 0} OR {@code
   *       accountDailyLossThreshold > 0}. A {@code -live} strategy whose tenant has NO armed
   *       account cap throws {@link IllegalStateException} (the new mandatory invariant).
   *   <li>The per-strategy {@code daily_loss_threshold} is OPTIONAL (null/≤0 OK) — the armed
   *       account cap satisfies the live loss-breaker invariant.
   *   <li>{@code notional_cap_pct_of_capital_base} — still required (non-null).
   * </ul>
   *
   * <p>A null/false {@code pre_trade_check_enabled} logs a WARNING (advisory) and does not throw.
   *
   * @param cfg the strategy config to validate
   * @param accountDailyLossPct the tenant's {@code account_daily_loss_pct} (fraction) or null
   * @param accountDailyLossThreshold the tenant's absolute {@code account_daily_loss_threshold} or
   *     null
   * @param label the {@code "tenantId/strategyId"} string used in messages
   */
  public static void validateLiveRequiredGates(
      StrategyConfig cfg,
      BigDecimal accountDailyLossPct,
      BigDecimal accountDailyLossThreshold,
      String label) {
    if (!isLive(cfg)) {
      return;
    }
    String brokerTarget = cfg.getBrokerTarget().value();

    boolean accountCapArmed =
        (accountDailyLossPct != null && accountDailyLossPct.signum() > 0)
            || (accountDailyLossThreshold != null && accountDailyLossThreshold.signum() > 0);
    if (!accountCapArmed) {
      throw new IllegalStateException(
          "live tenant "
              + label
              + " (broker_target="
              + brokerTarget
              + ") missing account loss cap: account_daily_loss_pct or account_daily_loss_threshold"
              + " must be set > 0. The account-level cap is the sole daily-loss breaker for a"
              + " real-money strategy.");
    }

    requireNotionalCap(cfg, brokerTarget, label);
    warnIfPreTradeDisabled(cfg, label);
  }

  private static void requireNotionalCap(StrategyConfig cfg, String brokerTarget, String label) {
    if (cfg.getNotionalCapPctOfCapitalBase() == null) {
      throw new IllegalStateException(
          "live strategy "
              + label
              + " (broker_target="
              + brokerTarget
              + ") is missing a required loss gate: notional_cap_pct_of_capital_base must be set."
              + " A real-money strategy must declare a portfolio notional cap.");
    }
  }

  private static void warnIfPreTradeDisabled(StrategyConfig cfg, String label) {
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
