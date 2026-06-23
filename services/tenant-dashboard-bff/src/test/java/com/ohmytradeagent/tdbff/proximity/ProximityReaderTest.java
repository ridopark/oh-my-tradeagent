package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.EntryProximityView;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.ExitProximityView;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.PositionProximity;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.WatchlistProximity;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProximityReaderTest {

  private final WorkflowClient client = mock(WorkflowClient.class);
  private final TenantStrategyResolver strategyResolver = mock(TenantStrategyResolver.class);
  private final ProximityReader reader = new ProximityReader(client, strategyResolver);

  // ---------------- watchlist (entry proximity) ----------------

  @Test
  void watchlist_returnsEntryProximityWithDirectionAwareDistance() {
    wireStrategies("acme", "wl");
    wireListExecutions("wf-leg");
    wireEntry(
        "wf-leg",
        new EntryProximityView(
            "NVDA",
            "ABOVE",
            new BigDecimal("761.00"),
            new BigDecimal("757.195"),
            new BigDecimal("764.805"),
            new BigDecimal("760.50"),
            "ARMED"));

    List<WatchlistProximity> out = reader.watchlist("acme");

    assertThat(out).hasSize(1);
    WatchlistProximity w = out.get(0);
    assertThat(w.ticker()).isEqualTo("NVDA");
    assertThat(w.state()).isEqualTo("ARMED");
    // ABOVE: (761.00 - 760.50) / 761.00 * 100 = 0.0657%
    assertThat(w.distanceToTriggerPct()).isEqualTo(0.0657);
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
                "ARMED"));
    // BELOW: (404 - 400) / 400 * 100 = 1.0%
    assertThat(d).isEqualTo(1.0);
  }

  @Test
  void distanceToTrigger_nullLastPrice_isNull() {
    assertThat(
            ProximityReader.distanceToTrigger(
                new EntryProximityView(
                    "TSLA", "ABOVE", new BigDecimal("400"), null, null, null, "ARMED")))
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

  private void wireStrategies(String tenant, String... strategyIds) {
    when(strategyResolver.strategyIdsForTenant(tenant)).thenReturn(List.of(strategyIds));
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
