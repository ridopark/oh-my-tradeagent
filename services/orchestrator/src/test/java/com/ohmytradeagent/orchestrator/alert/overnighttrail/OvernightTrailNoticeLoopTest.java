package com.ohmytradeagent.orchestrator.alert.overnighttrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import com.ohmytradeagent.orchestrator.workflows.TrailingState;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #747 Phase 4 (Fork N): at 15:40 ET, one read-only {@code OvernightTrailHeld} notice per armed
 * trail that will be held overnight.
 */
class OvernightTrailNoticeLoopTest {

  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final TenantStrategy PAIR =
      new TenantStrategy("prod_real", "watchlist-trigger-v1");
  private static final String WF = "t-prod_real/s-watchlist-trigger-v1/pos/SPY   260825C00640000/x";
  private static final String SPY = "SPY   260825C00640000";

  private StrategyRegistry registry;
  private WorkflowClient client;
  private WorkflowStub stub;
  private MarketDataOptionQuoteClient quotes;
  private final List<AuditEvent> audits = new CopyOnWriteArrayList<>();
  private StrategyConfig config;

  @BeforeEach
  void setUp() {
    registry = mock(StrategyRegistry.class);
    client = mock(WorkflowClient.class);
    stub = mock(WorkflowStub.class);
    quotes = mock(MarketDataOptionQuoteClient.class);
    config = new StrategyConfig();
    when(registry.list()).thenReturn(List.of(PAIR));
    when(registry.get(PAIR.tenantId(), PAIR.strategyId())).thenReturn(config);
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of(meta(WF)));
    when(client.newUntypedWorkflowStub(WF)).thenReturn(stub);
    position(SPY, 3);
    trail(true, "1.99", "0.25", "1.4925");
    when(quotes.optionQuote(SPY))
        .thenReturn(new OptionQuote(new BigDecimal("1.60"), new BigDecimal("1.65"), null));
  }

  /** The 2026-08-18 SPY shape: armed, peak 1.99, giveback 0.25, expiry 2026-08-25, at 15:40. */
  @Test
  void armedTrailHeldOvernight_emitsOneNotice() {
    loopAt(2026, 8, 18).runOnce();

    assertThat(audits)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getKind()).isEqualTo("OvernightTrailHeld");
              assertThat(e.getTenantId()).isEqualTo("prod_real");
              assertThat(e.getStrategyId()).isEqualTo("watchlist-trigger-v1");
              assertThat(e.getWorkflowId()).isEqualTo(WF);
              assertThat(e.getSubject())
                  .containsEntry("contract_symbol", SPY)
                  .containsEntry("remaining_qty", 3L)
                  .containsEntry("bid", new BigDecimal("1.60"))
                  .containsEntry("threshold", new BigDecimal("1.4925"))
                  .containsEntry("giveback_pct", new BigDecimal("0.25"))
                  .containsEntry("peak_premium", new BigDecimal("1.99"))
                  .containsEntry("distance_pct", new BigDecimal("0.0672"))
                  .containsEntry("note", OvernightTrailNoticeLoop.NOTE);
            });
  }

  @Test
  void unarmed_noNotice() {
    trail(false, null, null, null);
    loopAt(2026, 8, 18).runOnce();
    assertThat(audits).isEmpty();
  }

  @Test
  void expiresToday_noNotice() {
    loopAt(2026, 8, 25).runOnce();
    assertThat(audits).isEmpty();
  }

  @Test
  void eodForceFlatten_noNotice() {
    config.setEodForceFlatten(true);
    loopAt(2026, 8, 18).runOnce();
    assertThat(audits).isEmpty();
  }

  @Test
  void secondTickSameEtDate_noDuplicate() {
    OvernightTrailNoticeLoop loop = loopAt(2026, 8, 18);
    loop.runOnce();
    loop.runOnce();
    assertThat(audits).hasSize(1);
  }

  /** The notice matters more than the number. */
  @Test
  void quoteUnavailable_stillNotifiesWithNullBid() {
    when(quotes.optionQuote(SPY)).thenReturn(null);
    loopAt(2026, 8, 18).runOnce();
    assertThat(audits)
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getSubject())
                    .containsEntry("bid", null)
                    .containsEntry("distance_pct", null)
                    .containsEntry("threshold", new BigDecimal("1.4925")));
  }

  @Test
  void queryFailure_skipsThatPositionOnly() {
    String other = "t-prod_real/s-watchlist-trigger-v1/pos/QQQ   260829C00560000/y";
    WorkflowStub otherStub = mock(WorkflowStub.class);
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of(meta(WF), meta(other)));
    when(client.newUntypedWorkflowStub(other)).thenReturn(otherStub);
    when(stub.query("trailingState", TrailingState.class))
        .thenThrow(new IllegalStateException("query rejected"));
    when(otherStub.query("positionState", PositionState.class))
        .thenReturn(new PositionState("QQQ   260829C00560000", 2, new BigDecimal("2.00")));
    when(otherStub.query("trailingState", TrailingState.class))
        .thenReturn(
            new TrailingState(
                true,
                new BigDecimal("3.00"),
                new BigDecimal("0.30"),
                new BigDecimal("2.10"),
                null,
                null,
                null,
                0));

    loopAt(2026, 8, 18).runOnce();

    assertThat(audits)
        .singleElement()
        .satisfies(e -> assertThat(e.getWorkflowId()).isEqualTo(other));
  }

  @Test
  void disabledByFlag_noNotice() {
    OvernightTrailNoticeLoop loop =
        new OvernightTrailNoticeLoop(
            registry, client, quotes, audits::add, clockAt(2026, 8, 18), false);
    loop.notifyOvernightTrails();
    assertThat(audits).isEmpty();
  }

  private OvernightTrailNoticeLoop loopAt(int y, int m, int d) {
    AuditActivities audit = audits::add;
    return new OvernightTrailNoticeLoop(registry, client, quotes, audit, clockAt(y, m, d), true);
  }

  private static Clock clockAt(int y, int m, int d) {
    return Clock.fixed(ZonedDateTime.of(y, m, d, 15, 40, 0, 0, ET).toInstant(), ET);
  }

  private void position(String occ, long qty) {
    when(stub.query("positionState", PositionState.class))
        .thenReturn(new PositionState(occ, qty, new BigDecimal("1.20")));
  }

  private void trail(boolean armed, String peak, String giveback, String threshold) {
    when(stub.query("trailingState", TrailingState.class))
        .thenReturn(
            new TrailingState(
                armed,
                peak == null ? null : new BigDecimal(peak),
                giveback == null ? null : new BigDecimal(giveback),
                threshold == null ? null : new BigDecimal(threshold),
                null,
                null,
                null,
                0));
  }

  private static WorkflowExecutionMetadata meta(String wfId) {
    WorkflowExecutionMetadata m = mock(WorkflowExecutionMetadata.class);
    when(m.getExecution()).thenReturn(WorkflowExecution.newBuilder().setWorkflowId(wfId).build());
    return m;
  }
}
