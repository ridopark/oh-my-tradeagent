package com.ohmytradeagent.orchestrator.alert.overnighttrail;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import com.ohmytradeagent.orchestrator.workflows.TrailingState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #747 (Fork N, operator decision 2026-10-08): at 15:40 ET each weekday, one {@code
 * OvernightTrailHeld} notice per ARMED chandelier trail that will be held overnight, so the
 * operator decides — flatten, tighten, or accept — before the close. The stop is best-effort across
 * the overnight gap: a gap open fires it at the gapped bid.
 *
 * <p>Modelled on {@code FloorBreachAlertLoop}: a plain Spring {@code @Scheduled} bean outside
 * Temporal workflow history. Per (tenant, strategy) it lists running {@code PositionWorkflow}s via
 * the Visibility equality query, reads the read-only {@code trailingState} and {@code
 * positionState} queries, fetches the live bid, and emits an audit event; the Discord notice rides
 * the {@code OrderFailureAlerter} after-commit funnel. <b>It places, modifies and cancels
 * nothing</b> and sends no Signal or Update (source-scan guard: {@code
 * FloorBreachNoTradingActionGuardTest}).
 *
 * <p>A position qualifies when its trail is armed, it still holds contracts, its contract does not
 * expire today (an expiring contract closes today), and its strategy does not set {@code
 * eod_force_flatten: true} (the 15:55 flatten closes it). Best-effort everywhere: a failed listing
 * or query skips only that scope; a missing quote still sends the notice with a null bid.
 *
 * <p>Known limits, by design: on half-days 15:40 is after the 13:00 close, and on market holidays
 * the cron still runs — the notice is then redundant but harmless.
 */
@Component
@Profile("!test")
public class OvernightTrailNoticeLoop {

  private static final Logger log = LoggerFactory.getLogger(OvernightTrailNoticeLoop.class);

  static final String KIND_OVERNIGHT_TRAIL_HELD = "OvernightTrailHeld";
  static final String ACTOR = "overnight-trail-notice";
  static final String NOTE =
      "stop is best-effort across the overnight gap; realized givebacks on recorded gap fires:"
          + " 47% (cfg 25%), 52% (cfg 45%)";

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final StrategyRegistry registry;
  private final WorkflowClient client;
  private final MarketDataOptionQuoteClient quoteClient;
  private final AuditActivities audit;
  private final Clock clock;
  private final boolean enabled;

  /** {@code workflowId|etDate} already noticed; one notice per position per ET date. */
  private final Set<String> noticed = ConcurrentHashMap.newKeySet();

  private final ReentrantLock runLock = new ReentrantLock();

  public OvernightTrailNoticeLoop(
      StrategyRegistry registry,
      WorkflowClient client,
      MarketDataOptionQuoteClient quoteClient,
      AuditActivities audit,
      Clock clock,
      @Value("${alert.overnight-trail.enabled:true}") boolean enabled) {
    this.registry = registry;
    this.client = client;
    this.quoteClient = quoteClient;
    this.audit = audit;
    this.clock = clock;
    this.enabled = enabled;
  }

