package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof: with {@code dashboard.writer.enabled=false} (the repo default — no writer creds)
 * the {@code ProvisioningBindController} bean is NOT registered (its
 * {@code @ConditionalOnProperty}), so {@code POST /internal/provisioning/bind} has no handler and
 * 404s. The bind path is inert until a cluster enables the writer datasource.
 */
@WebMvcTest(ProvisioningBindController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "dashboard.writer.enabled=false")
class ProvisioningBindControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private InviteWriterRepository invites;

  @Test
  void darkFlagOff_routeIs404() throws Exception {
    mvc.perform(
            post("/internal/provisioning/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"google\",\"subject\":\"s\",\"email\":\"a@b.com\"}"))
        .andExpect(status().isNotFound());
  }
}
