package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * <p><b>Non-monotonic caveat (issue #276 §4, guardrail G3).</b> This figure is NOT guaranteed "only
 * more negative" than the orchestrator {@code audit_log} figure. The journal is the intended source
 * of truth (broker fills always land), but it groups on {@code filled_at} (America/New_York)
 * whereas {@code audit_log} groups on {@code occurred_at}, so a cross-day / ET-boundary fill can
 * bucket differently; and this does NOT fix the documented #276 phantom-gain (a prior-day entry
 * exited today credits raw exit proceeds with no same-day cost basis). It re-sources broker truth,
 * it does not claim monotonicity.
 *
 * <p>Constructor-injects {@link OrderIntentJournal} only (mirrors {@code
 * ReconciliationExecActivityImpl}); stateless and safe under Temporal Activity retry.
 */
@Component
public class DailyPnlExecActivityImpl implements DailyPnlExecActivity {

  // US equity options standard contract multiplier — matches the BFF RealizedPnlCalculator.
  static final BigDecimal MULTIPLIER = new BigDecimal("100");
  private static final String NO_SYMBOL_BUCKET = "";

  private final OrderIntentJournal journal;

  public DailyPnlExecActivityImpl(OrderIntentJournal journal) {
    this.journal = journal;
  }

  @Override
  public BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay) {
    Map<String, Deque<Lot>> entriesBySymbol = lotsBySymbol(tenantId, strategyId, tradingDay, "BUY");
    Map<String, Deque<Lot>> exitsBySymbol = lotsBySymbol(tenantId, strategyId, tradingDay, "SELL");

    BigDecimal realized = BigDecimal.ZERO;
    for (Map.Entry<String, Deque<Lot>> e : exitsBySymbol.entrySet()) {
      Deque<Lot> entries = entriesBySymbol.getOrDefault(e.getKey(), new ArrayDeque<>());
      realized = realized.add(realizePerSymbol(entries, e.getValue()));
    }
    return realized.multiply(MULTIPLIER);
  }

  // Buckets FILLED journal rows for one side by option_symbol (grouping on the stored padded-OCC
  // option_symbol is internally consistent — both sides read the same column). A fractional /
  // oversized filled_qty is skipped defensively (never crash-loops the activity under retry).
  private Map<String, Deque<Lot>> lotsBySymbol(
      String tenantId, String strategyId, LocalDate tradingDay, String side) {
    List<JournaledOrder> rows =
        journal.findFilledBySideOnDay(tenantId, strategyId, side, tradingDay);
    Map<String, Deque<Lot>> lotsBySymbol = new LinkedHashMap<>();
    for (JournaledOrder r : rows) {
      BigDecimal price = r.avgFillPrice();
      Long qty = r.filledQty();
      if (price == null || qty == null || qty <= 0) {
        continue;
      }
      String bucket = r.optionSymbol() == null ? NO_SYMBOL_BUCKET : r.optionSymbol();
      lotsBySymbol.computeIfAbsent(bucket, k -> new ArrayDeque<>()).add(new Lot(price, qty));
    }
    return lotsBySymbol;
  }

  // ---------------------------------------------------------------------------
  // FIFO realization — DUPLICATED VERBATIM (keep in lockstep with
  // RealizedPnlCalculator.realizePerSymbol / DailyPnlActivitiesImpl.realizePerSymbol). No shared
  // no-jOOQ module exists, so this matches the existing 3-copy precedent; FIFO parity is pinned by
  // DailyPnlExecActivityImplFifoParityTest against the shared BFF fixture.
  // ---------------------------------------------------------------------------

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
}
