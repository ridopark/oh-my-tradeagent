package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.BtoPricing.PricedLimit;
import com.ohmytradeagent.orchestrator.domain.BtoPricing.Strategy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link BtoPricing#computeBtoLimit}.
 *
 * <p>Determinism note: {@link BigDecimal#add} and {@link BigDecimal#multiply} have JDK-spec'd scale
 * arithmetic (no {@link java.math.MathContext} required), so results replay identically across JDK
 * versions. Tests compare values via {@code compareTo == 0} (per plan halt-condition 4) to avoid
 * flaking on scale mismatches (e.g. {@code 3.15} vs {@code 3.150}).
 */
class BtoPricingTest {

  @Test
  void bothCapsNull_returnsMirrorOfPayloadPrice() {
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithSlippage(null, null);

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.MIRROR);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("3.10"));
  }

  @Test
  void bothCapsZero_returnsMirrorOfPayloadPrice() {
    // Back-compat guard: parser-emitted "0.00" values must collapse to MIRROR identically to null
    // so today's behavior is bit-exact preserved for in-flight workflows.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithSlippage(BigDecimal.ZERO, BigDecimal.ZERO);

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.MIRROR);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("3.10"));
  }

  @Test
  void onlyAbsSet_returnsSlipAbs() {
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithSlippage(new BigDecimal("0.05"), null);

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_ABS);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("3.15"));
  }

  @Test
  void onlyPctSet_returnsSlipPctRoundedToPennyTick() {
    // 3.10 * (1 + 0.05) = 3.255, rounded HALF_UP to a penny tick -> 3.26 (Alpaca rejects >2 dp,
    // Issue #263). The pre-#263 assertion enshrined the un-rounded 3.255 bug; it is corrected here.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithSlippage(null, new BigDecimal("0.05"));

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_PCT);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("3.26"));
    assertThat(out.limit().scale()).isLessThanOrEqualTo(2);
  }

  @Test
  void pctMultiplyLandingOn3dp_roundsToPennyTick_issue263() {
    // Issue #263 regression: the live PLUG280121C00007000 BTO died because 1.35 * 1.10 = 1.485
    // (3 dp) drew a non-retryable Alpaca 422 ("limit price must be limited to 2 decimal places"),
    // killing the CopytradeSignalWorkflow with NO order placed. The limit must round HALF_UP to a
    // broker-accepted penny tick: 1.485 -> 1.49.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("1.35"));
    StrategyConfig cfg = configWithSlippage(null, new BigDecimal("0.10"));

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_PCT);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("1.49"));
    assertThat(out.limit().scale()).isLessThanOrEqualTo(2);
  }

  @Test
  void bothSet_returnsSlipMinChoosingSmaller_incidentFixture() {
    // Issue #191 sanity case: price=3.10, abs=0.05, pct=0.05
    //   abs branch: 3.10 + 0.05 = 3.15
    //   pct branch: 3.10 * 1.05 = 3.255
    //   min       : 3.15 (abs wins)
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithSlippage(new BigDecimal("0.05"), new BigDecimal("0.05"));

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_MIN);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("3.15"));
  }

  @Test
  void bothSet_pctWinsWhenSmaller() {
    // price=10.00, abs=1.00, pct=0.05
    //   abs branch: 10.00 + 1.00 = 11.00
    //   pct branch: 10.00 * 1.05 = 10.50
    //   min       : 10.50 (pct wins)
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("10.00"));
    StrategyConfig cfg = configWithSlippage(new BigDecimal("1.00"), new BigDecimal("0.05"));

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_MIN);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("10.50"));
  }

  @Test
  void bothSet_tieBreakChoosesAbsBranchDeterministically() {
    // Tie-break: when price + abs == price * (1 + pct), the helper returns the abs branch.
    // Documented choice (KISS): `a.compareTo(b) <= 0 ? a : b` collapses ties to the first arg
    // (abs). This keeps the strategy tag SLIP_MIN (both caps are set) and makes replay results
    // deterministic regardless of scale differences between the two candidates.
    //
    // Fixture: price=10.00, abs=0.50, pct=0.05  ->  10.50 == 10.50.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("10.00"));
    StrategyConfig cfg = configWithSlippage(new BigDecimal("0.50"), new BigDecimal("0.05"));

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_MIN);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("10.50"));
  }

  @Test
  void absSetPctZero_treatsPctAsUnset() {
    // Mixed: abs=0.05, pct=0.00 — the zero pct collapses to "unset", so strategy is SLIP_ABS
    // (not SLIP_MIN). Matches the null/zero equivalence guarantee.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("3.10"));
    StrategyConfig cfg = configWithSlippage(new BigDecimal("0.05"), BigDecimal.ZERO);

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_ABS);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("3.15"));
  }

  @Test
  void wireKeys_areStableContractRegardlessOfJavaEnumRenames() {
    // Pins the audit-subject wire-format contract: a Java rename (e.g. SLIP_MIN → SlipMin) must
    // NOT silently change what downstream consumers parse. The decoupling lives in wireKey().
    assertThat(Strategy.MIRROR.wireKey()).isEqualTo("mirror");
    assertThat(Strategy.SLIP_ABS.wireKey()).isEqualTo("slip_abs");
    assertThat(Strategy.SLIP_PCT.wireKey()).isEqualTo("slip_pct");
    assertThat(Strategy.SLIP_MIN.wireKey()).isEqualTo("slip_min");
  }

  private static CopytradeSignalPayload payloadWithPrice(BigDecimal price) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setPrice(price);
    return p;
  }

  private static StrategyConfig configWithSlippage(BigDecimal abs, BigDecimal pct) {
    StrategyConfig c = new StrategyConfig();
    c.setMaxSlippageAbs(abs);
    c.setMaxSlippagePct(pct);
    return c;
  }
}
