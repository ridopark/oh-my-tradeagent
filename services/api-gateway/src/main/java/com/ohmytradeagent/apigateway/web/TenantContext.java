package com.ohmytradeagent.apigateway.web;

import jakarta.servlet.http.HttpServletRequest;
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
