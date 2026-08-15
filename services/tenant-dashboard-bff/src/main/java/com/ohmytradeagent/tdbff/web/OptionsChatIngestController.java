package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatChannels;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatIngestParser;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/options-chat/ingest} — the scraper's write path for the {@code
 * /options-chat} mirror. A SERVICE route, not tenant- or operator-scoped: it carries no {@code
 * X-Tenant-Id} and grants no tenant anything.
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
}
