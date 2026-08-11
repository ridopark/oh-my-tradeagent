package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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
  private final TenantStrategies tenantStrategies;
  private final StrategyRegistry strategyRegistry;

  public PositionLookupActivitiesImpl(
      StringRedisTemplate redis,
      WorkflowClient workflowClient,
      TenantStrategies tenantStrategies,
      StrategyRegistry strategyRegistry) {
    this.redis = redis;
    this.workflowClient = workflowClient;
    this.tenantStrategies = tenantStrategies;
    this.strategyRegistry = strategyRegistry;
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
   * Edited-signal supersede (F1): enumerate ALL RUNNING PositionWorkflows for a (tenant, strategy)
   * — NO {@code ContractSymbol} predicate, because the supersede match is expiry-agnostic and
   * {@code ContractSymbol} is equality-only. The in-process filter (underlying+strike+right,
   * different expiry) is applied on each enumerated owner's {@code positionState} query.
   */
  static String tenantStrategyRunningQuery(String tenantId, String strategyId) {
    return String.format(
        "TenantStrategy = '%s' AND ExecutionStatus = '%s' AND WorkflowType = '%s'",
        WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenantId, strategyId)),
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

  /**
   * Fan-out primitive for the STC exit path — see {@link
   * PositionLookupActivities#findAllPositionWorkflowIds} for why one OCC can have several legs and
   * why neither source alone is sufficient.
   *
   * <p>Visibility supplies the authoritative set (oldest first, so the exit order matches entry
   * order); the Redis pointer is appended only when Visibility has not caught up with it yet. Both
   * are already-proven idioms in this class: the {@code ContractSymbol = occ} equality query with a
   * try-with-resources paging stream, and the {@code pos:} key.
   *
   * <p>NOT best-effort: a Visibility failure propagates. The caller is dispatching an EXIT, and
   * silently returning a short list would leave real legs open while the audit says the STC was
   * handled — worse than a retried activity.
   */
  @Override
  public List<String> findAllPositionWorkflowIds(String tenantId, String strategyId, String occ) {
    Set<String> ids = new LinkedHashSet<>();
    try (Stream<WorkflowExecutionMetadata> stream =
        workflowClient.listExecutions(visibilityQuery(tenantId, strategyId, occ))) {
      stream
          .sorted(Comparator.comparing(WorkflowExecutionMetadata::getStartTime, instantNullsLast()))
          .forEach(e -> ids.add(e.getExecution().getWorkflowId()));
    }
    // The cached pointer is the most RECENT leg, which is precisely the one a lagging Visibility
    // index can omit. LinkedHashSet keeps it out when Visibility already listed it.
    String cached = redis.opsForValue().get(key(tenantId, strategyId, occ));
    if (cached != null) {
      ids.add(cached);
    }
    return List.copyOf(ids);
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
    // reads Temporal Visibility directly, so it survives a cold/lagging pos:* cache.
    //
    // Tenant-wide enumeration reuses the proven #323 idiom (AccountKillSwitchCascadeActivitiesImpl
    // /
    // AccountPnlActivitiesImpl): resolve the tenant's strategy ids, then run ONE per-strategy
    // `TenantStrategy='...'` EQUALITY query against the shared ContractSymbol. Temporal SQL
    // Visibility supports neither `STARTS_WITH` nor `IN (...)`, so a per-strategy equality loop is
    // the only correct way to span all of a tenant's strategies. Short-circuits on the first hit.
    try {
      for (String sid : tenantStrategies.strategyIdsForTenant(tenantId)) {
        if (sid == null || sid.isBlank()) {
          continue;
        }
        if (anyRunningOwner(tenantId, sid, occPadded)) {
          return true;
        }
      }
      return false;
    } catch (RuntimeException e) {
      log.warn(
          "hasRunningOwnerForOcc best-effort Visibility probe failed tenant={} occ={} err={}",
          tenantId,
          occPadded,
          e.getMessage());
      return false;
    }
  }

  /**
   * The proven #323 Visibility-equality probe: is any RUNNING PositionWorkflow on {@code (tenantId,
   * strategyId)} managing {@code occPadded}? Runs ONE {@code TenantStrategy='...' AND
   * ContractSymbol = occPadded} equality query and closes the gRPC paging stream
   * (try-with-resources) per the idiom in {@link AccountKillSwitchCascadeActivitiesImpl} / {@link
   * AccountPnlActivitiesImpl}. Shared by the tenant-scoped {@link #hasRunningOwnerForOcc} and the
   * account-scoped {@link #hasRunningOwnerForOccOnAccount} so the two never drift. Exceptions
   * propagate to the caller's best-effort catch.
   */
  private boolean anyRunningOwner(String tenantId, String strategyId, String occPadded) {
    try (Stream<WorkflowExecutionMetadata> stream =
        workflowClient.listExecutions(visibilityQuery(tenantId, strategyId, occPadded))) {
      return stream.findAny().isPresent();
    }
  }

  @Override
  public boolean hasRunningOwnerForOccOnAccount(String brokerAccountId, String occPadded) {
    // Phase F2b: ACCOUNT-scoped (cross-TENANT) sibling-owner probe. BEST-EFFORT / read-only: any
    // error (or a blank account) returns false (no owner found → recon pages, the safe degrade — a
    // false page never masks a genuine orphan). Spans every tenant on the SAME broker account so a
    // sibling-TENANT owner on a shared brokerage account is found (the #477 fix was TENANT-scoped).
    //
    // Temporal SQL Visibility supports neither STARTS_WITH nor IN(...), so spanning all accounts
    // via
    // one query is impossible. Instead enumerate every (tenant, strategy) the registry knows, keep
    // only those whose resolved StrategyConfig.broker_account_id equals brokerAccountId, and run
    // the
    // proven per-(tenant,strategy) `ContractSymbol = occPadded` EQUALITY query (the #323 idiom
    // reused
    // by hasRunningOwnerForOcc). Short-circuit on the first running owner.
    if (brokerAccountId == null || brokerAccountId.isBlank()) {
      return false;
    }
    try {
      for (TenantStrategy ts : strategyRegistry.list()) {
        if (ts == null || ts.tenantId() == null || ts.strategyId() == null) {
          continue;
        }
        String account;
        try {
          StrategyConfig cfg = strategyRegistry.get(ts.tenantId(), ts.strategyId());
          account = cfg == null ? null : cfg.getBrokerAccountId();
        } catch (RuntimeException e) {
          // A single unreadable config must not abort the cross-account scan — skip this pair.
          log.warn(
              "hasRunningOwnerForOccOnAccount config load failed tenant={} strategy={} err={}",
              ts.tenantId(),
              ts.strategyId(),
              e.getMessage());
          continue;
        }
        if (account == null || account.isBlank() || !account.trim().equals(brokerAccountId)) {
          continue;
        }
        if (anyRunningOwner(ts.tenantId(), ts.strategyId(), occPadded)) {
          return true;
        }
      }
      return false;
    } catch (RuntimeException e) {
      log.warn(
          "hasRunningOwnerForOccOnAccount best-effort probe failed account={} occ={} err={}",
          brokerAccountId,
          occPadded,
          e.getMessage());
      return false;
    }
  }

  @Override
  public SupersedeCandidate findOpenPositionByUnderlyingStrikeRight(
      String tenantId,
      String strategyId,
      String underlying,
      BigDecimal strike,
      String right,
      String correctedExpiryDay) {
    // BEST-EFFORT / read-only: any failure returns null (no supersede — never auto-cancels a live
    // trade on a probe error). The window + partial-exited guardrails are applied by the caller.
    if (underlying == null || strike == null || right == null) {
      return null;
    }
    BigDecimal wantStrike = strike.stripTrailingZeros();
    try {
      String query = tenantStrategyRunningQuery(tenantId, strategyId);
      // Earliest-started match wins (the leg most likely to be the just-placed-then-corrected one).
      // Stream the Visibility paging iterator (close it per the #323 idiom), reading each owner's
      // positionState to match underlying+strike+right with a DIFFERENT expiry than the correction.
      List<WorkflowExecutionMetadata> owners;
      try (Stream<WorkflowExecutionMetadata> stream = workflowClient.listExecutions(query)) {
        owners =
            stream
                .sorted(
                    Comparator.comparing(
                        WorkflowExecutionMetadata::getStartTime, instantNullsLast()))
                .toList();
      }
      for (WorkflowExecutionMetadata meta : owners) {
        String wfId = meta.getExecution().getWorkflowId();
        PositionState state;
        try {
          state =
              workflowClient
                  .newUntypedWorkflowStub(wfId)
                  .query("positionState", PositionState.class);
        } catch (RuntimeException e) {
          // A since-closed owner or a query race must not abort the whole scan — skip this owner.
          log.warn(
              "findOpenPositionByUnderlyingStrikeRight positionState query failed wf_id={} err={}",
              wfId,
              e.getMessage());
          continue;
        }
        if (state == null || state.remainingQty() <= 0) {
          continue;
        }
        if (matchesSupersedeTarget(
            state.contractSymbol(), underlying, wantStrike, right, correctedExpiryDay)) {
          return new SupersedeCandidate(
              wfId, state.contractSymbol(), state.entryAt(), state.partialExited());
        }
      }
      return null;
    } catch (RuntimeException e) {
      log.warn(
          "findOpenPositionByUnderlyingStrikeRight best-effort probe failed tenant={} strategy={}"
              + " underlying={} err={}",
          tenantId,
          strategyId,
          underlying,
          e.getMessage());
      return null;
    }
  }

  /**
   * F1 supersede contract-identity predicate (pure, no I/O): the candidate OCC matches the
   * corrected signal on underlying (case-insensitive root) + strike + right, AND carries a
   * DIFFERENT expiry day. A same-expiry/same-OCC candidate is the existing OCC-exact dedup path,
   * NOT a supersede target. Returns false on a blank/unparseable candidate OCC (fail-safe — an
   * unmatchable leg is never superseded).
   */
  static boolean matchesSupersedeTarget(
      String occ,
      String underlying,
      BigDecimal wantStrike,
      String right,
      String correctedExpiryDay) {
    if (occ == null || occ.isBlank()) {
      return false;
    }
    String candUnderlying = OccSymbol.underlying(occ);
    BigDecimal candStrike = OccSymbol.strikeOf(occ);
    String candRight = OccSymbol.rightOf(occ);
    LocalDate candExpiry = OccSymbol.expiryOf(occ);
    if (candUnderlying == null || candStrike == null || candRight == null || candExpiry == null) {
      return false;
    }
    return candUnderlying.equalsIgnoreCase(underlying)
        && candStrike.compareTo(wantStrike) == 0
        && candRight.equals(right)
        && !candExpiry.toString().equals(correctedExpiryDay);
  }

  private static Comparator<Instant> instantNullsLast() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }
}
