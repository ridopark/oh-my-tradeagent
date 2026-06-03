package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import org.springframework.stereotype.Component;

/**
 * Formats the verbatim daily watchlist and posts it to the SAME trade-alert Discord webhook ({@code
 * ALERT_DISCORD_WEBHOOK_URL}) via the shared {@link WebhookClient}.
 *
 * <p>Best-effort by construction: {@link WebhookClient} is a no-op on a blank URL and never throws
 * on transport failure, so this activity never disrupts anything. The signal-source-discord sidecar
 * already dedupes by {@code source_message_id} (REJECT_DUPLICATE), so duplicate posts are not
 * expected; an activity retry (max 3) would re-post, but that is cosmetically harmless — there is
 * no order side-effect here.
 */
@Component
public class WatchlistMirrorActivitiesImpl implements WatchlistMirrorActivities {

  /** Discord hard limit for a single message body. */
  private static final int DISCORD_MAX = 2000;

  private static final String FENCE = "```";
  private static final String TRUNCATION_MARKER = "\n… (truncated)";

  private final WebhookClient webhookClient;

  public WatchlistMirrorActivitiesImpl(WebhookClient webhookClient) {
    this.webhookClient = webhookClient;
  }

  @Override
  public void postWatchlistAlert(WatchlistMirrorPayload payload) {
    webhookClient.post(format(payload));
  }

  /**
   * Pure, deterministic formatting: a header line, then the raw watchlist text wrapped in a
   * triple-backtick fenced code block. Neutralizes any literal triple-backtick in the body so it
   * cannot prematurely close the fence, and truncates the body (inside the fence) if the composed
   * message would exceed Discord's 2000-char limit.
   */
  static String format(WatchlistMirrorPayload payload) {
    String header =
        "📋 Watchlist — " + String.valueOf(payload.getEtDate()) + " — via " + payload.getAuthor();

    String rawText = payload.getRawText() == null ? "" : payload.getRawText();
    // Fence-injection guard: neutralize literal fences by inserting a zero-width space so the body
    // can never close the surrounding code block early.
    String body = rawText.replace(FENCE, "``​`");

    // Overhead = header + "\n" + open-fence + "\n" + "\n" + close-fence.
    String prefix = header + "\n" + FENCE + "\n";
    String suffix = "\n" + FENCE;
    int overhead = prefix.length() + suffix.length();

    if (overhead + body.length() > DISCORD_MAX) {
      int budget = DISCORD_MAX - overhead - TRUNCATION_MARKER.length();
      if (budget < 0) {
        budget = 0;
      }
      body = body.substring(0, Math.min(body.length(), budget)) + TRUNCATION_MARKER;
    }

    return prefix + body + suffix;
  }
}
