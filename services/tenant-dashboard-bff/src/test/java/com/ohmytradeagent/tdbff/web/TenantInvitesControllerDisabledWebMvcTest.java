package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
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
 * Dark-gate proof: with {@code operator.tenant-invite.enabled=false} (writer flag on, so the
 * absence is due to the operator flag alone) the {@code TenantInvitesController} bean is NOT
 * registered (its two-name {@code @ConditionalOnProperty} requires BOTH flags), so {@code POST
 * /api/admin/tenant-invites} has no handler and 404s. Filters disabled so this is purely the
 * absent-route assertion, not the bearer 401.
 */
@WebMvcTest(TenantInvitesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {"operator.tenant-invite.enabled=false", "dashboard.writer.enabled=true"})
class TenantInvitesControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private DbStrategyConfigReader strategyConfigReader;
  @MockitoBean private InviteWriterRepository invites;

  @Test
  void darkFlagOff_routeIs404() throws Exception {
    mvc.perform(
            post("/api/admin/tenant-invites")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"tenant_id\":\"acme\"}"))
        .andExpect(status().isNotFound());
  }
}
