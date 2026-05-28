package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issue #239: operator-triggered orphan-position adoption. Plain Activity code (not workflow), so
 * it drives the {@link WorkflowClient} directly to start + signal the reconstructed {@code
 * PositionWorkflow} — the same pattern as the bootstrappers. The {@code orchestrator-core} worker
 * registers this impl ({@link com.ohmytradeagent.orchestrator.config.TemporalWorkerConfig}).
 *
 * <p>Flow mirrors {@code CopytradeSignalWorkflowImpl.startPositionWorkflow} but sources every value
 * from broker truth + the journal + strategy config rather than a live signal payload:
 *
 * <ol>
 *   <li>Read broker truth + phantom guard (refuse when the broker does not hold the lot).
 *   <li>Resolve the anchoring journal row → {@code entry_signal_id} / {@code intent_key} / {@code
 *       broker_order_id}; idempotency guard (no-op when a live owner already exists).
 *   <li>Reconstruct {@link PositionWorkflowInput} (qty/premium from broker truth; {@code
 *       eod_force_flatten} + TTLs from config, never defaulted).
 *   <li>Start the {@code PositionWorkflow} on {@code orchestrator-core} with the canonical id +
 *       {@code TenantStrategy}/{@code ContractSymbol} search attributes.
 *   <li>Signal {@code onFill} so the first-fill gate wakes and {@code PositionEntered} fires.
 *   <li>Terminalize the journal row, seed discovery, emit {@code PositionAdopted} provenance.
 * </ol>
 */
@Component
public class PositionAdoptionActivitiesImpl implements PositionAdoptionActivities {

  private static final Logger log = LoggerFactory.getLogger(PositionAdoptionActivitiesImpl.class);

  private static final String KIND_POSITION_ADOPTED = "PositionAdopted";
  static final String POSITION_TASK_QUEUE = "orchestrator-core";
  static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";
  // Matches CopytradeSignalWorkflowImpl.DEFAULT_PENDING_TTL_PAPER_SECS / selectPendingTtlSecs.
  static final long DEFAULT_PENDING_TTL_PAPER_SECS = 90L;

  private final WorkflowClient workflowClient;
  private final StrategyActivities strategy;
  private final PositionLookupActivities positionLookup;
  private final ReconciliationExecActivity exec;
  private final AuditActivities audit;

  public PositionAdoptionActivitiesImpl(
      WorkflowClient workflowClient,
      StrategyActivities strategy,
      PositionLookupActivities positionLookup,
      ReconciliationExecActivity exec,
      AuditActivities audit) {
    this.workflowClient = workflowClient;
    this.strategy = strategy;
    this.positionLookup = positionLookup;
    this.exec = exec;
    this.audit = audit;
  }

