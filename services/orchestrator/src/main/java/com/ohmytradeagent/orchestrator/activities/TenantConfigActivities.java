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
   * {@code null} when unset — null disables the account cap entirely (opt-in / inert).
   */
  BigDecimal accountDailyLossThreshold(String tenantId);
}
