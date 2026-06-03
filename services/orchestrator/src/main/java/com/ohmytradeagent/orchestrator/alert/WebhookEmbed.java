package com.ohmytradeagent.orchestrator.alert;

import java.util.List;

/**
 * Minimal value object for a single Discord embed. Models the embed fields the orchestrator
 * renders: {@code title}, {@code description}, {@code color} (Discord's decimal RGB integer),
 * {@code fields} (the stacked label/value rows used by the trade alerters) and a {@code footer}
 * text. Kept tiny on purpose — only the fields actually rendered are modelled.
 *
 * <p>{@code description} is optional (the watchlist embed uses a fenced monospace table; the trade
 * alerts use {@link Field stacked fields} instead). {@code fields} defaults to an empty list via
 * the legacy four-arg constructor so the existing watchlist call site stays source-compatible.
 *
 * @param title embed title
 * @param description embed description (may carry a fenced monospace table); may be {@code null}
 * @param color decimal RGB color for the embed accent bar
 * @param footer footer text (e.g. {@code "via <author>"})
 * @param fields stacked label/value rows (each {@code inline:false}); never {@code null}
 */
public record WebhookEmbed(
    String title, String description, int color, String footer, List<Field> fields) {

  /**
   * A single Discord embed field — one labeled row in the stacked layout.
   *
   * @param name the field label (e.g. {@code "symbol"})
   * @param value the field value (may carry a markdown link, e.g. the Yahoo-linked contract)
   * @param inline whether Discord may pack this field side-by-side (alerts use {@code false})
   */
  public record Field(String name, String value, boolean inline) {}

  public WebhookEmbed {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  /**
   * Legacy four-arg constructor (no fields) — used by the watchlist mirror's fenced-table embed.
   */
  public WebhookEmbed(String title, String description, int color, String footer) {
    this(title, description, color, footer, List.of());
  }
}
