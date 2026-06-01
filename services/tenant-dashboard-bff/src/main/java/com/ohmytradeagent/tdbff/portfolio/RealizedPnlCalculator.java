package com.ohmytradeagent.tdbff.portfolio;

// FIFO realized-PnL algorithm COPIED FROM
// services/orchestrator/.../activities/DailyPnlActivitiesImpl.java (computeRealizedPnl + fetchLots)
// — keep in sync. Realizes P&L only for contracts actually exited on the trading day: EntryFilled
// rows establish a per-contract cost basis (avg_fill_price, filled_qty); PartialExitFilled rows
// (avg_fill_price, qty_filled) FIFO-match against them, grouped by option_symbol; each matched
// contract realizes (exit_price − entry_basis) × 100. Open (un-exited) entries contribute nothing.
// Documented limitation (issue #276 §4): a position entered on a prior day and closed today credits
// raw exit proceeds with no same-day cost basis (phantom gain). Trading day is America/New_York.
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RealizedPnlCalculator {

  private static final Logger log = LoggerFactory.getLogger(RealizedPnlCalculator.class);
  static final BigDecimal MULTIPLIER = new BigDecimal("100");
  private static final Set<String> ALLOWED_QTY_KEYS = Set.of("filled_qty", "qty_filled");
  private static final String NO_SYMBOL_BUCKET = "";

  private final DSLContext orchestratorDsl;

  public RealizedPnlCalculator(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /** Realized P&L for one (tenant, strategy) on {@code tradingDay} (America/New_York). */
  public BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay) {
    Map<String, Deque<Lot>> entriesBySymbol =
        fetchLots(tenantId, strategyId, tradingDay, "EntryFilled", "filled_qty");
    Map<String, Deque<Lot>> exitsBySymbol =
        fetchLots(tenantId, strategyId, tradingDay, "PartialExitFilled", "qty_filled");

    BigDecimal realized = BigDecimal.ZERO;
    for (Map.Entry<String, Deque<Lot>> e : exitsBySymbol.entrySet()) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(e.getKey(), new ArrayDeque<>());
      realized = realized.add(realizePerSymbol(entries, e.getValue()));
    }
    return realized.multiply(MULTIPLIER);
  }

  private static BigDecimal realizePerSymbol(Deque<Lot> entries, Deque<Lot> exits) {
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

  private record Lot(BigDecimal price, long qty) {
    Lot consume(long n) {
      return new Lot(price, qty - n);
    }
  }

  private Map<String, Deque<Lot>> fetchLots(
      String tenantId, String strategyId, LocalDate tradingDay, String kind, String qtyKey) {
    if (!ALLOWED_QTY_KEYS.contains(qtyKey)) {
      throw new IllegalArgumentException("unsupported qtyKey: " + qtyKey);
    }
    // Resolve to a compile-time literal so the SQL is built ONLY from constants; the whitelist
    // above
    // is then a contract check, not the sole barrier against a caller key reaching the query.
    String qtyCol = "filled_qty".equals(qtyKey) ? "filled_qty" : "qty_filled";
    String sql =
        "SELECT (subject->>'avg_fill_price')::numeric AS price, "
            + "(subject->>'"
            + qtyCol
            + "')::numeric AS qty, "
            + "subject->>'option_symbol' AS option_symbol "
            + "FROM audit_log "
            + "WHERE tenant_id = ? AND strategy_id = ? AND kind = ? "
            + "AND (occurred_at AT TIME ZONE 'America/New_York')::date = ? "
            + "AND subject->>'avg_fill_price' IS NOT NULL "
            + "AND subject->>'"
            + qtyCol
            + "' IS NOT NULL "
            + "ORDER BY occurred_at ASC, event_id ASC";
    Result<Record> rows = orchestratorDsl.fetch(sql, tenantId, strategyId, kind, tradingDay);
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
            "fetchLots: skipping {} row with fractional/oversized {}={} (tenant={} strategy={})",
            kind,
            qtyKey,
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
