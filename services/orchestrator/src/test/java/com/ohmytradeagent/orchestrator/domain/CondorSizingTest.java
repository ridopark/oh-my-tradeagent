package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CondorSizingTest {

  private static final BigDecimal STATIC_BASE = new BigDecimal("100000");
  private static final BigDecimal TICK = new BigDecimal("0.01");

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
            CondorSizing.size(
                    config("0.05", 1, 1), STATIC_BASE, legs(), new BigDecimal("1.11"), TICK)
                .contracts())
        .isEqualTo(1L);
  }

  @Test
  void raisedMaxContracts_sizesByMaxRisk() {
    // 100k × 0.05 = 5000; worst-case credit 1.11 − 2×0.01 = 1.09 → max risk (3 − 1.09) × 100 =
    // 191 → floor(26.18) = 26.
    CondorSizing.Result r =
        CondorSizing.size(config("0.05", 1, 50), STATIC_BASE, legs(), new BigDecimal("1.11"), TICK);

    assertThat(r.contracts()).isEqualTo(26L);
    assertThat(r.maxRiskPerContract()).isEqualByComparingTo("191");
    assertThat(r.allocation()).isEqualByComparingTo("5000");
  }

  @Test
  void sizesAgainstTheWalkFloorCredit_notTheMid() {
    // 102060 × 0.05 = 5103 = 27 × 189: at the MID (risk 189) this would be 27, but the walk can
    // fill down to 1.09 (risk 191) → floor(26.72) = 26, so realized max risk never exceeds the
    // allocation.
    assertThat(
            CondorSizing.size(
                    config("0.05", 1, 50),
                    new BigDecimal("102060"),
                    legs(),
                    new BigDecimal("1.11"),
                    TICK)
                .contracts())
        .isEqualTo(26L);
  }

  @Test
  void clampsToMaxContracts() {
    assertThat(
            CondorSizing.size(
                    config("0.05", 1, 9), STATIC_BASE, legs(), new BigDecimal("1.11"), TICK)
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
            CondorSizing.size(
                    config("0.05", 2, 50), STATIC_BASE, legs(), new BigDecimal("3.00"), TICK)
                .contracts())
        .isEqualTo(2L);
  }

  @Test
  void allocationBelowOneContractsRisk_clampsToMin() {
    // 1000 × 0.05 = 50 < 189.
    assertThat(
            CondorSizing.size(
                    config("0.05", 1, 50),
                    new BigDecimal("1000"),
                    legs(),
                    new BigDecimal("1.11"),
                    TICK)
                .contracts())
        .isEqualTo(1L);
  }

  @Test
  void noCapitalBase_clampsToMin() {
    assertThat(
            CondorSizing.size(config("0.05", 1, 50), null, legs(), new BigDecimal("1.11"), TICK)
                .contracts())
        .isEqualTo(1L);
    assertThat(
            CondorSizing.size(
                    config("0.05", 1, 50), BigDecimal.ZERO, legs(), new BigDecimal("1.11"), TICK)
                .contracts())
        .isEqualTo(1L);
  }
}
