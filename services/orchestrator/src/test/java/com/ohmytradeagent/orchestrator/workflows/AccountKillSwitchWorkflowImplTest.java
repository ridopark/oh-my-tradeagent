package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.activities.TenantStrategyBrokerTarget;
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
  // The account-snapshot dispatch routes to broker-<target>; tests pin alpaca-paper.
  private static final String BROKER_TARGET = "alpaca-paper";
  private static final String BROKER_QUEUE = "broker-" + BROKER_TARGET;

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private TenantConfigActivities tenantConfig;
  private AccountPnlActivities accountPnl;
  private DailyPnlExecActivity execPnl;
  private AccountKillSwitchCascadeActivities cascade;
  private GetOptionQuoteActivity optionQuote;
  private AccountSnapshotActivity accountSnapshot;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(AccountKillSwitchWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    tenantConfig = Mockito.mock(TenantConfigActivities.class);
    accountPnl = Mockito.mock(AccountPnlActivities.class);
    execPnl = Mockito.mock(DailyPnlExecActivity.class);
    cascade = Mockito.mock(AccountKillSwitchCascadeActivities.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);
    accountSnapshot = Mockito.mock(AccountSnapshotActivity.class);

    // Defaults: market open, today fixed, ABSOLUTE threshold set (legacy path so the existing
    // suite is unchanged), no pct, no realized loss, empty book, no quotes.
    when(calendar.isMarketOpen()).thenReturn(true);
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 14));
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);
    when(tenantConfig.tenantBrokerTarget(anyString())).thenReturn(BROKER_TARGET);
    // Phase 2: v>=1 sums a per-strategy realized read routed to each strategy's broker queue. By
    // default the tenant has ONE strategy on alpaca-paper; the exec realized read returns zero.
    when(accountPnl.tenantStrategyBrokerTargets(anyString()))
        .thenReturn(List.of(new TenantStrategyBrokerTarget("s1", BROKER_TARGET)));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString())).thenReturn(new AccountOpenBook(List.of(), 0, 0));
    when(cascade.cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));

    coreWorker.registerActivitiesImplementations(
        audit, calendar, tenantConfig, accountPnl, cascade);

    // GetOptionQuoteActivity is routed to the market-data task queue from the workflow stub.
    Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
    mdWorker.registerActivitiesImplementations(optionQuote);

    // AccountSnapshotActivity (SOD equity) + the v>=1 realized read (DailyPnlExecActivity) both
    // route to broker-<target> from the workflow stub. Register the exec realized read on BOTH the
    // paper and live queues here (workers must be created BEFORE env.start()) so the mixed
    // broker_target tests can route s2 to broker-alpaca-live without a post-start newWorker.
    Worker brokerWorker = env.newWorker(BROKER_QUEUE);
    brokerWorker.registerActivitiesImplementations(accountSnapshot, execPnl);
    Worker brokerLiveWorker = env.newWorker("broker-alpaca-live");
    brokerLiveWorker.registerActivitiesImplementations(execPnl);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  // ---------- THE DRILL: two strategies, realized + MTM crossing the cap ----------

  // A tenant with TWO strategies whose realized + open-MTM loss crosses
  // account_daily_loss_threshold trips the account kill switch EXACTLY once, HALTS + pages, and —
  // per the Phase 2 (PLAN-2026-07-15) no-auto-flatten policy — does NOT cascade a MARKET flatten
  // (fresh execution == v>=1). The trip subject carries flatten=manual so the operator is paged to
  // flatten by hand.
  @Test
  void heartbeat_twoStrategies_realizedPlusMtmCrossesThreshold_tripsOnceNoAutoFlatten() {
    // Phase 2 (C4): a tenant with TWO strategies on DIFFERENT broker_targets. The account path
    // routes each per-strategy realized read to its OWN broker queue and sums them:
    //   s1 (alpaca-paper) realized -1200 + s2 (alpaca-live) realized -1800 = -3000.
    // The exec activity is registered on both broker queues in setUp; route s2 to the live one.
    when(accountPnl.tenantStrategyBrokerTargets(anyString()))
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget("s1", "alpaca-paper"),
                new TenantStrategyBrokerTarget("s2", "alpaca-live")));
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s1"), any()))
        .thenReturn(new BigDecimal("-1200"));
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s2"), any()))
        .thenReturn(new BigDecimal("-1800"));
    // Open MTM: two losing positions valued (liveBid - entryPremium) * qty * 100.
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

    // Phase 2 (PLAN-2026-07-15): NO auto-flatten — the account-scoped cascade is never dispatched.
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());

    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject())
        .containsEntry("scope", "account")
        .containsEntry("flatten", "manual");
  }

  // Below threshold: total loss does not cross the cap -> no trip.
  @Test
  void heartbeat_belowThreshold_doesNotTrip() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
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
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-inert");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    // Inert path short-circuits before any PnL/book read.
    verify(execPnl, never()).computeRealizedPnl(anyString(), anyString(), any());
    verify(accountPnl, never()).accountOpenBook(anyString());
  }

  // Fail-closed MTM: every quote UNAVAILABLE on a small book must NOT fail-open. The cap engages
  // (trips with the distinct reason) rather than computing a falsely-small loss and staying open.
  @Test
  void heartbeat_quoteUnavailable_failsClosed_doesNotFailOpen() {
    // Realized 0; two positions but BOTH quotes UNAVAILABLE. Combined failures (2 of 2) trips the
    // small-book fail-closed bound.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
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
    // Phase 2 (PLAN-2026-07-15): a fail-closed trip also halts + pages but no longer auto-flattens.
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // ---------- dual-control trip/reset (mirror per-strategy) ----------

  @Test
  void tripUpdate_setsStateAndAudits_noAutoFlatten() {
    // Avoid an auto-trip racing the manual trip: market closed.
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-trip");
    WorkflowStub.fromTyped(stub).start(input());

    stub.trip(tripRequest("manual:operator_initiated", "operator:ridopark"));

    KillSwitchState state = stub.killswitchState();
    assertThat(state.getTripped()).isTrue();
    assertThat(state.getReason()).isEqualTo("manual:operator_initiated");

    // Phase 2 (PLAN-2026-07-15): the no-auto-flatten policy applies to manual trips too — the
    // cascade is never dispatched and the trip subject carries flatten=manual.
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject()).containsEntry("flatten", "manual");
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

    // No auto-flatten under the Phase 2 policy (the second trip is rejected by the validator).
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void resetUpdate_whenNotTripped_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-resetfirst");
    WorkflowStub.fromTyped(stub).start(input());

    assertThatThrownBy(() -> stub.reset(resetRequest("alice")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("not_tripped");
  }

  @Test
  void resetUpdate_blankApprover_rejectedByValidator() {
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-blank");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    assertThatThrownBy(() -> stub.reset(resetRequest("")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("approver_id_1_required");
  }

  @Test
  void resetUpdate_singleOperator_clearsTrippedAndSetsCooldown() {
    // Single-operator account reset: untrips, arms cooldown, emits KillSwitchResetApproved whose
    // subject carries approver_id_1 + via=manual_reset + cooling_down_until + cooldown_secs and NO
    // approver_id_2 (dual control retired).
    when(calendar.isMarketOpen()).thenReturn(false);
    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-reset");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    stub.reset(resetRequest("alice"));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getCoolingDownUntil()).isNotNull();

    AuditEvent reset = captureKind("KillSwitchResetApproved");
    assertThat(reset.getSubject())
        .containsEntry("approver_id_1", "alice")
        .containsEntry("via", "manual_reset")
        .containsKey("cooling_down_until")
        .containsKey("cooldown_secs")
        .doesNotContainKey("approver_id_2");
  }

  // ---------- post-reset cooldown: a still-down book must not immediately re-trip ----------

  // Auto-trip on a down book, RESET (sets coolingDownUntil = now + cooldownSecs), then heartbeat
  // again WHILE the book is still down but BEFORE the cooldown elapses: the cap must stay inert
  // (tripped=false). After the cooldown window passes the cap re-engages and trips again.
  @Test
  void heartbeat_afterReset_doesNotReTripDuringCooldown_thenReTripsAfter() {
    // A single down position large enough to cross the 5000 cap: (2.00-12.00)*10*100 = -10000.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
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

    // Single-operator reset -> coolingDownUntil = now + DEFAULT_RESET_COOLDOWN_SECS (60s).
    stub.reset(resetRequest("alice"));
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

  // ---------- pct-of-SOD-equity cap (the change) ----------

  // pct=0.40, SOD equity=5000 => effective threshold 2000. A total loss of -2000 trips; -1999
  // does not. (Drive the loss purely via realized so the book/quote path stays trivial.)
  @Test
  void pctConfigured_tripsAtFortyPctOfSodEquity() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));

    // -1999 > -2000 -> no trip.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-1999"));
    AccountKillSwitchWorkflow below = newStub("t-dev/account/killswitch-pct-below");
    WorkflowStub.fromTyped(below).start(input());
    env.sleep(Duration.ofSeconds(75));
    assertThat(below.killswitchState().getTripped()).isFalse();

    // -2000 <= -2000 -> trip.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-2000"));
    AccountKillSwitchWorkflow at = newStub("t-dev/account/killswitch-pct-at");
    WorkflowStub.fromTyped(at).start(input());
    env.sleep(Duration.ofSeconds(75));
    KillSwitchState s = at.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
  }

  // When BOTH pct and absolute are set, the pct x SOD-equity threshold wins. pct=0.40 x 5000 =
  // 2000 (NOT the absolute 5000), so a -2500 loss trips even though it is under the absolute cap.
  @Test
  void pctTakesPrecedenceOverAbsolute() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-2500"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-pct-precedence");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isTrue();
  }

  // Only the absolute threshold is set (pct unset) => legacy behavior, no SOD-equity read at all.
  @Test
  void pctUnset_absoluteStillWorks() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-6000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-abs-only");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isTrue();
    // Pct unset => the SOD-equity dispatch never fires.
    verify(accountSnapshot, never()).accountSnapshot(any());
  }

  // Neither pct nor absolute set => inert: never trips, no PnL/equity reads.
  @Test
  void bothUnset_inert() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-both-unset");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(accountSnapshot, never()).accountSnapshot(any());
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // SOD equity unavailable + pct configured + NO absolute fallback => fail-SAFE DEFER: the account
  // cap is skipped this tick (no trip), the heartbeat survives, and the equity read is retried on
  // the next tick.
  @Test
  void sodEquityUnavailable_pctConfigured_defersDoesNotTripOrCrash() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // Equity read fails (broker outage) -> snapshot throws on every attempt.
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("equity_unavailable"));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-999999"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-equity-down");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(135));

    // No trip on an unknown base, and the heartbeat keeps ticking (state query still answers).
    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    // Retried across multiple heartbeats (more than one tick attempted the read).
    verify(accountSnapshot, atLeast(2)).accountSnapshot(any());
  }

  // The SOD-equity snapshot is taken ONCE per trading day, not every heartbeat. Several heartbeats
  // within one day => exactly one equity read; a day rollover re-snapshots.
  @Test
  void sodEquitySnapshotOncePerDay() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));
    // Stay well under the cap so it never trips and the heartbeat keeps looping.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-snap-once");
    WorkflowStub.fromTyped(stub).start(input());

    // Three heartbeats on the same trading day => exactly one equity read.
    env.sleep(Duration.ofSeconds(190));
    verify(accountSnapshot, times(1)).accountSnapshot(any());

    // Roll the trading day forward => the next heartbeat re-snapshots SOD equity.
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 15));
    env.sleep(Duration.ofSeconds(70));
    verify(accountSnapshot, times(2)).accountSnapshot(any());
  }

  // ---------- continue-as-new sod_equity carry-forward ----------

  // The pure carry-forward builder honors the rolling-deploy schema_version branch: v3 (with
  // sod_equity populated) ONLY when a SOD equity was captured; otherwise v2 (old-worker-safe) and
  // sod_equity absent.
  @Test
  void carryForwardInput_bumpsSchemaV3OnlyWhenSodEquityCaptured() {
    LocalDate day = LocalDate.of(2026, 5, 14);

    AccountKillSwitchWorkflowInput withEquity =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev", false, "", "", null, null, day, new BigDecimal("5000"));
    assertThat(withEquity.getSchemaVersion()).isEqualTo(3L);
    assertThat(withEquity.getSodEquity()).isEqualByComparingTo(new BigDecimal("5000"));

    AccountKillSwitchWorkflowInput noEquity =
        AccountKillSwitchWorkflowImpl.carryForwardInput(
            "dev", false, "", "", null, null, day, null);
    assertThat(noEquity.getSchemaVersion()).isEqualTo(2L);
    assertThat(noEquity.getSodEquity()).isNull();
  }

  // A workflow STARTED FROM a continue-as-new carry-forward input that already carries sod_equity
  // (v3) restores it at @WorkflowInit and does NOT re-dispatch the broker AccountSnapshot on the
  // post-CAN run — the same-day equity survives the CAN. The pct cap still works off the carried
  // value (pct=0.40 x carried 5000 = 2000 -> a -2000 loss trips).
  @Test
  void sodEquityCarriedAcrossCan_restoresWithoutRedispatch() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // If the workflow were to (incorrectly) re-read equity, it would get 9999 — proving any trip is
    // off the CARRIED 5000 (threshold 2000), not a fresh read (threshold 3999.6).
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("9999")));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-2000"));

    // Carried input as continueAsNew would emit it: v3, sod_equity=5000, same trading day. Built by
    // hand (NOT via carryForwardInput) so this restore assertion is independent of the builder the
    // unit test pins — a builder regression cannot mask itself through this fixture.
    AccountKillSwitchWorkflowInput carried = new AccountKillSwitchWorkflowInput();
    carried.setSchemaVersion(3L);
    carried.setTenantId("dev");
    carried.setTradingDay(LocalDate.of(2026, 5, 14));
    carried.setSodEquity(new BigDecimal("5000"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-can-restore");
    WorkflowStub.fromTyped(stub).start(carried);
    env.sleep(Duration.ofSeconds(75));

    // Trips off the CARRIED equity (5000 -> threshold 2000; -2000 <= -2000).
    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:account_daily_loss");
    // The carried sodEquity was restored => NO broker snapshot re-dispatch on the post-CAN run.
    verify(accountSnapshot, never()).accountSnapshot(any());
  }

  // ---------- cap-inactive observability alert (PR #504 follow-up) ----------

  // pct configured + SOD equity unavailable for >= INACTIVE_ALERT_TICKS consecutive ticks => emit
  // exactly ONE AccountKillSwitchCapInactive audit (the Discord alert trigger), NOT one per tick.
  @Test
  void pctConfigured_sodEquityDownForNTicks_emitsCapInactiveOnce() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("equity_unavailable"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-cap-inactive");
    WorkflowStub.fromTyped(stub).start(input());

    // Run well past INACTIVE_ALERT_TICKS (3) but inside the re-page window (30): exactly one alert.
    env.sleep(Duration.ofSeconds(8 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);
    // Recovery not yet reached.
    assertThat(countKind("AccountKillSwitchCapReArmed")).isEqualTo(0L);
  }

  // A single transient defer (fewer than INACTIVE_ALERT_TICKS) must NOT alert.
  @Test
  void pctConfigured_singleTransientDefer_doesNotAlert() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // Fail the FIRST snapshot, then succeed: equity becomes available, cap arms — counter never
    // reaches the alert threshold.
    when(accountSnapshot.accountSnapshot(any()))
        .thenThrow(new IllegalStateException("blip"))
        .thenReturn(snapshot(new BigDecimal("5000")));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-transient");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(5 * 60));

    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(0L);
  }

  // Recovery: after alerting inactive, equity comes back => cap arms => emit ONE re-arm audit and
  // reset, so a subsequent outage can alert inactive again (counter reset proven by re-alert).
  @Test
  void capInactiveThenRecovers_emitsReArmAndResetsCounter() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    // Equity DOWN on every call (defer every tick) — drive by time, not call-count, since the
    // 3-attempt retry policy inflates per-tick call counts.
    when(accountSnapshot.accountSnapshot(any())).thenThrow(new IllegalStateException("down"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-recovers");
    WorkflowStub.fromTyped(stub).start(input());

    // Past INACTIVE_ALERT_TICKS -> one inactive alert.
    env.sleep(Duration.ofSeconds(6 * 60));
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);

    // Equity recovers -> the next arm tick emits the re-arm audit and resets the counter. Use
    // doReturn (not when(...).thenReturn) to re-stub a currently-throwing mock without invoking it.
    Mockito.doReturn(snapshot(new BigDecimal("5000"))).when(accountSnapshot).accountSnapshot(any());
    env.sleep(Duration.ofSeconds(3 * 60));

    assertThat(countKind("AccountKillSwitchCapReArmed")).isEqualTo(1L);
    assertThat(stub.killswitchState().getTripped()).isFalse();
  }

  // pct NOT configured (absolute-only, or nothing) => never alerts, no behavior change. Here the
  // absolute cap is set and the book is empty: the cap arms every tick, never inactive.
  @Test
  void pctNotConfigured_neverAlertsInactive() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(null);

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-abs-noalert");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(8 * 60));

    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(0L);
    assertThat(countKind("AccountKillSwitchCapReArmed")).isEqualTo(0L);
  }

  // (b) An unroutable/bare broker_target must DEGRADE to a defer (no throw, no heartbeat error
  // spam) and feed the inactive counter — proving the stub-build/fromValue moved inside the try.
  @Test
  void bareBrokerTarget_degradesToDeferAndFeedsInactiveCounter() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    // A legacy bare target: ExecActivitiesFactory.taskQueueFor rejects it (no worker polls
    // broker-paper); pre-fix this threw out of captureSodEquity -> heartbeat error. Now it defers.
    when(tenantConfig.tenantBrokerTarget(anyString())).thenReturn("paper");

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-bare-target");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(8 * 60));

    // No heartbeat-error spam (the bare target degraded inside captureSodEquity, not at run()).
    assertThat(countKind("KillSwitchHeartbeatError")).isEqualTo(0L);
    // The defer fed the inactive counter and crossed the threshold -> exactly one inactive alert.
    assertThat(countKind("AccountKillSwitchCapInactive")).isEqualTo(1L);
    assertThat(stub.killswitchState().getTripped()).isFalse();
  }

  // ---------- Phase 2 (C4/G2): account fail-CLOSED on a per-strategy realized read failure
  // ----------

  // Two strategies on different broker_targets; the SECOND per-strategy realized read throws. G2
  // FORBIDS a partial sum — the whole tenant compute defers (no trip on a partial/unknown number),
  // even though the first strategy alone would already cross the cap.
  @Test
  void heartbeat_perStrategyRealizedReadFails_failsClosed_noPartialSumNoTrip() {
    // The exec activity is registered on both broker queues in setUp.
    when(accountPnl.tenantStrategyBrokerTargets(anyString()))
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget("s1", "alpaca-paper"),
                new TenantStrategyBrokerTarget("s2", "alpaca-live")));
    // s1 alone (-6000) would cross the 5000 cap; s2's read is unavailable. Must NOT sum the partial
    // and trip — a partial under-counts the loss (here it would OVER-count, but the invariant is
    // "never trip on an incomplete tenant sum"): defer instead.
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s1"), any()))
        .thenReturn(new BigDecimal("-6000"));
    when(execPnl.computeRealizedPnl(eq("dev"), eq("s2"), any()))
        .thenThrow(new IllegalStateException("exec live journal unavailable"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-failclosed-realized");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(75));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
  }

  // C6/G1: a persistent per-strategy realized-read outage on v>=1 never trips (missing number is
  // not a loss) and, past REALIZED_READ_FAILURE_ALERT_TICKS, emits ONE distinct bounded alert.
  @Test
  void heartbeat_realizedReadOutage_doesNotTrip_thenAlertsAfterThreshold() {
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("exec journal unavailable"));

    AccountKillSwitchWorkflow stub = newStub("t-dev/account/killswitch-realized-outage");
    WorkflowStub.fromTyped(stub).start(input());
    env.sleep(Duration.ofSeconds(4 * 60));

    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString());
    assertThat(countKind("KillSwitchRealizedReadUnavailable")).isEqualTo(1L);
  }

  // ---------- helpers ----------

  private static AccountSnapshotResult snapshot(BigDecimal equity) {
    AccountSnapshotResult r = new AccountSnapshotResult();
    r.setSchemaVersion(1L);
    r.setEquity(equity);
    return r;
  }

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

  private static ResetKillSwitchRequest resetRequest(String a1) {
    ResetKillSwitchRequest r = new ResetKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setApproverId1(a1);
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

  /** Counts audit events of {@code kind} logged so far (0 if none) — never throws. */
  private long countKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    Mockito.verify(audit, Mockito.atLeast(0)).log(captor.capture());
    return captor.getAllValues().stream().filter(e -> kind.equals(e.getKind())).count();
  }
}
