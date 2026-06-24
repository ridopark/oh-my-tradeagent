package com.ohmytradeagent.tdbff.proximity;

// Mirrors PositionsReader's listExecutions + per-workflow query fan-out. Like that reader this is a
// READ-ONLY display with no cap to protect, so a per-workflow query race (a workflow terminating
// between the listExecutions and the query) is simply skipped best-effort, never a throw.
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Entry/exit proximity for a tenant's live watchlist legs and open positions, for the dashboard
 * {@code /live} view. The armed watchlist legs are enumerated from the Redis set the orchestrator
 * seeds on arm ({@link WorkflowIds#armedWatchlistCacheKey}) — robust against the Visibility-index
 * lag that misreports armed legs under postgres load. Open positions still use the {@code
 * TenantStrategy='t-<t>/s-<sid>' AND ExecutionStatus='Running'} Visibility query {@code
 * PositionsReader} uses. Both then fan out the {@code entryProximity} / {@code exitProximity} query
 * per workflow. Distances are computed here so the workflow queries stay deterministic (no clock
 * read).
 */
@Component
public class ProximityReader {

  private static final Logger log = LoggerFactory.getLogger(ProximityReader.class);
  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int PCT_SCALE = 4;
  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final WorkflowClient client;
  private final TenantStrategyResolver strategyResolver;
  private final StringRedisTemplate redis;

  public ProximityReader(
      WorkflowClient client, TenantStrategyResolver strategyResolver, StringRedisTemplate redis) {
    this.client = client;
    this.strategyResolver = strategyResolver;
    this.redis = redis;
  }

  /**
   * Live un-fired watchlist legs for the tenant, unioned across strategies, deduped by wf id.
   *
   * <p>Enumerates the armed-leg workflow ids from the Redis set the orchestrator seeds on arm
   * ({@link WorkflowIds#armedWatchlistCacheKey}) instead of a {@code listExecutions} visibility
   * query, which lags minutes under postgres load and misreports armed legs. Reads BOTH today's and
   * yesterday's (ET) keys and unions them, covering a leg armed just before midnight that the
   * dashboard views just after. The per-workflow {@code entryProximity} QUERY is unchanged (it is
   * not lag-affected); a wfId whose query returns null (gone/unarmed/race) is lazily {@code SREM}'d
   * from the key it came from.
   */
  public List<WatchlistProximity> watchlist(String tenantId) {
    LocalDate today = LocalDate.now(MARKET_TZ);
    LocalDate yesterday = today.minusDays(1);
    List<WatchlistProximity> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      String todayKey = WorkflowIds.armedWatchlistCacheKey(tenantId, strategyId, today);
      String yesterdayKey = WorkflowIds.armedWatchlistCacheKey(tenantId, strategyId, yesterday);
      // wfId -> the key it was read from, so a dead entry is SREM'd from its own key. today wins on
      // a dup (a leg present in both keys is treated as today's).
      Map<String, String> wfIdToKey = new LinkedHashMap<>();
      for (String wfId : smembers(yesterdayKey)) {
        wfIdToKey.put(wfId, yesterdayKey);
      }
      for (String wfId : smembers(todayKey)) {
        wfIdToKey.put(wfId, todayKey);
      }
      for (Map.Entry<String, String> e : wfIdToKey.entrySet()) {
        String wfId = e.getKey();
        if (!seen.add(wfId)) {
          continue;
        }
        try {
          WatchlistProximity w = entryProximity(wfId, strategyId);
          if (w != null) {
            out.add(w);
          } else {
            // Definitively gone/unarmed (workflow not found, or it answered with a blank ticker):
            // lazily evict from the key it came from so the set self-heals.
            srem(e.getValue(), wfId);
          }
        } catch (TransientQueryException ex) {
          // A transient query blip (timeout / worker restart / query rejected) is NOT proof the leg
          // is gone. Skip it for THIS poll but leave it in the set — the SADD happens once at arm
          // with no intraday re-seed, so an SREM here would permanently drop a still-live leg from
          // the dashboard for the rest of the day. It is retried next poll.
          log.warn(
              "entryProximity transient query failure, skipping without evict wf={} strategy={}"
                  + " err={}",
              wfId,
              strategyId,
              ex.getMessage());
        }
      }
    }
    return out;
  }

  private Set<String> smembers(String key) {
    Set<String> members = redis.opsForSet().members(key);
    return members == null ? Set.of() : members;
  }

  private void srem(String key, String wfId) {
    redis.opsForSet().remove(key, wfId);
  }

  /** Armed watchlist-exit positions for the tenant, unioned across strategies, deduped by wf id. */
  public List<PositionProximity> positions(String tenantId) {
    List<PositionProximity> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      try (Stream<WorkflowExecutionMetadata> stream =
          client.listExecutions(runningQuery(POSITION_WORKFLOW_TYPE, tenantId, strategyId))) {
        var it = stream.iterator();
        while (it.hasNext()) {
          String wfId = it.next().getExecution().getWorkflowId();
          if (!seen.add(wfId)) {
            continue;
          }
          PositionProximity p = exitProximity(wfId, strategyId);
          if (p != null) {
            out.add(p);
          }
        }
      }
    }
    return out;
  }

  private static String runningQuery(String workflowType, String tenantId, String strategyId) {
    return String.format(
        "WorkflowType='%s' AND TenantStrategy='%s' AND ExecutionStatus='Running'",
        workflowType,
        WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenantId, strategyId)));
  }

  /**
   * Queries one armed leg's entry proximity. Returns null when the leg is DEFINITIVELY gone (the
   * workflow is not found, or it answered with a blank ticker) — the caller evicts it. Throws
   * {@link TransientQueryException} on a transient query blip (timeout / worker restart / query
   * rejected), which is NOT proof the leg is gone — the caller skips it for this poll WITHOUT
   * evicting.
   */
  private WatchlistProximity entryProximity(String wfId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      EntryProximityView v = stub.query("entryProximity", EntryProximityView.class);
      if (v == null || v.ticker() == null || v.ticker().isBlank()) {
        return null;
      }
      return new WatchlistProximity(
          wfId,
          strategyId,
          v.ticker(),
          v.direction(),
          v.triggerLevel(),
          v.bandLow(),
          v.bandHigh(),
          v.lastPrice(),
          v.state(),
          distanceToTrigger(v),
          v.optionSymbol());
    } catch (WorkflowNotFoundException e) {
      // Definitive: no execution by this id. Evictable.
      log.warn("entryProximity workflow not found, evicting wf={} strategy={}", wfId, strategyId);
      return null;
    } catch (RuntimeException e) {
      // Transient (query timeout / worker blip / query rejected). Do NOT evict — retry next poll.
      throw new TransientQueryException(e);
    }
  }

  /**
   * Wraps a transient {@code entryProximity} query failure so the caller skips without evicting.
   */
  private static final class TransientQueryException extends RuntimeException {
    TransientQueryException(Throwable cause) {
      super(cause);
    }
  }

  private PositionProximity exitProximity(String wfId, String strategyId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      ExitProximityView v = stub.query("exitProximity", ExitProximityView.class);
      // Only watchlist-exit positions (armed) carry proximity levels; skip copytrade/unarmed.
      if (v == null || !v.armed() || v.contractSymbol() == null || v.contractSymbol().isBlank()) {
        return null;
      }
      return new PositionProximity(
          wfId,
          strategyId,
          v.contractSymbol(),
          v.entryPremium(),
          v.stopLevel(),
          v.targetLevel(),
          v.lastBid(),
          v.peakPremium(),
          v.trailingArmed(),
          pct(subtract(v.lastBid(), v.stopLevel()), v.lastBid()),
          pct(subtract(v.targetLevel(), v.lastBid()), v.lastBid()));
    } catch (RuntimeException e) {
      log.warn(
          "exitProximity query failed wf={} strategy={} err={}", wfId, strategyId, e.getMessage());
      return null;
    }
  }

  /**
   * Percent the underlying must still move toward the trigger to fire (positive = not yet crossed,
   * &lt;=0 = past it). Direction-aware: ABOVE needs price to rise to the trigger, BELOW to fall.
   */
  static Double distanceToTrigger(EntryProximityView v) {
    if (v.lastPrice() == null || v.triggerLevel() == null || v.triggerLevel().signum() == 0) {
      return null;
    }
    BigDecimal gap =
        "BELOW".equals(v.direction())
            ? v.lastPrice().subtract(v.triggerLevel())
            : v.triggerLevel().subtract(v.lastPrice());
    return pct(gap, v.triggerLevel());
  }

  private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
    return (a == null || b == null) ? null : a.subtract(b);
  }

  /**
   * Underlying root from an OCC option symbol (e.g. {@code NVDA 260516C00140000} or compact {@code
   * NVDA260516C00140000} -> {@code NVDA}). The OCC tail is fixed-width:
   * YYMMDD(6)+right(1)+strike(8) = 15 chars; the root is whatever precedes it (spaces stripped).
   * Returns null on a too-short / unparseable symbol so the caller skips the underlying-price
   * lookup.
   */
  public static String underlyingTicker(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replace(" ", "");
    if (compact.length() <= 15) {
      return null;
    }
    String root = compact.substring(0, compact.length() - 15);
    return root.isBlank() ? null : root;
  }

  /** {@code numerator / denominator * 100}, rounded; null if either operand is null or denom 0. */
  static Double pct(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator
        .divide(denominator, PCT_SCALE + 2, RoundingMode.HALF_UP)
        .multiply(HUNDRED)
        .setScale(PCT_SCALE, RoundingMode.HALF_UP)
        .doubleValue();
  }

  /** One live watchlist leg's entry proximity. */
  public record WatchlistProximity(
      String workflowId,
      String strategyId,
      String ticker,
      String direction,
      BigDecimal triggerLevel,
      BigDecimal bandLow,
      BigDecimal bandHigh,
      BigDecimal lastPrice,
      String state,
      Double distanceToTriggerPct,
      String optionSymbol) {}

  /** One armed position's exit proximity. */
  public record PositionProximity(
      String workflowId,
      String strategyId,
      String contractSymbol,
      BigDecimal entryPremium,
      BigDecimal stopLevel,
      BigDecimal targetLevel,
      BigDecimal lastBid,
      BigDecimal peakPremium,
      boolean trailingArmed,
      Double distanceToStopPct,
      Double distanceToTargetPct) {}

  /**
   * Transport mirror of the orchestrator's {@code EntryProximityView} query result (field names
   * must match) so the BFF deserializes the {@code entryProximity} query without a compile
   * dependency on the orchestrator module.
   */
  public record EntryProximityView(
      String ticker,
      String direction,
      BigDecimal triggerLevel,
      BigDecimal bandLow,
      BigDecimal bandHigh,
      BigDecimal lastPrice,
      String state,
      String optionSymbol) {}

  /** Transport mirror of the orchestrator's {@code ExitProximityView} query result. */
  public record ExitProximityView(
      String contractSymbol,
      BigDecimal entryPremium,
      BigDecimal stopLevel,
      BigDecimal targetLevel,
      BigDecimal lastBid,
      BigDecimal lastTickPremium,
      BigDecimal peakPremium,
      boolean trailingArmed,
      BigDecimal givebackPct,
      boolean armed,
      OffsetDateTime lastTickAt) {}
}
