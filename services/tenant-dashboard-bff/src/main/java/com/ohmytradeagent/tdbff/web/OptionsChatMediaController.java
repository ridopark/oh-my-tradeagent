package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.optionschat.MediaTypes;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.Media;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.PendingMedia;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media for the /options-chat mirror (Phase 4): the scraper fills bytes in, the page reads them
 * out.
 *
 * <p>WHY WE STORE BYTES AT ALL rather than linking Discord: their CDN urls are signed and expire in
 * ~24h, so a mirror that linked them would rot within a day; and every image load would come from
 * Discord, leaking each dashboard viewer's IP to a third party. Serving from our own origin fixes
 * both, and is why {@code source_url} is never returned to the browser.
 *
 * <p>Three routes, two different credentials — the split is the point:
 *
 * <ul>
 *   <li>{@code GET /internal/options-chat/pending-media} and {@code PUT
 *       /internal/options-chat/media/{id}} are the scraper's, gated by ServiceTokenFilter on the
 *       route-scoped OPTIONS_CHAT_INGEST_TOKEN.
 *   <li>{@code GET /api/options-chat/media/{id}} is the browser's, gated by the shared token plus a
 *       session tenant, like every other /api read.
 * </ul>
 */
@RestController
@ConditionalOnProperty(
    name = {"options-chat.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class OptionsChatMediaController {

  /** Bounds one fetch batch; the scraper loops. */
  private static final int MAX_PENDING = 25;

  /** Refuses a body larger than this. Mirrors the scraper's own skip threshold. */
  private static final int MAX_BYTES = 10 * 1024 * 1024;

  private final OptionsChatRepository repo;
  private final TenantContext ctx;
  private final long channelId;

  public OptionsChatMediaController(
      OptionsChatRepository repo,
      TenantContext ctx,
      @Value("${options-chat.channel-id}") long channelId) {
    this.repo = repo;
    this.ctx = ctx;
    this.channelId = channelId;
  }

  /** Attachments still awaiting bytes, oldest first (closest to CDN-url expiry). */
  @GetMapping("/internal/options-chat/pending-media")
  public ResponseEntity<Map<String, Object>> pending(
      @RequestParam(value = "limit", required = false, defaultValue = "25") int limit) {
    List<PendingMedia> rows = repo.pendingMedia(channelId, Math.clamp(limit, 1, MAX_PENDING));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (PendingMedia p : rows) {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("id", Long.toString(p.id()));
      j.put("source_url", p.sourceUrl());
      items.add(j);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }

  /**
   * Store one attachment's bytes.
   *
   * <p>READS THE BODY AS A CAPPED STREAM rather than taking {@code @RequestBody byte[]}. That
   * binding buffers the ENTIRE request into memory before any handler code runs, so a size check
   * inside the method guards nothing — this service has a 768Mi limit and a ~192MB default heap,
   * and a large enough PUT would OOM it before the check executed. The cap is enforced twice: an
   * early reject on a declared Content-Length, then on the stream itself, because Content-Length
   * can be absent or a lie.
   *
   * <p>An EMPTY body is the scraper's agreed signal for "this attachment will never arrive", with
   * {@code reason} distinguishing why so the page can say something true. Without it every terminal
   * case collapsed to "failed" and an oversized image was reported to the reader as an expired
   * link.
   *
   * <p>The Content-Type header is IGNORED — the stored type is sniffed from the bytes ({@link
   * MediaTypes}), so a hostile upload cannot get itself served back as {@code text/html} and become
   * stored XSS on the dashboard's own origin.
   */
  @PutMapping("/internal/options-chat/media/{id}")
  public ResponseEntity<Map<String, Object>> put(
      @PathVariable("id") long id,
      @RequestParam(value = "reason", required = false) String reason,
      HttpServletRequest request)
      throws IOException {
    // Cheap reject before a single byte is buffered.
    if (request.getContentLengthLong() > MAX_BYTES) {
      repo.markMediaTerminal(id, "skipped_too_large");
      return ResponseEntity.ok(Map.of("stored", false, "reason", "too_large"));
    }

    byte[] bytes = readCapped(request.getInputStream());
    if (bytes == null) {
      // Exceeded the cap mid-stream (no or lying Content-Length).
      repo.markMediaTerminal(id, "skipped_too_large");
      return ResponseEntity.ok(Map.of("stored", false, "reason", "too_large"));
    }
    if (bytes.length == 0) {
      String state = "too_large".equals(reason) ? "skipped_too_large" : "failed";
      repo.markMediaTerminal(id, state);
      return ResponseEntity.ok(Map.of("stored", false, "reason", state));
    }
    boolean stored = repo.storeMedia(id, bytes, MediaTypes.sniff(bytes));
    return ResponseEntity.ok(Map.of("stored", stored));
  }

  /** The body, or {@code null} once it exceeds {@link #MAX_BYTES}. Stops reading at the cap. */
  private static byte[] readCapped(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) != -1) {
      if (out.size() + n > MAX_BYTES) {
        return null;
      }
      out.write(chunk, 0, n);
    }
    return out.toByteArray();
  }

  /** Serve one attachment to the browser. */
  @GetMapping("/api/options-chat/media/{id}")
  public ResponseEntity<byte[]> get(HttpServletRequest req, @PathVariable("id") long id) {
    // Authentication only — the mirror is one shared room, so the value is intentionally unused
    // (same contract as the messages read).
    ctx.tenantId(req);

    Media m = repo.media(channelId, id);
    if (m == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        // Our sniffed type, never the caller's claim.
        .header(HttpHeaders.CONTENT_TYPE, m.contentType())
        // Belt and braces with the sniffed type: an unrecognised blob is octet-stream, and nosniff
        // stops the browser re-interpreting it as markup.
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        // Content is immutable per id, so it can be cached hard. `private` keeps it out of any
        // shared cache — this is authenticated third-party content.
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
        .body(m.bytes());
  }
}
