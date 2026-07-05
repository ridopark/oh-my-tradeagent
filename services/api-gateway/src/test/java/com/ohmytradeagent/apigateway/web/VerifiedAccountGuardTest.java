package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.config.ExecTargetProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * A1 arm-guard unit test. Drives {@link VerifiedAccountGuard} against real in-process {@link
 * HttpServer}s (one per broker_target, so per-target routing is genuinely exercised) so the actual
 * exec HTTP path — including the FAIL-CLOSED fault handling (C1) — is exercised, not a fluent-API
 * mock.
 *
 * <p>FAIL-CLOSED matrix: a well-formed {@code verified:true}+account → ALLOW; explicit {@code
 * verified:false} → REJECT_UNVERIFIED; a 5xx, a missing {@code verified}, a {@code verified:true}
 * with a blank account, and a network fault (server stopped) → FAULT (never a silent pass).
 *
 * <p>Per-target routing (#548): a {@code -live} target is verified against its OWN exec pod (from
 * {@code exec.targets}), not the shared paper base — so a live {@code verified:true} is
 * trustworthy. A broker_target absent from {@code exec.targets} (or a bare/unknown one) is refused
 * WITHOUT any HTTP call and NEVER falls back to the shared paper base (C5).
 */
class VerifiedAccountGuardTest {

  private static final String PAPER_TARGET = "alpaca-paper";
  private static final String LIVE_TARGET = "alpaca-live";

  private Stub paper;
  private Stub live;

  /** An in-process exec stand-in: a scripted next-response and a request-hit counter. */
  private static final class Stub {
    private final HttpServer server;
    private final AtomicReference<Response> nextResponse = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger(0);
    private final String baseUrl;

    Stub() throws IOException {
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

    void respond(int status, String body) {
      nextResponse.set(new Response(status, body));
    }

    void stop() {
      server.stop(0);
    }
  }

  private record Response(int status, String body) {}

  @BeforeEach
  void setUp() throws IOException {
    paper = new Stub();
    live = new Stub();
  }

  @AfterEach
  void tearDown() {
    if (paper != null) {
      paper.stop();
    }
    if (live != null) {
      live.stop();
    }
  }

  private static ExecTargetProperties targets(Map<String, String> map) {
    ExecTargetProperties p = new ExecTargetProperties();
    p.setTargets(map);
    return p;
  }

  // A deliberately-dead shared base: the guard routes on absolute exec.targets URIs and NEVER uses
  // this base. If it ever fell back to the shared base, the request would fail here (proving the
  // no-paper-fallback contract), and the no-call hit counters would still catch a misroute.
  private static final String UNUSED_SHARED_BASE = "http://127.0.0.1:1";

  /** A guard whose {@code exec.targets} routes paper and live at their own stand-ins. */
  private VerifiedAccountGuard guard() {
    return guard(targets(Map.of(PAPER_TARGET, paper.baseUrl, LIVE_TARGET, live.baseUrl)));
  }

