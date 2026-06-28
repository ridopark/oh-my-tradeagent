package com.ohmytradeagent.tdbff;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the FULL bff Spring context offline so Spring bean-wiring breaks fail CI instead of
 * surfacing only at deploy. The {@code @WebMvcTest} slices the rest of the suite uses load only the
 * web layer and <em>mock</em> their collaborators, so a real wiring fault never gets exercised —
 * the gap that let a {@code PortfolioHistoryClient} multi-constructor/no-{@code @Autowired} bug
 * ship green and {@code CrashLoopBackOff} the pod on the homelab deploy (PR #486). If the context
 * loads here, every real {@code @Component} was constructable.
 *
 * <p>Hermetic — nothing reaches the network at context init:
 *
 * <ul>
 *   <li>Flyway (the only eager external connector) is disabled via {@code spring.flyway.enabled} —
 *       which also skips its no-default {@code DASHBOARD_READONLY_PASSWORD} placeholder; a dummy is
 *       set as belt-and-suspenders.
 *   <li>The Hikari datasources and jOOQ {@code DSLContext}s construct lazily (no connection until
 *       first query), so they need no live DB.
 *   <li>The Temporal {@code WorkflowServiceStubs}/{@code WorkflowClient} and the Redis {@code
 *       StringRedisTemplate} are mocked so neither dials out.
 * </ul>
 */
@SpringBootTest(
    classes = TenantDashboardBffApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.flyway.enabled=false", "DASHBOARD_READONLY_PASSWORD=test-not-used"})
class ApplicationContextSmokeTest {

  // Mocked so context initialization never dials Temporal — these override TemporalClientConfig's
  // @Bean definitions.
  @MockitoBean private WorkflowServiceStubs workflowServiceStubs;
  @MockitoBean private WorkflowClient workflowClient;
  // ProximityReader injects StringRedisTemplate; mocking it guarantees no Redis connection attempt.
  @MockitoBean private StringRedisTemplate stringRedisTemplate;

  @Test
  void contextLoads() {
    // Intentionally empty: any bean-wiring failure throws during the context initialization above,
    // failing this test. This is the regression guard for the whole "a bean cannot be constructed"
    // class — broader than the per-class reflection guard added alongside PR #486.
  }
}
