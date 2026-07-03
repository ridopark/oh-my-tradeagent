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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Phase 2 (PLAN-2026-07-03-operator-tenant-delete) coverage for the per-{@code (tenant, strategy)}
 * teardown carrier. The workflow orchestration is proven here with mock / fake Activities on the
 * orchestrator-core worker; the Activities' own idempotency (NotFound-swallow) is unit-tested in
 * {@code TenantDeleteActivitiesImplTest}.
 *
 * <ul>
 *   <li>happy path drives the three steps in a → b → c order and returns step (c)'s count;
 *   <li>when every step yields success (schedule-absent, kill-switch-WF-absent, config-absent — the
 *       mocks' default no-op / 0 return) the workflow completes;
 *   <li>the ORDERING guard proves step (a) (broker_target resolve) runs BEFORE step (c) (config
 *       delete) — a fake that throws if the config row were already gone stays silent.
 * </ul>
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
    ordered.verify(activities).resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY);
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
    verify(activities, times(1)).resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY);
    verify(activities, times(1)).terminateKillSwitchWorkflow(TENANT, STRATEGY);
    verify(activities, times(1)).deleteStrategyConfig(eq(TENANT), eq(STRATEGY), eq(ACTOR));
  }

  @Test
  void ordering_brokerTargetResolveRunsBeforeConfigDelete() {
    // Proves the load-bearing order: the resolve step (which reads broker_target off the config row
    // to compute the recon schedule id) MUST run before the config row is deleted. The fake throws
    // if resolve is ever invoked after the config delete flag is set; a passing (silent) run proves
    // a → … → c ordering.
    OrderingFake fake = new OrderingFake();

    int deleted = startWith(fake).deleteTenant(TENANT, STRATEGY, ACTOR);

    assertThat(deleted).isEqualTo(1);
    assertThat(fake.calls).containsExactly("resolve", "terminate", "delete");
  }

  /** Fake that fails the resolve step if the config row has already been deleted. */
  private static final class OrderingFake implements TenantDeleteActivities {
    private final AtomicBoolean configDeleted = new AtomicBoolean(false);
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void resolveBrokerTargetAndDeleteReconSchedule(String tenantId, String strategyId) {
      if (configDeleted.get()) {
        throw new IllegalStateException(
            "resolve ran AFTER config delete — broker_target would be uncomputable");
      }
      calls.add("resolve");
    }

    @Override
    public void terminateKillSwitchWorkflow(String tenantId, String strategyId) {
      calls.add("terminate");
    }

    @Override
    public int deleteStrategyConfig(String tenantId, String strategyId, String actor) {
      configDeleted.set(true);
      calls.add("delete");
      return 1;
    }
  }
}
