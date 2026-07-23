package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase 2 (kill-switch realized re-source) exec impl of {@link DailyPnlExecActivity}. Reaches
 * exec's own {@code order_intent_journal} via {@link OrderIntentJournal} (jOOQ) — broker truth —
 * and FIFO-computes the realized figure the daily-loss kill switches trip on. A SELL that filled at
 * the broker but whose {@code PartialExitFilled} audit was lost (the Finding-1 flatten race) is
 * STILL counted here, because the journal always records the broker fill.
 *
 * <p><b>Cross-day realized fix (PLAN-2026-07-22, kill-switch phantom).</b> The day-scoped realized
 * figure no longer credits raw proceeds for a position entered on a prior day and exited today (the
 * #276 §4 phantom that ALWAYS inflated realized and let a cross-day LOSS fail-OPEN the daily-loss
 * cap). Both sides now fetch LOOKBACK-BOUNDED history ({@code [tradingDay − REALIZED_LOOKBACK_DAYS,
 * tradingDay]}, since this runs on the ~60s kill-switch heartbeat and an unbounded scan would grow
 * without limit) and FIFO-match every exit against its REAL (possibly prior-day) entry basis; the
 * day-scoped total counts only the exits whose ET date ({@code filled_at} in America/New_York)
 * equals the target trading day, while still consuming entry lots for prior-day exits so FIFO
 * reaches the correct remaining basis. A cross-day exit's realized is thus attributed to its own
 * exit day against its real entry basis — mirroring the BFF {@code RealizedPnlCalculator}
 * transform. Remaining limitation: an exit whose entry pre-dates the lookback window (or journal
 * retention) — no matching entry lot in-window — still falls to raw proceeds, counted only on its
 * exit day; the window is chosen well beyond the option tenor so this is unreachable for a
 * still-open expiring position.
 *
 * <p><b>Non-monotonic caveat (issue #276 §4, guardrail G3).</b> This figure is NOT guaranteed "only
 * more negative" than the orchestrator {@code audit_log} figure. The journal is the intended source
 * of truth (broker fills always land), but it groups on {@code filled_at} (America/New_York)
 * whereas {@code audit_log} groups on {@code occurred_at}, so a cross-day / ET-boundary fill can
 * bucket differently. It re-sources broker truth, it does not claim monotonicity.
 *
 * <p>Constructor-injects {@link OrderIntentJournal} only (mirrors {@code
 * ReconciliationExecActivityImpl}); stateless and safe under Temporal Activity retry.
 */
@Component
public class DailyPnlExecActivityImpl implements DailyPnlExecActivity {

  // US equity options standard contract multiplier — matches the BFF RealizedPnlCalculator.
  static final BigDecimal MULTIPLIER = new BigDecimal("100");
  private static final String NO_SYMBOL_BUCKET = "";
  // Trading-day boundary — mirrors the journal SQL's (filled_at AT TIME ZONE 'America/New_York').
  private static final ZoneId ET = ZoneId.of("America/New_York");

  /**
   * Realized-history lookback (PLAN-2026-07-22 review follow-up). {@code computeRealizedPnl} runs
   * on the account kill-switch HEARTBEAT (~every 60s), so the cross-day fix's full-history fetch is
   * bounded to {@code [tradingDay − REALIZED_LOOKBACK_DAYS, tradingDay]} to keep the scan from
   * growing without limit toward the Activity StartToCloseTimeout on an active/long-history tenant.
   *
   * <p><b>Safety rationale for 90:</b> the window MUST exceed the strategies' maximum option tenor
   * so a still-openable position's entry is always in-window and its cross-day exit FIFO-matches
   * the real basis (never a reintroduced phantom fail-open). These strategies trade short-dated
   * (weekly) options; even a monthly would be ~30-45 DTE, so 90 days is generously beyond any
   * realistic hold. Options expire, so no realistic hold reaches this window. A position entered
   * MORE than the window ago (and exited today) falls to the documented raw-proceeds residual — the
   * SAME limitation as pre-history retention, now bounded. Erring long: this is a real-money safety
   * mechanism, so the window is chosen well beyond the tenor rather than tight to it.
   *
   * <p>Intentional divergence from the BFF {@code RealizedPnlCalculator} (#617), which stays
   * full-history: that is a per-page-load display path, not a ~60s heartbeat, so it does not need
   * the bound. Only the heartbeat-driven kill-switch fetch is windowed.
   */
  static final int REALIZED_LOOKBACK_DAYS = 90;

  private final OrderIntentJournal journal;

  public DailyPnlExecActivityImpl(OrderIntentJournal journal) {
    this.journal = journal;
  }

  @Override
  public BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay) {
    // Cross-day realized fix (PLAN-2026-07-22): fetch LOOKBACK-BOUNDED history (no per-day
    // equality,
    // only an ET-date lower bound) so a cross-day exit FIFO-matches its REAL prior-day entry basis;
    // day-scope the total in-memory. The bound keeps this heartbeat-path scan from growing without
    // limit (see REALIZED_LOOKBACK_DAYS). Anchored to tradingDay (the Activity input, ~= today on
    // the heartbeat) rather than wall-clock now, so the window is deterministic and replay-stable.
    LocalDate sinceEtDay = tradingDay.minusDays(REALIZED_LOOKBACK_DAYS);
    Map<String, Deque<Lot>> entriesBySymbol = lotsBySymbol(tenantId, strategyId, "BUY", sinceEtDay);
    Map<String, Deque<Lot>> exitsBySymbol = lotsBySymbol(tenantId, strategyId, "SELL", sinceEtDay);

    BigDecimal realized = BigDecimal.ZERO;
    for (Map.Entry<String, Deque<Lot>> e : exitsBySymbol.entrySet()) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(e.getKey(), new ArrayDeque<>());
      realized = realized.add(realizePerSymbol(entries, e.getValue(), tradingDay));
    }
    return realized.multiply(MULTIPLIER);
  }

  // Buckets the lookback-bounded FILLED journal rows for one side by option_symbol (grouping on the
  // stored padded-OCC
  // option_symbol is internally consistent — both sides read the same column), carrying each fill's
  // ET date (from filled_at) so the caller can day-scope in-memory. A fractional / oversized
  // filled_qty is skipped defensively (never crash-loops the activity under retry). A FILLED row
  // with a null filled_at (no realizable timestamp) is skipped — its day cannot be resolved.
  private Map<String, Deque<Lot>> lotsBySymbol(
      String tenantId, String strategyId, String side, LocalDate sinceEtDay) {
    List<JournaledOrder> rows = journal.findFilledBySide(tenantId, strategyId, side, sinceEtDay);
    Map<String, Deque<Lot>> lotsBySymbol = new LinkedHashMap<>();
    for (JournaledOrder r : rows) {
      BigDecimal price = r.avgFillPrice();
      Long qty = r.filledQty();
      OffsetDateTime filledAt = r.filledAt();
      if (price == null || qty == null || qty <= 0 || filledAt == null) {
        continue;
      }
      LocalDate day = filledAt.atZoneSameInstant(ET).toLocalDate();
      String bucket = r.optionSymbol() == null ? NO_SYMBOL_BUCKET : r.optionSymbol();
      lotsBySymbol.computeIfAbsent(bucket, k -> new ArrayDeque<>()).add(new Lot(price, qty, day));
    }
    return lotsBySymbol;
  }

  // ---------------------------------------------------------------------------
  // FIFO realization — DUPLICATED VERBATIM (keep in lockstep with
  // RealizedPnlCalculator.realizePerSymbol / DailyPnlActivitiesImpl.realizePerSymbol). No shared
  // no-jOOQ module exists, so this matches the existing 3-copy precedent; FIFO parity is pinned by
  // DailyPnlExecActivityImplTest against the shared BFF fixture.
  //
  // Consumes ALL exits chronologically so FIFO reaches each exit's true remaining basis, but adds
  // an exit's realized (matched legs AND the residual raw-proceeds fallback) to the total ONLY when
  // targetDay == null (all-time) or equals the exit's ET date. A prior-day exit still advances the
  // entry FIFO; it just does not count toward a non-null target day. This attributes a cross-day
  // exit's realized to its own exit day against its real entry basis instead of phantom proceeds.
  // ---------------------------------------------------------------------------

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
}
