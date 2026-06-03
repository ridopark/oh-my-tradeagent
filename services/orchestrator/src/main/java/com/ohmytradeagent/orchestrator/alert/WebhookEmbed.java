package com.ohmytradeagent.orchestrator.alert;

/**
 * Minimal value object for a single Discord embed. Maps 1:1 onto the embed fields the orchestrator
 * uses for the rich watchlist post: {@code title}, {@code description}, {@code color} (Discord's
 * decimal RGB integer) and a {@code footer} text. Kept tiny on purpose — only the fields actually
 * rendered are modelled.
 *
 * @param title embed title
 * @param description embed description (may carry a fenced monospace table)
 * @param color decimal RGB color for the embed accent bar
 * @param footer footer text (e.g. {@code "via <author>"})
 */
public record WebhookEmbed(String title, String description, int color, String footer) {}
