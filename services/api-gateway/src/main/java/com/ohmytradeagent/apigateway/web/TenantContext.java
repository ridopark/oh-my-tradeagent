package com.ohmytradeagent.apigateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 5 placeholder auth. Real OAuth/JWT deferred to Phase 6. Headers honored: {@code
 * X-Tenant-Id}, {@code X-Strategy-Id}, {@code X-Operator-Id}. When a header is absent, falls back
 * to the configured default (single-tenant dev convenience).
 */
@Component
public class TenantContext {

  static final String HEADER_TENANT = "X-Tenant-Id";
  static final String HEADER_STRATEGY = "X-Strategy-Id";
  static final String HEADER_OPERATOR = "X-Operator-Id";
  static final String HEADER_APPROVER_2 = "X-Approver-Id-2";

  // The canonical tenant-id charset. A tenant value flows into Temporal workflow ids, Visibility
  // queries, and the exec X-Tenant-Id header, so it must stay restricted — kept here (compiled
  // once) as the single home for the rule, shared by the header guard and any path-tenant guard.
  // (Validated via Matcher.matches(), which anchors the whole string — no embedded illegal char.)
  private static final Pattern TENANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

  // The canonical operator-id charset (an email from the OPERATOR_EMAILS allowlist). An operator id
  // flows into the audit `actor` field, so reject control chars / overlong values before they are
  // recorded. Validated via Matcher.matches() (whole-string anchored). Email max length is 254.
  private static final int OPERATOR_ID_MAX_LENGTH = 254;
  private static final Pattern OPERATOR_ID_PATTERN = Pattern.compile("[A-Za-z0-9_@.+-]+");

  private final String defaultTenant;
  private final String defaultStrategy;
  // The set of operators authorized to invoke the operator-ADMIN routes (create-tenant,
  // credential-write, activate/deactivate). Normalized to trimmed-lowercase so membership is
  // case-insensitive. FAIL-CLOSED: an empty/unset allowlist denies ALL operators — a misconfigured
  // deploy must 403 on these real-money-adjacent routes, never allow-all. Enforced only via
  // requireAllowlistedOperator(); the presence-only operatorId() is left untouched so the many
  // non-admin operator readers (kill-switch approver, promotion approver, positions) are
  // unaffected.
  private final Set<String> operatorAllowlist;

  @Autowired
  public TenantContext(
      @Value("${api-gateway.default-tenant:dev}") String defaultTenant,
      @Value("${api-gateway.default-strategy:copytrade-v1}") String defaultStrategy,
      @Value("${operator.allowlist:}") String operatorAllowlist) {
    this.defaultTenant = defaultTenant;
    this.defaultStrategy = defaultStrategy;
    this.operatorAllowlist = parseAllowlist(operatorAllowlist);
  }

  /** Convenience for tests that do not exercise the operator allowlist gate (empty = deny-all). */
  public TenantContext(String defaultTenant, String defaultStrategy) {
    this(defaultTenant, defaultStrategy, "");
  }

  private static Set<String> parseAllowlist(String csv) {
    Set<String> out = new HashSet<>();
    if (csv == null) {
      return out;
    }
    Arrays.stream(csv.split(","))
        .map(s -> s.trim().toLowerCase(Locale.ROOT))
        .filter(s -> !s.isEmpty())
        .forEach(out::add);
    return out;
  }

  public String tenantId(HttpServletRequest req) {
    return headerOr(req, HEADER_TENANT, defaultTenant);
  }

  /**
   * STRICT tenant resolution for the UI-P2-a credential-write route — NO dev fallback. The
   * dashboard server asserts a verified {@code X-Tenant-Id}; an absent/blank/malformed value is a
   * misconfiguration or an attempt to skip the assertion, so it throws {@link
   * MissingHeaderException} (mapped to HTTP 400 by {@link GlobalExceptionHandler}) rather than
   * silently defaulting to {@code dev}. The format guard ({@code [A-Za-z0-9_-]+}) keeps a hostile
   * tenant value out of the workflow id / Visibility query. The lenient {@link
   * #tenantId(HttpServletRequest)} is untouched (other routes still default for single-tenant dev
   * convenience).
   */
  public String requiredTenantId(HttpServletRequest req) {
    String v = req.getHeader(HEADER_TENANT);
    if (!isValidTenantId(v)) {
      throw new MissingHeaderException(HEADER_TENANT);
    }
    return v;
  }