  @Override
  public AdoptionResult adoptOrphanPosition(String tenantId, String strategyId, String occ) {
    // 1. Broker truth + phantom guard. Refuse before any side effect when the broker does not
    // actually hold the lot — adoption must never spawn an owner for a position that isn't there.
    BrokerPosition brokerLot = exec.brokerGetPositionByOcc(tenantId, strategyId, occ);
    if (brokerLot == null) {
      log.info(
          "adoptOrphanPosition refused: broker does not hold lot tenant={} strategy={} occ={}",
          tenantId,
          strategyId,
          occ);
      return AdoptionResult.refusedNotHeld();
    }

    // 2. Resolve the anchoring journal row → entry_signal_id / intent_key / broker_order_id (the
    // same source recon uses at ReconciliationWorkflowImpl:251). Without an entry_signal_id we
    // cannot build the canonical workflow id, so refuse (documented known limitation).
    JournalEntry anchor = resolveAnchor(tenantId, strategyId, occ);
    if (anchor == null || anchor.getSignalId() == null || anchor.getSignalId().isBlank()) {
      log.info(
          "adoptOrphanPosition refused: no journal anchor for entry_signal_id tenant={} strategy={}"
              + " occ={}",
          tenantId,
          strategyId,
          occ);
      return AdoptionResult.refusedNoAnchor();
    }
    String entrySignalId = anchor.getSignalId();
    // Issue #246 (sibling of #243): canonicalize the OCC to the journal anchor's option_symbol —
    // the padded 21-char OccSymbol.of form (root padded to 6 chars with %-6s) that the live owner
    // was spawned + registered under (CopytradeSignalWorkflowImpl uses resolved.optionSymbol()
    // uniformly for the workflow id, ContractSymbol SA, PositionWorkflowInput, and discovery
    // cache). The operator may supply the broker/audit *compact* OCC (Alpaca strips the
    // space-padding), so every identity/discovery key below must use this canonical form — anchoring
    // on the raw `occ` would build a non-matching id (miss a live owner → adopt a duplicate) and
    // register the adopted owner under a cache key + ContractSymbol the STC lookup never queries.
    // Mirrors ReconciliationWorkflowImpl:267-272.
    String canonicalOcc = anchor.getOptionSymbol();
    String posWfId = WorkflowIds.position(tenantId, strategyId, canonicalOcc, entrySignalId);

    // Idempotency guard: never double-own. If a live PositionWorkflow already owns the OCC, no-op.
    if (positionLookup.isPositionWorkflowRunning(posWfId)) {
      log.info("adoptOrphanPosition no-op: live owner already running wf_id={}", posWfId);
      return AdoptionResult.alreadyOwned();
    }

    // 3. Reconstruct PositionWorkflowInput from broker truth + journal + config.
    StrategyConfig config = strategy.get(tenantId, strategyId);
    long qty = brokerLot.getQty();
    BigDecimal entryPremium = resolveEntryPremium(brokerLot);
    // Prefer the anchor's submitted_at as the fill timestamp. When the journal carries no
    // submitted_at we fall back to the adoption instant — which is the adoption time, NOT the true
    // entry time, so any duration metric derived from it will be understated. Logged as a WARNING
    // and documented on AdoptionResult.adopted(...).
    OffsetDateTime filledAt;
    if (anchor.getSubmittedAt() != null) {
      filledAt = anchor.getSubmittedAt();
    } else {
      filledAt = now();
      log.warn(
          "adoptOrphanPosition: journal anchor has no submitted_at; using adoption instant {} as"
              + " the fill timestamp (NOT the true entry time) tenant={} strategy={} occ={}",
          filledAt,
          tenantId,
          strategyId,
          occ);
    }

    PositionWorkflowInput posInput =
        buildInput(tenantId, strategyId, canonicalOcc, entrySignalId, qty, entryPremium, config);

    // 4. Start the PositionWorkflow on orchestrator-core with the canonical id + search attributes.
    Map<String, Object> sa = new LinkedHashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenantId, strategyId));
    sa.put("ContractSymbol", canonicalOcc);
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setWorkflowId(posWfId)
            .setTaskQueue(POSITION_TASK_QUEUE)
            .setSearchAttributes(sa)
            .build();
    WorkflowStub stub = workflowClient.newUntypedWorkflowStub(POSITION_WORKFLOW_TYPE, opts);
    try {
      stub.start(posInput);
    } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
      // Activity-retry safety: start() succeeded on a prior attempt but the run crashed
      // before the onFill signal landed. Do NOT bail out here — that would drop the fill and
      // leave the adopted PositionWorkflow to time out into PositionNeverFilled. The
      // running-probe above already let a genuinely-live owner short-circuit; reaching here
      // means we own a half-adopted workflow, so fall through to re-send onFill and finish
      // steps 6-8. Every downstream step is idempotent: markFilled is conditional on state in
      // {RECORDED, SUBMITTED}; cachePositionMapping is an idempotent put; the PositionWorkflow
      // first-fill latch dedupes a duplicate onFill; the PositionAdopted audit dup is a benign
      // provenance row.
      log.info(
          "adoptOrphanPosition retry: workflow already started (prior attempt); re-sending onFill"
              + " wf_id={}",
          posWfId);
    }

    // 5. Signal onFill within the TTL so the first-fill gate wakes and PositionEntered fires with
    // the real qty (instead of PositionNeverFilled).
    FillSignalPayload fill = new FillSignalPayload();
    fill.setBrokerOrderId(anchor.getBrokerOrderId());
    fill.setFilledQty(qty);
    fill.setAvgFillPrice(entryPremium);
    fill.setFilledAt(filledAt);
    stub.signal("onFill", fill);

    // 6. Terminalize the journal row, seed discovery, emit PositionAdopted provenance.
    exec.journalReconcileToFilled(anchor.getIntentKey(), qty, entryPremium, filledAt);
    positionLookup.cachePositionMapping(tenantId, strategyId, canonicalOcc, posWfId);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("option_symbol", canonicalOcc);
    subject.put("entry_signal_id", entrySignalId);
    subject.put("intent_key", anchor.getIntentKey());
    subject.put("broker_order_id", anchor.getBrokerOrderId());
    subject.put("qty", qty);
    subject.put("entry_premium", entryPremium);
    subject.put("workflow_id", posWfId);
    subject.put("eod_force_flatten", posInput.getEodForceFlatten());
    subject.put(
        "evidence",
        "orphan detected (broker-held, no running PositionWorkflow) -> broker truth confirms lot"
            + " held -> reconstructed PositionWorkflowInput started + onFill signalled");
    audit.log(auditEvent(tenantId, strategyId, entrySignalId, posWfId, subject));

    log.info(
        "adoptOrphanPosition adopted wf_id={} qty={} entry_premium={} broker_order_id={}",
        posWfId,
        qty,
        entryPremium,
        anchor.getBrokerOrderId());
    return AdoptionResult.adopted(posWfId, entrySignalId, qty);
  }

  /**
   * Resolve the anchoring journal row. Prefer the FILLED row (recon's source). Fall back to an open
   * (RECORDED/SUBMITTED) row matching the OCC — the {@code journal_status=missing} case (e.g. a
   * stuck SUBMITTED row as in #207) — so a still-open row can recover the signal_id/intent_key.
   */
  private JournalEntry resolveAnchor(String tenantId, String strategyId, String occ) {
    List<JournalEntry> filled = exec.journalListFilledByOcc(tenantId, strategyId, occ);
    if (!filled.isEmpty()) {
      return filled.get(0);
    }
    // Issue #246: padding-agnostic match — the operator may supply the broker/audit *compact* OCC
    // while the journal row carries the *padded* OccSymbol.of form (and vice versa). Compare the
    // space-stripped forms, mirroring the journal backstop in JooqOrderIntentJournal:126.
    String compactOcc = occ == null ? null : occ.replace(" ", "");
    for (JournalEntry open : exec.journalDumpOpen(tenantId, strategyId)) {
      String openOcc = open.getOptionSymbol();
      if (compactOcc != null && openOcc != null && compactOcc.equals(openOcc.replace(" ", ""))) {
        return open;
      }
    }
    return null;
  }

  private PositionWorkflowInput buildInput(
      String tenantId,
      String strategyId,
      String occ,
      String entrySignalId,
      long qty,
      BigDecimal entryPremium,
      StrategyConfig config) {
    PositionWorkflowInput posInput = new PositionWorkflowInput();
    posInput.setSchemaVersion(1L);
    posInput.setTenantId(tenantId);
    posInput.setStrategyId(strategyId);
    posInput.setEntrySignalId(entrySignalId);
    posInput.setContractSymbol(occ);
    posInput.setQty(qty);
    posInput.setEntryPremium(entryPremium);
    if (config != null && config.getBrokerTarget() != null) {
      posInput.setBrokerTarget(
          PositionWorkflowInput.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    }
    // eod_force_flatten passed through verbatim, never defaulted (copytrade false must propagate
    // so PositionWorkflowImpl does not re-arm the EOD timer).
    posInput.setEodForceFlatten(config != null ? config.getEodForceFlatten() : null);
    if (config != null && config.getMinPartialQtyBehavior() != null) {
      posInput.setMinPartialQtyBehavior(
          PositionWorkflowInput.MinPartialQtyBehavior.fromValue(
              config.getMinPartialQtyBehavior().value()));
    }
    long ttlSecs = selectPendingTtlSecs(config);
    posInput.setFirstFillTtlSecs(ttlSecs);
    posInput.setExitFillTtlSecs(ttlSecs);
    return posInput;
  }

  /**
   * Broker fill is the source of truth for the entry premium — the broker-held {@code
   * avg_entry_price}. The recon {@link JournalEntry} snapshot carries no price field, so there is
   * no journal fallback here; the phantom guard already guarantees a broker lot exists. Never the
   * author-posted price.
   */
  private static BigDecimal resolveEntryPremium(BrokerPosition brokerLot) {
    return brokerLot.getAvgEntryPrice();
  }

  /**
   * Mirror of {@code CopytradeSignalWorkflowImpl.selectPendingTtlSecs}: a "live" broker_target uses
   * {@code pending_ttl_live_secs}; otherwise {@code pending_ttl_paper_secs}; falls back to 90s.
   */
  long selectPendingTtlSecs(StrategyConfig config) {
    String target =
        (config != null && config.getBrokerTarget() != null)
            ? config.getBrokerTarget().value()
            : "";
    boolean isLive = target.contains("live");
    Long configured =
        config == null
            ? null
            : (isLive ? config.getPendingTtlLiveSecs() : config.getPendingTtlPaperSecs());
    return configured != null ? configured : DEFAULT_PENDING_TTL_PAPER_SECS;
  }

  private AuditEvent auditEvent(
      String tenantId,
      String strategyId,
      String entrySignalId,
      String posWfId,
      Map<String, Object> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(tenantId);
    event.setStrategyId(strategyId);
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(now());
    event.setKind(KIND_POSITION_ADOPTED);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("activity:PositionAdoptionActivities");
    event.setWorkflowId(posWfId);
    event.setCorrelationId(entrySignalId);
    return event;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
