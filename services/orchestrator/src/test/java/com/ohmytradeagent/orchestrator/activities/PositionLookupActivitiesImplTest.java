package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Pure-mock unit coverage for {@link PositionLookupActivitiesImpl#cacheArmedLeg} (no Redis
 * container; the IT covers real round-trips). Pins the best-effort swallow that the
 * WatchlistTriggerWorkflow's arm relies on: a Redis failure here must NOT propagate, so the
 * activity always returns normally and can never fail/stall arming.
 */
class PositionLookupActivitiesImplTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final WorkflowClient workflowClient = mock(WorkflowClient.class);
  private final PositionLookupActivitiesImpl svc =
      new PositionLookupActivitiesImpl(redis, workflowClient);

  @Test
  void cacheArmedLeg_seedsSetAndSetsTwoDayTtl() {
    @SuppressWarnings("unchecked")
    SetOperations<String, String> setOps = mock(SetOperations.class);
    when(redis.opsForSet()).thenReturn(setOps);

    svc.cacheArmedLeg("dev", "watchlist-trigger-v1", LocalDate.of(2026, 6, 24), "wf-1");

    String key =
        WorkflowIds.armedWatchlistCacheKey(
            "dev", "watchlist-trigger-v1", LocalDate.of(2026, 6, 24));
    verify(setOps).add(key, "wf-1");
    verify(redis).expire(eq(key), eq(PositionLookupActivitiesImpl.ARMED_CACHE_TTL));
  }

  @Test
  void cacheArmedLeg_redisThrows_swallowed() {
    when(redis.opsForSet()).thenThrow(new RuntimeException("redis down"));

    // Best-effort: the throw must NOT escape — arming can never be failed/stalled by a Redis
    // outage.
    assertThatCode(
            () ->
                svc.cacheArmedLeg("dev", "watchlist-trigger-v1", LocalDate.of(2026, 6, 24), "wf-1"))
        .doesNotThrowAnyException();
  }
}
