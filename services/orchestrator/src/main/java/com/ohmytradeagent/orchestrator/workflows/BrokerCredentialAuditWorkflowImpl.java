package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import com.ohmytradeagent.orchestrator.activities.BrokerCredentialAuditActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Single-step credential-audit workflow: dispatches {@code record} (the P6-d metadata-only,
 * hash-chained audit Activity) and completes. No state, no timers, no {@code Workflow.getVersion}
 * change-point (net-new workflow type), and no {@code Instant.now}/{@code UUID}/non-deterministic
 * iteration in the body — so replay determinism is trivially preserved.
 *
 * <p>The activity stub inherits this workflow's task queue (orchestrator-core), exactly like {@link
 * WatchlistMirrorWorkflowImpl}. Retry is UNLIMITED ({@code maximumAttempts=0}) with capped
 * exponential backoff so a transient orchestrator/DB blip cannot drop the audit — the write already
 * happened in exec, so the audit must eventually land.
 */
public class BrokerCredentialAuditWorkflowImpl implements BrokerCredentialAuditWorkflow {

  private final BrokerCredentialAuditActivities audit =
      Workflow.newActivityStub(
          BrokerCredentialAuditActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofMinutes(1))
                      .setMaximumAttempts(0) // unlimited — the audit must eventually land.
                      .build())
              .build());

  @Override
  public void record(BrokerCredentialAuditRequest request) {
    audit.record(request);
  }
}
