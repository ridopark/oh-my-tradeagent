package com.ohmytradeagent.tdbff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-gate proof for BOTH names of the two-name {@code @ConditionalOnProperty}. Each nested class
 * turns off exactly one flag (leaving the other on, so an absent route is attributable to the flag
 * under test) and gets its own Spring context via its own {@code @TestPropertySource}.
 *
 * <p>The ingest route is asserted alongside the read, not just the read: shipping a live write
 * endpoint that accepts untrusted third-party content while nothing consumes it would be worse than
 * shipping nothing.
 *
 * <p>Filters are disabled throughout, so these are purely absent-route assertions rather than the
 * bearer 401 (proven in {@code ServiceTokenFilterTest}).
 */
class OptionsChatDarkGateWebMvcTest {

  /** Both routes, asserted identically under whichever flag the enclosing class turned off. */
  abstract static class BothRoutesAre404 {

    @Autowired protected MockMvc mvc;
    @MockitoBean protected OptionsChatRepository repo;

    @Test
    void readRouteIs404() throws Exception {
      mvc.perform(get("/api/options-chat/messages").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
    }

    @Test
    void ingestRouteIs404() throws Exception {
      mvc.perform(
              post("/internal/options-chat/ingest")
                  .contentType("application/json")
                  .content("{\"channel_id\":\"786109983065505792\",\"messages\":[]}"))
          .andExpect(status().isNotFound());
    }
  }

  /** The feature flag itself off. */
  @Nested
  @WebMvcTest({OptionsChatController.class, OptionsChatIngestController.class})
  @AutoConfigureMockMvc(addFilters = false)
  @Import({TenantContext.class, OptionsChatChannels.class})
  @TestPropertySource(
      properties = {
        "options-chat.enabled=false",
        "dashboard.writer.enabled=true",
        // Labelled form, matching production config, so the tests exercise the real shape.
        "options-chat.channel-ids=786109983065505792:Discussion,769797179992571914:Signals"
      })
  class FeatureFlagOff extends BothRoutesAre404 {}

  /**
   * The writer flag off — the case that matters operationally. Both controllers reach the DB
   * through {@code dashboardWriterDsl}, which is ITSELF
   * {@code @ConditionalOnProperty("dashboard.writer.enabled")}. Gating them on the feature flag
   * alone would mean enabling {@code OPTIONS_CHAT_ENABLED} on a cluster whose writer is off
   * produces {@code NoSuchBeanDefinitionException} at context startup — a BFF that will not boot,
   * taking the whole dashboard with it — instead of a quiet 404. Dropping the second name is
   * exactly the kind of "harmless simplification" a later refactor makes, so it is pinned here.
   */
  @Nested
  @WebMvcTest({OptionsChatController.class, OptionsChatIngestController.class})
  @AutoConfigureMockMvc(addFilters = false)
  @Import({TenantContext.class, OptionsChatChannels.class})
  @TestPropertySource(
      properties = {
        "options-chat.enabled=true",
        "dashboard.writer.enabled=false",
        // Labelled form, matching production config, so the tests exercise the real shape.
        "options-chat.channel-ids=786109983065505792:Discussion,769797179992571914:Signals"
      })
  class WriterFlagOff extends BothRoutesAre404 {}
}
