package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.StrategyConfig;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * P0a (multi-tenant-broker-credentials epic): a {@link StrategyRegistry} backed by the {@code
 * strategy_config} table (V5). Reads the row for {@code (tenant_id, strategy_id)} and deserializes
 * its JSONB {@code config} blob into a {@link StrategyConfig}.
 *
 * <p><b>NOT the active bean in P0a.</b> Guarded behind {@code @ConditionalOnProperty(name =
 * "strategy.config.source", havingValue = "db")} (default OFF), so it is only constructed when an
 * operator explicitly opts in. With the flag absent/unset, {@link YamlStrategyRegistry} remains the
 * sole {@link StrategyRegistry} bean and stays wired into {@code StrategyActivities} — the live
 * signal path is unchanged. P0b flips the default so the DB wins.
 *
 * <p>Fail-closed posture: a row whose {@code schema_version} exceeds this build's supported version
 * throws rather than parse a newer-than-build blob — mirroring the workflow-input schema_version
 * guards (see {@code AdoptionWorkflowImpl}). A missing row throws {@link
 * YamlStrategyRegistry.StrategyNotFoundException}, matching the YAML registry's not-found behavior
 * so a reader swap is transparent to callers.
 */
@Component
@ConditionalOnProperty(name = "strategy.config.source", havingValue = "db")
public class DbStrategyRegistry implements StrategyRegistry {

  /**
   * Highest {@code StrategyConfig} schema_version this build can deserialize. A stored row with a
   * higher value is rejected fail-closed (forces an orchestrator-svc redeploy rather than a silent
   * mis-parse). Currently 1 — the only shipped StrategyConfig schema version (see
   * contract/schemas/strategy-config.json and the workflow-input guards).
   */
  static final long MAX_SUPPORTED_SCHEMA_VERSION = 1L;

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  @Autowired
  public DbStrategyRegistry(DSLContext dsl, ObjectMapper objectMapper) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

  @Override
  public StrategyConfig get(String tenantId, String strategyId) {
    Record row =
        dsl.fetchOne(
            "SELECT schema_version, config::text AS config_text "
                + "FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
            tenantId,
            strategyId);

    if (row == null) {
      throw new YamlStrategyRegistry.StrategyNotFoundException(
          "Strategy config not found in DB for tenant=" + tenantId + " strategy=" + strategyId);
    }

    long storedSchemaVersion = row.get("schema_version", Long.class);
    if (storedSchemaVersion > MAX_SUPPORTED_SCHEMA_VERSION) {
      throw new IllegalStateException(
          "strategy_config schema_version "
              + storedSchemaVersion
              + " for tenant="
              + tenantId
              + " strategy="
              + strategyId
              + " exceeds build-supported "
              + MAX_SUPPORTED_SCHEMA_VERSION
              + " — refusing to parse a newer-than-build row (redeploy orchestrator-svc)");
    }

    String configJson = row.get("config_text", String.class);
    try {
      return objectMapper.readValue(configJson, StrategyConfig.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(
          "Failed to deserialize strategy_config.config for tenant="
              + tenantId
              + " strategy="
              + strategyId,
          e);
    }
  }
}
