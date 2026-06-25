package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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

  /**
   * Phase 3 (2026-06-24): tenant-wide (any-strategy) Visibility query for {@link
   * #hasRunningOwnerForOcc}. Drops the exact {@code TenantStrategy} predicate in favor of a {@code
   * STARTS_WITH 't-<tenant>/s-'} prefix on the {@code TenantStrategy} Keyword SA so a sibling
   * strategy's RUNNING PositionWorkflow on the same OCC is matched. {@code STARTS_WITH} is a
   * supported operator on a custom Keyword Search Attribute under Advanced Visibility (this is NOT
   * a {@code WorkflowId STARTS_WITH}, which is forbidden).
   */
  static String tenantWideVisibilityQuery(String tenantId, String occ) {
    return String.format(
        "TenantStrategy STARTS_WITH '%s' AND ContractSymbol = '%s' AND ExecutionStatus = '%s' AND WorkflowType = '%s'",
        WorkflowIds.escapeForVisibilityQuery("t-" + tenantId + "/s-"),
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

  @Override
  public long sumRunningOwnerRemainingQtyForOcc(String tenantId, String occPadded) {
    // BEST-EFFORT / read-only: any systemic failure (e.g. a Redis SCAN outage) returns 0 — zero
    // coverage means recon pages, the safe degrade to pre-fix behavior (a false page, never a
    // masked genuine orphan). A per-owner query failure is contained below (skip that owner).
    try {
      Set<String> ownerWfIds = new LinkedHashSet<>();
      ScanOptions opts =
          ScanOptions.scanOptions().match("pos:" + tenantId + ":*:" + occPadded).count(256).build();
      try (Cursor<String> cursor = redis.scan(opts)) {
        while (cursor.hasNext()) {
          String wfId = redis.opsForValue().get(cursor.next());
          if (wfId != null) {
            ownerWfIds.add(wfId);
          }
        }
      }
      long sum = 0L;
      for (String wfId : ownerWfIds) {
        if (!isPositionWorkflowRunning(wfId)) {
          continue;
        }
        try {
          WorkflowStub stub = workflowClient.newUntypedWorkflowStub(wfId);
          PositionState state = stub.query("positionState", PositionState.class);
          if (state != null && state.remainingQty() > 0) {
            sum += state.remainingQty();
          }
        } catch (RuntimeException e) {
          // Skip this owner (treat its coverage as 0) — a since-closed owner or a query race must
          // not abort the whole sum. Mirrors the cacheArmedLeg best-effort warn pattern.
          log.warn(
              "sumRunningOwnerRemainingQtyForOcc owner positionState query failed wf_id={} occ={}"
                  + " err={}",
              wfId,
              occPadded,
              e.getMessage());
        }
      }
      return sum;
    } catch (RuntimeException e) {
      log.warn(
          "sumRunningOwnerRemainingQtyForOcc best-effort probe failed tenant={} occ={} err={}",
          tenantId,
          occPadded,
          e.getMessage());
      return 0L;
    }
  }

  @Override
  public boolean hasRunningOwnerForOcc(String tenantId, String occPadded) {
    // BEST-EFFORT / read-only: any Visibility outage returns false (no owner found → recon pages,
    // the safe degrade — a false page never masks a genuine orphan). Unlike the Redis SCAN this
    // reads Temporal Visibility directly, so it survives a cold/lagging pos:* cache. The query is
    // bounded to RUNNING PositionWorkflows for this OCC across ANY strategy of the tenant; the
    // mere existence of one such execution proves a sibling owner manages the lot.
    try {
      String query = tenantWideVisibilityQuery(tenantId, occPadded);
      return workflowClient.listExecutions(query).findAny().isPresent();
    } catch (RuntimeException e) {
      log.warn(
          "hasRunningOwnerForOcc best-effort Visibility probe failed tenant={} occ={} err={}",
          tenantId,
          occPadded,
          e.getMessage());
      return false;
    }
  }

  private static Comparator<Instant> instantNullsLast() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }
}
