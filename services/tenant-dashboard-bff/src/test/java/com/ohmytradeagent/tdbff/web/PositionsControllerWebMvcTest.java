package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Guards the shared no-`dev`-fallback contract at the web layer for {@code /api/positions}. */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class PositionsControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private PositionsReader reader;
  // Added when the controller grew the force-close write path (needs a WorkflowClient); the GET
  // tests below never touch it, but the bean must exist for the context to load.
  @MockitoBean private io.temporal.client.WorkflowClient client;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/positions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedPositions() throws Exception {
    when(reader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1", "s1", "SYM", 2, new BigDecimal("1.50"), new BigDecimal("300"))));

    mvc.perform(get("/api/positions").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].contract_symbol").value("SYM"));
  }
}
