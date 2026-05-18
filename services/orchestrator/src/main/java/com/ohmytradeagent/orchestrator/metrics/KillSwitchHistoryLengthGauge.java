package com.ohmytradeagent.orchestrator.metrics;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.bootstrap.TenantStrategyScanner;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code DescribeWorkflowExecution} for every bootstrapped {@code KillSwitchWorkflow} and
 * publishes the {@code history_length} field as a Prometheus gauge ({@code
 * temporal_workflow_history_length{workflow_type="KillSwitchWorkflow",tenant_id,strategy_id}}).
 *
 * <p>The gauge cannot live inside the workflow body because {@link
 * io.temporal.workflow.Workflow#getInfo()} is workflow-only API; emitting a metric activity from
 * inside the workflow would itself add to the very history we are trying to bound. Polling from a
 * Spring {@code @Scheduled} bean keeps the read out-of-band.
 *
 * <p>Scrape cadence matches {@link
 * com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflowImpl#HEARTBEAT_INTERVAL} so the
 * gauge's freshness aligns with each tick of the workflow's own loop. Issue #124.
 */
@Component
@Profile("!test")
public class KillSwitchHistoryLengthGauge {

  /** Reported value when {@code DescribeWorkflowExecution} returns {@code NOT_FOUND}. */
  static final long NOT_FOUND_VALUE = -1L;

  private static final Logger log = LoggerFactory.getLogger(KillSwitchHistoryLengthGauge.class);
  private static final String METRIC_NAME = "temporal_workflow_history_length";
  private static final String WORKFLOW_TYPE = "KillSwitchWorkflow";

  private final WorkflowServiceStubs serviceStubs;
  private final WorkflowClient workflowClient;
  private final MeterRegistry meterRegistry;
  private final Path tenantsDir;
  private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

  public KillSwitchHistoryLengthGauge(
      WorkflowServiceStubs serviceStubs,
      WorkflowClient workflowClient,
      MeterRegistry meterRegistry,
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.serviceStubs = serviceStubs;
    this.workflowClient = workflowClient;
    this.meterRegistry = meterRegistry;
    this.tenantsDir = Path.of(tenantsDir);
  }

  /**
   * Polls every workflow under {@code tenants/} and publishes the latest history length. Runs on a
   * 60s cadence; {@code initialDelay} is 60s so the first scrape happens after the bootstrappers
   * have had a chance to start their workflows.
   */
  @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
  public void poll() {
    if (!Files.exists(tenantsDir)) {
      return;
    }
    String namespace = workflowClient.getOptions().getNamespace();
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      String wfId = WorkflowIds.killswitch(ts.tenantId(), ts.strategyId());
      long historyLength = describeHistoryLength(namespace, wfId);
      AtomicLong ref =
          values.computeIfAbsent(wfId, k -> registerGauge(ts.tenantId(), ts.strategyId(), k));
      ref.set(historyLength);
    }
  }

  /**
   * Returns the current history length for {@code wfId}, or {@link #NOT_FOUND_VALUE} if the
   * workflow does not exist (e.g. during the homelab recovery window after a wedged-and-terminated
   * run is cleared but before {@code KillSwitchBootstrapper} restarts it). All other gRPC failures
   * are logged and surfaced as {@link #NOT_FOUND_VALUE} so the Prometheus scrape stays healthy.
   */
  private long describeHistoryLength(String namespace, String wfId) {
    DescribeWorkflowExecutionRequest req =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wfId).build())
            .build();
    try {
      DescribeWorkflowExecutionResponse resp =
          serviceStubs.blockingStub().describeWorkflowExecution(req);
      return resp.getWorkflowExecutionInfo().getHistoryLength();
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return NOT_FOUND_VALUE;
      }
      log.warn("describeWorkflowExecution failed wf_id={} status={}", wfId, e.getStatus(), e);
      return NOT_FOUND_VALUE;
    } catch (RuntimeException e) {
      log.warn("describeWorkflowExecution failed wf_id={}", wfId, e);
      return NOT_FOUND_VALUE;
    }
  }

  private AtomicLong registerGauge(String tenantId, String strategyId, String wfId) {
    AtomicLong value = new AtomicLong(NOT_FOUND_VALUE);
    Gauge.builder(METRIC_NAME, value, AtomicLong::doubleValue)
        .tag("workflow_type", WORKFLOW_TYPE)
        .tag("tenant_id", tenantId)
        .tag("strategy_id", strategyId)
        .description("History event count for the named workflow run (DescribeWorkflowExecution).")
        .register(meterRegistry);
    log.info("registered {} gauge wf_id={}", METRIC_NAME, wfId);
    return value;
  }
}
