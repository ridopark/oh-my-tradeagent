package com.ohmytradeagent.tdbff.optionschat;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestAttachment;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestEmbed;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the scraper's raw JSON body into validated {@link IngestMessage} records.
 *
 * <p>This is the PRIMARY validation point for the whole feature: the store and the read endpoint
 * treat the data as already-safe. The content is an untrusted third-party Discord room being
 * rendered inside a dashboard whose server actions can force-exit real-money positions, so the
 * rules here are the security boundary, not hygiene.
 *
 * <p>The renderer re-checks the few values that land in a URL or CSS context (an embed href, an
 * author colour). That is this codebase's established practice rather than redundancy — those
 * values cross two services to reach a context where being wrong is executable, and the check costs
 * a regex. Do not delete a renderer-side check on the strength of this class existing.
 *
 * <p>Two failure modes, deliberately different:
 *
 * <ul>
 *   <li><b>Structural problems reject the batch (400)</b> — wrong channel, unparseable snowflake,
 *       missing timestamp. These mean the caller is broken or is not our scraper; failing loudly is
 *       correct and the caller can be fixed.
 *   <li><b>Content problems are sanitized, not rejected</b> — an over-long string is truncated, an
 *       attachment with a non-http(s) URL is dropped, arrays past their cap are trimmed. Rejecting
 *       the batch instead would let one permanently-malformed message wedge the feed forever, and
 *       this is a display-only mirror where dropping one image beats stalling the room.
 * </ul>
 */
public final class OptionsChatIngestParser {

  /** Discord's own per-message ceiling; anything longer did not come from Discord. */
  static final int MAX_CONTENT = 4000;

  static final int MAX_AUTHOR = 128;
  static final int MAX_FILENAME = 256;
  static final int MAX_URL = 2048;
  static final int MAX_EMBED_TEXT = 2048;

  /** Discord caps attachments and embeds at 10 apiece. */
  static final int MAX_CHILDREN = 10;

  /** Bounds one request; the scraper's reconcile sweep sends far fewer. */
  static final int MAX_MESSAGES = 200;

  private static final Set<String> ALLOWED_KINDS = Set.of("image", "video", "file", "embed_image");

  /**
   * Exactly six hex digits behind a {@code #}. The author colour ends up in a CSS context in the
   * browser, so the stored value must be structurally incapable of carrying anything else — not
   * "sanitised", but unable to represent an injection in the first place. The scraper already
   * normalises Discord's {@code rgb(r,g,b)} to this form; anything that does not match is dropped
   * rather than repaired.
   */
  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

  private OptionsChatIngestParser() {}

  /**
   * Thrown for structural problems. Extends {@link IllegalArgumentException} so {@code
   * GlobalExceptionHandler} maps it to the service's standard 400 envelope — a hand-rolled 400 in
   * the controller would give this one route a different error shape from every other endpoint.
   */
  public static class InvalidIngestException extends IllegalArgumentException {
    public InvalidIngestException(String message) {
      super(message);
    }
  }

