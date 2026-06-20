package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.ArmDecision;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.FireDecision;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribeEquityActivity;
import com.ohmytradeagent.orchestrator.activities.TriggerFireDecider;
import com.ohmytradeagent.orchestrator.activities.WatchlistEntryDecider;
import com.ohmytradeagent.orchestrator.activities.WatchlistTriggerActivities;
import com.ohmytradeagent.orchestrator.activities.WatchlistTriggerLeg;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Temporal-test coverage for {@link WatchlistTriggerSessionWorkflowImpl} (Phase 5 parent). The pure
 * leg-mapping is covered in the activity/unit tests; here we exercise the fan-out wiring: enable
 * gate, one-child-per-arming-leg, arm:false drops a child, the fan-out cap, the
 * parent-does-not-pre-judge-crosses guarantee, malformed-leg skip-with-audit, deterministic child
 * ids + REJECT_DUPLICATE re-arm, and the EOD tolerant cancel.
 */
class WatchlistTriggerSessionWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";
  private static final String MD_QUEUE = WatchlistTriggerWorkflowImpl.MARKET_DATA_TASK_QUEUE;

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private WatchlistTriggerActivities parser;
  private WatchlistEntryDecider decider;
  private AccountSnapshotActivity accountSnapshot;

  // Child collaborators (registered so an armed child can start + idle until the EOD cancel).
  private RiskActivities risk;
  private ContractActivities contract;
  private TriggerFireDecider fireDecider;
  private SubscribeEquityActivity subscribeEquity;
  private GetOptionQuoteActivity optionQuote;
  private ExecActivities exec;
  private MarketCalendarActivity tradingCalendar;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(
        WatchlistTriggerSessionWorkflowImpl.class,
        WatchlistTriggerWorkflowImpl.class,
        PositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    parser = Mockito.mock(WatchlistTriggerActivities.class);
    decider = Mockito.mock(WatchlistEntryDecider.class);
    accountSnapshot = Mockito.mock(AccountSnapshotActivity.class);
    risk = Mockito.mock(RiskActivities.class);
    contract = Mockito.mock(ContractActivities.class);
    fireDecider = Mockito.mock(TriggerFireDecider.class);
    subscribeEquity = Mockito.mock(SubscribeEquityActivity.class);
    optionQuote = Mockito.mock(GetOptionQuoteActivity.class);
    exec = Mockito.mock(ExecActivities.class);
    tradingCalendar = Mockito.mock(MarketCalendarActivity.class);

    // Parent defaults: long EOD window, arm everything at 1x, $100k cash.
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    lenient()
        .when(decider.evaluateWatchlistEntry(any(), any()))
        .thenReturn(
            new ArmDecision().withArm(true).withSizeMultiplier(BigDecimal.ONE).withReason("arm"));
    lenient()
        .when(accountSnapshot.accountSnapshot(any()))
        .thenReturn(cash(new BigDecimal("100000")));

    // Child defaults (so a started child can idle until cancelled): nothing fires on its own.
    lenient()
        .when(fireDecider.evaluateTriggerFire(any(), any()))
        .thenReturn(
            new FireDecision()
                .withProceed(true)
                .withSizeMultiplier(BigDecimal.ONE)
                .withReason("x"));
    lenient()
        .when(risk.checkWatchlistEntry(any(), any(), any(), any(), any()))
        .thenReturn(RiskDecision.approved());
    lenient().when(optionQuote.getOptionQuote(any())).thenReturn(quoteOk());
    lenient().when(exec.placeOrder(any())).thenReturn(submitted());

    coreWorker.registerActivitiesImplementations(
        audit, calendar, parser, decider, risk, contract, fireDecider);
    Worker mdWorker = env.newWorker(MD_QUEUE);
    mdWorker.registerActivitiesImplementations(subscribeEquity, optionQuote);
    Worker brokerWorker = env.newWorker(BROKER_QUEUE);
    brokerWorker.registerActivitiesImplementations(exec, accountSnapshot, tradingCalendar);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void cleanParse_armsOneChildPerQualifyingLeg() {
    when(parser.parseWatchlistTriggers(any()))
        .thenReturn(List.of(okLeg("SPY", true), okLeg("SPY", false), okLeg("QQQ", true)));

    String result = run(config());

    assertThat(result).startsWith("armed=3;skipped=0");
    verify(decider, times(3)).evaluateWatchlistEntry(any(), any());
    assertThat(armedChildIds()).hasSize(3);
  }

  @Test
  void notCleanParse_armsNothing() {
    // not-clean => the parse activity returns an empty list.
    when(parser.parseWatchlistTriggers(any())).thenReturn(List.of());

    String result = run(config());

    assertThat(result).startsWith("armed=0;skipped=0");
    verify(decider, never()).evaluateWatchlistEntry(any(), any());
  }

  @Test
  void armFalse_thatChildNotStarted() {
    when(parser.parseWatchlistTriggers(any()))
        .thenReturn(List.of(okLeg("SPY", true), okLeg("QQQ", true)));
    when(decider.evaluateWatchlistEntry(any(), any()))
        .thenReturn(
            new ArmDecision().withArm(true).withSizeMultiplier(BigDecimal.ONE).withReason("a"))
        .thenReturn(
            new ArmDecision()
                .withArm(false)
                .withSizeMultiplier(BigDecimal.ZERO)
                .withReason("veto"));

    String result = run(config());

    assertThat(result).startsWith("armed=1;skipped=1");
    assertThat(armedChildIds()).hasSize(1);
  }

  @Test
  void fanoutCapEnforced_noStartBeyondCap() {
    List<WatchlistTriggerLeg> many = new ArrayList<>();
    for (int i = 0; i < WatchlistTriggerSessionWorkflowImpl.MAX_FANOUT_LEGS + 5; i++) {
      many.add(okLeg("T" + i, true));
    }
    when(parser.parseWatchlistTriggers(any())).thenReturn(many);

    String result = run(config());

    assertThat(result)
        .startsWith("armed=" + WatchlistTriggerSessionWorkflowImpl.MAX_FANOUT_LEGS + ";");
    verify(decider, times(WatchlistTriggerSessionWorkflowImpl.MAX_FANOUT_LEGS))
        .evaluateWatchlistEntry(any(), any());
    assertThat(auditKinds()).contains("WatchlistFanoutCapExceeded");
  }

  @Test
  void legAlreadyPastLevel_isStillArmed_parentDoesNotPreJudge() {
    // The parent has no price feed; it cannot and must not pre-judge a cross. A normal arm:true
    // leg is armed regardless of where the level sits — proven by exactly one child start.
    when(parser.parseWatchlistTriggers(any())).thenReturn(List.of(okLeg("SPY", true)));

    String result = run(config());

    assertThat(result).startsWith("armed=1;skipped=0");
    assertThat(armedChildIds()).hasSize(1);
  }

  @Test
  void enabledFalse_armsNothing() {
    when(parser.parseWatchlistTriggers(any())).thenReturn(List.of(okLeg("SPY", true)));
    StrategyConfig disabled = config();
    disabled.setEnabled(false);

    String result = run(disabled);

    assertThat(result).isEqualTo("armed=0;skipped=0;eod=false");
    verify(parser, never()).parseWatchlistTriggers(any());
    verify(decider, never()).evaluateWatchlistEntry(any(), any());
    assertThat(auditKinds()).contains("WatchlistSessionDisabled");
  }

  @Test
  void enabledNull_arms() {
    when(parser.parseWatchlistTriggers(any())).thenReturn(List.of(okLeg("SPY", true)));
    StrategyConfig nullEnabled = config();
    nullEnabled.setEnabled(null);

    String result = run(nullEnabled);

    assertThat(result).startsWith("armed=1;skipped=0");
  }

  @Test
  void eodCancel_toleratesAlreadyTerminalChild_withoutCrashing() {
    // EOD fires ~immediately. Children start, idle, then receive cancel; even if one had already
    // completed, the tolerant try/catch keeps the parent from crashing -> a clean summary returns.
    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofMillis(50));
    when(parser.parseWatchlistTriggers(any()))
        .thenReturn(List.of(okLeg("SPY", true), okLeg("QQQ", true)));

    WatchlistTriggerSessionWorkflow wf = stub("wl-session-eod");
    WorkflowStub.fromTyped(wf).start(input(config()));
    env.sleep(Duration.ofMinutes(1));
    String result = WorkflowStub.fromTyped(wf).getResult(String.class);

    assertThat(result).endsWith(";eod=true");
    assertThat(auditKinds()).contains("WatchlistSessionEod");
  }

  @Test
  void childIds_areDeterministic_andRejectDuplicateReArm() {
    when(parser.parseWatchlistTriggers(any())).thenReturn(List.of(okLeg("SPY", true)));

    run(config());
    List<String> ids = armedChildIds();
    assertThat(ids).containsExactly("t-dev/s-watchlist-trigger-v1/wl/2026-06-03/SPY/C");

    // Re-arm the SAME session id is idempotent (REJECT_DUPLICATE on the session id) — a second
    // start under the same workflow id is rejected by the test client.
    boolean rejected = false;
    try {
      WatchlistTriggerSessionWorkflow wf = stub("dup-session");
      WorkflowStub.fromTyped(wf).start(input(config()));
      WorkflowStub.fromTyped(wf).getResult(String.class);
      WatchlistTriggerSessionWorkflow again = stub("dup-session");
      WorkflowStub.fromTyped(again).start(input(config()));
    } catch (RuntimeException e) {
      rejected = true;
    }
    assertThat(rejected).isTrue();
  }

  @Test
  void malformedLeg_skippedAndAudited_otherLegsArmed() {
    WatchlistTriggerLeg bad = new WatchlistTriggerLeg(null, "SPY", "P", "malformed_strike:xx");
    when(parser.parseWatchlistTriggers(any()))
        .thenReturn(List.of(okLeg("SPY", true), bad, okLeg("QQQ", true)));

    String result = run(config());

    assertThat(result).startsWith("armed=2;skipped=1");
    assertThat(armedChildIds()).hasSize(2);
    AuditEvent skip = lastAudit("WatchlistLegSkipped");
    assertThat(skip.getSubject()).containsEntry("ticker", "SPY");
    assertThat(skip.getSubject()).containsEntry("right", "P");
  }

  // ---------- helpers ----------

  private String run(StrategyConfig config) {
    WatchlistTriggerSessionWorkflow wf = stub("wl-session-" + System.nanoTime());
    return wf.run(input(config));
  }

  private WatchlistTriggerSessionWorkflow stub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            WatchlistTriggerSessionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(
                    io.temporal.api.enums.v1.WorkflowIdReusePolicy
                        .WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
  }

  private static WatchlistTriggerSessionWorkflowInput input(StrategyConfig config) {
    return new WatchlistTriggerSessionWorkflowInput(source(), config);
  }

  private static WatchlistMirrorPayload source() {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("watchlist-trigger-v1");
    p.setEtDate(LocalDate.of(2026, 6, 3));
    p.setAuthor("TradingTheTrend");
    p.setRawText("ignored — parse is mocked");
    p.setSourceMessageId("msg-1");
    return p;
  }

  private static WatchlistTriggerLeg okLeg(String ticker, boolean call) {
    WatchlistTriggerPayload p = new WatchlistTriggerPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("watchlist-trigger-v1");
    p.setTicker(ticker);
    p.setDirection(
        call ? WatchlistTriggerPayload.Direction.ABOVE : WatchlistTriggerPayload.Direction.BELOW);
    p.setTrigger(new BigDecimal("100.00"));
    p.setStrike(new BigDecimal("100"));
    p.setRight(call ? WatchlistTriggerPayload.Right.C : WatchlistTriggerPayload.Right.P);
    p.setAction(WatchlistTriggerPayload.Action.BTO);
    p.setEtDate(LocalDate.of(2026, 6, 3));
    p.setSourceMessageId("msg-1");
    return new WatchlistTriggerLeg(p, ticker, call ? "C" : "P", null);
  }

  private static StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("watchlist-trigger-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    c.setMinContracts(1L);
    c.setMaxContracts(50L);
    c.setEntryMode(StrategyConfig.EntryMode.BREAKOUT);
    return c;
  }

  private static AccountSnapshotResult cash(BigDecimal amount) {
    AccountSnapshotResult r = new AccountSnapshotResult();
    r.setSchemaVersion(1L);
    r.setCash(amount);
    r.setEquity(amount);
    return r;
  }

  private static OptionQuoteResult quoteOk() {
    OptionQuoteResult r = new OptionQuoteResult();
    r.setSchemaVersion(1L);
    r.setContractSymbol("X");
    r.setBid(new BigDecimal("3.10"));
    r.setMid(new BigDecimal("3.15"));
    r.setAsk(new BigDecimal("3.20"));
    r.setRetrievedAt(OffsetDateTime.now());
    r.setStatus(OptionQuoteResult.Status.OK);
    return r;
  }

  private static OrderIntentResult submitted() {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey("x:entry");
    r.setBrokerOrderId("brk-1");
    r.setState(OrderIntentResult.State.SUBMITTED);
    r.setLastStateAt(OffsetDateTime.now());
    return r;
  }

  private List<String> armedChildIds() {
    List<String> ids = new ArrayList<>();
    for (AuditEvent e : allAudits()) {
      if ("WatchlistLegArmed".equals(e.getKind())) {
        ids.add(String.valueOf(e.getSubject().get("child_workflow_id")));
      }
    }
    return ids;
  }

  private List<String> auditKinds() {
    List<String> kinds = new ArrayList<>();
    for (AuditEvent e : allAudits()) {
      kinds.add(e.getKind());
    }
    return kinds;
  }

  private AuditEvent lastAudit(String kind) {
    return allAudits().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit kind=" + kind));
  }

  private List<AuditEvent> allAudits() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues();
  }
}
