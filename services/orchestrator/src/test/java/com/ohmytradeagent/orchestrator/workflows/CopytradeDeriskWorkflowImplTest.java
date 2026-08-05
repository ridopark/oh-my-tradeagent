package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeDeriskPayload;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * PLAN-2026-08-04-copytrade-derisk-followup-cue (Phase 2) — TDD spec for {@link
 * CopytradeDeriskWorkflowImpl}. Incident: on 2026-07-31 a copytrade author posted a BTO then, in a
 * separate message with no BTO grammar, an "I'm cool with going 0 or hero ... use your own stop"
 * escalation. Nothing acted on it and the INTC 8/03 95c expired worthless (−$6,700/tenant). This
 * workflow, when enabled per-tenant, trims the attributed open position and arms the chandelier
 * trail on the remainder.
 *
 * <p>Harness mirrors {@link CopytradeSignalWorkflowImplTest}: a {@link TestWorkflowEnvironment}
 * with mocked audit/strategy/contract activities and a parking {@link ParkingPositionWorkflowImpl}
 * double as the live external-signal target (so partialExit/armChandelier dispatch does not throw).
 * The parent-side dispatch audits ({@code DeriskTrimRequested} carrying the trim fraction, {@code
 * DeriskArmRequested} carrying the giveback) are the deterministic assertion surface — the same
 * convention the STC trail test uses.
 */
class CopytradeDeriskWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String OCC = "INTC  260803C00095000";
  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String TARGET_BTO_SIGNAL_ID = "bto-intc-1";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private StrategyActivities strategy;
  private ContractActivities contract;

  @BeforeEach
  void setUp() {
    ParkingPositionWorkflowImpl.reset();
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(
        CopytradeDeriskWorkflowImpl.class, ParkingPositionWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    contract = Mockito.mock(ContractActivities.class);
    coreWorker.registerActivitiesImplementations(audit, strategy, contract);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void incidentRepro_enabledConfig_trimsAndArms() {
    // Enabled: keep 25% (trim 75%), trail giveback 30% — the operator-agreed canary values.
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(Boolean.TRUE, new BigDecimal("0.25"), new BigDecimal("0.30")));
    stubResolve();
    String posWfId = startLiveTarget();

    String result = runWorkflow(deriskPayload());
    assertThat(result).isEqualTo("cue-msg-1:derisk");

    AuditEvent trim = capture("DeriskTrimRequested");
    assertThat(trim.getSubject()).containsEntry("signal_id", "cue-msg-1:derisk");
    assertThat(trim.getSubject()).containsEntry("target_bto_signal_id", TARGET_BTO_SIGNAL_ID);
    assertThat(trim.getSubject()).containsEntry("position_workflow_id", posWfId);
    assertThat(((Number) trim.getSubject().get("fraction")).doubleValue()).isEqualTo(0.75);
    assertThat(((Number) trim.getSubject().get("keep_fraction")).doubleValue()).isEqualTo(0.25);

    AuditEvent arm = capture("DeriskArmRequested");
    assertThat(arm.getSubject()).containsEntry("signal_id", "cue-msg-1:derisk");
    assertThat(arm.getSubject()).containsEntry("position_workflow_id", posWfId);
    assertThat(((Number) arm.getSubject().get("peak_premium")).doubleValue()).isEqualTo(1.34);
    assertThat(((Number) arm.getSubject().get("giveback_pct")).doubleValue()).isEqualTo(0.30);
  }

  @Test
  void disabledNull_noSignals_oneSkippedDisabledAudit() {
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(null, new BigDecimal("0.25"), new BigDecimal("0.30")));
    stubResolve();
    startLiveTarget();

    runWorkflow(deriskPayload());

    AuditEvent skipped = capture("DeriskSkippedDisabled");
    assertThat(skipped.getSubject()).containsEntry("signal_id", "cue-msg-1:derisk");
    assertNoAudit("DeriskTrimRequested");
    assertNoAudit("DeriskArmRequested");
  }

  @Test
  void disabledFalse_noSignals_oneSkippedDisabledAudit() {
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(Boolean.FALSE, new BigDecimal("0.25"), new BigDecimal("0.30")));
    stubResolve();
    startLiveTarget();

    runWorkflow(deriskPayload());

    capture("DeriskSkippedDisabled");
    assertNoAudit("DeriskTrimRequested");
    assertNoAudit("DeriskArmRequested");
  }

  @Test
  void targetClosedOrAbsent_emitsDeriskNoOpenPosition_noUnhandledFailure() {
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(Boolean.TRUE, new BigDecimal("0.25"), new BigDecimal("0.30")));
    stubResolve();
    // Deliberately do NOT start the target — the derived positionWorkflowId was never started, so
    // the partialExit external-signal command fails on the test server (SignalExternalWorkflow-
    // Exception), exactly the Friday QQQ / INTC-195c closed-position case.

    String result = runWorkflow(deriskPayload());
    // Benign catch path: the workflow COMPLETES (returns the cue signal_id), never a hard failure.
    assertThat(result).isEqualTo("cue-msg-1:derisk");

    AuditEvent noPos = capture("DeriskNoOpenPosition");
    assertThat(noPos.getSubject()).containsEntry("signal_id", "cue-msg-1:derisk");
    assertThat(noPos.getSubject()).containsEntry("position_workflow_id", expectedPosWfId());
    assertNoAudit("DeriskArmRequested");
  }

  @Test
  void keepFractionNull_defaultsTo025() {
    // Enabled, keep-fraction null → default 0.25 keep → trim 0.75.
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(Boolean.TRUE, null, new BigDecimal("0.30")));
    stubResolve();
    String posWfId = startLiveTarget();

    runWorkflow(deriskPayload());

    AuditEvent trim = capture("DeriskTrimRequested");
    assertThat(((Number) trim.getSubject().get("fraction")).doubleValue()).isEqualTo(0.75);
    assertThat(((Number) trim.getSubject().get("keep_fraction")).doubleValue()).isEqualTo(0.25);
    assertThat(trim.getSubject()).containsEntry("position_workflow_id", posWfId);
    // Arm still fires (giveback present).
    capture("DeriskArmRequested");
  }

  @Test
  void givebackNull_trimApplied_armSkippedInvalidGiveback() {
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(Boolean.TRUE, new BigDecimal("0.25"), null));
    stubResolve();
    startLiveTarget();

    runWorkflow(deriskPayload());

    // Trim still applies.
    AuditEvent trim = capture("DeriskTrimRequested");
    assertThat(((Number) trim.getSubject().get("fraction")).doubleValue()).isEqualTo(0.75);
    // Arm is skipped with the documented reason; no DeriskArmRequested.
    AuditEvent armSkipped = capture("DeriskArmSkipped");
    assertThat(armSkipped.getSubject()).containsEntry("reason", "invalid_giveback");
    assertNoAudit("DeriskArmRequested");
  }

  @Test
  void targetEntryPremiumNull_trimApplied_armSkippedNoPeak() {
    when(strategy.get(anyString(), anyString()))
        .thenReturn(deriskConfig(Boolean.TRUE, new BigDecimal("0.25"), new BigDecimal("0.30")));
    stubResolve();
    startLiveTarget();

    CopytradeDeriskPayload p = deriskPayload();
    p.setTargetEntryPremium(null); // no seed → cannot arm the trail this phase
    runWorkflow(p);

    AuditEvent trim = capture("DeriskTrimRequested");
    assertThat(((Number) trim.getSubject().get("fraction")).doubleValue()).isEqualTo(0.75);
    AuditEvent armSkipped = capture("DeriskArmSkipped");
    assertThat(armSkipped.getSubject()).containsEntry("reason", "arm_skipped_no_peak");
    assertNoAudit("DeriskArmRequested");
  }

  // ---- helpers ----

  private void stubResolve() {
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                OCC,
                "INTC",
                LocalDate.of(2026, 8, 3),
                new BigDecimal("95"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
  }

  /** Starts a parking PositionWorkflow at the derived target id so external signals land. */
  private String startLiveTarget() {
    String posWfId = expectedPosWfId();
    ParkingPositionWorkflow stub =
        env.getWorkflowClient()
            .newWorkflowStub(
                ParkingPositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId(posWfId)
                    .build());
    WorkflowStub.fromTyped(stub).start(positionInput(posWfId));
    return posWfId;
  }

  private static String expectedPosWfId() {
    return WorkflowIds.position(TENANT, STRATEGY, OCC, TARGET_BTO_SIGNAL_ID);
  }

  private String runWorkflow(CopytradeDeriskPayload payload) {
    CopytradeDeriskWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeDeriskWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    return wf.process(payload);
  }

  private AuditEvent capture(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  private void assertNoAudit(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    assertThat(captor.getAllValues().stream().anyMatch(e -> kind.equals(e.getKind())))
        .as("expected NO audit event with kind=%s", kind)
        .isFalse();
  }

  private StrategyConfig deriskConfig(
      Boolean enabled, BigDecimal keepFraction, BigDecimal giveback) {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId(TENANT);
    c.setStrategyId(STRATEGY);
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setAuthorWhitelist(Set.of("TradingTheTrend"));
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    c.setDeriskOnFollowupCue(enabled);
    c.setDeriskKeepFraction(keepFraction);
    c.setTrailGivebackPct(giveback);
    return c;
  }

  private CopytradeDeriskPayload deriskPayload() {
    CopytradeDeriskPayload p = new CopytradeDeriskPayload();
    p.setSchemaVersion(1L);
    p.setTenantId(TENANT);
    p.setStrategyId(STRATEGY);
    p.setSignalId("cue-msg-1:derisk");
    p.setMessageId("cue-msg-1");
    p.setAuthor("TradingTheTrend");
    p.setPostedAt(OffsetDateTime.of(2026, 8, 3, 17, 56, 0, 0, ZoneOffset.UTC));
    p.setTicker("INTC");
    p.setExpiry(LocalDate.of(2026, 8, 3));
    p.setStrike(new BigDecimal("95"));
    p.setRight(CopytradeDeriskPayload.Right.C);
    p.setTargetBtoSignalId(TARGET_BTO_SIGNAL_ID);
    p.setTargetEntryPremium(new BigDecimal("1.34"));
    p.setMatchedCue("0 or hero");
    p.setRawLine("I'm cool with going 0 or hero on these. Feel free to use your own stop");
    return p;
  }

  private PositionWorkflowInput positionInput(String wfId) {
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(TENANT);
    in.setStrategyId(STRATEGY);
    in.setEntrySignalId(TARGET_BTO_SIGNAL_ID);
    in.setContractSymbol(OCC);
    in.setQty(50L);
    in.setEntryPremium(new BigDecimal("1.34"));
    return in;
  }

  /**
   * Minimal PositionWorkflow-shaped double: parks in {@code run} so it stays RUNNING and can
   * receive the derisk workflow's external {@code partialExit} / {@code armChandelier} signals. The
   * parent-side audits are the assertion surface, so the handlers only record receipt.
   */
  @io.temporal.workflow.WorkflowInterface
  public interface ParkingPositionWorkflow {
    @io.temporal.workflow.WorkflowMethod
    String run(PositionWorkflowInput input);

    @io.temporal.workflow.SignalMethod
    void partialExit(PartialExitRequest req);

    @io.temporal.workflow.SignalMethod
    void armChandelier(ArmChandelierPayload payload);
  }

  public static final class ParkingPositionWorkflowImpl implements ParkingPositionWorkflow {
    static final Set<String> TERMINATE = ConcurrentHashMap.newKeySet();

    static void reset() {
      TERMINATE.clear();
    }

    @Override
    public String run(PositionWorkflowInput input) {
      Workflow.await(() -> TERMINATE.contains(Workflow.getInfo().getWorkflowId()));
      return input.getEntrySignalId();
    }

    @Override
    public void partialExit(PartialExitRequest req) {}

    @Override
    public void armChandelier(ArmChandelierPayload payload) {}
  }
}
