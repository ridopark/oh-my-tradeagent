package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatIngestParser;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredMessage;
import com.ohmytradeagent.tdbff.optionschat.Snowflakes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/options-chat/ingest} — the scraper's write path for the {@code
 * /options-chat} mirror — and {@code GET /internal/options-chat/messages}, the lotto poller's
 * forward cursor read of the same rows. SERVICE routes, not tenant- or operator-scoped: they carry
 * no {@code X-Tenant-Id} and grant no tenant anything.
 *
 * <p>Auth is the separate {@code OPTIONS_CHAT_INGEST_TOKEN}, NOT the BFF shared token — enforced
 * and explained in {@code ServiceTokenFilter}. Dark-gated on both {@code options-chat.enabled} and
 * {@code dashboard.writer.enabled}, matching the read side; the ingest is gated too because a live
 * write endpoint taking untrusted content with nothing consuming it is worse than no endpoint.
 *
 * <p>Idempotent by snowflake, so the scraper may safely re-send anything Discord still has rendered
 * after a restart.
 */
@RestController
@RequestMapping("/internal/options-chat")
@ConditionalOnProperty(
    name = {"options-chat.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class OptionsChatIngestController {

  /** Bounds one poll; the poller walks forward with {@code after}. */
  private static final int MAX_LIMIT = 200;

  private final OptionsChatRepository repo;
  private final OptionsChatChannels channels;

  public OptionsChatIngestController(OptionsChatRepository repo, OptionsChatChannels channels) {
    this.repo = repo;
    this.channels = channels;
  }

  @PostMapping("/ingest")
  public ResponseEntity<Map<String, Object>> ingest(
      @RequestBody(required = false) Map<String, Object> body) {
    // A structural rejection throws InvalidIngestException (an IllegalArgumentException), which
    // GlobalExceptionHandler turns into the service's standard 400 envelope. Content-level problems
    // never reach here — the parser sanitizes those.
    // The channel comes back from the parser already validated against the allowlist — never
    // re-read from the raw body, which would duplicate snowflake handling and re-derive a trusted
    // value from untrusted input.
    OptionsChatIngestParser.ParsedBatch batch =
        OptionsChatIngestParser.parse(body, channels.allowed());
    int stored = repo.ingest(batch.channelId(), batch.messages());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("received", batch.messages().size());
    // received - stored = replays the scraper re-sent; a healthy steady state has stored > 0 only
    // when the room is actually active.
    out.put("stored", stored);
    return ResponseEntity.ok(out);
  }

  /**
   * Rows of one allow-listed channel with {@code message_id > after}, oldest first, at most {@code
   * limit} (clamped to {@value #MAX_LIMIT}). The caller advances {@code after} to the last {@code
   * message_id} it received.
   *
   * <p>Unlike the page's read, an unknown {@code channel} is a 400 and never a fallback: a poller
   * that trades on what it reads must fail loudly if it is aimed at a room the operator did not
   * configure, not quietly act on the default one. The JSON is the page's per-message shape minus
   * the children (no attachments, no embeds).
   */
  @GetMapping("/messages")
  public ResponseEntity<Map<String, Object>> messages(
      @RequestParam("channel") String channel,
      @RequestParam(value = "after", defaultValue = "0") long after,
      @RequestParam(value = "limit", defaultValue = "100") int limit) {
    Long channelId = Snowflakes.parse(channel);
    if (channelId == null || !channels.allowed().contains(channelId)) {
      // IllegalArgumentException → GlobalExceptionHandler's standard 400 envelope. Fixed message:
      // the caller's raw input is not reflected back.
      throw new IllegalArgumentException("unknown options-chat channel");
    }
    List<StoredMessage> rows = repo.since(channelId, after, Math.clamp(limit, 1, MAX_LIMIT));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("channel_id", Long.toString(channelId));
    body.put("count", rows.size());
    body.put("items", rows.stream().map(OptionsChatController::messageJson).toList());
    return ResponseEntity.ok(body);
  }
}
