package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 6: default impl of the account-scoped cascade. Reuses the proven #323 tenant-wide union
 * (one {@code TenantStrategy='...'} equality query per resolved strategy, deduped by workflow id)
 * and fans out the SAME {@code riskBreach} signal the per-strategy cascade sends — so each target
 * PositionWorkflow's existing {@code riskBreach -> processRiskBreach -> flattenRemaining(
 * "risk_breach")} MARKET flatten runs unchanged. PositionWorkflowImpl is not modified.
 *
 * <p>A resolver throw (unreadable tenants tree) propagates — fail-closed, the trip is retried by
 * Temporal rather than silently signalling a subset. Individual signal failures are best-effort
 * (the target may have just closed).
 */
public class AccountKillSwitchCascadeActivitiesImpl implements AccountKillSwitchCascadeActivities {

  private static final Logger log =
      LoggerFactory.getLogger(AccountKillSwitchCascadeActivitiesImpl.class);

  private final WorkflowClient client;
  private final TenantStrategies tenantStrategies;
  private final Clock clock;

  public AccountKillSwitchCascadeActivitiesImpl(
      WorkflowClient client, TenantStrategies tenantStrategies, Clock clock) {
    this.client = client;
    this.tenantStrategies = tenantStrategies;
    this.clock = clock;
  }

  @Override
  public long cascadeAccountRiskBreach(
      String tenantId, String excludeWorkflowId, String reason, String actor) {
    Set<String> strategyIds = new LinkedHashSet<>();
    for (String sid : tenantStrategies.strategyIdsForTenant(tenantId)) {
      if (sid != null && !sid.isBlank()) {
        strategyIds.add(sid);
      }
    }

    RiskBreachPayload payload = new RiskBreachPayload();
    payload.setSchemaVersion(1L);
    payload.setReason(reason);
    payload.setActor(actor);
    payload.setOccurredAt(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));

    Set<String> signaled = new LinkedHashSet<>();
    long sent = 0L;
    for (String sid : strategyIds) {
      String query =
          "TenantStrategy='"
              + WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenantId, sid))
              + "' AND WorkflowType='PositionWorkflow' AND ExecutionStatus='Running'";
      try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
        var it = stream.iterator();
        while (it.hasNext()) {
          WorkflowExecutionMetadata md = it.next();
          String wfId = md.getExecution().getWorkflowId();
          if (wfId.equals(excludeWorkflowId) || !signaled.add(wfId)) {
            continue;
          }
          try {
            WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
            stub.signal("riskBreach", payload);
            sent++;
          } catch (RuntimeException e) {
            log.warn(
                "account cascade signal failed wf={} tenant={} strategy={} err={}",
                wfId,
                tenantId,
                sid,
                e.getMessage());
          }
        }
      }
    }
    log.info("account cascade complete tenant={} sent={} reason={}", tenantId, sent, reason);
    return sent;
  }
}
