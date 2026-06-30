package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Gated on {@code RUN_DB_ITS=true} — see {@link ContractActivitiesImplIT} for context. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class PositionLookupActivitiesImplIT {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private WorkflowClient workflowClient;
  private PositionLookupActivitiesImpl svc;

  @BeforeAll
  static void initFactory() {
    RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
    cfg.setHostName(redis.getHost());
    cfg.setPort(redis.getMappedPort(6379));
    connectionFactory = new LettuceConnectionFactory(cfg);
    connectionFactory.afterPropertiesSet();
  }

  @AfterAll
  static void destroyFactory() {
    if (connectionFactory != null) connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    // Wipe between tests.
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    workflowClient = mock(WorkflowClient.class);
    svc =
        new PositionLookupActivitiesImpl(
            redisTemplate,
            workflowClient,
            tenantId -> java.util.List.of(),
            // Phase F2b: the account-scoped probe needs a StrategyRegistry; these Redis-cache /
            // Visibility ITs don't exercise it, so an unstubbed mock (list() -> empty) suffices,
            // matching the sibling PositionLookupActivitiesImplTest's mock(StrategyRegistry.class).
            mock(com.ohmytradeagent.orchestrator.platform.StrategyRegistry.class));
  }

  @AfterEach
  void tearDown() {
    // No-op: connection factory shared.
  }

  @Test
  void cacheHit_returnsCachedWorkflowIdWithoutQueryingVisibility() {
    String key = PositionLookupActivitiesImpl.key("dev", "copytrade-v1", "NVDA  260516C00140000");
    redisTemplate.opsForValue().set(key, "wf-cached", Duration.ofSeconds(60));

    String result = svc.findPositionWorkflowId("dev", "copytrade-v1", "NVDA  260516C00140000");

    assertThat(result).isEqualTo("wf-cached");
    verifyNoInteractions(workflowClient);
  }

  @Test
  void cacheMiss_visibilityHit_returnsAndWritesBack() {
    WorkflowExecutionMetadata earlier = mock(WorkflowExecutionMetadata.class);
    WorkflowExecutionMetadata later = mock(WorkflowExecutionMetadata.class);
    when(earlier.getStartTime()).thenReturn(Instant.parse("2026-05-13T17:00:00Z"));
    when(later.getStartTime()).thenReturn(Instant.parse("2026-05-13T18:00:00Z"));
    when(earlier.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId("wf-earliest").build());
    when(later.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId("wf-later").build());
    when(workflowClient.listExecutions(anyString())).thenReturn(Stream.of(later, earlier));

    String result = svc.findPositionWorkflowId("dev", "copytrade-v1", "NVDA  260516C00140000");

    assertThat(result).isEqualTo("wf-earliest");
    String key = PositionLookupActivitiesImpl.key("dev", "copytrade-v1", "NVDA  260516C00140000");
    assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("wf-earliest");
    verify(workflowClient).listExecutions(anyString());
  }

  @Test
  void cacheMiss_visibilityEmpty_returnsNull() {
    when(workflowClient.listExecutions(anyString())).thenReturn(Stream.empty());

    String result = svc.findPositionWorkflowId("dev", "copytrade-v1", "NVDA  260516C00140000");

    assertThat(result).isNull();
    String key = PositionLookupActivitiesImpl.key("dev", "copytrade-v1", "NVDA  260516C00140000");
    assertThat(redisTemplate.opsForValue().get(key)).isNull();
  }

  @Test
  void cachePositionMapping_writesToRedis() {
    svc.cachePositionMapping("dev", "copytrade-v1", "NVDA  260516C00140000", "wf-new");

    String key = PositionLookupActivitiesImpl.key("dev", "copytrade-v1", "NVDA  260516C00140000");
    assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("wf-new");
  }
}
