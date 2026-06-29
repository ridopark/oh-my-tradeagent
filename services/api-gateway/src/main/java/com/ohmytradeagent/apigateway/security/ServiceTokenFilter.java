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
 * UI-P2-a inbound service-token gate for the {@code /broker-credentials} route ONLY. Every request
 * to that route must carry {@code Authorization: Bearer <API_GATEWAY_SHARED_TOKEN>} or it is
 * rejected 401 before the controller runs. Combined with the route being reachable only from the
 * dashboard server (NetworkPolicy lands in UI-P2-c), this makes the dashboard the sole possible
 * caller — so the {@code X-Tenant-Id} it asserts can be trusted.
 *
 * <p><b>Route-scoped.</b> {@link #shouldNotFilter} returns true for everything except paths under
 * {@code /broker-credentials} (UI-P2-a) and {@code /admin/tenants/} (Phase F one-click
 * activation/deactivation), so the existing operator routes ({@code /positions}, {@code
 * /promotion}, …) are untouched — they keep their current header-trust behavior.
 *
 * <p><b>Dark by default.</b> Active when ANY of {@code broker.credentials.write.enabled=true},
 * {@code operator.activation.enabled=true}, {@code operator.tenant-create.enabled=true}, or {@code
 * operator.credential-write.enabled=true}; with all unset the filter bean does not exist (just like
 * the controllers). When present it bearer-gates BOTH route prefixes. The {@code /admin/tenants/}
 * prefix covers the Phase F activation routes, the Phase I-1b create-tenant route, AND the Phase
 * I-1c operator credential-write route — so EVERY flag that activates an {@code /admin/tenants/}
 * controller MUST also be in this expression, or that route would be reachable unauthenticated.
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
        + " or ${operator.credential-write.enabled:false}")
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  static final String ROUTE_PREFIX = "/broker-credentials";
  // Phase F: the one-click activation/deactivation admin route is bearer-gated too.
  static final String ADMIN_ROUTE_PREFIX = "/admin/tenants/";
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
    // Filter ONLY the credential-write route and the Phase F admin activation route; every other
    // route passes straight through.
    return path == null || !(path.startsWith(ROUTE_PREFIX) || path.startsWith(ADMIN_ROUTE_PREFIX));
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
