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
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final BrokerDataSourceRouter router;
  private final DbStrategyConfigReader strategyRegistry;

  public RealizedPnlCalculator(
      BrokerDataSourceRouter router, DbStrategyConfigReader strategyRegistry) {
    this.router = router;
    this.strategyRegistry = strategyRegistry;
  }

  /** Today's realized P&L and since-inception realized P&L for one (tenant, strategy). */
  public record RealizedPnl(BigDecimal today, BigDecimal allTime) {}

  /**
   * Realized P&L for one (tenant, strategy) computed in a SINGLE full-history fetch + FIFO pass:
   * {@code today()} counts only exits whose ET date equals {@code tradingDay} (America/New_York);
   * {@code allTime()} counts EVERY exit. Consolidating both into one pass avoids two full-history
   * journal scans per strategy per page load.
   *
   * <p>Both figures share the same FIFO limitations: (1) an exit with no matching entry anywhere
   * (entry pre-dates journal retention, or its option_symbol bucket never matches) still credits
   * raw proceeds; (2) lots are pooled per option_symbol with no position-episode/expiry boundary,
   * so if the SAME option_symbol string is reused across separate closed-and-reopened episodes (or
   * an OCC is recycled across expiries) a later exit can FIFO-match an unrelated older entry basis.
   * Resolves the strategy's broker_target -> exec DSLContext; fail-soft to ZERO when unconfigured.
   */
  public RealizedPnl computeRealized(String tenantId, String strategyId, LocalDate tradingDay) {
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
      return new RealizedPnl(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    DSLContext dsl = router.dslFor(brokerTarget);

    Map<String, Deque<Lot>> entriesBySymbol = fetchLots(dsl, tenantId, strategyId, "BUY");
    Map<String, Deque<Lot>> exitsBySymbol = fetchLots(dsl, tenantId, strategyId, "SELL");
    return realizeBoth(entriesBySymbol, exitsBySymbol, tradingDay, LocalDate.now(MARKET_TZ));
  }

  /** Today's realized P&L for one (tenant, strategy). Thin delegate to {@link #computeRealized}. */
  public BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay) {
    return computeRealized(tenantId, strategyId, tradingDay).today();
  }

  /**
   * Since-inception (all-time) realized P&L for one (tenant, strategy). Thin delegate to {@link
   * #computeRealized}; the all-time figure is independent of the trading day, so a {@code null} day
   * is passed to scope out the today bucket.
   */
  public BigDecimal computeRealizedPnlAllTime(String tenantId, String strategyId) {
    return computeRealized(tenantId, strategyId, null).allTime();
  }

  // Single FIFO pass over the full BUY/SELL history that accumulates BOTH totals at once: {@code
  // today} sums exits whose ET date equals {@code tradingDay}; {@code allTime} sums every exit.
  // Package-private for direct unit testing without Postgres. The ×100 multiplier is applied here.
  static RealizedPnl realizeBoth(
      Map<String, Deque<Lot>> entriesBySymbol,
      Map<String, Deque<Lot>> exitsBySymbol,
      LocalDate tradingDay,
      LocalDate asOf) {
    Acc acc = new Acc();
    Set<String> symbols = new LinkedHashSet<>(exitsBySymbol.keySet());
    symbols.addAll(entriesBySymbol.keySet());
    for (String symbol : symbols) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(symbol, new ArrayDeque<>());
      accumulate(entries, exitsBySymbol.getOrDefault(symbol, new ArrayDeque<>()), tradingDay, acc);
      realizeWorthlessExpiry(symbol, entries, tradingDay, asOf, acc);
    }
    return new RealizedPnl(acc.today.multiply(MULTIPLIER), acc.allTime.multiply(MULTIPLIER));
  }

  // #931: a worthless expiry leaves no SELL row, so the lots still un-exited after the FIFO walk
  // would sit open forever. Once the contract's OCC expiry date is strictly BEFORE {@code asOf}
  // (the expiry session is over; on the expiry day itself the remainder can still be sold), realize
  // each remaining lot at 0 — a total loss of its basis — attributed to the expiry date. Same
  // "physically expired => worth zero" rule the account cap's open-book valuation uses
  // (AccountKillSwitchWorkflowImpl#hasPhysicallyExpired). Known overstatement: an ITM lot
  // auto-exercised into shares, or a lot closed at the broker with no journal row, is also booked
  // at 0. An undatable symbol (legacy NULL bucket, malformed OCC) never expires. Display-only:
  // the exec kill-switch FIFO (DailyPnlExecActivityImpl) deliberately does NOT do this — the cap
  // already charges an expired lot's full basis through its open-book valuation, so realizing it
  // there too would double-count the loss into a false trip.
  private static void realizeWorthlessExpiry(
      String symbol, Deque<Lot> remaining, LocalDate tradingDay, LocalDate asOf, Acc acc) {
    LocalDate expiry = occExpiry(symbol);
    if (expiry == null || !expiry.isBefore(asOf)) {
      return;
    }
    boolean countToday = expiry.equals(tradingDay);
    for (Lot lot : remaining) {
      BigDecimal loss = lot.price.negate().multiply(BigDecimal.valueOf(lot.qty));
      acc.allTime = acc.allTime.add(loss);
      if (countToday) {
        acc.today = acc.today.add(loss);
      }
    }
  }

  // Expiry date of an OCC symbol (padded or compact: root, yyMMdd, C|P, 8-digit strike), or null.
  static LocalDate occExpiry(String symbol) {
    String occ = symbol == null ? "" : symbol.replace(" ", "");
    if (!occ.matches("[A-Z0-9.]{1,6}\\d{6}[CP]\\d{8}")) {
      return null;
    }
    String yymmdd = occ.substring(occ.length() - 15, occ.length() - 9);
    try {
      return LocalDate.parse("20" + yymmdd, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  // Package-private for direct unit testing of the FIFO match (no Postgres needed). Consumes ALL
  // exits chronologically so FIFO reaches each exit's true remaining basis, but adds an exit's
  // realized to the total ONLY when {@code targetDay == null} (all-time) or the exit's ET date
  // equals {@code targetDay}. A prior-day exit still advances the entry FIFO; it just does not
  // count
  // toward a non-null target day. This attributes a cross-day exit's realized (matched legs AND the
  // residual raw-proceeds fallback) to its own exit day instead of crediting phantom raw proceeds.
  static BigDecimal realizePerSymbol(Deque<Lot> entries, Deque<Lot> exits, LocalDate targetDay) {
    Acc acc = new Acc();
    accumulate(entries, exits, targetDay, acc);
    // targetDay == null => all-time (every exit counts); else => only the target-day bucket.
    return targetDay == null ? acc.allTime : acc.today;
  }

  // Core per-symbol FIFO walk shared by every entry point. Consumes ALL exits chronologically so
  // FIFO reaches each exit's true remaining basis, accumulating BOTH buckets in a single pass:
  // every exit's realized (matched legs AND the residual raw-proceeds fallback) adds to {@code
  // allTime}; an exit adds to {@code today} only when {@code tradingDay != null} and the exit's ET
  // date equals it. A prior-day exit still advances the entry FIFO; it just does not count toward
  // {@code today}. Attributes a cross-day exit's realized to its own exit day, never phantom raw
  // proceeds.
  private static void accumulate(
      Deque<Lot> entries, Deque<Lot> exits, LocalDate tradingDay, Acc acc) {
    Lot entry = entries.poll();
    for (Lot exit : exits) {
      boolean countToday = tradingDay != null && tradingDay.equals(exit.day);
      long remainingExitQty = exit.qty;
      while (remainingExitQty > 0 && entry != null) {
        long matched = Math.min(remainingExitQty, entry.qty);
        BigDecimal perContractPnl = exit.price.subtract(entry.price);
        BigDecimal leg = perContractPnl.multiply(BigDecimal.valueOf(matched));
        acc.allTime = acc.allTime.add(leg);
        if (countToday) {
          acc.today = acc.today.add(leg);
        }
        remainingExitQty -= matched;
        entry = entry.qty == matched ? entries.poll() : entry.consume(matched);
      }
      if (remainingExitQty > 0) {
        BigDecimal residual = exit.price.multiply(BigDecimal.valueOf(remainingExitQty));
        acc.allTime = acc.allTime.add(residual);
        if (countToday) {
          acc.today = acc.today.add(residual);
        }
      }
    }
    // Put the partially-consumed current lot back so {@code entries} holds exactly the un-exited
    // remainder (#931 realizes it at a worthless expiry).
    if (entry != null) {
      entries.addFirst(entry);
    }
  }

  // Mutable two-bucket accumulator for the single FIFO walk (today + all-time), pre-×100.
  private static final class Acc {
    private BigDecimal today = BigDecimal.ZERO;
    private BigDecimal allTime = BigDecimal.ZERO;
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
            // #897: gated-condor rows (the 'MLEG' combo and every condor- order) have no
            // per-symbol FIFO basis; counting them would credit raw proceeds. IS DISTINCT FROM
            // keeps legacy NULL-symbol rows, which a plain <> would silently drop.
            + "AND intent_key NOT LIKE 'condor-%' AND option_symbol IS DISTINCT FROM 'MLEG' "
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
