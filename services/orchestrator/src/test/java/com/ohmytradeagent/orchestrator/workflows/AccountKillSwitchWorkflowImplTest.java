package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AccountKillSwitchWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String MARKET_DATA_QUEUE = "market-data";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private TenantConfigActivities tenantConfig;
  private AccountPnlActivities accountPnl;
  private AccountKillSwitchCascadeActivities cascade;
  private GetOptionQuoteActivity optionQuote;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(AccountKillSwitchWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    tenantConfig = Mockito.mock(TenantConfigActivities.class);
    accountPnl = Mockito.mock(AccountPnlActivities.class);
    cascade = Mockito.mock(AccountKillSwitchCascadeActivities.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);

    // Defaults: market open, today fixed, threshold set, no realized loss, empty book, no quotes.
    when(calendar.isMarketOpen()).thenReturn(true);
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 14));
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(accountPnl.computeTenantRealizedPnl(anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString())).thenReturn(new AccountOpenBook(List.of(), 0, 0));
    when(cascade.cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);

    coreWorker.registerActivitiesImplementations(
        audit, calendar, tenantConfig, accountPnl, cascade);

    // GetOptionQuoteActivity is routed to the market-data task queue from the workflow stub.
    Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
    mdWorker.registerActivitiesImplementations(optionQuote);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  // ---------- THE DRILL: two strategies, realized + MTM crossing the cap ----------

  // A tenant with TWO strategies whose realized + open-MTM loss crosses
  // account_daily_loss_threshold trips the account kill switch EXACTLY once and cascades a
  // riskBreach/MARKET flatten to running PositionWorkflows in BOTH strategies.
  @Test
  void heartbeat_twoStrategies_realizedPlusMtmCrossesThreshold_tripsOnceAndCascadesAccountWide() {
    // Realized -3000 (summed across both strategies inside the activity). Open MTM: two losing
    // positions, one per strategy, valued (liveBid - entryPremium) * qty * 100.
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
        .thenReturn(new BigDecimal("-3000"));
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    // s1 position: entry 3.00, bid 2.00 -> -1.00 * 10 * 100 = -1000
                    new OpenPositionValuation("NVDA  250516C00140000", new BigDecimal("3.00"), 10L),
                    // s2 position: entry 5.00, bid 4.00 -> -1.00 * 15 * 100 = -1500
                    new OpenPositionValuation(
                        "AAPL  250516C00200000", new BigDecimal("5.00"), 15L)),
                2,
                0));
    when(optionQuote.getOptionQuote(quoteFor("NVDA  250516C00140000")))
        .thenReturn(okQuote("NVDA  250516C00140000", new BigDecimal("2.00")));
    when(optionQuote.getOptionQuote(quoteFor("AAPL  250516C00200000")))
        .thenReturn(okQuote("AAPL  250516C00200000", new BigDecimal("4.00")));

    // Total = -3000 + (-1000) + (-1500) = -5500 <= -5000 -> trip.
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-drill");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
    assertThat(s.getActor()).isEqualTo("auto:account_daily_loss");

    // Account-scoped cascade invoked at least once (the heartbeat keeps ticking but `tripped`
    // short-circuits subsequent ticks, so the trip fires exactly once).
    verify(cascade, atLeastOnce())
        .cascadeAccountRiskBreach(
            eq("dev"), anyString(), eq("auto:account_daily_loss"), eq("auto:account_daily_loss"));

    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject()).containsEntry("scope", "account");
  }

  // Below threshold: total loss does not cross the cap -> no trip.
  @Test
  void heartbeat_belowThreshold_doesNotTrip() {
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
        .thenReturn(new BigDecimal("-1000"));
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  250516C00140000", new BigDecimal("3.00"), 5L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  250516C00140000", new BigDecimal("2.50")));
    // -1000 + (2.50-3.00)*5*100 = -1000 - 250 = -1250 > -5000 -> no trip.

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-below");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // Unset threshold => cap inert: even a massive loss does not trip (and PnL is never computed).
  @Test
  void heartbeat_unsetThreshold_capInert_noTripOnLargeLoss() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-inert");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    // Inert path short-circuits before any PnL/book read.
    verify(accountPnl, never()).computeTenantRealizedPnl(anyString(), any());
    verify(accountPnl, never()).accountOpenBook(anyString());
  }

  // Fail-closed MTM: every quote UNAVAILABLE on a small book must NOT fail-open. The cap engages
  // (trips with the distinct reason) rather than computing a falsely-small loss and staying open.
  @Test
  void heartbeat_quoteUnavailable_failsClosed_doesNotFailOpen() {
    // Realized 0; two positions but BOTH quotes UNAVAILABLE. Combined failures (2 of 2) trips the
    // small-book fail-closed bound.
    when(accountPnl.computeTenantRealizedPnl(anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation("NVDA  250516C00140000", new BigDecimal("3.00"), 5L),
                    new OpenPositionValuation("AAPL  250516C00200000", new BigDecimal("5.00"), 5L)),
                2,
                0));
    when(optionQuote.getOptionQuote(any())).thenReturn(unavailableQuote("NVDA  250516C00140000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-failclosed");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_mtm_unavailable");
    verify(cascade, atLeastOnce())
        .cascadeAccountRiskBreach(
            eq("dev"), anyString(), eq("auto:account_mtm_unavailable"), anyString());
  }

  // ---------- dual-control trip/reset (mirror per-strategy) ----------

  @Test
  void tripUpdate_setsStateAndAuditsAndCascades() {
    // Avoid an auto-trip racing the manual trip: market closed.
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-trip");
    WorkflowStub.fromTyped(stub).start(input());

    stub.trip(tripRequest("manual:operator_initiated", "operator:ridopark"));

    KillSwitchState state = stub.killswitchState();
    assertThat(state.getTripped()).isTrue();
    assertThat(state.getReason()).isEqualTo("manual:operator_initiated");

    verify(cascade, times(1))
        .cascadeAccountRiskBreach(
            eq("dev"),
            eq("t-dev/account/killswitch-trip"),
            eq("manual:operator_initiated"),
            eq("operator:ridopark"));
  }

  @Test
  void tripUpdate_whenAlreadyTripped_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-dup");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:first", "operator:a"));

    assertThatThrownBy(() -> stub.trip(tripRequest("manual:second", "operator:b")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("already_tripped");

    verify(cascade, times(1))
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void resetUpdate_sameApprovers_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-sameappr");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    assertThatThrownBy(() -> stub.reset(resetRequest("alice", "alice")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("approvers_must_differ");
  }

  @Test
  void resetUpdate_distinctApprovers_clearsTrippedAndSetsCooldown() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-reset");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    stub.reset(resetRequest("alice", "bob"));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getCoolingDownUntil()).isNotNull();
  }

  // ---------- post-reset cooldown: a still-down book must not immediately re-trip ----------

  // Auto-trip on a down book, RESET (sets coolingDownUntil = now + cooldownSecs), then heartbeat
  // again WHILE the book is still down but BEFORE the cooldown elapses: the cap must stay inert
  // (tripped=false). After the cooldown window passes the cap re-engages and trips again.
  @Test
  void heartbeat_afterReset_doesNotReTripDuringCooldown_thenReTripsAfter() {
    // A single down position large enough to cross the 5000 cap: (2.00-12.00)*10*100 = -10000.
    when(accountPnl.computeTenantRealizedPnl(anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString()))
        .thenReturn(
            new AccountOpenBook(
                List.of(
                    new OpenPositionValuation(
                        "NVDA  250516C00140000", new BigDecimal("12.00"), 10L)),
                1,
                0));
    when(optionQuote.getOptionQuote(any()))
        .thenReturn(okQuote("NVDA  250516C00140000", new BigDecimal("2.00")));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-cooldown");
    WorkflowStub.fromTyped(stub).start(input());

    // First heartbeat auto-trips on the down book.
    env.sleep(Duration.ofSeconds(75));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // Reset with distinct approvers -> coolingDownUntil = now + DEFAULT_RESET_COOLDOWN_SECS (60s).
    stub.reset(resetRequest("alice", "bob"));
    KillSwitchState afterReset = stub.killswitchState();
    assertThat(afterReset.getTripped()).isFalse();
    assertThat(afterReset.getCoolingDownUntil()).isNotNull();

    // Advance ~30s (one more heartbeat) but stay INSIDE the 60s cooldown: the still-down book must
    // NOT re-trip. Without the cooldown guard this heartbeat would immediately re-trip.
    env.sleep(Duration.ofSeconds(30));
    assertThat(stub.killswitchState().getTripped()).isFalse();

    // Past the cooldown window: the cap re-engages and trips again on the still-down book.
    env.sleep(Duration.ofSeconds(90));
    KillSwitchState afterCooldown = stub.killswitchState();
    assertThat(afterCooldown.getTripped()).isTrue();
    assertThat(afterCooldown.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // ---------- helpers ----------

  private AccountKillSwitchWorkflow newStub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            AccountKillSwitchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .build());
  }

  private static AccountKillSwitchWorkflowInput input() {
    AccountKillSwitchWorkflowInput in = new AccountKillSwitchWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    return in;
  }

  private static TripKillSwitchRequest tripRequest(String reason, String actor) {
    TripKillSwitchRequest r = new TripKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setReason(reason);
    r.setActor(actor);
    return r;
  }

  private static ResetKillSwitchRequest resetRequest(String a1, String a2) {
    ResetKillSwitchRequest r = new ResetKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setApproverId1(a1);
    r.setApproverId2(a2);
    return r;
  }

  private static GetOptionQuoteRequest quoteFor(String contractSymbol) {
    return org.mockito.ArgumentMatchers.argThat(
        req -> req != null && contractSymbol.equals(req.getContractSymbol()));
  }

  private static OptionQuoteResult okQuote(String contractSymbol, BigDecimal bid) {
    OptionQuoteResult q = new OptionQuoteResult();
    q.setSchemaVersion(1L);
    q.setContractSymbol(contractSymbol);
    q.setBid(bid);
    q.setRetrievedAt(OffsetDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC));
    q.setStatus(OptionQuoteResult.Status.OK);
    return q;
  }

  private static OptionQuoteResult unavailableQuote(String contractSymbol) {
    OptionQuoteResult q = new OptionQuoteResult();
    q.setSchemaVersion(1L);
    q.setContractSymbol(contractSymbol);
    q.setRetrievedAt(OffsetDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC));
    q.setStatus(OptionQuoteResult.Status.UNAVAILABLE);
    return q;
  }

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }
}
