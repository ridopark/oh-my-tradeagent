package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CondorSettlementTest {

  private static CondorLeg leg(String side, String type, int strike) {
    return new CondorLeg("XSP-" + side + type + strike, side, type, strike, null, null);
  }

  private static List<CondorLeg> condor(int longPut, int shortPut, int shortCall, int longCall) {
    return List.of(
        leg("sell", "C", shortCall),
        leg("sell", "P", shortPut),
        leg("buy", "C", longCall),
        leg("buy", "P", longPut));
  }

  // Max loss = (wider wing - credit) x 100 x qty: the settlement debit is capped at the wing width.
  @Test
  void maxLoss_isWiderWingMinusCreditTimesQty() {
    // put wing 599-596 = 3, call wing 604-601 = 3; credit 0.80 -> (3 - 0.80) x 100 x 2 = 440.
    assertThat(CondorSettlement.maxLoss(condor(596, 599, 601, 604), new BigDecimal("0.80"), 2L))
        .isEqualByComparingTo("440");
  }

  @Test
  void maxLoss_asymmetricWings_usesTheWiderWing() {
    // put wing 599-595 = 4, call wing 603-601 = 2 -> (4 - 1.00) x 100 = 300.
    assertThat(CondorSettlement.maxLoss(condor(595, 599, 601, 603), new BigDecimal("1.00"), 1L))
        .isEqualByComparingTo("300");
  }

  // Agrees with the settlement arithmetic at a spot beyond a wing (the worst case).
  @Test
  void maxLoss_equalsSettlementLossBeyondAWing() {
    List<CondorLeg> legs = condor(596, 599, 601, 604);
    BigDecimal credit = new BigDecimal("0.80");
    BigDecimal worst = CondorSettlement.settle(legs, credit, 1L, 650.0).pnl().negate();
    assertThat(CondorSettlement.maxLoss(legs, credit, 1L)).isEqualByComparingTo(worst);
  }

  @Test
  void maxLoss_creditAtOrAboveWidth_isZeroNeverAGain() {
    assertThat(CondorSettlement.maxLoss(condor(596, 599, 601, 604), new BigDecimal("3.50"), 1L))
        .isEqualByComparingTo("0");
  }

  @Test
  void maxLoss_missingLeg_throws() {
    List<CondorLeg> threeLegs = condor(596, 599, 601, 604).subList(0, 3);
    assertThatThrownBy(() -> CondorSettlement.maxLoss(threeLegs, BigDecimal.ONE, 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
