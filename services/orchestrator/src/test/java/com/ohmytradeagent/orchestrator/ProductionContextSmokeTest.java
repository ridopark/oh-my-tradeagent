package com.ohmytradeagent.orchestrator;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Issue #131: coarse-grained smoke test that boots {@link OrchestratorApplication}'s full Spring
 * context under the <strong>production profile</strong> (no {@code @ActiveProfiles("test")}) so
 * every {@code @Profile("!test")}-gated bean — {@link
 * com.ohmytradeagent.orchestrator.activities.TenantConfigChangedEmitter}, {@link
 * com.ohmytradeagent.orchestrator.bootstrap.KillSwitchBootstrapper}, {@link
 * com.ohmytradeagent.orchestrator.bootstrap.ReconciliationScheduleBootstrapper}, {@link
 * com.ohmytradeagent.orchestrator.metrics.KillSwitchHistoryLengthGauge} — actually gets
 * instantiated by Spring during {@code mvn verify}.
 *
 * <p>This is the test that would have caught PR #123's {@code BeanInstantiationException} (two
 * constructors, no {@code @Autowired}, Spring 6 ambiguity) at PR-review time instead of at homelab
 * deploy time when the orchestrator pod crash-looped. Same bug shape as the {@code
 * FillListenerMetrics}/{@code FillPoller} hotfixes earlier in the project.
 *
 * <p>The test body is intentionally empty: {@code @SpringBootTest} failing to refresh the context
 * fails the test, which is exactly the regression surface we want to pin.
 *
 * <p><b>What we exclude vs. mock and why:</b>
 *
 * <ul>
 *   <li><b>Excluded auto-configs</b>: {@code DataSourceAutoConfiguration}, {@code
 *       JdbcRepositoriesAutoConfiguration}, {@code FlywayAutoConfiguration}, {@code
 *       JooqAutoConfiguration}, {@code RedisAutoConfiguration}, {@code
 *       RedisRepositoriesAutoConfiguration} — these would otherwise try to open a JDBC connection
 *       to Postgres or a TCP connection to Redis on every test run. {@code mvn verify} doesn't
 *       provision either. Replacement {@code DSLContext} and {@code StringRedisTemplate} mocks are
 *       supplied by {@link TemporalMockConfig} so the downstream {@code @Component} beans that
 *       inject them ({@code AuditActivitiesImpl}, {@code ContractActivitiesImpl}, {@code
 *       PositionLookupActivitiesImpl}) still wire cleanly.
 *   <li><b>Test-double overrides for {@link WorkflowServiceStubs}, {@link WorkflowClient}, {@link
 *       WorkerFactory}</b>: these beans are declared in {@code TemporalWorkerConfig} and the real
 *       implementations dial {@code temporal.target} ({@code localhost:7233} by default) during
 *       construction. The overrides in {@link TemporalMockConfig} share the same bean names so
 *       {@code spring.main.allow-bean-definition-overriding=true} replaces the production
 *       definitions. {@code workflowServiceStubs} stays a Mockito mock (nothing here needs it to be
 *       real), but {@code workflowClient} and {@code workerFactory} are backed by an in-memory
 *       {@link TestWorkflowEnvironment} — <b>not</b> mocked out — specifically so the production
 *       {@code worker(...)} {@code @Bean} (also not overridden — see Issue #578 below) runs for
 *       real against them.
 *   <li><b>Issue #578 — the production {@code worker(...)} {@code @Bean} is NOT mocked</b>: earlier
 *       revisions of this test replaced {@code Worker worker()} with a Mockito mock, which meant
 *       the real {@code registerWorkflowImplementationTypes(...)}/{@code
 *       registerActivitiesImplementations(...)} calls in {@code TemporalWorkerConfig.worker(...)}
 *       never executed in CI — so a duplicate activity/workflow type name (e.g. #577's {@code
 *       TenantConfigUpdateActivities.update()} vs. {@code StrategyConfigUpdateActivities.update()},
 *       both defaulting to Temporal type {@code "Update"}) was structurally undetectable here and
 *       only surfaced as a {@code TypeAlreadyRegisteredException} crash-loop at homelab boot. Now
 *       {@code worker(...)} runs unmocked, backed by the in-memory {@link
 *       TestWorkflowEnvironment}'s real {@link WorkerFactory}, so the real Temporal SDK
 *       registration — and its duplicate-type check — executes during this test's context refresh.
 *   <li><b>{@code orchestrator.tenants-dir}</b>: pointed at a non-existent path so {@code
 *       KillSwitchBootstrapper}, {@code ReconciliationScheduleBootstrapper}, and {@code
 *       TenantConfigChangedEmitter} all early-return on their {@code Files.exists(tenantsDir)}
 *       guard. The beans still register and get instantiated — which is the contract this test
 *       enforces — they just skip their Temporal-touching {@code run()} bodies.
 * </ul>
 *
 * <p><b>Halt condition</b>: if a production bean fails to instantiate even with these test doubles
 * (e.g. a circular dep or a new auto-config that pulls in an external service), <b>do not</b> add
 * {@code @ActiveProfiles("test")} — that would re-enable the {@code @Profile("!test")} exclusions
 * and defeat this test's entire purpose. Surface the failing bean as a separate bug.
 */
