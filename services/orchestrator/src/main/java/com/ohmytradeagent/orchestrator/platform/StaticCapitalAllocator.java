package com.ohmytradeagent.orchestrator.platform;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StaticCapitalAllocator implements CapitalAllocator {

  private final BigDecimal capitalPerStrategy;

  public StaticCapitalAllocator(
      @Value("${orchestrator.capital.per-strategy:100000}") BigDecimal capitalPerStrategy) {
    this.capitalPerStrategy = capitalPerStrategy;
  }

  @Override
  public BigDecimal capitalForStrategy(String tenantId, String strategyId) {
    return capitalPerStrategy;
  }
}
