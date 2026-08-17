package com.ohmytradeagent.tdbff.web;

// COPIED FROM services/api-gateway/.../web/TenantContext.java — keep in sync.
// Deliberately diverged: the operator gateway falls back to a `dev` default when X-Tenant-Id is
// absent (single-tenant operator convenience). A TENANT-FACING read must NEVER silently fall back
// to `dev` — an absent X-Tenant-Id is a 401, not "show me dev's positions". Strategy ids are NOT a
// header here either: they are resolved server-side from the orchestrator's strategy_config
// table (DbStrategyConfigReader) — NOT from a mounted tenants tree, which this service stopped
// reading before the mount was removed on 2026-08-17.
// (TenantStrategyResolver, querying strategy_config) so a caller can never widen its own scope.
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

@Component
public class TenantContext {

  static final String HEADER_TENANT = "X-Tenant-Id";
  static final String HEADER_OPERATOR = "X-Operator-Id";

  // tenant_id is a SQL bind parameter scoping every read; constrain it to a safe charset here —
  // independent of any caller's ordering — as defense-in-depth against a malformed identity.
  private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9_-]+");

  // Kept in sync with api-gateway TenantContext: an operator id (an email from the allowlist) is a
  // pure-ASCII charset, ≤254 chars — validated BEFORE any Locale-fold so the case-insensitive
  // allowlist compare never hits a Turkish-İ locale surprise.
  private static final int OPERATOR_ID_MAX_LENGTH = 254;
  private static final Pattern OPERATOR_ID_PATTERN = Pattern.compile("[A-Za-z0-9_@.+-]+");

  // The operators authorized to invoke the operator-ADMIN read (GET /api/admin/tenants). Normalized
  // to trimmed-lowercase so membership is case-insensitive. FAIL-CLOSED: an empty/unset allowlist
  // denies ALL operators — a misconfigured deploy must 403, never allow-all. Enforced only via
  // requireAllowlistedOperator(); operatorId() itself is left presence-only.
  private final Set<String> operatorAllowlist;

  @Autowired
  public TenantContext(@Value("${operator.allowlist:}") String operatorAllowlist) {
    this.operatorAllowlist = parseAllowlist(operatorAllowlist);
  }

  /** Convenience for tests that do not exercise the operator allowlist gate (empty = deny-all). */
  public TenantContext() {
    this("");
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

  /**
   * The tenant the Next.js server resolved from the verified social identity and injected.
   * Required: an absent, blank, or malformed header is a 401 (never a `dev` fallback).
   */
  public String tenantId(HttpServletRequest req) {
    String v = req.getHeader(HEADER_TENANT);
    if (v == null || v.isBlank() || !TENANT_ID.matcher(v).matches()) {
      throw new MissingTenantException();
    }
    return v;
  }

  /**
   * The authenticated operator from {@code X-Operator-Id}, for the OPERATOR-scoped (cross-tenant)
   * admin reads — NOT tenant-scoped. Required: an absent or blank header is a 400 (mirrors the
   * api-gateway operator pattern). Distinct from {@link #tenantId} which is a 401: an operator
   * route is not "unauthenticated for a tenant scope", it is a malformed operator request.
   */
  public String operatorId(HttpServletRequest req) {
    String v = req.getHeader(HEADER_OPERATOR);
    if (v == null || v.isBlank()) {
      throw new MissingOperatorException();
    }
    return v;
  }

  /** Format check for an operator id (non-null, non-blank, {@code [A-Za-z0-9_@.+-]+}, ≤254). */
  private boolean isValidOperatorId(String operator) {
    return operator != null
        && !operator.isBlank()
        && operator.length() <= OPERATOR_ID_MAX_LENGTH
        && OPERATOR_ID_PATTERN.matcher(operator).matches();
  }

  /**
   * Presence + format + allowlist gate for the operator-ADMIN read (GET /api/admin/tenants).
   * Absent/blank {@code X-Operator-Id} is a 400 (via {@link #operatorId}); a malformed value is a
   * 400; a well-formed but NON-allowlisted operator is a 403 ({@link
   * UnauthorizedOperatorException}). Defense-in-depth so a leaked shared bearer token alone cannot
   * enumerate every tenant's masked account/activation state. An empty/unset allowlist denies ALL
   * operators (fail-closed). Format is validated BEFORE the {@link Locale#ROOT} case-fold.
   */
  public String requireAllowlistedOperator(HttpServletRequest req) {
    String operator = operatorId(req); // 400 if X-Operator-Id absent/blank
    if (!isValidOperatorId(operator)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    if (!operatorAllowlist.contains(operator.trim().toLowerCase(Locale.ROOT))) {
      throw new UnauthorizedOperatorException();
    }
    return operator;
  }

  /**
   * Thrown when {@code X-Tenant-Id} is absent, blank, or malformed; mapped to 401 by {@code
   * GlobalExceptionHandler}.
   */
  public static class MissingTenantException extends RuntimeException {
    MissingTenantException() {
      super("missing required header: " + HEADER_TENANT);
    }
  }

  /**
   * Thrown when {@code X-Operator-Id} is absent or blank on an operator-scoped route; mapped to 400
   * by {@code GlobalExceptionHandler}.
   */
  public static class MissingOperatorException extends RuntimeException {
    MissingOperatorException() {
      super("missing required header: " + HEADER_OPERATOR);
    }
  }

  /**
   * Thrown when a well-formed {@code X-Operator-Id} is NOT in the operator allowlist on the
   * operator-admin route; mapped to HTTP 403 by {@code GlobalExceptionHandler}. Carries no operator
   * value — the response stays a coarse 403 with no membership oracle.
   */
  public static class UnauthorizedOperatorException extends RuntimeException {
    UnauthorizedOperatorException() {
      super("operator not allowlisted");
    }
  }
}
