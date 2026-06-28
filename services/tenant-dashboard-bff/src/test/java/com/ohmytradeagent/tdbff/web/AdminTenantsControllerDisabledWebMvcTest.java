package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.credentials.AdminTenantAccountReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof: with {@code operator.admin-read.enabled=false} the {@code
 * AdminTenantsController} bean is NOT registered (its {@code @ConditionalOnProperty}), so {@code
 * GET /api/admin/tenants} has no handler and 404s. Filters disabled so this is purely the
 * absent-route assertion, not the bearer 401. The collaborator readers are mocked so the slice
 * context still starts even though the controller bean is excluded.
 */
@WebMvcTest(AdminTenantsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "operator.admin-read.enabled=false")
class AdminTenantsControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private DbStrategyConfigReader strategyConfigReader;
  @MockitoBean private AdminTenantAccountReader accountReader;
  @MockitoBean private LivePromotionStateReader livePromotionStateReader;

  @Test
  void darkFlagOff_routeIs404() throws Exception {
    mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "ridopark"))
        .andExpect(status().isNotFound());
  }
}
