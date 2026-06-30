package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;

@ActivityInterface
public interface RiskActivities {

  /**
   * Legacy entry-point retained for replay determinism of pre-#111 in-flight workflows. Notional is
   * computed against the unadjusted mirror price. Do not call from new code; new executions route
   * through {@link #checkEntryWithLimit} so the gates see the slip-adjusted max-cost.
   *
   * <p>{@code preTradeResult} may be null when the gate is disabled or was not run; {@code
   * checkPreTradeCheck} handles null, fail-closed sentinel, and success cases.
   */
  RiskDecision checkEntry(
      CopytradeSignalPayload payload, StrategyConfig config, PreTradeCheckResult preTradeResult);

  /**
   * Slip-adjusted variant of {@link #checkEntry}. {@code limit} is the BTO max-cost threaded into
   * both the {@code notional_cap_pct_of_equity} gate and the {@code pre_trade_check} buying-power
   * compare so a snug cap can no longer be passed on the optimistic mirror price.
   *
   * <p>{@code limit} should be non-null in production. The implementation falls back to {@code
   * payload.getPrice()} when {@code limit} is null to keep the unit-test surface ergonomic.
   *
   * <p>{@code accountCash} is the workflow-supplied account cash balance dispatched from the {@code
   * broker-<broker_target>} {@code AccountSnapshotActivity} (mirroring how {@code preTradeResult}
   * is threaded down). When non-null it feeds the {@code notional_cap_pct_of_equity} gate directly;
   * when null the gate falls back to {@link PortfolioSnapshot#accountEquity} keyed on {@code
   * broker_target} (the seam for tests / non-dispatch providers). The gate already fails closed on
   * a zero/missing figure.
   */
  RiskDecision checkEntryWithLimit(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      PreTradeCheckResult preTradeResult,
      BigDecimal limit,
      BigDecimal accountCash);

  /**
   * Watchlist-trigger entry gate: runs ONLY the strategy-agnostic risk gates (kill switch,
   * max_positions, and the Issue #6 portfolio stream) shared with {@link #checkEntryWithLimit}. The
   * copytrade-only pre-gates (author_whitelist, future-timestamp skew, max_signal_age) are NOT
   * applied — a {@link WatchlistTriggerPayload} carries no author and no posted-at timestamp.
   *
   * <p>{@code limit} is the BTO max-cost (option premium) threaded into the {@code
   * notional_cap_pct_of_equity} gate and the {@code pre_trade_check} buying-power compare,
   * mirroring {@link #checkEntryWithLimit}. {@code accountCash} feeds the notional-cap gate's
   * capital base and fails closed on null/zero.
   */
  RiskDecision checkWatchlistEntry(
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      PreTradeCheckResult preTradeResult,
      BigDecimal limit,
      BigDecimal accountCash);

  /**
   * Throws a non-retryable {@code PreTradeCheckMisconfigured} {@link
   * io.temporal.failure.ApplicationFailure} when {@code pre_trade_check} is enabled but only the
   * permissive default bean is wired; otherwise returns normally. Workflow must call this before
   * any cross-service {@code PreTradeCheckActivity} dispatch.
   */
  void assertPreTradeCheckRoutable(StrategyConfig config);

  /**
   * Narrow kill-switch-ONLY read covering BOTH the per-(tenant, strategy) {@link
   * com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow} and the tenant-wide {@link
   * com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow}. Returns a rejection when
   * EITHER scope is tripped or within cool-down, else {@code null} (clear). Unlike {@link
   * #checkWatchlistEntry} this applies NO max_positions / notional-cap / portfolio gates — it is
   * the inline cancel-on-filled adoption guard, where the lot already exists at the broker and a
   * position-count or notional refusal would wrongly defer a real position to reconciliation.
   *
   * <p>Fail-closed: any query failure (workflow-not-found, query rejection, timeout, null state)
   * surfaces as a {@link RiskDecision#rejected} with {@code KILL_SWITCH_UNAVAILABLE} so the caller
   * declines the inline adoption and lets recon re-confirm broker truth.
   */
  RiskDecision checkKillSwitchHalt(String tenantId, String strategyId);

  /**
   * Phase F4B (clamp-to-fit headroom): the largest contract count that fits the remaining
   * notional-cap headroom for this entry. {@code headroom = floor((cap - sumOpenNotional) / (limit
   * × 100))} where {@code cap = notional_cap_pct_of_capital_base × (cash + sumOpenNotional)} and
   * {@code sumOpenNotional} is the tenant-account-wide cost-basis sum from the same Visibility seam
   * {@link #checkEntryWithLimit}'s notional-cap gate reads.
   *
   * <p>The workflow composes this with its cash-weight sizing and {@code max_contracts} as {@code
   * MIN(cashSizing, headroom, max_contracts)}, then applies the {@code min_contracts} reject gate.
   * This activity owns ONLY the headroom math (it controls the {@code sumOpenNotional} seam); the
   * MIN-composition and the sub-minimum reject live in the workflow, gated behind the {@code
   * notional-cap-clamp-to-fit-v1} version marker.
   *
   * <ul>
   *   <li>cap not configured → {@link Long#MAX_VALUE} (no constraint; the MIN-composition no-ops).
   *   <li>cash null/zero (fail-closed, unavailable) → {@code 0} (no order would be sized).
   *   <li>over cap by fractions (remaining &lt; one contract) → {@code 0} (floored, never
   *       negative).
   * </ul>
   *
   * <p>Fail-closed parity with the gate: a {@link PortfolioSnapshot#openPositions} throw PROPAGATES
   * (it does not get swallowed into a permissive headroom).
   */
  long notionalCapHeadroomContracts(
      StrategyConfig config,
      BigDecimal limit,
      BigDecimal accountCash,
      String tenantId,
      String strategyId);
}
