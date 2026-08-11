package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PLAN-2026-08-10-live-manual-bto: {@code entries.manual.allowed-tenants} narrows the write flag to
 * named tenants.
 *
 * <p>Without it the flag is all-or-nothing across the single shared BFF deployment, so arming the
 * paper canary would arm the real-money tenants in the same instant — making the plan's
 * paper-canary-first sequence impossible to actually run. Here the flag is ON and the allowlist
 * admits only {@code staging_paper}.
 */
@WebMvcTest(ManualEntryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "entries.manual.write-enabled=true",
      "entries.manual.allowed-tenants=staging_paper"
    })
class ManualEntryTenantAllowlistTest {

  private static final String BODY =
      "{\"occ\":\"NVDA 260821C00225000\",\"strategy_id\":\"copytrade-v1\",\"qty\":3,"
          + "\"quoted_ask\":2.35,\"quoted_at\":\"2026-08-10T14:00:00Z\","
          + "\"idempotency_key\":\"idem-1\"}";

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private MarketDataQuoteClient quotes;
  @MockitoBean private StrategyConfigReader strategyConfigs;

  @Test
  void tenantOutsideTheAllowlist_isRefusedOnEveryRoute_evenThoughTheFlagIsOn() {
    // prod_real must be untouched while the canary runs on staging_paper.
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            mvc.perform(
                    post("/api/entries/manual")
                        .header("X-Tenant-Id", "prod_real")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                // Same body as flag-off: not an oracle for who else is armed.
                .andExpect(jsonPath("$.error").value("manual_entry_disabled")),
        () ->
            mvc.perform(
                    get("/api/entries/quote")
                        .param("occ", "NVDA 260821C00225000")
                        .header("X-Tenant-Id", "prod_real"))
                .andExpect(status().isNotFound()),
        () ->
            mvc.perform(
                    get("/api/entries/manual:idem-1/status")
                        .param("strategy_id", "copytrade-v1")
                        .header("X-Tenant-Id", "prod_real"))
                .andExpect(status().isNotFound()));

    verify(client, never())
        .newUntypedWorkflowStub(anyString(), any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void allowlistedTenant_reachesTheRoute() throws Exception {
    // Reaches the handler and fails LATER (no quote stubbed → 503), which is what proves the
    // allowlist admitted it rather than short-circuiting at the door.
    mvc.perform(
            get("/api/entries/quote")
                .param("occ", "NVDA 260821C00225000")
                .header("X-Tenant-Id", "staging_paper"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("quote_unavailable"));
  }
}
