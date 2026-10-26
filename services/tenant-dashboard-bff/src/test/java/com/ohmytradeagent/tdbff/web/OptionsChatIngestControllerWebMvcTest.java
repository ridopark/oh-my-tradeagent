package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestMessage;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredAttachment;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredEmbed;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredMessage;
import com.ohmytradeagent.tdbff.security.ServiceTokenFilter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for {@code POST /internal/options-chat/ingest} and the poller's {@code GET
 * /internal/options-chat/messages}. The token isolation (these routes accept ONLY {@code
 * OPTIONS_CHAT_INGEST_TOKEN}, never the shared token) is proven filter-side in {@code
 * ServiceTokenFilterTest}; filters are disabled here so these assertions are about the handler
 * alone — except {@link WithTheRealTokenFilter}, which runs the GET through the real filter so the
 * least-privilege claim of the lotto poller is pinned end-to-end.
 */
@WebMvcTest(OptionsChatIngestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TenantContext.class, OptionsChatChannels.class})
@TestPropertySource(
    properties = {
      "options-chat.enabled=true",
      "dashboard.writer.enabled=true",
      // Labelled form, matching production config, so the tests exercise the real shape.
      "options-chat.channel-ids=786109983065505792:Discussion,769797179992571914:Signals,880503518878892143:Lotto"
    })
class OptionsChatIngestControllerWebMvcTest {

  private static final long CHANNEL = 786109983065505792L;
  private static final long SIGNALS = 769797179992571914L;
  private static final long LOTTO = 880503518878892143L;

  @Autowired private MockMvc mvc;
  @MockitoBean private OptionsChatRepository repo;

  private static StoredMessage lottoPost(long id) {
    return new StoredMessage(
        id,
        "TradingTheTrend",
        "#ff0004",
        "https://cdn.discordapp.com/avatar.png",
        OffsetDateTime.of(2026, 10, 26, 14, 3, 11, 0, ZoneOffset.UTC),
        "BTO MU 10/31 1050c @ .51",
        1273987654321098700L,
        false,
        null,
        // Children are present on the record so the test proves the GET drops them, rather than
        // passing because there were none to drop.
        List.of(new StoredAttachment(42L, "image", "chart.png", null, 800, 600, "pending")),
        List.of(new StoredEmbed("t", "d", "https://example.com", "a", "f", null)));
  }

  @Test
  void getMessages_returnsAscendingItemsWithoutChildren_andClampsLimit() throws Exception {
    when(repo.since(eq(LOTTO), eq(1273987654321098000L), eq(200)))
        .thenReturn(List.of(lottoPost(1273987654321098765L)));

    mvc.perform(
            get("/internal/options-chat/messages")
                .param("channel", "880503518878892143")
                .param("after", "1273987654321098000")
                .param("limit", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel_id").value("880503518878892143"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].message_id").value("1273987654321098765"))
        .andExpect(jsonPath("$.items[0].author_name").value("TradingTheTrend"))
        .andExpect(jsonPath("$.items[0].author_color").value("#ff0004"))
        .andExpect(jsonPath("$.items[0].posted_at").value("2026-10-26T14:03:11Z"))
        .andExpect(jsonPath("$.items[0].content").value("BTO MU 10/31 1050c @ .51"))
        .andExpect(jsonPath("$.items[0].reply_to_id").value("1273987654321098700"))
        .andExpect(jsonPath("$.items[0].edited").value(false))
        .andExpect(jsonPath("$.items[0].deleted").value(false))
        .andExpect(jsonPath("$.items[0].attachments").doesNotExist())
        .andExpect(jsonPath("$.items[0].embeds").doesNotExist());
  }

