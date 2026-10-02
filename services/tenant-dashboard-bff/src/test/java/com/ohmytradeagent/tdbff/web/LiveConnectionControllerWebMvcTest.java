package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.live.LiveConnectionService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveConnectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class LiveConnectionControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private LiveConnectionService service;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/live/connection")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void returnsTheServiceBodyForTheHeaderTenant() throws Exception {
    when(service.connection("acme"))
        .thenReturn(Map.of("server_time", "2026-09-30T14:00:00Z", "market_open", true));

    mvc.perform(get("/api/live/connection").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.server_time").value("2026-09-30T14:00:00Z"))
        .andExpect(jsonPath("$.market_open").value(true));
  }
}
