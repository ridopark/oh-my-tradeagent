package com.ohmytradeagent.contract.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the promotion-voiding key set. The failure this prevents is not a wrong answer — it is two
 * components giving DIFFERENT answers, which is invisible to the operator and halts real trading
 * behind a healthy-looking admin page (2026-08-15; see {@link RiskRelevantConfigKeys}).
 */
class RiskRelevantConfigKeysTest {

  @Test
  void repegCeilingPctVoidsAPromotionButRepegAfterMsDoesNot() {
    // The asymmetry is deliberate and load-bearing. repeg_ceiling_pct raises the max price payable
    // on a real-money entry, so an edit must force a re-Activate. repeg_after_ms is the emergency
    // OFF-SWITCH: if disabling the re-peg also voided the promotion, the fastest way to stop the
    // feature would also halt all live entries — the operator would be choosing between two
    // outages.
    assertThat(RiskRelevantConfigKeys.ALL).contains("repeg_ceiling_pct");
    assertThat(RiskRelevantConfigKeys.ALL).doesNotContain("repeg_after_ms");
  }

  @Test
  void deadDailyLossThresholdDoesNotVoidAPromotion() {
    // Reversing a drift toward the STRICTER side is the wrong instinct when the stricter side
    // guards nothing. single-account-loss-rule Phase 4a made the per-strategy daily_loss_threshold
    // dead (the account cap is the sole breaker), so voiding a promotion on it forces a re-Activate
    // for zero safety gain — and a voided promotion fails live BTOs closed until an operator acts.
    // The orchestrator's exclusion is the deliberate position, pinned by
    // AuditQueryLivePromotionIT#dailyLossThresholdConfigChangedAfterApproval_returnsValid; the
    // BFF's inclusion was residue. This test exists so it cannot drift back in unnoticed — those
    // two ITs asserted opposite answers for months without either failing, because both are
    // RUN_DB_ITS-gated in separate modules.
    assertThat(RiskRelevantConfigKeys.ALL).doesNotContain("daily_loss_threshold");
  }

  @Test
  void coversEveryDangerousAndExposureFieldClass() {
    // These are the StrategyConfigWriter DANGEROUS/EXPOSURE fields. A promotion must not survive a
    // change to any of them.
    assertThat(RiskRelevantConfigKeys.ALL)
        .contains(
            "broker_target",
            "notional_cap_pct_of_capital_base",
            "max_contracts",
            "min_contracts",
            "max_positions",
            "capital_weight",
            "max_notional_per_signal",
            "max_daily_notional_deployed");
  }

  @Test
  void sqlArrayLiteralIsDeterministicAndInjectionSafe() {
    // Inlined into plain SQL (jOOQ treats every `?` as a bind, so `?|` misparses), so the shape
    // matters: sorted for a stable diff, single-quoted, cast to text[] for jsonb_exists_any.
    String literal = RiskRelevantConfigKeys.sqlArrayLiteral();

    assertThat(literal).isEqualTo(RiskRelevantConfigKeys.sqlArrayLiteral());
    assertThat(literal).startsWith("ARRAY['").endsWith("']::text[]");
    assertThat(literal).contains("'repeg_ceiling_pct'");
    // No key may carry a quote or backslash — these are inlined, not bound.
    assertThat(RiskRelevantConfigKeys.ALL).allSatisfy(k -> assertThat(k).matches("[a-z0-9_]+"));
  }
}
