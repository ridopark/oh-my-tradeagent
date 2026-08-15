package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredAttachment;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredEmbed;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredMessage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for {@code GET /api/options-chat/messages}. Flag-OFF → 404 lives in the two
 * Disabled siblings; the without-bearer 401 is proven filter-side in {@code
 * ServiceTokenFilterTest}.
 */
@WebMvcTest(OptionsChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TenantContext.class, OptionsChatChannels.class})
@TestPropertySource(
    properties = {
      "options-chat.enabled=true",
      "dashboard.writer.enabled=true",
      "options-chat.channel-ids=786109983065505792,769797179992571914"
    })
class OptionsChatControllerWebMvcTest {

  private static final long CHANNEL = 786109983065505792L;

  @Autowired private MockMvc mvc;
  @MockitoBean private OptionsChatRepository repo;

  @Test
  void missingTenantHeaderIs401_beforeAnyRead() throws Exception {
    // The feed is tenant-INDEPENDENT, but a signed-in session is still required. Auth uniformity is
    // deliberate: stricter than this endpoint needs, never looser.
    mvc.perform(get("/api/options-chat/messages")).andExpect(status().isUnauthorized());
    verifyNoInteractions(repo);
  }

  @Test
  void returnsMessagesWithSnowflakesAsStrings() throws Exception {
    when(repo.recent(eq(CHANNEL), isNull(), anyInt()))
        .thenReturn(
            List.of(
                new StoredMessage(
                    1273987654321098765L,
                    "TradingTheTrend",
                    "#ff0004",
                    "https://cdn.discordapp.com/avatar.png",
                    OffsetDateTime.of(2026, 8, 12, 14, 3, 11, 0, ZoneOffset.UTC),
                    "NVDA looking strong",
                    1273987654321098700L,
                    false,
                    null,
                    List.of(
                        new StoredAttachment(42L, "image", "chart.png", null, 800, 600, "pending")),
                    List.of(new StoredEmbed("t", "d", "https://example.com", "a", "f", null)))));

    mvc.perform(get("/api/options-chat/messages").header("X-Tenant-Id", "prod_real"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel_id").value("786109983065505792"))
        .andExpect(jsonPath("$.count").value(1))
        // Strings, not numbers: these exceed 2^53 and JavaScript would silently corrupt them.
        .andExpect(jsonPath("$.items[0].message_id").value("1273987654321098765"))
        // Discord's role colour, normalised upstream and re-validated at ingest.
        .andExpect(jsonPath("$.items[0].author_color").value("#ff0004"))
        .andExpect(jsonPath("$.items[0].reply_to_id").value("1273987654321098700"))
        .andExpect(jsonPath("$.items[0].attachments[0].id").value("42"))
        .andExpect(jsonPath("$.items[0].attachments[0].fetch_state").value("pending"))
        .andExpect(jsonPath("$.items[0].embeds[0].url").value("https://example.com"))
        .andExpect(jsonPath("$.items[0].deleted").value(false));
  }

  @Test
  void attachmentsNeverExposeTheDiscordCdnUrl() throws Exception {
    when(repo.recent(anyLong(), isNull(), anyInt()))
        .thenReturn(
            List.of(
                new StoredMessage(
                    1L,
                    "a",
                    null,
                    null,
                    OffsetDateTime.of(2026, 8, 12, 14, 3, 11, 0, ZoneOffset.UTC),
                    "",
                    null,
                    false,
                    null,
                    List.of(new StoredAttachment(7L, "image", "c.png", null, 1, 1, "ok")),
                    List.of())));

    // The page must load media from our own /media/{id}: the signed CDN urls expire, and hotlinking
    // would leak every viewer to Discord.
    mvc.perform(get("/api/options-chat/messages").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].attachments[0].source_url").doesNotExist());
  }

  @Test
  void theCursorIsPassedThroughAsALong() throws Exception {
    when(repo.recent(eq(CHANNEL), eq(1273987654321098765L), anyInt())).thenReturn(List.of());

    mvc.perform(
            get("/api/options-chat/messages")
                .param("before", "1273987654321098765")
                .header("X-Tenant-Id", "t"))
        .andExpect(status().isOk());

    verify(repo).recent(CHANNEL, 1273987654321098765L, 50);
  }

  @Test
  void aGarbageCursorFallsBackToTheNewestPageRatherThan400() throws Exception {
    when(repo.recent(eq(CHANNEL), isNull(), anyInt())).thenReturn(List.of());

    mvc.perform(
            get("/api/options-chat/messages")
                .param("before", "nonsense")
                .header("X-Tenant-Id", "t"))
        .andExpect(status().isOk());

    verify(repo).recent(CHANNEL, null, 50);
  }

  @Test
  void limitIsClampedSoOnePageCannotBeUnbounded() throws Exception {
    when(repo.recent(anyLong(), isNull(), anyInt())).thenReturn(List.of());

    mvc.perform(
            get("/api/options-chat/messages").param("limit", "100000").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk());

    verify(repo).recent(CHANNEL, null, 200);
  }
}
