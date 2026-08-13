package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatIngestParser;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatIngestParser.InvalidIngestException;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>AUTH IS DELIBERATELY NOT THE BFF SHARED TOKEN. {@code ServiceTokenFilter} route-scopes {@code
 * /internal/options-chat/**} to a SEPARATE {@code OPTIONS_CHAT_INGEST_TOKEN}, and that token opens
 * nothing else. The caller is a pod whose entire job is rendering an untrusted third-party Discord
 * room; handing it {@code BFF_SHARED_TOKEN} would let it set any {@code X-Tenant-Id} and read
 * positions, orders and portfolio for real-money tenants. The narrow token keeps a compromise of
 * the scraper confined to defacing this one mirror.
 *
 * <p>DARK-GATED on BOTH {@code options-chat.enabled} and {@code dashboard.writer.enabled}, matching
 * the read side. The ingest is gated too, not just the read: a live write endpoint accepting
 * arbitrary untrusted content into the dashboard DB with nothing consuming it is strictly worse
 * than no endpoint at all.
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
  private final long channelId;

  public OptionsChatIngestController(
      OptionsChatRepository repo, @Value("${options-chat.channel-id}") long channelId) {
    this.repo = repo;
    this.channelId = channelId;
  }

  @PostMapping("/ingest")
  public ResponseEntity<Map<String, Object>> ingest(
      @RequestBody(required = false) Map<String, Object> body) {
    List<IngestMessage> messages;
    try {
      messages = OptionsChatIngestParser.parse(body, channelId);
    } catch (InvalidIngestException e) {
      // Structural rejection: the caller is broken or is not our scraper. Content-level problems
      // never land here — the parser sanitizes those (see its javadoc).
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    int stored = repo.ingest(channelId, messages);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("received", messages.size());
    // received - stored = replays the scraper re-sent; a healthy steady state has stored > 0 only
    // when the room is actually active.
    out.put("stored", stored);
    return ResponseEntity.ok(out);
  }
}
