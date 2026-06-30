package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
  // The account-snapshot dispatch routes to broker-<target>; tests pin alpaca-paper.
  private static final String BROKER_TARGET = "alpaca-paper";
  private static final String BROKER_QUEUE = "broker-" + BROKER_TARGET;

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private TenantConfigActivities tenantConfig;
  private AccountPnlActivities accountPnl;
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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(accountPnl.accountOpenBook(anyString())).thenReturn(new AccountOpenBook(List.of(), 0, 0));
    when(cascade.cascadeAccountRiskBreach(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));

    coreWorker.registerActivitiesImplementations(
        audit, calendar, tenantConfig, accountPnl, cascade);

    // GetOptionQuoteActivity is routed to the market-data task queue from the workflow stub.
    Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
    mdWorker.registerActivitiesImplementations(optionQuote);

    // AccountSnapshotActivity (SOD equity) is routed to broker-<target> from the workflow stub.
    Worker brokerWorker = env.newWorker(BROKER_QUEUE);
    brokerWorker.registerActivitiesImplementations(accountSnapshot);

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
    verify(cascade, timeout(2000).atLeastOnce())
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
    verify(cascade, timeout(2000).atLeastOnce())
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

    verify(cascade, timeout(2000).times(1))
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

    verify(cascade, timeout(2000).times(1))
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

  // ---------- pct-of-SOD-equity cap (the change) ----------

  // pct=0.40, SOD equity=5000 => effective threshold 2000. A total loss of -2000 trips; -1999
  // does not. (Drive the loss purely via realized so the book/quote path stays trivial.)
  @Test
  void pctConfigured_tripsAtFortyPctOfSodEquity() {
    when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);
    when(tenantConfig.accountDailyLossPct(anyString())).thenReturn(new BigDecimal("0.40"));
    when(accountSnapshot.accountSnapshot(any())).thenReturn(snapshot(new BigDecimal("5000")));

    // -1999 > -2000 -> no trip.
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
        .thenReturn(new BigDecimal("-1999"));
    AccountKillSwitchWorkflow below = newStub("t-dev/account/killswitch-pct-below");
    WorkflowStub.fromTyped(below).start(input());
    env.sleep(Duration.ofSeconds(75));
    assertThat(below.killswitchState().getTripped()).isFalse();

    // -2000 <= -2000 -> trip.
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any())).thenReturn(BigDecimal.ZERO);

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
    when(accountPnl.computeTenantRealizedPnl(anyString(), any()))
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
