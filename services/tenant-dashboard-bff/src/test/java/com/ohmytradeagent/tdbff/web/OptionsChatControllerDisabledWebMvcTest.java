package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof for the FEATURE flag: with {@code options-chat.enabled=false} (writer flag ON, so
 * the absence is attributable to this flag alone) neither controller bean is registered and both
 * routes 404. Filters disabled, so this is purely the absent-route assertion.
 *
 * <p>The ingest route is asserted here too, not just the read: shipping a live write endpoint that
 * accepts untrusted third-party content while nothing consumes it would be worse than shipping
 * nothing.
 */
@WebMvcTest({OptionsChatController.class, OptionsChatIngestController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "options-chat.enabled=false",
      "dashboard.writer.enabled=true",
      "options-chat.channel-id=786109983065505792"
    })
class OptionsChatControllerDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private OptionsChatRepository repo;

  @Test
  void featureFlagOff_readRouteIs404() throws Exception {
    mvc.perform(get("/api/options-chat/messages").header("X-Tenant-Id", "prod_real"))
        .andExpect(status().isNotFound());
  }

  @Test
  void featureFlagOff_ingestRouteIs404() throws Exception {
    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content("{\"channel_id\":\"786109983065505792\",\"messages\":[]}"))
        .andExpect(status().isNotFound());
  }
}
