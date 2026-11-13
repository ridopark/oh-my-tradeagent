package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.trades.TradesReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seam C: filter → wrapper → {@link TenantContext} → handler, end to end.
 *
 * <p>Filters are ON (default for {@code @WebMvcTest}). The real {@link
 * com.ohmytradeagent.tdbff.security.ServiceTokenFilter} is wired into the MockMvc pipeline with the
 * acme MCP hash set via {@code @TestPropertySource}; {@code bff.service-token} resolves to the yml
 * default {@code dev-shared-token} (no prod profile, so the well-known-token boot guard does not
 * fire). {@link TradesController} is included in the slice for test 9 so a real tenant-read handler
 * proves the wrapper's tenant injection end to end.
 */
@WebMvcTest({McpWhoamiController.class, TradesController.class})
@Import(TenantContext.class)
@TestPropertySource(
    properties =
        "bff.mcp-read-tokens=87121ec9ab39dfe5f0915c4b2b25f2592e80aebba9d1ee1a081caf28491478fc:acme")
class McpWhoamiEndToEndWebMvcTest {

  // sha256("test-mcp-token-acme") — computed outside the code under test.
  private static final String MCP_ACME_TOKEN = "test-mcp-token-acme";

  @Autowired private MockMvc mvc;

  // Required by TradesController in the slice (test 9); lenient for tests that never reach it.
  @MockitoBean private TradesReader reader;
  @MockitoBean private TenantStrategyResolver strategyResolver;

  @Test
  void mcpToken_getsItsBinding() throws Exception {
    // No X-Tenant-Id on the request; the wrapper injects the bound tenant "acme".
    // Response must be byte-equal to the expected JSON (strict mode).
    mvc.perform(get("/api/mcp/whoami").header("Authorization", "Bearer " + MCP_ACME_TOKEN))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"tenant_id\":\"acme\",\"token_id\":\"87121ec9ab39\",\"scope\":\"read\"}",
                    true));
  }

  @Test
  void mcpToken_withForgedTenant_is403() throws Exception {
    // X-Tenant-Id: beta does not match the bound tenant "acme" → 403 at the filter.
    mvc.perform(
            get("/api/mcp/whoami")
                .header("Authorization", "Bearer " + MCP_ACME_TOKEN)
                .header("X-Tenant-Id", "beta"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void mcpToken_post_is403() throws Exception {
    // POST is not GET/HEAD → 403 at the filter's method gate; handler never runs.
    mvc.perform(post("/api/mcp/whoami").header("Authorization", "Bearer " + MCP_ACME_TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void sharedToken_is403() throws Exception {
    // The shared token passes the filter (rule 4 — no attribute set), reaches the controller,
    // which returns 403 because MCP_TOKEN_ID_ATTRIBUTE is absent.
    mvc.perform(
            get("/api/mcp/whoami")
                .header("Authorization", "Bearer dev-shared-token")
                .header("X-Tenant-Id", "acme"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void unknownToken_is401() throws Exception {
    // Unrecognised token → filter rule 6 → 401 with the standard body.
    mvc.perform(get("/api/mcp/whoami").header("Authorization", "Bearer not-a-valid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("unauthorized"));
  }

  @Test
  void mcpToken_canReadAnAllowlistedRoute_asItsTenant() throws Exception {
    // End-to-end proof that the wrapper injects the bound tenant into a real tenant-read handler.
    // GET /api/trades with no X-Tenant-Id — the wrapper must supply "acme" to TradesController.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(reader.trades(eq("acme"), eq(List.of("s1")), isNull(), anyInt()))
        .thenReturn(List.of(Map.of("event_id", "e1", "kind", "EntryFilled")));

    mvc.perform(get("/api/trades").header("Authorization", "Bearer " + MCP_ACME_TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"));

    // Verify the reader was called with "acme" — proves the wrapper's tenant injection reached
    // the handler, not a header the client supplied.
    verify(reader).trades(eq("acme"), any(), any(), anyInt());
  }
}
