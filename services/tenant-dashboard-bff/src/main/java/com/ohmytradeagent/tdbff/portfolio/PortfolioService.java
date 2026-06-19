package com.ohmytradeagent.tdbff.portfolio;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *       Each row may also carry an informational {@code account_number} (the brokerage account
 *       identity for dashboard verification) — optional and dev-gated behind {@code
 *       bff.expose-broker-account-number} (default false, so it is never exposed in prod).
 * </ul>
 *
 * <p>Unrealized / mark-to-market P&L is out of scope (no quote source) — omitted, not faked.
 */
@Service
public class PortfolioService {

  private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final PositionsReader positionsReader;
  private final RealizedPnlCalculator realizedPnl;
  private final AccountEquityClient accountEquity;
  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader strategyRegistry;
  // Dev-only gate: when true, account_equity rows also carry the informational brokerage
  // account_number for dashboard verification. Default false so it is NEVER exposed in prod.
  private final boolean exposeBrokerAccountNumber;
  // Per-section wall-clock bound for the concurrent Temporal-backed sub-reads (see await()). Must
  // stay below the dashboard's BFF call timeout (12s) so a stall degrades a section rather than
  // 500-ing the page; see application.yml bff.portfolio.subread-timeout-seconds for the invariant.
  private final long subreadTimeoutSeconds;
  // Daemon, cached pool: low-QPS dashboard reads; idle threads are reclaimed and never block JVM
  // exit. A timed-out sub-read is abandoned here and self-resolves when its own Temporal RPC
  // returns.
  private final ExecutorService subreadPool =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "bff-portfolio-subread");
            t.setDaemon(true);
            return t;
          });

  public PortfolioService(
      PositionsReader positionsReader,
      RealizedPnlCalculator realizedPnl,
      AccountEquityClient accountEquity,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry,
      @Value("${bff.expose-broker-account-number:false}") boolean exposeBrokerAccountNumber,
      @Value("${bff.portfolio.subread-timeout-seconds:9}") long subreadTimeoutSeconds) {
    this.positionsReader = positionsReader;
    this.realizedPnl = realizedPnl;
    this.accountEquity = accountEquity;
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
    this.exposeBrokerAccountNumber = exposeBrokerAccountNumber;
    this.subreadTimeoutSeconds = subreadTimeoutSeconds;
  }

  public Map<String, Object> portfolio(String tenantId) {
    List<String> strategyIds = strategyResolver.strategyIdsForTenant(tenantId);
    LocalDate tradingDay = LocalDate.now(MARKET_TZ);

    // Fire the two slow, orchestrator-worker-dependent sub-reads CONCURRENTLY so one stalled
    // dependency degrades only its own section instead of serially stacking past the page budget.
    Future<List<OpenPosition>> positionsFuture =
        subreadPool.submit(() -> positionsReader.openPositions(tenantId));

    // Account equity per distinct broker_target (account-level, shared across tenants). Each
    // snapshot
    // is an independent AccountSnapshotWorkflow round-trip — fetch them concurrently too.
    Set<String> brokerTargets = new LinkedHashSet<>();
    for (String strategyId : strategyIds) {
      String bt = strategyRegistry.brokerTarget(tenantId, strategyId);
      if (bt != null) {
        brokerTargets.add(bt);
      }
    }
    Map<String, Future<AccountEquityClient.BrokerAccount>> equityFutures = new LinkedHashMap<>();
    for (String brokerTarget : brokerTargets) {
      equityFutures.put(
          brokerTarget, subreadPool.submit(() -> accountEquity.snapshotFor(brokerTarget)));
    }

    // Realized P&L today, summed across strategies. Audit-backed DB read — not gated on the
    // orchestrator worker — so it stays inline and cheap.
    BigDecimal realizedToday = BigDecimal.ZERO;
    for (String strategyId : strategyIds) {
      realizedToday =
          realizedToday.add(realizedPnl.computeRealizedPnl(tenantId, strategyId, tradingDay));
    }

    // Join positions under the sub-read budget; a stalled query set degrades to "no positions
    // shown".
    List<OpenPosition> positions =
        await(positionsFuture, List.of(), "positions tenant=" + tenantId);
    BigDecimal sumOpenNotional = BigDecimal.ZERO;
    List<Map<String, Object>> positionItems = new ArrayList<>();
    for (OpenPosition p : positions) {
      sumOpenNotional = sumOpenNotional.add(p.openNotional());
      positionItems.add(positionItem(p));
    }

    // Join equity per broker under the same budget; a stalled snapshot degrades to null equity.
    List<Map<String, Object>> equityByBroker = new ArrayList<>();
    for (Map.Entry<String, Future<AccountEquityClient.BrokerAccount>> entry :
        equityFutures.entrySet()) {
      String brokerTarget = entry.getKey();
      AccountEquityClient.BrokerAccount acct =
          await(
              entry.getValue(),
              new AccountEquityClient.BrokerAccount(null, null),
              "equity broker_target=" + brokerTarget);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("broker_target", brokerTarget);
      m.put("equity", acct.equity());
      // Informational account identity, dev-gated. Never exposed in prod (flag defaults false) and
      // omitted when the broker adapter / degraded snapshot carries no account number.
      if (exposeBrokerAccountNumber && acct.accountNumber() != null) {
        m.put("account_number", acct.accountNumber());
      }
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

  /**
   * Awaits one concurrent sub-read under {@link #subreadTimeoutSeconds}. On timeout or failure it
   * logs and returns {@code fallback} so a single stalled dependency (a down/rolling orchestrator
   * worker, a slow broker account-fetch) degrades just its section of this read-only view rather
   * than failing the whole portfolio response — which would otherwise stack past the dashboard's
   * BFF call timeout and 500 the page.
   */
  private <T> T await(Future<T> future, T fallback, String label) {
    try {
      return future.get(subreadTimeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn(
          "portfolio sub-read timed out after {}s; degrading {}", subreadTimeoutSeconds, label);
      return fallback;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      log.warn("portfolio sub-read failed; degrading {} err={}", label, cause.getMessage());
      return fallback;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      log.warn("portfolio sub-read interrupted; degrading {}", label);
      return fallback;
    }
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
