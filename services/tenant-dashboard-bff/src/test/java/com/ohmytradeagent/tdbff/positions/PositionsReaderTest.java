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

  private static WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }
}
