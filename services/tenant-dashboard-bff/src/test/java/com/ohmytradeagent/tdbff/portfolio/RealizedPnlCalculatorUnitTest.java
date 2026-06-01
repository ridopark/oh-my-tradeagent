package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.portfolio.RealizedPnlCalculator.Lot;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of the FIFO match in {@link RealizedPnlCalculator#realizePerSymbol} — the
 * financial core — with hand-built lots, no Postgres. Per-contract P&L only (the ×100 multiplier is
 * applied later in {@code computeRealizedPnl}).
 */
class RealizedPnlCalculatorUnitTest {

  private static Deque<Lot> lots(Lot... ls) {
    return new ArrayDeque<>(List.of(ls));
  }

  private static Lot lot(String price, long qty) {
    return new Lot(new BigDecimal(price), qty);
  }

  @Test
  void fullMatch_realizesExitMinusEntryTimesQty() {
    var realized =
        RealizedPnlCalculator.realizePerSymbol(lots(lot("1.00", 2)), lots(lot("1.50", 2)));
    assertThat(realized).isEqualByComparingTo("1.00"); // (1.50 - 1.00) * 2
  }

  @Test
  void partialExit_matchesOnlyExitedQty_unexitedEntryContributesNothing() {
    var realized =
        RealizedPnlCalculator.realizePerSymbol(lots(lot("1.00", 5)), lots(lot("1.50", 2)));
    assertThat(realized).isEqualByComparingTo("1.00"); // (0.50) * 2; 3 remaining entries ignored
  }

  @Test
  void multipleEntries_matchFifoAcrossLots() {
    var entries = lots(lot("1.00", 1), lot("2.00", 1));
    var realized = RealizedPnlCalculator.realizePerSymbol(entries, lots(lot("3.00", 2)));
    assertThat(realized).isEqualByComparingTo("3.00"); // (3-1)*1 + (3-2)*1
  }

  @Test
  void exitWithNoEntry_pinsDocumentedPhantomGain() {
    // Issue #276 §4: a position entered on a prior day and closed today has no same-day cost basis,
    // so the exit credits raw proceeds (a phantom gain). Pin it so a future fix doesn't silently
    // regress.
    var realized = RealizedPnlCalculator.realizePerSymbol(lots(), lots(lot("2.00", 1)));
    assertThat(realized).isEqualByComparingTo("2.00"); // raw exit credit, no basis subtracted
  }
}
