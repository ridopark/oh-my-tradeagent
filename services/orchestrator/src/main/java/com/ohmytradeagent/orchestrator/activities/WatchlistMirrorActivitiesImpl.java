package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.Leg;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.ParseResult;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.TickerWatch;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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

  /** Discord hard limit for a single embed description. */
  private static final int EMBED_DESC_MAX = 4096;

  /** Discord green (0x57F287) as the decimal RGB integer the embed API expects. */
  private static final int DISCORD_GREEN = 5763719;

  private static final String FENCE = "```";
  private static final String TRUNCATION_MARKER = "\n… (truncated)";

  private static final DateTimeFormatter EMBED_DATE =
      DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

  private final WebhookClient webhookClient;

  public WatchlistMirrorActivitiesImpl(WebhookClient webhookClient) {
    this.webhookClient = webhookClient;
  }

  @Override
  public void postWatchlistAlert(WatchlistMirrorPayload payload) {
    ParseResult parsed = WatchlistParser.parse(payload.getRawText());
    if (parsed.clean() && !parsed.rows().isEmpty()) {
      webhookClient.postEmbed(payload.getTenantId(), buildEmbed(payload, parsed.rows()));
    } else {
      // Malformed/empty watchlist is never dropped — fall back to verbatim raw text.
      webhookClient.post(payload.getTenantId(), format(payload));
    }
  }

  /**
   * Builds the rich embed: green accent, date-stamped title, an {@code "via <author>"} footer, and
   * a per-play description (no code fence) so it uses the embed's full width.
   */
  private static WebhookEmbed buildEmbed(WatchlistMirrorPayload payload, List<TickerWatch> rows) {
    String title = "📋 Watchlist — " + EMBED_DATE.format(payload.getEtDate());
    String description = renderPlays(rows);
    String footer = "via " + payload.getAuthor();
    return new WebhookEmbed(title, description, DISCORD_GREEN, footer);
  }

  /**
   * Renders one line per leg (no code fence — the embed uses its full width): per ticker the call
   * line (if present) then the put line (if present), tickers in original order. A one-sided ticker
   * emits only that line — no placeholder. The play (ticker+strike) is bolded; the trigger is
   * plain.
   *
   * <pre>
   *   📈 **SPY 756C** — breaks above 755.30
   *   📉 **SPY 745P** — breaks below 748.00
   * </pre>
   */
  static String renderPlays(List<TickerWatch> rows) {
    StringBuilder sb = new StringBuilder();
    for (TickerWatch w : rows) {
      if (w.call() != null) {
        appendLine(sb, "📈", w.ticker(), w.call(), "breaks above");
      }
      if (w.put() != null) {
        appendLine(sb, "📉", w.ticker(), w.put(), "breaks below");
      }
    }
    return truncate(sb.toString(), EMBED_DESC_MAX, 0);
  }

  private static void appendLine(
      StringBuilder sb, String emoji, String ticker, Leg leg, String direction) {
    if (sb.length() > 0) {
      sb.append('\n');
    }
    sb.append(emoji)
        .append(" **")
        .append(ticker)
        .append(' ')
        .append(leg.strike())
        .append(Character.toUpperCase(leg.right()))
        .append("** — ")
        .append(direction)
        .append(' ')
        .append(leg.trigger().setScale(2, RoundingMode.HALF_UP).toPlainString());
  }

  /**
   * Pure, deterministic formatting: a header line, then the raw watchlist text wrapped in a
   * triple-backtick fenced code block. Neutralizes any literal triple-backtick in the body so it
   * cannot prematurely close the fence, and truncates the body (inside the fence) if the composed
   * message would exceed Discord's 2000-char limit.
   */
  static String format(WatchlistMirrorPayload payload) {
    String header =
        "📋 Watchlist — " + payload.getEtDate().toString() + " — via " + payload.getAuthor();

    String rawText = payload.getRawText() == null ? "" : payload.getRawText();
    // Fence-injection guard: neutralize literal fences by inserting a zero-width space so the body
    // can never close the surrounding code block early.
    String body = rawText.replace(FENCE, "``​`");

    // Overhead = header + "\n" + open-fence + "\n" + "\n" + close-fence.
    String prefix = header + "\n" + FENCE + "\n";
    String suffix = "\n" + FENCE;
    int overhead = prefix.length() + suffix.length();

    return prefix + truncate(body, DISCORD_MAX, overhead) + suffix;
  }

  /**
   * Truncates {@code body} (appending {@link #TRUNCATION_MARKER}) only when {@code overhead +
   * body.length()} would exceed {@code max}; otherwise returns {@code body} unchanged. The budget
   * is clamped non-negative so a pathological overhead never throws.
   */
  private static String truncate(String body, int max, int overhead) {
    if (overhead + body.length() <= max) {
      return body;
    }
    int budget = Math.max(0, max - overhead - TRUNCATION_MARKER.length());
    return body.substring(0, Math.min(body.length(), budget)) + TRUNCATION_MARKER;
  }
}
