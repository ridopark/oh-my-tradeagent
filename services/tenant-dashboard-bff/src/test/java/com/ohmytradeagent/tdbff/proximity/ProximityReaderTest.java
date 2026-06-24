package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.EntryProximityView;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.ExitProximityView;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.PositionProximity;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.WatchlistProximity;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProximityReaderTest {

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final WorkflowClient client = mock(WorkflowClient.class);
  private final TenantStrategyResolver strategyResolver = mock(TenantStrategyResolver.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final SetOperations<String, String> setOps = mock(SetOperations.class);

  private final ProximityReader reader = new ProximityReader(client, strategyResolver, redis);

  // ---------------- watchlist (entry proximity) ----------------

  @Test
  void watchlist_returnsEntryProximityWithDirectionAwareDistance() {
    wireStrategies("acme", "wl");
    wireArmedSet("acme", "wl", "wf-leg");
    wireEntry(
        "wf-leg",
        new EntryProximityView(
            "NVDA",
            "ABOVE",
            new BigDecimal("761.00"),
            new BigDecimal("757.195"),
            new BigDecimal("764.805"),
            new BigDecimal("760.50"),
            "ARMED",
            "NVDA  260516C00140000"));

    List<WatchlistProximity> out = reader.watchlist("acme");

    assertThat(out).hasSize(1);
    WatchlistProximity w = out.get(0);
    assertThat(w.ticker()).isEqualTo("NVDA");
    assertThat(w.state()).isEqualTo("ARMED");
    assertThat(w.optionSymbol()).isEqualTo("NVDA  260516C00140000");
    // ABOVE: (761.00 - 760.50) / 761.00 * 100 = 0.0657%
    assertThat(w.distanceToTriggerPct()).isEqualTo(0.0657);
    // The armed enumeration must NOT use listExecutions (the lag-prone visibility query).
    verify(client, never()).listExecutions(anyString());
  }

  // Armed legs are enumerated from BOTH today's and yesterday's (ET) Redis keys and unioned. A leg
  // armed just before midnight (yesterday's key) and one armed today must both surface.
  @Test
  void watchlist_enumeratesFromRedis_notListExecutions() {
    wireStrategies("acme", "wl");
    LocalDate today = LocalDate.now(MARKET_TZ);
    when(setOps.members(armedKey("acme", "wl", today))).thenReturn(Set.of("wf-today"));
    when(setOps.members(armedKey("acme", "wl", today.minusDays(1))))
        .thenReturn(Set.of("wf-yesterday"));
    when(redis.opsForSet()).thenReturn(setOps);
    wireEntry("wf-today", armedEntry("NVDA"));
    wireEntry("wf-yesterday", armedEntry("TSLA"));

    List<WatchlistProximity> out = reader.watchlist("acme");

    assertThat(out).hasSize(2);
    assertThat(out)
        .extracting(WatchlistProximity::workflowId)
        .containsExactlyInAnyOrder("wf-today", "wf-yesterday");
    verify(client, never()).listExecutions(anyString());
  }

  // A wfId whose workflow is DEFINITIVELY gone (WorkflowNotFoundException, the not-found/closed
  // case) is excluded AND lazily SREM'd from the exact key it was read from (the set self-heals).
  @Test
  void watchlist_deadEntry_skippedAndEvicted() {
    wireStrategies("acme", "wl");
    LocalDate today = LocalDate.now(MARKET_TZ);
    String todayKey = armedKey("acme", "wl", today);
    when(setOps.members(todayKey)).thenReturn(Set.of("wf-live", "wf-dead"));
    lenient().when(setOps.members(armedKey("acme", "wl", today.minusDays(1)))).thenReturn(Set.of());
    when(redis.opsForSet()).thenReturn(setOps);
    wireEntry("wf-live", armedEntry("NVDA"));
    // wf-dead: the workflow no longer exists -> WorkflowNotFoundException -> definitively gone.
    wireEntryThrows("wf-dead", notFound("wf-dead"));

    List<WatchlistProximity> out = reader.watchlist("acme");

    assertThat(out).extracting(WatchlistProximity::workflowId).containsExactly("wf-live");
    verify(setOps).remove(todayKey, "wf-dead");
    verify(setOps, never()).remove(todayKey, "wf-live");
  }

  // A leg that answers with a blank ticker is unarmed/gone -> excluded AND evicted (same definitive
  // semantics as a not-found workflow: it responded, just with no live state).
  @Test
  void watchlist_blankTicker_skippedAndEvicted() {
    wireStrategies("acme", "wl");
    LocalDate today = LocalDate.now(MARKET_TZ);
    String todayKey = armedKey("acme", "wl", today);
    when(setOps.members(todayKey)).thenReturn(Set.of("wf-blank"));
    lenient().when(setOps.members(armedKey("acme", "wl", today.minusDays(1)))).thenReturn(Set.of());
    when(redis.opsForSet()).thenReturn(setOps);
    wireEntry(
        "wf-blank",
        new EntryProximityView("", "ABOVE", null, null, null, null, "INITIALIZING", null));

    assertThat(reader.watchlist("acme")).isEmpty();
    verify(setOps).remove(todayKey, "wf-blank");
  }

  // A TRANSIENT query failure (timeout / worker blip / query rejected, surfaced as a generic
  // RuntimeException, NOT WorkflowNotFoundException) is NOT proof the leg is gone. The leg is
  // excluded from THIS poll's result but must NOT be SREM'd — the SADD happens once at arm with no
  // intraday re-seed, so an eviction here would permanently drop a still-live leg for the day.
  @Test
  void watchlist_transientQueryError_skippedButNotEvicted() {
    wireStrategies("acme", "wl");
    LocalDate today = LocalDate.now(MARKET_TZ);
    String todayKey = armedKey("acme", "wl", today);
    when(setOps.members(todayKey)).thenReturn(Set.of("wf-live", "wf-blip"));
    lenient().when(setOps.members(armedKey("acme", "wl", today.minusDays(1)))).thenReturn(Set.of());
    when(redis.opsForSet()).thenReturn(setOps);
    wireEntry("wf-live", armedEntry("NVDA"));
    wireEntryThrows("wf-blip", new RuntimeException("query timeout"));

    List<WatchlistProximity> out = reader.watchlist("acme");

    assertThat(out).extracting(WatchlistProximity::workflowId).containsExactly("wf-live");
    // The still-armed leg stays in the set: NO eviction on a transient blip.
    verify(setOps, never()).remove(todayKey, "wf-blip");
    verify(setOps, never()).remove(todayKey, "wf-live");
  }

  @Test
  void distanceToTrigger_belowDirection_usesMirroredGap() {
    Double d =
        ProximityReader.distanceToTrigger(
            new EntryProximityView(
                "TSLA",
                "BELOW",
                new BigDecimal("400.00"),
                null,
                null,
                new BigDecimal("404.00"),
                "ARMED",
                null));
    // BELOW: (404 - 400) / 400 * 100 = 1.0%
    assertThat(d).isEqualTo(1.0);
  }

  @Test
  void distanceToTrigger_nullLastPrice_isNull() {
    assertThat(
            ProximityReader.distanceToTrigger(
                new EntryProximityView(
                    "TSLA", "ABOVE", new BigDecimal("400"), null, null, null, "ARMED", null)))
        .isNull();
  }

  // ---------------- positions (exit proximity) ----------------

  @Test
  void positions_armed_computesStopAndTargetDistances() {
    wireStrategies("acme", "wl");
    wireListExecutions("wf-pos");
    wireExit(
        "wf-pos",
        armedExit(
            "NVDA  260516C00140000",
            new BigDecimal("2.00"), // entry
            new BigDecimal("1.50"), // stop
            new BigDecimal("3.00"), // target
            new BigDecimal("2.40"))); // lastBid

    List<PositionProximity> out = reader.positions("acme");

    assertThat(out).hasSize(1);
    PositionProximity p = out.get(0);
    // (2.40 - 1.50) / 2.40 * 100 = 37.5
    assertThat(p.distanceToStopPct()).isEqualTo(37.5);
    // (3.00 - 2.40) / 2.40 * 100 = 25.0
    assertThat(p.distanceToTargetPct()).isEqualTo(25.0);
  }

  @Test
  void positions_unarmed_isFilteredOut() {
    wireStrategies("acme", "copytrade");
    wireListExecutions("wf-copytrade");
    ExitProximityView unarmed =
        new ExitProximityView(
            "NVDA  260516C00140000",
            new BigDecimal("2.00"),
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            false,
            null);
    wireExit("wf-copytrade", unarmed);

    assertThat(reader.positions("acme")).isEmpty();
  }

  @Test
  void positions_queryThrows_skippedNotFatal() {
    wireStrategies("acme", "wl");
    wireListExecutions("wf-dead");
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq("wf-dead"))).thenReturn(stub);
    when(stub.query(eq("exitProximity"), eq(ExitProximityView.class), any(Object[].class)))
        .thenThrow(new RuntimeException("workflow terminated"));

    assertThat(reader.positions("acme")).isEmpty();
  }

  // ---------------- underlyingTicker (OCC root extraction) ----------------

  @Test
  void underlyingTicker_parsesPaddedAndCompactOcc() {
    assertThat(ProximityReader.underlyingTicker("NVDA  260516C00140000")).isEqualTo("NVDA");
    assertThat(ProximityReader.underlyingTicker("NVDA260516C00140000")).isEqualTo("NVDA");
    assertThat(ProximityReader.underlyingTicker("SPY   260609P00731000")).isEqualTo("SPY");
  }

  @Test
  void underlyingTicker_nullOrTooShort_isNull() {
    assertThat(ProximityReader.underlyingTicker(null)).isNull();
    assertThat(ProximityReader.underlyingTicker("")).isNull();
    assertThat(ProximityReader.underlyingTicker("260516C00140000")).isNull(); // root empty
  }

  // ---------------- helpers ----------------

  private static ExitProximityView armedExit(
      String occ, BigDecimal entry, BigDecimal stop, BigDecimal target, BigDecimal lastBid) {
    return new ExitProximityView(
        occ, entry, stop, target, lastBid, lastBid, null, false, null, true, null);
  }

  private static EntryProximityView armedEntry(String ticker) {
    return new EntryProximityView(
        ticker,
        "ABOVE",
        new BigDecimal("761.00"),
        new BigDecimal("757.195"),
        new BigDecimal("764.805"),
        new BigDecimal("760.50"),
        "ARMED",
        null);
  }

  private static String armedKey(String tenant, String strategyId, LocalDate date) {
    return WorkflowIds.armedWatchlistCacheKey(tenant, strategyId, date);
  }

  private void wireStrategies(String tenant, String... strategyIds) {
    when(strategyResolver.strategyIdsForTenant(tenant)).thenReturn(List.of(strategyIds));
  }

  // Seeds today's armed-watchlist Redis set with the given wfIds; yesterday's key is empty.
  private void wireArmedSet(String tenant, String strategyId, String... workflowIds) {
    LocalDate today = LocalDate.now(MARKET_TZ);
    lenient()
        .when(setOps.members(armedKey(tenant, strategyId, today)))
        .thenReturn(Set.of(workflowIds));
    lenient()
        .when(setOps.members(armedKey(tenant, strategyId, today.minusDays(1))))
        .thenReturn(Set.of());
    lenient().when(redis.opsForSet()).thenReturn(setOps);
  }

  private void wireListExecutions(String... workflowIds) {
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv ->
                Stream.of(workflowIds)
                    .map(ProximityReaderTest::metadata)
                    .map(m -> (WorkflowExecutionMetadata) m));
  }

  private void wireEntry(String workflowId, EntryProximityView view) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(workflowId))).thenReturn(stub);
    when(stub.query(eq("entryProximity"), eq(EntryProximityView.class), any(Object[].class)))
        .thenReturn(view);
  }

  private void wireEntryThrows(String workflowId, RuntimeException ex) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(workflowId))).thenReturn(stub);
    when(stub.query(eq("entryProximity"), eq(EntryProximityView.class), any(Object[].class)))
        .thenThrow(ex);
  }

  private static WorkflowNotFoundException notFound(String workflowId) {
    return new WorkflowNotFoundException(
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).build(),
        "WatchlistTriggerWorkflow",
        new RuntimeException("not found"));
  }

  private void wireExit(String workflowId, ExitProximityView view) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(workflowId))).thenReturn(stub);
    when(stub.query(eq("exitProximity"), eq(ExitProximityView.class), any(Object[].class)))
        .thenReturn(view);
  }

  private static WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }
}
