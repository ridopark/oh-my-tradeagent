package com.ohmytradeagent.orchestrator.activities;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Realized-only PnL composition from {@code audit_log}:
 *
 * <ul>
 *   <li>EntryFilled rows debit {@code avg_fill_price * filled_qty * 100} (long-only call/put buyer
 *       cost basis).
 *   <li>PartialExitFilled rows credit {@code avg_fill_price * qty_filled * 100}.
 * </ul>
 *
 * <p>Phase 5 ships paper-only. EOD/expiry force-flatten audits currently do not carry a fill price
 * — those positions remain "open" for PnL purposes until {@link
 * com.ohmytradeagent.orchestrator.workflows.PositionWorkflow} grows fill-event-driven flattens
 * (Phase 5b). Mark-to-market for open positions also lands in Phase 5b.
 *
 * <p>Multiplier is 100 (US equity options standard contract).
 */
@Component
public class DailyPnlActivitiesImpl implements DailyPnlActivities {

  private static final Logger log = LoggerFactory.getLogger(DailyPnlActivitiesImpl.class);

  static final BigDecimal MULTIPLIER = new BigDecimal("100");

  private final DSLContext dsl;

  public DailyPnlActivitiesImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay) {
    if (dsl == null) {
      // KISS fallback for test envs without a database; production env always wires a DSLContext.
      log.warn(
          "computeRealizedPnl: no DSLContext bean — returning zero (tenant={} strategy={} day={})",
          tenantId,
          strategyId,
          tradingDay);
      return BigDecimal.ZERO;
    }

    BigDecimal credits =
        sumPriceQty(tenantId, strategyId, tradingDay, "PartialExitFilled", "qty_filled");
    BigDecimal debits = sumPriceQty(tenantId, strategyId, tradingDay, "EntryFilled", "filled_qty");
    return credits.subtract(debits).multiply(MULTIPLIER);
  }

  /**
   * Sums {@code avg_fill_price * <qtyKey>} across audit_log rows matching the given kind on the
   * trading day. Rows missing either field are skipped (Postgres {@code NULL} from {@code ->>}
   * propagates through arithmetic to {@code NULL}, then COALESCE drops to 0).
   */
  private BigDecimal sumPriceQty(
      String tenantId, String strategyId, LocalDate tradingDay, String kind, String qtyKey) {
    String sql =
        "SELECT COALESCE(SUM("
            + "(subject->>'avg_fill_price')::numeric * (subject->>'"
            + qtyKey
            + "')::numeric"
            + "), 0) "
            + "FROM audit_log "
            + "WHERE tenant_id = ? AND strategy_id = ? AND kind = ? "
            + "AND (occurred_at AT TIME ZONE 'America/New_York')::date = ?";
    Result<Record> rows = dsl.fetch(sql, tenantId, strategyId, kind, tradingDay);
    Object v = rows.isEmpty() ? null : rows.get(0).get(0);
    if (v == null) {
      return BigDecimal.ZERO;
    }
    if (v instanceof BigDecimal bd) {
      return bd;
    }
    return new BigDecimal(v.toString());
  }
}
