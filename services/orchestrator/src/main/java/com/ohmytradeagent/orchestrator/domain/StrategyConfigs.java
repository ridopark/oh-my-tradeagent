package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.StrategyConfig;

/** Pure predicates over {@link StrategyConfig}. Determinism-safe — callable from workflow code. */
public final class StrategyConfigs {

  private StrategyConfigs() {}

  /**
   * Issue #336: the single source of truth for "is the notional-cap gate configured" — the
   * canonical {@code notional_cap_pct_of_capital_base} being set. The workflow's AccountSnapshot-
   * dispatch guard and {@code RiskActivitiesImpl#resolveNotionalCapPct} MUST agree on enablement:
   * if the guard skips the cash dispatch while the resolver still resolves a cap, {@code
   * checkNotionalCap} rejects every entry with {@code cash_unavailable} (the #336 regression). Keep
   * the two in lockstep through this predicate.
   *
   * <p>#338: the deprecated {@code notional_cap_pct_of_equity} alias was removed once every live
   * tenant had migrated. It is gone from the schema, so there is no second field to consult.
   */
  public static boolean notionalCapConfigured(StrategyConfig config) {
    return config.getNotionalCapPctOfCapitalBase() != null;
  }

  /**
   * True when capital-weight sizing should size from the broker account's live CASH balance rather
   * than the static global capital base. Back-compat: null/absent {@code capital_source} is treated
   * as {@code static} (the generated DTO also defaults the field to {@code static}), so this is
   * false unless the strategy explicitly opted into {@code account_cash}. The workflow uses this in
   * two lockstep places — the account-snapshot dispatch enablement (so cash is fetched even with no
   * notional cap) and the sizing-source switch — exactly as {@link #notionalCapConfigured} keeps
   * the dispatch guard and the notional-cap resolver in lockstep.
   */
  public static boolean accountCashSizing(StrategyConfig config) {
    return config.getCapitalSource() == StrategyConfig.CapitalSource.ACCOUNT_CASH;
  }
}
