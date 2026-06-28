package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase F (operator-account-onboarding) impl of the client-side kill-switch operations the {@code
 * LiveActivationWorkflow} needs. Reads/mutates the per-{@code (tenant, strategy)} {@code
 * KillSwitchWorkflow} via the injected {@link WorkflowClient} (an Activity context, not the
 * deterministic workflow body), mirroring {@code RiskActivitiesImpl}'s kill-switch read and {@code
 * KillSwitchController}'s trip Update.
 */
@Component
public class LiveActivationGateActivitiesImpl implements LiveActivationGateActivities {

  private static final Logger log = LoggerFactory.getLogger(LiveActivationGateActivitiesImpl.class);

  private final WorkflowClient workflowClient;

  public LiveActivationGateActivitiesImpl(WorkflowClient workflowClient) {
    this.workflowClient = workflowClient;
  }

  @Override
  public boolean killSwitchArmable(String tenantId, String strategyId) {
    if (workflowClient == null) {
      // Defensive: production always wires the client. Fail CLOSED — not armable.
      return false;
    }
    try {
      String wfId = WorkflowIds.killswitch(tenantId, strategyId);
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(wfId);
      KillSwitchState state = stub.query("killswitch_state", KillSwitchState.class);
      return state != null;
    } catch (Exception e) {
      // Unreachable / not running / query rejected → not armable. Fail CLOSED.
      log.warn(
          "killSwitchArmable query failed tenant={} strategy={}; treating as NOT armable",
          tenantId,
          strategyId,
          e);
      return false;
    }
  }

  @Override
  public void tripKillSwitch(String tenantId, String strategyId, String operatorId, String reason) {
    String wfId = WorkflowIds.killswitch(tenantId, strategyId);

    TripKillSwitchRequest tk = new TripKillSwitchRequest();
    tk.setSchemaVersion(1L);
    tk.setReason(reason);
    tk.setActor("operator:" + operatorId);

    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(wfId);
      stub.update("trip_killswitch", Void.class, tk);
    } catch (Exception e) {
      // An already-tripped switch is the DESIRED end-state of a deactivation — the trip Update's
      // validator rejects with "already_tripped" (surfaced as a WorkflowUpdateException wrapping
      // IllegalStateException). Swallow that idempotent case; rethrow anything else so the
      // deactivation workflow's retry policy sees a genuine failure.
      if (isAlreadyTripped(e)) {
        log.info(
            "tripKillSwitch tenant={} strategy={} already tripped — deactivation idempotent",
            tenantId,
            strategyId);
        return;
      }
      throw e;
    }
  }

  private static boolean isAlreadyTripped(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String m = t.getMessage();
      if (m != null && m.contains("already_tripped")) {
        return true;
      }
    }
    return false;
  }
}
