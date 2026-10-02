package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.apigateway.config.ExecTargetProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * live-realtime-holdings: {@code GET /internal/live/fill-listener-status?tenant=<id>} — READ-ONLY
 * proxy of exec's {@code GET /status/fill-listener}, so the tenant-dashboard-bff never reaches exec
 * directly (exec's NetworkPolicy admits only api-gateway, and admitting the BFF would also expose
 * live exec's {@code /internal/broker-credentials}).
 *
 * <p><b>Routing.</b> Reuses the credential-write routing: {@link TenantBrokerTargetResolver}
 * (tenant → its single broker_target, fail-closed on absent/ambiguous) then {@code exec.targets}
 * ({@link ExecTargetProperties}, broker_target → exec base URL, no paper fallback).
 *
 * <p><b>Response (200).</b> exec's body narrowed to the caller's tenant: {@code {enabled, now,
 * broker_target, pod_tenant_count, tenants:[<this tenant's row, if any>]}} — row field names
 * unchanged. Other tenants' rows are never returned; {@code pod_tenant_count} (how many rows exec
 * reported) is kept because exec's confirmation metrics are pod-wide.
 *
 * <p><b>Fail-soft.</b> Never guesses: an unresolved/unmapped broker_target → 422; exec unreachable,
 * timed out (1.5s), non-2xx or malformed → 502, both with {@code {status:"unknown", reason}}.
 *
 * <p><b>Auth.</b> SERVICE caller → bearer-gated by {@link
 * com.ohmytradeagent.apigateway.security.ServiceTokenFilter} (API_GATEWAY_SHARED_TOKEN). Dark by
 * default: gated on {@code live.fill-listener-status.enabled=true}.
 */
@RestController
@ConditionalOnProperty(name = "live.fill-listener-status.enabled", havingValue = "true")
public class LiveFillListenerStatusController {

  private static final Logger log = LoggerFactory.getLogger(LiveFillListenerStatusController.class);
  static final Duration EXEC_TIMEOUT = Duration.ofMillis(1500);
  private static final String STATUS_PATH = "/status/fill-listener";
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final TenantBrokerTargetResolver brokerTargetResolver;
  private final ExecTargetProperties execTargets;
  private final TenantContext ctx;
  private final RestClient rest;

  @Autowired
  public LiveFillListenerStatusController(
      TenantBrokerTargetResolver brokerTargetResolver,
      ExecTargetProperties execTargets,
      TenantContext ctx) {
    this(brokerTargetResolver, execTargets, ctx, boundedClient());
  }

  LiveFillListenerStatusController(
      TenantBrokerTargetResolver brokerTargetResolver,
      ExecTargetProperties execTargets,
      TenantContext ctx,
      RestClient rest) {
    this.brokerTargetResolver = brokerTargetResolver;
    this.execTargets = execTargets;
    this.ctx = ctx;
    this.rest = rest;
  }

  private static RestClient boundedClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(EXEC_TIMEOUT);
    factory.setReadTimeout(EXEC_TIMEOUT);
    return RestClient.builder().requestFactory(factory).build();
  }

  @GetMapping("/internal/live/fill-listener-status")
  public ResponseEntity<Map<String, Object>> status(@RequestParam("tenant") String tenant) {
    if (!ctx.isValidTenantId(tenant)) {
      return unknown(HttpStatus.BAD_REQUEST, "invalid tenant", null);
    }
    Optional<String> brokerTarget = brokerTargetResolver.resolve(tenant);
    if (brokerTarget.isEmpty()) {
      return unknown(HttpStatus.UNPROCESSABLE_ENTITY, "broker_target unresolved", null);
    }
    String target = brokerTarget.get();
    String baseUrl = execTargets.getTargets().get(target);
    if (baseUrl == null || baseUrl.isBlank()) {
      return unknown(HttpStatus.UNPROCESSABLE_ENTITY, "broker_target not routable", target);
    }

    Map<String, Object> exec;
    try {
      exec = rest.get().uri(baseUrl + STATUS_PATH).retrieve().body(MAP_TYPE);
    } catch (RuntimeException e) {
      log.warn(
          "fill-listener status read failed tenant={} broker_target={} cause={}",
          tenant,
          target,
          e.getClass().getName());
      return unknown(HttpStatus.BAD_GATEWAY, "exec status read failed", target);
    }
    if (exec == null
        || !(exec.get("enabled") instanceof Boolean)
        || !(exec.get("tenants") instanceof List<?> rows)) {
      return unknown(HttpStatus.BAD_GATEWAY, "malformed exec status", target);
    }

    List<?> mine =
        rows.stream()
            .filter(r -> r instanceof Map<?, ?> m && tenant.equals(m.get("tenant_id")))
            .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", exec.get("enabled"));
    body.put("now", exec.get("now"));
    body.put("broker_target", target);
    body.put("pod_tenant_count", rows.size());
    body.put("tenants", mine);
    return ResponseEntity.ok(body);
  }

  private static ResponseEntity<Map<String, Object>> unknown(
      HttpStatus status, String reason, String brokerTarget) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "unknown");
    body.put("reason", reason);
    body.put("broker_target", brokerTarget);
    return ResponseEntity.status(status).body(body);
  }
}
