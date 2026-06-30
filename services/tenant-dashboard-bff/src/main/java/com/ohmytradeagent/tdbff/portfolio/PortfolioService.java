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
 *       100), NOT live mark. Each open position MAY additionally carry live broker marks ({@code
 *       current_price}, {@code unrealized_pl} = total, {@code unrealized_intraday_pl} = today),
 *       joined by normalized-compact OCC from {@link BrokerPositionsClient}. These are
 *       broker-ACCOUNT-level truth (shared across tenants on a broker_target), fail-open (a
 *       degraded snapshot omits them, the row still renders), and never a risk-gate input.
 *   <li>{@code realized_pnl_today} — FIFO match of today's fills (America/New_York); see {@code
 *       RealizedPnlCalculator} for the documented intraday-match limitation.
 *   <li>{@code account_equity} — broker-account net-liq, SHARED across all tenants on a {@code
 *       broker_target}; {@code account_equity_scope} states it is NOT the tenant's portfolio value.
 *       Each row may also carry an informational {@code account_number} (the brokerage account
 *       identity for dashboard verification) — optional and dev-gated behind {@code
 *       bff.expose-broker-account-number} (default false, so it is never exposed in prod).
 * </ul>
 *
 * <p>The AGGREGATE {@code unrealized_pnl} body field stays null (no portfolio-wide quote source);
 * the per-position live marks above come from broker truth, not a computed mark, so the two are
 * distinct — per-row marks are present when the broker carries them, the aggregate is not faked.
 */
@Service
public class PortfolioService {

