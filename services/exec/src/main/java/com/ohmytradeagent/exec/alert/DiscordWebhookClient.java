package com.ohmytradeagent.exec.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
 */
@Component
public class DiscordWebhookClient implements WebhookClient {

  private static final Logger log = LoggerFactory.getLogger(DiscordWebhookClient.class);

  /** Discord embed limits (defensive truncation only — alerts carry ≤6 short fields). */
  private static final int MAX_FIELDS = 25;

  private static final int MAX_FIELD_NAME = 256;
  private static final int MAX_FIELD_VALUE = 1024;

  private final String webhookUrl;
  private final HttpClient httpClient;
  private final Duration requestTimeout;

  @Autowired
  public DiscordWebhookClient(@Value("${alert.discord.webhook-url:}") String webhookUrl) {
    this(
        webhookUrl,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        Duration.ofSeconds(3));
  }

  /** Test seam: inject a stub {@link HttpClient} and a short timeout. */
  DiscordWebhookClient(String webhookUrl, HttpClient httpClient, Duration requestTimeout) {
    this.webhookUrl = webhookUrl;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  @Override
  public void post(String content) {
    send("{\"content\":" + jsonString(content) + "}", content);
  }

  @Override
  public void postEmbed(WebhookEmbed embed) {
    send(embedBody(embed), embed.title());
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
    sb.append("}]}");
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
   * Shared best-effort HTTP send. A blank URL is a no-op and EVERY failure mode (timeout, non-2xx,
   * interruption, any exception) is caught and logged — never rethrown, so the broker/order path is
   * never disrupted. {@code logHint} is echoed in WARN logs (plain content or the embed title).
   */
  private void send(String jsonBody, String logHint) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      log.info("discord-alert (no webhook configured): {}", logHint);
      return;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(webhookUrl))
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
