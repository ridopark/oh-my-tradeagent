package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SizingTest {

  @Test
  void capitalWeightSizing_flooredAndClamped() {
    // capital=100_000, weight=0.2 -> allocation=20_000
    // price=2.30 -> per contract=$230
    // raw=20_000/230=86.95 -> floor=86 -> clamped by max=5 -> 5
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.30"));
    StrategyConfig cfg = configWithWeights(new BigDecimal("0.2"), 1L, 5L);

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("100000"), new BigDecimal("2.30"));

    assertThat(qty).isEqualTo(5);
  }

  @Test
  void cheapPremium_clampedByMaxContracts() {
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("0.05"));
    StrategyConfig cfg = configWithWeights(new BigDecimal("0.2"), 1L, 5L);

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("100000"), new BigDecimal("0.05"));

    assertThat(qty).isEqualTo(5);
  }

  @Test
  void expensivePremium_clampedByMinContracts() {
    // capital=10_000, weight=0.01 -> allocation=100
    // price=50 -> per contract=$5000
    // raw=100/5000=0 -> floor=0 -> clamped by min=1 -> 1
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("50.00"));
    StrategyConfig cfg = configWithWeights(new BigDecimal("0.01"), 1L, 5L);

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("10000"), new BigDecimal("50.00"));

    assertThat(qty).isEqualTo(1);
  }

  @Test
  void rawQuantityInsideRange_returnedAsIs() {
    // capital=10_000, weight=0.5 -> allocation=5000
    // price=10 -> per contract=$1000 -> raw=5
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("10"));
    StrategyConfig cfg = configWithWeights(new BigDecimal("0.5"), 1L, 10L);

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("10000"), new BigDecimal("10"));

    assertThat(qty).isEqualTo(5);
  }

  @Test
  void slipAdjustedLimit_dividesAllocationByLimitNotMirrorPrice() {
    // Issue #195: when slippage caps are set, sizing must divide by the slip-adjusted limit
    // (max-acceptable cost), not the mirror reference price. Use max=100 so the clamp doesn't
    // hide the difference:
    //   capital=100_000 * weight=0.2 = allocation=20_000
    //   mirror divisor: 3.10 * 100 = 310 -> 20_000 / 310 = 64.51 -> floor 64
    //   slip   divisor: 3.15 * 100 = 315 -> 20_000 / 315 = 63.49 -> floor 63
    // With the slip-adjusted limit threaded in, the result is 63 (the safer cap-aware sizing).
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithWeights(new BigDecimal("0.2"), 1L, 100L);
    cfg.setMaxSlippageAbs(new BigDecimal("0.05"));
    cfg.setMaxSlippagePct(new BigDecimal("0.05"));

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("100000"), new BigDecimal("3.15"));

    assertThat(qty).isEqualTo(63);
  }

  // ---------------------------------------------------------------------------
  // Scale-in entry-size reduction (PLAN-2026-07-28): when the BTO tail contains a
  // scale-in cue AND entry_scale_in_fraction is set, the sized count is multiplied
  // by the fraction and re-clamped up to min_contracts. Null fraction / no-cue tail
  // = full size. Only the payload-carrying overload applies it.
  // ---------------------------------------------------------------------------

  @Test
  void scaleInCue_withFraction_halvesTheSizedCount_incidentRepro() {
    // Incident: "BTO SPY 8/04 725p @ 3.09 risky, scaling in." entered FULL size.
    // capital=115_000, weight=1.0, limit=23.00 -> raw=115_000/2300=50 -> base=50.
    // fraction 0.5 -> floor(50*0.5)=25.
    CopytradeSignalPayload p = payloadWithTail(new BigDecimal("23.00"), "risky, scaling in");
    StrategyConfig cfg = configWithWeights(new BigDecimal("1.0"), 1L, 100L);
    cfg.setEntryScaleInFraction(new BigDecimal("0.5"));

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("115000"), new BigDecimal("23.00"));

    assertThat(qty).isEqualTo(25);
  }

  @Test
  void nullFraction_fullSize_evenWithScaleInCue() {
    CopytradeSignalPayload p = payloadWithTail(new BigDecimal("23.00"), "risky, scaling in");
    StrategyConfig cfg = configWithWeights(new BigDecimal("1.0"), 1L, 100L);
    // entry_scale_in_fraction left null (default OFF).

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("115000"), new BigDecimal("23.00"));

    assertThat(qty).isEqualTo(50);
  }

  @Test
  void tailWithoutCue_fullSize_evenWithFractionSet() {
    CopytradeSignalPayload p = payloadWithTail(new BigDecimal("23.00"), "risky, sending it");
    StrategyConfig cfg = configWithWeights(new BigDecimal("1.0"), 1L, 100L);
    cfg.setEntryScaleInFraction(new BigDecimal("0.5"));

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("115000"), new BigDecimal("23.00"));

    assertThat(qty).isEqualTo(50);
  }

  @Test
  void scaleInBelowMin_clampsUpToMinContracts() {
    // base=1, fraction 0.5 -> floor(0.5)=0 -> clamped up to min_contracts=1.
    // capital=10_000, weight=0.5 -> allocation=5000; limit=50 -> per contract=5000 -> raw=1.
    CopytradeSignalPayload p = payloadWithTail(new BigDecimal("50.00"), "starter");
    StrategyConfig cfg = configWithWeights(new BigDecimal("0.5"), 1L, 100L);
    cfg.setEntryScaleInFraction(new BigDecimal("0.5"));

    long qty = Sizing.computeContracts(p, cfg, new BigDecimal("10000"), new BigDecimal("50.00"));

    assertThat(qty).isEqualTo(1);
  }

  @Test
  void payloadFreeOverload_unchanged_ignoresScaleIn() {
    StrategyConfig cfg = configWithWeights(new BigDecimal("1.0"), 1L, 100L);
    cfg.setEntryScaleInFraction(new BigDecimal("0.5"));

    // The payload-free overload never reads a tail, so scale-in never applies: full 50.
    long qty = Sizing.computeContracts(cfg, new BigDecimal("115000"), new BigDecimal("23.00"));

    assertThat(qty).isEqualTo(50);
  }

  private CopytradeSignalPayload payloadWithTail(BigDecimal price, String tail) {
    CopytradeSignalPayload p = payloadWithPrice(price);
    p.setTail(tail);
    return p;
  }

  private CopytradeSignalPayload payloadWithPrice(BigDecimal price) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setPrice(price);
    return p;
  }

  private StrategyConfig configWithWeights(BigDecimal weight, long min, long max) {
    StrategyConfig c = new StrategyConfig();
    c.setCapitalWeight(weight);
    c.setMinContracts(min);
    c.setMaxContracts(max);
    return c;
  }
}
