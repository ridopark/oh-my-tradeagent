package com.ohmytradeagent.tdbff.portfolio;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches a brokerage account's net-liquidation equity by starting the short-lived {@code
 * AccountSnapshotWorkflow} on the orchestrator task queue and reading its result — the same
 * synchronous start-and-getResult pattern {@code api-gateway}'s {@code POST /positions/adopt} uses
 * for {@code AdoptionWorkflow}. The BFF is a Temporal <em>client</em>, so it cannot dispatch the
 * {@code AccountSnapshotActivity} directly (activities only run inside workflows); the workflow,
 * hosted on the orchestrator worker, dispatches that activity to {@code broker-<target>}. No broker
 * credentials live in the BFF.
 *
 * <p>Equity is account-level — shared by every tenant routing to a given {@code broker_target} —
 * and is NOT the tenant's portfolio value; the {@code account_equity_scope} label on the portfolio
 * response states this explicitly.
 */
@Component
public class AccountEquityClient {

  private static final Logger log = LoggerFactory.getLogger(AccountEquityClient.class);
  private static final String WORKFLOW_TYPE = "AccountSnapshotWorkflow";
  // Bounds the blocking getResult so an unreachable Temporal service can't pin a request thread.
  // Covers the workflow's 60s scheduleToCloseTimeout (AccountSnapshotWorkflowImpl) plus margin.
  private static final long RESULT_TIMEOUT_SECONDS = 90;

  private final WorkflowClient client;
  private final String orchestratorTaskQueue;

  public AccountEquityClient(
      WorkflowClient client,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}")
          String orchestratorTaskQueue) {
    this.client = client;
    this.orchestratorTaskQueue = orchestratorTaskQueue;
  }

  /**
   * Net-liquidation equity for the account behind {@code brokerTarget}, or {@code null} when the
   * snapshot is unavailable (a read-only view degrades gracefully rather than failing the whole
   * portfolio page). The broker adapter returns the sentinel {@code equity=0} when it has no real
   * account endpoint — surfaced as-is.
   */
  public BigDecimal equityFor(String brokerTarget) {
    AccountSnapshotRequest request = new AccountSnapshotRequest();
    request.setSchemaVersion(1L);
    request.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.fromValue(brokerTarget));
    request.setCorrelationId("dashboard-" + UUID.randomUUID());

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(orchestratorTaskQueue)
            .setWorkflowId("account-snapshot/" + brokerTarget + "/" + UUID.randomUUID())
            .build();
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(WORKFLOW_TYPE, opts);
      stub.start(request);
      // Bounded wait (RESULT_TIMEOUT_SECONDS) so an unreachable Temporal service / down task queue
      // cannot pin a Spring MVC request thread indefinitely; on timeout we degrade to null like any
      // other snapshot-unavailable case.
      AccountSnapshotResult result =
          stub.getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, AccountSnapshotResult.class);
      return result == null ? null : result.getEquity();
    } catch (TimeoutException | RuntimeException e) {
      log.warn(
          "AccountSnapshotWorkflow failed broker_target={} err={}", brokerTarget, e.getMessage());
      return null;
    }
  }
}
