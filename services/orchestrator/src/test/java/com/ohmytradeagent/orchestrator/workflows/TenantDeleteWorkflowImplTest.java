package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.orchestrator.activities.TenantDeleteActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Phase 2 (PLAN-2026-07-03-operator-tenant-delete) coverage for the per-{@code (tenant, strategy)}
 * teardown carrier. The workflow orchestration is proven here with mock Activities on the
 * orchestrator-core worker; the Activities' own idempotency (prefix-reap + NotFound-swallow) is
 * unit-tested in {@code TenantDeleteActivitiesImplTest}.
 *
 * <ul>
 *   <li>happy path drives the three steps a → b → c and returns step (c)'s count;
 *   <li>when every step yields success (all schedules already reaped, kill-switch-WF-absent,
 *       config-absent — the mocks' default no-op / 0 return) the workflow completes with 0.
 * </ul>
 *
 * <p>There is no ordering constraint to guard: step (a) reaps recon schedules by the {@code
 * (tenant, strategy)} prefix and never reads {@code broker_target} from the config row, so it no
 * longer has to run before the config delete.
 */
class TenantDeleteWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String ACTOR = "operator:ridopark";

  private TestWorkflowEnvironment env;

  @AfterEach
  void tearDown() {
    if (env != null) {
      env.close();
    }
  }

  /** Stands up a fresh env with {@code activities} registered and returns a workflow stub. */
  private TenantDeleteWorkflow startWith(TenantDeleteActivities activities) {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(CORE_QUEUE);
    worker.registerWorkflowImplementationTypes(TenantDeleteWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            TenantDeleteWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  @Test
  void happyPath_drivesStepsInOrder_returnsDeletedCount() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    when(activities.deleteStrategyConfig(TENANT, STRATEGY, ACTOR)).thenReturn(1);

    int deleted = startWith(activities).deleteTenant(TENANT, STRATEGY, ACTOR);

    assertThat(deleted).isEqualTo(1);
    InOrder ordered = inOrder(activities);
    ordered.verify(activities).deleteReconSchedules(TENANT, STRATEGY);
    ordered.verify(activities).terminateKillSwitchWorkflow(TENANT, STRATEGY);
    ordered.verify(activities).deleteStrategyConfig(TENANT, STRATEGY, ACTOR);
  }

  @Test
  void allStepsIdempotentSuccess_workflowCompletes() {
    // Every step yields success as if its target were already gone: the two void steps no-op and
    // the config delete returns 0 (already absent) — the Mockito defaults. The workflow must still
    // complete cleanly and surface the 0 count.
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);

    int deleted = startWith(activities).deleteTenant(TENANT, STRATEGY, ACTOR);

    assertThat(deleted).isZero();
    verify(activities, times(1)).deleteReconSchedules(TENANT, STRATEGY);
    verify(activities, times(1)).terminateKillSwitchWorkflow(TENANT, STRATEGY);
    verify(activities, times(1)).deleteStrategyConfig(eq(TENANT), eq(STRATEGY), eq(ACTOR));
  }
}
