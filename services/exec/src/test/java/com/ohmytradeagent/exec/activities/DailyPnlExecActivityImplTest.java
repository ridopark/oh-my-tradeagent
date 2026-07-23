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
 * Phase 2 (kill-switch realized re-source) + cross-day fix (PLAN-2026-07-22). Unit coverage of
 * {@link DailyPnlExecActivityImpl} against a mocked {@link OrderIntentJournal} (no Postgres): the
 * FIFO wiring, the C1 lost-audit self-heal, the C3 FIFO parity with the BFF {@code
 * RealizedPnlCalculator} fixture, the C7 same-day parity, and the cross-day phantom-proceeds fix (a
 * prior-day entry exited today FIFO-matches its real basis, not raw proceeds).
 */
class DailyPnlExecActivityImplTest {

  private static final LocalDate DAY = LocalDate.of(2026, 6, 29);
  // Cross-day incident dates (prod_real 2026-07-22 AAPL 260727C00330000).
  private static final LocalDate D1 = LocalDate.of(2026, 7, 21);
  private static final LocalDate D2 = LocalDate.of(2026, 7, 22);
  private static final String OCC = "DRAM  260703C00016000";

  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final DailyPnlExecActivityImpl activity = new DailyPnlExecActivityImpl(journal);

  // A FILLED row whose filled_at lands mid-day ET on `day` (14:00Z == 10:00 EDT, unambiguously
  // that day in America/New_York).
  private static JournaledOrder filledOn(
      String side, String occ, long qty, String price, LocalDate day) {
    OffsetDateTime filledAt = day.atTime(14, 0).atOffset(java.time.ZoneOffset.UTC);
    return new JournaledOrder(
        "intent-" + side + "-" + occ + "-" + qty + "-" + price + "-" + day,
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
        filledAt.minusSeconds(3),
        filledAt.minusSeconds(2),
        filledAt.minusSeconds(1),
        null,
        null,
        qty,
        new BigDecimal(price),
        filledAt,
        1L);
  }

  private static JournaledOrder filled(String side, String occ, long qty, String price) {
    return filledOn(side, occ, qty, price, DAY);
  }

  private void stub(String side, JournaledOrder... rows) {
    when(journal.findFilledBySide(eq("dev"), eq("copytrade-v1"), eq(side)))
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

  // ---------- Cross-day phantom-proceeds fix (PLAN-2026-07-22) ----------

  // THE INCIDENT (broker truth): prod_real 2026-07-22 AAPL 260727C00330000. BUY 50 @ 1.99 on D1;
  // SELL 39 across D1 (15@2.25, 11@2.46, 8@2.8875, 5@3.99); then SELL 11 @ 1.88 on D2 against the
  // 11 remaining @ 1.99 basis. Day-scoped to D2 counts ONLY the D2 exit = (1.88-1.99)*11*100 =
  // -$121, NOT the phantom raw proceeds 1.88*11*100 = +$2,068 (the figure the display showed).
  @Test
  void computeRealizedPnl_crossDayExit_matchesRealBasis_notPhantomProceeds() {
    String occ = "AAPL  260727C00330000";
    stub("BUY", filledOn("BUY", occ, 50, "1.99", D1));
    stub(
        "SELL",
        filledOn("SELL", occ, 15, "2.25", D1),
        filledOn("SELL", occ, 11, "2.46", D1),
        filledOn("SELL", occ, 8, "2.8875", D1),
        filledOn("SELL", occ, 5, "3.99", D1),
        filledOn("SELL", occ, 11, "1.88", D2));

    // Day-scoped to D2: only the cross-day exit counts, against its REAL 1.99 basis.
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", D2))
        .isEqualByComparingTo(new BigDecimal("-121.00")); // NOT +2068 phantom proceeds
  }

  // Cross-day GAIN unchanged: a prior-day position closed today at a genuine gain realizes the
  // smaller (S-E), NOT raw proceeds — the cap does not spuriously trip, but a real gain still
  // reads.
  @Test
  void computeRealizedPnl_crossDayGain_realizesMatchedGain_notRawProceeds() {
    String occ = "NVDA  260727C00140000";
    stub("BUY", filledOn("BUY", occ, 10, "1.00", D1));
    stub("SELL", filledOn("SELL", occ, 10, "1.50", D2));

    // D2 exit against D1's 1.00 basis: (1.50-1.00)*10*100 = +500. NOT the 1.50*10*100 = +1500 raw.
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", D2))
        .isEqualByComparingTo(new BigDecimal("500.00"));
  }

  // Same-day round-trip regression pin: buy+sell same day still realizes (S-E) exactly as before.
  @Test
  void computeRealizedPnl_sameDayRoundTrip_realizesMatchedPnl() {
    String occ = "TSLA  260727C00300000";
    stub("BUY", filledOn("BUY", occ, 3, "2.00", D2));
    stub("SELL", filledOn("SELL", occ, 2, "1.50", D2));

    // (1.50-2.00)*2*100 = -100.
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", D2))
        .isEqualByComparingTo(new BigDecimal("-100.00"));
  }

  // Pre-history residual preserved: an exit whose entry pre-dates fetched history still falls to
  // raw proceeds, counted only on its exit day.
  @Test
  void computeRealizedPnl_exitWithNoEntry_creditsRawProceeds_onlyOnExitDay() {
    String occ = "META  260727C00500000";
    stub("BUY"); // no entry in history
    stub("SELL", filledOn("SELL", occ, 1, "2.00", D2));

    // Counted on D2 (its exit day): raw 2.00*1*100 = +200.
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", D2))
        .isEqualByComparingTo(new BigDecimal("200.00"));
    // NOT counted on a different target day.
    assertThat(activity.computeRealizedPnl("dev", "copytrade-v1", D1))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ---------- C3: FIFO parity with the BFF RealizedPnlCalculator.realizePerSymbol ----------
  // The BFF method is static package-private in a DIFFERENT module, so we share the FIXTURE DATA
  // (the exact lots from RealizedPnlCalculatorUnitTest) rather than the method, and assert this
  // module's copy returns the identical per-contract BigDecimal for each case. Parity cases use the
  // all-time (null targetDay) path to sum every exit, matching the shared fixture semantics.

  private static Deque<Lot> lots(Lot... ls) {
    return new ArrayDeque<>(List.of(ls));
  }

  // Entry lot: day unused by the FIFO match. Exit lot on D2 (day matters only when targetDay set).
  private static Lot lot(String price, long qty) {
    return new Lot(new BigDecimal(price), qty, D2);
  }

  @Test
  void fifoParity_fullMatch() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(
                lots(lot("1.00", 2)), lots(lot("1.50", 2)), null))
        .isEqualByComparingTo("1.00");
  }

