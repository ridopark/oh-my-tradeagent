package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Seam D': web-layer contract for {@code POST /api/mcp/grants/{id}/revoke}. Filters OFF. The DB
 * boundary is represented by the mocked {@link McpGrantsRevoker}. Dark-gate with either flag off →
 * 404 lives in {@link McpGrantsRevokeControllerDisabledWebMvcTest}.
 */
@WebMvcTest(McpGrantsRevokeController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(
    properties = {"bff.mcp-delegated.enabled=true", "dashboard.writer.enabled=true"})
class McpGrantsRevokeControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private McpGrantsRevoker revoker;

  @Test
  void revoke_ownGrant_204_andRevokerCalledWithIdentity() throws Exception {
    when(revoker.revoke(eq("some-grant-id"), eq("google"), eq("sub-123"))).thenReturn(true);

    mvc.perform(
            post("/api/mcp/grants/some-grant-id/revoke")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isNoContent());
  }

  @Test
  void revoke_foreignOrUnknownGrant_404() throws Exception {
    // Revoker returns false when the UPDATE matched 0 rows — either not found or belongs to
    // another identity. Response is always 404 (no membership oracle).
    when(revoker.revoke(eq("foreign-grant-id"), eq("google"), eq("sub-123"))).thenReturn(false);

    mvc.perform(
            post("/api/mcp/grants/foreign-grant-id/revoke")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isNotFound());
  }

  @Test
  void revoke_missingIdentityHeaders_is401() throws Exception {
    mvc.perform(post("/api/mcp/grants/some-id/revoke")).andExpect(status().isUnauthorized());

    verifyNoInteractions(revoker);
  }

  @Test
  void revoke_get_is405() throws Exception {
    // Only POST is mapped; GET on the revoke path → 405 Method Not Allowed.
    mvc.perform(
            get("/api/mcp/grants/some-id/revoke")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isMethodNotAllowed());
  }
}
