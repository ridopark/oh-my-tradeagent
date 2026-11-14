package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.mcp.McpGrantsReader;
import com.ohmytradeagent.tdbff.mcp.McpGrantsReader.McpGrantRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seam D': web-layer contract for {@code GET /api/mcp/grants}. Filters OFF; identity comes from
 * {@code X-Identity-Provider} and {@code X-Identity-Subject} headers injected by the dashboard
 * server (same trust model as {@code X-Tenant-Id} behind the shared token). The DB boundary is
 * represented by the mocked {@link McpGrantsReader}. Dark-gate with flag off → 404 lives in {@link
 * McpGrantsControllerDisabledWebMvcTest}.
 */
@WebMvcTest(McpGrantsController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "bff.mcp-delegated.enabled=true")
class McpGrantsControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private McpGrantsReader reader;

  @Test
  void grants_list_returnsOnlyThisIdentitysGrants_sortedNewestFirst() throws Exception {
    // Reader returns two grants for this identity; a third for another identity is NOT returned
    // because the reader is called with exactly the identity from the headers.
    McpGrantRow g1 =
        new McpGrantRow(
            "id-1",
            "My App",
            "client-1",
            List.of("acme"),
            "2026-11-01T10:00:00Z",
            "2026-11-02T12:00:00Z",
            null);
    McpGrantRow g2 =
        new McpGrantRow(
            "id-2",
            null,
            "client-2",
            List.of("acme", "beta"),
            "2026-10-15T09:00:00Z",
            null,
            "2026-11-03T08:00:00Z");

    when(reader.listForIdentity("google", "sub-123")).thenReturn(List.of(g1, g2));

    mvc.perform(
            get("/api/mcp/grants")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grants.length()").value(2))
        .andExpect(jsonPath("$.grants[0].id").value("id-1"))
        .andExpect(jsonPath("$.grants[0].client_name").value("My App"))
        .andExpect(jsonPath("$.grants[0].client_id").value("client-1"))
        .andExpect(jsonPath("$.grants[0].tenants[0]").value("acme"))
        .andExpect(jsonPath("$.grants[0].created_at").value("2026-11-01T10:00:00Z"))
        .andExpect(jsonPath("$.grants[0].last_used_at").value("2026-11-02T12:00:00Z"))
        .andExpect(jsonPath("$.grants[0].revoked_at").isEmpty())
        .andExpect(jsonPath("$.grants[1].id").value("id-2"))
        .andExpect(jsonPath("$.grants[1].client_name").isEmpty())
        .andExpect(jsonPath("$.grants[1].revoked_at").value("2026-11-03T08:00:00Z"));
  }

  @Test
  void grants_list_missingIdentityHeaders_is401() throws Exception {
    mvc.perform(get("/api/mcp/grants"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_identity"));

    verifyNoInteractions(reader);
  }

  @Test
  void grants_list_missingSubjectHeader_is401() throws Exception {
    mvc.perform(get("/api/mcp/grants").header("X-Identity-Provider", "google"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_identity"));

    verifyNoInteractions(reader);
  }

  @Test
  void grants_list_badSubjectCharset_is400() throws Exception {
    // Subject must match [A-Za-z0-9._-]{1,255}; a space is invalid.
    mvc.perform(
            get("/api/mcp/grants")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "bad subject!"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(reader);
  }

  @Test
  void grants_list_badProviderCharset_is400() throws Exception {
    // Provider must match [a-z]{1,32}; uppercase is invalid.
    mvc.perform(
            get("/api/mcp/grants")
                .header("X-Identity-Provider", "Google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(reader);
  }

  @Test
  void grants_list_readerCalledWithHeaderIdentity() throws Exception {
    when(reader.listForIdentity(eq("google"), eq("sub-456"))).thenReturn(List.of());

    mvc.perform(
            get("/api/mcp/grants")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-456"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grants.length()").value(0));
  }
}
