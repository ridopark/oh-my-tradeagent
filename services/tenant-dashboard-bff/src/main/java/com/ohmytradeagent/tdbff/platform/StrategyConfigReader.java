package com.ohmytradeagent.tdbff.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * UI-P3-a read-only accessor for a tenant's full {@code strategy_config} rows (the editable config
 * + {@code version} for the future write path's optimistic CAS). Reads the orchestrator-owned
 * {@code strategy_config} table via {@code orchestratorDsl}; the BFF does not own that schema, so
 * plain {@code config::text} is read and parsed with Jackson rather than via generated jOOQ.
 *
 * <p>The {@code config} blob carries NO secret material — broker API keys/secrets live in the
 * separate {@code broker_credentials} table; {@code broker_account_id} is a non-secret brokerage
 * identifier. So the full config is safe to surface.
 *
 * <p>{@link #FIELD_CLASSES} is DISPLAY metadata mirroring {@code
 * orchestrator/.../StrategyConfigWriter}'s governance (IDENTITY / DANGEROUS / EXPOSURE; everything
 * unlisted is SAFE). It tells the UI which fields are read-only, dual-control-only, or tighten-only
 * — but it is NOT the enforcement point: {@code StrategyConfigWriter} re-checks every rule
 * server-side on write. Keep this map in sync with that class.
 */
@Component
public class StrategyConfigReader {

  /**
   * Field-class metadata mirroring {@code StrategyConfigWriter}. Any field NOT listed here is SAFE
   * (freely editable). IDENTITY + DANGEROUS render read-only; EXPOSURE renders tighten-only.
   */
  public static final Map<String, List<String>> FIELD_CLASSES =
      Map.of(
          "IDENTITY", List.of("tenant_id", "strategy_id", "schema_version"),
          "DANGEROUS",
              List.of(
                  "broker_target",
                  "broker_account_id",
                  "daily_loss_threshold",
                  "notional_cap_pct_of_capital_base"),
          "EXPOSURE",
              List.of(
                  "max_contracts",
                  "min_contracts",
                  "max_positions",
                  "capital_weight",
                  "max_notional_per_signal",
                  "max_daily_notional_deployed"));

  private final DSLContext orchestratorDsl;
  private final ObjectMapper objectMapper;

  public StrategyConfigReader(
      @Qualifier("orchestratorDsl") DSLContext orchestratorDsl, ObjectMapper objectMapper) {
    this.orchestratorDsl = orchestratorDsl;
    this.objectMapper = objectMapper;
  }

  /**
   * One row per strategy the tenant owns: {@code strategy_id}, the parsed {@code config} object,
   * and the row {@code version} (the write path's expected_version). Empty when the tenant has no
   * configured strategies.
   */
  public List<Map<String, Object>> configsForTenant(String tenantId) {
    return orchestratorDsl
        .fetch(
            "SELECT strategy_id, config::text AS config_text, version "
                + "FROM strategy_config WHERE tenant_id = ? ORDER BY strategy_id",
            tenantId)
        .stream()
        .map(this::row)
        .toList();
  }

  private Map<String, Object> row(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("strategy_id", r.get("strategy_id", String.class));
    m.put("version", r.get("version", Long.class));
    m.put("config", parseConfig(r.get("config_text", String.class)));
    return m;
  }

  private Map<String, Object> parseConfig(String json) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
      return parsed;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // A stored row that won't parse is a data-integrity problem, not a transient one. Surface it
      // as an empty object rather than throwing so one bad row can't 500 the whole read.
      return Map.of();
    }
  }
}
