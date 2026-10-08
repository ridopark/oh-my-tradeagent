package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflow.v1.PendingWorkflowTaskInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #910: a condor workflow stuck in a workflow-task failure loop runs no workflow code, so no
 * audit it would emit can page. The watchdog sees it from outside — the pending workflow task's
 * attempt count on DescribeWorkflowExecution — and pages RED once per stuck run.
 */
class CondorTaskFailureWatchdogTest {

  private static final String SESSION_WF =
      "t-staging_paper/s-gated_condor/condor-session/-2026-10-06T13:20:00Z";
  private static final String HOLD_WF = "t-staging_paper/s-gated_condor/condor/2026-10-06";

  private WorkflowClient client;
  private WorkflowServiceBlockingStub blocking;
  private final List<AuditEvent> audits = new CopyOnWriteArrayList<>();
  private CondorTaskFailureWatchdog watchdog;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    blocking = mock(WorkflowServiceBlockingStub.class);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("copytrade").build());
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(stubs.blockingStub()).thenReturn(blocking);
    running(List.of(), List.of());
    AuditActivities audit = audits::add;
    watchdog = new CondorTaskFailureWatchdog(client, audit);
  }

  /** The 2026-10-06 shape: the session NPE'd on every workflow task for hours. Page RED, once. */
  @Test
  void wedgedSessionPagesRedOnceWithTenantStrategyAndAttempt() {
    running(List.of(SESSION_WF), List.of());
    attempts(SESSION_WF, 57);

    watchdog.poll();

    assertThat(audits)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getKind()).isEqualTo("CondorWorkflowTaskFailing");
              assertThat(e.getTenantId()).isEqualTo("staging_paper");
              assertThat(e.getStrategyId()).isEqualTo("gated_condor");
              assertThat(e.getWorkflowId()).isEqualTo(SESSION_WF);
              assertThat(e.getSubject())
                  .containsEntry("workflow_type", "CondorSessionWorkflow")
                  .containsEntry("attempt", 57)
                  .containsEntry("run_id", "run-" + SESSION_WF);
            });

    attempts(SESSION_WF, 58);
    watchdog.poll();
    assertThat(audits).hasSize(1); // still the same stuck run: no re-page every tick
  }

  @Test
  void wedgedHoldPagesToo() {
    running(List.of(), List.of(HOLD_WF));
    attempts(HOLD_WF, 4);

    watchdog.poll();

    assertThat(audits)
        .singleElement()
        .satisfies(
            e -> assertThat(e.getSubject()).containsEntry("workflow_type", "CondorHoldWorkflow"));
  }

  /** attempt 1 is an ordinary in-flight task; 2 is one failure. Paging starts at the threshold. */
  @Test
  void pagesOnlyFromTheAttemptThreshold() {
    running(List.of(SESSION_WF), List.of());
    attempts(SESSION_WF, CondorTaskFailureWatchdog.FAILING_ATTEMPTS - 1);
    watchdog.poll();
    assertThat(audits).isEmpty();

    attempts(SESSION_WF, CondorTaskFailureWatchdog.FAILING_ATTEMPTS);
    watchdog.poll();
    assertThat(audits).hasSize(1);
  }

  /** A healthy run with no pending workflow task (parked on a timer) never pages. */
  @Test
  void healthyRunNeverPages() {
    running(List.of(SESSION_WF), List.of(HOLD_WF));
    when(blocking.describeWorkflowExecution(any()))
        .thenReturn(DescribeWorkflowExecutionResponse.getDefaultInstance());

    watchdog.poll();

    assertThat(audits).isEmpty();
  }

  /** No session today (holiday, weekend, not condor-enabled): nothing to check, nothing paged. */
  @Test
  void noCondorWorkflowsRunningIsSilent() {
    watchdog.poll();

    verifyNoInteractions(blocking);
    assertThat(audits).isEmpty();
  }

  /** A run that recovers and later sticks again is a new episode and pages again. */
  @Test
  void recoveryThenNewWedgePagesAgain() {
    running(List.of(SESSION_WF), List.of());
    attempts(SESSION_WF, 5);
    watchdog.poll();
    attempts(SESSION_WF, 1);
    watchdog.poll();
    attempts(SESSION_WF, 3);
    watchdog.poll();

    assertThat(audits).hasSize(2);
  }

  /** One unreadable run does not hide another stuck one; a list failure never throws. */
  @Test
  void describeFailureOfOneRunDoesNotStarveOthers() {
    running(List.of(SESSION_WF), List.of(HOLD_WF));
    when(blocking.describeWorkflowExecution(argThat(r -> r != null && wf(r).equals(SESSION_WF))))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    attempts(HOLD_WF, 9);

    watchdog.poll();

    assertThat(audits)
        .singleElement()
        .satisfies(e -> assertThat(e.getWorkflowId()).isEqualTo(HOLD_WF));
  }

  /** A page whose audit write failed is not counted as sent; the next tick sends it. */
  @Test
  void failedAuditWriteIsRetriedNextTick() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    AuditActivities flaky =
        e -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("db down");
          }
          audits.add(e);
        };
    CondorTaskFailureWatchdog w = new CondorTaskFailureWatchdog(client, flaky);
    running(List.of(SESSION_WF), List.of());
    attempts(SESSION_WF, 10);

    w.poll();
    assertThat(audits).isEmpty();
    w.poll();
    assertThat(audits).hasSize(1);
  }

  @Test
  void listFailureIsSwallowed() {
    when(client.listExecutions(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    watchdog.poll();

    assertThat(audits).isEmpty();
  }

  private void running(List<String> sessions, List<String> holds) {
    when(client.listExecutions(CondorTaskFailureWatchdog.SESSION_QUERY))
        .thenAnswer(inv -> sessions.stream().map(CondorTaskFailureWatchdogTest::meta));
    when(client.listExecutions(CondorTaskFailureWatchdog.HOLD_QUERY))
        .thenAnswer(inv -> holds.stream().map(CondorTaskFailureWatchdogTest::meta));
  }

  private void attempts(String wfId, int attempt) {
    when(blocking.describeWorkflowExecution(argThat(r -> r != null && wf(r).equals(wfId))))
        .thenReturn(
            DescribeWorkflowExecutionResponse.newBuilder()
                .setPendingWorkflowTask(PendingWorkflowTaskInfo.newBuilder().setAttempt(attempt))
                .build());
  }

  private static String wf(io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest r) {
    return r.getExecution().getWorkflowId();
  }

  private static WorkflowExecutionMetadata meta(String wfId) {
    WorkflowExecutionMetadata m = mock(WorkflowExecutionMetadata.class);
    when(m.getExecution())
        .thenReturn(
            WorkflowExecution.newBuilder().setWorkflowId(wfId).setRunId("run-" + wfId).build());
    return m;
  }
}