@SpringBootTest(
    classes = {OrchestratorApplication.class, ProductionContextSmokeTest.TemporalMockConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.web-application-type=none",
      // Required so the TemporalMockConfig @Bean methods below (which reuse the same names as
      // TemporalWorkerConfig's @Bean methods) can override the production definitions. Spring
      // disables override-by-name by default; we opt in for this test only.
      "spring.main.allow-bean-definition-overriding=true",
      // External I/O auto-configs that would otherwise dial Postgres / Redis at context-refresh.
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
      // Non-existent path: every @Profile("!test") bootstrapper hits its Files.exists() guard
      // and early-returns without touching Temporal.
      "orchestrator.tenants-dir=target/smoke-test-nonexistent-tenants-dir",
    })
class ProductionContextSmokeTest {

  /**
   * Replaces the real Temporal beans from {@code TemporalWorkerConfig} with test doubles so the
   * test doesn't dial {@code localhost:7233}. The {@code @Bean} method names ({@code
   * workflowServiceStubs}, {@code workflowClient}, {@code workerFactory}) match the production
   * names exactly, and {@code spring.main.allow-bean-definition-overriding=true} is set in
   * {@code @SpringBootTest.properties} so these definitions replace the originals from {@link
   * com.ohmytradeagent.orchestrator.config.TemporalWorkerConfig}. Name-matching is preferred over
   * {@code @Primary} because Spring's {@code @Configuration} CGLIB proxy invokes both {@code @Bean}
   * methods otherwise — and the production factory method itself dials Temporal during invocation,
   * defeating the mock.
   *
   * <p><b>Issue #578</b>: unlike {@code workflowServiceStubs}/{@code workflowClient}/{@code
   * workerFactory}, the production {@code worker(...)} {@code @Bean} from {@code
   * TemporalWorkerConfig} is deliberately <strong>not</strong> overridden here. It is left to run
   * for real, backed by the in-memory {@link TestWorkflowEnvironment}'s {@link WorkerFactory}
   * instead of a real Temporal connection, so its {@code registerWorkflowImplementationTypes(...)}
   * and {@code registerActivitiesImplementations(...)} calls execute against the real Temporal SDK
   * during context refresh. Two activity (or workflow) impls that collide on the same effective
   * type name on the {@code orchestrator-core} queue — e.g. #577's {@code
   * TenantConfigUpdateActivities.update()} vs {@code StrategyConfigUpdateActivities.update()}, both
   * defaulting to {@code "Update"} — now throw {@code TypeAlreadyRegisteredException} right here,
   * exactly like production boot, instead of escaping to a mocked no-op {@code Worker}.
   *
   * <p>The {@link WorkflowClient} comes from the same {@link TestWorkflowEnvironment} so {@link
   * com.ohmytradeagent.orchestrator.metrics.KillSwitchHistoryLengthGauge}'s scheduled {@code
   * poll()} method (which reads {@code workflowClient.getOptions().getNamespace()} on every tick)
   * gets a real, non-null answer instead of racing an NPE against context shutdown. The scheduler
   * is enabled (via {@code @EnableScheduling} on {@code OrchestratorApplication}) and may fire
   * during the brief context lifetime.
   */
  @TestConfiguration
  static class TemporalMockConfig {

    @Bean(destroyMethod = "close")
    TestWorkflowEnvironment temporalTestWorkflowEnvironment() {
      return TestWorkflowEnvironment.newInstance();
    }

    @Bean
    WorkflowServiceStubs workflowServiceStubs() {
      WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
      when(stubs.blockingStub())
          .thenReturn(mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class));
      return stubs;
    }

    @Bean
    WorkflowClient workflowClient(TestWorkflowEnvironment testEnv) {
      return testEnv.getWorkflowClient();
    }

    @Bean
    WorkerFactory workerFactory(TestWorkflowEnvironment testEnv) {
      return testEnv.getWorkerFactory();
    }

    /**
     * Replaces the {@link DSLContext} that would otherwise be wired by {@code
     * JooqAutoConfiguration} — that auto-config is excluded above because it depends on a real
     * {@link javax.sql.DataSource}, which we don't provision for the smoke test (no Postgres needed
     * for context-load verification). {@link
     * com.ohmytradeagent.orchestrator.activities.AuditActivitiesImpl}, {@link
     * com.ohmytradeagent.orchestrator.activities.DailyPnlActivitiesImpl}, and {@link
     * com.ohmytradeagent.orchestrator.activities.ContractActivitiesImpl} all inject {@code
     * DSLContext}.
     */
    @Bean
    DSLContext smokeDslContext() {
      return mock(DSLContext.class);
    }

    /**
     * Replaces the {@link StringRedisTemplate} that would otherwise be wired by {@code
     * RedisAutoConfiguration} (excluded above so the test doesn't try to dial Redis). {@link
     * com.ohmytradeagent.orchestrator.activities.PositionLookupActivitiesImpl} injects {@code
     * StringRedisTemplate}.
     */
    @Bean
    StringRedisTemplate smokeStringRedisTemplate() {
      return mock(StringRedisTemplate.class);
    }
  }

  @Test
  void contextLoads() {
    // Intentionally empty: the @SpringBootTest annotation does the work. If any
    // @Profile("!test")-gated bean fails to instantiate, context refresh throws and JUnit
    // reports the failure. That is exactly the regression surface this test pins.
    //
    // Issue #578: also covers the production TemporalWorkerConfig.worker(...) @Bean, which is NOT
    // mocked (see TemporalMockConfig javadoc above) — its real
    // registerWorkflowImplementationTypes(...)/registerActivitiesImplementations(...) calls run
    // during context refresh, so a duplicate activity/workflow type name on the orchestrator-core
    // queue throws TypeAlreadyRegisteredException right here.
  }
}
