package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.contract.activities.PortfolioHistoryActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Dispatches the {@link PortfolioHistoryActivity} to the {@code broker-<broker_target>} task queue
 * and returns its result. The activity-stub options mirror {@link AccountSnapshotWorkflowImpl} (15s
 * start-to-close, 60s schedule-to-close, 3 attempts); {@link ExecActivitiesFactory#taskQueueFor}
 * validates the target and builds the {@code broker-<target>} queue name (rejecting null/blank or
 * legacy bare {@code paper}/{@code live} values fast).
 *
 * <p>Determinism: the request is the workflow input — period/timeframe are pre-resolved by the BFF
 * client, so there are no clock/random reads here. Brand-new workflow type → no {@code
 * Workflow.getVersion} gate (no replay history exists).
 */
public class PortfolioHistoryWorkflowImpl implements PortfolioHistoryWorkflow {

  @Override
  public PortfolioHistoryResult history(PortfolioHistoryRequest request) {
    String brokerTarget =
        request.getBrokerTarget() == null ? null : request.getBrokerTarget().value();
    PortfolioHistoryActivity activity =
        Workflow.newActivityStub(
            PortfolioHistoryActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
    return activity.portfolioHistory(request);
  }
}
