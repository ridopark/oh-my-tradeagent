package com.ohmytradeagent.tdbff.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>ONE EXCEPTION TO THE SINGLE-TOKEN MODEL: {@code /internal/options-chat/**} and {@code
 * /internal/darkpool/**} are gated on a separate {@code OPTIONS_CHAT_INGEST_TOKEN} and reject the
 * shared token outright, because their callers are sidecar pods (one renders an untrusted
 * third-party Discord room, one polls market data) that must not be able to spoof a tenant. Both
 * directions of that isolation are pinned in {@code ServiceTokenFilterTest}; the rationale lives
 * with the config in {@code application.yml}. Fail-closed: a blank ingest token matches nothing.
 *
 * <p>THIRD CREDENTIAL CLASS: per-tenant, read-only MCP tokens, bound server-side via {@code
 * bff.mcp-read-tokens} ({@code <sha256-hex>:<tenant_id>}, comma-separated). An MCP token can only
 * GET/HEAD an explicit allowlist of tenant reads, always as its bound tenant, and nothing else. The
 * tenant is a property of the credential on the server; the client's view of "which tenant" is only
 * ever checked against it, never trusted on its own. Fail-closed: an empty map accepts no MCP
 * tokens.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);

  /**
   * Request attribute carrying the first 12 hex chars of the sha256 of the MCP token that
   * authorised this request. Set only for MCP-authorised requests; absent for shared-token
   * requests. Server-side only — no caller can forge it.
   */
  public static final String MCP_TOKEN_ID_ATTRIBUTE = "mcp.token_id";

  private static final String BEARER_PREFIX = "Bearer ";
  // The application.yml fallback used for local dev. Accepting it in production would mean a pod
  // started without BFF_SHARED_TOKEN silently trusts a value anyone can read from this repo.
  private static final String INSECURE_DEFAULT_TOKEN = "dev-shared-token";
  // The routes gated on the INGEST token rather than the shared one. All callers are sidecar pods
  // (the Discord mirror, the dark-pool collector, and the flow-collector) that must never hold a
  // tenant-reading credential. A new prefix here is a new holder of that token — keep the list
  // short.
  private static final String[] INGEST_PREFIXES = {
    "/internal/options-chat/", "/internal/darkpool/", "/internal/flow/"
  };
  // Percent-encodings the container decodes into a dot, a separator, or a path parameter — each
  // would change which handler runs after this filter has already chosen a token. See
  // hasSuspiciousPath.
  private static final String[] ENCODED_SEPARATORS = {"%2e", "%2f", "%5c", "%3b"};

  // Exact raw paths an MCP token may reach (GET/HEAD only). No trailing slash, no path variables.
  // The firewall guard already rejects every path that could normalise into one of these.
  private static final Set<String> MCP_ALLOWLIST =
      Set.of(
          "/api/positions",
          "/api/portfolio",
          "/api/portfolio-history",
          "/api/live/marks",
          "/api/trades",
          "/api/orders",
          "/api/signals",
          "/api/trail-liveness",
          "/api/floor-breach",
          "/api/account-killswitch",
          "/api/entries/quote",
          "/api/entries/chain",
          "/api/entries/chain/expirations",
          "/api/flow/orders",
          "/api/flow/summary",
          "/api/darkpool/prints",
          "/api/darkpool/summary",
          "/api/mcp/whoami");

  private final String sharedToken;
  private final String optionsChatIngestToken;
  // sha256-lowercase-hex -> tenant_id. Empty means no MCP tokens are configured (fail-closed).
  private final Map<String, String> mcpTokenMap;

  public ServiceTokenFilter(
      @Value("${bff.service-token}") String sharedToken,
      @Value("${options-chat.ingest-token:}") String optionsChatIngestToken,
      @Value("${bff.mcp-read-tokens:}") String mcpReadTokens,
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
    this.mcpTokenMap = parseMcpReadTokens(mcpReadTokens);
  }

  /**
   * Parses the comma-separated {@code <sha256-hex>:<tenant_id>} list. Blank entries are skipped (a
   * trailing comma is harmless). Any entry that does not match {@code
   * ^[0-9a-fA-F]{64}:[A-Za-z0-9_-]+$} throws {@link IllegalStateException} unconditionally — a
   * malformed map has no legitimate use in any profile.
   */
  private static Map<String, String> parseMcpReadTokens(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    Map<String, String> map = new LinkedHashMap<>();
    int pos = 0;
    for (String entry : raw.split(",", -1)) {
      pos++;
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!trimmed.matches("^[0-9a-fA-F]{64}:[A-Za-z0-9_-]+$")) {
        throw new IllegalStateException(
            "Malformed bff.mcp-read-tokens entry at position "
                + pos
                + " (expected <64-hex>:<tenant>)");
      }
      int colon = trimmed.indexOf(':');
      map.put(
          trimmed.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
          trimmed.substring(colon + 1));
    }
    return Collections.unmodifiableMap(map);
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
    // Rule 1: Path-traversal guard, BEFORE any path-based decision (this filter makes two: the
    // actuator
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
    // Rule 2: bearer absent or non-bearer scheme → 401.
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      reject(response);
      return;
    }
    String presented = header.substring(BEARER_PREFIX.length());

    // Rule 3: ingest prefix — only the ingest token; the shared token and any MCP token are
    // rejected here (fall through to reject at the end). An MCP token on an ingest path is
    // therefore always a 401, with no special case.
    if (isIngestPath(request.getRequestURI())) {
      if (constantTimeEquals(presented, optionsChatIngestToken)) {
        chain.doFilter(request, response);
      } else {
        reject(response);
      }
      return;
    }

    // Rule 4: shared token passes through unchanged — no attribute set, headers untouched.
    if (constantTimeEquals(presented, sharedToken)) {
      chain.doFilter(request, response);
      return;
    }

    // Rule 5: MCP read token. Compute sha256 once; compare against EVERY configured entry with
    // MessageDigest.isEqual (constant-time per entry, loop shape is independent of which entry
    // matches) so a timing side-channel cannot reveal which hash (if any) matched.
    if (!mcpTokenMap.isEmpty()) {
      String presentedHash = sha256hex(presented);
      String boundTenant = null;
      for (Map.Entry<String, String> entry : mcpTokenMap.entrySet()) {
        if (MessageDigest.isEqual(
            presentedHash.getBytes(StandardCharsets.UTF_8),
            entry.getKey().getBytes(StandardCharsets.UTF_8))) {
          boundTenant = entry.getValue();
          // Do NOT break: continue iterating all entries so the loop shape is timing-independent.
        }
      }
      if (boundTenant != null) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
          forbidden(response);
          return;
        }
        if (!MCP_ALLOWLIST.contains(request.getRequestURI())) {
          forbidden(response);
          return;
        }
        // X-Tenant-Id: if present and non-blank, it must match the bound tenant.
        // Absent or blank → the wrapper will inject it; forged → 403.
        String callerTenant = request.getHeader("X-Tenant-Id");
        if (callerTenant != null && !callerTenant.isBlank() && !callerTenant.equals(boundTenant)) {
          forbidden(response);
          return;
        }
        String tokenId = presentedHash.substring(0, 12);
        log.info(
            "mcp read tenant={} token_id={} method={} path={}",
            boundTenant,
            tokenId,
            method,
            request.getRequestURI());
        HttpServletRequest wrapped = new McpRequestWrapper(request, boundTenant);
        wrapped.setAttribute(MCP_TOKEN_ID_ATTRIBUTE, tokenId);
        chain.doFilter(wrapped, response);
        return;
      }
    }

    // Rule 6: unknown or unrecognised token → 401.
    reject(response);
  }

  private static boolean isIngestPath(String path) {
    if (path != null) {
      for (String prefix : INGEST_PREFIXES) {
        if (path.startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"unauthorized\"}");
  }

  private static void forbidden(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"forbidden\"}");
  }

  /**
   * True if the raw path could normalize into a different path than the one this filter inspects.
   * This is Spring Security's {@code StrictHttpFirewall} rule set, and every entry earns its place
   * — each corresponds to a transformation Tomcat applies BETWEEN this filter and handler dispatch:
   *
   * <ul>
   *   <li>{@code ;} and {@code %3b} — path PARAMETERS, stripped by {@code parsePathParameters}
   *       before decoding and normalizing. So {@code ..;a} is not the literal string {@code ".."}
   *       here, but IS a {@code ..} by the time the path is normalized. Omitting this was a real
   *       bypass: {@code /internal/options-chat/..;a/..;b/api/positions} would have carried the
   *       ingest token into a tenant read.
   *   <li>{@code .} / {@code ..} segments and {@code %2e} — the traversal itself, literal or
   *       encoded (the container decodes once, then normalizes).
   *   <li>{@code %2f} and {@code %5c} — encoded separators that become segment boundaries.
   *   <li>empty segments ({@code //}) — collapsed by normalize, so {@code /internal//options-chat/}
   *       reaches the ingest handler while failing this filter's prefix test, which would let the
   *       SHARED token through to the ingest route.
   * </ul>
   *
   * <p>Free for us: no route in this service takes a path segment containing a dot, a semicolon, or
   * anything needing percent-encoding (tenant ids are {@code [A-Za-z0-9_-]+}).
   */
  private static boolean hasSuspiciousPath(String rawUri) {
    if (rawUri == null || rawUri.isEmpty()) {
      return true; // no path to reason about — fail closed
    }
    String lower = rawUri.toLowerCase(java.util.Locale.ROOT);
    for (String encoded : ENCODED_SEPARATORS) {
      if (lower.contains(encoded)) {
        return true;
      }
    }
    if (rawUri.indexOf(';') >= 0 || rawUri.indexOf('\\') >= 0) {
      return true;
    }
    // Index 0 is the empty string before the leading '/'; a later empty segment means '//'.
    String[] segments = rawUri.split("/", -1);
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      // A trailing '/' leaves a final empty segment and is harmless.
      if (segment.isEmpty() && i < segments.length - 1) {
        return true;
      }
      if (segment.equals(".") || segment.equals("..")) {
        return true;
      }
    }
    return false;
  }

  private static boolean constantTimeEquals(String presented, String expected) {
    if (expected == null || expected.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Wraps a request for an MCP token holder: injects the bound tenant as {@code X-Tenant-Id} and
   * strips {@code X-Operator-Id}. All other headers pass through unchanged.
   */
  private static final class McpRequestWrapper extends HttpServletRequestWrapper {

    private static final String TENANT_HEADER = "x-tenant-id";
    private static final String OPERATOR_HEADER = "x-operator-id";
    private final String boundTenant;

    McpRequestWrapper(HttpServletRequest request, String boundTenant) {
      super(request);
      this.boundTenant = boundTenant;
    }

    @Override
    public String getHeader(String name) {
      if (TENANT_HEADER.equalsIgnoreCase(name)) {
        return boundTenant;
      }
      if (OPERATOR_HEADER.equalsIgnoreCase(name)) {
        return null;
      }
      return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (TENANT_HEADER.equalsIgnoreCase(name)) {
        return Collections.enumeration(Collections.singletonList(boundTenant));
      }
      if (OPERATOR_HEADER.equalsIgnoreCase(name)) {
        return Collections.emptyEnumeration();
      }
      return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names = new ArrayList<>();
      Enumeration<String> originalNames = super.getHeaderNames();
      if (originalNames != null) {
        while (originalNames.hasMoreElements()) {
          String name = originalNames.nextElement();
          // Strip X-Operator-Id; keep everything else (including any X-Tenant-Id the caller sent,
          // which the filter already verified matches the bound tenant or was absent).
          if (!OPERATOR_HEADER.equalsIgnoreCase(name)) {
            names.add(name);
          }
        }
      }
      // Always include X-Tenant-Id — the handler resolves the tenant from it.
      boolean hasTenant = names.stream().anyMatch(n -> TENANT_HEADER.equalsIgnoreCase(n));
      if (!hasTenant) {
        names.add("X-Tenant-Id");
      }
      return Collections.enumeration(names);
    }
  }
}
