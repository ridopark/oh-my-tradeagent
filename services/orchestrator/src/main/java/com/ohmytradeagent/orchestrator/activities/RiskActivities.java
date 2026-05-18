package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface RiskActivities {

  /**
   * {@code preTradeResult} may be null when the gate is disabled or was not run; {@code
   * checkPreTradeCheck} handles null, fail-closed sentinel, and success cases.
   */
  RiskDecision checkEntry(
      CopytradeSignalPayload payload, StrategyConfig config, PreTradeCheckResult preTradeResult);

  /**
   * Throws a non-retryable {@code PreTradeCheckMisconfigured} {@link
   * io.temporal.failure.ApplicationFailure} when {@code pre_trade_check} is enabled but only the
   * permissive default bean is wired; otherwise returns normally. Workflow must call this before
   * any cross-service {@code PreTradeCheckActivity} dispatch.
   */
  void assertPreTradeCheckRoutable(StrategyConfig config);
}
