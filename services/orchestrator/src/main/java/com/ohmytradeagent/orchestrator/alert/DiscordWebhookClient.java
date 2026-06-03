package com.ohmytradeagent.orchestrator.alert;

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
 * Issue #297: best-effort Discord webhook sender built on the JDK {@link HttpClient} (orchestrator
 * deliberately has no {@code spring-boot-starter-web}, so we avoid pulling in {@code RestClient}).
 *
 * <p>The {@link #post(String)} contract is strictly non-blocking from the trading path's
 * perspective: the request carries a bounded connect+request timeout and EVERY failure mode
 * (timeout, non-2xx response, {@link InterruptedException}, any other exception) is caught and
 * logged at WARN — never rethrown. A notification webhook being down must not become a trading-path
 * failure mode (the #295 lesson).
 *
 * <p>When no webhook URL is configured ({@code alert.discord.webhook-url} blank/unset) the client
 * is a no-op: it logs the alert at INFO and returns. This lets the feature ship and be tested
 * without the live secret value — provisioning the real webhook is an operator follow-up.
 */
@Component
public class DiscordWebhookClient implements WebhookClient {

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

  @Override
  public void post(String content) {
    send("{\"content\":" + jsonString(content) + "}", content);
  }

  @Override
  public void postEmbed(WebhookEmbed embed) {
    String body =
        "{\"embeds\":[{\"title\":"
            + jsonString(embed.title())
            + ",\"description\":"
            + jsonString(embed.description())
            + ",\"color\":"
            + embed.color()
            + ",\"footer\":{\"text\":"
            + jsonString(embed.footer())
            + "}}]}";
    send(body, embed.title());
  }

  /**
   * Shared best-effort HTTP send. {@code jsonBody} is the fully-built request payload; {@code
   * logHint} is a short identifier echoed in WARN logs (the plain content or the embed title). A
   * blank URL is a no-op and EVERY failure mode (timeout, non-2xx, interruption, any exception) is
   * caught and logged — never rethrown, so the trading/audit path is never disrupted.
   */
  private void send(String jsonBody, String logHint) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      // No secret provisioned (operator follow-up). Log so the alert is still visible in stdout.
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
      // Best-effort: never propagate. A down/slow webhook must not break the trading path.
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
