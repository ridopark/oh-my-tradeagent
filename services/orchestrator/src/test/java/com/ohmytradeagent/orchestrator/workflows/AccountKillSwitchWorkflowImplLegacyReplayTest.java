package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
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
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

/**
 * Replay-determinism coverage for BOTH version gates added to {@link
 * AccountKillSwitchWorkflowImpl}: {@code account-daily-loss-pct-of-sod-equity-v1} (the pct cap, in
 * {@code heartbeat()}) and {@code account-cap-inactive-alert-v1} (the cap-inactive observability
 * audit/alert, in {@code run()}). Both gates are read every tick under the new impl; a pre-change
 * recorded history carries NEITHER marker, so {@code getVersion(...)} resolves both to {@link
 * Workflow#DEFAULT_VERSION} on replay and the command stream stays byte-identical to the legacy
 * path (no SOD-equity dispatch, no cap-inactive audit). {@link TestWorkflowEnvironment} always
 * reports {@code getVersion(...) == 1} for fresh workflows, so the legacy {@code v ==
 * DEFAULT_VERSION} branch is unreachable from a round-trip test — only {@link WorkflowReplayer}
 * against a recorded pre-marker history exercises it.
 *
 * <p><b>Two fixtures, two pre-change paths:</b>
 *
 * <ul>
 *   <li>{@link #legacyAbsoluteThresholdHistoryReplaysCleanly} — the null-threshold opt-out path
 *       (sparse: {@code todayEt} → {@code isMarketOpen} → {@code accountDailyLossThreshold} →
 *       return). The regression guard for the gates' existence: omit either {@code getVersion} and
 *       the recorded legacy ticks diverge (the new marker / SOD-equity / cap-inactive commands
 *       would be scheduled against a history that has none) → {@code NonDeterministicException}.
 *   <li>{@link #legacyAbsoluteWithOpenPositionsHistoryReplaysCleanly} — the command-DENSE path the
 *       sparse fixture does not exercise: a valid absolute threshold → {@code
 *       computeTenantRealizedPnl} → {@code accountOpenBook} → per-position {@code getOptionQuote}
 *       (market-data queue) → no trip. This is where a command-ordering divergence is most likely,
 *       so byte-identity is pinned here too.
 * </ul>
 *
 * <p>Fixture production: a genuine pre-marker history is synthesised by {@link
 * LegacyHeartbeatEmulatorWorkflowImpl} (sparse) / {@link
 * LegacyHeartbeatOpenPositionsEmulatorWorkflowImpl} (dense), which mirror {@link
 * AccountKillSwitchWorkflowImpl}'s {@code run()} + {@code heartbeat()} command stream EXACTLY as it
 * was BEFORE the change but WITHOUT any {@code getVersion} marker call and without the
 * continue-as-new tail. The history is captured WHILE THE WORKFLOW IS STILL RUNNING (the loop is
 * infinite, like production) via {@link WorkflowClient#fetchHistory(String)}, so it ends on a
 * {@code WorkflowTaskCompleted} — matching a real in-flight kill-switch history. Regenerate with
 * {@code -Dgenerate.legacy.fixture=true}.
 */
