package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
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
 * <p>A recreate starts the switch fresh (a manual trip held by the terminated run is gone), and an
 * ensure that fails {@link #DOWN_PAGE_AFTER_FAILURES} times in a row leaves pre-trade checks
 * failing closed — both page via {@value #KIND_KILL_SWITCH_WORKFLOW_DOWN}.
 */
@Component
@Profile("!test")
public class KillSwitchBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KillSwitchBootstrapper.class);

  static final String KILLSWITCH_TASK_QUEUE = "orchestrator-core";

  static final String KIND_KILL_SWITCH_WORKFLOW_DOWN = "KillSwitchWorkflowDown";
  static final String ACTOR = "killswitch-bootstrapper";

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
      startKillSwitch(ts.tenantId(), ts.strategyId());
      tenantIds.add(ts.tenantId());
    }
    // Phase 6: one account-level kill switch per distinct tenant, alongside the per-strategy loop
    // above. Inert until the tenant sets account_daily_loss_threshold in tenant.yaml. (Both this
    // and the per-strategy start are exposed per-pair via ensureForTenantStrategy for the
    // restart-free reconcile loop.)
    for (String tenantId : tenantIds) {
      startAccountKillSwitch(tenantId);
    }
  }

  /**
   * Idempotent per-{@code (tenant, strategy)} ensure of the per-strategy {@link KillSwitchWorkflow}
   * and the per-tenant {@link AccountKillSwitchWorkflow}: a RUNNING switch is left alone, a missing
   * or closed one is started. Shared by the boot {@link #run} path and {@code TenantReconcileLoop},
   * which re-asserts it every tick.
   *
   * <p>Returns {@code true} only when BOTH kill-switches are confirmed RUNNING; {@code false}
   * otherwise, so the reconcile loop retries next tick. Both ensures are always attempted (no
   * short-circuit).
   */
  public boolean ensureForTenantStrategy(String tenantId, String strategyId) {
    boolean perStrategy = startKillSwitch(tenantId, strategyId);
    boolean perAccount = startAccountKillSwitch(tenantId);
    return perStrategy && perAccount;
  }

  private boolean startKillSwitch(String tenantId, String strategyId) {
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

    KillSwitchWorkflow stub = workflowClient.newWorkflowStub(KillSwitchWorkflow.class, opts);
    KillSwitchWorkflowInput input = new KillSwitchWorkflowInput();
    input.setSchemaVersion(1L);
    input.setTenantId(tenantId);
    input.setStrategyId(strategyId);

    return ensureRunning(
        wfId, tenantId, strategyId, "strategy", () -> WorkflowClient.start(stub::run, input));
  }

  private boolean startAccountKillSwitch(String tenantId) {
    String wfId = WorkflowIds.accountKillswitch(tenantId);

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setWorkflowId(wfId)
            .setTaskQueue(KILLSWITCH_TASK_QUEUE)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build();

    AccountKillSwitchWorkflow stub =
        workflowClient.newWorkflowStub(AccountKillSwitchWorkflow.class, opts);
    AccountKillSwitchWorkflowInput input = new AccountKillSwitchWorkflowInput();
    input.setSchemaVersion(1L);
    input.setTenantId(tenantId);

    return ensureRunning(
        wfId,
        tenantId,
        AccountKillSwitchWorkflowImpl.ACCOUNT_SCOPE,
        "account",
        () -> WorkflowClient.start(stub::run, input));
  }

  /** Describe, start if not RUNNING, and record the outcome. Never throws. */
  private boolean ensureRunning(
      String wfId, String tenantId, String strategyId, String scope, Runnable start) {
    WorkflowExecutionStatus prior = null;
    boolean running;
    try {
      prior = describeStatus(wfId);
      if (prior == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
        running = true;
      } else {
        try {
          start.run();
          running = true;
          if (prior == null) {
            log.info("started kill switch wf_id={}", wfId);
          } else {
            log.warn("kill switch wf_id={} was {}; recreated it", wfId, prior);
            page(wfId, tenantId, strategyId, scope, prior, true, 0);
          }
        } catch (WorkflowExecutionAlreadyStarted raced) {
          // Another starter won between the describe and the start; trust only a re-describe.
          running =
              describeStatus(wfId) == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
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
      page(wfId, tenantId, strategyId, scope, prior, false, failures);
    }
    return false;
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
      int failures) {
    try {
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("scope", scope);
      subject.put(
          "prior_status",
          prior == null ? null : prior.name().replace("WORKFLOW_EXECUTION_STATUS_", ""));
      subject.put("recreated", recreated);
      subject.put("consecutive_failures", failures);

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
