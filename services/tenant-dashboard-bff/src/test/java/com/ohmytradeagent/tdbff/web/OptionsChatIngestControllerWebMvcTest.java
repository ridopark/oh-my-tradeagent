package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for {@code POST /internal/options-chat/ingest}. The token isolation (this
 * route accepts ONLY {@code OPTIONS_CHAT_INGEST_TOKEN}, never the shared token) is proven
 * filter-side in {@code ServiceTokenFilterTest}; filters are disabled here so these assertions are
 * about the handler alone.
 */
@WebMvcTest(OptionsChatIngestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TenantContext.class, OptionsChatChannels.class})
@TestPropertySource(
    properties = {
      "options-chat.enabled=true",
      "dashboard.writer.enabled=true",
      // Labelled form, matching production config, so the tests exercise the real shape.
      "options-chat.channel-ids=786109983065505792:Discussion,769797179992571914:Signals"
    })
class OptionsChatIngestControllerWebMvcTest {

  private static final long CHANNEL = 786109983065505792L;
  private static final long SIGNALS = 769797179992571914L;

  @Autowired private MockMvc mvc;
  @MockitoBean private OptionsChatRepository repo;

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