  /**
   * The canonical tenant-id format check (non-null, non-blank, {@code [A-Za-z0-9_-]+}). The home of
   * the rule, so the header guard ({@link #requiredTenantId}) and operator-scoped path-tenant
   * guards apply exactly the same charset — each caller maps a {@code false} to its own error.
   */
  public boolean isValidTenantId(String tenant) {
    return tenant != null && !tenant.isBlank() && TENANT_ID_PATTERN.matcher(tenant).matches();
  }

  /**
   * Format check for an operator id (non-null, non-blank, {@code [A-Za-z0-9_@.+-]+}, ≤254 chars).
   * Used by {@link #requireAllowlistedOperator} to reject a hostile {@code X-Operator-Id} (control
   * chars / overlong) BEFORE it is normalized/allowlist-matched or recorded as the audit {@code
   * actor}. {@link #operatorId} only checks presence.
   */
  private boolean isValidOperatorId(String operator) {
    return operator != null
        && !operator.isBlank()
        && operator.length() <= OPERATOR_ID_MAX_LENGTH
        && OPERATOR_ID_PATTERN.matcher(operator).matches();
  }

  public String strategyId(HttpServletRequest req) {
    return headerOr(req, HEADER_STRATEGY, defaultStrategy);
  }

  public String operatorId(HttpServletRequest req) {
    String v = req.getHeader(HEADER_OPERATOR);
    if (v == null || v.isBlank()) {
      throw new MissingHeaderException(HEADER_OPERATOR);
    }
    return v;
  }

  /**
   * Presence + format + allowlist gate for the operator-ADMIN routes (create-tenant,
   * credential-write, activate/deactivate). Absent/blank {@code X-Operator-Id} is a 400 (via {@link
   * #operatorId}); a malformed value (control chars / overlong) is a 400; a well-formed but
   * NON-allowlisted operator is a 403 ({@link UnauthorizedOperatorException}).
   *
   * <p>Defense-in-depth: the dashboard already authorizes operators, but these routes can create
   * tenants and arm real-money trading, so the backend independently rejects a non-allowlisted
   * operator — a leaked shared bearer token alone cannot onboard/activate. An empty/unset allowlist
   * denies ALL operators (fail-closed). Distinct from {@link #operatorId} so the non-admin operator
   * readers (kill-switch trip/reset, promotion approver, positions) keep their presence-only
   * behavior — an empty allowlist must NEVER block a kill-switch trip.
   *
   * <p>Format is validated ({@link #isValidOperatorId}) BEFORE trimming + {@link Locale#ROOT}
   * lowercasing, so the case-fold runs only on the guaranteed-ASCII charset (no Turkish-İ locale
   * surprise) and a whitespace/control-char value can never be silently normalized into a match.
   *
   * <p>Scope note: {@code X-Approver-Id-2} ({@link #approverId2}) is deliberately NOT covered by
   * this allowlist — dual-approval (promotion / kill-switch reset) stays header-trusted in Phase 2.
   */
  public String requireAllowlistedOperator(HttpServletRequest req) {
    String operator = operatorId(req); // 400 if X-Operator-Id absent/blank
    if (!isValidOperatorId(operator)) {
      // Malformed operator id: a bad request, and unsafe to Locale-fold — reject 400 before match.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    if (!operatorAllowlist.contains(operator.trim().toLowerCase(Locale.ROOT))) {
      throw new UnauthorizedOperatorException();
    }
    return operator;
  }

  public String approverId2(HttpServletRequest req) {
    String v = req.getHeader(HEADER_APPROVER_2);
    if (v == null || v.isBlank()) {
      throw new MissingHeaderException(HEADER_APPROVER_2);
    }
    return v;
  }

  private String headerOr(HttpServletRequest req, String name, String fallback) {
    String v = req.getHeader(name);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  public static class MissingHeaderException extends RuntimeException {
    private final String header;

    MissingHeaderException(String header) {
      super("missing required header: " + header);
      this.header = header;
    }

    public String header() {
      return header;
    }
  }

  /**
   * Thrown when a well-formed {@code X-Operator-Id} is NOT in the operator allowlist on an
   * operator-admin route; mapped to HTTP 403 by {@link GlobalExceptionHandler}. Carries no operator
   * value — the response stays a coarse 403 with no membership oracle.
   */
  public static class UnauthorizedOperatorException extends RuntimeException {
    UnauthorizedOperatorException() {
      super("operator not allowlisted");
    }
  }
}