  /**
   * Parse and validate, rejecting anything not addressed to {@code expectedChannelId}.
   *
   * <p>The channel check is the reason this endpoint is not a general-purpose blob sink: without it
   * anyone holding the ingest token could write arbitrary rows under any channel id. It is an
   * ALLOWLIST — membership, never a range or a prefix.
   */
  public static List<IngestMessage> parse(Map<String, Object> body, java.util.Set<Long> allowed) {
    if (body == null) {
      throw new InvalidIngestException("body is required");
    }
    long channelId = requireSnowflake(body, "channel_id");
    if (!allowed.contains(channelId)) {
      throw new InvalidIngestException("channel_id is not an allowed options-chat channel");
    }
    Object raw = body.get("messages");
    if (!(raw instanceof List<?> list)) {
      throw new InvalidIngestException("messages must be an array");
    }
    if (list.size() > MAX_MESSAGES) {
      throw new InvalidIngestException("messages exceeds " + MAX_MESSAGES);
    }
    List<IngestMessage> out = new ArrayList<>(list.size());
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m)) {
        throw new InvalidIngestException("each message must be an object");
      }
      out.add(parseMessage(castMap(m)));
    }
    return out;
  }

  private static IngestMessage parseMessage(Map<String, Object> m) {
    long messageId = requireSnowflake(m, "message_id");
    String authorName = truncate(requireString(m, "author_name"), MAX_AUTHOR);
    OffsetDateTime postedAt = requireTimestamp(m, "posted_at");
    // Content may legitimately be empty — an image-only post has no text.
    String content = truncate(optionalString(m, "content", ""), MAX_CONTENT);
    Long replyToId = Snowflakes.parse(m.get("reply_to_id"));
    boolean edited = Boolean.TRUE.equals(m.get("edited"));
    String avatar = safeUrl(optionalString(m, "author_avatar_url", null));
    String color = safeColor(optionalString(m, "author_color", null));

    return new IngestMessage(
        messageId,
        authorName,
        color,
        avatar,
        postedAt,
        content,
        replyToId,
        edited,
        parseAttachments(m.get("attachments")),
        parseEmbeds(m.get("embeds")));
  }

  private static List<IngestAttachment> parseAttachments(Object raw) {
    List<IngestAttachment> out = new ArrayList<>();
    for (Map<String, Object> a : childObjects(raw)) {
      // A dropped URL is the whole attachment: without a source there is nothing to fetch later.
      String url = safeUrl(optionalString(a, "source_url", null));
      if (url == null) {
        continue;
      }
      String kind = optionalString(a, "kind", "file").toLowerCase(Locale.ROOT);
      if (!ALLOWED_KINDS.contains(kind)) {
        kind = "file";
      }
      out.add(
          new IngestAttachment(
              kind,
              url,
              truncate(optionalString(a, "filename", null), MAX_FILENAME),
              optionalInt(a, "width"),
              optionalInt(a, "height"),
              optionalInt(a, "byte_size")));
      if (out.size() == MAX_CHILDREN) {
        break;
      }
    }
    return out;
  }

  private static List<IngestEmbed> parseEmbeds(Object raw) {
    List<IngestEmbed> out = new ArrayList<>();
    for (Map<String, Object> e : childObjects(raw)) {
      out.add(
          new IngestEmbed(
              truncate(optionalString(e, "title", null), MAX_EMBED_TEXT),
              truncate(optionalString(e, "description", null), MAX_EMBED_TEXT),
              safeUrl(optionalString(e, "url", null)),
              truncate(optionalString(e, "author", null), MAX_AUTHOR),
              truncate(optionalString(e, "footer", null), MAX_EMBED_TEXT),
              safeUrl(optionalString(e, "thumbnail_url", null))));
      if (out.size() == MAX_CHILDREN) {
        break;
      }
    }
    return out;
  }

  private static List<Map<String, Object>> childObjects(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object o : list) {
      if (o instanceof Map<?, ?> m) {
        out.add(castMap(m));
      }
    }
    return out;
  }

  /**
   * {@code null} unless this is an {@code http}/{@code https} URL within the length cap. Everything
   * else — {@code javascript:}, {@code data:}, {@code file:}, a relative path — collapses to null
   * so it can never reach an {@code href} or {@code src} in the renderer.
   */
  static String safeUrl(String s) {
    if (s == null || s.isBlank() || s.length() > MAX_URL) {
      return null;
    }
    String lower = s.toLowerCase(Locale.ROOT);
    return (lower.startsWith("http://") || lower.startsWith("https://")) ? s : null;
  }

  /**
   * Truncate to at most {@code max} chars WITHOUT splitting a surrogate pair. Cutting mid-pair
   * would leave an unpaired surrogate that pgjdbc stores as a replacement byte — a silently
   * corrupted trailing character. Reachable in ordinary traffic: MAX_EMBED_TEXT is below Discord's
   * own 4096-char embed-description limit, and an emoji at the boundary is a surrogate pair.
   */
  /**
   * {@code null} unless this is exactly {@code #rrggbb}.
   *
   * <p>The colour ends up in a CSS context in the browser, so the stored value must be structurally
   * incapable of carrying anything else — not "sanitised", but unable to represent an injection.
   * The scraper already normalises Discord's {@code rgb(r,g,b)} to this form; anything that does
   * not match is dropped rather than repaired.
   */
  static String safeColor(String s) {
    return s != null && HEX_COLOR.matcher(s).matches() ? s : null;
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    if (s.length() > max && Character.isHighSurrogate(s.charAt(max - 1))) {
      return s.substring(0, max - 1);
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static long requireSnowflake(Map<String, Object> m, String key) {
    Long v = Snowflakes.parse(m.get(key));
    if (v == null) {
      throw new InvalidIngestException(key + " is required and must be a snowflake");
    }
    return v;
  }

  /**
   * A non-negative {@code int}, or {@code null} if absent, unparseable, or out of range.
   *
   * <p>Out-of-range values are DROPPED, never narrowed: {@code intValue()} on an untrusted
   * 5_000_000_000 wraps to 705032704, and on 4_294_967_296 to 0. {@code byte_size} exists so Phase
   * 4 can skip an over-large attachment WITHOUT downloading it, so a wrapped value would defeat
   * exactly the gate it feeds — and null (unknown, fetch and find out) is the safe answer, whereas
   * a small wrapped number is a confident lie.
   */
  private static Integer optionalInt(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      return null;
    }
    long parsed;
    if (v instanceof Number n) {
      parsed = n.longValue();
    } else {
      try {
        parsed = Long.parseLong(v.toString().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return (parsed < 0 || parsed > Integer.MAX_VALUE) ? null : (int) parsed;
  }

  private static String requireString(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null || v.toString().isBlank()) {
      throw new InvalidIngestException(key + " is required");
    }
    return v.toString();
  }

  private static String optionalString(Map<String, Object> m, String key, String fallback) {
    Object v = m.get(key);
    return v == null ? fallback : v.toString();
  }

  private static OffsetDateTime requireTimestamp(Map<String, Object> m, String key) {
    String s = requireString(m, key);
    try {
      return OffsetDateTime.parse(s);
    } catch (DateTimeParseException e) {
      throw new InvalidIngestException(key + " must be an ISO-8601 timestamp with an offset");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }
}
