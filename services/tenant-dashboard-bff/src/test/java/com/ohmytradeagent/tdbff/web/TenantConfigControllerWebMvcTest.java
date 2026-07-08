package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.TenantConfigReader;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer contract for {@code GET /api/tenant-config} (account-cap read, Phase 2). */
@WebMvcTest(TenantConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class TenantConfigControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private TenantConfigReader reader;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/tenant-config"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTheAccountCapWithVersionAndExposureFieldClasses() throws Exception {
    when(reader.capFor("acme"))
        .thenReturn(
            new TenantConfigReader.TenantCap(new BigDecimal("2500"), new BigDecimal("0.40"), 3L));

    mvc.perform(get("/api/tenant-config").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account_daily_loss_threshold").value(2500))
        .andExpect(jsonPath("$.account_daily_loss_pct").value(0.40))
        // version is Phase 3's expected_version (optimistic CAS).
        .andExpect(jsonPath("$.version").value(3))
        // The two cap fields are marked EXPOSURE (tighten-only) — the UI reuses its badge model.
        .andExpect(
            jsonPath("$.field_classes.EXPOSURE")
                .value(org.hamcrest.Matchers.hasItem("account_daily_loss_threshold")))
        .andExpect(
            jsonPath("$.field_classes.EXPOSURE")
                .value(org.hamcrest.Matchers.hasItem("account_daily_loss_pct")));
  }

  @Test
  void nullCapRendersNullsWithoutError() throws Exception {
    when(reader.capFor("acme")).thenReturn(new TenantConfigReader.TenantCap(null, null, null));

    mvc.perform(get("/api/tenant-config").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account_daily_loss_threshold").doesNotExist())
        .andExpect(jsonPath("$.account_daily_loss_pct").doesNotExist())
        .andExpect(jsonPath("$.version").doesNotExist())
        // The tighten-only metadata is still present so the UI badge model works with a null cap.
        .andExpect(
            jsonPath("$.field_classes.EXPOSURE")
                .value(org.hamcrest.Matchers.hasItem("account_daily_loss_threshold")));
  }

  @Test
  void readerIsQueriedWithTheAuthenticatedTenantOnly() throws Exception {
    // Cross-tenant isolation: the reader is called with the X-Tenant-Id tenant, never any client
    // param. A ?tenant=other query param must not widen scope.
    when(reader.capFor("acme"))
        .thenReturn(new TenantConfigReader.TenantCap(null, new BigDecimal("0.40"), 1L));

    mvc.perform(get("/api/tenant-config").param("tenant", "other").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account_daily_loss_pct").value(0.40));

    org.mockito.Mockito.verify(reader).capFor("acme");
    org.mockito.Mockito.verify(reader, org.mockito.Mockito.never())
        .capFor(ArgumentMatchers.eq("other"));
  }
}
