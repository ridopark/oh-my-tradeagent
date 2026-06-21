package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase 6: tenant-wide PnL inputs for the account-level loss cap ({@code
 * AccountKillSwitchWorkflow}).
 *
 * <p>Both methods span EVERY strategy on the tenant (the cap basis is the tenant's whole running
 * book on the shared {@code broker_target}, per the #323 design) — never a single (tenant,
 * strategy) pair. The per-strategy {@link DailyPnlActivities#computeRealizedPnl} and the
 * per-strategy kill switch are untouched.
 */
@ActivityInterface
public interface AccountPnlActivities {

  /**
   * Tenant-wide realized PnL in dollars for {@code tradingDay} (America/New_York). Summed across
   * all of the tenant's strategies by delegating to the existing, tested per-strategy realized-PnL
   * FIFO composition — the strategy-scoped predicate is applied per strategy and the results added.
   * Positive = net gain, negative = net loss.
   */
  BigDecimal computeTenantRealizedPnl(String tenantId, LocalDate tradingDay);

  /**
   * The tenant's whole running options book (open positions + the #325 fail-closed counts). See
   * {@link AccountOpenBook}. The workflow values UNREALIZED loss from the returned positions using
   * live bids it fetches itself via {@code GetOptionQuoteActivity}.
   */
  AccountOpenBook accountOpenBook(String tenantId);
}
