package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.portfolio.RealizedPnlCalculator.Lot;
import com.ohmytradeagent.tdbff.portfolio.RealizedPnlCalculator.RealizedPnl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of the FIFO match in {@link RealizedPnlCalculator#realizePerSymbol} — the
 * financial core — with hand-built lots, no Postgres. Per-contract P&L only (the ×100 multiplier is
 * applied later in {@code computeRealizedPnl}).
 *
 * <p>{@code realizePerSymbol(entries, exits, targetDay)}: a null {@code targetDay} sums every exit
 * (all-time); a non-null {@code targetDay} sums only exits whose ET date equals it, while still
 * consuming entry lots for prior-day exits so FIFO reaches the correct remaining basis.
 */
class RealizedPnlCalculatorUnitTest {

  private static final LocalDate D1 = LocalDate.of(2026, 7, 21);
  private static final LocalDate D2 = LocalDate.of(2026, 7, 22);

  private static Deque<Lot> lots(Lot... ls) {
    return new ArrayDeque<>(List.of(ls));
  }

  // Entry lot: day is unused by the FIFO match (entries only supply cost basis).
  private static Lot entry(String price, long qty) {
    return new Lot(new BigDecimal(price), qty, null);
  }

  // Exit lot: day drives day-scoping.
  private static Lot exit(String price, long qty, LocalDate day) {
    return new Lot(new BigDecimal(price), qty, day);
  }

  @Test
  void fullMatch_realizesExitMinusEntryTimesQty() {
    var realized =
        RealizedPnlCalculator.realizePerSymbol(
            lots(entry("1.00", 2)), lots(exit("1.50", 2, D1)), null);
    assertThat(realized).isEqualByComparingTo("1.00"); // (1.50 - 1.00) * 2
  }

  @Test
  void partialExit_matchesOnlyExitedQty_unexitedEntryContributesNothing() {
    var realized =
        RealizedPnlCalculator.realizePerSymbol(
            lots(entry("1.00", 5)), lots(exit("1.50", 2, D1)), null);
    assertThat(realized).isEqualByComparingTo("1.00"); // (0.50) * 2; 3 remaining entries ignored
  }

  @Test
  void multipleEntries_matchFifoAcrossLots() {
    var entries = lots(entry("1.00", 1), entry("2.00", 1));
    var realized = RealizedPnlCalculator.realizePerSymbol(entries, lots(exit("3.00", 2, D1)), null);
    assertThat(realized).isEqualByComparingTo("3.00"); // (3-1)*1 + (3-2)*1
  }

  @Test
  void exitWithNoEntry_pinsDocumentedPhantomGain_countedOnlyOnItsExitDay() {
    // Remaining #276 §4 limitation: an exit whose entry pre-dates retained history (no matching
    // entry lot anywhere) still credits raw proceeds. But it is now attributed to its OWN exit day:
    // counted on D2, and NOT counted for a different target day (D1).
    var noEntry = lots();
    assertThat(RealizedPnlCalculator.realizePerSymbol(noEntry, lots(exit("2.00", 1, D2)), D2))
        .isEqualByComparingTo("2.00"); // raw exit credit on its exit day
    assertThat(RealizedPnlCalculator.realizePerSymbol(lots(), lots(exit("2.00", 1, D2)), D1))
        .isEqualByComparingTo("0"); // not this day -> not counted
    assertThat(RealizedPnlCalculator.realizePerSymbol(lots(), lots(exit("2.00", 1, D2)), null))
        .isEqualByComparingTo("2.00"); // all-time still credits raw proceeds
  }

  @Test
  void crossDayEntryAndExit_allTimeMatchesRealCostBasis_noPhantomGain() {
    // ALL-TIME (#276 §4): entry on day 1, exit on day 2. Full history is fetched, so the day-2 exit
    // FIFO-matches the day-1 entry basis -> (exit - entry) * qty, NOT phantom raw proceeds.
    var entries = lots(entry("1.00", 2)); // day 1 entry
    var exits = lots(exit("1.50", 2, D2)); // day 2 exit
    var realized = RealizedPnlCalculator.realizePerSymbol(entries, exits, null);
    assertThat(realized).isEqualByComparingTo("1.00"); // (1.50 - 1.00) * 2, real basis applied
  }

  @Test
  void crossDayExit_dayScopedMatchesRealBasis_notPhantomProceeds_incidentReproduction() {
    // prod_real 2026-07-22 AAPL 260727C00330000: BUY 50 @ 1.99 on D1; SELL 39 across D1
    // (15@2.25, 11@2.46, 8@2.8875, 5@3.99); then SELL 11 @ 1.88 on D2 against the 11 remaining
    // @ 1.99 basis. Day-scoped to D2 counts ONLY the D2 exit = (1.88 - 1.99) * 11 = -1.21 (×100 ->
    // -$121), NOT the phantom raw proceeds 1.88 * 11 (×100 -> $2,068).
    var entries = lots(entry("1.99", 50));
    var exits =
        lots(
            exit("2.25", 15, D1),
            exit("2.46", 11, D1),
            exit("2.8875", 8, D1),
            exit("3.99", 5, D1),
            exit("1.88", 11, D2));

    // Day-scoped to D2: only the cross-day exit counts, against its REAL 1.99 basis.
    assertThat(RealizedPnlCalculator.realizePerSymbol(entries, exits, D2))
        .isEqualByComparingTo("-1.21"); // NOT 20.68 (2068 / 100 phantom)

    // Fresh deques (the previous call drained them); all-time is unchanged (sum of every exit).
    var entriesAll = lots(entry("1.99", 50));
    var exitsAll =
        lots(
            exit("2.25", 15, D1),
            exit("2.46", 11, D1),
            exit("2.8875", 8, D1),
            exit("3.99", 5, D1),
            exit("1.88", 11, D2));
    // 0.26*15 + 0.47*11 + 0.8975*8 + 2.00*5 + (-0.11)*11 = 3.90+5.17+7.18+10.00-1.21 = 25.04
    assertThat(RealizedPnlCalculator.realizePerSymbol(entriesAll, exitsAll, null))
        .isEqualByComparingTo("25.04");
  }

  @Test
  void sameDayRoundTrip_realizesCorrectlyOnThatDay() {
    // Buy + sell same day (D2): day-scoped to D2 matches its own basis (no cross-day involved).
    var entries = lots(entry("2.00", 3));
    var exits = lots(exit("1.50", 2, D2));
    assertThat(RealizedPnlCalculator.realizePerSymbol(entries, exits, D2))
        .isEqualByComparingTo("-1.00"); // (1.50 - 2.00) * 2
  }

  @Test
  void realizeBoth_yieldsTodayAndAllTimeFromOnePass_matchingSeparateCalls() {
    // The consolidated single-pass (RealizedPnlCalculator#computeRealized delegates to realizeBoth)
    // returns BOTH figures from ONE fetch + ONE FIFO walk. Same incident fixture as the day-scoped
    // test above (AAPL 260727C00330000): today (D2) = (1.88 - 1.99) * 11 = -1.21 ×100 -> -$121;
    // all-time = every exit = 25.04 ×100 -> $2,504. realizeBoth applies the ×100 multiplier, so the
    // record carries dollars (unlike the per-contract realizePerSymbol above).
    String occ = "AAPL260727C00330000";
    // The FIFO walk only polls the ENTRY deque; it iterates exits without consuming them, so this
    // one exits deque is reused across all three calls (fresh entry lots each time).
    Deque<Lot> exits =
        lots(
            exit("2.25", 15, D1),
            exit("2.46", 11, D1),
            exit("2.8875", 8, D1),
            exit("3.99", 5, D1),
            exit("1.88", 11, D2));

    RealizedPnl both =
        RealizedPnlCalculator.realizeBoth(
            Map.of(occ, lots(entry("1.99", 50))), Map.of(occ, exits), D2);

    assertThat(both.today()).isEqualByComparingTo("-121"); // FIFO loss, NOT the +2068 phantom
    assertThat(both.allTime()).isEqualByComparingTo("2504"); // 25.04 ×100, the full sum
    // Consistent with driving the day-scoped and all-time static walks separately (pre-×100).
    assertThat(RealizedPnlCalculator.realizePerSymbol(lots(entry("1.99", 50)), exits, D2))
        .isEqualByComparingTo("-1.21");
    assertThat(RealizedPnlCalculator.realizePerSymbol(lots(entry("1.99", 50)), exits, null))
        .isEqualByComparingTo("25.04");
  }

  @Test
  void dramLiveCase_partialExitBelowCost_realizesNetLoss() {
    // Mirrors the live prod_real 2026-06-29 data: bought 3 DRAM @ 2.3533, sold 2 @ 1.84. The 2 sold
    // contracts FIFO-match the 2.3533 basis -> per-contract (1.84 - 2.3533) * 2 = -1.0266; with the
    // ×100 multiplier applied in computeRealizedPnl that is -$102.66 (the documented -102.67
    // figure,
    // off by a rounding cent because the live cost basis carries more precision than 2.3533).
    var entries = lots(entry("2.3533", 3));
    var exits = lots(exit("1.84", 2, D1));
    var realized = RealizedPnlCalculator.realizePerSymbol(entries, exits, null);
    assertThat(realized).isEqualByComparingTo("-1.0266"); // ×100 later -> -102.66
  }
}
