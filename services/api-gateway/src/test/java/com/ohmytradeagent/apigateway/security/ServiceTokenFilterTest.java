package com.ohmytradeagent.apigateway.security;

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
  void missingAuthorizationHeaderOnRoute_is401_andDoesNotInvokeChain() throws Exception {
    MockHttpServletResponse res = run("/broker-credentials", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void wrongTokenOnRoute_is401() throws Exception {
    MockHttpServletResponse res = run("/broker-credentials", "Bearer not-the-token");
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void nonBearerSchemeOnRoute_is401() throws Exception {
    MockHttpServletResponse res = run("/broker-credentials", "Basic " + TOKEN);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void correctBearerTokenOnRoute_passesThrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/broker-credentials");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void missingAuthorizationHeaderOnAdminRoute_is401() throws Exception {
    // Phase F: the /admin/tenants/ activation route is bearer-gated too.
    MockHttpServletResponse res =
        run("/admin/tenants/dev/strategies/copytrade-v1/activate-live", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void wrongTokenOnAdminRoute_is401() throws Exception {
    MockHttpServletResponse res =
        run("/admin/tenants/dev/strategies/copytrade-v1/deactivate-live", "Bearer not-the-token");
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void correctBearerTokenOnAdminRoute_passesThrough() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest(
            "POST", "/admin/tenants/dev/strategies/copytrade-v1/activate-live");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void missingAuthorizationHeaderOnInternalFanoutRoute_is401() throws Exception {
    // Phase B1: the sidecar's registry-poll route is a service, bearer-gated too.
    MockHttpServletResponse res = run("/internal/copytrade-fanout-targets", null);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void wrongTokenOnInternalFanoutRoute_is401() throws Exception {
    MockHttpServletResponse res = run("/internal/copytrade-fanout-targets", "Bearer not-the-token");
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void correctBearerTokenOnInternalFanoutRoute_passesThrough() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/internal/copytrade-fanout-targets");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void otherRoutesAreNotFiltered_evenWithNoToken() throws Exception {
    // Route-scoping: /positions, /orders etc. must pass straight through (they keep their
    // existing header-trust behavior) regardless of the credential-route token. (Note: the
    // activation route is UNDER /admin/tenants/, so it is bearer-gated — not listed here.)
    for (String uri : new String[] {"/positions", "/orders", "/audit"}) {
      MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(req, res, chain);

      assertThat(chain.getRequest()).as("route %s must be exempt", uri).isSameAs(req);
      assertThat(res.getStatus()).isEqualTo(200);
    }
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

  private MockHttpServletResponse run(String uri, String authHeader)
      throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
    if (authHeader != null) {
      req.addHeader("Authorization", authHeader);
    }
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    return res;
  }
}
