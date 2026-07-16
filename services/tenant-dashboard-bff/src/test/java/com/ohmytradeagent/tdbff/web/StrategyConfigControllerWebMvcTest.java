package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer contract for {@code GET /api/strategy-config} (UI-P3-a). */
@WebMvcTest(StrategyConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class StrategyConfigControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private StrategyConfigReader reader;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/strategy-config"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantConfigEnvelopeWithVersionAndFieldClasses() throws Exception {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("broker_target", "alpaca-paper");
    config.put("max_contracts", 50);
    config.put("skip_avg", true);
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("strategy_id", "copytrade-v1");
    item.put("version", 3L);
    item.put("config", config);
    when(reader.configsForTenant("acme")).thenReturn(List.of(item));

    mvc.perform(get("/api/strategy-config").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].strategy_id").value("copytrade-v1"))
        // version is the write path's expected_version (optimistic CAS).
        .andExpect(jsonPath("$.items[0].version").value(3))
        .andExpect(jsonPath("$.items[0].config.broker_target").value("alpaca-paper"))
        .andExpect(jsonPath("$.items[0].config.max_contracts").value(50))
        // Field-class metadata so the UI knows what's read-only / tighten-only.
        .andExpect(
            jsonPath("$.field_classes.DANGEROUS")
                .value(org.hamcrest.Matchers.hasItem("broker_target")))
        .andExpect(
            jsonPath("$.field_classes.EXPOSURE")
                .value(org.hamcrest.Matchers.hasItem("max_contracts")))
        .andExpect(
            jsonPath("$.field_classes.IDENTITY").value(org.hamcrest.Matchers.hasItem("tenant_id")));
  }

  @Test
  void fieldClassesMirrorTheWriterGovernance() {
    // broker_account_id + the notional-cap kill-switch gate are DANGEROUS (dual-control); the
    // sizing
    // caps are EXPOSURE (tighten-only). Locks the display-metadata against drift from
    // StrategyConfigWriter. single-account-loss-rule Phase 4a: daily_loss_threshold is a dead field
    // (the account cap is the sole daily-loss breaker), so it is NO LONGER DANGEROUS.
    Map<String, List<String>> fc = StrategyConfigReader.FIELD_CLASSES;
    org.assertj.core.api.Assertions.assertThat(fc.get("DANGEROUS"))
        .contains("broker_target", "broker_account_id", "notional_cap_pct_of_capital_base")
        .doesNotContain("daily_loss_threshold");
    org.assertj.core.api.Assertions.assertThat(fc.get("EXPOSURE"))
        .contains("max_contracts", "min_contracts", "max_positions", "capital_weight");
  }
}
