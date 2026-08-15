package com.ohmytradeagent.tdbff.optionschat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
 * <p>DARK by default via a two-name {@code @ConditionalOnProperty} — see {@code application.yml}
 * for why both names are load-bearing, and {@code OptionsChatDarkGateWebMvcTest} for the pin.
 *
 * <p>Ingest is idempotent by Discord snowflake: {@code ON CONFLICT (message_id) DO NOTHING}. A
 * restart re-scrapes whatever Discord still has rendered and replays it harmlessly. Child rows are
 * written when the parent insert wins — OR backfilled onto an existing parent whose child set is
 * still empty, because Discord resolves accessories asynchronously and a message caught on first
 * render would otherwise keep rendering without its chart forever. Never duplicates: a non-empty
 * child set is left alone.
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

  /**
   * One attachment descriptor as scraped. No {@code contentType}: that is set from OUR transcode in
   * Phase 4, never from the caller, so carrying a caller-supplied field here could only ever be a
   * way to get one wrong. {@code byteSize} is Discord's claimed size, kept because it lets Phase 4
   * skip an over-large attachment WITHOUT downloading it first.
   */
  public record IngestAttachment(
      String kind,
      String sourceUrl,
      String filename,
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
      String authorColor,
      String authorAvatarUrl,
      OffsetDateTime postedAt,
      String content,
      Long replyToId,
      boolean edited,
      List<IngestAttachment> attachments,
      List<IngestEmbed> embeds) {}

  /** One attachment still awaiting its bytes, as handed to the scraper to fetch. */
  public record PendingMedia(long id, String sourceUrl) {}

  /** Stored media, as served by the media route. */
  public record Media(byte[] bytes, String contentType) {}

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
      String authorColor,
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
    return writerDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          int stored = 0;
          for (IngestMessage m : messages) {
            // ON CONFLICT DO NOTHING needs SELECT on the arbiter column (V9 grants it); without
            // that grant this is the 42501 that forced the invite bind's SAVEPOINT workaround.
            int inserted =
                tx.execute(
                    // posted_at is bound with an EXPLICIT ::timestamptz cast. jOOQ renders an
                    // OffsetDateTime bind as a STRING for PostgreSQL, and an uncast string against
                    // a timestamptz column fails with 42804 ("column is of type timestamp with
                    // time zone but expression is of type character varying"). Discovered the hard
                    // way: this 500'd every ingest in production until the cast was added.
                    "INSERT INTO options_chat_message (message_id, channel_id, author_name,"
                        + " author_color, author_avatar_url, posted_at, content, reply_to_id,"
                        + " edited, content_hash)"
                        + " VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?, ?, ?, ?)"
                        + " ON CONFLICT (message_id) DO NOTHING",
                    m.messageId(),
                    channelId,
                    m.authorName(),
                    m.authorColor(),
                    m.authorAvatarUrl(),
                    m.postedAt(),
                    m.content(),
                    m.replyToId(),
                    m.edited(),
                    sha256(m.content()));
            if (inserted != 1) {
              // Already stored — but possibly WITHOUT its children. Discord resolves link previews
              // and image accessories asynchronously, seconds after the message element appears, so
              // a scraper that catches a message on first render stores it bare. Without this
              // backfill a re-scrape would find the parent present and skip the children forever:
              // the message would render without its chart for the rest of its retention, which in
              // a trading room is the content. Only fills an EMPTY set, so it never duplicates and
              // never needs UPDATE (V9 grants INSERT + SELECT only).
              backfillChildren(tx, m);
              continue;
            }
            stored++;
            insertChildren(tx, m);
          }
          return stored;
        });
  }

  /**
   * Add children to an already-stored message that has none.
   *
   * <p>Deliberately all-or-nothing per child table: a partial set means the scrape caught the
   * message mid-resolution, and inserting into a non-empty set would collide with the {@code
   * (message_id, ordinal)} uniqueness. Waiting for a later sweep with the full set is both correct
   * and simpler than reconciling ordinals.
   */
  private static void backfillChildren(DSLContext tx, IngestMessage m) {
    if (!m.attachments().isEmpty() && countChildren(tx, "options_chat_attachment", m) == 0) {
      insertAttachments(tx, m);
    }
    if (!m.embeds().isEmpty() && countChildren(tx, "options_chat_embed", m) == 0) {
      insertEmbeds(tx, m);
    }
  }

  private static int countChildren(DSLContext tx, String table, IngestMessage m) {
    // Table name is a compile-time constant from the two call sites above, never caller input.
    Record r =
        tx.fetchOne("SELECT count(*) FROM " + table + " WHERE message_id = ?", m.messageId());
    return r == null ? 0 : r.get(0, Integer.class);
  }

  private static void insertChildren(DSLContext tx, IngestMessage m) {
    insertAttachments(tx, m);
    insertEmbeds(tx, m);
  }

  private static void insertAttachments(DSLContext tx, IngestMessage m) {
    int ordinal = 0;
    for (IngestAttachment a : m.attachments()) {
      // content_type is intentionally absent — Phase 4 sets it from our own transcode.
      tx.execute(
          "INSERT INTO options_chat_attachment (message_id, ordinal, kind, source_url, filename,"
              + " width, height, byte_size) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          m.messageId(),
          ordinal++,
          a.kind(),
          a.sourceUrl(),
          a.filename(),
          a.width(),
          a.height(),
          a.byteSize());
    }
  }

  private static void insertEmbeds(DSLContext tx, IngestMessage m) {
    int ordinal = 0;
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
    // No first-page/next-page branch: an absent cursor is just an upper bound no snowflake can
    // reach. One query means the column list and ORDER BY cannot drift between the two paths — a
    // drift that would only ever show up after the first page.
    long cursor = (before == null) ? Long.MAX_VALUE : before;
    Result<Record> rows =
        writerDsl.fetch(
            "SELECT message_id, author_name, author_color, author_avatar_url, posted_at, content,"
                + " reply_to_id, edited, deleted_at FROM options_chat_message"
                + " WHERE channel_id = ? AND message_id < ? ORDER BY message_id DESC LIMIT ?",
            channelId,
            cursor,
            limit);

    if (rows.isEmpty()) {
      return List.of();
    }
    List<Long> ids = rows.stream().map(r -> r.get("message_id", Long.class)).toList();
    Map<Long, List<StoredAttachment>> attachments =
        childrenFor(
            "SELECT id, message_id, kind, filename, content_type, width, height, fetch_state"
                + " FROM options_chat_attachment",
            ids,
            r ->
                new StoredAttachment(
                    r.get("id", Long.class),
                    r.get("kind", String.class),
                    r.get("filename", String.class),
                    r.get("content_type", String.class),
                    r.get("width", Integer.class),
                    r.get("height", Integer.class),
                    r.get("fetch_state", String.class)));
    Map<Long, List<StoredEmbed>> embeds =
        childrenFor(
            "SELECT message_id, title, description, url, author, footer, thumbnail_url"
                + " FROM options_chat_embed",
            ids,
            r ->
                new StoredEmbed(
                    r.get("title", String.class),
                    r.get("description", String.class),
                    r.get("url", String.class),
                    r.get("author", String.class),
                    r.get("footer", String.class),
                    r.get("thumbnail_url", String.class)));

    List<StoredMessage> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      long id = r.get("message_id", Long.class);
      out.add(
          new StoredMessage(
              id,
              r.get("author_name", String.class),
              r.get("author_color", String.class),
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

  /**
   * Fetch one child table for a whole page of messages and group it by parent — ONE query per child
   * table, not one per message. {@code selectSql} must project {@code message_id}; this appends the
   * parent filter and the ordering.
   *
   * <p>Callers must never project {@code bytes}: the BFF runs on a 768Mi limit (~192MB default
   * heap) and detoasting a page of images would exhaust it. Media is read one row at a time by
   * {@code /media/{id}}.
   */
  private <T> Map<Long, List<T>> childrenFor(
      String selectSql, List<Long> messageIds, Function<Record, T> mapper) {
    if (messageIds.isEmpty()) {
      // `IN ()` is a syntax error. Guarded here rather than relying on the caller's early return,
      // so this stays correct if it is ever called from somewhere else.
      return Map.of();
    }
    String binds = String.join(", ", Collections.nCopies(messageIds.size(), "?"));
    Result<Record> rows =
        writerDsl.fetch(
            selectSql + " WHERE message_id IN (" + binds + ") ORDER BY message_id, ordinal",
            messageIds.toArray());
    Map<Long, List<T>> byMessage = new HashMap<>();
    for (Record r : rows) {
      byMessage
          .computeIfAbsent(r.get("message_id", Long.class), k -> new ArrayList<>())
          .add(mapper.apply(r));
    }
    return byMessage;
  }

  private static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * Attachments whose bytes we still owe, oldest first.
   *
   * <p>Oldest-first is deliberate and time-critical: Discord's CDN urls are SIGNED and expire in
   * roughly 24h, so the longest-waiting row is the one closest to becoming permanently unfetchable.
   * Scoped to the channel so this can never hand out rows belonging to anything else.
   */
  public List<PendingMedia> pendingMedia(Collection<Long> channelIds, int limit) {
    if (channelIds.isEmpty()) {
      return List.of();
    }
    // Spans every mirrored channel: the fetcher is channel-agnostic, and scoping it to one would
    // strand the other channel's images pending until their CDN urls expired.
    Object[] binds = new Object[channelIds.size() + 1];
    int i = 0;
    for (Long c : channelIds) {
      binds[i++] = c;
    }
    binds[i] = limit;
    Result<Record> rows =
        writerDsl.fetch(
            "SELECT a.id, a.source_url FROM options_chat_attachment a"
                + " JOIN options_chat_message m ON m.message_id = a.message_id"
                + " WHERE m.channel_id IN ("
                + String.join(", ", Collections.nCopies(channelIds.size(), "?"))
                + ") AND a.fetch_state = 'pending' ORDER BY a.id ASC LIMIT ?",
            binds);
    List<PendingMedia> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      out.add(new PendingMedia(r.get("id", Long.class), r.get("source_url", String.class)));
    }
    return out;
  }

  /**
   * Fill in the fetched bytes. Returns false when the row is gone or already filled.
   *
   * <p>{@code content_type} is whatever the CALLER of this method decided — and the only caller
   * sniffs it from the bytes themselves rather than trusting any header, so a hostile {@code
   * text/html} can never become the Content-Type the browser sees.
   */
  public boolean storeMedia(long attachmentId, byte[] bytes, String contentType) {
    return writerDsl.execute(
            "UPDATE options_chat_attachment SET bytes = ?, content_type = ?, byte_size = ?,"
                + " fetch_state = 'ok' WHERE id = ? AND fetch_state <> 'ok'",
            bytes,
            contentType,
            bytes.length,
            attachmentId)
        == 1;
  }

  /** Mark an attachment permanently un-fetchable so it stops being handed out forever. */
  public boolean markMediaTerminal(long attachmentId, String state) {
    return writerDsl.execute(
            "UPDATE options_chat_attachment SET fetch_state = ? WHERE id = ? AND fetch_state <> 'ok'",
            state,
            attachmentId)
        == 1;
  }

  /**
   * One attachment's bytes, for the media route.
   *
   * <p>The ONLY place {@code bytes} is ever projected. Never widen this to a list query: the BFF
   * runs on a 768Mi limit with a ~192MB default heap, and detoasting a page of images would exhaust
   * it.
   */
  public Media media(Collection<Long> channelIds, long attachmentId) {
    if (channelIds.isEmpty()) {
      return null;
    }
    // The channel join is not decoration: it keeps an id from outside the configured channels
    // unreadable even if a row for it somehow existed.
    Object[] binds = new Object[channelIds.size() + 1];
    int i = 0;
    for (Long c : channelIds) {
      binds[i++] = c;
    }
    binds[i] = attachmentId;
    Record r =
        writerDsl.fetchOne(
            "SELECT a.bytes, a.content_type FROM options_chat_attachment a"
                + " JOIN options_chat_message m ON m.message_id = a.message_id"
                + " WHERE m.channel_id IN ("
                + String.join(", ", Collections.nCopies(channelIds.size(), "?"))
                + ") AND a.id = ? AND a.fetch_state = 'ok'",
            binds);
    if (r == null) {
      return null;
    }
    byte[] bytes = r.get("bytes", byte[].class);
    return bytes == null ? null : new Media(bytes, r.get("content_type", String.class));
  }
}
