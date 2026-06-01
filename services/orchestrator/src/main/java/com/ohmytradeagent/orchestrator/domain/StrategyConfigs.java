package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.StrategyConfig;

/** Pure predicates over {@link StrategyConfig}. Determinism-safe — callable from workflow code. */
public final class StrategyConfigs {

  private StrategyConfigs() {}

  /**
   * Issue #336: the single source of truth for "is the notional-cap gate configured". True when
   * EITHER cap field is set — the canonical {@code notional_cap_pct_of_capital_base} OR the
   * DEPRECATED alias {@code notional_cap_pct_of_equity}. This mirrors {@code
   * RiskActivitiesImpl#resolveNotionalCapPct}, which returns a non-null cap fraction in exactly
   * those cases and null only when BOTH fields are null. The workflow's AccountSnapshot-dispatch
   * guard and the resolver MUST agree on enablement: if the guard skips the cash dispatch while the
   * resolver still resolves a cap, {@code checkNotionalCap} rejects every entry with {@code
   * cash_unavailable} (the #336 regression). Keep the two in lockstep through this predicate.
   */
  public static boolean notionalCapConfigured(StrategyConfig config) {
    return config.getNotionalCapPctOfCapitalBase() != null
        || config.getNotionalCapPctOfEquity() != null;
  }
}
