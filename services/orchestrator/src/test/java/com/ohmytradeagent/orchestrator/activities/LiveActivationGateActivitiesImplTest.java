package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase F: the single-operator {@code resetKillSwitch} client activity used by a successful
 * one-click {@code activateLive}. Mirrors the {@code tripKillSwitch} idempotency contract: swallow
 * the desired end-state rejection ({@code not_tripped}) and rethrow any genuine failure.
 */
class LiveActivationGateActivitiesImplTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark";

  private WorkflowClient client;
  private WorkflowStub stub;
  private LiveActivationGateActivitiesImpl activities;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(stub);
    activities = new LiveActivationGateActivitiesImpl(client);
  }

  @Test
  void resetKillSwitch_issuesResetOnActivationUpdate_withOperatorApprover() {
    activities.resetKillSwitch(TENANT, STRATEGY, OPERATOR);

    verify(client).newUntypedWorkflowStub(WorkflowIds.killswitch(TENANT, STRATEGY));

    ArgumentCaptor<ResetKillSwitchRequest> captor =
        ArgumentCaptor.forClass(ResetKillSwitchRequest.class);
    verify(stub, times(1)).update(eq("reset_on_activation"), eq(Void.class), captor.capture());
    ResetKillSwitchRequest req = captor.getValue();
    assertThat(req.getSchemaVersion()).isEqualTo(1L);
    assertThat(req.getApproverId1()).isEqualTo("operator:" + OPERATOR);
    assertThat(req.getApproverId2()).isNull();
    assertThat(req.getNote()).isEqualTo("live_activation:one_click");
  }

  @Test
  void resetKillSwitch_swallowsNotTripped_idempotent() {
    doThrow(new RuntimeException("Update rejected: not_tripped"))
        .when(stub)
        .update(eq("reset_on_activation"), eq(Void.class), any());

    // Not tripped is the desired end-state (strategy already resumed) — must NOT throw.
    activities.resetKillSwitch(TENANT, STRATEGY, OPERATOR);

    verify(stub, times(1)).update(eq("reset_on_activation"), eq(Void.class), any());
  }

  @Test
  void resetKillSwitch_rethrowsGenuineFailure() {
    doThrow(new RuntimeException("transient temporal outage"))
        .when(stub)
        .update(eq("reset_on_activation"), eq(Void.class), any());

    assertThatThrownBy(() -> activities.resetKillSwitch(TENANT, STRATEGY, OPERATOR))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("transient temporal outage");
  }
}
