package com.ohmytradeagent.tdbff.web;

// COPIED FROM services/api-gateway/.../web/TenantContext.java — keep in sync.
// Deliberately diverged: the operator gateway falls back to a `dev` default when X-Tenant-Id is
// absent (single-tenant operator convenience). A TENANT-FACING read must NEVER silently fall back
// to `dev` — an absent X-Tenant-Id is a 401, not "show me dev's positions". Strategy ids are NOT a
// header here either: they are resolved server-side from the mounted tenants tree
// (TenantStrategyResolver) so a caller can never widen its own scope.
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TenantContext {

  static final String HEADER_TENANT = "X-Tenant-Id";

  // tenant_id flows into a filesystem path (YamlStrategyRegistry resolves tenants/<id>/...), so
  // constrain it to a safe charset here — independent of any caller's ordering — to make path
  // traversal (e.g. "../../etc") structurally impossible rather than only implicitly blocked.
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
   * Thrown when {@code X-Tenant-Id} is absent, blank, or malformed; mapped to 401 by {@code
   * GlobalExceptionHandler}.
   */
  public static class MissingTenantException extends RuntimeException {
    MissingTenantException() {
      super("missing required header: " + HEADER_TENANT);
    }
  }
}
