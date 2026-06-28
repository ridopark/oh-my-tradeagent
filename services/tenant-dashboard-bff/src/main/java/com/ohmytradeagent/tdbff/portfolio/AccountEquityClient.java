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
  // Client-side wait bound for the blocking getResult. Kept short (not the workflow's full 60s
  // scheduleToCloseTimeout) so the portfolio PAGE stays responsive: a healthy account snapshot
  // returns in well under a second, and if it doesn't we degrade to null (equity unavailable)
  // rather than make the user wait. The workflow's own 60s timeout still bounds it server-side.
  private static final long RESULT_TIMEOUT_SECONDS = 8;

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
   * Net-liquidation equity plus the informational brokerage account identity from a SINGLE account
   * snapshot. {@code accountNumber} is the Alpaca {@code /v2/account 'account_number'} — surfaced
   * only for dashboard account verification, never a credential and never used by any gate. Both
   * fields are nullable: a degraded/unavailable snapshot yields {@code new BrokerAccount(null,
   * null)}, and a broker adapter with no real account endpoint may carry a null account number.
   */
  public record BrokerAccount(BigDecimal equity, String accountNumber) {}

  /**
   * Equity + informational account identity for the account behind {@code brokerTarget}, read from
   * one {@code AccountSnapshotWorkflow} round-trip. Never {@code null}: on any
   * timeout/error/degrade it returns {@code new BrokerAccount(null, null)} (a read-only view
   * degrades gracefully rather than failing the whole portfolio page). The broker adapter returns
   * the sentinel {@code equity=0} when it has no real account endpoint — surfaced as-is.
   */
  public BrokerAccount snapshotFor(String brokerTarget) {
    AccountSnapshotRequest request = new AccountSnapshotRequest();
    request.setSchemaVersion(1L);
    request.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.fromValue(brokerTarget));
    request.setCorrelationId("dashboard-" + UUID.randomUUID());

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(orchestratorTaskQueue)
            .setWorkflowId("account-snapshot/" + brokerTarget + "/" + UUID.randomUUID())
            .build();
    WorkflowStub stub = client.newUntypedWorkflowStub(WORKFLOW_TYPE, opts);
    try {
      stub.start(request);
      // Bounded wait (RESULT_TIMEOUT_SECONDS) so an unreachable Temporal service / down task queue
      // cannot pin a Spring MVC request thread indefinitely; on timeout we degrade to null like any
      // other snapshot-unavailable case.
      AccountSnapshotResult result =
          stub.getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, AccountSnapshotResult.class);
      return result == null
          ? new BrokerAccount(null, null)
          : new BrokerAccount(result.getEquity(), result.getAccountNumber());
    } catch (TimeoutException e) {
      // We stopped waiting, but the workflow is still running. Cancel it so it doesn't linger as an
      // orphan — holding an orchestrator worker slot and re-hitting the broker account endpoint —
      // long after this request already degraded to null.
      log.warn(
          "AccountSnapshotWorkflow timed out broker_target={}; cancelling orphan", brokerTarget);
      cancelQuietly(stub, brokerTarget);
      return new BrokerAccount(null, null);
    } catch (RuntimeException e) {
      // start() may have already succeeded before getResult() threw (e.g. transient Temporal
      // connectivity), leaving the workflow running. Cancel it too so it doesn't orphan until its
      // scheduleToClose timeout.
      log.warn(
          "AccountSnapshotWorkflow failed broker_target={} err={}; cancelling orphan",
          brokerTarget,
          e.getMessage());
      cancelQuietly(stub, brokerTarget);
      return new BrokerAccount(null, null);
    }
  }

  private void cancelQuietly(WorkflowStub stub, String brokerTarget) {
    try {
      stub.cancel();
    } catch (RuntimeException cancelErr) {
      log.warn(
          "AccountSnapshotWorkflow cancel failed broker_target={} err={}",
          brokerTarget,
          cancelErr.getMessage());
    }
  }
}
