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
 * <p>Cross-day fix (PLAN-2026-07-22, issue #276 §4): a position entered on a prior day and closed
 * today no longer credits raw exit proceeds with no cost basis (the phantom that ALWAYS inflated
 * realized and let a genuine cross-day LOSS fail-open the daily-loss cap). Lookback-bounded {@code
 * audit_log} history is now fetched ({@code [tradingDay − REALIZED_LOOKBACK_DAYS, tradingDay]},
 * since this runs on the ~60s kill-switch heartbeat and an unbounded scan would grow without limit)
 * and every exit FIFO-matches its REAL (possibly prior-day) entry basis; the day-scoped total
 * counts only the exits whose ET date ({@code occurred_at} in America/New_York) equals the trading
 * day, while still consuming entry lots for prior-day exits so FIFO reaches the correct remaining
 * basis. A cross-day exit's realized is thus attributed to its own exit day against its real entry
 * basis — mirroring the exec-journal and BFF {@code RealizedPnlCalculator} transforms. Remaining
 * limitation: an exit whose entry pre-dates the lookback window (or retained history) — no matching
 * entry lot in-window — still falls to raw proceeds, counted only on its exit day; the window is
 * chosen well beyond the option tenor so this is unreachable for a still-open expiring position.
 *
 * <p>Orphaned / {@code RECORDED}-but-unfilled rows (no {@code EntryFilled} audit is emitted until a
 * fill lands) are excluded by construction. EOD/expiry force-flatten audits do not carry a fill
 * price and so do not credit here — those positions remain open for PnL until {@link
 * com.ohmytradeagent.orchestrator.workflows.PositionWorkflow} grows fill-event-driven flattens.
 *
 * <p>Multiplier is 100 (US equity options standard contract).
 */
@Component
public class DailyPnlActivitiesImpl implements DailyPnlActivities {

  private static final Logger log = LoggerFactory.getLogger(DailyPnlActivitiesImpl.class);

  static final BigDecimal MULTIPLIER = new BigDecimal("100");

  /**
   * Realized-history lookback (PLAN-2026-07-22 review follow-up). {@code computeRealizedPnl} runs
   * on the account kill-switch HEARTBEAT (~every 60s), so the cross-day fix's full-history fetch is
   * bounded to {@code [tradingDay − REALIZED_LOOKBACK_DAYS, tradingDay]} to keep the {@code
   * audit_log} scan from growing without limit toward the Activity StartToCloseTimeout on an
   * active/long-history tenant.
   *
   * <p><b>Safety rationale for 90:</b> the window MUST exceed the strategies' maximum option tenor
   * so a still-openable position's entry is always in-window and its cross-day exit FIFO-matches
   * the real basis (never a reintroduced phantom fail-open). These strategies trade short-dated
   * (weekly) options; even a monthly would be ~30-45 DTE, so 90 days is generously beyond any
   * realistic hold. Options expire, so no realistic hold reaches this window. A position entered
   * MORE than the window ago (and exited today) falls to the documented raw-proceeds residual — the
   * SAME limitation as pre-history retention, now bounded. Erring long: this is a real-money safety
   * mechanism, so the window is chosen well beyond the tenor rather than tight to it. Kept in
   * lockstep with {@code DailyPnlExecActivityImpl.REALIZED_LOOKBACK_DAYS}.
   *
   * <p>Intentional divergence from the BFF {@code RealizedPnlCalculator} (#617), which stays
   * full-history: that is a per-page-load display path, not a ~60s heartbeat, so it does not need
   * the bound. Only the heartbeat-driven kill-switch fetch is windowed.
   */
  static final int REALIZED_LOOKBACK_DAYS = 90;

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
    // Cross-day fix (PLAN-2026-07-22): fetch LOOKBACK-BOUNDED history (no per-day equality, only an
    // ET-date lower bound) so a cross-day exit FIFO-matches its REAL prior-day entry basis; the
    // target day scopes only which exits COUNT. The bound keeps this heartbeat-path scan from
    // growing without limit (see REALIZED_LOOKBACK_DAYS). Anchored to tradingDay (the Activity
    // input, ~= today on the heartbeat), so the window is deterministic and replay-stable.
    LocalDate sinceEtDay = tradingDay.minusDays(REALIZED_LOOKBACK_DAYS);
    // #820 review finding 1: entry basis comes from EntryFilled PLUS the #738/#820 growth rows
    // (PositionEntryIncreased carries the delta qty + the journal's cumulative avg price). Without
    // them, a grown/corrected lot's exit FIFO-exhausts the first-slice basis and the residual is
    // credited at RAW PROCEEDS — ~+$5k of phantom realized gain on the prod_real SMCI heal, in the
    // very ledger the daily-loss breaker reads. One time-ordered stream (single query, kind IN)
    // so growth lots interleave at their true occurred_at.
    Map<String, Deque<Lot>> entriesBySymbol =
        fetchEntryLotsIncludingGrowth(tenantId, strategyId, sinceEtDay);
    Map<String, Deque<Lot>> exitsBySymbol =
        fetchLots(tenantId, strategyId, "PartialExitFilled", "qty_filled", sinceEtDay);

    BigDecimal realized = BigDecimal.ZERO;
    for (Map.Entry<String, Deque<Lot>> e : exitsBySymbol.entrySet()) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(e.getKey(), new ArrayDeque<>());
      realized = realized.add(realizePerSymbol(entries, e.getValue(), tradingDay));
    }
    return realized.multiply(MULTIPLIER);
  }

  /**
   * FIFO-matches one symbol's exits against that symbol's entry cost basis: {@code realized =
   * (exit_price − entry_basis)} per matched contract (multiplier applied by the caller). Un-exited
   * entry contracts contribute nothing — their debit is excluded until they exit (issue #273), so
   * an open long never fabricates a loss.
   *
   * <p>Consumes ALL exits chronologically so FIFO reaches each exit's true remaining basis, but
   * adds an exit's realized (matched legs AND the residual raw-proceeds fallback) to the total ONLY
   * when {@code targetDay == null} (all-time) or equals the exit's ET date. A prior-day exit still
   * advances the entry FIFO; it just does not count toward a non-null target day. This attributes a
   * cross-day exit's realized to its own exit day against its real prior-day basis instead of
   * crediting phantom raw proceeds (issue #276 §4).
   */
  private static BigDecimal realizePerSymbol(
      Deque<Lot> entries, Deque<Lot> exits, LocalDate targetDay) {
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
      // A residual exit qty with no remaining entry basis (an exit whose entry pre-dates retained
      // history) realizes the exit credit alone — counted only on its own exit day.
      if (remainingExitQty > 0 && count) {
        realized = realized.add(exit.price.multiply(BigDecimal.valueOf(remainingExitQty)));
      }
    }
    return realized;
  }

  /**
   * A fill lot: per-contract {@code avg_fill_price}, the (mutable-remaining) contract count, and
   * the fill's ET date ({@code day}; populated for exits to drive day-scoping, null/unused for
   * entries).
   */
  private record Lot(BigDecimal price, long qty, LocalDate day) {
    Lot consume(long n) {
      return new Lot(price, qty - n, day);
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
   * Fetches fill lots for the given audit kind across LOOKBACK-BOUNDED history (cross-day fix: the
   * per-day equality predicate is replaced by an ET-date lower bound {@code >= sinceEtDay} so a
   * cross-day exit FIFO-matches its real prior-day basis while the heartbeat-path scan stays
   * bounded), ordered by {@code occurred_at} (FIFO), grouped by {@code option_symbol} (issue #276).
   * Each lot carries {@code (avg_fill_price, <qtyKey>)} and the row's ET date so the caller can
   * day-scope in-memory. Rows missing a price/qty field, with a non-positive quantity, or with a
   * fractional quantity (issue #276 [minor]) are skipped — a malformed audit row never moves the
   * figure and never crashes the activity. Rows lacking {@code option_symbol} (pre-change history)
   * are grouped under the {@link #NO_SYMBOL_BUCKET} key.
   */
  /**
   * #820: EntryFilled rows plus PositionEntryIncreased growth rows as ONE time-ordered basis
   * stream. The growth row's qty key is {@code qty_added}; its price is the entry order's
   * cumulative average at that point (bookEntryGrowth). COALESCE picks whichever key the row
   * carries; ordering by occurred_at keeps a lot's growth strictly after its first slice.
   */
  private Map<String, Deque<Lot>> fetchEntryLotsIncludingGrowth(
      String tenantId, String strategyId, LocalDate sinceEtDay) {
    String sql =
        "SELECT (subject->>'avg_fill_price')::numeric AS price, "
            + "COALESCE(subject->>'filled_qty', subject->>'qty_added')::numeric AS qty, "
            + "subject->>'option_symbol' AS option_symbol, "
            + "(occurred_at AT TIME ZONE 'America/New_York')::date AS et_date "
            + "FROM audit_log "
            + "WHERE tenant_id = ? AND strategy_id = ? "
            + "AND kind IN ('EntryFilled', 'PositionEntryIncreased') "
            + "AND subject->>'avg_fill_price' IS NOT NULL "
            + "AND COALESCE(subject->>'filled_qty', subject->>'qty_added') IS NOT NULL "
            + "AND (occurred_at AT TIME ZONE 'America/New_York')::date >= ? "
            + "ORDER BY occurred_at ASC, event_id ASC";
    Result<Record> rows = dsl.fetch(sql, tenantId, strategyId, sinceEtDay);
    return lotsFromRows(rows);
  }

  private Map<String, Deque<Lot>> fetchLots(
      String tenantId, String strategyId, String kind, String qtyKey, LocalDate sinceEtDay) {
    if (!ALLOWED_QTY_KEYS.contains(qtyKey)) {
      throw new IllegalArgumentException("unsupported qtyKey: " + qtyKey);
    }
    String sql =
        "SELECT (subject->>'avg_fill_price')::numeric AS price, "
            + "(subject->>'"
            + qtyKey
            + "')::numeric AS qty, "
            + "subject->>'option_symbol' AS option_symbol, "
            + "(occurred_at AT TIME ZONE 'America/New_York')::date AS et_date "
            + "FROM audit_log "
            + "WHERE tenant_id = ? AND strategy_id = ? AND kind = ? "
            + "AND subject->>'avg_fill_price' IS NOT NULL "
            + "AND subject->>'"
            + qtyKey
            + "' IS NOT NULL "
            // Lookback lower bound (PLAN-2026-07-22 review follow-up): bound the heartbeat-path
            // scan
            // while still covering any realistic still-open options position. Mirrors the ET-date
            // boundary used for et_date above.
            + "AND (occurred_at AT TIME ZONE 'America/New_York')::date >= ? "
            + "ORDER BY occurred_at ASC, event_id ASC";
    Result<Record> rows = dsl.fetch(sql, tenantId, strategyId, kind, sinceEtDay);
    return lotsFromRows(rows);
  }

  private static Map<String, Deque<Lot>> lotsFromRows(Result<Record> rows) {
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
        log.warn("lot row skipped: fractional/oversized qty={}", qty);
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