  private VerifiedAccountGuard guard(ExecTargetProperties execTargets) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(2))
            .withReadTimeout(Duration.ofSeconds(2));
    RestClient client =
        RestClient.builder()
            .baseUrl(UNUSED_SHARED_BASE)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build();
    return new VerifiedAccountGuard(client, execTargets);
  }

  // ---- paper target (existing behavior, still green) --------------------------------------------

  @Test
  void paper_verifiedTrueWithAccount_allows() {
    paper.respond(200, "{\"verified\":true,\"account\":\"PA3FKGPFYPLH\"}");
    assertThat(guard().evaluate("acme", PAPER_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.ALLOW);
    assertThat(paper.hits.get()).isEqualTo(1);
    assertThat(live.hits.get()).isEqualTo(0);
  }

  @Test
  void paper_verifiedFalse_rejectsUnverified() {
    paper.respond(200, "{\"verified\":false}");
    assertThat(guard().evaluate("acme", PAPER_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNVERIFIED);
  }

  @Test
  void paper_serverError_isFault() {
    paper.respond(500, "{\"error\":\"boom\"}");
    assertThat(guard().evaluate("acme", PAPER_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void paper_missingVerifiedField_isFault() {
    paper.respond(200, "{\"account\":\"PA3FKGPFYPLH\"}");
    assertThat(guard().evaluate("acme", PAPER_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void paper_verifiedTrueButBlankAccount_isFault() {
    paper.respond(200, "{\"verified\":true,\"account\":\"  \"}");
    assertThat(guard().evaluate("acme", PAPER_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void paper_networkFault_serverDown_isFault_neverAllows() {
    paper.stop();
    paper = null; // avoid double-stop in tearDown
    assertThat(
            guard(targets(Map.of(PAPER_TARGET, "http://127.0.0.1:1", LIVE_TARGET, live.baseUrl)))
                .evaluate("acme", PAPER_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  // ---- live target (incident reproduction: today this hard-refuses) -----------------------------

  @Test
  void live_verifiedTrueWithAccount_allows_routedToLivePod() {
    // The incident-reproduction case: a verified LIVE account must ALLOW, read from the LIVE pod.
    live.respond(200, "{\"verified\":true,\"account\":\"847309116\"}");
    assertThat(guard().evaluate("acme", LIVE_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.ALLOW);
    // Read from the live pod, NEVER the shared paper base.
    assertThat(live.hits.get()).isEqualTo(1);
    assertThat(paper.hits.get()).isEqualTo(0);
  }

  @Test
  void live_verifiedFalse_rejectsUnverified() {
    live.respond(200, "{\"verified\":false}");
    assertThat(guard().evaluate("acme", LIVE_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNVERIFIED);
    assertThat(paper.hits.get()).isEqualTo(0);
  }

  @Test
  void live_verifiedTrueButBlankAccount_isFault() {
    live.respond(200, "{\"verified\":true,\"account\":\"\"}");
    assertThat(guard().evaluate("acme", LIVE_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  @Test
  void live_transportFault_serverDown_isFault() {
    live.stop();
    live = null; // avoid double-stop in tearDown
    assertThat(
            guard(targets(Map.of(PAPER_TARGET, paper.baseUrl, LIVE_TARGET, "http://127.0.0.1:1")))
                .evaluate("acme", LIVE_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.FAULT);
  }

  // ---- fail-closed: unmapped / unknown broker_target, no HTTP call, no paper fallback -----------

  @Test
  void unmappedTarget_rejectsUnsupported_withoutAnyExecCall() {
    // tradier-live is a well-formed <provider>-live target but is NOT in exec.targets → fail
    // closed,
    // NO http call, NEVER a fallback to the shared paper base.
    live.respond(200, "{\"verified\":true,\"account\":\"847309116\"}");
    assertThat(guard().evaluate("acme", "tradier-live"))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNSUPPORTED_TARGET);
    assertThat(paper.hits.get()).isEqualTo(0);
    assertThat(live.hits.get()).isEqualTo(0);
  }

  @Test
  void unknownSuffixTarget_rejectsUnsupported_withoutAnyExecCall() {
    // alpaca-foo is neither -paper nor -live → not a supported target → fail closed, no http call.
    assertThat(guard().evaluate("acme", "alpaca-foo"))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNSUPPORTED_TARGET);
    assertThat(paper.hits.get()).isEqualTo(0);
    assertThat(live.hits.get()).isEqualTo(0);
  }

  @Test
  void blankMappedBase_rejectsUnsupported_withoutAnyExecCall() {
    // An exec.targets entry present but BLANK for the tenant's broker_target → fail closed (the
    // execBase.isBlank() branch), NO http call, NEVER a fallback to the shared paper base.
    live.respond(200, "{\"verified\":true,\"account\":\"847309116\"}");
    assertThat(guard(targets(Map.of(LIVE_TARGET, ""))).evaluate("acme", LIVE_TARGET))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNSUPPORTED_TARGET);
    assertThat(paper.hits.get()).isEqualTo(0);
    assertThat(live.hits.get()).isEqualTo(0);
  }

  @Test
  void caseVariantTarget_rejectsUnsupported_withoutAnyExecCall() {
    // Stored broker_target "Alpaca-Live" (mixed case) with only a lowercase alpaca-live key → the
    // match is case-SENSITIVE, so it must fail closed (no http call), NEVER fall back to paper.
    live.respond(200, "{\"verified\":true,\"account\":\"847309116\"}");
    assertThat(guard().evaluate("acme", "Alpaca-Live"))
        .isEqualTo(VerifiedAccountGuard.Decision.REJECT_UNSUPPORTED_TARGET);
    assertThat(paper.hits.get()).isEqualTo(0);
    assertThat(live.hits.get()).isEqualTo(0);
  }

  @Test
  void trailingSpaceTarget_isTrimmed_routedToLivePod_allows() {
    // Stored broker_target "alpaca-live " (trailing space) with the map keyed on "alpaca-live" →
    // the .trim() on the lookup routes to the LIVE pod (ALLOW), never the shared paper base.
    live.respond(200, "{\"verified\":true,\"account\":\"847309116\"}");
    assertThat(guard().evaluate("acme", "alpaca-live "))
        .isEqualTo(VerifiedAccountGuard.Decision.ALLOW);
    assertThat(live.hits.get()).isEqualTo(1);
    assertThat(paper.hits.get()).isEqualTo(0);
  }

  @Test
  void providerOf_derivesProviderFromPaperAndLive_nullForBareOrUnknown() {
    assertThat(VerifiedAccountGuard.providerOf(null)).isNull();
    assertThat(VerifiedAccountGuard.providerOf("paper")).isNull();
    assertThat(VerifiedAccountGuard.providerOf("live")).isNull();
    assertThat(VerifiedAccountGuard.providerOf("alpaca-foo")).isNull();
    assertThat(VerifiedAccountGuard.providerOf("alpaca-paper")).isEqualTo("alpaca");
    assertThat(VerifiedAccountGuard.providerOf("alpaca-live")).isEqualTo("alpaca");
    assertThat(VerifiedAccountGuard.providerOf("tradier-paper")).isEqualTo("tradier");
    assertThat(VerifiedAccountGuard.providerOf("tradier-live")).isEqualTo("tradier");
  }
}
