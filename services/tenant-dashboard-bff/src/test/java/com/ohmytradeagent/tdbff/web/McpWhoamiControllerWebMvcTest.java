package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.security.ServiceTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seam B: {@link McpWhoamiController} with filters OFF and the token-id attribute injected by hand
 * via {@link
 * org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder#requestAttr}. Catches
 * the response shape and the 403-without-attribute rule; misses how the attribute gets there (that
 * is Seam C, {@link McpWhoamiEndToEndWebMvcTest}).
 */
@WebMvcTest(McpWhoamiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class McpWhoamiControllerWebMvcTest {

  @Autowired private MockMvc mvc;

  @Test
  void whoami_withTokenAttributeAndTenant_returnsBinding() throws Exception {
    mvc.perform(
            get("/api/mcp/whoami")
                .requestAttr(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE, "87121ec9ab39")
                .header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.token_id").value("87121ec9ab39"))
        .andExpect(jsonPath("$.scope").value("read"));
  }

  @Test
  void whoami_withoutTokenAttribute_is403() throws Exception {
    // No MCP_TOKEN_ID_ATTRIBUTE set — simulates the shared token passing the filter.
    // The controller must return 403 (the shared token has no single tenant).
    mvc.perform(get("/api/mcp/whoami").header("X-Tenant-Id", "acme"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void whoami_withAttributeButNoTenantHeader_is401() throws Exception {
    // TenantContext.tenantId() throws MissingTenantException → GlobalExceptionHandler → 401.
    // The filter never produces this combination in practice (the wrapper always injects
    // X-Tenant-Id), but the test pins the fail-closed order: missing tenant wins over having
    // an attribute.
    mvc.perform(
            get("/api/mcp/whoami")
                .requestAttr(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE, "87121ec9ab39"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }
}
