package com.ohmytradeagent.tdbff.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The structural replacement for the operator gateway's header-trust. Every request must carry
 * {@code Authorization: Bearer <BFF_SHARED_TOKEN>} or it is rejected with 401 before any handler
 * runs. Combined with the service being off-ingress (ClusterIP only), this makes the Next.js server
 * the sole possible caller — so the {@code X-Tenant-Id} it injects (after verifying the social
 * identity) can be trusted.
 *
 * <p>Actuator health/readiness/liveness probes are exempt so Kubernetes can probe the pod without
 * the shared token.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final String sharedToken;

  public ServiceTokenFilter(@Value("${bff.service-token}") String sharedToken) {
    this.sharedToken = sharedToken;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path != null && path.startsWith("/actuator/health");
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
