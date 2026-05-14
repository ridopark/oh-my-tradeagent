package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Realized cumulative daily PnL for (tenant, strategy) on a given trading day, in absolute dollars.
 *
 * <p>Phase 5 ships realized-only PnL composition: sum of EntryFilled (debit) + PartialExitFilled /
 * EodForceFlattened / ExpiryForceFlattened (credit) extracted from {@code audit_log.subject} JSON.
 * Mark-to-market on open positions lands in Phase 5b.
 */
@ActivityInterface
public interface DailyPnlActivities {

  /**
   * Returns the realized PnL in dollars for ({@code tenantId}, {@code strategyId}) for {@code
   * tradingDay} (America/New_York calendar date). Positive = net gain, negative = net loss. Returns
   * zero when no audit rows match.
   */
  BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay);
}
