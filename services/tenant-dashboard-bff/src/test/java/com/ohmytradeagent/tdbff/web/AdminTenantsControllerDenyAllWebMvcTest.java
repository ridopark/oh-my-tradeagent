package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.credentials.AdminTenantAccountReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fail-closed proof: with the admin-read route ENABLED but {@code operator.allowlist} UNSET
 * (empty), the allowlist denies ALL operators — even a well-formed {@code X-Operator-Id} gets a
 * generic 403, end-to-end, and no tenant data is read. A misconfigured deploy must 403, never
 * allow-all.
 */
@WebMvcTest(AdminTenantsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "operator.admin-read.enabled=true")
class AdminTenantsControllerDenyAllWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private DbStrategyConfigReader strategyConfigReader;
  @MockitoBean private AdminTenantAccountReader accountReader;
  @MockitoBean private LivePromotionStateReader livePromotionStateReader;

  @Test
  void emptyAllowlist_deniesAll_wellFormedOperatorIs403() throws Exception {
    mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "ridopark"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
    Mockito.verifyNoInteractions(strategyConfigReader);
  }
}
