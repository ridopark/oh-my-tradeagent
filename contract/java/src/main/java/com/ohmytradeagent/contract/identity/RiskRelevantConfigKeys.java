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
          // Routes real orders to a SPECIFIC brokerage account, so a change re-points live trading
          // at different money — the same blast radius as broker_target, which sits beside it in
          // the DANGEROUS class. It was absent here while this javadoc claimed to mirror
          // DANGEROUS + EXPOSURE exactly, so the claim was false.
          //
          // Defense-in-depth rather than an active hole:
          // StrategyConfigWriter#requireDangerousUnchanged
          // already REJECTS any change (including null -> value) on the API path, so a
          // TenantConfigChanged carrying this key should not normally arise. It costs nothing and
          // makes the stated invariant true.
          //
          // The test that excluded daily_loss_threshold — "is the field dead?" — answers the
          // opposite way here: broker_account_id is load-bearing for real-money routing.
          "broker_account_id",
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
          "repeg_ceiling_pct");

  // DELIBERATELY ABSENT — daily_loss_threshold.
  //
  // It was the other half of the 2026-08-15 drift, in the opposite direction: present in the BFF's
  // copy, absent from the orchestrator's. The first instinct is to resolve a drift toward the
  // STRICTER side, and that is wrong here. single-account-loss-rule Phase 4a made the per-strategy
  // daily_loss_threshold a DEAD field — the account cap (account_daily_loss_pct) is the sole
  // daily-loss breaker, and nothing reads this key at runtime. A change to it cannot alter the risk
  // envelope, so voiding a promotion on it guards nothing and only forces a re-Activate.
  //
  // That is not free. A voided promotion fails live BTOs closed until an operator re-Activates, and
  // on 2026-08-15 exactly that sequence halted three real-money tenants. Gratuitous voiding
  // triggers are an availability risk with no matching safety gain.
  //
  // The orchestrator's exclusion was the DELIBERATE, documented, test-pinned position
  // (AuditQueryLivePromotionIT#dailyLossThresholdConfigChangedAfterApproval_returnsValid); the
  // BFF's inclusion was residue. Aligned to the deliberate side. Pinned by
  // RiskRelevantConfigKeysTest so it cannot drift back in unnoticed.

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
