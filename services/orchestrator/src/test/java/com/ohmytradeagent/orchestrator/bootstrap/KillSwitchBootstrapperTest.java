package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #911: a terminated kill-switch workflow must be recreated by the ensure, not misread as
 * "already running". Runs against the in-memory Temporal test server so the workflow-id reuse
 * semantics are the server's, not a mock's.
 */
class KillSwitchBootstrapperTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "strat-a";

  private TestWorkflowEnvironment env;
  private WorkflowClient client;
  private final List<AuditEvent> audits = new CopyOnWriteArrayList<>();
  private final AuditActivities audit = audits::add;
  private KillSwitchBootstrapper bootstrapper;

  /** Minimal stand-in: runs forever, like the real impl, with no activities. */
  public static class ParkedKillSwitch implements KillSwitchWorkflow {
    @Override
    public String run(KillSwitchWorkflowInput input) {
      Workflow.await(() -> false);
      return null;
    }

    @Override
    public void tripValidator(TripKillSwitchRequest request) {}

    @Override
    public void trip(TripKillSwitchRequest request) {}

    @Override
    public void resetValidator(ResetKillSwitchRequest request) {}

    @Override
    public void reset(ResetKillSwitchRequest request) {}

    @Override
    public void resetOnActivationValidator(ResetKillSwitchRequest request) {}

    @Override
    public void resetOnActivation(ResetKillSwitchRequest request) {}

    @Override
    public KillSwitchState killswitchState() {
      return null;
    }
  }

  /** Minimal stand-in for the account cap. */
  public static class ParkedAccountKillSwitch implements AccountKillSwitchWorkflow {
    @Override
    public String run(AccountKillSwitchWorkflowInput input) {
      Workflow.await(() -> false);
      return null;
    }

    @Override
    public void tripValidator(TripKillSwitchRequest request) {}

    @Override
    public void trip(TripKillSwitchRequest request) {}

    @Override
    public void resetValidator(ResetKillSwitchRequest request) {}

    @Override
    public void reset(ResetKillSwitchRequest request) {}

    @Override
    public KillSwitchState killswitchState() {
      return null;
    }
  }

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    Worker worker = env.newWorker(KillSwitchBootstrapper.KILLSWITCH_TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        ParkedKillSwitch.class, ParkedAccountKillSwitch.class);
    env.start();
    client = env.getWorkflowClient();
    bootstrapper = new KillSwitchBootstrapper(client, audit, "does-not-exist");
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  /**
   * The 2026-10-06 incident shape: the account cap is running, an operator terminates it, and the
   * next ensure must bring it back RUNNING (pre-fix: REJECT_DUPLICATE refused the start and the
   * catch reported success while the cap stayed down).
   */
  @Test
  void terminatedAccountKillSwitchIsRecreatedAndPaged() {
    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();
    String accountWf = WorkflowIds.accountKillswitch(TENANT);
    client.newUntypedWorkflowStub(accountWf).terminate("operator roll onto new marker");
    assertThat(status(accountWf))
        .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED);

    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();

    assertThat(status(accountWf))
        .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    assertThat(audits).hasSize(1);
    AuditEvent page = audits.get(0);
    assertThat(page.getKind()).isEqualTo("KillSwitchWorkflowDown");
    assertThat(page.getTenantId()).isEqualTo(TENANT);
    assertThat(page.getWorkflowId()).isEqualTo(accountWf);
    assertThat(page.getSubject())
        .containsEntry("recreated", true)
        .containsEntry("prior_status", "TERMINATED")
        .containsEntry("scope", "account");
  }

  /** Same for the per-strategy switch (whose manual trip a recreate resets — hence the page). */
  @Test
  void terminatedStrategyKillSwitchIsRecreatedAndPaged() {
    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();
    String wf = WorkflowIds.killswitch(TENANT, STRATEGY);
    client.newUntypedWorkflowStub(wf).terminate("operator");

    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();

    assertThat(status(wf)).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    assertThat(audits)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getStrategyId()).isEqualTo(STRATEGY);
              assertThat(e.getSubject()).containsEntry("scope", "strategy");
            });
  }

  /** Already RUNNING is a benign no-op: same run kept, nothing paged. */
  @Test
  void alreadyRunningIsABenignNoOp() {
    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();
    String accountWf = WorkflowIds.accountKillswitch(TENANT);
    String runId = describe(accountWf).getWorkflowExecutionInfo().getExecution().getRunId();

    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();
    assertThat(bootstrapper.ensureForTenantStrategy(TENANT, STRATEGY)).isTrue();

    assertThat(describe(accountWf).getWorkflowExecutionInfo().getExecution().getRunId())
        .isEqualTo(runId);
    assertThat(audits).isEmpty();
  }

  /**
   * An ensure that cannot confirm RUNNING returns false (the reconcile loop retries) and pages
   * exactly once per kill switch when it has failed {@code DOWN_PAGE_AFTER_FAILURES} times in a
   * row.
   */
  @Test
  void persistentFailureReturnsFalseAndPagesOnceAfterN() {
    WorkflowClient unreachable = mock(WorkflowClient.class);
    when(unreachable.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    when(unreachable.getWorkflowServiceStubs())
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    KillSwitchBootstrapper down = new KillSwitchBootstrapper(unreachable, audit, "does-not-exist");

    for (int i = 1; i < KillSwitchBootstrapper.DOWN_PAGE_AFTER_FAILURES; i++) {
      assertThat(down.ensureForTenantStrategy(TENANT, STRATEGY)).isFalse();
    }
    assertThat(audits).isEmpty();

    assertThat(down.ensureForTenantStrategy(TENANT, STRATEGY)).isFalse();
    assertThat(audits).hasSize(2); // one per kill switch (strategy + account)
    assertThat(audits)
        .allSatisfy(
            e -> {
              assertThat(e.getKind()).isEqualTo("KillSwitchWorkflowDown");
              assertThat(e.getSubject())
                  .containsEntry("recreated", false)
                  .containsEntry(
                      "consecutive_failures", KillSwitchBootstrapper.DOWN_PAGE_AFTER_FAILURES);
            });

    assertThat(down.ensureForTenantStrategy(TENANT, STRATEGY)).isFalse();
    assertThat(audits).hasSize(2); // no re-page every tick
  }

  /**
   * Pins the server semantics the fix relies on: REJECT_DUPLICATE refuses a terminated id (the
   * trap), and ALLOW_DUPLICATE still refuses a second CONCURRENT run (conflict policy defaults to
   * FAIL), so dropping REJECT_DUPLICATE loses no single-instance guarantee.
   */
  @Test
  void reusePolicySemanticsThisFixReliesOn() {
    String wf = "reuse-policy-pin";
    start(wf, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
    assertThatThrownBy(
            () -> start(wf, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE))
        .isInstanceOf(WorkflowExecutionAlreadyStarted.class);

    client.newUntypedWorkflowStub(wf).terminate("pin");
    assertThatThrownBy(
            () -> start(wf, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE))
        .isInstanceOf(WorkflowExecutionAlreadyStarted.class);
    start(wf, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
    assertThat(status(wf)).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
  }

  private void start(String wfId, WorkflowIdReusePolicy policy) {
    AccountKillSwitchWorkflow stub =
        client.newWorkflowStub(
            AccountKillSwitchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(wfId)
                .setTaskQueue(KillSwitchBootstrapper.KILLSWITCH_TASK_QUEUE)
                .setWorkflowIdReusePolicy(policy)
                .build());
    WorkflowClient.start(stub::run, new AccountKillSwitchWorkflowInput());
  }

  private WorkflowExecutionStatus status(String wfId) {
    return describe(wfId).getWorkflowExecutionInfo().getStatus();
  }

  private DescribeWorkflowExecutionResponse describe(String wfId) {
    return client
        .getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wfId).build())
                .build());
  }
}