  @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "America/New_York")
  public void notifyOvernightTrails() {
    if (!enabled || !runLock.tryLock()) {
      return;
    }
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.warn("overnight-trail notice pass failed", e);
    } finally {
      runLock.unlock();
    }
  }

  void runOnce() {
    LocalDate today = LocalDate.now(clock.withZone(MARKET_TZ));
    noticed.removeIf(key -> !key.endsWith("|" + today));
    List<TenantStrategy> pairs;
    try {
      pairs = registry.list();
    } catch (RuntimeException e) {
      log.warn("overnight-trail: registry.list() failed; skipping", e);
      return;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (TenantStrategy ts : pairs) {
      if (flattensAtEod(ts)) {
        continue;
      }
      String query =
          "WorkflowType='PositionWorkflow' AND TenantStrategy='"
              + WorkflowIds.escapeForVisibilityQuery(
                  WorkflowIds.tenantStrategy(ts.tenantId(), ts.strategyId()))
              + "' AND ExecutionStatus='Running'";
      try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
        for (WorkflowExecutionMetadata m : (Iterable<WorkflowExecutionMetadata>) stream::iterator) {
          String wfId = m.getExecution().getWorkflowId();
          if (seen.add(wfId)) {
            noticeOne(ts, wfId, today);
          }
        }
      } catch (RuntimeException e) {
        log.warn(
            "overnight-trail: Visibility listing failed tenant={} strategy={}: {}",
            ts.tenantId(),
            ts.strategyId(),
            e.getMessage());
      }
    }
  }

  /** {@code eod_force_flatten: true} arms the 15:55 flatten; null/false do not. */
  private boolean flattensAtEod(TenantStrategy ts) {
    try {
      StrategyConfig cfg = registry.get(ts.tenantId(), ts.strategyId());
      return cfg != null && Boolean.TRUE.equals(cfg.getEodForceFlatten());
    } catch (RuntimeException e) {
      // Unknown config: notify anyway — a redundant notice beats a missing one.
      return false;
    }
  }

  private void noticeOne(TenantStrategy ts, String wfId, LocalDate today) {
    String key = wfId + "|" + today;
    if (noticed.contains(key)) {
      return;
    }
    TrailingState trail;
    PositionState state;
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      trail = stub.query("trailingState", TrailingState.class);
      if (trail == null || !trail.armed()) {
        return;
      }
      state = stub.query("positionState", PositionState.class);
    } catch (RuntimeException e) {
      log.debug("overnight-trail: query failed wf={}: {}", wfId, e.getMessage());
      return;
    }
    if (state == null || state.contractSymbol() == null || state.remainingQty() <= 0) {
      return;
    }
    LocalDate expiry = OccSymbol.expiryOf(state.contractSymbol());
    if (expiry != null && !expiry.isAfter(today)) {
      return;
    }
    BigDecimal bid = bidOf(state.contractSymbol());
    if (emit(ts, wfId, state, trail, bid, expiry)) {
      noticed.add(key);
    }
  }

  private BigDecimal bidOf(String occ) {
    try {
      OptionQuote q = quoteClient.optionQuote(occ);
      return q == null ? null : q.bid();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private boolean emit(
      TenantStrategy ts,
      String wfId,
      PositionState state,
      TrailingState trail,
      BigDecimal bid,
      LocalDate expiry) {
    try {
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("contract_symbol", state.contractSymbol());
      subject.put("remaining_qty", state.remainingQty());
      subject.put("bid", bid);
      subject.put("threshold", trail.thresholdPremium());
      subject.put("giveback_pct", trail.givebackPct());
      subject.put("peak_premium", trail.peakPremium());
      subject.put("distance_pct", distancePct(bid, trail.thresholdPremium()));
      subject.put("expiry", expiry == null ? null : expiry.toString());
      subject.put("note", NOTE);

      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId(ts.tenantId());
      event.setStrategyId(ts.strategyId());
      event.setEventId(UUID.randomUUID().toString());
      event.setOccurredAt(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
      event.setKind(KIND_OVERNIGHT_TRAIL_HELD);
      event.setActor(ACTOR);
      event.setWorkflowId(wfId);
      event.setCorrelationId(wfId);
      event.setSubject(subject);
      audit.log(event);
      return true;
    } catch (RuntimeException e) {
      log.warn("overnight-trail: audit emit failed wf={}", wfId, e);
      return false;
    }
  }

  /** {@code (bid − threshold) / bid}, 4 dp; null when either side is unknown or the bid is ≤ 0. */
  static BigDecimal distancePct(BigDecimal bid, BigDecimal threshold) {
    if (bid == null || threshold == null || bid.signum() <= 0) {
      return null;
    }
    return bid.subtract(threshold).divide(bid, 4, RoundingMode.HALF_UP);
  }
}
