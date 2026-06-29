package com.ohmytradeagent.apigateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
  private static final Pattern TENANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

  private final String defaultTenant;
  private final String defaultStrategy;

  public TenantContext(
      @Value("${api-gateway.default-tenant:dev}") String defaultTenant,
      @Value("${api-gateway.default-strategy:copytrade-v1}") String defaultStrategy) {
    this.defaultTenant = defaultTenant;
    this.defaultStrategy = defaultStrategy;
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
}
