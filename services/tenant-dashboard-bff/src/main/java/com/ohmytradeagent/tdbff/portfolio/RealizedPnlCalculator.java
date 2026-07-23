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
// Cross-day fix (#276 §4): the DAY-SCOPED figure no longer credits raw proceeds for a position
// entered on a prior day and exited today. Both the day-scoped and all-time calcs now fetch FULL
// history and FIFO-match every exit against its REAL entry basis; the day-scoped calc simply counts
// only the exits whose ET date equals the target trading day toward the total (while still
// consuming
// entry lots for prior-day exits so FIFO reaches the correct remaining basis). The realized
// loss/gain
// of a cross-day exit is thus attributed to its own exit day. Remaining limitation: an exit whose
// entry pre-dates journal retention (no matching entry lot anywhere) still falls to raw proceeds,
// counted only on its exit day.
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
   * Since-inception (all-time) realized P&L for one (tenant, strategy). Same FIFO logic as {@link
   * #computeRealizedPnl}, but sums EVERY exit regardless of day ({@code tradingDay == null}). Two
   * limitations remain: (1) an exit with no matching entry anywhere (entry pre-dates journal
   * retention, or its option_symbol bucket never matches) still credits raw proceeds; (2) lots are
   * pooled per option_symbol with no position-episode/expiry boundary, so if the SAME option_symbol
   * string is reused across separate closed-and-reopened episodes (or an OCC is recycled across
   * expiries) a later exit can FIFO-match an unrelated older entry basis.
   */
  public BigDecimal computeRealizedPnlAllTime(String tenantId, String strategyId) {
    return realize(tenantId, strategyId, null);
  }

  // Shared FIFO realization. ALWAYS fetches full BUY/SELL history; {@code tradingDay} scopes only
  // which exits COUNT toward the total (null = all-time). Prior-day exits still consume entry lots
  // so FIFO reaches the correct remaining basis for a target-day exit.
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

    Map<String, Deque<Lot>> entriesBySymbol = fetchLots(dsl, tenantId, strategyId, "BUY");
    Map<String, Deque<Lot>> exitsBySymbol = fetchLots(dsl, tenantId, strategyId, "SELL");

    BigDecimal realized = BigDecimal.ZERO;
    for (Map.Entry<String, Deque<Lot>> e : exitsBySymbol.entrySet()) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(e.getKey(), new ArrayDeque<>());
      realized = realized.add(realizePerSymbol(entries, e.getValue(), tradingDay));
    }
    return realized.multiply(MULTIPLIER);
  }

  // Package-private for direct unit testing of the FIFO match (no Postgres needed). Consumes ALL
  // exits chronologically so FIFO reaches each exit's true remaining basis, but adds an exit's
  // realized to the total ONLY when {@code targetDay == null} (all-time) or the exit's ET date
  // equals {@code targetDay}. A prior-day exit still advances the entry FIFO; it just does not
  // count
  // toward a non-null target day. This attributes a cross-day exit's realized (matched legs AND the
  // residual raw-proceeds fallback) to its own exit day instead of crediting phantom raw proceeds.
  static BigDecimal realizePerSymbol(Deque<Lot> entries, Deque<Lot> exits, LocalDate targetDay) {
    BigDecimal realized = BigDecimal.ZERO;
    Lot entry = entries.poll();
    for (Lot exit : exits) {
      boolean count = targetDay == null || targetDay.equals(exit.day);
      long remainingExitQty = exit.qty;
      while (remainingExitQty > 0 && entry != null) {
        long matched = Math.min(remainingExitQty, entry.qty);
        if (count) {
          BigDecimal perContractPnl = exit.price.subtract(entry.price);
          realized = realized.add(perContractPnl.multiply(BigDecimal.valueOf(matched)));
        }
        remainingExitQty -= matched;
        entry = entry.qty == matched ? entries.poll() : entry.consume(matched);
      }
      if (remainingExitQty > 0 && count) {
        realized = realized.add(exit.price.multiply(BigDecimal.valueOf(remainingExitQty)));
      }
    }
    return realized;
  }

  // {@code day} is the fill's ET date; populated for exits (drives day-scoping), null/unused for
  // entries.
  record Lot(BigDecimal price, long qty, LocalDate day) {
    Lot consume(long n) {
      return new Lot(price, qty - n, day);
    }
  }

  // Fetches ALL FILLED journal rows for one side (BUY=entries, SELL=exits) bucketed by
  // option_symbol, carrying each row's ET date so the caller can day-scope in-memory. Full history
  // is always fetched (no per-day predicate) so a day-scoped exit FIFO-matches its real prior-day
  // entry basis. The SQL is built ONLY from constants; side/state are constant literals controlled
  // here and tenant_id/strategy_id are bound parameters (param count no longer varies by
  // day-scope).
  private Map<String, Deque<Lot>> fetchLots(
      DSLContext dsl, String tenantId, String strategyId, String side) {
    if (!"BUY".equals(side) && !"SELL".equals(side)) {
      throw new IllegalArgumentException("unsupported side: " + side);
    }
    String sql =
        "SELECT avg_fill_price AS price, filled_qty AS qty, option_symbol, "
            + "(filled_at AT TIME ZONE 'America/New_York')::date AS et_date "
            + "FROM order_intent_journal "
            + "WHERE tenant_id = ? AND strategy_id = ? AND state = 'FILLED' AND side = ? "
            + "AND filled_qty IS NOT NULL AND avg_fill_price IS NOT NULL "
            + "ORDER BY filled_at ASC, recorded_at ASC";
    Result<Record> rows = dsl.fetch(sql, tenantId, strategyId, side);
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
      LocalDate etDate = r.get("et_date", LocalDate.class);
      lotsBySymbol
          .computeIfAbsent(bucket, k -> new ArrayDeque<>())
          .add(new Lot(price, qtyLong, etDate));
    }
    return lotsBySymbol;
  }
}
