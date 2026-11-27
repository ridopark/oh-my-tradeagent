package com.ohmytradeagent.tdbff;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code /actuator/prometheus} is served without a token (the scraper has none), on the same port
 * as the API. Any pod allowed to reach that port can read it — including internet-facing ones (the
 * MCP gateway, the public demo's poller). The per-route request timer would tell such a pod, in
 * real time, when an operator force-closes or manually enters on a live account, so it is not
 * exported. Same hermetic full-context setup as {@link ApplicationContextSmokeTest}.
 */
@SpringBootTest(
    classes = TenantDashboardBffApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "DASHBOARD_READONLY_PASSWORD=test-not-used",
      "DASHBOARD_WRITER_PASSWORD=test-not-used"
    })
class MetricsExposureTest {

  @MockitoBean private WorkflowServiceStubs workflowServiceStubs;
  @MockitoBean private WorkflowClient workflowClient;
  @MockitoBean private StringRedisTemplate stringRedisTemplate;

  @Autowired private MeterRegistry registry;

  @Test
  void perRouteRequestTimers_areNeverRegistered() {
    // What Spring MVC's instrumentation does for every request.
    registry
        .timer("http.server.requests", "uri", "/api/positions/force-close", "method", "POST")
        .record(Duration.ofMillis(5));
    registry.more().longTaskTimer("http.server.requests.active", "uri", "/api/entries").start();

    assertThat(registry.find("http.server.requests").meters()).isEmpty();
    assertThat(registry.find("http.server.requests.active").meters()).isEmpty();
  }

  @Test
  void otherMeters_areStillExported() {
    // The deny rule is one name, not the registry: pool and JVM metrics keep working.
    registry.counter("hikaricp.connections.timeout", "pool", "test").increment();

    assertThat(registry.find("hikaricp.connections.timeout").meters()).isNotEmpty();
  }
}
