package com.ohmytradeagent.orchestrator.platform;

import com.ohmytradeagent.contract.StrategyConfig;
import java.util.List;

public interface StrategyRegistry {
  StrategyConfig get(String tenantId, String strategyId);

  /**
   * Enumerates every {@code (tenantId, strategyId)} the registry knows about. The DB-backed
   * registry returns the {@code SELECT DISTINCT tenant_id, strategy_id FROM strategy_config} set
   * (the Phase-0 decided enumeration source — no {@code tenants} table); the YAML registry returns
   * the mounted {@code tenants/} ConfigMap scan. Feeds {@code TenantReconcileLoop} so a runtime row
   * insert is picked up without an orchestrator restart.
   */
  List<TenantStrategy> list();
}
