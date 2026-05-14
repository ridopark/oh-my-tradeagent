package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class CopytradeSignalWorkflowImplTest {

  private static final String TASK_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private StrategyActivities strategy;
  private RiskActivities risk;
  private ContractActivities contract;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(CopytradeSignalWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    risk = Mockito.mock(RiskActivities.class);
    contract = Mockito.mock(ContractActivities.class);

    worker.registerActivitiesImplementations(audit, strategy, risk, contract);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void approvedSignal_routesThroughRiskContractAudit_andProducesSignalAccepted() {
    StrategyConfig cfg = config();
    when(strategy.get("dev", "copytrade-v1")).thenReturn(cfg);
    when(risk.checkEntry(any(), eq(cfg))).thenReturn(RiskDecision.approved());
    when(contract.resolve(any()))
        .thenReturn(
            new ContractResolveResult(
                "NVDA  260516C00140000",
                "NVDA",
                LocalDate.of(2026, 5, 16),
                new BigDecimal("140"),
                "C",
                ContractResolveResult.SOURCE_GENERATED));
    when(strategy.capitalForStrategy("dev", "copytrade-v1")).thenReturn(new BigDecimal("100000"));

    CopytradeSignalPayload payload = btoPayload();

    String result = runWorkflow(payload);

    assertThat(result).isEqualTo(payload.getSignalId());
    verify(strategy).get("dev", "copytrade-v1");
    verify(risk).checkEntry(any(), eq(cfg));
    verify(contract).resolve(any(ContractResolveInput.class));
    verify(strategy).capitalForStrategy("dev", "copytrade-v1");

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeast(2)).log(captor.capture());

    List<String> kinds = captor.getAllValues().stream().map(AuditEvent::getKind).toList();
    assertThat(kinds).containsExactly("SignalReceived", "SignalAccepted");

    AuditEvent accepted = captor.getAllValues().get(1);
    assertThat(accepted.getSubject()).containsEntry("option_symbol", "NVDA  260516C00140000");
    assertThat(((Number) accepted.getSubject().get("contracts")).longValue()).isEqualTo(5L);
    assertThat(accepted.getCorrelationId()).isEqualTo(payload.getSignalId());
  }

  @Test
  void rejectedByAuthor_producesSignalRejectedAndSkipsContractResolve() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntry(any(), any()))
        .thenReturn(
            RiskDecision.rejected(RejectionReason.AUTHOR_NOT_WHITELISTED, "author=stranger"));

    runWorkflow(btoPayload());

    verify(contract, never()).resolve(any());
    verify(strategy, never()).capitalForStrategy(anyString(), anyString());

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());

    AuditEvent rejected = lastByKind(captor, "SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "AUTHOR_NOT_WHITELISTED");
    assertThat(rejected.getSubject()).containsEntry("reason_detail", "author=stranger");
  }

  @Test
  void rejectedByStaleSignal_producesSignalRejected() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntry(any(), any()))
        .thenReturn(RiskDecision.rejected(RejectionReason.SIGNAL_TOO_OLD, "age_secs=2000"));

    runWorkflow(btoPayload());

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());

    AuditEvent rejected = lastByKind(captor, "SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "SIGNAL_TOO_OLD");
    verify(contract, never()).resolve(any());
  }

  @Test
  void rejectedByMaxPositions_producesSignalRejected() {
    when(strategy.get(anyString(), anyString())).thenReturn(config());
    when(risk.checkEntry(any(), any()))
        .thenReturn(RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "open=5"));

    runWorkflow(btoPayload());

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());

    AuditEvent rejected = lastByKind(captor, "SignalRejected");
    assertThat(rejected.getSubject()).containsEntry("reason_code", "MAX_POSITIONS_EXCEEDED");
    verify(contract, never()).resolve(any());
  }

  private String runWorkflow(CopytradeSignalPayload payload) {
    CopytradeSignalWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    return wf.process(payload);
  }

  private AuditEvent lastByKind(ArgumentCaptor<AuditEvent> captor, String kind) {
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  private CopytradeSignalPayload btoPayload() {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("111:0");
    p.setMessageId("111");
    p.setAuthor("acme_trader");
    p.setPostedAt(OffsetDateTime.of(2026, 5, 13, 17, 22, 31, 0, ZoneOffset.UTC));
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker("NVDA");
    p.setExpiry(LocalDate.of(2026, 5, 16));
    p.setStrike(new BigDecimal("140"));
    p.setRight(CopytradeSignalPayload.Right.C);
    p.setPrice(new BigDecimal("2.30"));
    p.setRawLine("BTO NVDA 5/16 140C @ 2.30");
    return p;
  }

  private StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    c.setAuthorWhitelist(Set.of("acme_trader"));
    c.setMaxSignalAgeSecs(1800L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }
}
