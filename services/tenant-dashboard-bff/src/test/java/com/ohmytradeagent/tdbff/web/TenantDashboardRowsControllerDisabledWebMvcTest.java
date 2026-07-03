package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof: with {@code operator.tenant-delete.enabled=false} (writer flag on, so the
 * absence is due to the operator flag alone) the {@code TenantDashboardRowsController} bean is NOT
 * registered (its two-name {@code @ConditionalOnProperty} requires BOTH flags), so {@code DELETE
 * /api/admin/tenants/{tenant}/dashboard-rows} has no handler and 404s. Filters disabled so this is
 * purely the absent-route assertion, not the bearer 401.
 */
@WebMvcTest(TenantDashboardRowsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {"operator.tenant-delete.enabled=false", "dashboard.writer.enabled=true"})
class TenantDashboardRowsControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private InviteWriterRepository writer;

  @Test
  void darkFlagOff_routeIs404() throws Exception {
    mvc.perform(
            delete("/api/admin/tenants/acme/dashboard-rows").header("X-Operator-Id", "ridopark"))
        .andExpect(status().isNotFound());
  }
}
