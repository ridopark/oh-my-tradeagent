package com.ohmytradeagent.exec.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P6-c authentication for the dark credential-write endpoint. Mirrors the tenant-dashboard-bff
 * {@code ServiceTokenFilter}: every request to {@code /internal/broker-credentials} must carry
 * {@code Authorization: Bearer <EXEC_ADMIN_SHARED_TOKEN>} or it is rejected with 401 before any
 * handler runs. Constant-time compare guards against a timing side-channel.
 *
 * <p><b>Gated identically to {@link BrokerCredentialAdminController}</b> — {@code
 * broker.creds.source=db} AND an {@code alpaca-*} impl — so on a homelab pod (selector at {@code
 * env}) this bean does not exist; the filter (and the endpoint it guards) is dark by construction.
 *
 * <p><b>Route-scoped.</b> {@link #shouldNotFilter} returns true for everything except the
 * credential route, so the Temporal worker, actuator health/readiness/prometheus, and every other
 * path are unaffected — only the credential write is gated.
 *
 * <p><b>Fail-fast under {@code prod}.</b> A pod that boots with the well-known dev default token
 * under the {@code prod} profile crashloops rather than silently trusting a value anyone can read
 * from this repo.
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class ExecAdminTokenFilter extends OncePerRequestFilter {

  static final String CREDENTIAL_ROUTE = "/internal/broker-credentials";

  private static final String BEARER_PREFIX = "Bearer ";
  // The application.yml fallback used for local dev. Accepting it under prod would mean a pod
  // started without EXEC_ADMIN_SHARED_TOKEN silently trusts a value anyone can read from this repo.
  private static final String INSECURE_DEFAULT_TOKEN = "dev-admin-token";

  // The expected token is fixed at construction, so encode it once rather than per request. The
  // constant-time compare (MessageDigest.isEqual) is preserved — only the right operand is hoisted.
  private final byte[] sharedTokenBytes;

  public ExecAdminTokenFilter(
      @Value("${exec.admin.service-token}") String sharedToken, Environment environment) {
    if (INSECURE_DEFAULT_TOKEN.equals(sharedToken)
        && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "exec.admin.service-token is the well-known default under the prod profile — set"
              + " EXEC_ADMIN_SHARED_TOKEN to a real secret");
    }
    this.sharedTokenBytes = sharedToken.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Scope the auth to the credential route ONLY. Everything else (actuator, the worker) is
    // untouched — this filter must not change any existing behavior.
    String path = request.getRequestURI();
    return path == null || !path.equals(CREDENTIAL_ROUTE);
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
    return java.security.MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), sharedTokenBytes);
  }
}