  @Test
  void getMessages_defaultsAfterToZeroAndLimitTo100() throws Exception {
    when(repo.since(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

    mvc.perform(get("/internal/options-chat/messages").param("channel", "880503518878892143"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0))
        .andExpect(jsonPath("$.items").isEmpty());

    verify(repo).since(LOTTO, 0L, 100);
  }

  @Test
  void getMessages_unknownOrMissingChannelIs400_andNothingIsRead() throws Exception {
    // NOT the read page's fallback-to-default: a poller aimed at the wrong channel must fail
    // loudly, never silently trade on the discussion room.
    mvc.perform(get("/internal/options-chat/messages").param("channel", "999999999999999999"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
    mvc.perform(get("/internal/options-chat/messages").param("channel", "not-a-snowflake"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/internal/options-chat/messages")).andExpect(status().isBadRequest());

    verifyNoInteractions(repo);
  }

  @Test
  void getMessages_nonNumericCursorIs400() throws Exception {
    mvc.perform(
            get("/internal/options-chat/messages")
                .param("channel", "880503518878892143")
                .param("after", "yesterday"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(repo);
  }

  /**
   * The ONE test in this class that runs the real {@link ServiceTokenFilter}: the GET lives under
   * the ingest prefix, so holding the ingest token is sufficient and holding the BFF shared token
   * (which opens every tenant's real-money reads) is NOT — the poller never needs it.
   */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(OptionsChatIngestController.class)
  @Import({TenantContext.class, OptionsChatChannels.class, ServiceTokenFilter.class})
  @TestPropertySource(
      properties = {
        "options-chat.enabled=true",
        "dashboard.writer.enabled=true",
        "options-chat.channel-ids=786109983065505792:Discussion,880503518878892143:Lotto",
        "bff.service-token=test-shared-token",
        "options-chat.ingest-token=test-ingest-token"
      })
  class WithTheRealTokenFilter {

    @Autowired private MockMvc mvc;
    @MockitoBean private OptionsChatRepository repo;

    @Test
    void getMessages_ingestTokenOk_sharedTokenRejected_unknownChannel400() throws Exception {
      when(repo.since(eq(LOTTO), eq(0L), anyInt()))
          .thenReturn(List.of(lottoPost(1273987654321098765L)));

      mvc.perform(
              get("/internal/options-chat/messages")
                  .param("channel", "880503518878892143")
                  .header("Authorization", "Bearer test-ingest-token"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].message_id").value("1273987654321098765"));

      mvc.perform(
              get("/internal/options-chat/messages")
                  .param("channel", "880503518878892143")
                  .header("Authorization", "Bearer test-shared-token"))
          .andExpect(status().isUnauthorized());

      mvc.perform(get("/internal/options-chat/messages").param("channel", "880503518878892143"))
          .andExpect(status().isUnauthorized());

      mvc.perform(
              get("/internal/options-chat/messages")
                  .param("channel", "999999999999999999")
                  .header("Authorization", "Bearer test-ingest-token"))
          .andExpect(status().isBadRequest());

      // Exactly the one authorized, allow-listed read reached the store.
      verify(repo).since(LOTTO, 0L, 100);
      verify(repo, never()).since(eq(999999999999999999L), anyLong(), anyInt());
    }
  }

  @Test
  void storesABatchAndReportsHowManyWereNew() throws Exception {
    when(repo.ingest(anyLong(), anyList())).thenReturn(1);

    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content(
                    """
                    {"channel_id":"786109983065505792","messages":[
                      {"message_id":"1273987654321098765","author_name":"TradingTheTrend",
                       "posted_at":"2026-08-12T14:03:11Z","content":"NVDA strong",
                       "attachments":[{"kind":"image","source_url":"https://cdn.discordapp.com/a.png"}]}
                    ]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(1))
        .andExpect(jsonPath("$.stored").value(1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<IngestMessage>> captor = ArgumentCaptor.forClass(List.class);
    verify(repo).ingest(anyLong(), captor.capture());
    assertThatOneMessageSurvived(captor.getValue());
  }

  private static void assertThatOneMessageSurvived(List<IngestMessage> messages) {
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).messageId()).isEqualTo(1273987654321098765L);
    assertThat(messages.get(0).attachments()).hasSize(1);
  }

  @Test
  void aReplayIsAcceptedButStoresNothing() throws Exception {
    // The scraper re-sends whatever Discord still has rendered after a restart; that must be a
    // no-op, not an error.
    when(repo.ingest(anyLong(), anyList())).thenReturn(0);

    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content(
                    """
                    {"channel_id":"786109983065505792","messages":[
                      {"message_id":"1273987654321098765","author_name":"a",
                       "posted_at":"2026-08-12T14:03:11Z","content":"x"}
                    ]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(1))
        .andExpect(jsonPath("$.stored").value(0));
  }

  @Test
  void aForeignChannelIs400_andNothingIsWritten() throws Exception {
    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content(
                    """
                    {"channel_id":"999999999999999999","messages":[
                      {"message_id":"1273987654321098765","author_name":"a",
                       "posted_at":"2026-08-12T14:03:11Z","content":"x"}
                    ]}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verify(repo, never()).ingest(anyLong(), anyList());
  }

  @Test
  void aStructurallyBrokenMessageIs400_andNothingIsWritten() throws Exception {
    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content(
                    """
                    {"channel_id":"786109983065505792","messages":[
                      {"author_name":"a","posted_at":"2026-08-12T14:03:11Z","content":"x"}
                    ]}"""))
        .andExpect(status().isBadRequest());

    verify(repo, never()).ingest(anyLong(), anyList());
  }

  @Test
  void anEmptyBatchIsAccepted() throws Exception {
    when(repo.ingest(anyLong(), anyList())).thenReturn(0);

    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content("{\"channel_id\":\"786109983065505792\",\"messages\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(0));
  }

  @Test
  void acceptsTheSecondConfiguredChannelAndStoresUnderIt() throws Exception {
    // The allowlist is what makes a second mirror pod possible; storing under the wrong channel
    // would put signal messages in the discussion tab.
    when(repo.ingest(anyLong(), anyList())).thenReturn(1);

    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content(
                    """
                    {"channel_id":"769797179992571914","messages":[
                      {"message_id":"1273987654321098765","author_name":"TradingTheTrend",
                       "posted_at":"2026-08-15T14:03:11Z","content":"BTO MU 8/21 1050C @ 2.42"}
                    ]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(1));

    // The channel comes from the PARSER's validated value, not a re-read of the raw body.
    verify(repo).ingest(eq(SIGNALS), anyList());
  }

  @Test
  void stillRejectsAChannelOutsideTheAllowlist() throws Exception {
    mvc.perform(
            post("/internal/options-chat/ingest")
                .contentType("application/json")
                .content(
                    """
                    {"channel_id":"111111111111111111","messages":[
                      {"message_id":"1273987654321098765","author_name":"a",
                       "posted_at":"2026-08-15T14:03:11Z","content":"x"}
                    ]}"""))
        .andExpect(status().isBadRequest());

    verify(repo, never()).ingest(anyLong(), anyList());
  }
}
