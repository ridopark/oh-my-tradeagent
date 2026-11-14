package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
    // If the controller were registered, this stub would make it answer 204 — so a 404 here can
    // only mean the route is absent (a default mock returning false would also give 404).
    when(revoker.revoke(any(), any(), any())).thenReturn(true);
    mvc.perform(
            post("/api/mcp/grants/11111111-1111-4111-8111-111111111111/revoke")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isNotFound());
  }
}
