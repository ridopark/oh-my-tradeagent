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
 * Dark-gate proof for the SECOND name in the conditional: with {@code options-chat.enabled=true}
 * but {@code dashboard.writer.enabled=false}, both routes must still 404.
 *
 * <p>This is the case that matters operationally. Both controllers reach the DB through {@code
 * dashboardWriterDsl}, which is ITSELF {@code @ConditionalOnProperty("dashboard.writer.enabled")}.
 * Gating them on the feature flag alone would mean that enabling {@code OPTIONS_CHAT_ENABLED} on a
 * cluster whose writer is off does not produce a quiet 404 — it produces {@code
 * NoSuchBeanDefinitionException} at context startup, i.e. a BFF that will not boot and takes the
 * whole dashboard down with it. Losing the second name in the conditional is exactly the kind of
 * "harmless simplification" a later refactor makes, so it is pinned here.
 */
@WebMvcTest({OptionsChatController.class, OptionsChatIngestController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "options-chat.enabled=true",
      "dashboard.writer.enabled=false",
      "options-chat.channel-id=786109983065505792"
    })
class OptionsChatWriterDisabledWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private OptionsChatRepository repo;

  @Test
  void writerFlagOff_readRouteIs404() throws Exception {
    mvc.perform(get("/api/options-chat/messages").header("X-Tenant-Id", "prod_real"))
        .andExpect(status().isNotFound());
  }

  @Test
  void writerFlagOff_ingestRouteIs404() throws Exception {
    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content("{\"channel_id\":\"786109983065505792\",\"messages\":[]}"))
        .andExpect(status().isNotFound());
  }
}
