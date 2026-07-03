package com.ohmytradeagent.tdbff.platform;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Read-only accessor for a strategy's {@code broker_target} from the orchestrator-owned {@code
 * strategy_config} table. The BFF only needs this one stable scalar to pick the exec datasource /
 * broker task queue.
 *
 * <p>Fail-soft by design: a missing row or a config blob without {@code broker_target} returns
 * {@code null} rather than throwing. The {@code strategy_config} table is not in the BFF's
 * generated jOOQ (the BFF does not own that schema), so plain {@link DSL#field}/{@link DSL#table}
 * refs are used. No {@code schema_version} check — the BFF reads one forward-compatible scalar, and
 * a version gate here would violate the read-only surface's fail-soft contract.
 */
@Component
public class DbStrategyConfigReader {

  private final DSLContext orchestratorDsl;

  public DbStrategyConfigReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /**
   * The {@code broker_target} (e.g. {@code alpaca-paper}) for a (tenant, strategy), or {@code null}
   * when no row exists or the config carries no {@code broker_target}. Returning {@code null} means
   * the read-only surface degrades for that strategy — this method never throws.
   */
  public String brokerTarget(String tenantId, String strategyId) {
    Record1<String> row =
        orchestratorDsl
            .select(DSL.field("config->>'broker_target'", String.class))
            .from(DSL.table("strategy_config"))
            .where(DSL.field("tenant_id").eq(tenantId).and(DSL.field("strategy_id").eq(strategyId)))
            .fetchOne();
    return row == null ? null : row.value1();
  }

  /**
   * Every distinct {@code (tenant_id, strategy_id, broker_target)} present in {@code
   * strategy_config}, ordered for a stable result. Mirrors the orchestrator's {@code
   * DbStrategyRegistry.list()} enumeration source (no {@code tenants} table) but also carries the
   * per-row {@code broker_target} so the operator list need not re-query it per strategy. A row
   * whose config has no {@code broker_target} yields a {@code null} target (fail-soft, never
   * thrown); the caller decides how to render it.
   */
  public List<TenantStrategyBrokerTarget> listAll() {
    return orchestratorDsl
        .select(
            DSL.field("tenant_id", String.class),
            DSL.field("strategy_id", String.class),
            DSL.field("config->>'broker_target'", String.class))
        .from(DSL.table("strategy_config"))
        .orderBy(DSL.field("tenant_id"), DSL.field("strategy_id"))
        .fetch()
        .map(r -> new TenantStrategyBrokerTarget(r.value1(), r.value2(), r.value3()));
  }

  /**
   * True iff at least one {@code strategy_config} row exists for {@code tenantId} — i.e. the tenant
   * is real. Used by the operator create-invite endpoint to reject an invite for an unknown tenant
   * BEFORE any write. Read-only against the orchestrator DSL, like the rest of this reader.
   */
  public boolean tenantExists(String tenantId) {
    return orchestratorDsl.fetchExists(
        DSL.selectOne()
            .from(DSL.table("strategy_config"))
            .where(DSL.field("tenant_id", String.class).eq(tenantId)));
  }

  /** One enumerated strategy and its configured {@code broker_target} (may be {@code null}). */
  public record TenantStrategyBrokerTarget(
      String tenantId, String strategyId, String brokerTarget) {}
}
