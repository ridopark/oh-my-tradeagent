package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PLAN-2026-08-10-live-manual-bto: DEFAULT state of the manual-entry surface — the dark-launch flag
 * ({@code entries.manual.write-enabled}) is unset, so every route 404s SERVER-SIDE. The write
 * surface is not merely hidden on the dashboard: this is the only BFF route that can OPEN a
 * real-money position, so it must be unreachable until the operator arms it deliberately.
 *
 * <p>Deliberately no {@code @TestPropertySource} — the absence of the property IS the case under
 * test.
 */
@WebMvcTest(ManualEntryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class ManualEntryDarkLaunchTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private MarketDataQuoteClient quotes;
  @MockitoBean private StrategyConfigReader strategyConfigs;

  @Test
  void manual_flagUnset_404sAndTouchesNothing() throws Exception {
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"occ\":\"NVDA 260821C00225000\",\"strategy_id\":\"copytrade-v1\",\"qty\":3,"
                        + "\"quoted_ask\":2.35,\"quoted_at\":\"2026-08-10T14:00:00Z\","
                        + "\"idempotency_key\":\"idem-1\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("manual_entry_disabled"));

    // No Temporal client call, no quote read, no config read — the refusal is the first thing that
    // happens, BEFORE even the tenant resolution.
    verifyNoInteractions(client);
    verifyNoInteractions(quotes);
    verifyNoInteractions(strategyConfigs);
  }

  @Test
  void quote_flagUnset_404s() throws Exception {
    // The preview is gated too: it must not be a probe for which contracts are quotable while the
    // write is dark.
    mvc.perform(
            get("/api/entries/quote")
                .param("occ", "NVDA 260821C00225000")
                .header("X-Tenant-Id", "acme"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("manual_entry_disabled"));

    verifyNoInteractions(quotes);
  }

  @Test
  void status_flagUnset_404s() throws Exception {
    mvc.perform(
            get("/api/entries/manual:idem-1/status")
                .param("strategy_id", "copytrade-v1")
                .header("X-Tenant-Id", "acme"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("manual_entry_disabled"));

    verifyNoInteractions(client);
  }
}
