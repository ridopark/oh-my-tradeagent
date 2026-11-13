package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/notional-cap} — tenant-scoped notional-cap inputs and headroom per strategy,
 * computed byte-for-byte as the orchestrator's {@code RiskActivitiesImpl#checkNotionalCap} gate
 * computes them. Returns {@code reason} instead of a number whenever the gate would reject or an
 * input is missing; never substitutes a value (no net-liq for cash, no zero for a missing sum).
 *
 * <p>Positions are read directly from {@code PositionsReader.openPositions} — NOT through {@code
 * PortfolioCache}, whose degraded path publishes {@code sum_open_notional = 0}, which would
 * overstate headroom. One broker account snapshot per distinct {@code broker_target} per request.
 */
@RestController
@RequestMapping("/api/notional-cap")
public class NotionalCapController {

  /**
   * The formula string carried in every response so the AI client knows what the numbers mean and
   * can spot when inputs are absent.
   */
  static final String FORMULA =
      "capital_base = cash + sum_open_notional; "
          + "cap = capital_base × notional_cap_pct_of_capital_base; "
          + "headroom = cap − sum_open_notional";

  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader configReader;
  private final AccountEquityClient equityClient;
  private final PositionsReader positionsReader;
  private final TenantContext ctx;

  public NotionalCapController(
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader configReader,
      AccountEquityClient equityClient,
      PositionsReader positionsReader,
      TenantContext ctx) {
    this.strategyResolver = strategyResolver;
    this.configReader = configReader;
    this.equityClient = equityClient;
    this.positionsReader = positionsReader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> notionalCap(HttpServletRequest req) {
    String tenantId = ctx.tenantId(req);
    List<String> strategyIds = strategyResolver.strategyIdsForTenant(tenantId);

    // Read positions (fail-soft for the display: positions_unavailable, never 500).
    // PositionsReader.openPositions is called directly — PortfolioCache's degraded path would
    // publish sum_open_notional=0, overstating headroom; that is exactly the wrong failure mode.
    BigDecimal sumOpenNotional = null;
    Integer openPositionsCount = null;
    boolean positionsUnavailable = false;
    try {
      List<PositionsReader.OpenPosition> positions = positionsReader.openPositions(tenantId);
      BigDecimal sum = BigDecimal.ZERO;
      for (PositionsReader.OpenPosition p : positions) {
        sum = sum.add(p.openNotional());
      }
      sumOpenNotional = sum;
      openPositionsCount = positions.size();
    } catch (RuntimeException e) {
      positionsUnavailable = true;
    }

    // One snapshot per distinct broker_target — de-duped here so a tenant with N strategies on
    // the same account never issues N identical AccountSnapshotWorkflows for one request.
    Map<String, AccountEquityClient.BrokerAccount> snapshotCache = new LinkedHashMap<>();

    List<Map<String, Object>> strategies = new ArrayList<>();
    for (String strategyId : strategyIds) {
      strategies.add(
          buildStrategyRow(
              tenantId, strategyId, positionsUnavailable, sumOpenNotional, snapshotCache));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenantId);
    body.put("formula", FORMULA);
    body.put("sum_open_notional", sumOpenNotional);
    body.put("sum_open_notional_basis", "cost_basis_at_entry");
    body.put("open_positions_count", openPositionsCount);
    body.put("strategies", strategies);
    return ResponseEntity.ok(body);
  }

  private Map<String, Object> buildStrategyRow(
      String tenantId,
      String strategyId,
      boolean positionsUnavailable,
      BigDecimal sumOpenNotional,
      Map<String, AccountEquityClient.BrokerAccount> snapshotCache) {

    // positions_unavailable overrides all other reasons: the cap denominator is unknown, so we
    // cannot compute anything meaningful — but we still read and report cash so the operator can
    // see the account state independently of the Temporal read.
    if (positionsUnavailable) {
      String bt = configReader.brokerTarget(tenantId, strategyId);
      BigDecimal cash = cashForTarget(tenantId, bt, snapshotCache);
      return strategyRow(strategyId, bt, null, cash, null, null, null, "positions_unavailable");
    }

    String brokerTarget = configReader.brokerTarget(tenantId, strategyId);
    if (brokerTarget == null) {
      return strategyRow(strategyId, null, null, null, null, null, null, "broker_target_unknown");
    }

    BigDecimal cash = cashForTarget(tenantId, brokerTarget, snapshotCache);

    // Gate's exact fail-closed condition: null OR signum() <= 0.
    if (cash == null || cash.signum() <= 0) {
      return strategyRow(
          strategyId, brokerTarget, null, cash, null, null, null, "cash_unavailable");
    }

    // DB errors from the pct read propagate here and are mapped to config_unavailable.
    BigDecimal pct;
    try {
      pct = configReader.notionalCapPctOfCapitalBase(tenantId, strategyId);
    } catch (RuntimeException e) {
      BigDecimal capitalBase = cash.add(sumOpenNotional);
      return strategyRow(
          strategyId, brokerTarget, null, cash, capitalBase, null, null, "config_unavailable");
    }

    BigDecimal capitalBase = cash.add(sumOpenNotional);

    if (pct == null) {
      // Gate disabled (no pct configured); capital_base and cash are still informative.
      return strategyRow(
          strategyId, brokerTarget, null, cash, capitalBase, null, null, "cap_not_configured");
    }

    BigDecimal cap = capitalBase.multiply(pct);
    BigDecimal headroom = cap.subtract(sumOpenNotional);
    return strategyRow(strategyId, brokerTarget, pct, cash, capitalBase, cap, headroom, null);
  }

  /**
   * Returns the account's cash for {@code brokerTarget}, fetching via {@code
   * AccountEquityClient.snapshotFor} at most once per target per request (the cache absorbs
   * repeated calls when multiple strategies share one broker account). Returns {@code null} when
   * {@code brokerTarget} is null (caller maps to {@code broker_target_unknown} before calling).
   */
  private BigDecimal cashForTarget(
      String tenantId,
      String brokerTarget,
      Map<String, AccountEquityClient.BrokerAccount> snapshotCache) {
    if (brokerTarget == null) {
      return null;
    }
    AccountEquityClient.BrokerAccount acct =
        snapshotCache.computeIfAbsent(brokerTarget, bt -> equityClient.snapshotFor(tenantId, bt));
    return acct.cash();
  }

  private static Map<String, Object> strategyRow(
      String strategyId,
      String brokerTarget,
      BigDecimal pct,
      BigDecimal cash,
      BigDecimal capitalBase,
      BigDecimal cap,
      BigDecimal headroom,
      String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("strategy_id", strategyId);
    row.put("broker_target", brokerTarget);
    row.put("notional_cap_pct_of_capital_base", pct);
    row.put("cash", cash);
    row.put("capital_base", capitalBase);
    row.put("cap", cap);
    row.put("headroom", headroom);
    row.put("reason", reason);
    return row;
  }
}
