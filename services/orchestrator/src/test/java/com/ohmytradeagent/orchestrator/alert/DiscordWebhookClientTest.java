package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Issue #297: the webhook sender must be strictly best-effort — a blank URL is a no-op, and a
 * transport failure (IOException, timeout) is swallowed and logged, never rethrown.
 */
class DiscordWebhookClientTest {

  @Test
  void blankUrlIsNoOpAndDoesNotThrow() {
    DiscordWebhookClient client = new DiscordWebhookClient("");
    assertThatCode(() -> client.post("hello")).doesNotThrowAnyException();
  }

  @Test
  void transportIoExceptionIsSwallowed() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    Mockito.when(
            http.send(
                Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any()))
        .thenThrow(new IOException("connection reset"));
    DiscordWebhookClient client =
        new DiscordWebhookClient("https://discord.example/webhook", http, Duration.ofSeconds(1));

    assertThatCode(() -> client.post("alert body")).doesNotThrowAnyException();
  }

  @Test
  void non2xxResponseIsSwallowed() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<Void> resp = Mockito.mock(HttpResponse.class);
    Mockito.when(resp.statusCode()).thenReturn(500);
    Mockito.when(
            http.send(
                Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any()))
        .thenReturn(resp);
    DiscordWebhookClient client =
        new DiscordWebhookClient("https://discord.example/webhook", http, Duration.ofSeconds(1));

    assertThatCode(() -> client.post("alert body")).doesNotThrowAnyException();
  }
}