class AccountKillSwitchWorkflowImplLegacyReplayTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String MARKET_DATA_QUEUE = "market-data";

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/account-killswitch-pre-pct-nullthreshold-legacy-history.json";
  private static final Path FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "account-killswitch-pre-pct-nullthreshold-legacy-history.json");
  private static final String EMULATOR_WORKFLOW_ID = "account-killswitch-pre-pct-emulator";

  private static final String OPEN_POS_FIXTURE_RESOURCE =
      "temporal/replay/account-killswitch-pre-pct-openpositions-legacy-history.json";
  private static final Path OPEN_POS_FIXTURE_SOURCE_PATH =
      Path.of(
          "src/test/resources/temporal/replay/"
              + "account-killswitch-pre-pct-openpositions-legacy-history.json");
  private static final String OPEN_POS_EMULATOR_WORKFLOW_ID =
      "account-killswitch-pre-pct-openpositions-emulator";

  /**
   * Pins the version-marker constant value so a rename in {@link AccountKillSwitchWorkflowImpl}
   * fails this test loudly. Renaming the literal would silently re-version live executions.
   */
  @Test
  void versionAccountDailyLossPctConstantNameIsStable() throws Exception {
    Field marker =
        AccountKillSwitchWorkflowImpl.class.getDeclaredField("VERSION_ACCOUNT_DAILY_LOSS_PCT");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("account-daily-loss-pct-of-sod-equity-v1");
  }

  /**
   * Pins the cap-inactive-alert marker so a rename fails loudly (re-versioning live executions).
   */
  @Test
  void versionCapInactiveAlertConstantNameIsStable() throws Exception {
    Field marker =
        AccountKillSwitchWorkflowImpl.class.getDeclaredField("VERSION_CAP_INACTIVE_ALERT");
    marker.setAccessible(true);
    assertThat((String) marker.get(null)).isEqualTo("account-cap-inactive-alert-v1");
  }

  /**
   * The command-DENSE pre-change path: a valid absolute threshold whose heartbeat ran {@code
   * computeTenantRealizedPnl} → {@code accountOpenBook} → per-position {@code getOptionQuote} → no
   * trip, with NO version markers. Replayed under the new impl, both gates resolve to {@code
   * DEFAULT_VERSION} and the (command-heavy) stream matches byte-for-byte. This covers the
   * divergence-prone path the sparse null-threshold fixture cannot.
   */
  @Test
  void legacyAbsoluteWithOpenPositionsHistoryReplaysCleanly() throws Exception {
    assertThat(getClass().getClassLoader().getResource(OPEN_POS_FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=AccountKillSwitchWorkflowImplLegacyReplayTest#regenerateOpenPositionsFixture"
                + " -Dsurefire.failIfNoSpecifiedTests=false`",
            OPEN_POS_FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        OPEN_POS_FIXTURE_RESOURCE, AccountKillSwitchWorkflowImpl.class);
  }

  /**
   * THE SENTINEL. A pre-change history whose heartbeat ticks took the legacy null-threshold {@code
   * return} path with NO {@code account-daily-loss-pct-of-sod-equity-v1} marker. Replay under the
   * new impl: the gate resolves to {@code DEFAULT_VERSION}, the absolute-threshold path runs, and
   * the command stream matches byte-for-byte → no {@code NonDeterministicException}. Omit the
   * {@code getVersion} gate and this replay throws (the new marker / SOD-equity branch would
   * diverge from the recorded legacy ticks). This is the regression guard.
   */
  @Test
  void legacyAbsoluteThresholdHistoryReplaysCleanly() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as(
            "Missing fixture resource %s. Regenerate with"
                + " `mvn -pl services/orchestrator test -Dgenerate.legacy.fixture=true"
                + " -Dtest=AccountKillSwitchWorkflowImplLegacyReplayTest#regenerateFixture"
                + " -Dsurefire.failIfNoSpecifiedTests=false`",
            FIXTURE_RESOURCE)
        .isNotNull();

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        FIXTURE_RESOURCE, AccountKillSwitchWorkflowImpl.class);
  }

  // ---------------------------------------------------------------------------
  // Fixture regeneration
  // ---------------------------------------------------------------------------

  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyHeartbeatEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      TenantConfigActivities tenantConfig = Mockito.mock(TenantConfigActivities.class);
      AccountPnlActivities accountPnl = Mockito.mock(AccountPnlActivities.class);
      AccountKillSwitchCascadeActivities cascade =
          Mockito.mock(AccountKillSwitchCascadeActivities.class);

      when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 6, 14));
      when(calendar.isMarketOpen()).thenReturn(true);
      // Legacy null-threshold opt-out path: the heartbeat returns before any PnL/book read.
      when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(null);

      worker.registerActivitiesImplementations(audit, calendar, tenantConfig, accountPnl, cascade);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      AccountKillSwitchWorkflow wf =
          client.newWorkflowStub(
              AccountKillSwitchWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(EMULATOR_WORKFLOW_ID)
                  .build());
      WorkflowStub.fromTyped(wf).start(input());

      // A few ticks (60s interval). The infinite loop never completes; capture the running history.
      env.sleep(Duration.ofMinutes(5));

      json = client.fetchHistory(EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  @Test
  @EnabledIfSystemProperty(named = "generate.legacy.fixture", matches = "true")
  void regenerateOpenPositionsFixture() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    String json;
    try {
      Worker worker = env.newWorker(CORE_QUEUE);
      worker.registerWorkflowImplementationTypes(
          LegacyHeartbeatOpenPositionsEmulatorWorkflowImpl.class);

      AuditActivities audit = Mockito.mock(AuditActivities.class);
      MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
      TenantConfigActivities tenantConfig = Mockito.mock(TenantConfigActivities.class);
      AccountPnlActivities accountPnl = Mockito.mock(AccountPnlActivities.class);
      AccountKillSwitchCascadeActivities cascade =
          Mockito.mock(AccountKillSwitchCascadeActivities.class);
      GetOptionQuoteActivity optionQuote = Mockito.mock(GetOptionQuoteActivity.class);

      when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 6, 14));
      when(calendar.isMarketOpen()).thenReturn(true);
      // Valid absolute threshold -> the command-dense path runs (pnl + book + per-position quotes).
      when(tenantConfig.accountDailyLossThreshold(anyString())).thenReturn(new BigDecimal("5000"));
      when(accountPnl.computeTenantRealizedPnl(anyString(), any())).thenReturn(BigDecimal.ZERO);
      when(accountPnl.accountOpenBook(anyString()))
          .thenReturn(
              new AccountOpenBook(
                  List.of(
                      new OpenPositionValuation(
                          "NVDA  250516C00140000", new BigDecimal("3.00"), 5L)),
                  1,
                  0));
      // Non-breaching quote so the dense path completes WITHOUT a trip (records a clean tick
      // stream).
      when(optionQuote.getOptionQuote(any()))
          .thenReturn(okQuote("NVDA  250516C00140000", new BigDecimal("2.90")));

      worker.registerActivitiesImplementations(audit, calendar, tenantConfig, accountPnl, cascade);
      Worker mdWorker = env.newWorker(MARKET_DATA_QUEUE);
      mdWorker.registerActivitiesImplementations(optionQuote);
      env.start();

      WorkflowClient client = env.getWorkflowClient();
      AccountKillSwitchWorkflow wf =
          client.newWorkflowStub(
              AccountKillSwitchWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(CORE_QUEUE)
                  .setWorkflowId(OPEN_POS_EMULATOR_WORKFLOW_ID)
                  .build());
      WorkflowStub.fromTyped(wf).start(input());

      env.sleep(Duration.ofMinutes(5));
      json = client.fetchHistory(OPEN_POS_EMULATOR_WORKFLOW_ID).toJson(true);
    } finally {
      env.close();
    }

    assertThat(WorkflowExecutionHistory.fromJson(json).getEvents()).isNotEmpty();
    Files.createDirectories(OPEN_POS_FIXTURE_SOURCE_PATH.getParent());
    Files.writeString(OPEN_POS_FIXTURE_SOURCE_PATH, json, StandardCharsets.UTF_8);
  }

  private static OptionQuoteResult okQuote(String contractSymbol, BigDecimal bid) {
    OptionQuoteResult q = new OptionQuoteResult();
    q.setSchemaVersion(1L);
    q.setContractSymbol(contractSymbol);
    q.setBid(bid);
    q.setRetrievedAt(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC));
    q.setStatus(OptionQuoteResult.Status.OK);
    return q;
  }

  // ---------------------------------------------------------------------------
  // Legacy emulator — mirrors the PRE-change run()+heartbeat() command stream EXACTLY
  // ---------------------------------------------------------------------------

  /**
   * Mirrors {@link AccountKillSwitchWorkflowImpl}'s {@code run()} + {@code heartbeat()} command
   * stream as it was BEFORE the pct change. Implements {@link AccountKillSwitchWorkflow} so the
   * recorded {@code workflowType.name} is {@code AccountKillSwitchWorkflow} — what {@link
   * WorkflowReplayer} expects when registering {@link AccountKillSwitchWorkflowImpl}.
   *
   * <p>Per-tick command sequence (identical to the pre-change heartbeat, MINUS the {@code
   * getVersion("account-daily-loss-pct-of-sod-equity-v1", ...)} marker): {@code
   * Workflow.sleep(60s)} → {@code calendar.todayEt()} → (not-tripped, no cooldown) → {@code
   * calendar.isMarketOpen()} → {@code tenantConfig.accountDailyLossThreshold(...)} → return on null
   * threshold. No {@code getVersion} call is made, so the recorded history contains no marker.
   */
  public static class LegacyHeartbeatEmulatorWorkflowImpl implements AccountKillSwitchWorkflow {
    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

    private final MarketCalendarActivities calendar =
        Workflow.newActivityStub(MarketCalendarActivities.class, OPTS);
    private final TenantConfigActivities tenantConfig =
        Workflow.newActivityStub(TenantConfigActivities.class, OPTS);

    private final AccountKillSwitchWorkflowInput input;
    private boolean tripped;
    private LocalDate tradingDay;

    @WorkflowInit
    public LegacyHeartbeatEmulatorWorkflowImpl(AccountKillSwitchWorkflowInput in) {
      this.input = in;
    }

    @Override
    public String run(AccountKillSwitchWorkflowInput in) {
      if (this.tradingDay == null) {
        this.tradingDay = calendar.todayEt();
      }
      while (true) {
        Workflow.sleep(Duration.ofSeconds(60));
        legacyHeartbeat();
      }
    }

    /** The PRE-change heartbeat body — identical to production minus the getVersion marker. */
    private void legacyHeartbeat() {
      LocalDate today = calendar.todayEt();
      if (!today.equals(tradingDay)) {
        this.tradingDay = today;
      }
      if (tripped) {
        return;
      }
      if (!calendar.isMarketOpen()) {
        return;
      }
      BigDecimal threshold = tenantConfig.accountDailyLossThreshold(input.getTenantId());
      if (threshold == null || threshold.signum() <= 0) {
        // Legacy null-threshold opt-out — NO marker, NO PnL. This is the path the sentinel records.
        return;
      }
      // Valid-threshold tail unused by the fixture (the null-threshold opt-out short-circuits).
      this.tripped = true;
    }

    // The remaining surface is unused by the emulator (the fixture only drives the heartbeat loop).
    @Override
    public void tripValidator(TripKillSwitchRequest request) {}

    @Override
    public void trip(TripKillSwitchRequest request) {}

    @Override
    public void resetValidator(ResetKillSwitchRequest request) {}

    @Override
    public void reset(ResetKillSwitchRequest request) {}

    @Override
    public KillSwitchState killswitchState() {
      KillSwitchState s = new KillSwitchState();
      s.setSchemaVersion(1L);
      s.setTripped(tripped);
      return s;
    }
  }

  /**
   * Command-DENSE pre-change emulator: mirrors the PRE-change {@code heartbeat()} valid-threshold
   * path EXACTLY (the path the sparse null-threshold emulator never reaches), MINUS the {@code
   * getVersion} markers. Per tick: {@code sleep} → {@code todayEt} → (not tripped, no cooldown) →
   * {@code isMarketOpen} → {@code accountDailyLossThreshold} → {@code computeTenantRealizedPnl} →
   * {@code accountOpenBook} → per-position {@code getOptionQuote} (market-data queue) → fail-closed
   * bound → non-breaching total → NO trip. Implements {@link AccountKillSwitchWorkflow} so the
   * recorded {@code workflowType.name} matches what {@link WorkflowReplayer} registers.
   */
  public static class LegacyHeartbeatOpenPositionsEmulatorWorkflowImpl
      implements AccountKillSwitchWorkflow {
    private static final String ACCOUNT_SCOPE = "__account__";
    private static final String MARKET_DATA_TASK_QUEUE = "market-data";
    private static final java.math.BigDecimal CONTRACT_MULTIPLIER =
        com.ohmytradeagent.orchestrator.domain.Sizing.CONTRACT_MULTIPLIER;
    private static final int RELATIVE_FAILURE_THRESHOLD_MULTIPLIER = 2;
    private static final int SMALL_BOOK_MAX_POSITIONS = 2;

    private static final ActivityOptions OPTS =
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
    private static final ActivityOptions QUOTE_OPTS =
        ActivityOptions.newBuilder()
            .setTaskQueue(MARKET_DATA_TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .build();

    private final MarketCalendarActivities calendar =
        Workflow.newActivityStub(MarketCalendarActivities.class, OPTS);
    private final TenantConfigActivities tenantConfig =
        Workflow.newActivityStub(TenantConfigActivities.class, OPTS);
    private final AccountPnlActivities accountPnl =
        Workflow.newActivityStub(AccountPnlActivities.class, OPTS);
    private final GetOptionQuoteActivity optionQuote =
        Workflow.newActivityStub(GetOptionQuoteActivity.class, QUOTE_OPTS);

    private final AccountKillSwitchWorkflowInput input;
    private boolean tripped;
    private LocalDate tradingDay;

    @WorkflowInit
    public LegacyHeartbeatOpenPositionsEmulatorWorkflowImpl(AccountKillSwitchWorkflowInput in) {
      this.input = in;
    }

    @Override
    public String run(AccountKillSwitchWorkflowInput in) {
      if (this.tradingDay == null) {
        this.tradingDay = calendar.todayEt();
      }
      while (true) {
        Workflow.sleep(Duration.ofSeconds(60));
        legacyHeartbeat();
      }
    }

    private void legacyHeartbeat() {
      LocalDate today = calendar.todayEt();
      if (!today.equals(tradingDay)) {
        this.tradingDay = today;
      }
      if (tripped) {
        return;
      }
      if (!calendar.isMarketOpen()) {
        return;
      }
      BigDecimal threshold = tenantConfig.accountDailyLossThreshold(input.getTenantId());
      if (threshold == null || threshold.signum() <= 0) {
        return;
      }
      BigDecimal realized = accountPnl.computeTenantRealizedPnl(input.getTenantId(), tradingDay);
      AccountOpenBook book = accountPnl.accountOpenBook(input.getTenantId());
      BigDecimal openMtm = BigDecimal.ZERO;
      int quoteFailures = 0;
      for (OpenPositionValuation pos : book.positions()) {
        BigDecimal bid = liveBid(pos.contractSymbol());
        if (bid == null) {
          quoteFailures++;
          continue;
        }
        BigDecimal perContract = bid.subtract(pos.entryPremium());
        openMtm =
            openMtm.add(
                perContract
                    .multiply(BigDecimal.valueOf(pos.remainingQty()))
                    .multiply(CONTRACT_MULTIPLIER));
      }
      int combinedFailures = book.valueFailures() + quoteFailures;
      if (book.listed() > 0 && failsClosed(book.listed(), combinedFailures)) {
        this.tripped = true;
        return;
      }
      BigDecimal totalPnl = realized.add(openMtm);
      if (totalPnl.compareTo(threshold.negate()) <= 0) {
        this.tripped = true;
      }
    }

    private BigDecimal liveBid(String contractSymbol) {
      GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
      qreq.setSchemaVersion(1L);
      qreq.setTenantId(input.getTenantId());
      qreq.setStrategyId(ACCOUNT_SCOPE);
      qreq.setContractSymbol(contractSymbol);
      OptionQuoteResult quote = optionQuote.getOptionQuote(qreq);
      if (quote == null
          || quote.getStatus() != OptionQuoteResult.Status.OK
          || quote.getBid() == null) {
        return null;
      }
      return quote.getBid();
    }

    private static boolean failsClosed(int listed, int failures) {
      boolean exceedsRelative = (long) failures * RELATIVE_FAILURE_THRESHOLD_MULTIPLIER > listed;
      boolean tripsSmallBookFloor = listed <= SMALL_BOOK_MAX_POSITIONS && failures >= 1;
      return exceedsRelative || tripsSmallBookFloor;
    }

    @Override
    public void tripValidator(TripKillSwitchRequest request) {}

    @Override
    public void trip(TripKillSwitchRequest request) {}

    @Override
    public void resetValidator(ResetKillSwitchRequest request) {}

    @Override
    public void reset(ResetKillSwitchRequest request) {}

    @Override
    public KillSwitchState killswitchState() {
      KillSwitchState s = new KillSwitchState();
      s.setSchemaVersion(1L);
      s.setTripped(tripped);
      return s;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static AccountKillSwitchWorkflowInput input() {
    AccountKillSwitchWorkflowInput in = new AccountKillSwitchWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    return in;
  }
}
