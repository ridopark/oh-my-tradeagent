package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.Media;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.PendingMedia;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer contract for the Phase 4 media routes. */
@WebMvcTest(OptionsChatMediaController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TenantContext.class, OptionsChatChannels.class})
@TestPropertySource(
    properties = {
      "options-chat.enabled=true",
      "dashboard.writer.enabled=true",
      // Labelled form, matching production config, so the tests exercise the real shape.
      "options-chat.channel-ids=786109983065505792:Discussion,769797179992571914:Signals"
    })
class OptionsChatMediaControllerWebMvcTest {

  private static final long CHANNEL = 786109983065505792L;
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

  @Autowired private MockMvc mvc;
  @MockitoBean private OptionsChatRepository repo;

  @Test
  void pendingListsOldestFirstForTheConfiguredChannel() throws Exception {
    when(repo.pendingMedia(anyCollection(), anyInt()))
        .thenReturn(List.of(new PendingMedia(7L, "https://cdn.discordapp.com/a.png")));

    mvc.perform(get("/internal/options-chat/pending-media"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        // String, not number — ids are bigints and JS would corrupt them.
        .andExpect(jsonPath("$.items[0].id").value("7"))
        .andExpect(jsonPath("$.items[0].source_url").value("https://cdn.discordapp.com/a.png"));
  }

  @Test
  void servingMediaUsesOurSniffedTypeAndForbidsSniffing() throws Exception {
    when(repo.media(anyCollection(), eq(7L))).thenReturn(new Media(PNG, "image/png"));

    mvc.perform(get("/api/options-chat/media/7").header("X-Tenant-Id", "prod_real"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", "inline"))
        .andExpect(content().bytes(PNG));
  }

  @Test
  void servingMediaRequiresASession() throws Exception {
    // The feed is tenant-INDEPENDENT, but a signed-in session is still required — same contract as
    // the messages read.
    mvc.perform(get("/api/options-chat/media/7")).andExpect(status().isUnauthorized());
    verifyNoInteractions(repo);
  }

  @Test
  void unknownOrUnfetchedMediaIs404() throws Exception {
    when(repo.media(anyCollection(), eq(999L))).thenReturn(null);

    mvc.perform(get("/api/options-chat/media/999").header("X-Tenant-Id", "t"))
        .andExpect(status().isNotFound());
  }

  @Test
  void anEmptyBodyMarksTheAttachmentTerminalRatherThanStoringNothing() throws Exception {
    // The scraper's agreed signal for "this url is permanently gone" — it must stop the row being
    // handed out forever.
    mvc.perform(put("/internal/options-chat/media/7").content(new byte[0]))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(false));

    verify(repo).markMediaTerminal(7L, "failed");
  }

  @Test
  void anOversizedBodyIsRecordedAsSkippedNotRetriedForever() throws Exception {
    mvc.perform(put("/internal/options-chat/media/7").content(new byte[11 * 1024 * 1024]))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(false));

    verify(repo).markMediaTerminal(7L, "skipped_too_large");
  }

  @Test
  void anEmptyBodyWithReasonTooLargeIsRecordedAsSkipped_notAsAnExpiredLink() throws Exception {
    // The scraper caps BEFORE sending, so an oversized attachment arrives as an empty body. Without
    // the reason every terminal case collapsed to "failed" and the page told the reader the source
    // link had expired — which is untrue and unactionable.
    mvc.perform(
            put("/internal/options-chat/media/7").param("reason", "too_large").content(new byte[0]))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("skipped_too_large"));

    verify(repo).markMediaTerminal(7L, "skipped_too_large");
  }

  @Test
  void storedBytesGetTheTypeSniffedFromTheBytes_neverTheCallersHeader() throws Exception {
    when(repo.storeMedia(eq(7L), any(), eq("image/png"))).thenReturn(true);

    mvc.perform(
            put("/internal/options-chat/media/7")
                .contentType("text/html") // a hostile claim, ignored
                .content(PNG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(true));

    verify(repo).storeMedia(eq(7L), any(), eq("image/png"));
  }

  private static int anyInt() {
    return org.mockito.ArgumentMatchers.anyInt();
  }

  private static byte[] any() {
    return org.mockito.ArgumentMatchers.any();
  }

  private static java.util.Collection<Long> anyCollection() {
    return org.mockito.ArgumentMatchers.anyCollection();
  }
}
