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

  private static final String CALL_HEADER = "CALL ▲ above";
  private static final String PUT_HEADER = "PUT ▼ below";
  private static final String NONE = "—";

  private final WebhookClient webhookClient;

  public WatchlistMirrorActivitiesImpl(WebhookClient webhookClient) {
    this.webhookClient = webhookClient;
  }

  @Override
  public void postWatchlistAlert(WatchlistMirrorPayload payload) {
    ParseResult parsed = WatchlistParser.parse(payload.getRawText());
    if (parsed.clean() && !parsed.rows().isEmpty()) {
      webhookClient.postEmbed(buildEmbed(payload, parsed.rows()));
    } else {
      // Malformed/empty watchlist is never dropped — fall back to verbatim raw text.
      webhookClient.post(format(payload));
    }
  }

  /**
   * Builds the rich embed: green accent, date-stamped title, an {@code "via <author>"} footer, and
   * a fenced monospace table as the description so columns stay aligned in Discord.
   */
  private static WebhookEmbed buildEmbed(WatchlistMirrorPayload payload, List<TickerWatch> rows) {
    String title = "📋 Watchlist — " + EMBED_DATE.format(payload.getEtDate());
    String description = renderTable(rows);
    String footer = "via " + payload.getAuthor();
    return new WebhookEmbed(title, description, DISCORD_GREEN, footer);
  }

  /**
   * Renders the per-ticker rows as a left-padded monospace table inside a fenced code block. Column
   * widths are computed from content. Strikes print without a trailing {@code .0}; triggers are
   * formatted to 2 decimals. An absent leg renders as an em-dash.
   */
  static String renderTable(List<TickerWatch> rows) {
    String[] tickerCol = new String[rows.size()];
    String[] callCol = new String[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      TickerWatch w = rows.get(i);
      tickerCol[i] = w.ticker();
      callCol[i] = formatLeg(w.call());
    }

    int tickerWidth = maxWidth("TICKER", tickerCol);
    int callWidth = maxWidth(CALL_HEADER, callCol);

    StringBuilder sb = new StringBuilder();
    sb.append(pad("TICKER", tickerWidth))
        .append("  ")
        .append(pad(CALL_HEADER, callWidth))
        .append("  ")
        .append(PUT_HEADER);
    for (int i = 0; i < rows.size(); i++) {
      sb.append('\n')
          .append(pad(tickerCol[i], tickerWidth))
          .append("  ")
          .append(pad(callCol[i], callWidth))
          .append("  ")
          .append(formatLeg(rows.get(i).put()));
    }

    // Overhead = open-fence + "\n" + "\n" + close-fence.
    String table = truncate(sb.toString(), EMBED_DESC_MAX, FENCE.length() * 2 + 2);
    return FENCE + "\n" + table + "\n" + FENCE;
  }

  private static String formatLeg(Leg leg) {
    if (leg == null) {
      return NONE;
    }
    return leg.strike()
        + Character.toUpperCase(leg.right())
        + "  "
        + leg.trigger().setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static int maxWidth(String header, String[] values) {
    int width = header.length();
    for (String v : values) {
      width = Math.max(width, v.length());
    }
    return width;
  }

  private static String pad(String value, int width) {
    if (value.length() >= width) {
      return value;
    }
    return value + " ".repeat(width - value.length());
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
