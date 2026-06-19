package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PositionSnapshotRequest;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;

/**
 * Dispatches {@link ReconciliationExecActivity#brokerListOpenPositions} to the {@code
 * broker-<broker_target>} task queue and returns its result. The activity-stub options mirror
 * {@link AccountSnapshotWorkflowImpl} (15s start-to-close, 60s schedule-to-close, 3 attempts);
 * {@link ExecActivitiesFactory#taskQueueFor(String)} validates the target and builds the {@code
 * broker-<target>} queue name (rejecting null/blank or legacy bare {@code paper}/{@code live}
 * values fast). Determinism: the request is the workflow input — no clock/random reads.
 */
public class PositionSnapshotWorkflowImpl implements PositionSnapshotWorkflow {

  @Override
  public List<BrokerPosition> snapshot(PositionSnapshotRequest request) {
    String brokerTarget =
        request.getBrokerTarget() == null ? null : request.getBrokerTarget().value();
    ReconciliationExecActivity activity =
        Workflow.newActivityStub(
            ReconciliationExecActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    return activity.brokerListOpenPositions(request.getTenantId(), request.getStrategyId());
  }
}
