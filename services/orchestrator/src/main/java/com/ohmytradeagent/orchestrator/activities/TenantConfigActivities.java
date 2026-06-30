package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;

/**
 * Phase 6: exposes the tenant-level account loss cap threshold to {@code
 * AccountKillSwitchWorkflow}. Mirrors {@link StrategyActivities#get} (config read happens in an
 * Activity, not workflow code, so the YAML read stays off the replay path). Re-read each heartbeat
 * so a tenant.yaml edit takes effect without a restart, exactly like the per-strategy {@code
 * daily_loss_threshold}.
 */
@ActivityInterface
public interface TenantConfigActivities {

  /**
   * Returns the tenant's {@code account_daily_loss_threshold} (absolute positive dollars), or
   * {@code null} when unset — null disables the absolute account cap (opt-in / inert).
   */
  BigDecimal accountDailyLossThreshold(String tenantId);

  /**
   * Returns the tenant's {@code account_daily_loss_pct} (a FRACTION of start-of-day account equity,
   * e.g. {@code 0.40} for 40%), or {@code null} when unset. When set ({@code > 0}) and start-of-day
   * equity is known, the account cap uses {@code pct x sodEquity} as the effective threshold in
   * preference to the absolute {@link #accountDailyLossThreshold}.
   */
  BigDecimal accountDailyLossPct(String tenantId);

  /**
   * Resolves the {@code broker_target} the tenant's account trades against (the shared
   * broker_target across the tenant's strategies, per the #323 one-tenant-per-broker_target
   * invariant). Used by the account kill switch to route the start-of-day account-equity snapshot
   * to {@code broker-<target>}. Returns {@code null} when no broker_target can be resolved (no
   * strategies / unreadable config) — the caller treats a null target as "equity unavailable" and
   * fails SAFE (defers the pct check) rather than crashing.
   */
  String tenantBrokerTarget(String tenantId);
}
