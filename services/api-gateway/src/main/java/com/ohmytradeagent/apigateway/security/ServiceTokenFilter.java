package com.ohmytradeagent.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * {@code /broker-credentials}, so the existing operator routes ({@code /positions}, {@code
 * /promotion}, …) are untouched — they keep their current header-trust behavior.
 *
 * <p><b>Dark by default.</b> Gated on {@code broker.credentials.write.enabled=true}; with the flag
 * unset the filter bean does not exist (just like the controller and exec client).
 *
 * <p>Mirrors the tenant-dashboard-bff filter: constant-time token compare (no timing side-channel)
 * and a prod fail-fast on the well-known default token (a pod started under the {@code prod}
 * profile without {@code API_GATEWAY_SHARED_TOKEN} refuses to boot rather than trust a
 * repo-readable value).
 */
@Component
@ConditionalOnProperty(name = "broker.credentials.write.enabled", havingValue = "true")
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  static final String ROUTE_PREFIX = "/broker-credentials";
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
    // Filter ONLY the credential-write route; every other route passes straight through.
    return path == null || !path.startsWith(ROUTE_PREFIX);
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
