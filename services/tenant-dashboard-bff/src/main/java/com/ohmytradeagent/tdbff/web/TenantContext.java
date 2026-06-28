package com.ohmytradeagent.tdbff.web;

// COPIED FROM services/api-gateway/.../web/TenantContext.java — keep in sync.
// Deliberately diverged: the operator gateway falls back to a `dev` default when X-Tenant-Id is
// absent (single-tenant operator convenience). A TENANT-FACING read must NEVER silently fall back
// to `dev` — an absent X-Tenant-Id is a 401, not "show me dev's positions". Strategy ids are NOT a
// header here either: they are resolved server-side from the mounted tenants tree
// (TenantStrategyResolver, querying strategy_config) so a caller can never widen its own scope.
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TenantContext {

  static final String HEADER_TENANT = "X-Tenant-Id";
  static final String HEADER_OPERATOR = "X-Operator-Id";

  // tenant_id is a SQL bind parameter scoping every read; constrain it to a safe charset here —
  // independent of any caller's ordering — as defense-in-depth against a malformed identity.
  private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9_-]+");

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
}
