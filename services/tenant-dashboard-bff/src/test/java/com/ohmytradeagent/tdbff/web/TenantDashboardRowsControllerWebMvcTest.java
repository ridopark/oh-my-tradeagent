package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import com.ohmytradeagent.tdbff.invites.InviteWriterRepository.DeletedIdentityCounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for the dark-gated, operator-scoped {@code DELETE
 * /api/admin/tenants/{tenant}/dashboard-rows}: the {@code X-Operator-Id} allowlist gate (400
 * missing / 403 non-allowlisted, resolved BEFORE any delete) and the success envelope echoing the
 * two delete counts. Flag-OFF → 404 lives in {@link
 * TenantDashboardRowsControllerDisabledWebMvcTest}; the without-bearer 401 is proven filter-side in
 * {@link com.ohmytradeagent.tdbff.security.ServiceTokenFilterTest}.
 */
@WebMvcTest(TenantDashboardRowsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "operator.tenant-delete.enabled=true",
      "dashboard.writer.enabled=true",
      "operator.allowlist=ridopark"
    })
class TenantDashboardRowsControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private InviteWriterRepository writer;

  @Test
  void missingOperatorHeaderIs400_beforeAnyDelete() throws Exception {
    mvc.perform(delete("/api/admin/tenants/acme/dashboard-rows"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("missing_operator"));
    verifyNoInteractions(writer);
  }

  @Test
  void nonAllowlistedOperatorIs403_beforeAnyDelete() throws Exception {
    mvc.perform(
            delete("/api/admin/tenants/acme/dashboard-rows").header("X-Operator-Id", "intruder"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
    verifyNoInteractions(writer);
  }

  @Test
  void allowlistedOperator_deletes_andEchoesCounts() throws Exception {
    when(writer.deleteTenantIdentities("staging-paper-2"))
        .thenReturn(new DeletedIdentityCounts(2, 1));

    mvc.perform(
            delete("/api/admin/tenants/staging-paper-2/dashboard-rows")
                .header("X-Operator-Id", "ridopark"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("staging-paper-2"))
        .andExpect(jsonPath("$.deleted_users").value(2))
        .andExpect(jsonPath("$.deleted_invites").value(1));

    verify(writer).deleteTenantIdentities("staging-paper-2");
  }
}
