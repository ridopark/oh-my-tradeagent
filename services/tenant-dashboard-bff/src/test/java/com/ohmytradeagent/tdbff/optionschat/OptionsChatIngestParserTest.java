package com.ohmytradeagent.tdbff.optionschat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatIngestParser.InvalidIngestException;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The security boundary for the whole feature. Everything downstream — store, read endpoint, page —
 * assumes this parser already made the data safe, so these tests are the enforcement of that
 * assumption rather than ordinary input hygiene.
 */
class OptionsChatIngestParserTest {

  private static final long CHANNEL = 786109983065505792L;

  // ---- structural rejection (400) -------------------------------------------------------------

  @Test
  void aDifferentChannelIsRejected_soTheStoreIsNotAGeneralPurposeBlobSink() {
    Map<String, Object> body = body(CHANNEL + 1, List.of(message()));
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, CHANNEL))
        .isInstanceOf(InvalidIngestException.class)
        .hasMessageContaining("channel_id");
  }

  @Test
  void nullBodyIsRejected() {
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(null, CHANNEL))
        .isInstanceOf(InvalidIngestException.class);
  }

  @Test
  void missingSnowflakeIsRejected() {
    Map<String, Object> m = message();
    m.remove("message_id");
    Map<String, Object> body = body(CHANNEL, List.of(m));
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, CHANNEL))
        .isInstanceOf(InvalidIngestException.class)
        .hasMessageContaining("message_id");
  }

  @Test
  void timestampWithoutAnOffsetIsRejected() {
    Map<String, Object> m = message();
    m.put("posted_at", "2026-08-12T14:03:11"); // no offset -> ambiguous instant
    Map<String, Object> body = body(CHANNEL, List.of(m));
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, CHANNEL))
        .isInstanceOf(InvalidIngestException.class)
        .hasMessageContaining("posted_at");
  }

  @Test
  void anOversizedBatchIsRejected() {
    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < OptionsChatIngestParser.MAX_MESSAGES + 1; i++) {
      Map<String, Object> m = message();
      m.put("message_id", Long.toString(1000L + i));
      many.add(m);
    }
    Map<String, Object> body = body(CHANNEL, many);
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, CHANNEL))
        .isInstanceOf(InvalidIngestException.class)
        .hasMessageContaining("messages");
  }

  // ---- content sanitization (never rejects the batch) -----------------------------------------

  @Test
  void nonHttpAttachmentUrlsAreDropped_soTheyCanNeverReachAnImgSrc() {
    for (String hostile :
        List.of(
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "/relative/path",
            "vbscript:msgbox(1)")) {
      Map<String, Object> m = message();
      m.put("attachments", List.of(attachment(hostile)));
      List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

      assertThat(out).hasSize(1);
      assertThat(out.get(0).attachments())
          .as("attachment with url %s must be dropped entirely", hostile)
          .isEmpty();
    }
  }

  @Test
  void httpAndHttpsAttachmentUrlsSurvive() {
    Map<String, Object> m = message();
    m.put(
        "attachments",
        List.of(
            attachment("https://cdn.discordapp.com/a.png"), attachment("http://example.com/b")));
    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out.get(0).attachments()).hasSize(2);
  }

  @Test
  void aHostileAvatarUrlBecomesNullButTheMessageSurvives() {
    Map<String, Object> m = message();
    m.put("author_avatar_url", "javascript:alert(1)");
    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).authorAvatarUrl()).isNull();
  }

  @Test
  void callerSuppliedContentTypeIsIgnored_becauseMediaServesItAsAResponseHeader() {
    Map<String, Object> a = attachment("https://cdn.discordapp.com/a.png");
    a.put("content_type", "text/html");
    Map<String, Object> m = message();
    m.put("attachments", List.of(a));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out.get(0).attachments().get(0).contentType())
        .as("content_type must come from our own transcode, never the caller")
        .isNull();
  }

  @Test
  void anUnknownAttachmentKindCollapsesToFile() {
    Map<String, Object> a = attachment("https://cdn.discordapp.com/a.bin");
    a.put("kind", "executable");
    Map<String, Object> m = message();
    m.put("attachments", List.of(a));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out.get(0).attachments().get(0).kind()).isEqualTo("file");
  }

  @Test
  void overLongContentIsTruncatedRatherThanRejected() {
    Map<String, Object> m = message();
    m.put("content", "x".repeat(OptionsChatIngestParser.MAX_CONTENT + 500));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out.get(0).content()).hasSize(OptionsChatIngestParser.MAX_CONTENT);
  }

  @Test
  void attachmentsBeyondTheCapAreTrimmed() {
    List<Map<String, Object>> lots = new ArrayList<>();
    for (int i = 0; i < OptionsChatIngestParser.MAX_CHILDREN + 5; i++) {
      lots.add(attachment("https://cdn.discordapp.com/" + i + ".png"));
    }
    Map<String, Object> m = message();
    m.put("attachments", lots);

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out.get(0).attachments()).hasSize(OptionsChatIngestParser.MAX_CHILDREN);
  }

  @Test
  void anImageOnlyPostWithEmptyContentIsAccepted() {
    Map<String, Object> m = message();
    m.remove("content");
    m.put("attachments", List.of(attachment("https://cdn.discordapp.com/a.png")));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).content()).isEmpty();
  }

  @Test
  void snowflakesArriveAsStringsAndKeepFullPrecision() {
    // 19 digits: above 2^53, so a JSON number would already have been corrupted client-side.
    Map<String, Object> m = message();
    m.put("message_id", "1273987654321098765");
    m.put("reply_to_id", "1273987654321098700");

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), CHANNEL);

    assertThat(out.get(0).messageId()).isEqualTo(1273987654321098765L);
    assertThat(out.get(0).replyToId()).isEqualTo(1273987654321098700L);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static Map<String, Object> body(long channelId, List<Map<String, Object>> messages) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("channel_id", Long.toString(channelId));
    b.put("messages", messages);
    return b;
  }

  private static Map<String, Object> message() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("message_id", "1273987654321098765");
    m.put("author_name", "TradingTheTrend");
    m.put("posted_at", "2026-08-12T14:03:11Z");
    m.put("content", "NVDA looking strong here");
    return m;
  }

  private static Map<String, Object> attachment(String url) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("kind", "image");
    a.put("source_url", url);
    a.put("filename", "chart.png");
    return a;
  }
}
