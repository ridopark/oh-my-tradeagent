package com.ohmytradeagent.apigateway.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) BFF hop: {@code DELETE
 * /api/admin/tenants/{tenant}/dashboard-rows} (Phase 3) — the one store api-gateway cannot reach
 * directly (only the BFF connects to the dashboard DB). The BFF route is service-token bearer-gated
 * AND requires an allowlisted {@code X-Operator-Id}, so this hop sends the BFF bearer (default
 * header on {@code bffRestClient}) plus the authenticated operator id. Idempotent on the BFF side
 * (0 rows = success); a non-2xx or transport fault throws so the orchestration reports a 207.
 *
 * <p>Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class DashboardRowsDeleteForwarder {

  private final RestClient bffRestClient;

  public DashboardRowsDeleteForwarder(RestClient bffRestClient) {
    this.bffRestClient = bffRestClient;
  }

  /** Deletes the tenant's dashboard user + invite rows; returns the per-table counts. */
  public DeletedCounts delete(String tenant, String operator) {
    return bffRestClient
        .method(HttpMethod.DELETE)
        .uri("/api/admin/tenants/{tenant}/dashboard-rows", tenant)
        .header("X-Operator-Id", operator)
        .exchange(
            (request, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (!status.is2xxSuccessful()) {
                throw new IllegalStateException(
                    "bff dashboard-rows delete returned " + status.value());
              }
              java.util.Map<?, ?> parsed = response.bodyTo(java.util.Map.class);
              int users = intOf(parsed, "deleted_users");
              int invites = intOf(parsed, "deleted_invites");
              return new DeletedCounts(users, invites);
            },
            false);
  }

  private static int intOf(java.util.Map<?, ?> body, String key) {
    Object v = body == null ? null : body.get(key);
    return v instanceof Number n ? n.intValue() : 0;
  }

  /** Rows removed from each dashboard identity table. */
  public record DeletedCounts(int users, int invites) {}
}
