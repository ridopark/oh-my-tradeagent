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

  /**
   * Plan-2B R-AB-3: the copytrade-v1 conservative caps (abs=0.05, pct=0.05) now ship in the YAML so
   * the entry crosses the spread within the willing-to-pay cap instead of exact-mirroring and
   * non-filling. SLIP_MIN takes the lesser of (price + abs) and (price * (1 + pct)). At price=2.30:
   * abs branch 2.35, pct branch 2.415 → 2.35 (abs wins). No market order; the cap is the bound.
   */
  @Test
  void copytradeConservativeCaps_returnsSlipMin_withinWillingToPayCap_issueRAB3() {
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.30"));
    StrategyConfig cfg = configWithSlippage(new BigDecimal("0.05"), new BigDecimal("0.05"));

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.SLIP_MIN);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("2.35"));
    assertThat(out.limit().scale()).isLessThanOrEqualTo(2);
  }

  /**
   * Plan-2B R-AB-3: MIRROR fidelity is preserved when both caps are unset (null) — a strategy that
   * has NOT opted into slippage still mirrors the author price exactly. Guards against the config
   * change in copytrade-v1.yaml accidentally being read as a global default.
   */
  @Test
  void mirrorStillValidWhenSlippageNull_issueRAB3() {
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.30"));
    StrategyConfig cfg = configWithSlippage(null, null);

    PricedLimit out = BtoPricing.computeBtoLimit(p, cfg);

    assertThat(out.strategy()).isEqualTo(Strategy.MIRROR);
    assertThat(out.limit()).isEqualByComparingTo(new BigDecimal("2.30"));
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

  // ---------------------------------------------------------------------------------------------
  // Re-peg ceiling + limit (PLAN-2026-08-04-bto-entry-repeg Phase 2).
  //
  // The incident these model: the entry limit is anchored to the signal's already-stale price and
  // never reaches toward the live market, so an option that has ticked past it expires unfilled.
  // computeBtoLimit stays the INITIAL peg (its coverage above is the no-regression guard); these
  // two helpers describe the single bounded re-peg toward the live ask.
  // ---------------------------------------------------------------------------------------------

  @Test
  void repegCeiling_appliesTenPercentDefaultWhenUnset() {
    // Unset means "use the default", NOT "disabled" — the feature ships active. The off-switch is
    // repeg_after_ms=0, resolved in the workflow, not here.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.39"));

    BigDecimal ceiling = BtoPricing.computeRepegCeiling(p, configWithSlippage(null, null));

    assertThat(ceiling).isEqualByComparingTo(new BigDecimal("2.63"));
  }

  @Test
  void repegCeiling_usesConfiguredPctWhenSet() {
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.39"));
    StrategyConfig cfg = configWithSlippage(null, null);
    cfg.setRepegCeilingPct(new BigDecimal("0.20"));

    assertThat(BtoPricing.computeRepegCeiling(p, cfg)).isEqualByComparingTo(new BigDecimal("2.87"));
  }

  @Test
  void repegCeiling_treatsZeroAsUnsetLikeTheSlippageCaps() {
    // Mirrors BtoPricing's existing null/ZERO equivalence so a parser-emitted "0.00" cannot
    // collapse the ceiling to the signal price and silently disable the re-peg.
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.39"));
    StrategyConfig cfg = configWithSlippage(null, null);
    cfg.setRepegCeilingPct(BigDecimal.ZERO);

    assertThat(BtoPricing.computeRepegCeiling(p, cfg)).isEqualByComparingTo(new BigDecimal("2.63"));
  }

  @Test
  void repegCeiling_isPennyRounded() {
    // 2.37 * 1.10 = 2.607 — a 3rd decimal Alpaca rejects with a non-retryable 422 (Issue #263).
    CopytradeSignalPayload p = payloadWithPrice(new BigDecimal("2.37"));

    BigDecimal ceiling = BtoPricing.computeRepegCeiling(p, configWithSlippage(null, null));

    assertThat(ceiling.scale()).isEqualTo(2);
    assertThat(ceiling).isEqualByComparingTo(new BigDecimal("2.61"));
  }

  @Test
  void repegLimit_stepsOnePennyAboveTheAsk_aaplIncident() {
    // AAPL 8/14 315C: limit 2.51 while the option was already 2.55-2.61 AT SUBMIT, so the order was
    // never marketable and expired. The re-peg reaches 2.56 and fills, inside the 2.63 ceiling.
    BigDecimal limit =
        BtoPricing.computeRepegLimit(
            new BigDecimal("2.55"), new BigDecimal("2.63"), new BigDecimal("2.51"));

    assertThat(limit).isEqualByComparingTo(new BigDecimal("2.56"));
  }

  @Test
  void repegLimit_isBoundedByTheCeiling_nvdaIncident() {
    // NVDA 8/10 212.5C ran 2.95 -> 3.25, which is +16% over the signal price. The re-peg stops at
    // the ceiling and declines to chase; filling only if the option trades back through it is the
    // bound working, not a gap.
    BigDecimal limit =
        BtoPricing.computeRepegLimit(
            new BigDecimal("3.25"), new BigDecimal("3.09"), new BigDecimal("2.95"));

    assertThat(limit).isEqualByComparingTo(new BigDecimal("3.09"));
  }

  @Test
  void repegLimit_nullWhenAskIsAtOrBelowTheInitialPeg() {
    // Nothing to gain: the standing order is already at least as marketable. Degrade to one-shot
    // rather than burn a cancel/replace round-trip.
    assertThat(
            BtoPricing.computeRepegLimit(
                new BigDecimal("2.40"), new BigDecimal("2.63"), new BigDecimal("2.51")))
        .isNull();
  }

  @Test
  void repegLimit_nullWhenTheCeilingIsAtOrBelowTheInitialPeg() {
    // A tenant configuring repeg_ceiling_pct BELOW max_slippage_pct must not re-peg DOWNWARD.
    assertThat(
            BtoPricing.computeRepegLimit(
                new BigDecimal("3.00"), new BigDecimal("2.45"), new BigDecimal("2.51")))
        .isNull();
  }

  @Test
  void repegLimit_nullWhenAskIsMissingOrNonPositive() {
    // Entry fail-safe: no live ask means no re-peg at all. Never buy at a cap that could not be
    // priced against a live market — the inverse of the exit path, where a missing quote degrades
    // to a marketable order because the position MUST be closed.
    BigDecimal ceiling = new BigDecimal("2.63");
    BigDecimal peg = new BigDecimal("2.51");

    assertThat(BtoPricing.computeRepegLimit(null, ceiling, peg)).isNull();
    assertThat(BtoPricing.computeRepegLimit(BigDecimal.ZERO, ceiling, peg)).isNull();
    assertThat(BtoPricing.computeRepegLimit(new BigDecimal("-1.00"), ceiling, peg)).isNull();
  }

  @Test
  void repegLimit_isPennyRoundedAndNeverExceedsTheCeiling() {
    // A 3dp ask (mid-derived quotes can carry one) must not leak a >2dp limit into placeOrder, and
    // rounding must not step over the ceiling.
    BigDecimal limit =
        BtoPricing.computeRepegLimit(
            new BigDecimal("2.555"), new BigDecimal("2.63"), new BigDecimal("2.51"));

    assertThat(limit.scale()).isEqualTo(2);
    assertThat(limit).isEqualByComparingTo(new BigDecimal("2.57"));
    assertThat(limit).isLessThanOrEqualTo(new BigDecimal("2.63"));
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
