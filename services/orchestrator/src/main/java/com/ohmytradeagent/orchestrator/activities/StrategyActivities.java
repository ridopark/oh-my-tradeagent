package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;

@ActivityInterface
public interface StrategyActivities {

  StrategyConfig get(String tenantId, String strategyId);

  BigDecimal capitalForStrategy(String tenantId, String strategyId);
}
