package com.ohmytradeagent.apigateway.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase B1 copytrade fan-out registry. {@code GET /internal/copytrade-fanout-targets} returns the
 * {@code (tenant_id, strategy_id)} set of ENABLED copytrade tenants so the signal sidecar (Phase
 * B2, not yet a consumer) can fan out dynamically instead of from a hardcoded env list.
 *
 * <p>Plain jOOQ read against the orchestrator {@code strategy_config} table (same DB api-gateway
 * already reads for {@code /audit}). No contract schema / pydantic — the response is a plain JSON
 * {@code Map}, mirroring the BFF admin reads.
 *
 * <p><b>"Enabled" = {@code enabled} true OR absent.</b> The predicate {@code (config->>'enabled')
 * IS DISTINCT FROM 'false'} matches the schema default ({@code true}) and the runtime gate in
 * {@code CopytradeSignalWorkflowImpl}, which rejects only {@code Boolean.FALSE}. The {@code
 * strategy_id} is a configurable bind parameter (default {@code copytrade-v1}), never a hardcoded
 * query literal.
 *
 * <p><b>Auth.</b> The caller is a SERVICE (the sidecar), not an operator, so this route is
 * bearer-gated by {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter} (shared service
 * token) rather than the {@code X-Operator-Id} allowlist — a missing/bad bearer is 401 before this
 * controller runs.
 *
 * <p><b>Dark by construction.</b> Gated on {@code copytrade.fanout.enabled=true}; with the flag
 * unset (repo default / homelab) the bean does not exist → the route 404s. NO repo manifest sets it
 * true; the cutover is a manual per-cluster operator action (Phase B3).
 */
@RestController
@RequestMapping("/internal/copytrade-fanout-targets")
@ConditionalOnProperty(name = "copytrade.fanout.enabled", havingValue = "true")
public class CopytradeFanoutController {

  private final DSLContext dsl;
  private final String strategyId;

  public CopytradeFanoutController(
      DSLContext dsl, @Value("${copytrade.fanout.strategy-id:copytrade-v1}") String strategyId) {
    this.dsl = dsl;
    this.strategyId = strategyId;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> targets() {
    List<Map<String, Object>> targets =
        dsl
            .select(DSL.field("tenant_id"), DSL.field("strategy_id"))
            .from(DSL.table("strategy_config"))
            .where(
                DSL.field("strategy_id")
                    .eq(strategyId)
                    // enabled true OR absent — exclude only an explicit enabled:false.
                    .and(DSL.condition("(config ->> 'enabled') is distinct from 'false'")))
            .fetch()
            .stream()
            .map(CopytradeFanoutController::target)
            .toList();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targets", targets);
    body.put("count", targets.size());
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> target(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tenant_id", r.get("tenant_id"));
    m.put("strategy_id", r.get("strategy_id"));
    return Collections.unmodifiableMap(m);
  }
}