  @Test
  void fifoParity_partialExit() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(
                lots(lot("1.00", 5)), lots(lot("1.50", 2)), null))
        .isEqualByComparingTo("1.00");
  }

  @Test
  void fifoParity_multipleEntriesFifo() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(
                lots(lot("1.00", 1), lot("2.00", 1)), lots(lot("3.00", 2)), null))
        .isEqualByComparingTo("3.00");
  }

  @Test
  void fifoParity_exitWithNoEntry_phantomGain() {
    assertThat(DailyPnlExecActivityImpl.realizePerSymbol(lots(), lots(lot("2.00", 1)), null))
        .isEqualByComparingTo("2.00");
  }

  @Test
  void fifoParity_dramLiveCase() {
    assertThat(
            DailyPnlExecActivityImpl.realizePerSymbol(
                lots(lot("2.3533", 3)), lots(lot("1.84", 2)), null))
        .isEqualByComparingTo("-1.0266");
  }

  // Cross-day FIFO parity (mirrors RealizedPnlCalculatorUnitTest's incident reproduction): the
  // day-scoped D2 result matches the BFF impl exactly (-1.21 per-contract; ×100 -> -$121), and
  // the all-time sum is unchanged (25.04 per-contract).
  @Test
  void fifoParity_crossDayIncident() {
    var entries =
        lots(new Lot(new BigDecimal("1.99"), 50, null)); // D1 entry, day unused for entries
    var exits =
        lots(
            new Lot(new BigDecimal("2.25"), 15, D1),
            new Lot(new BigDecimal("2.46"), 11, D1),
            new Lot(new BigDecimal("2.8875"), 8, D1),
            new Lot(new BigDecimal("3.99"), 5, D1),
            new Lot(new BigDecimal("1.88"), 11, D2));
    assertThat(DailyPnlExecActivityImpl.realizePerSymbol(entries, exits, D2))
        .isEqualByComparingTo("-1.21"); // ×100 -> -$121

    var entriesAll = lots(new Lot(new BigDecimal("1.99"), 50, null));
    var exitsAll =
        lots(
            new Lot(new BigDecimal("2.25"), 15, D1),
            new Lot(new BigDecimal("2.46"), 11, D1),
            new Lot(new BigDecimal("2.8875"), 8, D1),
            new Lot(new BigDecimal("3.99"), 5, D1),
            new Lot(new BigDecimal("1.88"), 11, D2));
    assertThat(DailyPnlExecActivityImpl.realizePerSymbol(entriesAll, exitsAll, null))
        .isEqualByComparingTo("25.04");
  }
}
