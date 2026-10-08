package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On Spring start, scans the tenants directory and ensures one {@link KillSwitchWorkflow} is
 * running per {@code (tenant, strategy)} (plus one {@link AccountKillSwitchWorkflow} per tenant).
 *
 * <p>Issue #911: the ensure DESCRIBES the workflow and only counts it ensured when it is RUNNING. A
 * closed prior run (operator terminate, failure) is recreated — the starts use {@code
 * ALLOW_DUPLICATE}, because {@code REJECT_DUPLICATE} permanently refuses an id whose last run was
 * terminated, and that rejection used to be misread as "already running" while the account cap
 * stayed down. {@code ALLOW_DUPLICATE} still refuses a second CONCURRENT run (the default id
 * conflict policy is FAIL), so a warm boot never disturbs a running switch.
 *
 * <p>A recreate never fails open: the closed run's trip state is QUERIED (Temporal serves queries
 * on a closed run by replaying its history) and carried into the new run's input — the same
 * carry-forward fields continue-as-new uses, hydrated without re-running the trip cascade. If that
 * query fails, the new run starts TRIPPED ({@value #REASON_STATE_UNKNOWN}) for the operator to
 * reset through the audited path. Recreates, and ensures that fail {@link
 * #DOWN_PAGE_AFTER_FAILURES} times in a row, page via {@value #KIND_KILL_SWITCH_WORKFLOW_DOWN}.
 */
@Component
@Profile("!test")
public class KillSwitchBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KillSwitchBootstrapper.class);

  static final String KILLSWITCH_TASK_QUEUE = "orchestrator-core";

  static final String KIND_KILL_SWITCH_WORKFLOW_DOWN = "KillSwitchWorkflowDown";
  static final String ACTOR = "killswitch-bootstrapper";
  static final String REASON_STATE_UNKNOWN = "recreated_state_unknown";

  /** Consecutive failed ensures of one kill switch before it pages (once per episode). */
  static final int DOWN_PAGE_AFTER_FAILURES = 3;

  private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

  private final WorkflowClient workflowClient;
  private final AuditActivities audit;
  private final Path tenantsDir;

  public KillSwitchBootstrapper(
      WorkflowClient workflowClient,
      AuditActivities audit,
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.workflowClient = workflowClient;
    this.audit = audit;
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping KillSwitchWorkflow bootstrap", tenantsDir);
      return;
    }
    Set<String> tenantIds = new LinkedHashSet<>();
    for (TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      ensureStrategyKillSwitch(ts.tenantId(), ts.strategyId());
      tenantIds.add(ts.tenantId());
    }
    // Phase 6: one account-level kill switch per distinct tenant, alongside the per-strategy loop
    // above. Inert until the tenant sets account_daily_loss_threshold in tenant.yaml.
    for (String tenantId : tenantIds) {
      ensureAccountKillSwitch(tenantId);
    }
  }

  /**
   * Idempotent ensure of the per-{@code (tenant, strategy)} {@link KillSwitchWorkflow}: a RUNNING
   * switch is left alone, a missing or closed one is started. Returns {@code true} only when it is
   * confirmed RUNNING, so the reconcile loop retries a {@code false} next tick. Never throws.
   */
  public boolean ensureStrategyKillSwitch(String tenantId, String strategyId) {
    String wfId = WorkflowIds.killswitch(tenantId, strategyId);
    Map<String, Object> sa = new HashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenantId, strategyId));

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setWorkflowId(wfId)
            .setTaskQueue(KILLSWITCH_TASK_QUEUE)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .setSearchAttributes(sa)
            .build();

    return ensureRunning(
        wfId,
        tenantId,
        strategyId,
        "strategy",
        () -> workflowClient.newWorkflowStub(KillSwitchWorkflow.class, wfId).killswitchState(),
        carried -> {
          KillSwitchWorkflowInput input = new KillSwitchWorkflowInput();
          input.setSchemaVersion(carried == null ? 1L : 2L);
          input.setTenantId(tenantId);
          input.setStrategyId(strategyId);
          if (carried != null) {
            input.setTripped(carried.getTripped());
            input.setReason(carried.getReason());
            input.setActor(carried.getActor());
            input.setTrippedAt(carried.getTrippedAt());
            input.setCoolingDownUntil(carried.getCoolingDownUntil());
            input.setTradingDay(carried.getTradingDay());
          }
          KillSwitchWorkflow stub = workflowClient.newWorkflowStub(KillSwitchWorkflow.class, opts);
          WorkflowClient.start(stub::run, input);
        });
  }

  /**
   * Idempotent ensure of the per-tenant {@link AccountKillSwitchWorkflow}; same contract as {@link
   * #ensureStrategyKillSwitch}.
   */
  public boolean ensureAccountKillSwitch(String tenantId) {
    String wfId = WorkflowIds.accountKillswitch(tenantId);

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setWorkflowId(wfId)
            .setTaskQueue(KILLSWITCH_TASK_QUEUE)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build();

    return ensureRunning(
        wfId,
        tenantId,
        AccountKillSwitchWorkflowImpl.ACCOUNT_SCOPE,
        "account",
        () ->
            workflowClient.newWorkflowStub(AccountKillSwitchWorkflow.class, wfId).killswitchState(),
        carried -> {
          AccountKillSwitchWorkflowInput input = new AccountKillSwitchWorkflowInput();
          input.setSchemaVersion(carried == null ? 1L : 2L);
          input.setTenantId(tenantId);
          if (carried != null) {
            input.setTripped(carried.getTripped());
            input.setReason(carried.getReason());
            input.setActor(carried.getActor());
            input.setTrippedAt(carried.getTrippedAt());
            input.setCoolingDownUntil(carried.getCoolingDownUntil());
            input.setTradingDay(carried.getTradingDay());
          }
          AccountKillSwitchWorkflow stub =
              workflowClient.newWorkflowStub(AccountKillSwitchWorkflow.class, opts);
          WorkflowClient.start(stub::run, input);
        });
  }

  /**
   * Describe; if not RUNNING, start (carrying a closed run's state forward); record the outcome.
   * {@code start} receives {@code null} for a first-ever start. Never throws.
   */
  private boolean ensureRunning(
      String wfId,
      String tenantId,
      String strategyId,
      String scope,
      Supplier<KillSwitchState> queryClosedRun,
      Consumer<KillSwitchState> start) {
    WorkflowExecutionStatus prior = null;
    boolean running;
    try {
      prior = describeStatus(wfId);
      if (prior == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
        running = true;
      } else if (prior == null) {
        running = start(wfId, start, null);
        if (running) {
          log.info("started kill switch wf_id={}", wfId);
        }
      } else {
        boolean stateUnknown = false;
        KillSwitchState carried;
        try {
          carried = queryClosedRun.get();
          if (carried == null) {
            throw new IllegalStateException("closed run returned no state");
          }
        } catch (RuntimeException e) {
          log.error(
              "kill switch wf_id={} closed run state unreadable; recreating TRIPPED", wfId, e);
          stateUnknown = true;
          carried = new KillSwitchState();
          carried.setTripped(true);
          carried.setReason(REASON_STATE_UNKNOWN);
          carried.setActor(ACTOR);
          carried.setTrippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        running = start(wfId, start, carried);
        if (running) {
          log.warn(
              "kill switch wf_id={} was {}; recreated it (tripped={}, state_unknown={})",
              wfId,
              prior,
              carried.getTripped(),
              stateUnknown);
          Map<String, Object> extra = new LinkedHashMap<>();
          extra.put("tripped", Boolean.TRUE.equals(carried.getTripped()));
          extra.put("state_unknown", stateUnknown);
          page(wfId, tenantId, strategyId, scope, prior, true, 0, extra);
        }
      }
    } catch (RuntimeException e) {
      log.error("failed to ensure kill switch wf_id={}", wfId, e);
      running = false;
    }

    if (running) {
      consecutiveFailures.remove(wfId);
      return true;
    }
    int failures = consecutiveFailures.merge(wfId, 1, Integer::sum);
    log.warn("kill switch wf_id={} not running (prior={}, failures={})", wfId, prior, failures);
    if (failures == DOWN_PAGE_AFTER_FAILURES) {
      page(wfId, tenantId, strategyId, scope, prior, false, failures, Map.of());
    }
    return false;
  }

  /**
   * Starts; a start that lost a race to another starter counts only if a re-describe is RUNNING.
   */
  private boolean start(String wfId, Consumer<KillSwitchState> start, KillSwitchState carried) {
    try {
      start.accept(carried);
      return true;
    } catch (WorkflowExecutionAlreadyStarted raced) {
      return describeStatus(wfId) == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
    }
  }

  /** Latest-run status of {@code wfId}, or {@code null} when no run has ever existed. */
  private WorkflowExecutionStatus describeStatus(String wfId) {
    DescribeWorkflowExecutionRequest req =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(workflowClient.getOptions().getNamespace())
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wfId).build())
            .build();
    try {
      return workflowClient
          .getWorkflowServiceStubs()
          .blockingStub()
          .describeWorkflowExecution(req)
          .getWorkflowExecutionInfo()
          .getStatus();
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return null;
      }
      throw e;
    }
  }

  /** Best-effort {@value #KIND_KILL_SWITCH_WORKFLOW_DOWN} audit row; pages via the alerter. */
  private void page(
      String wfId,
      String tenantId,
      String strategyId,
      String scope,
      WorkflowExecutionStatus prior,
      boolean recreated,
      int failures,
      Map<String, Object> extra) {
    try {
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("scope", scope);
      subject.put(
          "prior_status",
          prior == null ? null : prior.name().replace("WORKFLOW_EXECUTION_STATUS_", ""));
      subject.put("recreated", recreated);
      subject.put("consecutive_failures", failures);
      subject.putAll(extra);

      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId(tenantId);
      event.setStrategyId(strategyId);
      event.setEventId(UUID.randomUUID().toString());
      event.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
      event.setKind(KIND_KILL_SWITCH_WORKFLOW_DOWN);
      event.setActor(ACTOR);
      event.setWorkflowId(wfId);
      event.setCorrelationId(wfId);
      event.setSubject(subject);
      audit.log(event);
    } catch (RuntimeException e) {
      log.warn("kill switch down page failed wf_id={}", wfId, e);
    }
  }
}
