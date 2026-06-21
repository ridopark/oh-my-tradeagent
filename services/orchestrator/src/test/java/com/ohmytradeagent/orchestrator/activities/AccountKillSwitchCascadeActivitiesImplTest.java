package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.RiskBreachPayload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountKillSwitchCascadeActivitiesImplTest {

  private WorkflowClient client;
  private AccountKillSwitchCascadeActivitiesImpl cascade;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
  }

  private AccountKillSwitchCascadeActivitiesImpl forStrategies(List<String> strategyIds) {
    return new AccountKillSwitchCascadeActivitiesImpl(
        client, tenantId -> strategyIds, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  // The account cascade signals riskBreach to running PositionWorkflows across ALL tenant
  // strategies — one equality query per strategy, deduped by workflow id.
  @Test
  void cascadesRiskBreachToAllTenantStrategies() {
    cascade = forStrategies(List.of("copytrade-v1", "copytrade-v2"));
    stubExecutionsByQuery(
        Map.of(
            "t-dev/s-copytrade-v1", "wf-s1",
            "t-dev/s-copytrade-v2", "wf-s2"));
    WorkflowStub s1 = mock(WorkflowStub.class);
    WorkflowStub s2 = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub("wf-s1")).thenReturn(s1);
    when(client.newUntypedWorkflowStub("wf-s2")).thenReturn(s2);

    long sent =
        cascade.cascadeAccountRiskBreach("dev", "t-dev/account/killswitch", "auto:x", "auto:x");

    assertThat(sent).isEqualTo(2);
    verify(s1).signal(eq("riskBreach"), any(RiskBreachPayload.class));
    verify(s2).signal(eq("riskBreach"), any(RiskBreachPayload.class));
  }

  // The account kill-switch workflow itself (excludeWorkflowId) is never signalled.
  @Test
  void excludesSelf() {
    cascade = forStrategies(List.of("copytrade-v1"));
    stubExecutionsByQuery(Map.of("t-dev/s-copytrade-v1", "t-dev/account/killswitch"));
    WorkflowStub self = mock(WorkflowStub.class);
    lenient().when(client.newUntypedWorkflowStub("t-dev/account/killswitch")).thenReturn(self);

    long sent =
        cascade.cascadeAccountRiskBreach("dev", "t-dev/account/killswitch", "auto:x", "auto:x");

    assertThat(sent).isZero();
    verify(self, never()).signal(anyString(), any());
  }

  // A signal failure on one target is best-effort; the cascade keeps going and counts the rest.
  @Test
  void bestEffortOnIndividualSignalFailure() {
    cascade = forStrategies(List.of("copytrade-v1", "copytrade-v2"));
    stubExecutionsByQuery(
        Map.of(
            "t-dev/s-copytrade-v1", "wf-bad",
            "t-dev/s-copytrade-v2", "wf-good"));
    WorkflowStub bad = mock(WorkflowStub.class);
    org.mockito.Mockito.doThrow(new RuntimeException("closed"))
        .when(bad)
        .signal(eq("riskBreach"), any());
    WorkflowStub good = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub("wf-bad")).thenReturn(bad);
    when(client.newUntypedWorkflowStub("wf-good")).thenReturn(good);

    long sent = cascade.cascadeAccountRiskBreach("dev", "self", "auto:x", "auto:x");

    assertThat(sent).isEqualTo(1);
    verify(good, times(1)).signal(eq("riskBreach"), any());
  }

  // ----- helpers -----

  private void stubExecutionsByQuery(Map<String, String> tenantStrategyToWorkflowId) {
    when(client.listExecutions(anyString()))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              return tenantStrategyToWorkflowId.entrySet().stream()
                  .filter(e -> query.contains("TenantStrategy='" + e.getKey() + "'"))
                  .map(Map.Entry::getValue)
                  .map(this::metadata);
            });
  }

  private WorkflowExecutionMetadata metadata(String workflowId) {
    WorkflowExecutionMetadata md = mock(WorkflowExecutionMetadata.class);
    lenient()
        .when(md.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return md;
  }
}
