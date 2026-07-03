package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.StrategyConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * A1 read-side helper: fetches the stored {@code (config, version)} for a {@code (tenant,
 * strategy)} from the orchestrator DB's {@code strategy_config} table (api-gateway already reads
 * that DB via {@code API_GATEWAY_DB_URL} — see {@link AuditController}). Used by the operator
 * enable route (to build the arming update from the current config + optimistic-concurrency
 * version) AND by the tenant-scoped route (to detect a {@code false→true} arming transition and
 * read the stored {@code broker_target} the arm-guard keys off).
 *
 * <p>The {@code config} column is a JSONB blob (the full {@link StrategyConfig}); it is cast to
 * text and deserialized with the shared {@link ObjectMapper}. Read-only — no mutation happens here.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.strategy-enable.enabled=true} OR {@code
 * strategy.config.write.enabled=true} — the two routes that need it.
 */
@Component
@ConditionalOnExpression(
    "${operator.strategy-enable.enabled:false} or ${strategy.config.write.enabled:false}"
        + " or ${operator.tenant-delete.enabled:false}")
public class StrategyConfigReader {

  private final DSLContext dsl;
  private final ObjectMapper mapper;

  public StrategyConfigReader(DSLContext dsl, ObjectMapper mapper) {
    this.dsl = dsl;
    this.mapper = mapper;
  }

  /** The stored config + optimistic-concurrency version, or empty when no row exists. */
  public Optional<Stored> read(String tenant, String strategy) {
    Record row =
        dsl.select(
                DSL.field("config").cast(String.class).as("config_json"),
                DSL.field("version", Long.class))
            .from(DSL.table("strategy_config"))
            .where(DSL.field("tenant_id").eq(tenant))
            .and(DSL.field("strategy_id").eq(strategy))
            .fetchOne();
    if (row == null) {
      return Optional.empty();
    }
    String json = row.get("config_json", String.class);
    Long version = row.get("version", Long.class);
    if (json == null || version == null) {
      // A row that cannot yield a coherent (config, version) is a corrupt store, not "absent" —
      // surface it as a runtime fault rather than a silent empty (which would read as NOT_FOUND).
      throw new IllegalStateException(
          "corrupt strategy_config row for tenant=" + tenant + " strategy=" + strategy);
    }
    try {
      StrategyConfig config = mapper.readValue(json, StrategyConfig.class);
      return Optional.of(new Stored(config, version));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(
          "unparseable strategy_config for tenant=" + tenant + " strategy=" + strategy, e);
    }
  }

  /**
   * Operator tenant-delete (PLAN-2026-07-03, Phase 4): ALL {@code (tenant, *)} strategy rows in a
   * SINGLE query — the one read that feeds P0 (LIVE/NON_PAPER/UNKNOWN broker_target shape) AND P2
   * (every strategy {@code enabled=false}). Ordered by {@code strategy_id} for a deterministic
   * scan.
   *
   * <p><b>Fail-closed on an unreadable row.</b> A missing {@code strategy_id}/{@code config}/{@code
   * version}, or a {@code config} that will not parse, throws {@link IllegalStateException} — the
   * caller (P0) must treat that as NOT deletable, never skip the row. An empty list (no rows) is a
   * legitimate {@code UNKNOWN_TENANT_SHAPE} the caller distinguishes.
   */
  public List<StrategyRow> listByTenant(String tenant) {
    var rows =
        dsl.select(
                DSL.field("strategy_id", String.class),
                DSL.field("config").cast(String.class).as("config_json"),
                DSL.field("version", Long.class))
            .from(DSL.table("strategy_config"))
            .where(DSL.field("tenant_id").eq(tenant))
            .orderBy(DSL.field("strategy_id"))
            .fetch();
    List<StrategyRow> out = new ArrayList<>();
    for (Record r : rows) {
      String strategyId = r.get("strategy_id", String.class);
      String json = r.get("config_json", String.class);
      Long version = r.get("version", Long.class);
      if (strategyId == null || json == null || version == null) {
        throw new IllegalStateException("corrupt strategy_config row for tenant=" + tenant);
      }
      try {
        StrategyConfig config = mapper.readValue(json, StrategyConfig.class);
        out.add(new StrategyRow(strategyId, config, version));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException(
            "unparseable strategy_config for tenant=" + tenant + " strategy=" + strategyId, e);
      }
    }
    return out;
  }

  /** The stored strategy config and its optimistic-concurrency version. */
  public record Stored(StrategyConfig config, long version) {}

  /**
   * One {@code (tenant, strategy)} row: its id, parsed config, and optimistic-concurrency version.
   */
  public record StrategyRow(String strategyId, StrategyConfig config, long version) {}
}
