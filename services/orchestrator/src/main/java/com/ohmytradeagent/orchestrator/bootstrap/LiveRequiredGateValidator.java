package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
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
 * <p>P0c-b2: config is resolved through the active {@link StrategyRegistry} (yaml in yaml-mode, the
 * {@code strategy_config} DB store in db-mode) rather than read directly off disk, so the boot gate
 * validates exactly the config the live read path will serve. The scan still enumerates which
 * {@code (tenant, strategy)} pairs to check; a row that cannot load throws and the throw propagates
 * (boot fails closed) — never caught/skipped.
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

  private LiveRequiredGateValidator() {}

  /**
   * Throws {@link IllegalStateException} if any {@code -live} strategy is missing a required loss
   * gate. No-op when the tenants dir does not exist. Config for each scanned strategy is read via
   * {@code registry.get}; that read throws on a missing/invalid row, and the throw propagates (boot
   * fails closed).
   */
  public static void validate(Path tenantsDir, StrategyRegistry registry) {
    if (!Files.exists(tenantsDir)) {
      return;
    }
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      StrategyConfig cfg = registry.get(ts.tenantId(), ts.strategyId());
      String label = ts.tenantId() + "/" + ts.strategyId();
      StrategyConfigInvariants.validateLiveRequiredGates(cfg, label);
    }
  }
}
