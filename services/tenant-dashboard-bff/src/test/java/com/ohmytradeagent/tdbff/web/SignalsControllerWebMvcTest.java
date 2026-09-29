package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.trades.SignalsReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer slice for {@code /api/signals}; mirrors {@link TradesControllerWebMvcTest}. */
@WebMvcTest(SignalsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class SignalsControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private SignalsReader reader;
  @MockitoBean private TenantStrategyResolver strategyResolver;

  @BeforeEach
  void resolveTenantStrategies() {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
  }

  @Test
  void missingTenantHeaderIs401() throws Exception {
    // Same no-`dev`-fallback contract every tenant-facing read has: absent X-Tenant-Id -> 401.
    mvc.perform(get("/api/signals"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedSignals() throws Exception {
    when(reader.signals(eq("acme"), eq(List.of("s1")), anyInt()))
        .thenReturn(
            List.of(Map.of("strategy_id", "s1", "subject", "{\"option_symbol\":\"INTC  1C1\"}")));

    mvc.perform(get("/api/signals").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.count").value(1))
        // The dashboard parses `subject` as a JSON STRING, so it must serialize as one here too —
        // if this ever becomes an object, the manual-entry picker silently renders no options.
        .andExpect(jsonPath("$.items[0].subject").isString());
  }

  @Test
  void tenantWithNoStrategiesGetsEmptyListNotAllTenants() throws Exception {
    // A tenant that resolves to zero strategies must not fall through to an unscoped read.
    when(strategyResolver.strategyIdsForTenant("nobody")).thenReturn(List.of());
    when(reader.signals(eq("nobody"), eq(List.of()), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/signals").header("X-Tenant-Id", "nobody"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }
}