  private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final PositionsReader positionsReader;
  private final RealizedPnlCalculator realizedPnl;
  private final AccountEquityClient accountEquity;
  private final BrokerPositionsClient brokerPositions;
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
      BrokerPositionsClient brokerPositions,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry,
      @Value("${bff.expose-broker-account-number:false}") boolean exposeBrokerAccountNumber,
      @Value("${bff.portfolio.subread-timeout-seconds:9}") long subreadTimeoutSeconds) {
    this.positionsReader = positionsReader;
    this.realizedPnl = realizedPnl;
    this.accountEquity = accountEquity;
    this.brokerPositions = brokerPositions;
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
    // is an independent AccountSnapshotWorkflow round-trip — fetch them concurrently too. We also
    // remember one representative strategy per broker_target to thread the (account-level)
    // live-marks
    // read's forward-compat tenant/strategy hooks.
    Set<String> brokerTargets = new LinkedHashSet<>();
    Map<String, String> repStrategyByTarget = new LinkedHashMap<>();
    for (String strategyId : strategyIds) {
      String bt = strategyRegistry.brokerTarget(tenantId, strategyId);
      if (bt != null) {
        brokerTargets.add(bt);
        repStrategyByTarget.putIfAbsent(bt, strategyId);
      }
    }
    Map<String, Future<AccountEquityClient.BrokerAccount>> equityFutures = new LinkedHashMap<>();
    for (String brokerTarget : brokerTargets) {
      equityFutures.put(
          brokerTarget, subreadPool.submit(() -> accountEquity.snapshotFor(brokerTarget)));
    }

    // Live marks per distinct broker_target (account-level broker truth — current price + today's /
    // total unrealized P&L). Each is an independent PositionSnapshotWorkflow round-trip, fetched
    // concurrently and fail-open: a degraded snapshot yields no marks for that target, dropping the
    // mark columns rather than failing the page.
    Map<String, Future<Map<String, BrokerPositionsClient.PositionMarks>>> marksFutures =
        new LinkedHashMap<>();
    for (String brokerTarget : brokerTargets) {
      String repStrategy = repStrategyByTarget.get(brokerTarget);
      marksFutures.put(
          brokerTarget,
          subreadPool.submit(() -> brokerPositions.marksFor(brokerTarget, tenantId, repStrategy)));
    }

    // Since-inception realized P&L, per strategy. Unlike the day-scoped read below, the all-time
    // calc drops the date predicate and scans the strategy's ENTIRE audit_log history, so it grows
    // unbounded over time — dispatch it concurrently and await it under the same sub-read budget as
    // the other slow reads so a slow scan can't stack past the page budget. The all-time figure
    // lets the Status page reconcile to starting capital (start + realized_all_time + unrealized ≈
    // equity) and is strictly MORE correct than the daily calc (resolves the #276 §4 cross-day
    // phantom gain).
    Map<String, Future<BigDecimal>> allTimeFutures = new LinkedHashMap<>();
    for (String strategyId : strategyIds) {
      allTimeFutures.put(
          strategyId,
          subreadPool.submit(() -> realizedPnl.computeRealizedPnlAllTime(tenantId, strategyId)));
    }

    // Realized P&L today, summed across strategies. The day-scoped read is date-bounded (small and
    // fast) and not gated on the orchestrator worker, so it stays inline and cheap.
    BigDecimal realizedToday = BigDecimal.ZERO;
    for (String strategyId : strategyIds) {
      realizedToday =
          realizedToday.add(realizedPnl.computeRealizedPnl(tenantId, strategyId, tradingDay));
    }

    // Sum the since-inception figures under the sub-read budget. A stalled scan degrades to a NULL
    // contribution (the await fallback is null, not ZERO) — and if ANY strategy degraded, the whole
    // figure is published as null so the tile renders "—" (unavailable), NOT a misleading $0.00 or
    // a silently under-counted total. This follows the same null-seeding convention the rest of the
    // page uses for degraded aggregates (see account equity / unrealized marks).
    BigDecimal realizedAllTime = BigDecimal.ZERO;
    boolean realizedAllTimeDegraded = false;
    for (Map.Entry<String, Future<BigDecimal>> entry : allTimeFutures.entrySet()) {
      BigDecimal contribution =
          await(
              entry.getValue(),
              null,
              "realized_all_time tenant=" + tenantId + " strategy=" + entry.getKey());
      if (contribution == null) {
        realizedAllTimeDegraded = true;
      } else {
        realizedAllTime = realizedAllTime.add(contribution);
      }
    }

    // Merge live marks across all broker_targets into one OCC-keyed map (account-level, so an OCC
    // is
    // unique to a contract regardless of which target holds it). Each fetch is fail-open under the
    // sub-read budget — a stalled target contributes no marks rather than failing the page.
    Map<String, BrokerPositionsClient.PositionMarks> marksByOcc = new LinkedHashMap<>();
    for (Map.Entry<String, Future<Map<String, BrokerPositionsClient.PositionMarks>>> entry :
        marksFutures.entrySet()) {
      Map<String, BrokerPositionsClient.PositionMarks> m =
          await(entry.getValue(), Map.of(), "marks broker_target=" + entry.getKey());
      marksByOcc.putAll(m);
    }

    // Join positions under the sub-read budget; a stalled query set degrades to "no positions
    // shown". Each open position picks up its live marks by normalized-compact OCC (the tracked
    // position carries the padded canonical OCC; broker marks are keyed compact — both normalize
    // the
    // same way). No matching mark -> the row stays clean (mark fields omitted).
    List<OpenPosition> positions =
        await(positionsFuture, List.of(), "positions tenant=" + tenantId);
    BigDecimal sumOpenNotional = BigDecimal.ZERO;
    List<Map<String, Object>> positionItems = new ArrayList<>();
    for (OpenPosition p : positions) {
      sumOpenNotional = sumOpenNotional.add(p.openNotional());
      BrokerPositionsClient.PositionMarks marks =
          marksByOcc.get(BrokerPositionsClient.compactOcc(p.contractSymbol()));
      positionItems.add(positionItem(p, marks));
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
    body.put("realized_pnl_all_time", realizedAllTimeDegraded ? null : realizedAllTime);
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

  private static Map<String, Object> positionItem(
      OpenPosition p, BrokerPositionsClient.PositionMarks marks) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("strategy_id", p.strategyId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("remaining_qty", p.remainingQty());
    m.put("entry_premium", p.entryPremium());
    m.put("open_notional", p.openNotional());
    // Live broker marks (account-level), added only when this position matched a broker mark by
    // OCC.
    // current_price = per-unit mark; unrealized_pl = TOTAL since entry; unrealized_intraday_pl =
    // TODAY'S. Omitted when there is no matching broker position (the row still renders).
    if (marks != null) {
      if (marks.currentPrice() != null) {
        m.put("current_price", marks.currentPrice());
      }
      if (marks.unrealizedPl() != null) {
        m.put("unrealized_pl", marks.unrealizedPl());
      }
      if (marks.unrealizedIntradayPl() != null) {
        m.put("unrealized_intraday_pl", marks.unrealizedIntradayPl());
      }
    }
    return m;
  }
}
