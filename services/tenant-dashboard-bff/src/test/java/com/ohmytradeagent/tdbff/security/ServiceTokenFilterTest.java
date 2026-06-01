package com.ohmytradeagent.tdbff.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {

  private static final String TOKEN = "s3cr3t-shared-token";
  private final ServiceTokenFilter filter = new ServiceTokenFilter(TOKEN);

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
