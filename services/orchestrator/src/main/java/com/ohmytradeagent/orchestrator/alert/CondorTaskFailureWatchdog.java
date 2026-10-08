package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Issue #910: pages RED when a running {@code CondorSessionWorkflow} or {@code CondorHoldWorkflow}
 * is stuck in a workflow-task failure loop. In that state the workflow's code never runs, so no
 * audit kind it would emit can fire — on 2026-10-06 the session NPE'd on every task for 3+ hours
 * and paged nothing. The signal is read from outside instead: DescribeWorkflowExecution's pending
 * workflow task {@code attempt}, which the server increments on every failed (or timed-out) task.
 *
 * <p>Only RUNNING condor workflows are examined (one visibility query per type, the same shape as
 * {@code PositionHistoryLengthGauge}), so a day with no session — weekend, holiday, a strategy that
 * is not condor-enabled — checks nothing and pages nothing. Pages once per stuck run; a run that
 * recovers and sticks again pages again. Not workflow code — no replay constraints.
 */
@Component
@Profile("!test")
public class CondorTaskFailureWatchdog {

  private static final Logger log = LoggerFactory.getLogger(CondorTaskFailureWatchdog.class);

  static final String KIND_CONDOR_WORKFLOW_TASK_FAILING = "CondorWorkflowTaskFailing";
  static final String ACTOR = "condor-task-failure-watchdog";

  /** Attempt 1 is an ordinary task; 2 follows one failure; 3+ is a retry loop worth a page. */
  static final int FAILING_ATTEMPTS = 3;

  static final String SESSION_QUERY =
      "WorkflowType = 'CondorSessionWorkflow' AND ExecutionStatus = 'Running'";
  static final String HOLD_QUERY =
      "WorkflowType = 'CondorHoldWorkflow' AND ExecutionStatus = 'Running'";

  /** Condor workflow ids start {@code t-<tenant>/s-<strategy>/} ({@code WorkflowIds}). */
  private static final Pattern TENANT_STRATEGY = Pattern.compile("^t-([^/]+)/s-([^/]+)/");

  private final WorkflowClient workflowClient;
  private final AuditActivities audit;

  /** {@code workflowId/runId} of runs already paged for their current stuck episode. */
  private final Set<String> paged = ConcurrentHashMap.newKeySet();

  public CondorTaskFailureWatchdog(WorkflowClient workflowClient, AuditActivities audit) {
    this.workflowClient = workflowClient;
    this.audit = audit;
  }

  @Scheduled(
      fixedDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}",
      initialDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}")
  public void poll() {
    Map<WorkflowExecution, String> running = new LinkedHashMap<>();
    try {
      list(SESSION_QUERY).forEach(e -> running.put(e, "CondorSessionWorkflow"));
      list(HOLD_QUERY).forEach(e -> running.put(e, "CondorHoldWorkflow"));
    } catch (RuntimeException e) {
      log.warn("condor task-failure watchdog: listExecutions failed; skipping this tick", e);
      return;
    }

    Set<String> stillRunning = new HashSet<>();
    for (Map.Entry<WorkflowExecution, String> e : running.entrySet()) {
      WorkflowExecution exec = e.getKey();
      String key = exec.getWorkflowId() + "/" + exec.getRunId();
      stillRunning.add(key);
      int attempt;
      try {
        attempt = pendingTaskAttempt(exec);
      } catch (RuntimeException ex) {
        log.warn("condor task-failure watchdog: describe failed wf={}", exec.getWorkflowId(), ex);
        continue;
      }
      if (attempt < FAILING_ATTEMPTS) {
        paged.remove(key);
      } else if (!paged.contains(key)) {
        log.error(
            "condor workflow task failing wf={} run={} attempt={}",
            exec.getWorkflowId(),
            exec.getRunId(),
            attempt);
        if (page(exec, e.getValue(), attempt)) {
          paged.add(key);
        }
      }
    }
    paged.retainAll(stillRunning);
  }

  private List<WorkflowExecution> list(String query) {
    try (Stream<WorkflowExecutionMetadata> stream = workflowClient.listExecutions(query)) {
      return stream.map(WorkflowExecutionMetadata::getExecution).toList();
    }
  }

  /** The pending workflow task's attempt, or 0 when no task is pending (parked on a timer). */
  private int pendingTaskAttempt(WorkflowExecution exec) {
    DescribeWorkflowExecutionResponse resp =
        workflowClient
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(workflowClient.getOptions().getNamespace())
                    .setExecution(exec)
                    .build());
    return resp.hasPendingWorkflowTask() ? resp.getPendingWorkflowTask().getAttempt() : 0;
  }

  /**
   * Writes the {@value #KIND_CONDOR_WORKFLOW_TASK_FAILING} audit row, which pages via the alerter.
   * Returns false when the write failed, so the next tick tries again.
   */
  private boolean page(WorkflowExecution exec, String workflowType, int attempt) {
    try {
      Matcher m = TENANT_STRATEGY.matcher(exec.getWorkflowId());
      boolean parsed = m.find();
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("workflow_type", workflowType);
      subject.put("run_id", exec.getRunId());
      subject.put("attempt", attempt);

      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId(parsed ? m.group(1) : "unknown");
      event.setStrategyId(parsed ? m.group(2) : "unknown");
      event.setEventId(UUID.randomUUID().toString());
      event.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
      event.setKind(KIND_CONDOR_WORKFLOW_TASK_FAILING);
      event.setActor(ACTOR);
      event.setWorkflowId(exec.getWorkflowId());
      event.setCorrelationId(exec.getWorkflowId());
      event.setSubject(subject);
      audit.log(event);
      return true;
    } catch (RuntimeException e) {
      log.warn("condor task-failure page failed wf={}", exec.getWorkflowId(), e);
      return false;
    }
  }
}
