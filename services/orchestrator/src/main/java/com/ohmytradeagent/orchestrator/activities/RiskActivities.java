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
