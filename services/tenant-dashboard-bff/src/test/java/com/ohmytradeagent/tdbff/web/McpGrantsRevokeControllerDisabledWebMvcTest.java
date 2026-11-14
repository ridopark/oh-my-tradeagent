package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.mcp.McpGrantsRevoker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof for {@code POST /api/mcp/grants/{id}/revoke}: with {@code
 * dashboard.writer.enabled=false} (repo default) the {@code McpGrantsRevokeController} bean is NOT
 * registered, so the route 404s regardless of {@code bff.mcp-delegated.enabled}.
 */
@WebMvcTest(McpGrantsRevokeController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(
    properties = {"bff.mcp-delegated.enabled=true", "dashboard.writer.enabled=false"})
class McpGrantsRevokeControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private McpGrantsRevoker revoker;

  @Test
  void revoke_writerDisabled_is404() throws Exception {
    mvc.perform(
            post("/api/mcp/grants/some-id/revoke")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isNotFound());
  }
}
