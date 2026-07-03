package com.ohmytradeagent.exec.web;

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

class ExecAdminTokenFilterTest {

  private static final String TOKEN = "s3cr3t-admin-token";
  private final ExecAdminTokenFilter filter =
      new ExecAdminTokenFilter(TOKEN, new MockEnvironment());

  @Test
  void missingAuthorizationHeader_is401_andDoesNotInvokeChain() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse res = run("/internal/broker-credentials", null, chain);
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull(); // handler never reached
  }

  @Test
  void wrongToken_is401() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse res =
        run("/internal/broker-credentials", "Bearer not-the-token", chain);
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void nonBearerScheme_is401() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse res = run("/internal/broker-credentials", "Basic " + TOKEN, chain);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void correctBearerToken_passesThroughToHandler() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/broker-credentials");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req); // reached the (mock) downstream handler
  }

  @Test
  void accountReadSubPath_withoutToken_is401() throws Exception {
    // C3: the A1 read endpoint lives UNDER the credential route prefix, not at the exact base — it
    // must still be bearer-gated (an exact-equals gate would skip it → unauthenticated account
    // read).
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse res = run("/internal/broker-credentials/acme/account", null, chain);
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull(); // handler never reached
  }

  @Test
  void accountReadSubPath_withCorrectToken_passesThroughToHandler() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/internal/broker-credentials/acme/account");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void deleteMethod_withoutToken_is401() throws Exception {
    // The teardown route reuses the same base path; the filter is method-agnostic, so a DELETE
    // without a bearer token is rejected 401 before any handler runs (never exempted).
    MockHttpServletRequest req =
        new MockHttpServletRequest("DELETE", "/internal/broker-credentials");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void deleteMethod_withCorrectToken_passesThroughToHandler() throws Exception {
    MockHttpServletRequest req =
        new MockHttpServletRequest("DELETE", "/internal/broker-credentials");
    req.addHeader("Authorization", "Bearer " + TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  void nonCredentialRoute_isNotFiltered_evenWithoutToken() throws Exception {
    // Route-scoped: the worker / actuator / any other path must be untouched by this filter.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health/readiness");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isSameAs(req); // exempt → passed straight through
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @Test
  void prometheusScrape_isNotFiltered() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/prometheus");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isSameAs(req);
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @Test
  void wellKnownDefaultToken_underProdProfile_failsBoot() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    assertThatThrownBy(() -> new ExecAdminTokenFilter("dev-admin-token", prod))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wellKnownDefaultToken_withoutProdProfile_isAllowedForLocalDev() {
    assertThatCode(() -> new ExecAdminTokenFilter("dev-admin-token", new MockEnvironment()))
        .doesNotThrowAnyException();
  }

  private MockHttpServletResponse run(String uri, String authHeader, MockFilterChain chain)
      throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
    if (authHeader != null) {
      req.addHeader("Authorization", authHeader);
    }
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    return res;
  }
}
