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
import io.temporal.workflow.WorkflowInit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /** Makes the NEXT killswitch_state query throw (a closed run whose history cannot replay). */
  static final AtomicBoolean FAIL_NEXT_QUERY = new AtomicBoolean();

  /**
   * Minimal stand-in: runs forever, like the real impl, with no activities. Its query echoes the
   * carried input, so a test can see what a recreate started the new run with.
   */
  public static class ParkedKillSwitch implements KillSwitchWorkflow {
    private final KillSwitchWorkflowInput input;

    @WorkflowInit
    public ParkedKillSwitch(KillSwitchWorkflowInput input) {
      this.input = input;
    }

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
      if (FAIL_NEXT_QUERY.getAndSet(false)) {
        throw new IllegalStateException("history replay failed");
      }
      KillSwitchState s = new KillSwitchState();
      s.setTripped(Boolean.TRUE.equals(input.getTripped()));
      s.setReason(input.getReason());
      s.setActor(input.getActor());
      return s;
    }
  }

  /** Minimal stand-in for the account cap (never tripped). */
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
      KillSwitchState s = new KillSwitchState();
      s.setTripped(false);
      return s;
    }
  }

  @BeforeEach
  void setUp() {
    FAIL_NEXT_QUERY.set(false);
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
    assertThat(ensureBoth(bootstrapper)).isTrue();
    String accountWf = WorkflowIds.accountKillswitch(TENANT);
    settle();
    client.newUntypedWorkflowStub(accountWf).terminate("operator roll onto new marker");
    assertThat(status(accountWf))
        .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED);

    assertThat(ensureBoth(bootstrapper)).isTrue();

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
        .containsEntry("scope", "account")
        .containsEntry("tripped", false)
        .containsEntry("state_unknown", false);
  }

  /**
   * (c) The closed run's state cannot be read → the new run starts TRIPPED ({@code
   * recreated_state_unknown}) and the recreate page says the state was unknown. Never fail open.
   */
  @Test
  void unreadableClosedRunStateRecreatesTrippedAndPages() {
    assertThat(ensureBoth(bootstrapper)).isTrue();
    String wf = WorkflowIds.killswitch(TENANT, STRATEGY);
    settle();
    client.newUntypedWorkflowStub(wf).terminate("operator");
    FAIL_NEXT_QUERY.set(true);

    assertThat(ensureBoth(bootstrapper)).isTrue();

    KillSwitchState now = client.newWorkflowStub(KillSwitchWorkflow.class, wf).killswitchState();
    assertThat(now.getTripped()).isTrue();
    assertThat(now.getReason()).isEqualTo(KillSwitchBootstrapper.REASON_STATE_UNKNOWN);
    assertThat(now.getActor()).isEqualTo(KillSwitchBootstrapper.ACTOR);
    assertThat(audits)
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getSubject())
                    .containsEntry("recreated", true)
                    .containsEntry("tripped", true)
                    .containsEntry("state_unknown", true));
  }

  /** Same for the per-strategy switch (whose manual trip a recreate resets — hence the page). */
  @Test
  void terminatedStrategyKillSwitchIsRecreatedAndPaged() {
    assertThat(ensureBoth(bootstrapper)).isTrue();
    String wf = WorkflowIds.killswitch(TENANT, STRATEGY);
    settle();
    client.newUntypedWorkflowStub(wf).terminate("operator");

    assertThat(ensureBoth(bootstrapper)).isTrue();

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
    assertThat(ensureBoth(bootstrapper)).isTrue();
    String accountWf = WorkflowIds.accountKillswitch(TENANT);
    String runId = describe(accountWf).getWorkflowExecutionInfo().getExecution().getRunId();

    assertThat(ensureBoth(bootstrapper)).isTrue();
    assertThat(ensureBoth(bootstrapper)).isTrue();

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
      assertThat(ensureBoth(down)).isFalse();
    }
    assertThat(audits).isEmpty();

    assertThat(ensureBoth(down)).isFalse();
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

    assertThat(ensureBoth(down)).isFalse();
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

  /**
   * Queries both running switches so their first workflow task has completed before a terminate —
   * the SDK cannot replay a history whose first task never completed, so a query of that closed run
   * fails (which the bootstrapper rightly treats as unknown state).
   */
  private void settle() {
    client
        .newWorkflowStub(KillSwitchWorkflow.class, WorkflowIds.killswitch(TENANT, STRATEGY))
        .killswitchState();
    client
        .newWorkflowStub(AccountKillSwitchWorkflow.class, WorkflowIds.accountKillswitch(TENANT))
        .killswitchState();
  }

  private static boolean ensureBoth(KillSwitchBootstrapper b) {
    boolean perStrategy = b.ensureStrategyKillSwitch(TENANT, STRATEGY);
    boolean perAccount = b.ensureAccountKillSwitch(TENANT);
    return perStrategy && perAccount;
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
