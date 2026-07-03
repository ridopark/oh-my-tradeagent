package com.ohmytradeagent.apigateway.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) exec hop: {@code DELETE
 * /internal/broker-credentials} (Phase 1). Mirrors {@link BrokerCredentialForwardService}'s exec
 * forward (shared {@code execRestClient}, per-request {@code X-Tenant-Id}, the exec admin bearer as
 * a default header). Idempotent on the exec side (0 rows = success); a non-2xx or transport fault
 * throws so the orchestration reports a 207 {@code TenantDeleteStepFailed} rather than a false
 * success.
 *
 * <p>Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class BrokerCredentialDeleteForwarder {

  private static final Logger log = LoggerFactory.getLogger(BrokerCredentialDeleteForwarder.class);

  private final RestClient execRestClient;

  public BrokerCredentialDeleteForwarder(RestClient execRestClient) {
    this.execRestClient = execRestClient;
  }

  /**
   * Deletes the tenant's stored credentials for {@code provider}; returns rows-deleted (0 = ok).
   */
  public int delete(String tenant, String provider) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("provider", provider);
    return execRestClient
        .method(HttpMethod.DELETE)
        .uri("/internal/broker-credentials")
        .header("X-Tenant-Id", tenant)
        .body(body)
        .exchange(
            (request, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (!status.is2xxSuccessful()) {
                throw new IllegalStateException(
                    "exec broker-credentials delete returned " + status.value());
              }
              Map<?, ?> parsed = response.bodyTo(Map.class);
              Object deleted = parsed == null ? null : parsed.get("deleted");
              return deleted instanceof Number n ? n.intValue() : 0;
            },
            false);
  }
}
