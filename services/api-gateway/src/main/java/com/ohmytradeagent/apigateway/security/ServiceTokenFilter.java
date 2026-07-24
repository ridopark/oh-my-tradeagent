package com.ohmytradeagent.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * UI-P2-a inbound service-token gate. Every request to a gated route must carry {@code
 * Authorization: Bearer <API_GATEWAY_SHARED_TOKEN>} or it is rejected 401 before the controller
 * runs. For {@code /broker-credentials}, combined with the route being reachable only from the
 * dashboard server (NetworkPolicy lands in UI-P2-c), this makes the dashboard the sole possible
 * caller — so the {@code X-Tenant-Id} it asserts can be trusted.
 *
 * <p><b>Route-scoped.</b> {@link #shouldNotFilter} returns true for everything except paths under
 * {@code /broker-credentials} (UI-P2-a), {@code /admin/tenants/} (Phase F one-click
 * activation/deactivation), and {@code /internal/copytrade-fanout-targets} (Phase B1 sidecar
 * registry poll), so the existing operator routes ({@code /positions}, {@code /audit}, …) are
 * untouched — they keep their current header-trust behavior. (The one-click activation route lives
 * UNDER {@code /admin/tenants/}, so it is bearer-gated, not header-trusted.)
 *
 * <p><b>Dark by default.</b> Active when ANY of {@code broker.credentials.write.enabled=true},
 * {@code operator.activation.enabled=true}, {@code operator.tenant-create.enabled=true}, {@code
 * operator.credential-write.enabled=true}, {@code copytrade.fanout.enabled=true}, or {@code
 * watchlist.fanout.enabled=true}; with all unset the filter bean does not exist (just like the
 * controllers). When present it bearer-gates ALL four route prefixes. The {@code /admin/tenants/}
 * prefix covers the Phase F activation routes, the Phase I-1b create-tenant route, AND the Phase
 * I-1c operator credential-write route; the {@code /internal/copytrade-fanout-targets} and {@code
 * /internal/watchlist-fanout-targets} prefixes cover the copytrade (Phase B1) and watchlist (Phase
 * 1, Fork A) fan-out registries (both SERVICE-token routes, not operator-scoped) — so EVERY flag
 * that activates ANY of these gated controllers MUST also be in this expression, or that route
 * would be reachable unauthenticated.
 *
 * <p>Mirrors the tenant-dashboard-bff filter: constant-time token compare (no timing side-channel)
 * and a prod fail-fast on the well-known default token (a pod started under the {@code prod}
 * profile without {@code API_GATEWAY_SHARED_TOKEN} refuses to boot rather than trust a
 * repo-readable value).
 */
@Component
@ConditionalOnExpression(
    "${broker.credentials.write.enabled:false} or ${operator.activation.enabled:false}"
        + " or ${operator.tenant-create.enabled:false}"
        + " or ${operator.credential-write.enabled:false}"
        + " or ${operator.strategy-enable.enabled:false}"
        + " or ${operator.tenant-delete.enabled:false}"
        + " or ${copytrade.fanout.enabled:false}"
        + " or ${watchlist.fanout.enabled:false}")
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  static final String ROUTE_PREFIX = "/broker-credentials";
  // Phase F: the one-click activation/deactivation admin route is bearer-gated too.
  static final String ADMIN_ROUTE_PREFIX = "/admin/tenants/";
  // Phase B1: the copytrade fan-out registry route the sidecar polls. The caller is a SERVICE, so a
  // shared service-token bearer gates it (NOT the X-Operator-Id allowlist). Every flag that
  // activates this route MUST also be in the @ConditionalOnExpression above, or the route would be
  // reachable unauthenticated.
  static final String INTERNAL_FANOUT_ROUTE_PREFIX = "/internal/copytrade-fanout-targets";
  // Phase 1 (Fork A): the watchlist fan-out registry route the sidecar polls — the sibling of the
  // copytrade fan-out route. Same SERVICE-token bearer gate; watchlist.fanout.enabled is in the
  // @ConditionalOnExpression above so turning ON only that flag still brings up this gate.
  static final String INTERNAL_WATCHLIST_FANOUT_ROUTE_PREFIX = "/internal/watchlist-fanout-targets";
  // The application.yml fallback used for local dev. Accepting it under prod would silently trust a
  // value anyone can read from this repo.
  private static final String INSECURE_DEFAULT_TOKEN = "dev-shared-token";

  private final String sharedToken;

  public ServiceTokenFilter(
      @Value("${api-gateway.service-token:${API_GATEWAY_SHARED_TOKEN:dev-shared-token}}")
          String sharedToken,
      Environment environment) {
    if (INSECURE_DEFAULT_TOKEN.equals(sharedToken)
        && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "api-gateway.service-token is the well-known default under the prod profile — set"
              + " API_GATEWAY_SHARED_TOKEN to a real secret");
    }
    this.sharedToken = sharedToken;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    // Filter ONLY the credential-write route, the Phase F admin activation route, and the Phase B1
    // internal fan-out route; every other route passes straight through.
    return path == null
        || !(path.startsWith(ROUTE_PREFIX)
            || path.startsWith(ADMIN_ROUTE_PREFIX)
            || path.startsWith(INTERNAL_FANOUT_ROUTE_PREFIX)
            || path.startsWith(INTERNAL_WATCHLIST_FANOUT_ROUTE_PREFIX));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX) || !tokenMatches(header)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean tokenMatches(String header) {
    String presented = header.substring(BEARER_PREFIX.length());
    // Constant-time compare so a timing side-channel can't be used to recover the shared token.
    return java.security.MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), sharedToken.getBytes(StandardCharsets.UTF_8));
  }
}
