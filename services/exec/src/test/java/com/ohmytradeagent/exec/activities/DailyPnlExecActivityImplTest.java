package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.activities.DailyPnlExecActivityImpl.Lot;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 (kill-switch realized re-source). Unit coverage of {@link DailyPnlExecActivityImpl}
 * against a mocked {@link OrderIntentJournal} (no Postgres): the FIFO wiring, the C1 lost-audit
 * self-heal, the C3 FIFO parity with the BFF {@code RealizedPnlCalculator} fixture, and the C7
 * same-day parity.
 */
class DailyPnlExecActivityImplTest {

  private static final LocalDate DAY = LocalDate.of(2026, 6, 29);
  private static final String OCC = "DRAM  260703C00016000";

  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final DailyPnlExecActivityImpl activity = new DailyPnlExecActivityImpl(journal);

  private static JournaledOrder filled(String side, String occ, long qty, String price) {
    return new JournaledOrder(
        "intent-" + side + "-" + occ + "-" + qty + "-" + price,
        "sig",
        "dev",
        "copytrade-v1",
        "alpaca-live",
        "cid",
        occ,
        side,
        qty,
        new BigDecimal("2.00"),
        OrderState.FILLED,
        "brk",
        OffsetDateTime.parse("2026-06-29T14:00:00Z"),
        OffsetDateTime.parse("2026-06-29T14:00:01Z"),
        OffsetDateTime.parse("2026-06-29T14:00:02Z"),
        null,
        null,
        qty,
        new BigDecimal(price),
        OffsetDateTime.parse("2026-06-29T14:00:03Z"),
        1L);
  }

  private void stub(String side, JournaledOrder... rows) {
    when(journal.findFilledBySideOnDay(eq("dev"), eq("copytrade-v1"), eq(side), eq(DAY)))
        .thenReturn(List.of(rows));
  }

  // C1: a SELL that FILLED at the broker (its PartialExitFilled audit was NEVER written to
  // audit_log) is still counted here — the journal always has the fill. The 2026-06-29 DRAM live
  // case: bought 3 @ 2.3533, sold 2 @ 1.84 -> (1.84-2.3533)*2*100 = -102.66. The exec-journal
  // figure
  // is MORE negative than an audit_log figure that missed the exit ($0).
  @Test
  void computeRealizedPnl_countsLostAuditSell_moreNegativeThanZero() {
    stub("BUY", filled("BUY", OCC, 3, "2.3533"));
    stub("SELL", filled("SELL", OCC, 2, "1.84"));

    BigDecimal realized = activity.computeRealizedPnl("dev", "copytrade-v1", DAY);

    assertThat(realized).isEqualByComparingTo(new BigDecimal("-102.66"));
    // The audit_log figure that MISSED the exit would have been 0; broker truth is more negative.
    assertThat(realized).isLessThan(BigDecimal.ZERO);
  }

  // C7: same-day normal case (every exit has a same-day matching entry) — parity with the audit_log
  // number on the common path. Full 3-of-3 exit against the 2.3533 basis at 3.00: +0.6467*3*100.
  @Test
  void computeRealizedPnl_sameDayFullMatch_parityCommonPath() {
    stub("BUY", filled("BUY", OCC, 3, "2.3533"));
    stub("SELL", filled("SELL", OCC, 3, "3.00"));

    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", DAY))
        .isEqualByComparingTo(new BigDecimal("194.01")); // (3.00-2.3533)*3*100
  }

  // Grouped per option_symbol: an exit only nets against its OWN symbol's entry basis.
  @Test
  void computeRealizedPnl_groupsPerOptionSymbol() {
    stub(
        "BUY",
        filled("BUY", "AAA   260703C00010000", 1, "1.00"),
        filled("BUY", "BBB   260703C00020000", 1, "5.00"));
    stub("SELL", filled("SELL", "AAA   260703C00010000", 1, "1.50"));

    // Only AAA exits: (1.50-1.00)*1*100 = 50. BBB entry is open, contributes nothing.
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", DAY))
        .isEqualByComparingTo(new BigDecimal("50.00"));
  }

  // No FILLED rows -> zero.
  @Test
  void computeRealizedPnl_noRows_returnsZero() {
    stub("BUY");
    stub("SELL");
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", DAY))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ---------- C3: FIFO parity with the BFF RealizedPnlCalculator.realizePerSymbol ----------
  // The BFF method is static package-private in a DIFFERENT module, so we share the FIXTURE DATA
  // (the exact lots from RealizedPnlCalculatorUnitTest) rather than the method, and assert this
  // module's copy returns the identical per-contract BigDecimal for each case.

  private static Deque<Lot> lots(Lot... ls) {
    return new ArrayDeque<>(List.of(ls));
  }

  private static Lot lot(String price, long qty) {
    return new Lot(new BigDecimal(price), qty);
  }

  @Test
  void fifoParity_fullMatch() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(lots(lot("1.00", 2)), lots(lot("1.50", 2))))
        .isEqualByComparingTo("1.00");
  }

  @Test
  void fifoParity_partialExit() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(lots(lot("1.00", 5)), lots(lot("1.50", 2))))
        .isEqualByComparingTo("1.00");
  }

  @Test
  void fifoParity_multipleEntriesFifo() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(
                lots(lot("1.00", 1), lot("2.00", 1)), lots(lot("3.00", 2))))
        .isEqualByComparingTo("3.00");
  }

  @Test
  void fifoParity_exitWithNoEntry_phantomGain() {
    assertThat(DailyPnlExecActivityImpl.realizePerSymbol(lots(), lots(lot("2.00", 1))))
        .isEqualByComparingTo("2.00");
  }

  @Test
  void fifoParity_dramLiveCase() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(lots(lot("2.3533", 3)), lots(lot("1.84", 2))))
        .isEqualByComparingTo("-1.0266");
  }
}
