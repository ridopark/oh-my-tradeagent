package com.ohmytradeagent.tdbff.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The structural replacement for the operator gateway's header-trust. Every request must carry
 * {@code Authorization: Bearer <BFF_SHARED_TOKEN>} or it is rejected with 401 before any handler
 * runs. Combined with the service being off-ingress (ClusterIP only), this makes the Next.js server
 * the sole possible caller — so the {@code X-Tenant-Id} it injects (after verifying the social
 * identity) can be trusted.
 *
 * <p>All {@code /actuator/} endpoints are exempt so Kubernetes can probe health/readiness/liveness
 * and a cluster-internal Prometheus can scrape {@code /actuator/prometheus} without the shared
 * token. Safe because the service is ClusterIP-only and NetworkPolicy-restricted, and the exposed
 * set (health, info, prometheus) carries no secrets.
 *
 * <p>ONE EXCEPTION TO THE SINGLE-TOKEN MODEL: {@code /internal/options-chat/**} is gated on a
 * SEPARATE {@code OPTIONS_CHAT_INGEST_TOKEN} and rejects the shared token outright. Its caller is
 * the Discord chat-mirror pod, whose entire job is rendering an untrusted third-party room — with
 * {@code BFF_SHARED_TOKEN} it could set any {@code X-Tenant-Id} and read positions, orders and
 * portfolio for real-money tenants, which is exactly the spoofing this filter exists to prevent.
 * The narrow token confines a compromise of that pod to defacing the chat mirror. Fail-closed: an
 * unset or blank ingest token rejects every request to that prefix, so the route cannot be reached
 * before the secret is provisioned.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  // The application.yml fallback used for local dev. Accepting it in production would mean a pod
  // started without BFF_SHARED_TOKEN silently trusts a value anyone can read from this repo.
  private static final String INSECURE_DEFAULT_TOKEN = "dev-shared-token";
  private static final String OPTIONS_CHAT_INGEST_PREFIX = "/internal/options-chat/";

  private final String sharedToken;
  private final String optionsChatIngestToken;

  public ServiceTokenFilter(
      @Value("${bff.service-token}") String sharedToken,
      @Value("${options-chat.ingest-token:}") String optionsChatIngestToken,
      Environment environment) {
    if (INSECURE_DEFAULT_TOKEN.equals(sharedToken)
        && environment.acceptsProfiles(Profiles.of("prod"))) {
      // Fail fast at boot rather than run with a well-known token (the k8s Deployment sets the
      // `prod` profile; local dev runs without it and keeps the default for convenience).
      throw new IllegalStateException(
          "bff.service-token is the well-known default under the prod profile — set BFF_SHARED_TOKEN"
              + " to a real secret");
    }
    this.sharedToken = sharedToken;
    this.optionsChatIngestToken = optionsChatIngestToken;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path != null && path.startsWith("/actuator/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX) || !tokenMatches(request, header)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean tokenMatches(HttpServletRequest request, String header) {
    String presented = header.substring(BEARER_PREFIX.length());
    String expected = expectedTokenFor(request.getRequestURI());
    // Fail closed: an unprovisioned (blank) expected token matches nothing, so the route stays shut
    // rather than accepting an empty bearer.
    if (expected == null || expected.isBlank()) {
      return false;
    }
    // Constant-time compare so a timing side-channel can't be used to recover the token.
    return java.security.MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The one token accepted for this path. The options-chat ingest prefix accepts ONLY its own token
   * — the shared token is rejected there too, so nothing about holding one implies the other.
   */
  private String expectedTokenFor(String path) {
    if (path != null && path.startsWith(OPTIONS_CHAT_INGEST_PREFIX)) {
      return optionsChatIngestToken;
    }
    return sharedToken;
  }
}
