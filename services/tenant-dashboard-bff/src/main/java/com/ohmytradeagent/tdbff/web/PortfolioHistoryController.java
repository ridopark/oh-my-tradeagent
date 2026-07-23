package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient;
import com.ohmytradeagent.tdbff.portfolio.PortfolioHistoryClient;
import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator;
import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator.RangeReturn;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryController.class);

  private final PortfolioHistoryClient client;
  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader strategyRegistry;
  private final PortfolioReturnCalculator returnCalculator;
  private final AccountEquityClient accountEquityClient;
  private final TenantContext ctx;

  // Per-read wall-clock bound for the concurrent history + live-equity sub-reads. Shares the same
  // property (and 12s-BFF-budget invariant) as PortfolioService's sub-reads so both degrade below
  // the
  // dashboard's call timeout rather than failing the page.
  private final long subreadTimeoutSeconds;

  // Daemon, cached pool: low-QPS dashboard reads; idle threads are reclaimed and never block JVM
  // exit. A timed-out sub-read is abandoned here and self-resolves when its own Temporal RPC
  // returns.
  private final ExecutorService subreadPool =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "bff-portfolio-history-subread");
            t.setDaemon(true);
            return t;
          });

  public PortfolioHistoryController(
      PortfolioHistoryClient client,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry,
      PortfolioReturnCalculator returnCalculator,
      AccountEquityClient accountEquityClient,
      TenantContext ctx,
      @Value("${bff.portfolio.subread-timeout-seconds:9}") long subreadTimeoutSeconds) {
    this.client = client;
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
    this.returnCalculator = returnCalculator;
    this.accountEquityClient = accountEquityClient;
    this.ctx = ctx;
    this.subreadTimeoutSeconds = subreadTimeoutSeconds;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> history(
      @RequestParam(name = "range", defaultValue = "1M") String range, HttpServletRequest req) {
    String tenant = ctx.tenantId(req);

    String brokerTarget = primaryBrokerTarget(tenant);

    // Fire the history read AND (for daily-bar ranges only) the live-equity read CONCURRENTLY: both
    // are independent blocking Temporal round-trips, so running them SEQUENTIALLY would sum their
    // latencies and could exceed the dashboard's 12s BFF call budget under a slow/degraded
    // orchestrator — failing the whole chart instead of degrading. Concurrent submit + bounded
    // await
    // degrades each read on its own (mirrors PortfolioService's sub-read pattern). No broker_target
    // →
    // no reads (empty chart, client never called).
    PortfolioHistoryResult history = null;
    BigDecimal liveEquity = null;
    Long evAsOf = null;
    if (brokerTarget != null) {
      final String bt = brokerTarget;
      Future<PortfolioHistoryResult> historyFuture =
          subreadPool.submit(() -> client.historyFor(tenant, bt, range));
      boolean dailyBars = client.usesDailyBars(range);
      Future<BigDecimal> equityFuture =
          dailyBars ? subreadPool.submit(() -> liveEquityFor(tenant, bt)) : null;
      history = await(historyFuture, null, "portfolio-history range=" + range);
      liveEquity =
          equityFuture == null ? null : await(equityFuture, null, "live-equity range=" + range);
      // For a daily-bar range the live-equity EV is valued at NOW, so the range calc must window
      // cash flows through NOW too — otherwise a deposit made TODAY (in the live EV, but dated
      // after
      // the series' last COMPLETED session) is counted as profit. Pair the EV's as-of time with the
      // EV. Intraday ranges (no live-equity read) pass null → window stays the series' last point.
      evAsOf = dailyBars ? Instant.now().getEpochSecond() : null;
    }

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
                history.getCashFlowsAvailable(),
                history.getBaseValueAsof(),
                liveEquity,
                evAsOf);
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

  /**
   * Live net-liquidation equity for the tenant's OWN account behind {@code brokerTarget}, read from
   * one {@code AccountSnapshotWorkflow} round-trip — the same value the {@code /live} header total
   * uses. Returns null on any degrade so the range return falls back to the chart's last point; a
   * failed/slow equity read must NEVER fail the chart. {@link AccountEquityClient#snapshotFor}
   * already degrades internally, but we guard defensively so no exception escapes here.
   */
  private BigDecimal liveEquityFor(String tenant, String brokerTarget) {
    if (brokerTarget == null) {
      return null;
    }
    try {
      return accountEquityClient.snapshotFor(tenant, brokerTarget).equity();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Awaits one concurrent sub-read under {@link #subreadTimeoutSeconds}. On timeout or failure it
   * logs and returns {@code fallback} so a single stalled dependency (a down/rolling orchestrator
   * worker, a slow broker fetch) degrades just its section of this read-only chart rather than
   * failing the whole response — which would otherwise stack past the dashboard's BFF call timeout
   * and 500 the page. Mirrors {@link com.ohmytradeagent.tdbff.portfolio.PortfolioService#await}.
   */
  private <T> T await(Future<T> future, T fallback, String label) {
    try {
      return future.get(subreadTimeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn(
          "portfolio-history sub-read timed out after {}s; degrading {}",
          subreadTimeoutSeconds,
          label);
      return fallback;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      log.warn("portfolio-history sub-read failed; degrading {} err={}", label, cause.getMessage());
      return fallback;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      log.warn("portfolio-history sub-read interrupted; degrading {}", label);
      return fallback;
    }
  }

  private static <T> List<T> nullToEmpty(List<T> values) {
    return values == null ? List.of() : values;
  }
}
