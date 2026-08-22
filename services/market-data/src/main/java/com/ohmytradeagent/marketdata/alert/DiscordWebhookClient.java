package com.ohmytradeagent.marketdata.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * #776 P2: minimal best-effort Discord webhook sender for market-data's recovery alerts. Same
 * pattern as the exec/orchestrator copies — the services deliberately do not share alert code — but
 * deliberately smaller: one global URL, plain-content posts only (no per-tenant routing, no
 * embeds), because the boot recovery is the only caller.
 *
 * <p>Strictly non-blocking for the caller: bounded connect+request timeouts and EVERY failure mode
 * (timeout, non-2xx, interruption, any exception) is caught and logged at WARN — never rethrown. A
 * down webhook must never become a market-data failure mode.
 *
 * <p>When no URL is configured ({@code alert.discord.webhook-url} blank/unset — the dev/CI
 * default), the client is a no-op that logs the alert at INFO so it stays visible in stdout.
 */
@Component
public class DiscordWebhookClient {

  private static final Logger log = LoggerFactory.getLogger(DiscordWebhookClient.class);

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

  /**
   * Posts {@code content} to the configured webhook, best-effort. {@code allowed_mentions.parse=[]}
   * so an alert can never ping (operator feed; no user input should notify).
   */
  public void post(String content) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      // No secret provisioned (operator follow-up). Log so the alert is still visible in stdout.
      log.info("discord-alert (no webhook configured): {}", content);
      return;
    }
    try {
      String body = "{\"content\":" + jsonString(content) + ",\"allowed_mentions\":{\"parse\":[]}}";
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(webhookUrl))
              .timeout(requestTimeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        log.warn("discord-alert non-2xx status={} content={}", status, content);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("discord-alert interrupted content={}", content, e);
    } catch (Exception e) {
      // Best-effort: never propagate. A down/slow webhook must not break market-data.
      log.warn("discord-alert dispatch failed content={}", content, e);
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
