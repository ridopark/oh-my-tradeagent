package com.ohmytradeagent.exec.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Issue #297: exec webhook sender — blank URL no-op, transport failure swallowed, embed JSON. */
class DiscordWebhookClientTest {

  @Test
  void blankUrlIsNoOpAndDoesNotThrow() {
    DiscordWebhookClient client = new DiscordWebhookClient("", "");
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
        new DiscordWebhookClient(
            "https://discord.example/webhook", "", http, Duration.ofSeconds(1));

    assertThatCode(() -> client.post("alert body")).doesNotThrowAnyException();
  }

  @Test
  void postEmbedBlankUrlIsNoOpAndDoesNotThrow() {
    DiscordWebhookClient client = new DiscordWebhookClient("", "");
    assertThatCode(() -> client.postEmbed(new WebhookEmbed("t", 15548997, "f", List.of())))
        .doesNotThrowAnyException();
  }

  @Test
  void postEmbedSendsEmbedsJsonWithStackedFields() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<Void> resp = Mockito.mock(HttpResponse.class);
    Mockito.when(resp.statusCode()).thenReturn(204);
    Mockito.when(
            http.send(
                Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any()))
        .thenReturn(resp);
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.example/webhook", "", http, Duration.ofSeconds(1));

    client.postEmbed(
        new WebhookEmbed(
            "Title \"q\"",
            15548997,
            "tenant/strategy: dev/copytrade-v1",
            List.of(
                new WebhookEmbed.Field(
                    "symbol",
                    "[AAPL 260116C00200000](https://finance.yahoo.com/quote/AAPL260116C00200000/)",
                    false),
                new WebhookEmbed.Field("reason", "422 too long", false))));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    Mockito.verify(http).send(captor.capture(), Mockito.<HttpResponse.BodyHandler<Void>>any());
    String body = bodyOf(captor.getValue());

