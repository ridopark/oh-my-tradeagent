package com.ohmytradeagent.orchestrator.alert.floorbreach;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Evaluation;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachStateStore.Decision;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import com.ohmytradeagent.orchestrator.alert.tradecontext.TradeContextRecorder;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Issue #779: the -50%-of-entry floor-breach ALERT detector. A plain Spring {@code @Scheduled} bean
 * (the {@code TenantReconcileLoop} pattern) that lives entirely OUTSIDE Temporal workflow history
 * and outside the trading path. Per tick it enumerates {@code (tenant, strategy)} pairs, lists
 * running {@code PositionWorkflow}s via the proven Visibility equality query, reads each one's
 * read-only {@code positionState} query, fetches the live BID from market-data, evaluates {@link
 * FloorBreachEvaluator}, runs {@link FloorBreachStateStore} hysteresis, and — on a firing decision
 * — emits ONE {@code FloorBreachAlerted} audit event via {@link AuditActivities#log}. The Discord
 * page rides the existing audit-driven {@code OrderFailureAlerter} after-commit funnel. <b>No other
 * side effect.</b>
 *
 * <p><b>HARD INVARIANT (#779):</b> this package places, modifies, and cancels NOTHING. Its only
 * Temporal verbs are the Visibility listing and the read-only untyped {@code positionState} query.
 * Enforced by {@code FloorBreachNoTradingActionGuardTest}, a build-failing source scan over {@code
 * alert/floorbreach/}.
 *
 * <p>Best-effort everywhere: a tick never throws; a per-pair Visibility failure or a per-workflow
 * query race skips that scope only (this is a read with no cap to keep fail-closed — the {@code
 * PositionsReader} stance, not the {@code VisibilityPortfolioSnapshot} one). A market-data failure
 * evaluates to UNKNOWN, which is inert.
 */
@Component
@Profile("!test")
public class FloorBreachAlertLoop {

  private static final Logger log = LoggerFactory.getLogger(FloorBreachAlertLoop.class);

  /** Registered in {@code services/audit} {@code AuditEventKinds.ALL_KINDS} (neutral kind). */
  private static final String KIND_FLOOR_BREACH_ALERTED = "FloorBreachAlerted";

  static final String ACTOR = "floor-breach-alerter";

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final StrategyRegistry registry;
  private final WorkflowClient client;
  private final MarketDataOptionQuoteClient quoteClient;
  private final FloorBreachThresholdResolver thresholdResolver;
  private final FloorBreachStateStore stateStore;
  private final AuditActivities audit;
  private final TradeContextRecorder recorder;
  private final boolean enabled;

  /** Serializes ticks; {@code tryLock}-skip so a slow pass never stalls the scheduler pool. */
  private final ReentrantLock tickLock = new ReentrantLock();

  public FloorBreachAlertLoop(
      StrategyRegistry registry,
      WorkflowClient client,
      MarketDataOptionQuoteClient quoteClient,
      FloorBreachThresholdResolver thresholdResolver,
      FloorBreachStateStore stateStore,
      AuditActivities audit,
      TradeContextRecorder recorder,
      @Value("${alert.floor-breach.enabled:true}") boolean enabled) {
    this.registry = registry;
    this.client = client;
    this.quoteClient = quoteClient;
    this.thresholdResolver = thresholdResolver;
    this.stateStore = stateStore;
    this.audit = audit;
    this.recorder = recorder;
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${alert.floor-breach.poll-ms:60000}",
      initialDelayString = "${alert.floor-breach.poll-ms:60000}")
  public void tick() {
    if (!enabled) {
      return;
    }
    if (!tickLock.tryLock()) {
      log.debug("floor-breach: a pass is already in flight; skipping this one");
      return;
    }
    try {
      tickOnce();
    } catch (RuntimeException e) {
      // Belt-and-suspenders: a notification loop must never propagate into the scheduler.
      log.warn("floor-breach tick failed", e);
    } finally {
      tickLock.unlock();
    }
  }

  private void tickOnce() {
    List<TenantStrategy> pairs;
    try {
      pairs = registry.list();
    } catch (RuntimeException e) {
      log.warn("floor-breach: registry.list() failed; skipping this tick", e);
      return;
    }

    Set<String> seenWorkflowIds = new LinkedHashSet<>();
    boolean anyListingFailed = false;
    for (TenantStrategy ts : pairs) {
      String query =
          "WorkflowType='PositionWorkflow' AND TenantStrategy='"
              + WorkflowIds.escapeForVisibilityQuery(
                  WorkflowIds.tenantStrategy(ts.tenantId(), ts.strategyId()))
              + "' AND ExecutionStatus='Running'";
      try (Stream<WorkflowExecutionMetadata> stream = client.listExecutions(query)) {
        var it = stream.iterator();
        while (it.hasNext()) {
          String wfId = it.next().getExecution().getWorkflowId();
          if (!seenWorkflowIds.add(wfId)) {
            continue;
          }
          try {
            evaluateOne(ts, wfId);
          } catch (RuntimeException e) {
            // Fail-soft per workflow: one bad evaluation must not starve the rest of the book.
            log.warn("floor-breach: evaluation failed wf={}: {}", wfId, e.getMessage());
          }
        }
      } catch (RuntimeException e) {
        anyListingFailed = true;
        log.warn(
            "floor-breach: Visibility listing failed tenant={} strategy={}: {}",
            ts.tenantId(),
            ts.strategyId(),
            e.getMessage());
      }
    }

    // Evict state for closed workflows — but only off a COMPLETE listing. A failed per-pair query
    // must not evict a live position's hysteresis state (which would cost a duplicate page later).
    if (!anyListingFailed) {
      stateStore.retainOnly(seenWorkflowIds);
      // #783 exit append: a complete listing is also the authority on which recorded positions
      // are gone. Belt-and-suspenders wrapped — the recorder is a passenger, never the driver.
      try {
        recorder.closeVanished(seenWorkflowIds);
      } catch (RuntimeException e) {
        log.warn("floor-breach: trade-context close pass failed: {}", e.getMessage());
      }
    }
  }

  /** One position: read state, fetch bid, evaluate, run hysteresis, maybe emit the audit event. */
  private void evaluateOne(TenantStrategy ts, String wfId) {
    PositionState state;
    try {
      state = client.newUntypedWorkflowStub(wfId).query("positionState", PositionState.class);
    } catch (RuntimeException e) {
      // Best-effort: a query race (just-closed workflow, rolling deploy) skips only this workflow.
      log.debug("floor-breach: positionState query failed wf={}: {}", wfId, e.getMessage());
      return;
    }
    if (state == null
        || state.contractSymbol() == null
        || state.contractSymbol().isBlank()
        || state.remainingQty() <= 0
        || state.entryPremium() == null) {
      return;
    }
    LocalDate expiry = OccSymbol.expiryOf(state.contractSymbol());
    if (expiry != null && expiry.isBefore(LocalDate.now(MARKET_TZ))) {
      // Physically expired: the broker has dropped the contract; nothing to page on.
      return;
    }

    OptionQuote quote = quoteClient.optionQuote(state.contractSymbol());
    // #783 trade-context recording rides this poll (entry snapshot + MFE/MAE ratchet). Wrapped so
    // a recorder failure can NEVER break the alert evaluation below — alerting stays primary.
    try {
      recorder.observe(ts, wfId, state, quote);
    } catch (RuntimeException e) {
      log.warn("floor-breach: trade-context observe failed wf={}: {}", wfId, e.getMessage());
    }
    BigDecimal threshold = thresholdResolver.threshold(ts.tenantId(), ts.strategyId());
    Evaluation eval = FloorBreachEvaluator.evaluate(state.entryPremium(), quote, threshold);
    Decision decision = stateStore.onTick(wfId, eval, Instant.now());
    if (decision.alert()) {
      emitAlert(ts, wfId, state, quote, eval, threshold, expiry);
    }
  }

  /** Emits the {@code FloorBreachAlerted} audit row. The ONLY side effect of this loop. */
  private void emitAlert(
      TenantStrategy ts,
      String wfId,
      PositionState state,
      OptionQuote quote,
      Evaluation eval,
      BigDecimal threshold,
      LocalDate expiry) {
    try {
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("contract_symbol", state.contractSymbol());
      subject.put("qty", state.remainingQty());
      subject.put("entry_premium", state.entryPremium());
      subject.put("current_bid", quote == null ? null : quote.bid());
      subject.put("loss_pct", eval.lossPct());
      subject.put("step", eval.step());
      subject.put("threshold", threshold);
      if (state.entryAt() != null) {
        subject.put("entry_at", state.entryAt().toString());
      }
      if (expiry != null) {
        subject.put("dte", ChronoUnit.DAYS.between(LocalDate.now(MARKET_TZ), expiry));
      }

      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId(ts.tenantId());
      event.setStrategyId(ts.strategyId());
      event.setEventId(UUID.randomUUID().toString());
      event.setOccurredAt(now);
      event.setKind(KIND_FLOOR_BREACH_ALERTED);
      event.setActor(ACTOR);
      event.setWorkflowId(wfId);
      event.setCorrelationId(wfId);
      event.setSubject(subject);
      audit.log(event);
    } catch (RuntimeException e) {
      // The audit write is best-effort here: this loop must never throw. Losing one row costs one
      // page; the next qualifying tick (deeper step / interval) will page again.
      log.warn("floor-breach: audit emit failed wf={}", wfId, e);
    }
  }
}
