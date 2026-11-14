package com.ohmytradeagent.tdbff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {

  private static final String TOKEN = "s3cr3t-shared-token";
  private static final String INGEST_TOKEN = "s3cr3t-options-chat-ingest";
  private final ServiceTokenFilter filter =
      new ServiceTokenFilter(TOKEN, INGEST_TOKEN, "", new MockEnvironment(), null);

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
    assertThatThrownBy(
            () -> new ServiceTokenFilter("dev-shared-token", INGEST_TOKEN, "", prod, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wellKnownDefaultToken_withoutProdProfile_isAllowedForLocalDev() {
    assertThatCode(
            () ->
                new ServiceTokenFilter(
                    "dev-shared-token", INGEST_TOKEN, "", new MockEnvironment(), null))
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

  // The lotto poller's GET /internal/options-chat/messages shares the prefix, and the prefix rule
  // is
  // method-agnostic: the ingest token opens it, the shared token does not — so the poller holds
  // only the ingest token and can never reach a tenant read.
  @Test
  void pollerReadRoute_GET_withTheIngestToken_passesThrough() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/internal/options-chat/messages");
    req.addHeader("Authorization", "Bearer " + INGEST_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void pollerReadRoute_GET_withTheSharedToken_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/options-chat/messages", "Bearer " + TOKEN);
    assertThat(res.getStatus())
        .as("holding BFF_SHARED_TOKEN must NOT open the poller's options-chat read")
        .isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // darkpool ingest: the SECOND prefix on the SAME ingest token. The collector pod is a sibling of
  // the chat-mirror pod (same image, same secret), so it holds the ingest token and must get
  // exactly
  // what that token already gives — never the shared token's tenant reads.
  // ---------------------------------------------------------------------------------------------

  @Test
  void darkpoolIngestRoute_withTheIngestToken_passesThrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/darkpool/ingest");
    req.addHeader("Authorization", "Bearer " + INGEST_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void darkpoolIngestRoute_withTheSharedToken_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/darkpool/ingest", "Bearer " + TOKEN);
    assertThat(res.getStatus())
        .as("holding BFF_SHARED_TOKEN must NOT open the darkpool ingest")
        .isEqualTo(401);
  }

  @Test
  void darkpoolIngestRoute_withoutBearer_is401() throws Exception {
    assertThat(run("/internal/darkpool/ingest", null).getStatus()).isEqualTo(401);
  }

  @Test
  void aPrefixThatMerelyStartsLikeAnIngestPrefix_isNotOne() throws Exception {
    // The match is on the full segment with its trailing slash: "/internal/darkpoolx/..." and
    // "/internal/darkpool" (no slash) are NOT ingest routes, so the ingest token must not open
    // them.
    assertThat(run("/internal/darkpoolx/ingest", "Bearer " + INGEST_TOKEN).getStatus())
        .isEqualTo(401);
    assertThat(run("/internal/darkpool", "Bearer " + INGEST_TOKEN).getStatus()).isEqualTo(401);
    assertThat(run("/internal/options-chatx/ingest", "Bearer " + INGEST_TOKEN).getStatus())
        .isEqualTo(401);
    assertThat(run("/internal/options-chat", "Bearer " + INGEST_TOKEN).getStatus()).isEqualTo(401);
  }

  @Test
  void darkpoolPrefixWithEncodedDots_cannotSmuggleTheIngestTokenIntoATenantRead() throws Exception {
    MockHttpServletResponse res =
        run("/internal/darkpool/%2e%2e/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void darkpoolPathParameterTraversal_cannotSmuggleTheIngestTokenIntoATenantRead()
      throws Exception {
    MockHttpServletResponse res =
        run("/internal/darkpool/..;a/..;b/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void doubledSlashes_cannotSlipTheSharedTokenIntoTheDarkpoolIngest() throws Exception {
    MockHttpServletResponse res = run("/internal//darkpool/ingest", "Bearer " + TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void darkpoolIngestRoute_whenIngestTokenIsUnprovisioned_is401() throws Exception {
    ServiceTokenFilter unprovisioned =
        new ServiceTokenFilter(TOKEN, "", "", new MockEnvironment(), null);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/darkpool/ingest");
    req.addHeader("Authorization", "Bearer ");
    MockHttpServletResponse res = new MockHttpServletResponse();

    unprovisioned.doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus()).isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // flow ingest: the THIRD prefix on the SAME ingest token. The flow-collector pod is a sibling of
  // the dark-pool and mirror pods (same image, same secret), so it holds the ingest token and must
  // get exactly what that token already gives — never the shared token's tenant reads.
  // ---------------------------------------------------------------------------------------------

  @Test
  void flowIngestRoute_withTheIngestToken_passesThrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/flow/ingest");
    req.addHeader("Authorization", "Bearer " + INGEST_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void flowIngestRoute_withTheSharedToken_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/flow/ingest", "Bearer " + TOKEN);
    assertThat(res.getStatus())
        .as("holding BFF_SHARED_TOKEN must NOT open the flow ingest")
        .isEqualTo(401);
  }

  @Test
  void flowIngestRoute_withoutBearer_is401() throws Exception {
    assertThat(run("/internal/flow/ingest", null).getStatus()).isEqualTo(401);
  }

  @Test
  void flowWatchlistRoute_withTheIngestToken_passesThrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/flow/watchlist");
    req.addHeader("Authorization", "Bearer " + INGEST_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void flowWatchlistRoute_withTheSharedToken_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/flow/watchlist", "Bearer " + TOKEN);
    assertThat(res.getStatus())
        .as("holding BFF_SHARED_TOKEN must NOT open the flow watchlist")
        .isEqualTo(401);
  }

  @Test
  void flowPrefixWithEncodedDots_cannotSmuggleTheIngestTokenIntoATenantRead() throws Exception {
    MockHttpServletResponse res =
        run("/internal/flow/%2e%2e/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void doubledSlashes_cannotSlipTheSharedTokenIntoTheFlowIngest() throws Exception {
    MockHttpServletResponse res = run("/internal//flow/ingest", "Bearer " + TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void flowPathParameterTraversal_cannotSmuggleTheIngestTokenIntoATenantRead() throws Exception {
    // Mirrors darkpoolPathParameterTraversal: `..;a` traverses after Tomcat strips path params.
    MockHttpServletResponse res =
        run("/internal/flow/..;a/..;b/api/positions", "Bearer " + INGEST_TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void flowIngestRoute_whenIngestTokenIsUnprovisioned_is401() throws Exception {
    // Mirrors darkpoolIngestRoute_whenIngestTokenIsUnprovisioned_is401.
    ServiceTokenFilter unprovisioned =
        new ServiceTokenFilter(TOKEN, "", "", new MockEnvironment(), null);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/flow/ingest");
    req.addHeader("Authorization", "Bearer ");
    MockHttpServletResponse res = new MockHttpServletResponse();

    unprovisioned.doFilter(req, res, new MockFilterChain());

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
    ServiceTokenFilter unprovisioned =
        new ServiceTokenFilter(TOKEN, "", "", new MockEnvironment(), null);
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

  // ---------------------------------------------------------------------------------------------
  // MCP read token tests (Step 1). The acme token hash is sha256("test-mcp-token-acme"),
  // the beta token hash is sha256("test-mcp-token-beta") — both computed outside the code under
  // test so the tests do not inherit a bug in the filter's own sha256 helper.
  // ---------------------------------------------------------------------------------------------

  private static final String MCP_ACME_TOKEN = "test-mcp-token-acme";
  private static final String MCP_ACME_HASH =
      "87121ec9ab39dfe5f0915c4b2b25f2592e80aebba9d1ee1a081caf28491478fc";
  private static final String MCP_ACME_TOKEN_ID = "87121ec9ab39";
  private static final String MCP_BETA_TOKEN = "test-mcp-token-beta";
  private static final String MCP_BETA_HASH =
      "bb705d371dc75cfce9616f51628261d27dd939fa509f59988902d3f591112556";

  private final ServiceTokenFilter mcpFilter =
      new ServiceTokenFilter(
          TOKEN, INGEST_TOKEN, MCP_ACME_HASH + ":acme", new MockEnvironment(), null);

  @Test
  void mcpToken_allowlistedGet_passesThrough_withBoundTenantInjected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
    assertThat(downstream.getHeader("X-Tenant-Id")).isEqualTo("acme");
    assertThat(Collections.list(downstream.getHeaders("X-Tenant-Id")))
        .hasSize(1)
        .containsExactly("acme");
    assertThat(Collections.list(downstream.getHeaderNames()))
        .anyMatch(n -> n.equalsIgnoreCase("X-Tenant-Id"));
  }

  @Test
  void mcpToken_headOnAllowlistedRoute_passesThrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("HEAD", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void mcpToken_everyAllowlistedPath_passesThrough() throws Exception {
    String[] allowlisted = {
      "/api/positions",
      "/api/portfolio",
      "/api/portfolio-history",
      "/api/live/marks",
      "/api/trades",
      "/api/orders",
      "/api/signals",
      "/api/trail-liveness",
      "/api/floor-breach",
      "/api/account-killswitch",
      "/api/entries/quote",
      "/api/entries/chain",
      "/api/entries/chain/expirations",
      "/api/flow/orders",
      "/api/flow/summary",
      "/api/darkpool/prints",
      "/api/darkpool/summary",
      "/api/mcp/whoami",
      "/api/notional-cap"
    };
    for (String path : allowlisted) {
      MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
      req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      mcpFilter.doFilter(req, res, chain);

      assertThat(res.getStatus()).as(path).isEqualTo(200);
      assertThat(chain.getRequest()).as(path).isNotNull();
    }
  }

  @Test
  void mcpToken_writeMethods_are403() throws Exception {
    // Non-GET/HEAD methods on write routes → 403; chain not invoked.
    String[][] cases = {
      {"POST", "/api/positions/force-close"},
      {"POST", "/api/positions/partial-close"},
      {"POST", "/api/positions/arm-trail"},
      {"POST", "/api/entries/manual"},
      {"POST", "/api/account-killswitch/reset"},
      {"PUT", "/api/positions/force-close"},
      {"DELETE", "/api/positions/force-close"},
      {"PATCH", "/api/positions/force-close"},
      // POST on an allowlisted path is still 403 because the method gate fires first
      {"POST", "/api/positions"}
    };
    for (String[] c : cases) {
      MockHttpServletRequest req = new MockHttpServletRequest(c[0], c[1]);
      req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      mcpFilter.doFilter(req, res, chain);

      assertThat(res.getStatus()).as(c[0] + " " + c[1]).isEqualTo(403);
      assertThat(res.getContentAsString())
          .as(c[0] + " " + c[1])
          .isEqualTo("{\"error\":\"forbidden\"}");
      assertThat(chain.getRequest()).as(c[0] + " " + c[1] + " chain not invoked").isNull();
    }
  }

  @Test
  void mcpToken_nonAllowlistedGet_is403() throws Exception {
    String[] paths = {
      "/api/admin/tenants",
      "/api/admin/tenant-invites",
      "/api/broker-credentials/status",
      "/api/tenant-config",
      "/api/strategy-config",
      "/api/options-chat/messages",
      "/api/positions/arm-anchors",
      "/api/entries/watchlist",
      "/api/live/connection",
      "/internal/provisioning/bind"
    };
    for (String path : paths) {
      MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
      req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      mcpFilter.doFilter(req, res, chain);

      assertThat(res.getStatus()).as(path).isEqualTo(403);
      assertThat(chain.getRequest()).as(path + " chain not invoked").isNull();
    }
  }

  @Test
  void mcpToken_trailingSlashOnAllowlistedPath_is403() throws Exception {
    // Exact match only: "/api/positions/" is not in the allowlist.
    // (aTrailingSlashIsStillAllowed pins that the shared token still passes "/api/positions/".)
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions/");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void mcpToken_forgedOtherTenantHeader_is403() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    req.addHeader("X-Tenant-Id", "beta");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void mcpToken_sameTenantHeader_isAllowed() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    req.addHeader("X-Tenant-Id", "acme");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
    assertThat(downstream.getHeader("X-Tenant-Id")).isEqualTo("acme");
  }

  @Test
  void mcpToken_absentTenantHeader_isAllowed() throws Exception {
    // No X-Tenant-Id on the incoming request; wrapper still injects the bound tenant.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
    assertThat(downstream.getHeader("X-Tenant-Id")).isEqualTo("acme");
  }

  @Test
  void mcpToken_operatorHeader_isStripped() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    req.addHeader("X-Operator-Id", "op@example.com");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
    assertThat(downstream.getHeader("X-Operator-Id")).isNull();
    assertThat(Collections.list(downstream.getHeaders("X-Operator-Id"))).isEmpty();
    assertThat(Collections.list(downstream.getHeaderNames()))
        .noneMatch(n -> n.equalsIgnoreCase("X-Operator-Id"));
  }

  @Test
  void mcpToken_setsTokenIdAttribute() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(chain.getRequest().getAttribute(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE))
        .isEqualTo(MCP_ACME_TOKEN_ID);
  }

  @Test
  void sharedToken_doesNotSetTokenIdAttribute_andLeavesHeadersAlone() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    req.addHeader("X-Tenant-Id", "beta");
    req.addHeader("X-Operator-Id", "op");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    // Use the mcpFilter (has MCP config) so there is no doubt the shared-token branch is taken.
    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    // Rule 4: shared token passes the ORIGINAL request object through unchanged.
    assertThat(chain.getRequest()).isSameAs(req);
    assertThat(req.getHeader("X-Tenant-Id")).isEqualTo("beta");
    assertThat(req.getHeader("X-Operator-Id")).isEqualTo("op");
    assertThat(req.getAttribute(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE)).isNull();
  }

  @Test
  void unknownToken_is401_withTheExistingBody() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer not-a-token");
    MockHttpServletResponse res = new MockHttpServletResponse();

    mcpFilter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"unauthorized\"}");
  }

  @Test
  void mcpTokens_emptyMap_is401ForAnMcpLookingToken() throws Exception {
    // Filter constructed with no MCP tokens — the acme token is unknown.
    ServiceTokenFilter emptyMcp =
        new ServiceTokenFilter(TOKEN, INGEST_TOKEN, "", new MockEnvironment(), null);
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();

    emptyMcp.doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void mcpToken_onIngestRoutes_is401() throws Exception {
    // Rule 3 (ingest prefix) runs before rule 5 (MCP); an MCP token on an ingest path is 401.
    String[][] cases = {
      {"POST", "/internal/options-chat/ingest"},
      {"GET", "/internal/options-chat/messages"},
      {"POST", "/internal/darkpool/ingest"},
      {"POST", "/internal/flow/ingest"},
      {"GET", "/internal/flow/watchlist"}
    };
    for (String[] c : cases) {
      MockHttpServletRequest req = new MockHttpServletRequest(c[0], c[1]);
      req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      mcpFilter.doFilter(req, res, chain);

      assertThat(res.getStatus()).as(c[0] + " " + c[1]).isEqualTo(401);
      assertThat(chain.getRequest()).as(c[0] + " " + c[1] + " chain not invoked").isNull();
    }
  }

  @Test
  void ingestToken_onAllowlistedTenantRead_isStill401() throws Exception {
    // The ingest token must NOT open tenant reads regardless of the MCP map being populated.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + INGEST_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();

    mcpFilter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus())
        .as("a leaked ingest token must NOT open tenant reads even with MCP map present")
        .isEqualTo(401);
  }

  @Test
  void sharedToken_onAllowlistedRoutes_stillPassesThrough() throws Exception {
    // The shared token's access is unaffected by the MCP map.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    mcpFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void mcpToken_encodedAndTraversalPaths_are401() throws Exception {
    // The firewall guard runs BEFORE any token decision; these all return 401 not 403.
    String[] paths = {
      "/api/%2e%2e/api/positions",
      "/api/positions/..;a",
      "/api//positions",
      "/internal/options-chat/%2e%2e/%2e%2e/api/positions"
    };
    for (String path : paths) {
      MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
      req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
      MockHttpServletResponse res = new MockHttpServletResponse();

      mcpFilter.doFilter(req, res, new MockFilterChain());

      assertThat(res.getStatus()).as(path).isEqualTo(401);
    }
  }

  @Test
  void secondConfiguredToken_isBoundToItsOwnTenant() throws Exception {
    ServiceTokenFilter twoTokenFilter =
        new ServiceTokenFilter(
            TOKEN,
            INGEST_TOKEN,
            MCP_ACME_HASH + ":acme," + MCP_BETA_HASH + ":beta",
            new MockEnvironment(),
            null);

    // Beta token reaches the chain as "beta".
    MockHttpServletRequest betaReq = new MockHttpServletRequest("GET", "/api/positions");
    betaReq.addHeader("Authorization", "Bearer " + MCP_BETA_TOKEN);
    MockHttpServletResponse betaRes = new MockHttpServletResponse();
    MockFilterChain betaChain = new MockFilterChain();
    twoTokenFilter.doFilter(betaReq, betaRes, betaChain);
    assertThat(betaRes.getStatus()).isEqualTo(200);
    assertThat(((HttpServletRequest) betaChain.getRequest()).getHeader("X-Tenant-Id"))
        .isEqualTo("beta");

    // Beta token with X-Tenant-Id: acme → 403 (forged cross-tenant header).
    MockHttpServletRequest forgedReq = new MockHttpServletRequest("GET", "/api/positions");
    forgedReq.addHeader("Authorization", "Bearer " + MCP_BETA_TOKEN);
    forgedReq.addHeader("X-Tenant-Id", "acme");
    MockHttpServletResponse forgedRes = new MockHttpServletResponse();
    MockFilterChain forgedChain = new MockFilterChain();
    twoTokenFilter.doFilter(forgedReq, forgedRes, forgedChain);
    assertThat(forgedRes.getStatus()).isEqualTo(403);
    assertThat(forgedChain.getRequest()).isNull();
  }

  @Test
  void malformedMcpTokenEntry_failsBoot() {
    // Each of these must throw unconditionally (regardless of profile).
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    String[] malformed = {
      "nothex:acme",
      MCP_ACME_HASH, // no colon + tenant
      MCP_ACME_HASH + ":bad tenant!", // tenant has space and !
      MCP_ACME_HASH.substring(1) + ":acme" // only 63 hex chars
    };
    for (String bad : malformed) {
      assertThatThrownBy(
              () -> new ServiceTokenFilter(TOKEN, INGEST_TOKEN, bad, prod, null),
              "expected IllegalStateException for entry: " + bad)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void malformedMcpTokenEntry_failsBoot_withoutProdProfileToo() {
    // Records that the boot check is unconditional (not prod-only).
    assertThatThrownBy(
            () ->
                new ServiceTokenFilter(
                    TOKEN, INGEST_TOKEN, "nothex:acme", new MockEnvironment(), null))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Delegated assertion credential class (Step 1, Seam A).
  // The jOOQ MockDataProvider-backed DashboardMembershipReader returns one row (member), zero rows
  // (not member), or throws — without touching a real database. The DelegatedAssertionAuthenticator
  // is constructed directly (not via Spring) using
  // DelegatedAssertionTestKeys.PRIMARY_PUBLIC_KEY_B64.
  // ---------------------------------------------------------------------------------------------

  // sha256("google:sub-1") first 12 hex — computed independently with sha256sum.
  private static final String DLG_CREDENTIAL_ID = "dlg-daf0e67a0269";
  private static final String DLG_SUB = "sub-1";
  private static final String DLG_TENANT = "acme";

  private static DashboardMembershipReader memberReader() {
    return new DashboardMembershipReader(mockDslReturning(1));
  }

  private static DashboardMembershipReader nonMemberReader() {
    return new DashboardMembershipReader(mockDslReturning(0));
  }

  private static DashboardMembershipReader throwingReader() {
    return new DashboardMembershipReader(
        DSL.using(
            new MockConnection(
                ctx -> {
                  throw new SQLException("simulated DB failure");
                }),
            SQLDialect.POSTGRES));
  }

  private static DSLContext mockDslReturning(int rows) {
    return DSL.using(
        new MockConnection(
            ctx -> {
              DSLContext helper = DSL.using(SQLDialect.POSTGRES);
              Result<Record> result = helper.newResult();
              for (int i = 0; i < rows; i++) {
                result.add(helper.newRecord());
              }
              return new MockResult[] {new MockResult(rows, result)};
            }),
        SQLDialect.POSTGRES);
  }

  private static DelegatedAssertionAuthenticator authenticator(DashboardMembershipReader reader) {
    return new DelegatedAssertionAuthenticator(
        DelegatedAssertionTestKeys.PRIMARY_PUBLIC_KEY_B64, reader);
  }

  private static ServiceTokenFilter filterWithDelegated(DashboardMembershipReader reader) {
    return new ServiceTokenFilter(
        TOKEN, INGEST_TOKEN, "", new MockEnvironment(), authenticator(reader));
  }

  @Test
  void delegated_valid_allowlistedGet_passesAsBoundTenant() throws Exception {
    // Reader → member. Valid assertion for (google, sub-1, acme) on an allowlisted GET.
    // Downstream sees X-Tenant-Id=acme, no X-Operator-Id, attribute = dlg-<12hex of sha256>.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + assertion);
    req.addHeader("X-Operator-Id", "op@example.com");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filterWithDelegated(memberReader()).doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
    assertThat(downstream.getHeader("X-Tenant-Id")).isEqualTo(DLG_TENANT);
    assertThat(downstream.getHeader("X-Operator-Id")).isNull();
    assertThat(downstream.getAttribute(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE))
        .isEqualTo(DLG_CREDENTIAL_ID);
  }

  @Test
  void delegated_membershipAbsent_is403() throws Exception {
    // Reader → not member. Chain not invoked.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filterWithDelegated(nonMemberReader())
        .doFilter(
            new MockHttpServletRequest("GET", "/api/positions") {
              {
                addHeader("Authorization", "Bearer " + assertion);
              }
            },
            res,
            chain);

    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void delegated_readerThrows_is403() throws Exception {
    // Reader throws (DB error) → fail-closed → NOT_MEMBER → 403.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filterWithDelegated(throwingReader())
        .doFilter(
            new MockHttpServletRequest("GET", "/api/positions") {
              {
                addHeader("Authorization", "Bearer " + assertion);
              }
            },
            res,
            chain);

    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void delegated_expired_is401() throws Exception {
    // exp <= now → INVALID → 401.
    long now = Instant.now().getEpochSecond();
    Map<String, Object> expiredClaims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    expiredClaims.put("exp", now - 1);
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), expiredClaims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_iatInFuture_is401() throws Exception {
    // iat > now + 30 → INVALID → 401.
    long now = Instant.now().getEpochSecond();
    Map<String, Object> futureClaims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    futureClaims.put("iat", now + 60);
    futureClaims.put("exp", now + 120);
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), futureClaims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_lifetimeOver300s_is401() throws Exception {
    // exp - iat > 300 → INVALID → 401.
    long now = Instant.now().getEpochSecond();
    Map<String, Object> longLifetimeClaims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    longLifetimeClaims.put("iat", now - 10);
    longLifetimeClaims.put("exp", now + 295); // 305s lifetime
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(), longLifetimeClaims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_wrongAudience_is401() throws Exception {
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("aud", "other-service");
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_wrongIssuer_is401() throws Exception {
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("iss", "malicious-gateway");
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_wrongProvider_is401() throws Exception {
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("provider", "github");
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_badTenantCharset_is401() throws Exception {
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("tenant", "tenant with spaces!");
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_wrongKey_is401() throws Exception {
    // Signed with the secondary private key; the authenticator has the primary public key.
    String assertion =
        DelegatedAssertionTestKeys.buildWithSecondaryKey(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_tamperedPayload_is401() throws Exception {
    // Signature is valid over the original payload; we replace it with a different tenant.
    Map<String, Object> legitimate = DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT);
    Map<String, Object> replacement = new HashMap<>(legitimate);
    replacement.put("tenant", "beta");
    String tampered =
        DelegatedAssertionTestKeys.buildTamperedPayload(
            DelegatedAssertionTestKeys.validHeader(), legitimate, replacement);

    assertThat(runWithDelegated(tampered, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_algNone_is401() throws Exception {
    // alg: "none" → INVALID (alg pin guard).
    Map<String, Object> header = new HashMap<>(DelegatedAssertionTestKeys.validHeader());
    header.put("alg", "none");
    String assertion =
        DelegatedAssertionTestKeys.build(
            header, DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_algHS256_is401() throws Exception {
    // alg: "HS256" (classic confusion attack header) → INVALID.
    Map<String, Object> header = new HashMap<>(DelegatedAssertionTestKeys.validHeader());
    header.put("alg", "HS256");
    String assertion =
        DelegatedAssertionTestKeys.build(
            header, DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_algRS256Header_is401() throws Exception {
    // alg: "RS256" → INVALID.
    Map<String, Object> header = new HashMap<>(DelegatedAssertionTestKeys.validHeader());
    header.put("alg", "RS256");
    String assertion =
        DelegatedAssertionTestKeys.build(
            header, DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_writeMethods_are403() throws Exception {
    // Non-GET/HEAD methods → 403 (method gate in applyMcpReadRules).
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    ServiceTokenFilter f = filterWithDelegated(memberReader());

    for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
      MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/positions");
      req.addHeader("Authorization", "Bearer " + assertion);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      f.doFilter(req, res, chain);

      assertThat(res.getStatus()).as(method).isEqualTo(403);
      assertThat(chain.getRequest()).as(method + " chain not invoked").isNull();
    }
  }

  @Test
  void delegated_nonAllowlistedGet_is403() throws Exception {
    // Allowlisted by shared token but NOT in MCP_ALLOWLIST → 403.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    assertThat(runWithDelegated(assertion, "/api/admin/tenants", "GET").getStatus()).isEqualTo(403);
  }

  @Test
  void delegated_forgedOtherTenantHeader_is403() throws Exception {
    // Assertion says tenant=acme; request carries X-Tenant-Id: beta → 403.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + assertion);
    req.addHeader("X-Tenant-Id", "beta");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filterWithDelegated(memberReader()).doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus()).isEqualTo(403);
  }

  @Test
  void delegated_sameTenantHeader_isAllowed() throws Exception {
    // Assertion says tenant=acme; request carries X-Tenant-Id: acme → allowed.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + assertion);
    req.addHeader("X-Tenant-Id", DLG_TENANT);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filterWithDelegated(memberReader()).doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void delegated_onIngestRoutes_is401() throws Exception {
    // Rule 3 (ingest prefix) runs before rule 5b: a delegated assertion on an ingest path → 401.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    ServiceTokenFilter f = filterWithDelegated(memberReader());

    for (String[] c :
        new String[][] {
          {"POST", "/internal/options-chat/ingest"},
          {"GET", "/internal/options-chat/messages"},
          {"POST", "/internal/darkpool/ingest"},
          {"POST", "/internal/flow/ingest"},
          {"GET", "/internal/flow/watchlist"}
        }) {
      MockHttpServletRequest req = new MockHttpServletRequest(c[0], c[1]);
      req.addHeader("Authorization", "Bearer " + assertion);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      f.doFilter(req, res, chain);

      assertThat(res.getStatus()).as(c[0] + " " + c[1]).isEqualTo(401);
      assertThat(chain.getRequest()).as(c[0] + " " + c[1] + " chain not invoked").isNull();
    }
  }

  @Test
  void delegated_encodedPaths_are401() throws Exception {
    // Rule 1 (suspicious path) runs before rule 5b.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    ServiceTokenFilter f = filterWithDelegated(memberReader());

    for (String path :
        new String[] {"/api/%2e%2e/api/positions", "/api/positions/..;a", "/api//positions"}) {
      MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
      req.addHeader("Authorization", "Bearer " + assertion);
      MockHttpServletResponse res = new MockHttpServletResponse();

      f.doFilter(req, res, new MockFilterChain());

      assertThat(res.getStatus()).as(path).isEqualTo(401);
    }
  }

  @Test
  void delegated_disabled_is401() throws Exception {
    // null authenticator → rule 5b skipped; a valid assertion is an unknown token → 401.
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    // Filter built with null authenticator (the default; bff.mcp-delegated.enabled=false).
    ServiceTokenFilter disabledFilter =
        new ServiceTokenFilter(TOKEN, INGEST_TOKEN, "", new MockEnvironment(), null);
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + assertion);
    MockHttpServletResponse res = new MockHttpServletResponse();

    disabledFilter.doFilter(req, res, new MockFilterChain());

    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"unauthorized\"}");
  }

  @Test
  void staticMcpToken_stillWorks_whenDelegatedEnabled() throws Exception {
    // Static MCP token is unaffected by the delegated authenticator.
    ServiceTokenFilter f =
        new ServiceTokenFilter(
            TOKEN,
            INGEST_TOKEN,
            MCP_ACME_HASH + ":acme",
            new MockEnvironment(),
            authenticator(memberReader()));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    f.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(((HttpServletRequest) chain.getRequest()).getHeader("X-Tenant-Id"))
        .isEqualTo("acme");
    assertThat(chain.getRequest().getAttribute(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE))
        .isEqualTo(MCP_ACME_TOKEN_ID);
  }

  @Test
  void sharedToken_unchanged_whenDelegatedEnabled() throws Exception {
    // Shared token is unaffected by the delegated authenticator.
    ServiceTokenFilter f =
        new ServiceTokenFilter(
            TOKEN, INGEST_TOKEN, "", new MockEnvironment(), authenticator(memberReader()));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    req.addHeader("X-Tenant-Id", "acme");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    f.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req); // shared token passes original request unchanged
  }

  @Test
  void authenticator_enabledWithEmptyKey_failsBoot() {
    assertThatThrownBy(() -> new DelegatedAssertionAuthenticator("", memberReader()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void authenticator_malformedKey_failsBoot() {
    // "YWJj" decodes to "abc" — valid base64 but not a valid Ed25519 SPKI DER.
    assertThatThrownBy(() -> new DelegatedAssertionAuthenticator("YWJj", memberReader()))
        .isInstanceOf(IllegalStateException.class);
  }

  // Run helper for delegated tests: builds a filter with a member reader, runs a request.
  private MockHttpServletResponse runWithDelegated(String assertion, String path, String method)
      throws ServletException, IOException {
    ServiceTokenFilter f = filterWithDelegated(memberReader());
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    req.addHeader("Authorization", "Bearer " + assertion);
    MockHttpServletResponse res = new MockHttpServletResponse();
    f.doFilter(req, res, new MockFilterChain());
    return res;
  }

  @Test
  void malformedMcpTokenEntry_errorMessage_hasPositionNotValue() {
    // The error message must NOT echo the raw entry value back into the pod log (a mis-pasted
    // token would appear there). It identifies the entry by its 1-based position only.
    assertThatThrownBy(
            () ->
                new ServiceTokenFilter(
                    TOKEN, INGEST_TOKEN, "rawsecrettoken123:acme", new MockEnvironment(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("position")
        .hasMessageNotContaining("rawsecrettoken123");
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 2 — null/non-object JSON in header or payload must NOT escape as 500.
  // ---------------------------------------------------------------------------------------------

  @Test
  void delegated_nullHeaderJson_is401() throws Exception {
    // header segment decodes to JSON null — with the original code decodeJsonObject returns null
    // and header.get("alg") throws NPE, propagating out of doFilter (500 in production).
    // After the fix decodeJsonObject throws → NOT_AN_ASSERTION → 401.
    String nullHeaderB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("null".getBytes(StandardCharsets.UTF_8));
    String validPayloadB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"iss\":\"x\"}".getBytes(StandardCharsets.UTF_8));
    String token = nullHeaderB64 + "." + validPayloadB64 + ".AAAA";
    // RED: NullPointerException propagates; GREEN: 401.
    assertThat(runWithDelegated(token, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_nullPayloadJson_is401() throws Exception {
    // payload segment decodes to JSON null.  With a dummy sig the sig check catches it first
    // (INVALID → 401); the fix makes it NOT_AN_ASSERTION → 401 — either way never 500.
    String validHeaderB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"EdDSA\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String nullPayloadB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("null".getBytes(StandardCharsets.UTF_8));
    String token = validHeaderB64 + "." + nullPayloadB64 + ".AAAA";
    assertThat(runWithDelegated(token, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_arrayHeaderJson_is401() throws Exception {
    // header segment decodes to a JSON array — not an object → NOT_AN_ASSERTION → 401.
    String arrayHeaderB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("[1,2,3]".getBytes(StandardCharsets.UTF_8));
    String validPayloadB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"iss\":\"x\"}".getBytes(StandardCharsets.UTF_8));
    String token = arrayHeaderB64 + "." + validPayloadB64 + ".AAAA";
    assertThat(runWithDelegated(token, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 3 — lifetime cap: iat > exp is accepted (negative diff passes > 300 check), and
  // extreme iat/exp values overflow long, making exp-iat appear small/negative.
  // ---------------------------------------------------------------------------------------------

  @Test
  void delegated_iatAfterExp_is401() throws Exception {
    // iat is slightly in future (within 30 s tolerance, so iat-too-far check passes), exp is
    // also in future but earlier than iat — a logically impossible token.
    // Current code: exp-iat = -5 > 300 is false → token accepted (200). Fixed: 401.
    long now = Instant.now().getEpochSecond();
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("iat", now + 10); // slightly in future, within tolerance
    claims.put("exp", now + 5); // in future but < iat
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);
    // RED: 200 (accepted); GREEN: 401.
    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void delegated_extremeIatExp_is401() throws Exception {
    // iat = -9e18, exp = 9e18: exp > now (not expired), iat < now+30 (not future-iat),
    // but exp - iat overflows → wraps to negative → current check -N > 300 is false → accepted.
    // Math.subtractExact must throw ArithmeticException → INVALID → 401.
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("iat", -9_000_000_000_000_000_000L);
    claims.put("exp", 9_000_000_000_000_000_000L);
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);
    // RED: 200 (overflow bypasses the check); GREEN: 401.
    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 4 — client_id charset: a newline can inject a header into the log line.
  // ---------------------------------------------------------------------------------------------

  @Test
  void delegated_newlineInClientId_is401() throws Exception {
    // A newline in client_id would let log-injection fabricate additional log fields.
    // Current code has no charset guard → token accepted (200). Fixed: 401.
    Map<String, Object> claims =
        new HashMap<>(DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));
    claims.put("client_id", "valid-client\nX-Injected: spoofed-header");
    String assertion =
        DelegatedAssertionTestKeys.build(DelegatedAssertionTestKeys.validHeader(), claims);
    // RED: 200; GREEN: 401.
    assertThat(runWithDelegated(assertion, "/api/positions", "GET").getStatus()).isEqualTo(401);
  }

  @Test
  void wellFormedMcpTokenMap_withTrailingCommaAndSpaces_boots() {
    assertThatCode(
            () ->
                new ServiceTokenFilter(
                    TOKEN,
                    INGEST_TOKEN,
                    " " + MCP_ACME_HASH + ":acme , ",
                    new MockEnvironment(),
                    null))
        .doesNotThrowAnyException();
  }

  @Test
  void uppercaseHexInConfig_matchesTheSameToken() throws Exception {
    ServiceTokenFilter upperFilter =
        new ServiceTokenFilter(
            TOKEN,
            INGEST_TOKEN,
            MCP_ACME_HASH.toUpperCase(Locale.ROOT) + ":acme",
            new MockEnvironment(),
            null);
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/positions");
    req.addHeader("Authorization", "Bearer " + MCP_ACME_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    upperFilter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(((HttpServletRequest) chain.getRequest()).getHeader("X-Tenant-Id"))
        .isEqualTo("acme");
  }
}
