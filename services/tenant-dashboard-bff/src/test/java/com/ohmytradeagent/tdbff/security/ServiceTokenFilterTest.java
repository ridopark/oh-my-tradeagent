package com.ohmytradeagent.tdbff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {

  private static final String TOKEN = "s3cr3t-shared-token";
  private static final String INGEST_TOKEN = "s3cr3t-options-chat-ingest";
  private final ServiceTokenFilter filter =
      new ServiceTokenFilter(TOKEN, INGEST_TOKEN, new MockEnvironment());

  @Test
  void missingAuthorizationHeader_is401_andDoesNotInvokeChain() throws Exception {
    MockHttpServletResponse res = run("/api/positions", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void wrongToken_is401() throws Exception {
    MockHttpServletResponse res = run("/api/trades", "Bearer not-the-token");
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void nonBearerScheme_is401() throws Exception {
    MockHttpServletResponse res = run("/api/orders", "Basic " + TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void correctBearerToken_passesThrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/portfolio");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    // Chain invoked => request reached the (mock) downstream handler.
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void actuatorHealth_isExemptFromTokenCheck() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health/readiness");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isSameAs(req); // not filtered, passed straight through
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @Test
  void wellKnownDefaultToken_underProdProfile_failsBoot() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    assertThatThrownBy(() -> new ServiceTokenFilter("dev-shared-token", INGEST_TOKEN, prod))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wellKnownDefaultToken_withoutProdProfile_isAllowedForLocalDev() {
    assertThatCode(
            () -> new ServiceTokenFilter("dev-shared-token", INGEST_TOKEN, new MockEnvironment()))
        .doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------------------------
  // options-chat ingest: a SEPARATE token, and the two must not substitute for each other.
  //
  // The caller of this route is the Discord chat-mirror pod, which renders an untrusted third-party
  // room. If it held the shared token it could set any X-Tenant-Id and read positions/orders for
  // real-money tenants. These tests are the enforcement of that isolation in BOTH directions — the
  // reverse case matters just as much, since a leaked ingest token must not open the tenant reads.
  // ---------------------------------------------------------------------------------------------

  @Test
  void ingestRoute_withItsOwnToken_passesThrough() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/internal/options-chat/ingest");
    req.addHeader("Authorization", "Bearer " + INGEST_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void ingestRoute_withTheSharedToken_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/options-chat/ingest", "Bearer " + TOKEN);
    assertThat(res.getStatus())
        .as("holding BFF_SHARED_TOKEN must NOT open the options-chat ingest")
        .isEqualTo(401);
  }

  @Test
  void tenantReadRoute_withTheIngestToken_is401() throws Exception {
    MockHttpServletResponse res = run("/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus())
        .as("a leaked options-chat ingest token must NOT open tenant reads")
        .isEqualTo(401);
  }

  @Test
  void ingestRoute_withoutBearer_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/options-chat/ingest", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // Path-traversal guard. getRequestURI() is the RAW request line; Spring dispatches on the DECODED
  // and NORMALIZED path. Without this guard the two disagree and every path-based decision this
  // filter makes can be aimed at a different handler than the one it authorized.
  // ---------------------------------------------------------------------------------------------

  @Test
  void ingestPrefixWithEncodedDots_cannotSmuggleTheIngestTokenIntoATenantRead() throws Exception {
    // The raw URI startsWith the ingest prefix, so the prefix check alone would accept the ingest
    // token — while the container normalizes this to /api/positions.
    MockHttpServletResponse res =
        run("/internal/options-chat/%2e%2e/%2e%2e/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void actuatorPrefixWithEncodedDots_isNotExempt() throws Exception {
    // Worse than the ingest case: the actuator exemption skips the filter entirely, so without the
    // guard this would reach a tenant read with NO credential at all.
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/actuator/%2e%2e/api/positions");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).as("must not have been passed downstream").isNull();
  }

  @Test
  void pathParameterTraversal_cannotSmuggleTheIngestTokenIntoATenantRead() throws Exception {
    // Tomcat strips path parameters (`;x`) BEFORE decoding and normalizing, so `..;a` is not the
    // literal string ".." when this filter sees it, but IS a ".." by the time the path is
    // normalized. A guard that only compared segments against ".." let this straight through.
    MockHttpServletResponse res =
        run("/internal/options-chat/..;a/..;b/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void pathParameterTraversal_isNotExemptViaActuatorEither() throws Exception {
    assertThat(run("/actuator/..;a/api/positions", null).getStatus()).isEqualTo(401);
  }

  @Test
  void doubledSlashes_cannotSlipTheSharedTokenIntoTheIngestRoute() throws Exception {
    // normalize() collapses `//`, so this reaches the ingest handler while failing the prefix test
    // — which would mean the SHARED token was accepted for an ingest write.
    MockHttpServletResponse res = run("/internal//options-chat/ingest", "Bearer " + TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void encodedSeparatorsAreRejected() throws Exception {
    for (String uri :
        new String[] {
          "/internal/options-chat/%2f%2e%2e/api/positions",
          "/internal/options-chat/%5c../api/positions",
          "/internal/options-chat/..%3ba/api/positions"
        }) {
      assertThat(run(uri, "Bearer " + INGEST_TOKEN).getStatus()).as(uri).isEqualTo(401);
    }
  }

  @Test
  void aTrailingSlashIsStillAllowed() throws Exception {
    // The empty-segment rule must not reject an ordinary trailing slash.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions/");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void literalDotSegments_areRejectedEvenWithAValidSharedToken() throws Exception {
    assertThat(run("/api/../api/positions", "Bearer " + TOKEN).getStatus()).isEqualTo(401);
    assertThat(run("/api/./positions", "Bearer " + TOKEN).getStatus()).isEqualTo(401);
  }

  @Test
  void encodedDotsAreRejectedInAnyCase() throws Exception {
    assertThat(run("/internal/options-chat/%2E%2E/api/positions", "Bearer " + INGEST_TOKEN))
        .extracting(MockHttpServletResponse::getStatus)
        .isEqualTo(401);
  }

  @Test
  void ordinaryPathsAreUnaffectedByTheGuard() throws Exception {
    // No route in this service takes a dot-bearing path segment, so the guard must be invisible to
    // every legitimate caller — including the tenant-scoped admin routes.
    MockHttpServletRequest req =
        new MockHttpServletRequest("DELETE", "/api/admin/tenants/acme-paper_1/dashboard-rows");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void ingestRoute_whenIngestTokenIsUnprovisioned_is401_evenWithAnEmptyBearer() throws Exception {
    // Fail-closed: a blank configured token must match NOTHING, not "any empty bearer".
    ServiceTokenFilter unprovisioned = new ServiceTokenFilter(TOKEN, "", new MockEnvironment());
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/internal/options-chat/ingest");
    req.addHeader("Authorization", "Bearer ");
    MockHttpServletResponse res = new MockHttpServletResponse();

    unprovisioned.doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus()).isEqualTo(401);
  }

  // The operator tenant-user-invite routes (Phase 2) are NOT actuator paths, so the always-on
  // filter bearer-gates them like every other route. This is the load-bearing auth invariant for a
  // tenant-access-GRANTING endpoint: it must NEVER be reachable without the shared token. These
  // tests fail closed if a future change adds either route to shouldNotFilter (which would exempt =
  // un-authenticate it).
  @Test
  void createInviteRoute_withoutBearer_is401() throws Exception {
    MockHttpServletResponse res = run("/api/admin/tenant-invites", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void bindRoute_withoutBearer_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/provisioning/bind", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  // The operator tenant-delete dashboard-rows route (Phase 3) is NOT an actuator path, so the
  // always-on filter bearer-gates it like every other route — a store-deleting endpoint must NEVER
  // be reachable without the shared token. Fails closed if a future change adds it to
  // shouldNotFilter (which would un-authenticate it).
  @Test
  void deleteDashboardRowsRoute_withoutBearer_is401() throws Exception {
    MockHttpServletResponse res = run("/api/admin/tenants/acme/dashboard-rows", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void bindRoute_wrongBearer_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/provisioning/bind", "Bearer not-the-token");
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void actuatorPrometheus_isExemptSoScrapesNeedNoToken() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/prometheus");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isSameAs(req); // exempt — a Prometheus scrape passes through
    assertThat(res.getStatus()).isEqualTo(200);
  }

  private MockHttpServletResponse run(String uri, String authHeader)
      throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
    if (authHeader != null) {
      req.addHeader("Authorization", authHeader);
    }
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    return res;
  }
}
