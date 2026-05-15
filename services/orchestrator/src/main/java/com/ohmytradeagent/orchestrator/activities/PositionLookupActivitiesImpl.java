package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 3 OCC → workflow_id resolver. Two-tier lookup: Redis cache (24h TTL) → Visibility query
 * fallback (write-back to cache on hit). Visibility query uses {@code TenantStrategy} and {@code
 * ContractSymbol} custom Search Attributes (registered at Phase 0).
 */
@Component
public class PositionLookupActivitiesImpl implements PositionLookupActivities {

  static final Duration CACHE_TTL = Duration.ofSeconds(86400);
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

  private static Comparator<Instant> instantNullsLast() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }
}
