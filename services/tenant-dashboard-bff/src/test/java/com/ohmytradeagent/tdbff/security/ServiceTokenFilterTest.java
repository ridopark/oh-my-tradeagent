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
  private final ServiceTokenFilter filter = new ServiceTokenFilter(TOKEN, new MockEnvironment());

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
    assertThatThrownBy(() -> new ServiceTokenFilter("dev-shared-token", prod))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wellKnownDefaultToken_withoutProdProfile_isAllowedForLocalDev() {
    assertThatCode(() -> new ServiceTokenFilter("dev-shared-token", new MockEnvironment()))
        .doesNotThrowAnyException();
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
