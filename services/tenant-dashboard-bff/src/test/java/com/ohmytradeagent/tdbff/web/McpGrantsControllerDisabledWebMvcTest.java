package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.mcp.McpGrantsReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof: with {@code bff.mcp-delegated.enabled=false} (the repo default) the {@code
 * McpGrantsController} bean is NOT registered ({@code @ConditionalOnProperty}), so {@code GET
 * /api/mcp/grants} has no handler and 404s.
 */
@WebMvcTest(McpGrantsController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "bff.mcp-delegated.enabled=false")
class McpGrantsControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private McpGrantsReader reader;

  @Test
  void grants_disabled_is404() throws Exception {
    mvc.perform(
            get("/api/mcp/grants")
                .header("X-Identity-Provider", "google")
                .header("X-Identity-Subject", "sub-123"))
        .andExpect(status().isNotFound());
  }
}
