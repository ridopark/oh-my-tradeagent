package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.CapitalAllocator;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class StrategyActivitiesImpl implements StrategyActivities {

  private final StrategyRegistry registry;
  private final CapitalAllocator capitalAllocator;

  public StrategyActivitiesImpl(StrategyRegistry registry, CapitalAllocator capitalAllocator) {
    this.registry = registry;
    this.capitalAllocator = capitalAllocator;
  }

  @Override
  public StrategyConfig get(String tenantId, String strategyId) {
    return registry.get(tenantId, strategyId);
  }

  @Override
  public BigDecimal capitalForStrategy(String tenantId, String strategyId) {
    return capitalAllocator.capitalForStrategy(tenantId, strategyId);
  }
}
