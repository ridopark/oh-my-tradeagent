package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot.OpenPosition;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #318: unit coverage for the Advanced Visibility–backed {@link PortfolioSnapshot}. Mocks the
 * {@link WorkflowClient} Visibility seam (listExecutions + per-workflow {@code positionState}
 * query) so the query shape, notional valuation, underlying derivation, and (tenant, strategy)
 * isolation are all asserted without a live Temporal cluster.
 */
class VisibilityPortfolioSnapshotTest {

  private WorkflowClient client;
  private SimpleMeterRegistry meterRegistry;
  private VisibilityPortfolioSnapshot snapshot;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    meterRegistry = new SimpleMeterRegistry();
    snapshot = new VisibilityPortfolioSnapshot(client, meterRegistry);
  }

  // The Visibility query filters on the TenantStrategy SA + WorkflowType +
  // ExecutionStatus='Running'
  // — never a WorkflowId prefix (PLAN.md:120-127). #323: the single-strategy resolver (default
  // ctor)
  // runs exactly ONE equality query — byte-identical to the pre-#323 TenantStrategy='...' equality
  // filter (inertness preserved; no IN (...) clause).
  @Test
  void openPositions_queriesOnTenantStrategySearchAttribute_notWorkflowIdPrefix() {
    when(client.listExecutions(anyString())).thenReturn(Stream.of());

    snapshot.openPositions("acme", "copytrade-v1");

    ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
    verify(client).listExecutions(q.capture());
    String query = q.getValue();
    assertThat(query)
        .isEqualTo(
            "WorkflowType='PositionWorkflow' AND TenantStrategy='t-acme/s-copytrade-v1' "
                + "AND ExecutionStatus='Running'");
    assertThat(query).doesNotContain("WorkflowId");
    assertThat(query).doesNotContain(" IN (");
  }

  // #323 tenant-account-wide cap basis: the snapshot runs the proven equality query ONCE PER
  // strategy of the requesting tenant (resolver returns both), unioning the results. Each
  // per-strategy query is the equality form, never an IN (...) clause. The requesting strategy is
  // always present.
  @Test
  void openPositions_widensQueryToAllTenantStrategies_tenantAccountWide() {
    VisibilityPortfolioSnapshot wide =
        new VisibilityPortfolioSnapshot(
            client, meterRegistry, tenantId -> List.of("copytrade-v1", "copytrade-v2"));
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of());

    wide.openPositions("acme", "copytrade-v1");

    ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
    verify(client, times(2)).listExecutions(q.capture());
    List<String> queries = q.getAllValues();
    assertThat(queries)
        .containsExactlyInAnyOrder(
            "WorkflowType='PositionWorkflow' AND TenantStrategy='t-acme/s-copytrade-v1' "
                + "AND ExecutionStatus='Running'",
            "WorkflowType='PositionWorkflow' AND TenantStrategy='t-acme/s-copytrade-v2' "
                + "AND ExecutionStatus='Running'");
    assertThat(queries).noneMatch(query -> query.contains(" IN ("));
  }

  // #323: positions from two strategies of ONE tenant sum across both strategies (account-wide
  // notional). Drives the union end-to-end through per-strategy equality queries + valuation.
  @Test
  void openPositions_aggregatesNotionalAcrossTwoStrategiesOfOneTenant() {
    VisibilityPortfolioSnapshot wide =
        new VisibilityPortfolioSnapshot(
            client, meterRegistry, tenantId -> List.of("copytrade-v1", "copytrade-v2"));
    // wf-s1 belongs to strategy 1, wf-s2 to strategy 2; each per-strategy equality query returns
    // its
    // own execution. Route the right stream to the right query by matching the TenantStrategy
    // value.
    stubExecutionsByQuery(
        Map.of(
            "t-acme/s-copytrade-v1", "wf-s1",
            "t-acme/s-copytrade-v2", "wf-s2"),
        Map.of(
            "wf-s1", new PositionState("NVDA  250516C00140000", 2L, new BigDecimal("2.00")),
            "wf-s2", new PositionState("AAPL  250516C00200000", 1L, new BigDecimal("5.00"))));

    List<OpenPosition> positions = wide.openPositions("acme", "copytrade-v1");

    // NVDA 2 ctr @ 2.00 -> 400 ; AAPL 1 ctr @ 5.00 -> 500. Both tenant strategies aggregate.
    assertThat(positions)
        .containsExactlyInAnyOrder(
            new OpenPosition("NVDA", new BigDecimal("400.00")),
            new OpenPosition("AAPL", new BigDecimal("500.00")));
  }

  // #323 cross-tenant isolation: every per-strategy query carries the t-<tenant>/ prefix, so a
  // second tenant's TenantStrategy value can never appear in any query.
  @Test
  void openPositions_neverIncludesAnotherTenantInQuery_crossTenantIsolation() {
    VisibilityPortfolioSnapshot wide =
        new VisibilityPortfolioSnapshot(
            client, meterRegistry, tenantId -> List.of("copytrade-v1", "copytrade-v2"));
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of());

    wide.openPositions("acme", "copytrade-v1");

    ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
    verify(client, times(2)).listExecutions(q.capture());
    assertThat(q.getAllValues()).allSatisfy(query -> assertThat(query).doesNotContain("t-other"));
    assertThat(q.getAllValues()).allSatisfy(query -> assertThat(query).contains("t-acme/"));
  }

  // #323 inertness: the single-strategy case (resolver returns nothing extra) runs exactly ONE
  // equality query — the requesting strategy is always unioned in, so the resolved set is never
  // empty here and the snapshot does not hit the fail-closed empty-set guard.
  @Test
  void openPositions_singleStrategy_runsExactlyOneEqualityQuery_inert() {
    VisibilityPortfolioSnapshot single =
        new VisibilityPortfolioSnapshot(client, meterRegistry, tenantId -> List.of());
    when(client.listExecutions(anyString())).thenReturn(Stream.of());

    single.openPositions("acme", "copytrade-v1");

    ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
    verify(client, times(1)).listExecutions(q.capture());
    assertThat(q.getValue())
        .isEqualTo(
            "WorkflowType='PositionWorkflow' AND TenantStrategy='t-acme/s-copytrade-v1' "
                + "AND ExecutionStatus='Running'");
  }

  // #323 fail-closed: when the resolved strategy set is empty — a blank requesting strategy AND an
  // empty resolver — the snapshot must THROW rather than query nothing and report
  // sum_open_notional=0 (which would loosen the cap fail-OPEN). The blank requesting id is dropped
  // and the resolver adds nothing, so the set is genuinely empty and the empty-set guard fires
  // before any listExecutions call.
  @Test
  void openPositions_throwsOnEmptyStrategySet_failClosed() {
    VisibilityPortfolioSnapshot emptySet =
        new VisibilityPortfolioSnapshot(client, meterRegistry, tenantId -> List.of());

    assertThatThrownBy(() -> emptySet.openPositions("acme", "  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty strategy set");

    verify(client, never()).listExecutions(anyString());
  }

  // #323 fail-closed (#325): a resolver that throws (unreadable tenants tree) must propagate, not
  // be
  // swallowed into a match-nothing query that would loosen the cap.
  @Test
  void openPositions_propagatesResolverError_failClosed() {
    VisibilityPortfolioSnapshot wide =
        new VisibilityPortfolioSnapshot(
            client,
            meterRegistry,
            tenantId -> {
              throw new IllegalStateException("tenants tree unreadable");
            });

    assertThatThrownBy(() -> wide.openPositions("acme", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenants tree unreadable");
  }

  @Test
  void openPositions_valuesEachRunningPosition_costBasisNotionalAndUnderlying() {
    // Two running positions: NVDA 3 ctr @ 2.50 -> 750 ; AAPL 1 ctr @ 4.00 -> 400.
    stubExecutions(
        Map.of(
            "wf-nvda", new PositionState("NVDA  250516C00140000", 3L, new BigDecimal("2.50")),
            "wf-aapl", new PositionState("AAPL  250516C00200000", 1L, new BigDecimal("4.00"))));

    List<OpenPosition> positions = snapshot.openPositions("acme", "copytrade-v1");

    assertThat(positions)
        .containsExactlyInAnyOrder(
            new OpenPosition("NVDA", new BigDecimal("750.00")),
            new OpenPosition("AAPL", new BigDecimal("400.00")));
  }

  @Test
  void openPositions_skipsClosedOrUnvaluablePositions() {
    // remainingQty 0 (just closed), blank contract, and null premium are all skipped.
    stubExecutions(
        Map.of(
            "wf-closed", new PositionState("NVDA  250516C00140000", 0L, new BigDecimal("2.50")),
            "wf-blank", new PositionState("", 2L, new BigDecimal("2.50")),
            "wf-nopremium", new PositionState("AAPL  250516C00200000", 2L, null),
            "wf-live", new PositionState("MSFT  250516C00300000", 2L, new BigDecimal("1.00"))));

    List<OpenPosition> positions = snapshot.openPositions("acme", "copytrade-v1");

    assertThat(positions).containsExactly(new OpenPosition("MSFT", new BigDecimal("200.00")));
  }

  // #325 best-effort contract on a larger book: 1 genuine value-failure out of 3 listed (33% < 50%,
  // and listed > 2 so the small-book floor does not apply) stays best-effort — the failed position
  // is skipped and the surviving two are returned. This preserves "an isolated transient query
  // failure on a larger book skips and continues" rather than failing the whole snapshot.
  @Test
  void openPositions_querySkipsWorkflowThatFailedTheQuery_bestEffort() {
    WorkflowExecutionMetadata good1 = metadata("wf-good-1");
    WorkflowExecutionMetadata good2 = metadata("wf-good-2");
    WorkflowExecutionMetadata bad = metadata("wf-bad");
    when(client.listExecutions(anyString())).thenReturn(Stream.of(bad, good1, good2));

    WorkflowStub good1Stub = mock(WorkflowStub.class);
    when(good1Stub.query(eq("positionState"), eq(PositionState.class)))
        .thenReturn(new PositionState("NVDA  250516C00140000", 1L, new BigDecimal("3.00")));
    WorkflowStub good2Stub = mock(WorkflowStub.class);
    when(good2Stub.query(eq("positionState"), eq(PositionState.class)))
        .thenReturn(new PositionState("AAPL  250516C00200000", 2L, new BigDecimal("1.50")));
    WorkflowStub badStub = mock(WorkflowStub.class);
    when(badStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("workflow not found"));

    when(client.newUntypedWorkflowStub("wf-good-1")).thenReturn(good1Stub);
    when(client.newUntypedWorkflowStub("wf-good-2")).thenReturn(good2Stub);
    when(client.newUntypedWorkflowStub("wf-bad")).thenReturn(badStub);

    List<OpenPosition> positions = snapshot.openPositions("acme", "copytrade-v1");

    assertThat(positions)
        .containsExactlyInAnyOrder(
            new OpenPosition("NVDA", new BigDecimal("300.00")),
            new OpenPosition("AAPL", new BigDecimal("300.00")));
  }

  // #325 small-book floor: 1 genuine value-failure out of 2 listed positions is exactly 50% (below
  // the relative >50% threshold) but on a 1-2 position book a single missed position is up to a
  // full position's notional, materially loosening the cap — so the small-book floor fails it
  // closed (throws) rather than returning the surviving position with an undercounted notional.
  @Test
  void openPositions_throwsWhenOneOfTwoListedPositionsFailsToValue_smallBookFloor() {
    WorkflowExecutionMetadata good = metadata("wf-good");
    WorkflowExecutionMetadata bad = metadata("wf-bad");
    when(client.listExecutions(anyString())).thenReturn(Stream.of(bad, good));

    WorkflowStub goodStub = mock(WorkflowStub.class);
    lenient()
        .when(goodStub.query(eq("positionState"), eq(PositionState.class)))
        .thenReturn(new PositionState("NVDA  250516C00140000", 1L, new BigDecimal("3.00")));
    WorkflowStub badStub = mock(WorkflowStub.class);
    when(badStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("workflow not found"));

    when(client.newUntypedWorkflowStub("wf-good")).thenReturn(goodStub);
    when(client.newUntypedWorkflowStub("wf-bad")).thenReturn(badStub);

    assertThatThrownBy(() -> snapshot.openPositions("acme", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("value-failure bound exceeded");
  }

  // #325 fail-closed contract: a Visibility error in the listExecutions query must PROPAGATE (not
  // be
  // swallowed into List.of()). An empty list → sum_open_notional=0 → loosens the notional cap →
  // fail-OPEN; the throw is what keeps the gate fail-closed at the activity boundary.
  @Test
  void openPositions_propagatesListExecutionsError_failClosed_notEmptyList() {
    when(client.listExecutions(anyString()))
        .thenThrow(new RuntimeException("visibility unavailable"));

    assertThatThrownBy(() -> snapshot.openPositions("acme", "copytrade-v1"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("visibility unavailable");
  }

  // #325 fail-closed contract: if the per-workflow positionState query throws for EVERY listed
  // position (a correlated Temporal degradation while listExecutions still succeeds), the Task (c)
  // bound trips — > 50% failed to value — so the snapshot fails closed (throws) rather than
  // returning an undercounted (here empty) list that would loosen the cap.
  @Test
  void openPositions_throwsWhenAllListedPositionsFailToValue_failClosed() {
    WorkflowExecutionMetadata a = metadata("wf-a");
    WorkflowExecutionMetadata b = metadata("wf-b");
    when(client.listExecutions(anyString())).thenReturn(Stream.of(a, b));

    WorkflowStub aStub = mock(WorkflowStub.class);
    when(aStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("query failed a"));
    WorkflowStub bStub = mock(WorkflowStub.class);
    when(bStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("query failed b"));
    when(client.newUntypedWorkflowStub("wf-a")).thenReturn(aStub);
    when(client.newUntypedWorkflowStub("wf-b")).thenReturn(bStub);

    assertThatThrownBy(() -> snapshot.openPositions("acme", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("value-failure bound exceeded");
  }

  // #325 fail-closed bound boundary: 1 value-failure out of 1 listed position is 100% > 50%, so a
  // single failure on a one-position book fails closed (small-count guard).
  @Test
  void openPositions_throwsWhenSingleListedPositionFailsToValue_failClosed() {
    WorkflowExecutionMetadata only = metadata("wf-only");
    when(client.listExecutions(anyString())).thenReturn(Stream.of(only));

    WorkflowStub stub = mock(WorkflowStub.class);
    when(stub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("query failed"));
    when(client.newUntypedWorkflowStub("wf-only")).thenReturn(stub);

    assertThatThrownBy(() -> snapshot.openPositions("acme", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("value-failure bound exceeded");
  }

  // #325: a legitimate just-closed/blank/null-premium skip must NOT count toward the value-failure
  // bound — even if EVERY listed position is a legitimate skip, that returns an empty list (no
  // throw), preserving the existing skip semantics distinct from a query failure.
  @Test
  void openPositions_legitimateSkipsDoNotCountTowardFailureBound() {
    stubExecutions(
        Map.of(
            "wf-closed", new PositionState("NVDA  250516C00140000", 0L, new BigDecimal("2.50")),
            "wf-blank", new PositionState("", 2L, new BigDecimal("2.50"))));

    List<OpenPosition> positions = snapshot.openPositions("acme", "copytrade-v1");

    assertThat(positions).isEmpty();
  }

  // Isolation: the snapshot only queries the workflows the (tenant, strategy)-scoped Visibility
  // query returned — a different scope's position is never reached.
  @Test
  void openPositions_isolatesByTenantStrategy() {
    stubExecutions(
        Map.of("wf-acme", new PositionState("NVDA  250516C00140000", 1L, new BigDecimal("2.00"))));

    snapshot.openPositions("acme", "copytrade-v1");

    // The cross-tenant workflow id is never resolved because Visibility never returned it.
    verify(client, never()).newUntypedWorkflowStub("wf-other-tenant");
  }

  // #317 contract: the snapshot's accountEquity returns the ZERO sentinel so the notional-cap gate
  // fails closed when it falls back here (real equity comes over the broker dispatch seam).
  @Test
  void accountEquity_returnsZeroSentinel_failClosedFallback() {
    assertThat(snapshot.accountEquity("alpaca-paper")).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // #329 observability: a best-effort value-failure (1 of 3 listed, below the fail-closed bound)
  // increments openpositions_value_failures_total by the failure count, tagged failed_closed=false.
  // Reuses the best-effort book from openPositions_querySkipsWorkflowThatFailedTheQuery_bestEffort.
  @Test
  void openPositions_emitsValueFailuresCounter_bestEffort_failedClosedFalse() {
    WorkflowExecutionMetadata good1 = metadata("wf-good-1");
    WorkflowExecutionMetadata good2 = metadata("wf-good-2");
    WorkflowExecutionMetadata bad = metadata("wf-bad");
    when(client.listExecutions(anyString())).thenReturn(Stream.of(bad, good1, good2));

    WorkflowStub good1Stub = mock(WorkflowStub.class);
    when(good1Stub.query(eq("positionState"), eq(PositionState.class)))
        .thenReturn(new PositionState("NVDA  250516C00140000", 1L, new BigDecimal("3.00")));
    WorkflowStub good2Stub = mock(WorkflowStub.class);
    when(good2Stub.query(eq("positionState"), eq(PositionState.class)))
        .thenReturn(new PositionState("AAPL  250516C00200000", 2L, new BigDecimal("1.50")));
    WorkflowStub badStub = mock(WorkflowStub.class);
    when(badStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("workflow not found"));
    when(client.newUntypedWorkflowStub("wf-good-1")).thenReturn(good1Stub);
    when(client.newUntypedWorkflowStub("wf-good-2")).thenReturn(good2Stub);
    when(client.newUntypedWorkflowStub("wf-bad")).thenReturn(badStub);

    snapshot.openPositions("acme", "copytrade-v1");

    assertThat(valueFailureCount("acme", "copytrade-v1", false)).isEqualTo(1.0);
    assertThat(valueFailureCount("acme", "copytrade-v1", true)).isEqualTo(0.0);
  }

  // #329 observability: when the fail-closed bound trips (here all listed positions fail to value),
  // the counter still fires — by the full failure count, tagged failed_closed=true — even though
  // the
  // call also throws. The emit happens before the throw so the degradation is observable.
  @Test
  void openPositions_emitsValueFailuresCounter_failClosed_failedClosedTrue() {
    WorkflowExecutionMetadata a = metadata("wf-a");
    WorkflowExecutionMetadata b = metadata("wf-b");
    when(client.listExecutions(anyString())).thenReturn(Stream.of(a, b));

    WorkflowStub aStub = mock(WorkflowStub.class);
    when(aStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("query failed a"));
    WorkflowStub bStub = mock(WorkflowStub.class);
    when(bStub.query(eq("positionState"), eq(PositionState.class)))
        .thenThrow(new RuntimeException("query failed b"));
    when(client.newUntypedWorkflowStub("wf-a")).thenReturn(aStub);
    when(client.newUntypedWorkflowStub("wf-b")).thenReturn(bStub);

    assertThatThrownBy(() -> snapshot.openPositions("acme", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(valueFailureCount("acme", "copytrade-v1", true)).isEqualTo(2.0);
    assertThat(valueFailureCount("acme", "copytrade-v1", false)).isEqualTo(0.0);
  }

  // #329 observability: an all-good book has zero value-failures, so the counter never registers
  // (no
  // emit) — gating on valueFailures > 0 keeps the signal quiet on the happy path.
  @Test
  void openPositions_doesNotEmitValueFailuresCounter_allGoodBook() {
    stubExecutions(
        Map.of(
            "wf-nvda", new PositionState("NVDA  250516C00140000", 3L, new BigDecimal("2.50")),
            "wf-aapl", new PositionState("AAPL  250516C00200000", 1L, new BigDecimal("4.00"))));

    snapshot.openPositions("acme", "copytrade-v1");

    assertThat(
            meterRegistry.find(VisibilityPortfolioSnapshot.VALUE_FAILURES_COUNTER_NAME).counter())
        .isNull();
  }

  // #329 observability: legitimate just-closed/blank/null-premium skips are NOT value-failures, so
  // an
  // all-skips book also emits nothing — distinguishing benign skips from genuine query degradation.
  @Test
  void openPositions_doesNotEmitValueFailuresCounter_allLegitimateSkips() {
    stubExecutions(
        Map.of(
            "wf-closed", new PositionState("NVDA  250516C00140000", 0L, new BigDecimal("2.50")),
            "wf-blank", new PositionState("", 2L, new BigDecimal("2.50"))));

    snapshot.openPositions("acme", "copytrade-v1");

    assertThat(
            meterRegistry.find(VisibilityPortfolioSnapshot.VALUE_FAILURES_COUNTER_NAME).counter())
        .isNull();
  }

  // ----- helpers -----

  private double valueFailureCount(String tenant, String strategy, boolean failedClosed) {
    var counter =
        meterRegistry
            .find(VisibilityPortfolioSnapshot.VALUE_FAILURES_COUNTER_NAME)
            .tag("tenant", tenant)
            .tag("strategy", strategy)
            .tag("failed_closed", Boolean.toString(failedClosed))
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  // Routes each per-strategy equality query (matched by its TenantStrategy value) to a single
  // workflow id, and stubs that workflow's positionState. Used by the multi-strategy union tests
  // where listExecutions is called once per strategy and each call must return its own stream.
  private void stubExecutionsByQuery(
      Map<String, String> tenantStrategyToWorkflowId,
      Map<String, PositionState> stateByWorkflowId) {
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              return tenantStrategyToWorkflowId.entrySet().stream()
                  .filter(e -> query.contains("TenantStrategy='" + e.getKey() + "'"))
                  .map(Map.Entry::getValue)
                  .map(this::metadata);
            });
    stateByWorkflowId.forEach(
        (wfId, state) -> {
          WorkflowStub stub = mock(WorkflowStub.class);
          lenient()
              .when(stub.query(eq("positionState"), eq(PositionState.class)))
              .thenReturn(state);
          when(client.newUntypedWorkflowStub(wfId)).thenReturn(stub);
        });
  }

  private void stubExecutions(Map<String, PositionState> byWorkflowId) {
    Stream<WorkflowExecutionMetadata> stream = byWorkflowId.keySet().stream().map(this::metadata);
    when(client.listExecutions(anyString())).thenReturn(stream);
    byWorkflowId.forEach(
        (wfId, state) -> {
          WorkflowStub stub = mock(WorkflowStub.class);
          lenient()
              .when(stub.query(eq("positionState"), eq(PositionState.class)))
              .thenReturn(state);
          when(client.newUntypedWorkflowStub(wfId)).thenReturn(stub);
        });
  }

  private WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    lenient()
        .when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }
}
