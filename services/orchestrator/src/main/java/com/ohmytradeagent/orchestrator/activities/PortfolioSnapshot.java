package com.ohmytradeagent.orchestrator.activities;

import java.math.BigDecimal;
import java.util.List;

/**
 * Issue #6: portfolio-level read surface for risk-svc gates that need cross-position state.
 *
 * <p>Reads are scoped by {@code (tenant_id, strategy_id)} to match the multi-tenant isolation
 * boundary that the rest of the platform enforces. Implementations are expected to query Temporal
 * Advanced Visibility (the source of truth for running {@code PositionWorkflow} instances) plus an
 * equity / MTM source; production wiring lands per provider (e.g. Alpaca, Tradier) and
 * stub-friendly implementations supply zero/empty values so each gate stays opt-in via {@link
 * com.ohmytradeagent.contract.StrategyConfig}.
 */
public interface PortfolioSnapshot {

  /** A single open position summary used by the portfolio-level risk gates. */
  record OpenPosition(String underlyingTicker, BigDecimal openNotional) {}

  /** Open positions for {@code (tenant, strategy)} at the moment of the call. */
  List<OpenPosition> openPositions(String tenantId, String strategyId);

  /**
   * Account equity (dollars) the {@code notional_cap_pct_of_equity} gate compares against. Returns
   * zero when the figure is unavailable; the gate fails closed on a zero equity (cannot compute the
   * cap → reject).
   */
  BigDecimal accountEquity(String tenantId, String strategyId);
}
