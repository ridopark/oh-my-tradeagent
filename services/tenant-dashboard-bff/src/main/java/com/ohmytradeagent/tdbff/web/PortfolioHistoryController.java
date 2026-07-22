package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.PortfolioHistoryClient;
import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator;
import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator.RangeReturn;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/portfolio-history?range=1D|1W|1M|3M|YTD|1Y} — the brokerage account's
 * portfolio-history series for the dashboard {@code /live} equity chart. READ-ONLY: a GET of
 * account history places no orders.
 *
 * <p>Resolves the tenant's {@code broker_target} the same way {@link
 * com.ohmytradeagent.tdbff.portfolio.PortfolioService} does (aggregate strategies → distinct broker
 * targets); for a multi-target tenant it returns the PRIMARY (first) target, since the {@code
 * /live} total header is single-account (Robinhood) semantics. The {@code range} → Alpaca {@code
 * period}/{@code timeframe} resolution lives in {@link PortfolioHistoryClient} (keeping the
 * workflow a dumb pass-through). The tenant's {@code tenant_id} is forwarded so exec resolves the
 * tenant's OWN broker credentials; the {@code account_scope} label states the (per-tenant own
 * account) scope.
 */
@RestController
@RequestMapping("/api/portfolio-history")
public class PortfolioHistoryController {

  // Same wording as PortfolioService.account_equity_scope — the chart is this tenant's OWN account.
  static final String ACCOUNT_SCOPE =
      "Net-liquidation equity of this tenant's OWN brokerage account behind the broker_target"
          + " (broker-account-level truth for that account) — NOT this tenant's per-strategy"
          + " portfolio value.";

  private final PortfolioHistoryClient client;
  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader strategyRegistry;
  private final PortfolioReturnCalculator returnCalculator;
  private final TenantContext ctx;

  public PortfolioHistoryController(
      PortfolioHistoryClient client,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry,
      PortfolioReturnCalculator returnCalculator,
      TenantContext ctx) {
    this.client = client;
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
    this.returnCalculator = returnCalculator;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> history(
      @RequestParam(name = "range", defaultValue = "1M") String range, HttpServletRequest req) {
    String tenant = ctx.tenantId(req);

    String brokerTarget = primaryBrokerTarget(tenant);
    PortfolioHistoryResult history =
        brokerTarget == null ? null : client.historyFor(tenant, brokerTarget, range);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamps", history == null ? List.of() : nullToEmpty(history.getTimestamps()));
    body.put("equity", history == null ? List.of() : nullToEmpty(history.getEquity()));
    body.put("profit_loss", history == null ? List.of() : nullToEmpty(history.getProfitLoss()));
    body.put(
        "profit_loss_pct", history == null ? List.of() : nullToEmpty(history.getProfitLossPct()));
    body.put("base_value", history == null ? null : history.getBaseValue());
    body.put("base_value_asof", history == null ? null : history.getBaseValueAsof());
    body.put("timeframe", history == null ? null : history.getTimeframe());

    // Deposit-adjusted range return (additive; "Today" still reads profit_loss[last] above). A
    // degraded (null) history or unavailable cash flows yield null range figures → UI renders "—".
    RangeReturn rr =
        history == null
            ? new RangeReturn(null, null)
            : returnCalculator.compute(
                history.getEquity(),
                history.getBaseValue(),
                history.getTimestamps(),
                history.getCashFlowTimestamps(),
                history.getCashFlowAmounts(),
                history.getCashFlowsAvailable());
    body.put("range_pl", rr.rangePl());
    body.put("range_pl_pct", rr.rangePlPct());
    body.put(
        "cash_flows_available",
        history != null && Boolean.TRUE.equals(history.getCashFlowsAvailable()));

    body.put("account_scope", ACCOUNT_SCOPE);
    return ResponseEntity.ok(body);
  }

  /**
   * The tenant's PRIMARY broker_target: the first distinct, non-null target across the tenant's
   * strategies (mirrors PortfolioService's per-target aggregation, which iterates strategies in
   * ascending id order). Null when the tenant has no strategy carrying a broker_target — the chart
   * then degrades to empty rather than 500.
   */
  private String primaryBrokerTarget(String tenantId) {
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      String bt = strategyRegistry.brokerTarget(tenantId, strategyId);
      if (bt != null) {
        return bt;
      }
    }
    return null;
  }

  private static <T> List<T> nullToEmpty(List<T> values) {
    return values == null ? List.of() : values;
  }
}