    assertThat(body).contains("\"embeds\":[{");
    assertThat(body).contains("\"color\":15548997");
    assertThat(body).contains("\"footer\":{\"text\":\"tenant/strategy: dev/copytrade-v1\"}");
    assertThat(body).contains("\"title\":\"Title \\\"q\\\"\"");
    // fields[] carries the stacked rows; the symbol value is the Yahoo markdown link.
    assertThat(body).contains("\"fields\":[{");
    assertThat(body).contains("\"name\":\"symbol\"");
    assertThat(body)
        .contains(
            "\"value\":\"[AAPL 260116C00200000](https://finance.yahoo.com/quote/AAPL260116C00200000/)\"");
    assertThat(body).contains("\"inline\":false");
    assertThat(body).contains("\"name\":\"reason\"");
    // A fields-only embed carries no description key.
    assertThat(body).doesNotContain("\"description\"");
  }

  @Test
  void postContentBodyCarriesContentAndSuppressesMentions() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.example/webhook", "", http, Duration.ofSeconds(1));

    client.post("hello world");

    String body = sentBody(http);
    assertThat(body).contains("\"content\":\"hello world\"");
    assertThat(body).contains("\"allowed_mentions\":{\"parse\":[]}");
  }

  @Test
  void postEmbedBodyCarriesEmbedsAndSuppressesMentions() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.example/webhook", "", http, Duration.ofSeconds(1));

    client.postEmbed(new WebhookEmbed("Title", 15548997, "via Author", List.of()));

    String body = sentBody(http);
    assertThat(body).contains("\"embeds\":[{");
    assertThat(body).contains("\"allowed_mentions\":{\"parse\":[]}");
  }

  @Test
  void contentWithEveryoneMentionStillPostsButSuppressed() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.example/webhook", "", http, Duration.ofSeconds(1));

    client.post("alert @everyone now");

    String body = sentBody(http);
    assertThat(body).contains("@everyone");
    assertThat(body).contains("\"allowed_mentions\":{\"parse\":[]}");
  }

  // --- Per-tenant routing (ALERT_DISCORD_WEBHOOK_URLS) -------------------------------------------

  @Test
  void postEmbedConfiguredTenantRoutesToItsOwnWebhook() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            "acme=https://discord.test/webhooks/1/aaa;beta=https://discord.test/webhooks/2/bbb",
            http,
            Duration.ofSeconds(1));

    client.postEmbed("beta", new WebhookEmbed("t", 5763719, "f", List.of()));

    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/2/bbb");
  }

  @Test
  void postEmbedUnconfiguredTenantFallsBackToGlobalDefault() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            "acme=https://discord.test/webhooks/1/aaa",
            http,
            Duration.ofSeconds(1));

    client.postEmbed("unknown-tenant", new WebhookEmbed("t", 5763719, "f", List.of()));

    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/0/global");
  }

  @Test
  void postConfiguredTenantRoutesToItsOwnWebhook() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            "acme=https://discord.test/webhooks/1/aaa",
            http,
            Duration.ofSeconds(1));

    client.post("acme", "hello tenant");

    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/1/aaa");
  }

  @Test
  void nullTenantFallsBackToGlobalDefault() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            "acme=https://discord.test/webhooks/1/aaa",
            http,
            Duration.ofSeconds(1));

    client.postEmbed(null, new WebhookEmbed("t", 5763719, "f", List.of()));

    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/0/global");
  }

  @Test
  void bothGlobalAndTenantBlankIsNoOpAndDoesNotThrow() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    DiscordWebhookClient client = new DiscordWebhookClient("", "", http, Duration.ofSeconds(1));

    // Unconfigured tenant resolves to the (blank) global default → no HTTP send at all.
    assertThatCode(() -> client.postEmbed("acme", new WebhookEmbed("t", 1, "f", List.of())))
        .doesNotThrowAnyException();
    Mockito.verify(http, Mockito.never())
        .send(Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any());
  }

  // --- Map parsing ------------------------------------------------------------------------------

  @Test
  void mapParsingWellFormedMultiEntryRoutesEach() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            " acme = https://discord.test/webhooks/1/aaa ; beta=https://discord.test/webhooks/2/bbb ",
            http,
            Duration.ofSeconds(1));

    // Both entries parsed (whitespace trimmed around key and value).
    client.post("acme", "x");
    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/1/aaa");
  }

  @Test
  void mapParsingMalformedEntrySkippedWithoutThrowing() throws Exception {
    HttpClient http = okHttp();
    // "no-equals-here" is malformed (no '='); it must be skipped, the good entry still parsed.
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            "no-equals-here;acme=https://discord.test/webhooks/1/aaa",
            http,
            Duration.ofSeconds(1));

    client.post("acme", "x");
    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/1/aaa");
  }

  @Test
  void mapParsingPreservesUrlContainingSlashAndSplitsOnFirstEquals() throws Exception {
    HttpClient http = okHttp();
    // The value carries both '/' and a literal '=' (a query string) — split on the FIRST '=' only,
    // so the whole "https://.../path?a=b" is kept as the URL. A token in the URL is never logged.
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global",
            "acme=https://discord.test/webhooks/1/aaa?token=xyz",
            http,
            Duration.ofSeconds(1));

    client.post("acme", "x");
    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/1/aaa?token=xyz");
  }

  @Test
  void mapParsingEmptyStringYieldsEmptyMapSoEveryTenantUsesGlobal() throws Exception {
    HttpClient http = okHttp();
    DiscordWebhookClient client =
        new DiscordWebhookClient(
            "https://discord.test/webhooks/0/global", "", http, Duration.ofSeconds(1));

    client.post("acme", "x");
    assertThat(uriOf(http)).isEqualTo("https://discord.test/webhooks/0/global");
  }

  /** Captures the single request URI sent through the mock client. */
  private static String uriOf(HttpClient http) throws Exception {
    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    Mockito.verify(http).send(captor.capture(), Mockito.<HttpResponse.BodyHandler<Void>>any());
    return captor.getValue().uri().toString();
  }

  /** A mock HttpClient that returns a 204 for any send. */
  private static HttpClient okHttp() throws Exception {
    HttpClient http = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<Void> resp = Mockito.mock(HttpResponse.class);
    Mockito.when(resp.statusCode()).thenReturn(204);
    Mockito.when(
            http.send(
                Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<Void>>any()))
        .thenReturn(resp);
    return http;
  }

  /** Captures and drains the single request body sent through the mock client. */
  private static String sentBody(HttpClient http) throws Exception {
    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    Mockito.verify(http).send(captor.capture(), Mockito.<HttpResponse.BodyHandler<Void>>any());
    return bodyOf(captor.getValue());
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
