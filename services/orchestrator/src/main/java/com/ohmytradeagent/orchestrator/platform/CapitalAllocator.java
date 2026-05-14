package com.ohmytradeagent.orchestrator.platform;

import java.math.BigDecimal;

public interface CapitalAllocator {
  BigDecimal capitalForStrategy(String tenantId, String strategyId);
}
