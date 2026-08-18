package com.ohmytradeagent.tdbff.positions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.positions.PositionsReader.PositionStateView;
import com.ohmytradeagent.tdbff.positions.PositionsReader.TrailingStateView;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #434: a physically-expired option contract must not appear in the dashboard's open
 * positions nor contribute to sum_open_notional — the broker dropped it at expiry, but a
 * PositionWorkflow that rode a worthless contract can linger "open" until its durable
 * worthless-close lands. The filter is read-side (zero replay risk) and fails OPEN on an
 * unparseable OCC so a parse quirk never hides a real live position.
 */
class PositionsReaderTest {

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private final WorkflowClient client = mock(WorkflowClient.class);
  private final TenantStrategyResolver strategyResolver = mock(TenantStrategyResolver.class);
  private final PositionsReader reader = new PositionsReader(client, strategyResolver);

  // ---------------------------------------------------------------------------
  // parseExpiry unit coverage (padded canonical + compact broker forms)
  // ---------------------------------------------------------------------------

  @Test
  void parsesExpiryFromSpacePaddedCanonicalOcc() {
    // The TSLA put from the incident: space-padded canonical form.
    assertThat(PositionsReader.parseExpiry("TSLA  260618P00380000"))
        .isEqualTo(LocalDate.of(2026, 6, 18));
  }

  @Test
  void parsesExpiryFromCompactBrokerOcc() {
    // Alpaca compact form (no root padding).
    assertThat(PositionsReader.parseExpiry("TSLA260618P00380000"))
        .isEqualTo(LocalDate.of(2026, 6, 18));
  }

  @Test
  void parseExpiryReturnsNullForUnparseableOcc() {
    assertThat(PositionsReader.parseExpiry(null)).isNull();
    assertThat(PositionsReader.parseExpiry("")).isNull();
    assertThat(PositionsReader.parseExpiry("SHORT")).isNull();
    // 15 chars but the YYMMDD slice is non-numeric -> null (fail-open).
    assertThat(PositionsReader.parseExpiry("ABCDEFP00380000")).isNull();
  }

  // ---------------------------------------------------------------------------
  // openPositions filtering
  // ---------------------------------------------------------------------------

