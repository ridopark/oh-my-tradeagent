package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for the service-token-gated {@code POST /internal/provisioning/bind}: it maps
 * only {@code (provider, subject, email)} to the writer repo (NO caller tenant is ever read),
 * echoes the granted tenants, and collapses every no-match/blank-input case to the same empty-grant
 * response. Flag-OFF → 404 lives in {@link ProvisioningBindControllerDisabledWebMvcTest}; the
 * bearer 401 lives in {@code ServiceTokenFilterTest}.
 */
@WebMvcTest(ProvisioningBindController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "dashboard.writer.enabled=true")
class ProvisioningBindControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private InviteWriterRepository invites;

  @Test
  void matchingInvite_returnsGrantedTenant() throws Exception {
    when(invites.bindMatchingInvites("google", "sub-1", "Foo@Bar.com")).thenReturn(List.of("acme"));

    mvc.perform(
            post("/internal/provisioning/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"provider\":\"google\",\"subject\":\"sub-1\",\"email\":\"Foo@Bar.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.granted[0]").value("acme"))
        .andExpect(jsonPath("$.granted.length()").value(1));
  }

  @Test
  void callerSuppliedTenantIsIgnored_onlyInviteTenantGranted() throws Exception {
    // The body carries a rogue tenant_id; the controller must NOT read it — the granted tenant
    // comes
    // solely from the repo (the matched invite). Repo is stubbed for the 3-arg identity only.
    when(invites.bindMatchingInvites("google", "sub-2", "x@y.com")).thenReturn(List.of("acme"));

    mvc.perform(
            post("/internal/provisioning/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"provider\":\"google\",\"subject\":\"sub-2\",\"email\":\"x@y.com\","
                        + "\"tenant_id\":\"victim-tenant\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.granted[0]").value("acme"))
        .andExpect(jsonPath("$.granted.length()").value(1));
  }

  @Test
  void multipleTenantsForOneEmail_allGranted() throws Exception {
    when(invites.bindMatchingInvites("facebook", "sub-3", "multi@x.com"))
        .thenReturn(List.of("acme", "globex"));

    mvc.perform(
            post("/internal/provisioning/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"provider\":\"facebook\",\"subject\":\"sub-3\",\"email\":\"multi@x.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.granted.length()").value(2))
        .andExpect(jsonPath("$.granted[0]").value("acme"))
        .andExpect(jsonPath("$.granted[1]").value("globex"));
  }

  @Test
  void noMatch_returnsEmptyGrant() throws Exception {
    when(invites.bindMatchingInvites(eq("google"), eq("sub-x"), eq("none@x.com")))
        .thenReturn(List.of());

    mvc.perform(
            post("/internal/provisioning/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"provider\":\"google\",\"subject\":\"sub-x\",\"email\":\"none@x.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.granted.length()").value(0));
  }

  @Test
  void blankIdentityFields_collapseToEmptyGrant_noRepoCall() throws Exception {
    // Missing subject: no oracle, no 4xx — same empty-grant shape as a genuine no-match.
    mvc.perform(
            post("/internal/provisioning/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"google\",\"email\":\"a@b.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.granted.length()").value(0));
    verifyNoInteractions(invites);
  }
}
