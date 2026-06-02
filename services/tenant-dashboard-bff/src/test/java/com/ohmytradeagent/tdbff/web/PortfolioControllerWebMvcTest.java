package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.portfolio.PortfolioService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Guards the shared no-`dev`-fallback contract at the web layer for {@code /api/portfolio}. */
@WebMvcTest(PortfolioController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class PortfolioControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private PortfolioService service;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/portfolio"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedPortfolio() throws Exception {
    when(service.portfolio("acme"))
        .thenReturn(Map.of("tenant_id", "acme", "open_positions_count", 0));

    mvc.perform(get("/api/portfolio").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.open_positions_count").value(0));
  }
}
