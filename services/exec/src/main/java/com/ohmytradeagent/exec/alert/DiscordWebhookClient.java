package com.ohmytradeagent.exec.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issue #297: best-effort Discord webhook sender for the exec service, built on the JDK {@link
 * HttpClient} (the same transport the orchestrator alerter uses — kept dependency-light and
 * identical in behavior across both services).
 *
 * <p>The {@link #post(String)} contract is strictly non-blocking from the broker/order path's
 * perspective: the request carries a bounded connect+request timeout and EVERY failure mode is
 * caught and logged at WARN — never rethrown. A notification webhook being down must not become a
 * trading-path failure mode, and must not alter the original broker exception's Temporal
 * retryable/non-retryable classification (the #295 / #264 lesson).
 *
 * <p>When no webhook URL is configured ({@code alert.discord.webhook-url} blank/unset) the client
 * is a no-op: it logs the alert at INFO and returns. Provisioning the real webhook is an operator
 * follow-up.
 *
 * <p>Per-tenant routing: a second source ({@code alert.discord.webhook-urls} / {@code
 * ALERT_DISCORD_WEBHOOK_URLS}) carries an optional map of {@code tenantId=url} entries (entries
 * {@code ;}-separated, key/value split on the FIRST {@code =}). Parsed ONCE at construction into an
 * immutable map. The tenant-scoped {@link #post(String, String)} / {@link #postEmbed(String,
 * WebhookEmbed)} resolve {@code tenantId} to its dedicated URL, falling back to the global default
 * ({@code webhook-url}) when the tenant is absent. The no-arg overloads always use the global
 * default (back-compat). A malformed map entry is skipped with a single WARN that NEVER logs the
 * URL/token — only the tenant key or a count. This is the exec mirror of the orchestrator routing
 * fix: without it, a live tenant's broker-rejection alert lands in another tenant's channel.
 */
@Component
public class DiscordWebhookClient implements WebhookClient {

  private static final Logger log = LoggerFactory.getLogger(DiscordWebhookClient.class);

  /** Discord embed limits (defensive truncation only — alerts carry ≤6 short fields). */
  private static final int MAX_FIELDS = 25;

  private static final int MAX_FIELD_NAME = 256;
  private static final int MAX_FIELD_VALUE = 1024;

  /** Global default webhook (back-compat). Used by the no-arg overloads and as the fallback. */
  private final String webhookUrl;

  /** Immutable per-tenant routing map (tenantId → url), parsed once at construction. */
  private final Map<String, String> webhookUrlsByTenant;

  private final HttpClient httpClient;
  private final Duration requestTimeout;

  @Autowired
  public DiscordWebhookClient(
      @Value("${alert.discord.webhook-url:}") String webhookUrl,
      @Value("${alert.discord.webhook-urls:}") String webhookUrls) {
    this(
        webhookUrl,
        webhookUrls,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        Duration.ofSeconds(3));
  }

  /**
   * Test seam: inject a stub {@link HttpClient} and a short timeout. {@code webhookUrls} is the raw
   * {@code tenant=url;tenant=url} string (parsed here exactly as in production); pass {@code ""}
   * for the global-only case.
   */
  DiscordWebhookClient(
      String webhookUrl, String webhookUrls, HttpClient httpClient, Duration requestTimeout) {
    this.webhookUrl = webhookUrl;
    this.webhookUrlsByTenant = parseWebhookUrls(webhookUrls);
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Parses the per-tenant map source ({@code tenant=url} entries, {@code ;}-separated, split on the
   * FIRST {@code =} so a URL may itself contain {@code =}) into an immutable {@code tenantId → url}
   * map. Trims keys/values; skips blank or malformed entries (missing {@code =}, blank key, or
   * blank value) with a single WARN that logs ONLY the offending tenant key or a count — NEVER the
   * URL/token. Never throws: a malformed source degrades to whatever entries parsed cleanly. Uses a
   * {@link LinkedHashMap} for deterministic ordering. {@code null}/blank source → empty map.
   */
  private static Map<String, String> parseWebhookUrls(String raw) {
    Map<String, String> parsed = new LinkedHashMap<>();
    if (raw == null || raw.isBlank()) {
      return Map.copyOf(parsed);
    }
    int skipped = 0;
    StringBuilder skippedKeys = new StringBuilder();
    for (String entry : raw.split(";")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        // Missing '=' or a blank key (leading '='). Do NOT log the value — it may carry a token.
        skipped++;
        continue;
      }
      String tenant = trimmed.substring(0, eq).trim();
      String url = trimmed.substring(eq + 1).trim();
      if (tenant.isEmpty() || url.isEmpty()) {
        skipped++;
        if (!tenant.isEmpty()) {
          if (skippedKeys.length() > 0) {
            skippedKeys.append(',');
          }
          skippedKeys.append(tenant);
        }
        continue;
      }
      parsed.put(tenant, url);
    }
    if (skipped > 0) {
      log.warn(
          "discord-alert webhook-urls: skipped {} malformed entr{} (tenant keys: {})",
          skipped,
          skipped == 1 ? "y" : "ies",
          skippedKeys.length() == 0 ? "n/a" : skippedKeys.toString());
    }
    return Map.copyOf(parsed);
  }

  /**
   * Fixed mention-suppression literal appended to every webhook body: with {@code parse:[]} Discord
   * renders {@code @everyone}/{@code @here}/{@code <@…>}/{@code <@&…>} as text but never notifies.
   * No user input — these are operator alert feeds and none should ever ping.
   */
  private static final String NO_MENTIONS = ",\"allowed_mentions\":{\"parse\":[]}";

  @Override
  public void post(String content) {
    send(webhookUrl, "{\"content\":" + jsonString(content) + NO_MENTIONS + "}", content);
  }

  @Override
  public void post(String tenantId, String content) {
    send(resolve(tenantId), "{\"content\":" + jsonString(content) + NO_MENTIONS + "}", content);
  }

  @Override
  public void postEmbed(WebhookEmbed embed) {
    send(webhookUrl, embedBody(embed), embed.title());
  }

  @Override
  public void postEmbed(String tenantId, WebhookEmbed embed) {
    send(resolve(tenantId), embedBody(embed), embed.title());
  }

  /**
   * Resolves {@code tenantId} to its dedicated webhook URL, falling back to the global default when
   * the tenant has no per-tenant entry (or {@code tenantId} is {@code null}). The blank-URL no-op
   * and swallow-and-log contract is preserved downstream in {@link #send}.
   */
  private String resolve(String tenantId) {
    if (tenantId == null) {
      return webhookUrl;
    }
    return webhookUrlsByTenant.getOrDefault(tenantId, webhookUrl);
  }

  /**
   * Serializes a {@link WebhookEmbed} into the Discord {@code {"embeds":[{...}]}} payload. Mirrors
   * the orchestrator copy: respects Discord's embed limits with defensive truncation (≤ {@value
   * #MAX_FIELDS} fields, field {@code name} ≤ {@value #MAX_FIELD_NAME} and {@code value} ≤ {@value
   * #MAX_FIELD_VALUE} chars) and omits a blank {@code description}.
   */
  static String embedBody(WebhookEmbed embed) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"embeds\":[{\"title\":").append(jsonString(embed.title()));
    if (embed.description() != null && !embed.description().isBlank()) {
      sb.append(",\"description\":").append(jsonString(embed.description()));
    }
    sb.append(",\"color\":").append(embed.color());
    List<WebhookEmbed.Field> fields = embed.fields();
    if (fields != null && !fields.isEmpty()) {
      sb.append(",\"fields\":[");
      int count = Math.min(fields.size(), MAX_FIELDS);
      for (int i = 0; i < count; i++) {
        WebhookEmbed.Field f = fields.get(i);
        if (i > 0) {
          sb.append(',');
        }
        sb.append("{\"name\":")
            .append(jsonString(truncate(f.name(), MAX_FIELD_NAME)))
            .append(",\"value\":")
            .append(jsonString(truncate(f.value(), MAX_FIELD_VALUE)))
            .append(",\"inline\":")
            .append(f.inline())
            .append('}');
      }
      sb.append(']');
    }
    if (embed.footer() != null && !embed.footer().isBlank()) {
      sb.append(",\"footer\":{\"text\":").append(jsonString(embed.footer())).append("}");
    }
    sb.append("}]").append(NO_MENTIONS).append("}");
    return sb.toString();
  }

  /**
   * Caps {@code value} to {@code max} chars (blank/null → empty); defensive against Discord limits.
   */
  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /**
   * Shared best-effort HTTP send. {@code targetUrl} is the resolved destination (global default or
   * a per-tenant URL); {@code jsonBody} is the fully-built request payload. A blank URL is a no-op
   * and EVERY failure mode (timeout, non-2xx, interruption, any exception) is caught and logged —
   * never rethrown, so the broker/order path is never disrupted. {@code logHint} is echoed in WARN
   * logs (plain content or the embed title).
   */
  private void send(String targetUrl, String jsonBody, String logHint) {
    if (targetUrl == null || targetUrl.isBlank()) {
      log.info("discord-alert (no webhook configured): {}", logHint);
      return;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(targetUrl))
              .timeout(requestTimeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        log.warn("discord-alert non-2xx status={} content={}", status, logHint);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("discord-alert interrupted content={}", logHint, e);
    } catch (Exception e) {
      // Best-effort: never propagate. A down/slow webhook must not break the broker/order path.
      log.warn("discord-alert dispatch failed content={}", logHint, e);
    }
  }

  /** Minimal JSON string escaping for the webhook payload (no extra JSON dependency needed). */
  private static String jsonString(String raw) {
    StringBuilder sb = new StringBuilder(raw.length() + 2);
    sb.append('"');
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
