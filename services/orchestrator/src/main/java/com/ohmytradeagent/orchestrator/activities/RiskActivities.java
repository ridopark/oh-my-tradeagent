package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;

@ActivityInterface
public interface RiskActivities {

  /**
   * Legacy entry-point retained for the v=DEFAULT_VERSION branch of {@code
   * CopytradeSignalWorkflowImpl.handleBto} so pre-#111 in-flight workflows continue to replay
   * deterministically. New workflow executions take the v>=1 branch and route through {@link
   * #checkEntryWithLimit} so the notional-cap + buying-power gates see the slip-adjusted limit
   * rather than the unadjusted mirror premium. Do not call from new code.
   *
   * <p>{@code preTradeResult} may be null when the gate is disabled or was not run; {@code
   * checkPreTradeCheck} handles null, fail-closed sentinel, and success cases.
   */
  RiskDecision checkEntry(
      CopytradeSignalPayload payload, StrategyConfig config, PreTradeCheckResult preTradeResult);

  /**
   * Issue #198: slip-adjusted variant of {@link #checkEntry}. {@code limit} is the BTO max-cost
   * computed by {@code BtoPricing.computeBtoLimit(...)} in the workflow body and threaded through
   * to both the {@code notional_cap_pct_of_equity} gate and the {@code pre_trade_check}
   * buying-power compare so a snug cap can no longer be passed on the optimistic mirror price.
   *
   * <p>Additive on the {@code @ActivityInterface}: Temporal derives the activity type from the
   * capitalised method name ({@code CheckEntryWithLimit}), so the existing {@code CheckEntry}
   * activity type is unchanged — recorded histories that scheduled it still replay deterministic.
   *
   * <p>{@code limit} should be non-null in production (v>=1 callers always pass {@code
   * priced.limit()}). The implementation falls back to {@code payload.getPrice()} when {@code
   * limit} is null to keep the unit-test surface ergonomic.
   */
  RiskDecision checkEntryWithLimit(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      PreTradeCheckResult preTradeResult,
      BigDecimal limit);

  /**
   * Throws a non-retryable {@code PreTradeCheckMisconfigured} {@link
   * io.temporal.failure.ApplicationFailure} when {@code pre_trade_check} is enabled but only the
   * permissive default bean is wired; otherwise returns normally. Workflow must call this before
   * any cross-service {@code PreTradeCheckActivity} dispatch.
   */
  void assertPreTradeCheckRoutable(StrategyConfig config);
}
