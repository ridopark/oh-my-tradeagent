package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.credentials.BrokerCredentialStatusReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web-layer contract for {@code /api/broker-credentials/status}: the shared no-`dev`-fallback 401,
 * the {@code {tenant_id,count,items}} envelope, and a hard guard that NO secret column name ever
 * appears in the serialized response.
 */
@WebMvcTest(BrokerCredentialStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class BrokerCredentialStatusControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private BrokerCredentialStatusReader reader;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/broker-credentials/status"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedStatusEnvelope() throws Exception {
    when(reader.statuses("acme")).thenReturn(List.of(representativeRow()));

    mvc.perform(get("/api/broker-credentials/status").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].provider").value("alpaca-paper"))
        .andExpect(jsonPath("$.items[0].configured").value(true))
        .andExpect(jsonPath("$.items[0].version").value(7))
        .andExpect(jsonPath("$.items[0].broker_account_id").value("PA3FKGPFYPLH"))
        .andExpect(jsonPath("$.items[0].updated_by").value("ops@acme"));
  }

  @Test
  void responseNeverContainsSecretColumnNames() throws Exception {
    when(reader.statuses("acme")).thenReturn(List.of(representativeRow()));

    MvcResult result =
        mvc.perform(get("/api/broker-credentials/status").header("X-Tenant-Id", "acme"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andReturn();

    String json = result.getResponse().getContentAsString();
    for (String secret :
        List.of("ciphertext", "wrapped_dek", "iv", "dek_iv", "api_secret", "api_key")) {
      Assertions.assertThat(json)
          .as("serialized status response must not leak secret column %s", secret)
          .doesNotContain(secret);
    }
  }

  private static Map<String, Object> representativeRow() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("provider", "alpaca-paper");
    m.put("configured", true);
    m.put("version", 7L);
    m.put("broker_account_id", "PA3FKGPFYPLH");
    m.put("updated_at", "2026-06-14T12:00:00Z");
    m.put("updated_by", "ops@acme");
    return m;
  }
}
