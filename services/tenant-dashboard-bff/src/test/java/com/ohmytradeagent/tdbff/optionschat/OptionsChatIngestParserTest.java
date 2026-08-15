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
  private static final java.util.Set<Long> ALLOWED = java.util.Set.of(CHANNEL, 769797179992571914L);

  // ---- structural rejection (400) -------------------------------------------------------------

  @Test
  void aDifferentChannelIsRejected_soTheStoreIsNotAGeneralPurposeBlobSink() {
    Map<String, Object> body = body(CHANNEL + 1, List.of(message()));
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, ALLOWED))
        .isInstanceOf(InvalidIngestException.class)
        .hasMessageContaining("channel_id");
  }

  @Test
  void nullBodyIsRejected() {
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(null, ALLOWED))
        .isInstanceOf(InvalidIngestException.class);
  }

  @Test
  void missingSnowflakeIsRejected() {
    Map<String, Object> m = message();
    m.remove("message_id");
    Map<String, Object> body = body(CHANNEL, List.of(m));
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, ALLOWED))
        .isInstanceOf(InvalidIngestException.class)
        .hasMessageContaining("message_id");
  }

  @Test
  void timestampWithoutAnOffsetIsRejected() {
    Map<String, Object> m = message();
    m.put("posted_at", "2026-08-12T14:03:11"); // no offset -> ambiguous instant
    Map<String, Object> body = body(CHANNEL, List.of(m));
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, ALLOWED))
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
    assertThatThrownBy(() -> OptionsChatIngestParser.parse(body, ALLOWED))
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
      List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

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
    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).attachments()).hasSize(2);
  }

  @Test
  void aHostileAvatarUrlBecomesNullButTheMessageSurvives() {
    Map<String, Object> m = message();
    m.put("author_avatar_url", "javascript:alert(1)");
    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).authorAvatarUrl()).isNull();
  }

  @Test
  void aCallerSuppliedContentTypeIsIgnoredRatherThanRejected() {
    // IngestAttachment carries no contentType component at all, so a caller cannot influence what
    // /media/{id} later serves as a Content-Type header — the guarantee is structural, not a check.
    // What this pins is that supplying one is silently ignored rather than failing the batch.
    Map<String, Object> a = attachment("https://cdn.discordapp.com/a.png");
    a.put("content_type", "text/html");
    Map<String, Object> m = message();
    m.put("attachments", List.of(a));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).attachments()).hasSize(1);
    assertThat(out.get(0).attachments().get(0).kind()).isEqualTo("image");
  }

  @Test
  void avalidRoleColourSurvives() {
    Map<String, Object> m = message();
    m.put("author_color", "#ff0004");

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).authorColor()).isEqualTo("#ff0004");
  }

  @Test
  void anythingThatIsNotSixHexDigitsIsDropped_becauseItEndsUpInACssContext() {
    // Not sanitised — the stored value must be structurally incapable of representing an
    // injection, so anything that is not exactly #rrggbb becomes null rather than being repaired.
    for (String hostile :
        List.of(
            "red",
            "#fff",
            "#ff0004; background: url(https://attacker.io/beacon)",
            "rgb(255,0,4)",
            "expression(alert(1))",
            "var(--x)",
            "#gggggg",
            "javascript:alert(1)")) {
      Map<String, Object> m = message();
      m.put("author_color", hostile);

      List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

      assertThat(out.get(0).authorColor()).as(hostile).isNull();
    }
  }

  @Test
  void safeColorAcceptsOnlySixHexDigits_testedDirectlyLikeSafeUrl() {
    assertThat(OptionsChatIngestParser.safeColor("#ff0004")).isEqualTo("#ff0004");
    assertThat(OptionsChatIngestParser.safeColor("#FF0004")).isEqualTo("#FF0004");
    assertThat(OptionsChatIngestParser.safeColor(null)).isNull();
    assertThat(OptionsChatIngestParser.safeColor("#fff")).isNull();
    assertThat(OptionsChatIngestParser.safeColor("#gggggg")).isNull();
  }

  @Test
  void anUnknownAttachmentKindCollapsesToFile() {
    Map<String, Object> a = attachment("https://cdn.discordapp.com/a.bin");
    a.put("kind", "executable");
    Map<String, Object> m = message();
    m.put("attachments", List.of(a));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).attachments().get(0).kind()).isEqualTo("file");
  }

  @Test
  void overLongContentIsTruncatedRatherThanRejected() {
    Map<String, Object> m = message();
    m.put("content", "x".repeat(OptionsChatIngestParser.MAX_CONTENT + 500));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).content()).hasSize(OptionsChatIngestParser.MAX_CONTENT);
  }

  @Test
  void truncationNeverSplitsASurrogatePair() {
    // An emoji sitting exactly on the boundary. Cutting mid-pair leaves an unpaired surrogate that
    // pgjdbc stores as a replacement byte — a silently corrupted trailing character.
    String emoji = "🚀"; // rocket, one code point, two chars
    Map<String, Object> m = message();
    m.put("content", "x".repeat(OptionsChatIngestParser.MAX_CONTENT - 1) + emoji);

    String content =
        OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED).get(0).content();

    assertThat(content).hasSize(OptionsChatIngestParser.MAX_CONTENT - 1);
    assertThat(Character.isHighSurrogate(content.charAt(content.length() - 1)))
        .as("must not end on an unpaired high surrogate")
        .isFalse();
  }

  @Test
  void anOutOfRangeByteSizeIsDroppedRatherThanWrapped() {
    // intValue() would narrow 5_000_000_000 to 705032704 — a confident lie fed straight into the
    // Phase 4 "skip if too large" gate. Unknown is the safe answer.
    Map<String, Object> a = attachment("https://cdn.discordapp.com/a.png");
    a.put("byte_size", 5_000_000_000L);
    Map<String, Object> m = message();
    m.put("attachments", List.of(a));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).attachments().get(0).byteSize()).isNull();
  }

  @Test
  void aNegativeDimensionIsDropped() {
    Map<String, Object> a = attachment("https://cdn.discordapp.com/a.png");
    a.put("width", -1);
    Map<String, Object> m = message();
    m.put("attachments", List.of(a));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).attachments().get(0).width()).isNull();
  }

  @Test
  void attachmentsBeyondTheCapAreTrimmed() {
    List<Map<String, Object>> lots = new ArrayList<>();
    for (int i = 0; i < OptionsChatIngestParser.MAX_CHILDREN + 5; i++) {
      lots.add(attachment("https://cdn.discordapp.com/" + i + ".png"));
    }
    Map<String, Object> m = message();
    m.put("attachments", lots);

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out.get(0).attachments()).hasSize(OptionsChatIngestParser.MAX_CHILDREN);
  }

  @Test
  void anImageOnlyPostWithEmptyContentIsAccepted() {
    Map<String, Object> m = message();
    m.remove("content");
    m.put("attachments", List.of(attachment("https://cdn.discordapp.com/a.png")));

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).content()).isEmpty();
  }

  @Test
  void snowflakesArriveAsStringsAndKeepFullPrecision() {
    // 19 digits: above 2^53, so a JSON number would already have been corrupted client-side.
    Map<String, Object> m = message();
    m.put("message_id", "1273987654321098765");
    m.put("reply_to_id", "1273987654321098700");

    List<IngestMessage> out = OptionsChatIngestParser.parse(body(CHANNEL, List.of(m)), ALLOWED);

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
