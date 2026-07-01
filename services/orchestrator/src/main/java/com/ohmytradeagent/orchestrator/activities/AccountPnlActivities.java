package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

  /**
   * Phase 2 (kill-switch realized re-source): the tenant's strategies each paired with the {@code
   * broker_target} their journal lives on, so the account kill-switch WORKFLOW can route a
   * per-strategy realized read to that strategy's {@code broker-<target>} exec queue and sum them
   * itself (the broker-truth re-source; routing must live in workflow code). Supports mixed
   * broker_targets. FAIL-CLOSED discipline (mirrors {@link #accountOpenBook}): an empty resolved
   * strategy set THROWS rather than returning nothing (nothing summed would zero the realized loss
   * and could let a real drawdown slip under the cap). A strategy whose broker_target cannot be
   * resolved is returned with a null {@code brokerTarget} — the workflow fails closed on it (G2).
   */
  List<TenantStrategyBrokerTarget> tenantStrategyBrokerTargets(String tenantId);
}
