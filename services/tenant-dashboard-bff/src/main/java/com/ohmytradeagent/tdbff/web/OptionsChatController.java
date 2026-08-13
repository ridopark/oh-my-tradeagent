package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredAttachment;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredEmbed;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredMessage;
import com.ohmytradeagent.tdbff.optionschat.Snowflakes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/options-chat/messages?before=&limit=} — newest-first page of the mirrored Discord
 * room behind {@code /options-chat}.
 *
 * <p>DELIBERATELY TENANT-INDEPENDENT. Every other read here is scoped by {@code X-Tenant-Id}; this
 * one returns identical bytes for every tenant, because the mirror is one shared room rather than
 * per-tenant trading data. The header is still REQUIRED and still 401s when absent — it is used
 * purely as the authentication assertion that a signed-in session exists, which keeps the auth
 * shape uniform across the whole surface (strictly stricter than needed, never looser) and lets the
 * dashboard reuse {@code bffGet} unchanged. Do not read tenant scoping into this endpoint; there is
 * none.
 *
 * <p>Dark-gated via a two-name {@code @ConditionalOnProperty} — see {@code application.yml} for why
 * both names are load-bearing. Snowflake ids are emitted as JSON STRINGS (see {@code Snowflakes}).
 */
@RestController
@RequestMapping("/api/options-chat")
@ConditionalOnProperty(
    name = {"options-chat.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class OptionsChatController {

  /** Bounds one page; the client pages upward with {@code before}. */
  private static final int MAX_LIMIT = 200;

  private final OptionsChatRepository repo;
  private final TenantContext ctx;
  private final long channelId;

  public OptionsChatController(
      OptionsChatRepository repo,
      TenantContext ctx,
      @Value("${options-chat.channel-id}") long channelId) {
    this.repo = repo;
    this.ctx = ctx;
    this.channelId = channelId;
  }

  @GetMapping("/messages")
  public ResponseEntity<Map<String, Object>> messages(
      HttpServletRequest req,
      @RequestParam(value = "before", required = false) String before,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    // Authentication only — the value is intentionally unused (see the class javadoc).
    ctx.tenantId(req);

    // A malformed cursor falls back to the newest page rather than 400ing: it is opaque to the
    // client, so the only ways to get one wrong are a bug or a hand-edited URL, and neither is
    // worth an error page instead of the newest messages.
    List<StoredMessage> rows =
        repo.recent(channelId, Snowflakes.parse(before), Math.clamp(limit, 1, MAX_LIMIT));

    List<Map<String, Object>> items = rows.stream().map(OptionsChatController::toJson).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("channel_id", Long.toString(channelId));
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> toJson(StoredMessage m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("message_id", Long.toString(m.messageId()));
    out.put("author_name", m.authorName());
    out.put("author_color", m.authorColor());
    out.put("author_avatar_url", m.authorAvatarUrl());
    out.put("posted_at", m.postedAt() == null ? null : m.postedAt().toString());
    // Plain text. The client renders it through a sanitizing markdown component and must never
    // inject it as HTML.
    out.put("content", m.content());
    out.put("reply_to_id", m.replyToId() == null ? null : Long.toString(m.replyToId()));
    out.put("edited", m.edited());
    out.put("deleted", m.deletedAt() != null);

    List<Map<String, Object>> attachments = new ArrayList<>();
    for (StoredAttachment a : m.attachments()) {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("id", Long.toString(a.id()));
      j.put("kind", a.kind());
      j.put("filename", a.filename());
      j.put("content_type", a.contentType());
      j.put("width", a.width());
      j.put("height", a.height());
      j.put("fetch_state", a.fetchState());
      // No source_url: the page must load media from our own /media/{id} route, never from the
      // Discord CDN directly (the signed urls expire, and hotlinking leaks viewers to Discord).
      attachments.add(j);
    }
    out.put("attachments", attachments);

    List<Map<String, Object>> embeds = new ArrayList<>();
    for (StoredEmbed e : m.embeds()) {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("title", e.title());
      j.put("description", e.description());
      j.put("url", e.url());
      j.put("author", e.author());
      j.put("footer", e.footer());
      j.put("thumbnail_url", e.thumbnailUrl());
      embeds.add(j);
    }
    out.put("embeds", embeds);
    return out;
  }
}
