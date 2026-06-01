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
}