  @Test
  void filtersOutExpiredPositionFromOpenPositionsAndNotional() {
    // Yesterday-expired OCC (ET) is dropped; a not-yet-expired OCC is kept.
    LocalDate today = LocalDate.now(MARKET_TZ);
    String expiredOcc = occFor("TSLA", today.minusDays(1), "P", "00380000");
    String liveOcc = occFor("NVDA", today.plusDays(7), "C", "00140000");

    wireStrategies("acme", "s1");
    wireListExecutions("wf-expired", "wf-live");
    wireState("wf-expired", new PositionStateView(expiredOcc, 25, new BigDecimal("1.12")));
    wireState("wf-live", new PositionStateView(liveOcc, 3, new BigDecimal("2.00")));

    List<OpenPosition> out = reader.openPositions("acme");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).contractSymbol()).isEqualTo(liveOcc);
    // The dropped expired position contributes nothing to notional.
    assertThat(out.get(0).openNotional()).isEqualByComparingTo("600.00"); // 2.00 * 3 * 100
  }

  @Test
  void keepsTodayExpiringPositionUntilTheDayAfter() {
    // An option expiring TODAY is still "open" until the calendar day passes (filter uses
    // isBefore).
    LocalDate today = LocalDate.now(MARKET_TZ);
    String todayOcc = occFor("SPY", today, "C", "00500000");

    wireStrategies("acme", "s1");
    wireListExecutions("wf-today");
    wireState("wf-today", new PositionStateView(todayOcc, 2, new BigDecimal("1.00")));

    List<OpenPosition> out = reader.openPositions("acme");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).contractSymbol()).isEqualTo(todayOcc);
  }

  @Test
  void keepsUnparseableOccFailOpen() {
    // A garbled OCC must NOT be hidden (could be a real live position the operator needs to see).
    wireStrategies("acme", "s1");
    wireListExecutions("wf-garbled");
    wireState("wf-garbled", new PositionStateView("NOT-AN-OCC", 4, new BigDecimal("1.50")));

    List<OpenPosition> out = reader.openPositions("acme");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).contractSymbol()).isEqualTo("NOT-AN-OCC");
  }

  // ---------------------------------------------------------------------------
  // armed trailing-stop state (drives /live's per-position stop badge)
  // ---------------------------------------------------------------------------

  @Test
  void carriesTheArmedTrailingStopThroughToTheOpenPosition() {
    LocalDate today = LocalDate.now(MARKET_TZ);
    String occ = occFor("DRAM", today.plusDays(30), "C", "00100000");

    wireStrategies("acme", "s1");
    wireListExecutions("wf-armed");
    wireState("wf-armed", new PositionStateView(occ, 2, new BigDecimal("3.28")));
    wireTrailing(
        "wf-armed", new TrailingStateView(true, new BigDecimal("0.35"), new BigDecimal("2.63")));

    List<OpenPosition> out = reader.openPositions("acme");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).trailingArmed()).isTrue();
    assertThat(out.get(0).trailGivebackPct()).isEqualByComparingTo("0.35");
    // Passed through verbatim. 2.63 is PEAK-anchored and is NOT derivable from any price this
    // reader holds: entry 3.28 x 0.65 = 2.13. A reader that recomputed the stop would fail here.
    assertThat(out.get(0).trailStopPrice()).isEqualByComparingTo("2.63");
  }

  @Test
  void reportsNoTrailWhenThePositionIsUnarmed() {
    LocalDate today = LocalDate.now(MARKET_TZ);
    String occ = occFor("NVDA", today.plusDays(7), "C", "00140000");

    wireStrategies("acme", "s1");
    wireListExecutions("wf-unarmed");
    wireState("wf-unarmed", new PositionStateView(occ, 3, new BigDecimal("2.00")));
    wireTrailing("wf-unarmed", new TrailingStateView(false, null, null));

    List<OpenPosition> out = reader.openPositions("acme");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).trailingArmed()).isFalse();
    assertThat(out.get(0).trailGivebackPct()).isNull();
    assertThat(out.get(0).trailStopPrice()).isNull();
  }

  @Test
  void neverDropsThePositionWhenTheTrailingStopQueryFails() {
    // The badge is decoration; the position is not. An orchestrator too old to answer
    // trailingState, or a query that races a worker restart, must cost the operator the BADGE and
    // nothing else — a holdings row vanishing because a stop could not be read would be a far worse
    // failure than the one this feature fixes.
    LocalDate today = LocalDate.now(MARKET_TZ);
    String occ = occFor("NVDA", today.plusDays(7), "C", "00140000");

    wireStrategies("acme", "s1");
    wireListExecutions("wf-trail-broken");
    wireState("wf-trail-broken", new PositionStateView(occ, 3, new BigDecimal("2.00")));
    WorkflowStub stub = client.newUntypedWorkflowStub("wf-trail-broken");
    when(stub.query(eq("trailingState"), eq(TrailingStateView.class), any(Object[].class)))
        .thenThrow(new IllegalStateException("query rejected: worker restarting"));

    List<OpenPosition> out = reader.openPositions("acme");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).contractSymbol()).isEqualTo(occ);
    assertThat(out.get(0).openNotional()).isEqualByComparingTo("600.00");
    assertThat(out.get(0).trailingArmed()).isFalse();
    assertThat(out.get(0).trailStopPrice()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static String occFor(String root, LocalDate expiry, String right, String strike8) {
    return String.format("%-6s%s%s%s", root, expiry.format(YYMMDD), right, strike8);
  }

  private void wireStrategies(String tenant, String... strategyIds) {
    when(strategyResolver.strategyIdsForTenant(tenant)).thenReturn(List.of(strategyIds));
  }

  private void wireListExecutions(String... workflowIds) {
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv ->
                Stream.of(workflowIds)
                    .map(PositionsReaderTest::metadata)
                    .map(m -> (WorkflowExecutionMetadata) m));
  }

  private void wireState(String workflowId, PositionStateView state) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(workflowId))).thenReturn(stub);
    when(stub.query(eq("positionState"), eq(PositionStateView.class), any(Object[].class)))
        .thenReturn(state);
  }

  /**
   * Stubs the SECOND per-workflow query. Left unstubbed, the mock answers null, which the reader
   * reads as "no trail" — the same degradation an orchestrator predating the query produces, and
   * why every pre-existing test in this class still passes untouched.
   */
  private void wireTrailing(String workflowId, TrailingStateView trailing) {
    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
    when(stub.query(eq("trailingState"), eq(TrailingStateView.class), any(Object[].class)))
        .thenReturn(trailing);
  }

  private static WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }
}
