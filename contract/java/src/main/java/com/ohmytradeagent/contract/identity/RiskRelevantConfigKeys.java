package com.ohmytradeagent.contract.identity;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The StrategyConfig keys whose change VOIDS a live promotion — the single source of truth for both
 * consumers.
 *
 * <p>Two independent copies of this set used to exist, and they drifted:
 *
 * <ul>
 *   <li>{@code AuditQueryActivitiesImpl} (orchestrator) — the TRADING GATE. A risk-relevant {@code
 *       TenantConfigChanged} after the approval makes {@code checkLivePromotion} return {@code
 *       CONFIG_CHANGED}, and the workflow then REFUSES every live BTO.
 *   <li>{@code LivePromotionStateReader} (tenant-dashboard-bff) — what the ADMIN UI RENDERS, and
 *       therefore whether the operator is offered "Activate live" or "Deactivate".
 * </ul>
 *
 * <p>When they disagree the failure is silent and operator-hostile in exactly one direction: a key
 * the gate treats as risk-relevant but the UI does not means live trading is HALTED while the admin
 * page still reads "● live — valid until &lt;date&gt;" and offers only Deactivate. The operator
 * cannot see the halt and has no button to clear it. That happened on 2026-08-15 with {@code
 * repeg_ceiling_pct}: it was added to the orchestrator's copy and not the BFF's, and all three
 * real-money tenants sat fail-closed with a green-looking admin page.
 *
 * <p>So this is not a tidy-up. Both consumers now read this constant, and the drift is
 * unrepresentable rather than merely discouraged.
 */
public final class RiskRelevantConfigKeys {

  private RiskRelevantConfigKeys() {}

  /**
   * Adding a key here TIGHTENS safety: edits to it will void live promotions and force a
   * re-Activate. Removing one loosens it — do that only with the same care as any other real-money
   * control change.
   */
  public static final Set<String> ALL =
      Set.of(
          // CORE — DANGEROUS (StrategyConfigWriter.checkFieldClasses)
          "broker_target",
          "notional_cap_pct_of_capital_base",
          // CORE — EXPOSURE (StrategyConfigWriter.checkFieldClasses)
          "max_contracts",
          "min_contracts",
          "max_positions",
          "capital_weight",
          "max_notional_per_signal",
          "max_daily_notional_deployed",
          // risk-manager additions
          "notional_cap_pct_of_equity",
          "same_underlying_count",
          "sector_concentration_cap",
          "daily_trade_count",
          "drawdown_velocity_threshold",
          // PLAN-2026-08-04-bto-entry-repeg: raises the MAX PRICE PAYABLE on a real-money entry
          // above the max_slippage_pct cap, the same category as the notional_cap_* keys.
          // repeg_after_ms is deliberately NOT here, so the emergency off-switch stays a fast edit
          // that does not void a promotion.
          "repeg_ceiling_pct",
          // Retained deliberately: the per-strategy daily-loss breaker is a dead field (the account
          // cap superseded it), but it stayed in the BFF's copy while the orchestrator dropped it.
          // Keeping it is the SAFE direction — an edit to a dead field merely forces a harmless
          // re-Activate, whereas dropping it would silently widen what can change under a live
          // promotion.
          "daily_loss_threshold");

  /**
   * The set rendered as a Postgres {@code text[]} literal for inlining into a plain-SQL {@code
   * jsonb_exists_any(target, text[])} call. Both consumers build the same predicate, and both need
   * it inlined: jOOQ plain SQL treats every {@code ?} as a JDBC bind, so the {@code ?|} operator
   * would misparse. The keys are compile-time constants (never user input), so inlining is
   * injection-safe. Sorted for a stable, diffable literal.
   */
  public static String sqlArrayLiteral() {
    return ALL.stream().sorted().collect(Collectors.joining("','", "ARRAY['", "']::text[]"));
  }
}
