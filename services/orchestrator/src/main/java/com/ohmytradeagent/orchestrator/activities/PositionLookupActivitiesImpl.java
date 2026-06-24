package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 3 OCC → workflow_id resolver. Two-tier lookup: Redis cache (24h TTL) → Visibility query
 * fallback (write-back to cache on hit). Visibility query uses {@code TenantStrategy} and {@code
 * ContractSymbol} custom Search Attributes (registered at Phase 0).
 */
@Component
public class PositionLookupActivitiesImpl implements PositionLookupActivities {

  private static final Logger log = LoggerFactory.getLogger(PositionLookupActivitiesImpl.class);

  static final Duration CACHE_TTL = Duration.ofSeconds(86400);
  static final Duration ARMED_CACHE_TTL = Duration.ofDays(2);
  static final String WORKFLOW_TYPE = "PositionWorkflow";

  private final StringRedisTemplate redis;
  private final WorkflowClient workflowClient;

  public PositionLookupActivitiesImpl(StringRedisTemplate redis, WorkflowClient workflowClient) {
    this.redis = redis;
    this.workflowClient = workflowClient;
  }

  static String key(String tenantId, String strategyId, String occ) {
    return "pos:" + tenantId + ":" + strategyId + ":" + occ;
  }

  static String visibilityQuery(String tenantId, String strategyId, String occ) {
    return String.format(
        "TenantStrategy = '%s' AND ContractSymbol = '%s' AND ExecutionStatus = '%s' AND WorkflowType = '%s'",
        WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenantId, strategyId)),
        WorkflowIds.escapeForVisibilityQuery(occ),
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING.name(),
        WORKFLOW_TYPE);
  }

  @Override
  public String findPositionWorkflowId(String tenantId, String strategyId, String occ) {
    String cacheKey = key(tenantId, strategyId, occ);
    String cached = redis.opsForValue().get(cacheKey);
    if (cached != null) {
      return cached;
    }
    String query = visibilityQuery(tenantId, strategyId, occ);
    Optional<WorkflowExecutionMetadata> earliest =
        workflowClient
            .listExecutions(query)
            .min(Comparator.comparing(WorkflowExecutionMetadata::getStartTime, instantNullsLast()));
    if (earliest.isEmpty()) {
      return null;
    }
    String workflowId = earliest.get().getExecution().getWorkflowId();
    redis.opsForValue().set(cacheKey, workflowId, CACHE_TTL);
    return workflowId;
  }

  @Override
  public void cachePositionMapping(
      String tenantId, String strategyId, String occ, String workflowId) {
    redis.opsForValue().set(key(tenantId, strategyId, occ), workflowId, CACHE_TTL);
  }

  @Override
  public void cacheArmedLeg(
      String tenantId, String strategyId, java.time.LocalDate etDate, String workflowId) {
    // BEST-EFFORT: the armed-watchlist set is a display hint for the BFF, never a gate on arming.
    // Swallow + log any Redis failure so this activity ALWAYS returns normally — a Redis outage can
    // never fail or stall the live WatchlistTriggerWorkflow's arm. SADD is idempotent (arm happens
    // once; stable workflow id across continue-as-new), so a retry/replay is a safe no-op.
    String armedKey = WorkflowIds.armedWatchlistCacheKey(tenantId, strategyId, etDate);
    try {
      redis.opsForSet().add(armedKey, workflowId);
      redis.expire(armedKey, ARMED_CACHE_TTL);
    } catch (RuntimeException e) {
      log.warn(
          "cacheArmedLeg best-effort seed failed key={} wf_id={} err={}",
          armedKey,
          workflowId,
          e.getMessage());
    }
  }

  @Override
  public boolean isPositionWorkflowRunning(String workflowId) {
    // Issue #165 Phase 3: probe Temporal directly for the latest execution status of this id.
    // Visibility lags behind the durable history (eventually consistent), so describe is the
    // only signal the recon loop can trust on a per-cycle basis.
    String namespace = workflowClient.getOptions().getNamespace();
    DescribeWorkflowExecutionRequest req =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
            .build();
    try {
      DescribeWorkflowExecutionResponse resp =
          workflowClient.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(req);
      WorkflowExecutionStatus status = resp.getWorkflowExecutionInfo().getStatus();
      return status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return false;
      }
      log.warn(
          "describeWorkflowExecution failed during isPositionWorkflowRunning probe wf_id={}"
              + " status={}",
          workflowId,
          e.getStatus(),
          e);
      // Conservative: treat unknown as "not running" so recon emits a PositionOrphan rather than
      // silently dropping a possibly-orphan signal. Operator's runbook covers the false-positive.
      return false;
    }
  }

  private static Comparator<Instant> instantNullsLast() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }
}
