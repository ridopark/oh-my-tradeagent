package com.ohmytradeagent.exec.alert;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Issue #297: exec webhook sender — blank URL no-op, transport failure swallowed. */
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
}
