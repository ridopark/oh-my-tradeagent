package com.ohmytradeagent.orchestrator.activities;

@FunctionalInterface
public interface PositionCounter {
  long countOpen(String tenantId, String strategyId);
}
