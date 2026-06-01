package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.trades.TradesReader;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice for the BFF: exercises the controller + the real {@link TenantContext} + {@link
 * GlobalExceptionHandler} wired into the MVC pipeline. Filters are disabled — the {@link
 * com.ohmytradeagent.tdbff.security.ServiceTokenFilter} has its own unit test; here we cover the
 * tenant-scoping (200), the no-`dev`-fallback contract (401), and exception→status mapping (400).
 */
@WebMvcTest(TradesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class TradesControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private TradesReader reader;
  @MockitoBean private TenantStrategyResolver strategyResolver;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    // A tenant-facing read must never fall back to `dev`: absent X-Tenant-Id -> 401, not 200.
    mvc.perform(get("/api/trades"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedTrades() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(reader.trades(eq("acme"), anyList(), isNull(), anyInt()))
        .thenReturn(List.of(Map.of("event_id", "e1", "kind", "EntryFilled")));

    mvc.perform(get("/api/trades").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].kind").value("EntryFilled"));
  }

  @Test
  void malformedSinceIs400() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    // A bad `since` reaches OffsetDateTime.parse in the reader and throws DateTimeParseException;
    // GlobalExceptionHandler must map it to 400, not 500.
    when(reader.trades(eq("acme"), anyList(), eq("not-a-date"), anyInt()))
        .thenThrow(
            new DateTimeParseException("Text 'not-a-date' could not be parsed", "not-a-date", 0));

    mvc.perform(get("/api/trades").header("X-Tenant-Id", "acme").param("since", "not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));
  }
}
