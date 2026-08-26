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
import java.time.OffsetDateTime;
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
  // Phase 3: the cross-strategy Visibility fallback resolves the tenant's strategies via this
  // resolver (the #323 idiom). The single-tenant fixture returns the two homelab strategies.
  private final TenantStrategies tenantStrategies =
      tenantId -> List.of("copytrade-v1", "watchlist-trigger-v1");
  // Phase F2b: the account-scoped probe resolves accounts via the StrategyRegistry; these
  // cacheArmedLeg tests don't exercise it, so an empty mock suffices.
  private final com.ohmytradeagent.orchestrator.platform.StrategyRegistry strategyRegistry =
      mock(com.ohmytradeagent.orchestrator.platform.StrategyRegistry.class);
  private final PositionLookupActivitiesImpl svc =
      new PositionLookupActivitiesImpl(redis, workflowClient, tenantStrategies, strategyRegistry);

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

  /**
   * #829 — the live incident verbatim: the single-slot pos:* key holds only the manual sibling
   * (remaining 5) because it evicted the corrected copytrade lot's mapping at spawn; Visibility
   * still enumerates BOTH owners. The sum must be 26 (21 + 5), not the cache-derived 5 that was one
   * recon sweep from a false "uncovered 21" partial-coverage page.
   */
  @Test
  void sumRunningOwner_cacheEvictedSibling_visibilityUnionStillSumsBoth() {
    PositionLookupActivitiesImpl probe = spy(svc);
    String key = "pos:dev:copytrade-v1:" + OCC;
    Cursor<String> cursor = cursorOf(key);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(key)).thenReturn("wf-manual"); // the evictor won the slot
    // metas built BEFORE the outer when() — metaAt stubs internally and nesting them inside
    // another stubbing trips Mockito's unfinished-stubbing detection.
    io.temporal.client.WorkflowExecutionMetadata manualMeta =
        metaAt("wf-manual", "2026-08-25T18:09:00Z");
    io.temporal.client.WorkflowExecutionMetadata copytradeMeta =
        metaAt("wf-copytrade", "2026-08-25T17:58:00Z");
    String q = PositionLookupActivitiesImpl.visibilityQuery("dev", "copytrade-v1", OCC);
    when(workflowClient.listExecutions(q))
        .thenReturn(java.util.stream.Stream.of(manualMeta, copytradeMeta));
    doReturn(true).when(probe).isPositionWorkflowRunning("wf-manual");
    doReturn(true).when(probe).isPositionWorkflowRunning("wf-copytrade");
    stubPositionState("wf-manual", 5L);
    stubPositionState("wf-copytrade", 21L);

    assertThat(probe.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(26L);
  }

  /**
   * #829 degrade path — the core safety claim pinned: a Visibility outage on the union loop must
   * leave the cache-derived owner still summed (an under-count pages; it never throws and never
   * zeroes a cache-confirmed owner).
   */
  @Test
  void sumRunningOwner_visibilityThrows_degradesToCacheDerivedSum() {
    PositionLookupActivitiesImpl probe = spy(svc);
    String key = "pos:dev:copytrade-v1:" + OCC;
    Cursor<String> cursor = cursorOf(key);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(key)).thenReturn("wf-cached");
    when(workflowClient.listExecutions(anyString()))
        .thenThrow(new RuntimeException("visibility down"));
    doReturn(true).when(probe).isPositionWorkflowRunning("wf-cached");
    stubPositionState("wf-cached", 7L);

    assertThat(probe.sumRunningOwnerRemainingQtyForOcc("dev", OCC)).isEqualTo(7L);
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

  // hasRunningOwnerForOcc: Phase 3 (2026-06-24) tenant-wide Visibility fallback. Enumerates the
  // tenant's strategies and runs ONE per-strategy `TenantStrategy='...'` EQUALITY query (the #323
  // idiom — Temporal SQL Visibility supports neither STARTS_WITH nor IN).

  @Test
  void hasRunningOwner_siblingStrategyHasRunningOwner_returnsTrue() {
    // The owner lives under the SECOND strategy; the first strategy's query is empty.
    String firstQuery = PositionLookupActivitiesImpl.visibilityQuery("dev", "copytrade-v1", OCC);
    String secondQuery =
        PositionLookupActivitiesImpl.visibilityQuery("dev", "watchlist-trigger-v1", OCC);
    when(workflowClient.listExecutions(firstQuery)).thenReturn(java.util.stream.Stream.empty());
    when(workflowClient.listExecutions(secondQuery))
        .thenReturn(
            java.util.stream.Stream.of(mock(io.temporal.client.WorkflowExecutionMetadata.class)));

    assertThat(svc.hasRunningOwnerForOcc("dev", OCC)).isTrue();
  }

  @Test
  void hasRunningOwner_noStrategyHasRunningOwner_returnsFalse() {
    when(workflowClient.listExecutions(anyString())).thenReturn(java.util.stream.Stream.empty());

    assertThat(svc.hasRunningOwnerForOcc("dev", OCC)).isFalse();
  }

  @Test
  void hasRunningOwner_visibilityThrows_returnsFalseNoThrow() {
    when(workflowClient.listExecutions(anyString()))
        .thenThrow(new RuntimeException("visibility down"));

    // Best-effort: any error returns false (recon pages — the safe degrade, never masks an orphan).
    assertThat(svc.hasRunningOwnerForOcc("dev", OCC)).isFalse();
  }

  @Test
  void hasRunningOwner_usesPerStrategyEqualityQueryNotPrefix() {
    // Guard the #323 idiom: the query must be a per-strategy TenantStrategy EQUALITY query keyed on
    // ContractSymbol — NOT a STARTS_WITH prefix (unsupported by Temporal SQL Visibility) and NOT a
    // WorkflowId predicate.
    String q = PositionLookupActivitiesImpl.visibilityQuery("dev", "copytrade-v1", OCC);
    assertThat(q)
        .contains("TenantStrategy = 't-dev/s-copytrade-v1'")
        .contains("ContractSymbol = '" + OCC + "'")
        .contains("ExecutionStatus = 'WORKFLOW_EXECUTION_STATUS_RUNNING'")
        .contains("WorkflowType = 'PositionWorkflow'")
        .doesNotContain("STARTS_WITH")
        .doesNotContain("WorkflowId");
  }

  // findOpenPositionByUnderlyingStrikeRight (F1 edited-signal supersede): enumerate RUNNING
  // PositionWorkflows for (tenant, strategy) keyed on TenantStrategy ONLY (no ContractSymbol
  // predicate — the match is expiry-agnostic and ContractSymbol is equality-only), filter
  // in-process
  // by underlying+strike+right with a DIFFERENT expiry.

  private static final String SPY_0706 = "SPY   260706P00710000"; // SPY 7/06 710P (prior wrong leg)
  private static final String SPY_0708 = "SPY   260708P00710000"; // SPY 7/08 710P (corrected leg)

  @Test
  void findOpenPosition_differentExpirySameStrikeRight_returnsCandidate() {
    String query = PositionLookupActivitiesImpl.tenantStrategyRunningQuery("dev", "copytrade-v1");
    io.temporal.client.WorkflowExecutionMetadata m = meta("wf-0706");
    when(workflowClient.listExecutions(query)).thenReturn(java.util.stream.Stream.of(m));
    OffsetDateTime entryAt = OffsetDateTime.parse("2026-07-05T14:30:00Z");
    stubSupersedeState("wf-0706", SPY_0706, 50L, entryAt, false);

    PositionLookupActivities.SupersedeCandidate c =
        svc.findOpenPositionByUnderlyingStrikeRight(
            "dev", "copytrade-v1", "SPY", new BigDecimal("710"), "P", "2026-07-08");

    assertThat(c).isNotNull();
    assertThat(c.workflowId()).isEqualTo("wf-0706");
    assertThat(c.occ()).isEqualTo(SPY_0706);
    assertThat(c.entryAt()).isEqualTo(entryAt);
    assertThat(c.partialExited()).isFalse();
  }

  @Test
  void findOpenPosition_sameExpiry_returnsNull() {
    // Same OCC/expiry is the existing dedup path, NOT a supersede target.
    String query = PositionLookupActivitiesImpl.tenantStrategyRunningQuery("dev", "copytrade-v1");
    io.temporal.client.WorkflowExecutionMetadata m = meta("wf-0708");
    when(workflowClient.listExecutions(query)).thenReturn(java.util.stream.Stream.of(m));
    stubSupersedeState(
        "wf-0708", SPY_0708, 50L, OffsetDateTime.parse("2026-07-05T14:30:00Z"), false);

    assertThat(
            svc.findOpenPositionByUnderlyingStrikeRight(
                "dev", "copytrade-v1", "SPY", new BigDecimal("710"), "P", "2026-07-08"))
        .isNull();
  }

  @Test
  void findOpenPosition_differentStrike_returnsNull() {
    String query = PositionLookupActivitiesImpl.tenantStrategyRunningQuery("dev", "copytrade-v1");
    io.temporal.client.WorkflowExecutionMetadata m = meta("wf-0706");
    when(workflowClient.listExecutions(query)).thenReturn(java.util.stream.Stream.of(m));
    stubSupersedeState(
        "wf-0706", SPY_0706, 50L, OffsetDateTime.parse("2026-07-05T14:30:00Z"), false);

    // Corrected strike 715 != prior 710 → no match.
    assertThat(
            svc.findOpenPositionByUnderlyingStrikeRight(
                "dev", "copytrade-v1", "SPY", new BigDecimal("715"), "P", "2026-07-08"))
        .isNull();
  }

  @Test
  void findOpenPosition_differentRight_returnsNull() {
    String query = PositionLookupActivitiesImpl.tenantStrategyRunningQuery("dev", "copytrade-v1");
    io.temporal.client.WorkflowExecutionMetadata m = meta("wf-0706");
    when(workflowClient.listExecutions(query)).thenReturn(java.util.stream.Stream.of(m));
    stubSupersedeState(
        "wf-0706", SPY_0706, 50L, OffsetDateTime.parse("2026-07-05T14:30:00Z"), false);

    // Corrected right C != prior P → no match.
    assertThat(
            svc.findOpenPositionByUnderlyingStrikeRight(
                "dev", "copytrade-v1", "SPY", new BigDecimal("710"), "C", "2026-07-08"))
        .isNull();
  }

  @Test
  void findOpenPosition_carriesEntryAtAndPartialExitedFromState() {
    String query = PositionLookupActivitiesImpl.tenantStrategyRunningQuery("dev", "copytrade-v1");
    io.temporal.client.WorkflowExecutionMetadata m = meta("wf-0706");
    when(workflowClient.listExecutions(query)).thenReturn(java.util.stream.Stream.of(m));
    OffsetDateTime entryAt = OffsetDateTime.parse("2026-07-05T14:31:00Z");
    stubSupersedeState("wf-0706", SPY_0706, 25L, entryAt, true);

    PositionLookupActivities.SupersedeCandidate c =
        svc.findOpenPositionByUnderlyingStrikeRight(
            "dev", "copytrade-v1", "SPY", new BigDecimal("710"), "P", "2026-07-08");

    // The activity does NOT apply the partial-exited guardrail — it surfaces it for the caller.
    assertThat(c).isNotNull();
    assertThat(c.partialExited()).isTrue();
    assertThat(c.entryAt()).isEqualTo(entryAt);
  }

  @Test
  void findOpenPosition_visibilityThrows_returnsNullNoThrow() {
    when(workflowClient.listExecutions(anyString())).thenThrow(new RuntimeException("vis down"));

    assertThat(
            svc.findOpenPositionByUnderlyingStrikeRight(
                "dev", "copytrade-v1", "SPY", new BigDecimal("710"), "P", "2026-07-08"))
        .isNull();
  }

  @Test
  void findOpenPosition_usesTenantStrategyQueryWithoutContractSymbolOrPrefix() {
    String q = PositionLookupActivitiesImpl.tenantStrategyRunningQuery("dev", "copytrade-v1");
    assertThat(q)
        .contains("TenantStrategy = 't-dev/s-copytrade-v1'")
        .contains("ExecutionStatus = 'WORKFLOW_EXECUTION_STATUS_RUNNING'")
        .contains("WorkflowType = 'PositionWorkflow'")
        .doesNotContain("ContractSymbol")
        .doesNotContain("STARTS_WITH")
        .doesNotContain("WorkflowId");
  }

  // ---------- findAllPositionWorkflowIds (STC multi-leg fan-out) ----------

  private static io.temporal.client.WorkflowExecutionMetadata metaAt(String wfId, String startIso) {
    io.temporal.client.WorkflowExecutionMetadata m =
        mock(io.temporal.client.WorkflowExecutionMetadata.class);
    when(m.getExecution())
        .thenReturn(
            io.temporal.api.common.v1.WorkflowExecution.newBuilder().setWorkflowId(wfId).build());
    when(m.getStartTime()).thenReturn(java.time.Instant.parse(startIso));
    return m;
  }

  private ValueOperations<String, String> stubCachedPointer(String cached) {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn(cached);
    return ops;
  }

  @Test
  void findAll_returnsEveryVisibleLegOldestFirst() {
    // Exit order should follow entry order, so the enumeration is sorted by start time — the raw
    // Visibility stream carries no ordering guarantee.
    var newer = metaAt("wf-newer", "2026-07-05T15:00:00Z");
    var older = metaAt("wf-older", "2026-07-05T14:00:00Z");
    when(workflowClient.listExecutions(anyString()))
        .thenReturn(java.util.stream.Stream.of(newer, older));
    stubCachedPointer(null);

    assertThat(svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .containsExactly("wf-older", "wf-newer");
  }

  @Test
  void findAll_includesACachedLegVisibilityHasNotIndexedYet() {
    // THE reason this is a union. Visibility lags under Postgres load, and the leg it is most
    // likely to be missing is the newest one — which is exactly the one the Redis pointer holds.
    // Dropping it would leave a just-opened position running after the author's exit.
    var older = metaAt("wf-older", "2026-07-05T14:00:00Z");
    when(workflowClient.listExecutions(anyString())).thenReturn(java.util.stream.Stream.of(older));
    stubCachedPointer("wf-brand-new");

    assertThat(svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .containsExactly("wf-older", "wf-brand-new");
  }

  @Test
  void findAll_doesNotDuplicateALegPresentInBothSources() {
    // The common case: the cached pointer IS one of the visible legs. Signalling it twice would
    // dispatch the same partialExit to one position twice.
    var a = metaAt("wf-a", "2026-07-05T14:00:00Z");
    var b = metaAt("wf-b", "2026-07-05T15:00:00Z");
    when(workflowClient.listExecutions(anyString())).thenReturn(java.util.stream.Stream.of(a, b));
    stubCachedPointer("wf-b");

    assertThat(svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .containsExactly("wf-a", "wf-b");
  }

  @Test
  void findAll_cachedOnly_whenVisibilityKnowsNothing() {
    when(workflowClient.listExecutions(anyString())).thenReturn(java.util.stream.Stream.empty());
    stubCachedPointer("wf-only");

    assertThat(svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .containsExactly("wf-only");
  }

  @Test
  void findAll_emptyWhenNeitherSourceHasALeg() {
    when(workflowClient.listExecutions(anyString())).thenReturn(java.util.stream.Stream.empty());
    stubCachedPointer(null);

    assertThat(svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .isEmpty();
  }

  @Test
  void findAll_propagatesAVisibilityFailureRatherThanReturningAShortList() {
    // Deliberately NOT best-effort, unlike the recon probes in this class. Swallowing the error
    // would return a truncated list, the caller would audit the STC as handled, and real legs
    // would stay open. A propagated failure just retries the activity.
    when(workflowClient.listExecutions(anyString()))
        .thenThrow(new IllegalStateException("visibility down"));
    stubCachedPointer("wf-cached");

    assertThatCode(
            () -> svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void findAll_queriesTheTenantStrategyAndContractSymbolScopedVisibility() {
    // The OCC must be the PADDED canonical form: ContractSymbol is an equality predicate, so a
    // compact OCC would silently match nothing and the fan-out would find no legs.
    when(workflowClient.listExecutions(anyString())).thenReturn(java.util.stream.Stream.empty());
    stubCachedPointer(null);

    svc.findAllPositionWorkflowIds("dev", "copytrade-v1", "NVDA  260516C00140000");

    verify(workflowClient)
        .listExecutions(
            PositionLookupActivitiesImpl.visibilityQuery(
                "dev", "copytrade-v1", "NVDA  260516C00140000"));
  }

  private static io.temporal.client.WorkflowExecutionMetadata meta(String wfId) {
    io.temporal.client.WorkflowExecutionMetadata m =
        mock(io.temporal.client.WorkflowExecutionMetadata.class);
    io.temporal.api.common.v1.WorkflowExecution exec =
        io.temporal.api.common.v1.WorkflowExecution.newBuilder().setWorkflowId(wfId).build();
    when(m.getExecution()).thenReturn(exec);
    when(m.getStartTime()).thenReturn(java.time.Instant.parse("2026-07-05T14:30:00Z"));
    return m;
  }

  private void stubSupersedeState(
      String wfId, String occ, long remainingQty, OffsetDateTime entryAt, boolean partialExited) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(wfId)).thenReturn(stub);
    when(stub.query("positionState", PositionState.class))
        .thenReturn(
            new PositionState(occ, remainingQty, new BigDecimal("3.00"), entryAt, partialExited));
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
