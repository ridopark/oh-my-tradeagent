package com.ohmytradeagent.orchestrator.activities;

import java.util.List;
import org.jooq.DSLContext;

/**
 * DB-backed {@link TenantStrategies} (PLAN-2026-07-22). Enumerates a tenant's strategy ids with a
 * parameterized {@code SELECT DISTINCT strategy_id FROM strategy_config WHERE tenant_id = ?} — the
 * same {@code strategy_config} source order-routing, recon, and the kill-switch bootstrap already
 * use — instead of scanning the {@code tenants/} ConfigMap tree ({@link ScannerTenantStrategies}).
 * Same contract, same fresh-read-each-call semantics.
 *
 * <p><b>Why.</b> The account daily-loss cap resolves a tenant's {@code broker_target} (for the
 * SOD-equity read) and its loss basis through {@link TenantStrategies}. A DB-onboarded tenant
 * absent from the ConfigMap tree resolved to an EMPTY strategy set under {@link
 * ScannerTenantStrategies}, so the cap silently never armed (prod-kipark, 2026-07-21). Enumerating
 * from the DB arms the cap for any DB-onboarded tenant with no per-tenant tree patch.
 *
 * <p><b>Cross-tenant isolation.</b> The {@code WHERE tenant_id = ?} bind (never an unfiltered
 * distinct scan) guarantees only the requesting tenant's strategies are ever returned. The bind
 * matches {@code tenantId} byte-for-byte, so {@code prod_real} (underscore) and {@code prod-kipark}
 * (hyphen) resolve to their own rows.
 *
 * <p><b>Empty vs error (fail-loud vs fail-safe).</b> A SUCCESSFUL query that returns no rows (a
 * tenant genuinely absent from {@code strategy_config}) yields an empty list — the account-cap
 * consumers turn that into a fail-LOUD {@code broker_target_unresolved} defer (the structural
 * silent-unprotect, now observable). A DB read error (a jOOQ exception on a transient outage)
 * PROPAGATES — the caller defers quietly and retries, never tripping on an unknown loss base.
 */
public final class DbTenantStrategies implements TenantStrategies {

  private final DSLContext dsl;

  public DbTenantStrategies(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<String> strategyIdsForTenant(String tenantId) {
    return dsl.fetch(
            "SELECT DISTINCT strategy_id FROM strategy_config WHERE tenant_id = ? "
                + "ORDER BY strategy_id",
            tenantId)
        .map(r -> r.get("strategy_id", String.class));
  }
}
