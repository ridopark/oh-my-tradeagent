package com.ohmytradeagent.tdbff.platform;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Resolves a tenant's full strategy set from the orchestrator-owned {@code strategy_config} table.
 * Auth yields only a {@code tenant_id}, but every read scopes on {@code (tenant_id, strategy_id)};
 * a tenant may own several strategies, so the BFF aggregates across all of them (mirroring how
 * {@code VisibilityPortfolioSnapshot} widens its cap basis to the tenant's whole book). Reads fresh
 * each call so an operator adding a strategy does not require a restart.
 */
@Component
public class TenantStrategyResolver {

  private final DSLContext orchestratorDsl;

  public TenantStrategyResolver(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /** Strategy ids owned by {@code tenantId}, ordered ascending. Empty if the tenant has none. */
  public List<String> strategyIdsForTenant(String tenantId) {
    return orchestratorDsl
        .select(DSL.field("strategy_id", String.class))
        .from(DSL.table("strategy_config"))
        .where(DSL.field("tenant_id").eq(tenantId))
        .orderBy(DSL.field("strategy_id").asc())
        .fetch(DSL.field("strategy_id", String.class));
  }
}
