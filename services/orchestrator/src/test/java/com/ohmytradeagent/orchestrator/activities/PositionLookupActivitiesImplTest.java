package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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

  // sumRunningOwnerRemainingQtyForOcc: account-scoped (any-strategy) sibling-owner coverage probe.

  private static final String OCC = "NVDA  260706P00190000";

  @Test
  void sumRunningOwner_oneRunningOwner_returnsRemainingQty() {
    PositionLookupActivitiesImpl probe = spy(svc);
    String key = "pos:dev:copytrade-v1:" + OCC;
    Cursor<String> cursor = cursorOf(key);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(key)).thenReturn("wf-1");
    doReturn(true).when(probe).isPositionWorkflowRunning("wf-1");
    stubPositionState("wf-1", 50L);

    assertThat(probe.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(50L);
  }

  @Test
  void sumRunningOwner_cachedWfNotRunning_returnsZero() {
    PositionLookupActivitiesImpl probe = spy(svc);
    String key = "pos:dev:watchlist-trigger-v1:" + OCC;
    Cursor<String> cursor = cursorOf(key);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(key)).thenReturn("wf-stale");
    doReturn(false).when(probe).isPositionWorkflowRunning("wf-stale");

    assertThat(probe.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(0L);
  }

  @Test
  void sumRunningOwner_noMatchingKey_returnsZero() {
    Cursor<String> cursor = cursorOf();
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

    assertThat(svc.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(0L);
  }

  @Test
  void sumRunningOwner_twoRunningOwners_returnsSum() {
    PositionLookupActivitiesImpl probe = spy(svc);
    String key1 = "pos:dev:copytrade-v1:" + OCC;
    String key2 = "pos:dev:watchlist-trigger-v1:" + OCC;
    Cursor<String> cursor = cursorOf(key1, key2);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(key1)).thenReturn("wf-1");
    when(valueOps.get(key2)).thenReturn("wf-2");
    doReturn(true).when(probe).isPositionWorkflowRunning(anyString());
    stubPositionState("wf-1", 5L);
    stubPositionState("wf-2", 5L);

    assertThat(probe.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(10L);
  }

  @Test
  void sumRunningOwner_redisScanThrows_returnsZeroNoThrow() {
    when(redis.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("redis down"));

    assertThat(svc.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(0L);
  }

  @SuppressWarnings("unchecked")
  private static Cursor<String> cursorOf(String... keys) {
    Cursor<String> cursor = mock(Cursor.class);
    java.util.Iterator<String> backing = List.of(keys).iterator();
    when(cursor.hasNext()).thenAnswer(inv -> backing.hasNext());
    when(cursor.next()).thenAnswer(inv -> backing.next());
    return cursor;
  }

  private void stubPositionState(String wfId, long remainingQty) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(wfId)).thenReturn(stub);
    when(stub.query("positionState", PositionState.class))
        .thenReturn(new PositionState(OCC, remainingQty, new BigDecimal("0.84")));
  }
}
