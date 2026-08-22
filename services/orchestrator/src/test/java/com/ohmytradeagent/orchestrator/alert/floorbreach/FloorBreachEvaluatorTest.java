package com.ohmytradeagent.orchestrator.alert.floorbreach;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Evaluation;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Status;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Issue #779 T1: the pure breach formula. The load-bearing rules: the comparison is {@code <=} on
 * the BID (never the mid — #690), the boundary is inclusive, and every quote-unavailable shape maps
 * to UNKNOWN — never OK (a monitoring failure must not read as "all clear") and never BREACH
 * (except the real bid-0-with-live-ask worthless case).
 */
class FloorBreachEvaluatorTest {

  private static final BigDecimal ENTRY = new BigDecimal("2.00");

  private static OptionQuote quote(String bid, String mid, String ask) {
    return new OptionQuote(
        bid == null ? null : new BigDecimal(bid),
        mid == null ? null : new BigDecimal(mid),
        ask == null ? null : new BigDecimal(ask));
  }

  @Test
  void bidExactlyAtTheFloorLine_isBreach_boundaryInclusive() {
    // entry 2.00, threshold 0.50 → floor line 1.00. bid == 1.00 exactly must BREACH.
    Evaluation e =
        FloorBreachEvaluator.evaluate(ENTRY, quote("1.00", "1.10", "1.20"), new BigDecimal("0.50"));
    assertThat(e.status()).isEqualTo(Status.BREACH);
    assertThat(e.lossPct()).isEqualByComparingTo("0.50");
    assertThat(e.step()).isEqualTo(50);
  }

  @Test
  void bidOneCentAboveTheLine_isOk() {
    Evaluation e =
        FloorBreachEvaluator.evaluate(ENTRY, quote("1.01", "1.10", "1.20"), new BigDecimal("0.50"));
    assertThat(e.status()).isEqualTo(Status.OK);
  }

  @Test
  void nullQuote_isUnknown_neverOk() {
    Evaluation e = FloorBreachEvaluator.evaluate(ENTRY, null, new BigDecimal("0.50"));
    assertThat(e.status()).isEqualTo(Status.UNKNOWN);
  }

  @Test
  void nullBid_isUnknown() {
    Evaluation e =
        FloorBreachEvaluator.evaluate(ENTRY, quote(null, "1.10", "1.20"), new BigDecimal("0.50"));
    assertThat(e.status()).isEqualTo(Status.UNKNOWN);
  }

  @Test
  void zeroBidWithNoAsk_deadBook_isUnknown() {
    // snapshotQuote is UNFILTERED by the #690 tick guard: a no-bid no-ask snapshot is
    // untrustworthy, not worthless.
    assertThat(
            FloorBreachEvaluator.evaluate(ENTRY, quote("0", "0", null), new BigDecimal("0.50"))
                .status())
        .isEqualTo(Status.UNKNOWN);
    assertThat(
            FloorBreachEvaluator.evaluate(ENTRY, quote("0", "0", "0"), new BigDecimal("0.50"))
                .status())
        .isEqualTo(Status.UNKNOWN);
  }

  @Test
  void zeroBidWithLiveAsk_isBreachAtMinus100() {
    // The real bled-to-worthless case (the INTC 50-lot in the counterfactual) must alert.
    Evaluation e =
        FloorBreachEvaluator.evaluate(ENTRY, quote("0", "0.03", "0.05"), new BigDecimal("0.50"));
    assertThat(e.status()).isEqualTo(Status.BREACH);
    assertThat(e.lossPct()).isEqualByComparingTo("1");
    assertThat(e.step()).isEqualTo(100);
  }

  @Test
  void nullThreshold_defaultsToFiftyPct() {
    // bid 0.99 < 1.00 (the default 0.50 line on entry 2.00) → BREACH without any threshold given.
    Evaluation breach = FloorBreachEvaluator.evaluate(ENTRY, quote("0.99", "1.10", "1.20"), null);
    assertThat(breach.status()).isEqualTo(Status.BREACH);
    Evaluation ok = FloorBreachEvaluator.evaluate(ENTRY, quote("1.50", "1.60", "1.70"), null);
    assertThat(ok.status()).isEqualTo(Status.OK);
  }

  @Test
  void midAboveTheLineWhileBidBelow_isStillBreach_bidGoverns() {
    // The #690 widening-book case: mid 1.40 sits above the 1.00 line, bid 0.60 sits below. The
    // exit path trades the bid, so the alert must fire.
    Evaluation e =
        FloorBreachEvaluator.evaluate(ENTRY, quote("0.60", "1.40", "2.20"), new BigDecimal("0.50"));
    assertThat(e.status()).isEqualTo(Status.BREACH);
    assertThat(e.lossPct()).isEqualByComparingTo("0.70");
    assertThat(e.step()).isEqualTo(70);
  }

  @Test
  void nullOrNonPositiveEntryPremium_isUnknown() {
    assertThat(FloorBreachEvaluator.evaluate(null, quote("1.00", "1.10", "1.20"), null).status())
        .isEqualTo(Status.UNKNOWN);
    assertThat(
            FloorBreachEvaluator.evaluate(BigDecimal.ZERO, quote("1.00", "1.10", "1.20"), null)
                .status())
        .isEqualTo(Status.UNKNOWN);
  }

  @Test
  void okAboveTheRearmBand_reportsRecovered_insideTheBandDoesNot() {
    // floor 1.00, band 1.10: bid 1.05 is OK but NOT recovered; bid 1.10 is recovered.
    Evaluation inBand =
        FloorBreachEvaluator.evaluate(ENTRY, quote("1.05", "1.10", "1.20"), new BigDecimal("0.50"));
    assertThat(inBand.status()).isEqualTo(Status.OK);
    assertThat(inBand.recoveredAboveBand()).isFalse();
    Evaluation recovered =
        FloorBreachEvaluator.evaluate(ENTRY, quote("1.10", "1.20", "1.30"), new BigDecimal("0.50"));
    assertThat(recovered.status()).isEqualTo(Status.OK);
    assertThat(recovered.recoveredAboveBand()).isTrue();
  }

  @Test
  void stepBucketsByTensAndCapsAt100() {
    assertThat(FloorBreachEvaluator.stepOf(new BigDecimal("0.50"))).isEqualTo(50);
    assertThat(FloorBreachEvaluator.stepOf(new BigDecimal("0.59"))).isEqualTo(50);
    assertThat(FloorBreachEvaluator.stepOf(new BigDecimal("0.60"))).isEqualTo(60);
    assertThat(FloorBreachEvaluator.stepOf(new BigDecimal("0.999"))).isEqualTo(90);
    assertThat(FloorBreachEvaluator.stepOf(BigDecimal.ONE)).isEqualTo(100);
  }
}
