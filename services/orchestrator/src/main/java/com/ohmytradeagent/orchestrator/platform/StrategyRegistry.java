package com.ohmytradeagent.orchestrator.platform;

import com.ohmytradeagent.contract.StrategyConfig;

public interface StrategyRegistry {
  StrategyConfig get(String tenantId, String strategyId);
}
