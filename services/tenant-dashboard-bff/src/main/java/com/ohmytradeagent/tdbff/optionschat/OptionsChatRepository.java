package com.ohmytradeagent.tdbff.optionschat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Store for the read-only {@code /options-chat} Discord mirror (V9). Writes the scraper's ingest
 * and serves the page's paginated read, both through the least-privilege {@code dashboard_writer}
 * DSL — the only dashboard-DB role the BFF has a DSLContext for.
 *
 * <p>DARK by default via a two-name {@code @ConditionalOnProperty}: the bean exists only when BOTH
 * {@code options-chat.enabled=true} AND {@code dashboard.writer.enabled=true}. The second name is
 * load-bearing, not decoration — {@code dashboardWriterDsl} is itself conditional on it, so gating
 * on the feature flag alone would fail context startup with {@code NoSuchBeanDefinitionException}
 * on any cluster where the writer is off, rather than simply leaving the route absent.
 *
 * <p>Ingest is idempotent by Discord snowflake: {@code ON CONFLICT (message_id) DO NOTHING}. A
 * restart re-scrapes whatever Discord still has rendered and replays it harmlessly. Child rows are
 * written only when the parent insert actually won, so a replay never duplicates attachments.
 */
@Repository
@ConditionalOnProperty(
    name = {"options-chat.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class OptionsChatRepository {

  private final DSLContext writerDsl;

  public OptionsChatRepository(@Qualifier("dashboardWriterDsl") DSLContext writerDsl) {
    this.writerDsl = writerDsl;
  }

  /** One attachment descriptor as scraped. {@code bytes} is fetched later (Phase 4). */
  public record IngestAttachment(
      String kind,
      String sourceUrl,
      String filename,
      String contentType,
      Integer width,
      Integer height,
      Integer byteSize) {}

  /** One link preview / bot embed, flattened to the fields the renderer shows. */
  public record IngestEmbed(
      String title,
      String description,
      String url,
      String author,
      String footer,
      String thumbnailUrl) {}

  /** One scraped message. {@code content} is PLAIN TEXT — never HTML. */
  public record IngestMessage(
      long messageId,
      String authorName,
      String authorAvatarUrl,
      OffsetDateTime postedAt,
      String content,
      Long replyToId,
      boolean edited,
      List<IngestAttachment> attachments,
      List<IngestEmbed> embeds) {}

  /** An attachment as served to the page. Deliberately carries no {@code bytes}. */
  public record StoredAttachment(
      long id,
      String kind,
      String filename,
      String contentType,
      Integer width,
      Integer height,
      String fetchState) {}

  /** An embed as served to the page. */
  public record StoredEmbed(
      String title,
      String description,
      String url,
      String author,
      String footer,
      String thumbnailUrl) {}

  /** A message as served to the page, with its children attached. */
  public record StoredMessage(
      long messageId,
      String authorName,
      String authorAvatarUrl,
      OffsetDateTime postedAt,
      String content,
      Long replyToId,
      boolean edited,
      OffsetDateTime deletedAt,
      List<StoredAttachment> attachments,
      List<StoredEmbed> embeds) {}

  /**
   * Upsert a batch of scraped messages for one channel. Returns how many were newly stored (a
   * replayed message counts 0). Runs as ONE transaction so a mid-batch failure cannot leave a
   * message row without its attachments.
   *
   * <p>{@code content_hash} is computed HERE, never taken from the caller: it is the Phase 6 edit
   * detector, so letting the scraper supply it would let a buggy scraper mask a real edit.
   */
  public int ingest(long channelId, List<IngestMessage> messages) {
    if (messages.isEmpty()) {
      return 0;
    }
    int[] stored = {0};
    writerDsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          for (IngestMessage m : messages) {
            // ON CONFLICT DO NOTHING needs SELECT on the arbiter column (V9 grants it); without
            // that grant this is the 42501 that forced the invite bind's SAVEPOINT workaround.
            int inserted =
                tx.execute(
                    "INSERT INTO options_chat_message (message_id, channel_id, author_name,"
                        + " author_avatar_url, posted_at, content, reply_to_id, edited,"
                        + " content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (message_id) DO NOTHING",
                    m.messageId(),
                    channelId,
                    m.authorName(),
                    m.authorAvatarUrl(),
                    m.postedAt(),
                    m.content(),
                    m.replyToId(),
                    m.edited(),
                    sha256(m.content()));
            if (inserted != 1) {
              // Already stored. Children were written with the original insert; re-adding them
              // would violate the (message_id, ordinal) uniqueness.
              continue;
            }
            stored[0]++;
            insertChildren(tx, m);
          }
        });
    return stored[0];
  }

  private static void insertChildren(DSLContext tx, IngestMessage m) {
    int ordinal = 0;
    for (IngestAttachment a : m.attachments()) {
      tx.execute(
          "INSERT INTO options_chat_attachment (message_id, ordinal, kind, source_url, filename,"
              + " content_type, width, height, byte_size) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          m.messageId(),
          ordinal++,
          a.kind(),
          a.sourceUrl(),
          a.filename(),
          a.contentType(),
          a.width(),
          a.height(),
          a.byteSize());
    }
    ordinal = 0;
    for (IngestEmbed e : m.embeds()) {
      tx.execute(
          "INSERT INTO options_chat_embed (message_id, ordinal, title, description, url, author,"
              + " footer, thumbnail_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          m.messageId(),
          ordinal++,
          e.title(),
          e.description(),
          e.url(),
          e.author(),
          e.footer(),
          e.thumbnailUrl());
    }
  }

  /**
   * Newest-first page of messages for {@code channelId}, oldest cursor semantics: {@code before} is
   * exclusive, so paging up passes the oldest id already rendered. Uses exactly the {@code
   * (channel_id, message_id DESC)} index V9 creates.
   */
  public List<StoredMessage> recent(long channelId, Long before, int limit) {
    Result<Record> rows =
        before == null
            ? writerDsl.fetch(
                "SELECT message_id, author_name, author_avatar_url, posted_at, content,"
                    + " reply_to_id, edited, deleted_at FROM options_chat_message"
                    + " WHERE channel_id = ? ORDER BY message_id DESC LIMIT ?",
                channelId,
                limit)
            : writerDsl.fetch(
                "SELECT message_id, author_name, author_avatar_url, posted_at, content,"
                    + " reply_to_id, edited, deleted_at FROM options_chat_message"
                    + " WHERE channel_id = ? AND message_id < ? ORDER BY message_id DESC LIMIT ?",
                channelId,
                before,
                limit);

    if (rows.isEmpty()) {
      return List.of();
    }
    List<Long> ids = rows.stream().map(r -> r.get("message_id", Long.class)).toList();
    Map<Long, List<StoredAttachment>> attachments = attachmentsFor(ids);
    Map<Long, List<StoredEmbed>> embeds = embedsFor(ids);

    List<StoredMessage> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      long id = r.get("message_id", Long.class);
      out.add(
          new StoredMessage(
              id,
              r.get("author_name", String.class),
              r.get("author_avatar_url", String.class),
              r.get("posted_at", OffsetDateTime.class),
              r.get("content", String.class),
              r.get("reply_to_id", Long.class),
              Boolean.TRUE.equals(r.get("edited", Boolean.class)),
              r.get("deleted_at", OffsetDateTime.class),
              attachments.getOrDefault(id, List.of()),
              embeds.getOrDefault(id, List.of())));
    }
    return out;
  }

  private Map<Long, List<StoredAttachment>> attachmentsFor(List<Long> messageIds) {
    // `bytes` is NEVER projected here. The BFF runs on a 768Mi limit (~192MB default heap) and
    // detoasting a page of images would exhaust it; media is read one row at a time by /media/{id}.
    Result<Record> rows =
        writerDsl.fetch(
            "SELECT id, message_id, kind, filename, content_type, width, height, fetch_state"
                + " FROM options_chat_attachment WHERE message_id IN ("
                + placeholders(messageIds.size())
                + ") ORDER BY message_id, ordinal",
            messageIds.toArray());
    Map<Long, List<StoredAttachment>> byMessage = new HashMap<>();
    for (Record r : rows) {
      byMessage
          .computeIfAbsent(r.get("message_id", Long.class), k -> new ArrayList<>())
          .add(
              new StoredAttachment(
                  r.get("id", Long.class),
                  r.get("kind", String.class),
                  r.get("filename", String.class),
                  r.get("content_type", String.class),
                  r.get("width", Integer.class),
                  r.get("height", Integer.class),
                  r.get("fetch_state", String.class)));
    }
    return byMessage;
  }

  private Map<Long, List<StoredEmbed>> embedsFor(List<Long> messageIds) {
    Result<Record> rows =
        writerDsl.fetch(
            "SELECT message_id, title, description, url, author, footer, thumbnail_url"
                + " FROM options_chat_embed WHERE message_id IN ("
                + placeholders(messageIds.size())
                + ") ORDER BY message_id, ordinal",
            messageIds.toArray());
    Map<Long, List<StoredEmbed>> byMessage = new HashMap<>();
    for (Record r : rows) {
      byMessage
          .computeIfAbsent(r.get("message_id", Long.class), k -> new ArrayList<>())
          .add(
              new StoredEmbed(
                  r.get("title", String.class),
                  r.get("description", String.class),
                  r.get("url", String.class),
                  r.get("author", String.class),
                  r.get("footer", String.class),
                  r.get("thumbnail_url", String.class)));
    }
    return byMessage;
  }

  /** Bind placeholders for an IN list. Count comes from a page we just read, never from input. */
  private static String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  private static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
