package com.ohmytradeagent.tdbff.portfolio;

// Realized-PnL source is now the exec broker's order_intent_journal (BROKER TRUTH), selected per
// strategy via its broker_target -> BrokerDataSourceRouter (alpaca-paper -> execAlpacaPaperDsl,
// alpaca-live -> execAlpacaLiveDsl), mirroring OrdersReader. RATIONALE: the orchestrator audit_log
// can MISS a PartialExitFilled event when a PositionWorkflow fills a SELL at the broker but fails
// to journal the audit event (the F1 fill-race, fixed going forward by #503) — that under-reports
// realized P&L (a real -$102.66 loss showed as $0). The broker journal always has the fill, so it
// is authoritative and self-heals historical gaps.
//
// FIFO match: FILLED BUY rows establish a per-contract cost basis (avg_fill_price, filled_qty);
// FILLED SELL rows (avg_fill_price, filled_qty) FIFO-match against them, grouped by option_symbol;
// each matched contract realizes (exit_price − entry_basis) × 100. Open (un-exited) entries
// contribute nothing. Trading day is America/New_York.
//
// Phantom-proceeds limitation (issue #276 §4) applies ONLY to the DAY-SCOPED figure: a position
// entered on a prior day and exited today has no same-day BUY to match, so the day-scoped calc
// falls to the raw-proceeds branch (credits exit proceeds with no basis). The ALL-TIME figure is
// exact — every BUY is in scope, so each SELL FIFO-matches its real prior-day entry basis.
import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RealizedPnlCalculator {

  private static final Logger log = LoggerFactory.getLogger(RealizedPnlCalculator.class);
  static final BigDecimal MULTIPLIER = new BigDecimal("100");
  private static final String NO_SYMBOL_BUCKET = "";

  private final BrokerDataSourceRouter router;
  private final DbStrategyConfigReader strategyRegistry;

  public RealizedPnlCalculator(
      BrokerDataSourceRouter router, DbStrategyConfigReader strategyRegistry) {
    this.router = router;
    this.strategyRegistry = strategyRegistry;
  }

  /** Realized P&L for one (tenant, strategy) on {@code tradingDay} (America/New_York). */
  public BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay) {
    return realize(tenantId, strategyId, tradingDay);
  }

  /**
   * Since-inception (all-time) realized P&L for one (tenant, strategy). IDENTICAL FIFO logic to
   * {@link #computeRealizedPnl} but fetches BUY/SELL fills across ALL history (no per-day
   * predicate). This is strictly MORE correct than the day-scoped calc: it resolves the documented
   * #276 §4 cross-day "phantom gain" — an exit on a later day now FIFO-matches its real prior-day
   * entry cost basis instead of crediting raw proceeds — FOR exits whose entry leg is within
   * retained history. Two limitations remain: (1) an exit with no matching entry anywhere (entry
   * pre-dates journal retention, or its option_symbol bucket never matches) still credits raw
   * proceeds; (2) lots are pooled per option_symbol with no position-episode/expiry boundary, so if
   * the SAME option_symbol string is reused across separate closed-and-reopened episodes (or an OCC
   * is recycled across expiries) a later exit can FIFO-match an unrelated older entry basis.
   */
  public BigDecimal computeRealizedPnlAllTime(String tenantId, String strategyId) {
    return realize(tenantId, strategyId, null);
  }

  // Shared FIFO realization. A null {@code tradingDay} omits the per-day predicate (all-time).
  // Resolves the strategy's broker_target -> exec DSLContext; fail-soft to ZERO when unconfigured.
  private BigDecimal realize(String tenantId, String strategyId, LocalDate tradingDay) {
    String brokerTarget = strategyRegistry.brokerTarget(tenantId, strategyId);
    // brokerTarget reads fail-soft (null = unconfigured / missing config row). A null target must
    // NOT flow to router.dslFor — that would throw BrokerNotConfiguredException. Degrade this
    // strategy's contribution to ZERO instead. A null is anomalous (the orchestrator forbids a null
    // broker_target), so WARN rather than vanish it silently.
    if (brokerTarget == null) {
      log.warn(
          "realized P&L: no broker_target for {}/{} in strategy_config; contributing 0",
          tenantId,
          strategyId);
      return BigDecimal.ZERO;
    }
    DSLContext dsl = router.dslFor(brokerTarget);

    Map<String, Deque<Lot>> entriesBySymbol =
        fetchLots(dsl, tenantId, strategyId, tradingDay, "BUY");
    Map<String, Deque<Lot>> exitsBySymbol =
        fetchLots(dsl, tenantId, strategyId, tradingDay, "SELL");

    BigDecimal realized = BigDecimal.ZERO;
    for (Map.Entry<String, Deque<Lot>> e : exitsBySymbol.entrySet()) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(e.getKey(), new ArrayDeque<>());
      realized = realized.add(realizePerSymbol(entries, e.getValue()));
    }
    return realized.multiply(MULTIPLIER);
  }

  // Package-private for direct unit testing of the FIFO match (no Postgres needed).
  static BigDecimal realizePerSymbol(Deque<Lot> entries, Deque<Lot> exits) {
    BigDecimal realized = BigDecimal.ZERO;
    Lot entry = entries.poll();
    for (Lot exit : exits) {
      long remainingExitQty = exit.qty;
      while (remainingExitQty > 0 && entry != null) {
        long matched = Math.min(remainingExitQty, entry.qty);
        BigDecimal perContractPnl = exit.price.subtract(entry.price);
        realized = realized.add(perContractPnl.multiply(BigDecimal.valueOf(matched)));
        remainingExitQty -= matched;
        entry = entry.qty == matched ? entries.poll() : entry.consume(matched);
      }
      if (remainingExitQty > 0) {
        realized = realized.add(exit.price.multiply(BigDecimal.valueOf(remainingExitQty)));
      }
    }
    return realized;
  }

  record Lot(BigDecimal price, long qty) {
    Lot consume(long n) {
      return new Lot(price, qty - n);
    }
  }

  // Fetches FILLED journal rows for one side (BUY=entries, SELL=exits) bucketed by option_symbol.
  // The SQL is built ONLY from constants; side/state are constant literals controlled here and
  // tenant_id/strategy_id/tradingDay are bound parameters. tradingDay is bound only in the
  // day-scoped branch (so the param count differs between the two fetch calls — see #507).
  private Map<String, Deque<Lot>> fetchLots(
      DSLContext dsl, String tenantId, String strategyId, LocalDate tradingDay, String side) {
    if (!"BUY".equals(side) && !"SELL".equals(side)) {
      throw new IllegalArgumentException("unsupported side: " + side);
    }
    boolean dayScoped = tradingDay != null;
    String dayPredicate =
        dayScoped ? "AND (filled_at AT TIME ZONE 'America/New_York')::date = ? " : "";
    String sql =
        "SELECT avg_fill_price AS price, filled_qty AS qty, option_symbol "
            + "FROM order_intent_journal "
            + "WHERE tenant_id = ? AND strategy_id = ? AND state = 'FILLED' AND side = ? "
            + "AND filled_qty IS NOT NULL AND avg_fill_price IS NOT NULL "
            + dayPredicate
            + "ORDER BY filled_at ASC, recorded_at ASC";
    Result<Record> rows =
        dayScoped
            ? dsl.fetch(sql, tenantId, strategyId, side, tradingDay)
            : dsl.fetch(sql, tenantId, strategyId, side);
    Map<String, Deque<Lot>> lotsBySymbol = new LinkedHashMap<>();
    for (Record r : rows) {
      BigDecimal price = r.get("price", BigDecimal.class);
      BigDecimal qty = r.get("qty", BigDecimal.class);
      if (price == null || qty == null || qty.signum() <= 0) {
        continue;
      }
      long qtyLong;
      try {
        qtyLong = qty.longValueExact();
      } catch (ArithmeticException ex) {
        log.warn(
            "fetchLots: skipping {} row with fractional/oversized filled_qty={} (tenant={}"
                + " strategy={})",
            side,
            qty,
            tenantId,
            strategyId);
        continue;
      }
      String symbol = r.get("option_symbol", String.class);
      String bucket = symbol == null ? NO_SYMBOL_BUCKET : symbol;
      lotsBySymbol.computeIfAbsent(bucket, k -> new ArrayDeque<>()).add(new Lot(price, qtyLong));
    }
    return lotsBySymbol;
  }
}
