package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.Sizing.SizingOutcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SizingWithDecidersTest {

  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final BigDecimal ZERO = BigDecimal.ZERO;

  @Test
  void neutralMultipliers_matchAllThreeSizingPaths() {
    record Case(BigDecimal weight, long min, long max, BigDecimal capital, BigDecimal premium) {}
    // Cases where raw >= min, so the copytrade min-clamp never engages and the three paths agree.
    // The raw-below-min divergence (decider SKIPs, copytrade floors up) is covered separately.
    Case[] cases = {
      new Case(new BigDecimal("0.2"), 1L, 100L, new BigDecimal("100000"), new BigDecimal("2.30")),
      new Case(new BigDecimal("0.5"), 1L, 10L, new BigDecimal("10000"), new BigDecimal("10")),
      new Case(new BigDecimal("0.2"), 1L, 5L, new BigDecimal("100000"), new BigDecimal("0.05")),
    };
    for (Case c : cases) {
      StrategyConfig cfg = config(c.weight(), c.min(), c.max());
      CopytradeSignalPayload p = new CopytradeSignalPayload();
      p.setPrice(c.premium());

      long legacy = Sizing.computeContracts(p, cfg, c.capital(), c.premium());
      long overload = Sizing.computeContracts(cfg, c.capital(), c.premium());
      SizingOutcome neutral =
          Sizing.computeContractsWithDeciders(cfg, c.capital(), c.premium(), ONE, ONE);

      assertThat(overload).isEqualTo(legacy);
      assertThat(neutral.skip()).isFalse();
      assertThat(neutral.contracts()).isEqualTo(legacy);
    }
  }

  @Test
  void armMultiplierZero_skips() {
    StrategyConfig cfg = config(new BigDecimal("0.2"), 1L, 100L);
    SizingOutcome o =
        Sizing.computeContractsWithDeciders(
            cfg, new BigDecimal("100000"), new BigDecimal("2.30"), ZERO, ONE);

    assertThat(o.skip()).isTrue();
    assertThat(o.contracts()).isZero();
    assertThat(o.reason()).isEqualTo("decider-zero");
  }

  @Test
  void fireMultiplierZero_skips() {
    StrategyConfig cfg = config(new BigDecimal("0.2"), 1L, 100L);
    SizingOutcome o =
        Sizing.computeContractsWithDeciders(
            cfg, new BigDecimal("100000"), new BigDecimal("2.30"), ONE, ZERO);

    assertThat(o.skip()).isTrue();
    assertThat(o.reason()).isEqualTo("decider-zero");
  }

  @Test
  void rawBelowMinContracts_skipsRatherThanFlooringUp() {
    // capital=10_000 * weight=0.01 = allocation=100; premium=50 -> per contract=$5000
    // raw = floor(100 / 5000) = 0 ; min=1 -> must SKIP (not floored up to 1)
    StrategyConfig cfg = config(new BigDecimal("0.01"), 1L, 5L);
    SizingOutcome o =
        Sizing.computeContractsWithDeciders(
            cfg, new BigDecimal("10000"), new BigDecimal("50.00"), ONE, ONE);

    assertThat(o.skip()).isTrue();
    assertThat(o.contracts()).isZero();
    assertThat(o.reason()).isEqualTo("below-min");
  }

  @Test
  void rawWithinRange_returnedAsIs() {
    // allocation=5000, per contract=$1000 -> raw=5, within [1,10]
    StrategyConfig cfg = config(new BigDecimal("0.5"), 1L, 10L);
    SizingOutcome o =
        Sizing.computeContractsWithDeciders(
            cfg, new BigDecimal("10000"), new BigDecimal("10"), ONE, ONE);

    assertThat(o.skip()).isFalse();
    assertThat(o.contracts()).isEqualTo(5);
  }

  @Test
  void rawAboveMax_clampedToMax() {
    // allocation=20_000, per contract=$230 -> raw=86, max=5 -> clamp to 5
    StrategyConfig cfg = config(new BigDecimal("0.2"), 1L, 5L);
    SizingOutcome o =
        Sizing.computeContractsWithDeciders(
            cfg, new BigDecimal("100000"), new BigDecimal("2.30"), ONE, ONE);

    assertThat(o.skip()).isFalse();
    assertThat(o.contracts()).isEqualTo(5);
  }

  private StrategyConfig config(BigDecimal weight, long min, long max) {
    StrategyConfig c = new StrategyConfig();
    c.setCapitalWeight(weight);
    c.setMinContracts(min);
    c.setMaxContracts(max);
    return c;
  }
}
