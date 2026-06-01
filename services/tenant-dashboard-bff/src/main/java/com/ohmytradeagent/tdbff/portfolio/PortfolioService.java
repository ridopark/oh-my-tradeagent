package com.ohmytradeagent.tdbff.portfolio;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.platform.YamlStrategyRegistry;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Composes the read-only portfolio view, aggregated across all of a tenant's strategies. Every
 * figure is honestly labeled:
 *
 * <ul>
 *   <li>{@code open_positions} + {@code sum_open_notional} — cost basis (entry premium × qty ×
 *       100), NOT live mark (no market-data wired).
 *   <li>{@code realized_pnl_today} — FIFO match of today's fills (America/New_York); see {@code
 *       RealizedPnlCalculator} for the documented intraday-match limitation.
 *   <li>{@code account_equity} — broker-account net-liq, SHARED across all tenants on a {@code
 *       broker_target}; {@code account_equity_scope} states it is NOT the tenant's portfolio value.
 * </ul>
 *
 * <p>Unrealized / mark-to-market P&L is out of scope (no quote source) — omitted, not faked.
 */
@Service
public class PortfolioService {

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final PositionsReader positionsReader;
  private final RealizedPnlCalculator realizedPnl;
  private final AccountEquityClient accountEquity;
  private final TenantStrategyResolver strategyResolver;
  private final YamlStrategyRegistry strategyRegistry;

  public PortfolioService(
      PositionsReader positionsReader,
      RealizedPnlCalculator realizedPnl,
      AccountEquityClient accountEquity,
      TenantStrategyResolver strategyResolver,
      YamlStrategyRegistry strategyRegistry) {
    this.positionsReader = positionsReader;
    this.realizedPnl = realizedPnl;
    this.accountEquity = accountEquity;
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
  }

  public Map<String, Object> portfolio(String tenantId) {
    List<String> strategyIds = strategyResolver.strategyIdsForTenant(tenantId);
    LocalDate tradingDay = LocalDate.now(MARKET_TZ);

    // Open positions + cost-basis notional.
    List<OpenPosition> positions = positionsReader.openPositions(tenantId);
    BigDecimal sumOpenNotional = BigDecimal.ZERO;
    List<Map<String, Object>> positionItems = new ArrayList<>();
    for (OpenPosition p : positions) {
      sumOpenNotional = sumOpenNotional.add(p.openNotional());
      positionItems.add(positionItem(p));
    }

    // Realized P&L today, summed across strategies.
    BigDecimal realizedToday = BigDecimal.ZERO;
    for (String strategyId : strategyIds) {
      realizedToday =
          realizedToday.add(realizedPnl.computeRealizedPnl(tenantId, strategyId, tradingDay));
    }

    // Account equity per distinct broker_target (account-level, shared across tenants).
    Set<String> brokerTargets = new LinkedHashSet<>();
    for (String strategyId : strategyIds) {
      String bt = strategyRegistry.brokerTarget(tenantId, strategyId);
      if (bt != null) {
        brokerTargets.add(bt);
      }
    }
    List<Map<String, Object>> equityByBroker = new ArrayList<>();
    for (String brokerTarget : brokerTargets) {
      BigDecimal equity = accountEquity.equityFor(brokerTarget);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("broker_target", brokerTarget);
      m.put("equity", equity);
      equityByBroker.add(m);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenantId);
    body.put("trading_day", tradingDay.toString());
    body.put("open_positions", positionItems);
    body.put("open_positions_count", positionItems.size());
    body.put("sum_open_notional", sumOpenNotional);
    body.put("sum_open_notional_basis", "cost_basis_at_entry"); // NOT live mark
    body.put("realized_pnl_today", realizedToday);
    body.put("account_equity", equityByBroker);
    body.put(
        "account_equity_scope",
        "Brokerage account net-liquidation equity behind each broker_target, SHARED across all"
            + " tenants routing to that broker — NOT this tenant's portfolio value.");
    body.put("unrealized_pnl", null);
    body.put("unrealized_pnl_note", "Out of scope: no market-data / quote source wired.");
    return body;
  }

  private static Map<String, Object> positionItem(OpenPosition p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("strategy_id", p.strategyId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("remaining_qty", p.remainingQty());
    m.put("entry_premium", p.entryPremium());
    m.put("open_notional", p.openNotional());
    return m;
  }
}
