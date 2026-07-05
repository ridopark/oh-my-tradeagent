package com.ohmytradeagent.apigateway.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
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

  public DashboardRowsDeleteForwarder(@Qualifier("bffRestClient") RestClient bffRestClient) {
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
              Map<?, ?> parsed = response.bodyTo(Map.class);
              int users = intOf(parsed, "deleted_users");
              int invites = intOf(parsed, "deleted_invites");
              return new DeletedCounts(users, invites);
            },
            false);
  }

  /**
   * The NEWEST dashboard-row creation instant for the tenant ({@code GET
   * /api/admin/tenants/{tenant}/dashboard-rows/newest}, Phase 2 incarnation guard), or {@link
   * Optional#empty()} when the tenant has no dashboard rows. Used by the residual-cleanup route to
   * refuse a re-onboarded (reused-tenant_id) tenant whose new invite POSTDATES the last delete. A
   * non-2xx or transport fault THROWS — the caller MUST treat that as fail-closed (refuse cleanup),
   * never as "no rows". Same bff hop (bearer default header + allowlisted operator) as {@link
   * #delete}.
   */
  public Optional<Instant> newestCreatedAt(String tenant, String operator) {
    return bffRestClient
        .get()
        .uri("/api/admin/tenants/{tenant}/dashboard-rows/newest", tenant)
        .header("X-Operator-Id", operator)
        .exchange(
            (request, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (!status.is2xxSuccessful()) {
                throw new IllegalStateException(
                    "bff dashboard-rows newest read returned " + status.value());
              }
              Map<?, ?> parsed = response.bodyTo(Map.class);
              Object raw = parsed == null ? null : parsed.get("newest_created_at");
              if (!(raw instanceof String s) || s.isBlank()) {
                // null / absent → the tenant has no dashboard rows (empty).
                return Optional.<Instant>empty();
              }
              // ISO-8601 with offset (the bff serializes OffsetDateTime.toString()).
              return Optional.of(OffsetDateTime.parse(s).toInstant());
            },
            false);
  }

  private static int intOf(Map<?, ?> body, String key) {
    Object v = body == null ? null : body.get(key);
    return v instanceof Number n ? n.intValue() : 0;
  }

  /** Rows removed from each dashboard identity table. */
  public record DeletedCounts(int users, int invites) {}
}
