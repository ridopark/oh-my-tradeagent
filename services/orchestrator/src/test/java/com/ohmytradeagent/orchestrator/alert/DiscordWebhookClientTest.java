package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

  @Test
  void postEmbedBlankUrlIsNoOpAndDoesNotThrow() {
    DiscordWebhookClient client = new DiscordWebhookClient("");
    assertThatCode(() -> client.postEmbed(new WebhookEmbed("t", "d", 5763719, "f")))
        .doesNotThrowAnyException();
  }

  @Test
  void postEmbedSendsEmbedsJsonWithEscapedFields() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<Void> resp = Mockito.mock(HttpResponse.class);
    Mockito.when(resp.statusCode()).thenReturn(204);
    Mockito.when(
            http.send(
                Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any()))
        .thenReturn(resp);
    DiscordWebhookClient client =
        new DiscordWebhookClient("https://discord.example/webhook", http, Duration.ofSeconds(1));

    client.postEmbed(new WebhookEmbed("Title \"q\"", "line1\nline2", 5763719, "via Author"));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    Mockito.verify(http).send(captor.capture(), Mockito.<HttpResponse.BodyHandler<Void>>any());
    String body = bodyOf(captor.getValue());

    assertThat(body).contains("\"embeds\":[{");
    assertThat(body).contains("\"color\":5763719");
    assertThat(body).contains("\"footer\":{\"text\":\"via Author\"}");
    // The title's embedded quote and the description's newline are JSON-escaped.
    assertThat(body).contains("\"title\":\"Title \\\"q\\\"\"");
    assertThat(body).contains("\"description\":\"line1\\nline2\"");
  }

  @Test
  void postEmbedSerializesStackedFieldsAndOmitsBlankDescription() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<Void> resp = Mockito.mock(HttpResponse.class);
    Mockito.when(resp.statusCode()).thenReturn(204);
    Mockito.when(
            http.send(
                Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any()))
        .thenReturn(resp);
    DiscordWebhookClient client =
        new DiscordWebhookClient("https://discord.example/webhook", http, Duration.ofSeconds(1));

    client.postEmbed(
        new WebhookEmbed(
            "🚨 FAILED",
            null,
            15548997,
            "workflow_id: wf-1 | tenant/strategy: dev/copytrade-v1",
            java.util.List.of(
                new WebhookEmbed.Field(
                    "symbol",
                    "[AAPL 260116C00200000](https://finance.yahoo.com/quote/AAPL%20%20260116C00200000/)",
                    false),
                new WebhookEmbed.Field("reason", "DAILY_LOSS_LIMIT", false))));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    Mockito.verify(http).send(captor.capture(), Mockito.<HttpResponse.BodyHandler<Void>>any());
    String body = bodyOf(captor.getValue());

    assertThat(body).contains("\"color\":15548997");
    assertThat(body).contains("\"fields\":[{");
    assertThat(body).contains("\"name\":\"symbol\"");
    assertThat(body)
        .contains(
            "\"value\":\"[AAPL 260116C00200000](https://finance.yahoo.com/quote/AAPL%20%20260116C00200000/)\"");
    assertThat(body).contains("\"inline\":false");
    assertThat(body).contains("\"name\":\"reason\"");
    // A null/blank description is omitted entirely (alerts use fields, not a description).
    assertThat(body).doesNotContain("\"description\"");
  }

  /** Drains the request's BodyPublisher into a UTF-8 string. */
  private static String bodyOf(HttpRequest request) {
    Flow.Publisher<ByteBuffer> publisher = request.bodyPublisher().orElseThrow();
    StringBuilder sb = new StringBuilder();
    CompletableFuture<Void> done = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ByteBuffer item) {
            sb.append(StandardCharsets.UTF_8.decode(item));
          }

          @Override
          public void onError(Throwable throwable) {
            done.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            done.complete(null);
          }
        });
    done.join();
    return sb.toString();
  }
}
