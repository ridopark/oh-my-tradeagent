package com.ohmytradeagent.exec.alert;

import java.util.List;

/**
 * Minimal value object for a single Discord embed. Ported from the orchestrator's {@code
 * WebhookEmbed} (the plan keeps the two alert packages mirrored rather than introducing a shared
 * dependency between services). Models the embed fields exec renders for the broker-rejection
 * alert: {@code title}, {@code color} (Discord's decimal RGB integer), stacked {@link Field}s and a
 * {@code footer}. {@code description} is optional.
 *
 * @param title embed title
 * @param description embed description; may be {@code null} (broker-rejection uses fields only)
 * @param color decimal RGB color for the embed accent bar
 * @param footer footer text (low-signal trace ids demoted here)
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

  /** Convenience constructor (no description) — broker-rejection builds fields only. */
  public WebhookEmbed(String title, int color, String footer, List<Field> fields) {
    this(title, null, color, footer, fields);
  }
}
