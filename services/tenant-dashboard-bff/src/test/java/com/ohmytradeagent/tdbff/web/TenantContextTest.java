package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TenantContextTest {

  private final TenantContext ctx = new TenantContext();

  @Test
  void presentHeader_returnsTenant() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("X-Tenant-Id", "acme");
    assertThat(ctx.tenantId(req)).isEqualTo("acme");
  }

  @Test
  void missingHeader_throws401Mapped_andNeverFallsBackToDev() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    assertThatThrownBy(() -> ctx.tenantId(req))
        .isInstanceOf(TenantContext.MissingTenantException.class);
  }

  @Test
  void blankHeader_throws() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("X-Tenant-Id", "   ");
    assertThatThrownBy(() -> ctx.tenantId(req))
        .isInstanceOf(TenantContext.MissingTenantException.class);
  }

  @Test
  void pathTraversalHeader_throws_soItNeverReachesFilesystemResolution() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("X-Tenant-Id", "../../etc/passwd");
    assertThatThrownBy(() -> ctx.tenantId(req))
        .isInstanceOf(TenantContext.MissingTenantException.class);
  }

  // ---- operator allowlist (requireAllowlistedOperator) --------------------------------------

  private static MockHttpServletRequest reqWithOperator(String operator) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    if (operator != null) {
      req.addHeader("X-Operator-Id", operator);
    }
    return req;
  }

  @Test
  void allowlistedOperator_returnsOperator_caseInsensitiveAndTrimmedConfig() {
    // Config entry has surrounding whitespace + different case; the request matches after
    // trim + case-insensitive normalization.
    TenantContext c = new TenantContext("  RIDOPARK , other@x.com ");
    assertThat(c.requireAllowlistedOperator(reqWithOperator("ridopark"))).isEqualTo("ridopark");
  }

  @Test
  void nonAllowlistedOperator_throwsUnauthorized() {
    TenantContext c = new TenantContext("ridopark");
    assertThatThrownBy(() -> c.requireAllowlistedOperator(reqWithOperator("intruder")))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
  }

  @Test
  void emptyAllowlist_deniesAll_throwsUnauthorized() {
    TenantContext c = new TenantContext(""); // empty = deny-all (fail-closed)
    assertThatThrownBy(() -> c.requireAllowlistedOperator(reqWithOperator("ridopark")))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
  }

  @Test
  void requireAllowlistedOperator_missingHeader_stillThrowsMissingOperator_not403() {
    TenantContext c = new TenantContext("ridopark");
    assertThatThrownBy(() -> c.requireAllowlistedOperator(reqWithOperator(null)))
        .isInstanceOf(TenantContext.MissingOperatorException.class);
  }
}
