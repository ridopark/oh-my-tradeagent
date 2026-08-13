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
 * separate {@code OPTIONS_CHAT_INGEST_TOKEN} and rejects the shared token outright, because its
 * caller renders an untrusted third-party Discord room and must not be able to spoof a tenant. Both
 * directions of that isolation are pinned in {@code ServiceTokenFilterTest}; the rationale lives
 * with the config in {@code application.yml}. Fail-closed: a blank ingest token matches nothing.
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
    // The exemption applies only to a path that cannot normalize into something else. Without the
    // second clause, `/actuator/%2e%2e/api/positions` would skip this filter entirely and reach a
    // tenant read UNAUTHENTICATED — a worse version of the ingest-token problem documented in
    // doFilterInternal. Suspicious paths fall through to the filter, which rejects them.
    return path != null && path.startsWith("/actuator/") && !hasSuspiciousPath(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // Path-traversal guard, BEFORE any path-based decision (this filter makes two: the actuator
    // exemption and the ingest-token prefix). getRequestURI() is the RAW, un-normalized request
    // line, while Spring dispatches on the DECODED and NORMALIZED path — so the string this filter
    // authorizes and the handler that actually runs can disagree:
    //
    //   POST /internal/options-chat/%2e%2e/%2e%2e/api/positions
    //
    // startsWith("/internal/options-chat/") is true, so the ingest token would be accepted, but the
    // request dispatches to /api/positions — turning a scraper credential into a real-money tenant
    // read, the precise thing the split token exists to prevent. Rather than trying to reproduce
    // the container's normalization here (getting that subtly wrong is how these bugs happen), we
    // reject any request whose path could normalize to something other than itself. This is what
    // Spring Security's StrictHttpFirewall does, and it costs us nothing: no route in this service
    // takes a path segment containing a dot (tenant ids are [A-Za-z0-9_-]+), so a legitimate caller
    // never sends one.
    if (hasSuspiciousPath(request.getRequestURI())) {
      reject(response);
      return;
    }
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX) || !tokenMatches(request, header)) {
      reject(response);
      return;
    }
    chain.doFilter(request, response);
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"unauthorized\"}");
  }

  /**
   * True if the raw path contains a dot segment or a percent-encoded dot, in any case. Both forms
   * are rejected: the literal {@code ..} because the container would collapse it, and {@code %2e}
   * because the container decodes once before normalizing, so an encoded dot becomes a real one
   * after this filter has already made its decision.
   */
  private static boolean hasSuspiciousPath(String rawUri) {
    if (rawUri == null) {
      return true; // no path to reason about — fail closed
    }
    if (rawUri.toLowerCase(java.util.Locale.ROOT).contains("%2e")) {
      return true;
    }
    for (String segment : rawUri.split("/")) {
      if (segment.equals(".") || segment.equals("..")) {
        return true;
      }
    }
    return false;
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
