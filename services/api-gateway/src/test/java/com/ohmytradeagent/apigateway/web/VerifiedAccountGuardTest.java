package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * A1 arm-guard unit test. Drives {@link VerifiedAccountGuard} against a real in-process {@link
 * HttpServer} so the actual exec HTTP path (including the FAIL-CLOSED fault handling, C1) is
 * exercised — not a fluent-API mock.
 *
 * <p>FAIL-CLOSED matrix: a well-formed {@code verified:true}+account → ALLOW; explicit {@code
 * verified:false} → REJECT_UNVERIFIED; a 5xx, a missing {@code verified}, a {@code verified:true}
 * with a blank account, and a network fault (server stopped) → FAULT (never a silent pass). A
 * {@code -live} / non-paper target is rejected WITHOUT any exec call (C5).
 */
class VerifiedAccountGuardTest {

  private HttpServer server;
  private final AtomicReference<Response> nextResponse = new AtomicReference<>();
  private final AtomicInteger hits = new AtomicInteger(0);
  private String baseUrl;

  private record Response(int status, String body) {}

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/broker-credentials",
        exchange -> {
          hits.incrementAndGet();
          Response r = nextResponse.get();
          byte[] payload =
              r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(r.status(), payload.length == 0 ? -1 : payload.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private VerifiedAccountGuard guard() {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(2))
            .withReadTimeout(Duration.ofSeconds(2));
    RestClient client =
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build();
    return new VerifiedAccountGuard(client);
  }

  @Test
  void verifiedTrueWithAccount_allows() {
    nextResponse.set(new Response(200, "{\"verified\":true,\"account\":\"847309116\"}"));
    assertThat(guard().evaluate("acme", "alpaca-paper"))
        .isEqualTo(VerifiedAccountGuard.Decision.ALLOW);
    assertThat(hits.get()).isEqualTo(1);
  }

  @Test
  void verifiedFalse_rejectsUnverified() {
    nextResponse.set(new Response(200, "{\"verified\":false}"));
    assertThat(guard().evaluate("acme", "alpaca-paper"))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNVERIFIED);
  }

  @Test
  void serverError_isFault() {
    nextResponse.set(new Response(500, "{\"error\":\"boom\"}"));
    assertThat(guard().evaluate("acme", "alpaca-paper"))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void missingVerifiedField_isFault() {
    nextResponse.set(new Response(200, "{\"account\":\"847309116\"}"));
    assertThat(guard().evaluate("acme", "alpaca-paper"))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void verifiedTrueButBlankAccount_isFault() {
    nextResponse.set(new Response(200, "{\"verified\":true,\"account\":\"  \"}"));
    assertThat(guard().evaluate("acme", "alpaca-paper"))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void networkFault_serverDown_isFault_neverAllows() {
    // Stop the server so the connection is refused — a fail-OPEN here would be worse than no guard.
    server.stop(0);
    server = null;
    assertThat(guard().evaluate("acme", "alpaca-paper"))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void liveTarget_isRejectedWithoutAnyExecCall() {
    nextResponse.set(new Response(200, "{\"verified\":true,\"account\":\"847309116\"}"));
    assertThat(guard().evaluate("acme", "alpaca-live"))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNSUPPORTED_TARGET);
    // C5: a live target must NOT read the paper pod — a stray paper creds row must not false-pass.
    assertThat(hits.get()).isEqualTo(0);
  }

  @Test
  void bareAndNullTargets_areUnsupported() {
    assertThat(VerifiedAccountGuard.paperProvider(null)).isNull();
    assertThat(VerifiedAccountGuard.paperProvider("paper")).isNull();
    assertThat(VerifiedAccountGuard.paperProvider("live")).isNull();
    assertThat(VerifiedAccountGuard.paperProvider("alpaca-live")).isNull();
    assertThat(VerifiedAccountGuard.paperProvider("alpaca-paper")).isEqualTo("alpaca");
    assertThat(VerifiedAccountGuard.paperProvider("tradier-paper")).isEqualTo("tradier");
  }
}
