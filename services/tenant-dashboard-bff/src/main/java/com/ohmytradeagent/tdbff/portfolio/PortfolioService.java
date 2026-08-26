package com.ohmytradeagent.tdbff.portfolio;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li>{@code realized_pnl_today} — FIFO-matched realized P&L attributed to today's exits
 *       (America/New_York), including cross-day exits matched to their real prior-day basis; see
 *       {@code RealizedPnlCalculator} for the remaining pre-history limitation.
 *   <li>{@code account_equity} — net-liquidation equity of THIS tenant's OWN brokerage account (the
 *       tenant_id is forwarded so exec resolves the tenant's own broker credentials); account-level
 *       truth for that account, NOT this tenant's per-strategy portfolio value. {@code
 *       account_equity_scope} states this. Each row may also carry an informational {@code
 *       account_number} (the brokerage account identity for dashboard verification) — optional and
 *       dev-gated behind {@code bff.expose-broker-account-number} (default false, so it is never
 *       exposed in prod).
 * </ul>
 *
 * <p>The AGGREGATE {@code unrealized_pnl} body field stays null (no portfolio-wide quote source);
 * the per-position live marks above come from broker truth, not a computed mark, so the two are
 * distinct — per-row marks are present when the broker carries them, the aggregate is not faked.
 */
@Service
public class PortfolioService {

  // Equity-options contract multiplier (a premium is per-share; one contract covers 100 shares).
  private static final BigDecimal OPTIONS_MULTIPLIER = BigDecimal.valueOf(100);

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

