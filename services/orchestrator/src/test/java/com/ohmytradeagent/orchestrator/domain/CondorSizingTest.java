package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CondorSizingTest {

  private static final BigDecimal STATIC_BASE = new BigDecimal("100000");

  /** 601/604 calls, 599/596 puts: both wings 3 wide. */
  private static List<CondorLeg> legs() {
    return List.of(
        new CondorLeg("XSP   261005C00601000", "sell", "C", 601, null, null),
        new CondorLeg("XSP   261005P00599000", "sell", "P", 599, null, null),
        new CondorLeg("XSP   261005C00604000", "buy", "C", 604, null, null),
        new CondorLeg("XSP   261005P00596000", "buy", "P", 596, null, null));
  }

  private static StrategyConfig config(String weight, long min, long max) {
    return new StrategyConfig()
        .withCapitalWeight(new BigDecimal(weight))
        .withCapitalSource(StrategyConfig.CapitalSource.STATIC)
        .withMinContracts(min)
        .withMaxContracts(max);
  }

  @Test
  void deployedStagingPaperConfig_staysOneLot() {
    // staging_paper/gated_condor as deployed: weight 0.05, static, min 1, max 1.
    assertThat(
            CondorSizing.size(config("0.05", 1, 1), STATIC_BASE, legs(), new BigDecimal("1.11"))
                .contracts())
        .isEqualTo(1L);
  }

  @Test
  void raisedMaxContracts_sizesByMaxRisk() {
    // 100k × 0.05 = 5000; max risk (3 − 1.11) × 100 = 189 → floor(26.45) = 26.
    CondorSizing.Result r =
        CondorSizing.size(config("0.05", 1, 50), STATIC_BASE, legs(), new BigDecimal("1.11"));

    assertThat(r.contracts()).isEqualTo(26L);
    assertThat(r.maxRiskPerContract()).isEqualByComparingTo("189");
    assertThat(r.allocation()).isEqualByComparingTo("5000");
  }

  @Test
  void clampsToMaxContracts() {
    assertThat(
            CondorSizing.size(config("0.05", 1, 9), STATIC_BASE, legs(), new BigDecimal("1.11"))
                .contracts())
        .isEqualTo(9L);
  }

  @Test
  void asymmetricWings_useTheWiderOne() {
    List<CondorLeg> asym =
        List.of(
            new CondorLeg("C601", "sell", "C", 601, null, null),
            new CondorLeg("P599", "sell", "P", 599, null, null),
            new CondorLeg("C603", "buy", "C", 603, null, null),
            new CondorLeg("P594", "buy", "P", 594, null, null));
    assertThat(CondorSizing.maxWingWidth(asym)).isEqualByComparingTo("5");
  }

  @Test
  void creditAtOrAboveWidth_clampsToMin() {
    assertThat(
            CondorSizing.size(config("0.05", 2, 50), STATIC_BASE, legs(), new BigDecimal("3.00"))
                .contracts())
        .isEqualTo(2L);
  }

  @Test
  void allocationBelowOneContractsRisk_clampsToMin() {
    // 1000 × 0.05 = 50 < 189.
    assertThat(
            CondorSizing.size(
                    config("0.05", 1, 50), new BigDecimal("1000"), legs(), new BigDecimal("1.11"))
                .contracts())
        .isEqualTo(1L);
  }

  @Test
  void noCapitalBase_clampsToMin() {
    assertThat(
            CondorSizing.size(config("0.05", 1, 50), null, legs(), new BigDecimal("1.11"))
                .contracts())
        .isEqualTo(1L);
    assertThat(
            CondorSizing.size(
                    config("0.05", 1, 50), BigDecimal.ZERO, legs(), new BigDecimal("1.11"))
                .contracts())
        .isEqualTo(1L);
  }
}
