package com.ohmytradeagent.orchestrator.activities;

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
import org.springframework.stereotype.Component;

/**
 * Realized-only daily PnL composition from {@code audit_log}.
 *
 * <p>Issue #273: the daily-loss kill switch must reflect <em>realized</em> P&amp;L, not raw capital
 * deployed. The earlier implementation booked {@code Σ exit credits − Σ entry debits}, which sank
 * the figure by the full purchase cost of every <em>open</em> long the moment it filled — with no
 * mark-to-market or open-position offset on the other side of the ledger. On a normal trading day a
 * couple of unexited entries would push the figure below {@code -daily_loss_threshold} and trip the
 * kill switch even though nothing had actually been lost.
 *
 * <p>This implementation realizes P&amp;L only for the contracts that have actually been
 * <em>exited</em> on the trading day. Open (un-exited) entry debits are excluded from the figure
 * until a {@code PartialExitFilled} credit closes them — there is no Alpaca account-P&amp;L source
 * wired here (no {@code OptionsBroker}/market-data adapter exposes {@code equity}/{@code
 * last_equity}/{@code unrealized_pl}/{@code realized_pl}), so the issue's documented fallback —
 * realized-only — is used instead of broker account P&amp;L:
 *
 * <ul>
 *   <li>EntryFilled rows establish a per-contract cost basis ({@code avg_fill_price}, {@code
 *       filled_qty}), ordered by fill time.
 *   <li>PartialExitFilled rows ({@code avg_fill_price}, {@code qty_filled}) are matched FIFO
 *       against those entries; each exited contract realizes {@code (exit_price − entry_cost_basis)
 *       * 100}.
 *   <li>Entry contracts with no matching exit contribute nothing — an open long never fabricates a
 *       loss.
 * </ul>
 *
 * <p>Issue #276: FIFO matching is grouped by {@code option_symbol} so each exited contract realizes
 * against its OWN symbol's entry basis — pooling all symbols' entries into one FIFO queue let a
 * same-day exit match a foreign symbol's cost basis and mis-state the daily figure against the
 * kill-switch threshold. The {@code option_symbol} key is emitted on both fill-audit subjects under
 * a {@code Workflow.getVersion} gate in the producers ({@code CopytradeSignalWorkflowImpl} entry,
 * {@code PositionWorkflowImpl} exit). Pre-change historical rows lack the key; they are tolerated
 * by grouping all keyless rows into a single no-symbol bucket that FIFO-matches among itself
 * exactly as before (no data migration; never NPE, never cross-attribute against keyed rows).
 *
 * <p>Limitation (issue #276 §4, documented only, out of scope here): a position entered on a prior
 * day and closed today credits raw exit proceeds with no same-day cost basis (phantom gain), so a
 * genuine prior-day loss closed intraday does not count toward today's daily-loss figure — the
 * switch can fail-open. The proper fix is wiring Alpaca account P&amp;L ({@code equity −
 * last_equity} / {@code unrealized_pl + realized_pl}) as the kill-switch source of truth; tracked
 * separately, not attempted in this change.
 *
 * <p>Rows are already scoped to the trading day and (tenant, strategy), so orphaned / {@code
 * RECORDED}-but-unfilled (no {@code EntryFilled} audit is emitted until a fill lands) and prior-day
 * positions are excluded by construction. EOD/expiry force-flatten audits do not carry a fill price
 * and so do not credit here — those positions remain open for PnL until {@link
 * com.ohmytradeagent.orchestrator.workflows.PositionWorkflow} grows fill-event-driven flattens.
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

    // Issue #276: group both sides by option_symbol so each exited contract realizes against its
    // OWN symbol's entry basis (pooling across symbols let an exit FIFO-match a foreign symbol's
    // cost basis). Pre-change rows lacking option_symbol fall into a single no-symbol bucket ("")
    // and FIFO-match among themselves exactly as before.
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

  /**
   * FIFO-matches one symbol's exits against that symbol's entry cost basis. Realizes P&L only for
   * contracts that have actually been exited today: {@code realized = (exit_price − entry_basis)}
   * per matched contract (multiplier applied by the caller). Un-exited entry contracts contribute
   * nothing — their debit is excluded until they exit (issue #273), so an open long never
   * fabricates a loss.
   */
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
      // A residual exit qty with no remaining entry basis (e.g. a prior-day entry exited today, or
      // an exit audit without a matching same-day entry) realizes the exit credit alone; there is
      // no same-day cost basis to net against.
      if (remainingExitQty > 0) {
        realized = realized.add(exit.price.multiply(BigDecimal.valueOf(remainingExitQty)));
      }
    }
    return realized;
  }

  /** A fill lot: per-contract {@code avg_fill_price} and the (mutable-remaining) contract count. */
  private record Lot(BigDecimal price, long qty) {
    Lot consume(long n) {
      return new Lot(price, qty - n);
    }
  }

  /**
   * Allowlist for the {@code qtyKey} that is string-interpolated into the {@code fetchLots} SQL.
   * {@code fetchLots} is only ever called with these hardcoded literals, but the allowlist guard
   * (issue #276 [nit]) prevents a future refactor from introducing SQL injection through that
   * interpolation point.
   */
  private static final Set<String> ALLOWED_QTY_KEYS = Set.of("filled_qty", "qty_filled");

  /** Bucket key for pre-change historical rows that lack an {@code option_symbol} subject field. */
  private static final String NO_SYMBOL_BUCKET = "";

  /**
   * Fetches fill lots for the given audit kind on the trading day, ordered by {@code occurred_at}
   * (FIFO), grouped by {@code option_symbol} (issue #276). Each lot is {@code (avg_fill_price,
   * <qtyKey>)}. Rows missing either field, with a non-positive quantity, or with a fractional
   * quantity (issue #276 [minor]) are skipped — a malformed audit row never moves the figure and
   * never crashes the activity. Rows lacking {@code option_symbol} (pre-change history) are grouped
   * under the {@link #NO_SYMBOL_BUCKET} key.
   */
  private Map<String, Deque<Lot>> fetchLots(
      String tenantId, String strategyId, LocalDate tradingDay, String kind, String qtyKey) {
    if (!ALLOWED_QTY_KEYS.contains(qtyKey)) {
      throw new IllegalArgumentException("unsupported qtyKey: " + qtyKey);
    }
    String sql =
        "SELECT (subject->>'avg_fill_price')::numeric AS price, "
            + "(subject->>'"
            + qtyKey
            + "')::numeric AS qty, "
            + "subject->>'option_symbol' AS option_symbol "
            + "FROM audit_log "
            + "WHERE tenant_id = ? AND strategy_id = ? AND kind = ? "
            + "AND (occurred_at AT TIME ZONE 'America/New_York')::date = ? "
            + "AND subject->>'avg_fill_price' IS NOT NULL "
            + "AND subject->>'"
            + qtyKey
            + "' IS NOT NULL "
            + "ORDER BY occurred_at ASC, event_id ASC";
    Result<Record> rows = dsl.fetch(sql, tenantId, strategyId, kind, tradingDay);
    Map<String, Deque<Lot>> lotsBySymbol = new LinkedHashMap<>();
    for (Record r : rows) {
      BigDecimal price = r.get("price", BigDecimal.class);
      BigDecimal qty = r.get("qty", BigDecimal.class);
      if (price == null || qty == null || qty.signum() <= 0) {
        continue;
      }
      long qtyLong;
      try {
        // Options qty is integer in practice; a fractional filled_qty is defensively skipped rather
        // than thrown (longValueExact() would raise ArithmeticException and crash-loop the activity
        // under Temporal retry).
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