    // Account equity per distinct broker_target, read as THIS tenant's OWN account (see
    // AccountEquityClient for the tenant → credential resolution). Each snapshot is an independent
    // AccountSnapshotWorkflow round-trip — fetch them concurrently too. We also remember one
    // representative strategy per broker_target to thread the (account-level) live-marks read's
    // forward-compat tenant/strategy hooks.
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
          brokerTarget,
          subreadPool.submit(() -> accountEquity.snapshotFor(tenantId, brokerTarget)));
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

    // Realized P&L per strategy — today AND since-inception from a SINGLE full-history fetch + FIFO
    // pass (RealizedPnlCalculator#computeRealized), so we scan the strategy's ENTIRE journal
    // history
    // ONCE per strategy per page load rather than twice. The day-scoped calc FIFO-matches cross-day
    // exits against their real prior-day basis (#276 §4 fix), so it too needs full history — the
    // single pass yields both the today figure and the all-time figure (which lets the Status page
    // reconcile to starting capital: start + realized_all_time + unrealized ≈ equity). The scan
    // grows unbounded over time, so dispatch it concurrently and await it under the sub-read budget
    // so a slow scan can't stack past the page budget.
    Map<String, Future<RealizedPnlCalculator.RealizedPnl>> realizedFutures = new LinkedHashMap<>();
    for (String strategyId : strategyIds) {
      realizedFutures.put(
          strategyId,
          subreadPool.submit(() -> realizedPnl.computeRealized(tenantId, strategyId, tradingDay)));
    }

    // Sum both figures under the sub-read budget with the same null-seeded degrade convention: a
    // stalled scan degrades to a NULL contribution (the await fallback is null, not ZERO), and if
    // ANY strategy degraded the whole figure is published as null so the tile renders "—"
    // (unavailable), NOT a misleading $0.00 or a silently under-counted total. Both aggregates are
    // seeded independently; because a single fetch now backs both, a degraded strategy nulls both.
    // This follows the null-seeding convention the rest of the page uses for degraded aggregates
    // (see account equity / unrealized marks).
    BigDecimal realizedToday = BigDecimal.ZERO;
    boolean realizedTodayDegraded = false;
    BigDecimal realizedAllTime = BigDecimal.ZERO;
    boolean realizedAllTimeDegraded = false;
    for (Map.Entry<String, Future<RealizedPnlCalculator.RealizedPnl>> entry :
        realizedFutures.entrySet()) {
      RealizedPnlCalculator.RealizedPnl contribution =
          await(
              entry.getValue(),
              null,
              "realized tenant=" + tenantId + " strategy=" + entry.getKey());
      if (contribution == null) {
        realizedTodayDegraded = true;
        realizedAllTimeDegraded = true;
      } else {
        realizedToday = realizedToday.add(contribution.today());
        realizedAllTime = realizedAllTime.add(contribution.allTime());
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
      // Live intraday "today" P&L = equity - last_equity (last_equity is the prior market close).
      // This is the GENUINE today figure the /live header shows, distinct from Alpaca
      // portfolio-history's last COMPLETED daily bar. Null (never fabricated) when either equity or
      // last_equity is unavailable — the dashboard then falls back to the last daily bar.
      // last_equity is surfaced alongside so the header can aggregate the percentage denominator
      // (sum today_pl / sum last_equity) across broker_targets.
      BigDecimal lastEquity = acct.lastEquity();
      m.put("last_equity", lastEquity);
      m.put(
          "today_pl",
          (acct.equity() != null && lastEquity != null)
              ? acct.equity().subtract(lastEquity)
              : null);
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
    body.put("realized_pnl_today", realizedTodayDegraded ? null : realizedToday);
    body.put("realized_pnl_all_time", realizedAllTimeDegraded ? null : realizedAllTime);
    body.put("account_equity", equityByBroker);
    body.put(
        "account_equity_scope",
        "Net-liquidation equity of this tenant's OWN brokerage account behind each broker_target"
            + " (broker-account-level truth for that account) — NOT this tenant's per-strategy"
            + " portfolio value.");
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
    // Live broker marks, joined by OCC. current_price is the shared per-unit mark. The two P&L
    // figures are PER-ROW (#832): the broker's numbers are ACCOUNT-POSITION-level, and attaching
    // them verbatim duplicated the whole contract's P&L onto every sibling workflow row sharing
    // the OCC (live: a 5-lot and a 21-lot both showed the combined 26-lot -1194).
    //   unrealized_pl        = (current − entry_premium) × remaining_qty × 100 — the row's OWN
    //                          basis; proration would mis-state both rows when sibling bases
    //                          differ.
    //   unrealized_intraday  = broker intraday × remaining_qty / broker_qty — proration by qty is
    //                          EXACT here: (current − lastday) is identical per contract
    //                          regardless of entry basis.
    // Uncomputable fields are omitted (missing entry/qty/mark) — the row still renders, matching
    // the no-matching-mark degrade.
    if (marks != null) {
      if (marks.currentPrice() != null) {
        m.put("current_price", marks.currentPrice());
      }
      if (marks.currentPrice() != null && p.entryPremium() != null) {
        m.put(
            "unrealized_pl",
            marks
                .currentPrice()
                .subtract(p.entryPremium())
                .multiply(BigDecimal.valueOf(p.remainingQty()))
                .multiply(OPTIONS_MULTIPLIER)
                .setScale(2, RoundingMode.HALF_UP));
      }
      if (marks.unrealizedIntradayPl() != null
          && marks.brokerQty() != null
          && marks.brokerQty() > 0) {
        m.put(
            "unrealized_intraday_pl",
            marks
                .unrealizedIntradayPl()
                .multiply(BigDecimal.valueOf(p.remainingQty()))
                .divide(BigDecimal.valueOf(marks.brokerQty()), 2, RoundingMode.HALF_UP));
      }
    }
    // Armed-trailing-stop state, straight off the position's own workflow. trailing_armed is
    // ALWAYS present (a row must state its protection status either way, and an absent key would
    // read the same as "not armed" only by accident); the two numerics follow the marks convention
    // and are omitted when there is no trail. trail_stop_price is PEAK-anchored — the price the
    // stop fires at now, NOT a mark-derived estimate the client could compute itself.
    m.put("trailing_armed", p.trailingArmed());
    if (p.trailGivebackPct() != null) {
      m.put("trail_giveback_pct", p.trailGivebackPct());
    }
    if (p.trailStopPrice() != null) {
      m.put("trail_stop_price", p.trailStopPrice());
    }
    return m;
  }
}
