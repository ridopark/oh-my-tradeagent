package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #239/#285: short-lived orphan-position adoption workflow. Carries the adoption semantics
 * verbatim from the original {@code PositionAdoptionActivitiesImpl} but as a workflow so the
 * broker-truth {@link ReconciliationExecActivity} calls route through the exec task queue ({@code
 * broker-<broker_target>}) — exactly the {@code ReconciliationWorkflowImpl} pattern — instead of an
 * in-process bean (which was a throwing placeholder). The {@code PositionWorkflow} owner is started
 * as an {@code ABANDON} child + {@code onFill} forward, mirroring {@code
 * CopytradeSignalWorkflowImpl.startPositionWorkflow}.
 *
 * <p>Flow (every value sourced from broker truth + the journal + strategy config, never a live
 * signal payload):
 *
 * <ol>
 *   <li>Read broker truth + phantom guard (refuse when the broker does not hold the lot).
 *   <li>Resolve the anchoring journal row → {@code entry_signal_id} / {@code intent_key} / {@code
 *       broker_order_id}; idempotency guard (no-op when a live owner already exists).
 *   <li>Reconstruct {@link PositionWorkflowInput} (qty/premium from broker truth; {@code
 *       eod_force_flatten} + TTLs from config, never defaulted).
 *   <li>Start the {@code PositionWorkflow} child with the canonical id + {@code TenantStrategy}/
 *       {@code ContractSymbol} search attributes.
 *   <li>Forward {@code onFill} so the first-fill gate wakes and {@code PositionEntered} fires.
 *   <li>Terminalize the journal row, seed discovery, emit {@code PositionAdopted} provenance.
 * </ol>
 */
public class AdoptionWorkflowImpl implements AdoptionWorkflow {

  /**
   * Forward-compat version anchor for this workflow type. New executions enter at v=1; any future
   * deterministic branch is gated by bumping the max version here so in-flight adoptions on replay
   * keep their recorded history. (Adoption is short-lived, but the anchor keeps the standard
   * versioning discipline.)
   */
  static final String VERSION_ADOPTION = "adoption-v1";

  private static final String KIND_POSITION_ADOPTED = "PositionAdopted";
  // Matches CopytradeSignalWorkflowImpl.DEFAULT_PENDING_TTL_PAPER_SECS / selectPendingTtlSecs.
  static final long DEFAULT_PENDING_TTL_PAPER_SECS = 90L;

  private static final ActivityOptions CORE_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, CORE_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, CORE_OPTIONS);
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(PositionLookupActivities.class, CORE_OPTIONS);

  @Override
  public AdoptionResult adopt(AdoptionWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 1L) {
      throw new IllegalArgumentException(
          "AdoptionWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    // Forward-compat anchor (see VERSION_ADOPTION). v is always 1 for new executions today.
    Workflow.getVersion(VERSION_ADOPTION, Workflow.DEFAULT_VERSION, 1);

    String tenantId = in.getTenantId();
    String strategyId = in.getStrategyId();
    String occ = in.getOcc();
    String operatorId = in.getOperatorId();

    // broker_target → exec task queue. Resolve from strategy config (the source of truth recon
    // uses), then build the exec-queue activity stub the same way ReconciliationWorkflowImpl does.
    StrategyConfig config = strategy.get(tenantId, strategyId);
    String brokerTarget =
        (config != null && config.getBrokerTarget() != null)
            ? config.getBrokerTarget().value()
            : null;
    ReconciliationExecActivity exec =
        Workflow.newActivityStub(
            ReconciliationExecActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build());

    // 1. Broker truth + phantom guard. Refuse before any side effect when the broker does not
    // actually hold the lot — adoption must never spawn an owner for a position that isn't there.
    BrokerPosition brokerLot = exec.brokerGetPositionByOcc(tenantId, strategyId, occ);
    if (brokerLot == null) {
      return refused(AdoptionResult.Outcome.REFUSED_NOT_HELD);
    }

    // 2. Resolve the anchoring journal row → entry_signal_id / intent_key / broker_order_id.
    // Without
    // an entry_signal_id we cannot build the canonical workflow id, so refuse.
    JournalEntry anchor = resolveAnchor(exec, tenantId, strategyId, occ);
    if (anchor == null || anchor.getSignalId() == null || anchor.getSignalId().isBlank()) {
      return refused(AdoptionResult.Outcome.REFUSED_NO_ANCHOR);
    }
    String entrySignalId = anchor.getSignalId();
    // Issue #246/#243: canonicalize the OCC to the journal anchor's option_symbol — the padded
    // 21-char OccSymbol.of form the live owner was spawned + registered under. The operator may
    // supply the broker/audit compact OCC, so every identity/discovery key must use this canonical
    // form. Mirrors ReconciliationWorkflowImpl.
    String canonicalOcc = anchor.getOptionSymbol();
    String posWfId = WorkflowIds.position(tenantId, strategyId, canonicalOcc, entrySignalId);

    // Idempotency guard: never double-own. If a live PositionWorkflow already owns the OCC, no-op.
    if (positionLookup.isPositionWorkflowRunning(posWfId)) {
      return refused(AdoptionResult.Outcome.ALREADY_OWNED);
    }

    // 3. Reconstruct PositionWorkflowInput from broker truth + journal + config.
    long qty = brokerLot.getQty();
    BigDecimal entryPremium = brokerLot.getAvgEntryPrice();
    // Prefer the anchor's submitted_at as the fill timestamp; fall back to the adoption instant
    // (the adoption time, NOT the true entry time) when the journal carries no submitted_at.
    OffsetDateTime filledAt =
        anchor.getSubmittedAt() != null ? anchor.getSubmittedAt() : workflowNow();

    PositionWorkflowInput posInput =
        buildInput(tenantId, strategyId, canonicalOcc, entrySignalId, qty, entryPremium, config);

    // 4. Start the PositionWorkflow as an ABANDON child with the canonical id + search attributes
    // (the child must outlive this short-lived adoption workflow). Mirrors
    // CopytradeSignalWorkflowImpl.startPositionWorkflow.
    Map<String, Object> sa = new LinkedHashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(tenantId, strategyId));
    sa.put("ContractSymbol", canonicalOcc);
    ChildWorkflowOptions opts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId(posWfId)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .setSearchAttributes(sa)
            .build();
    PositionWorkflow child = Workflow.newChildWorkflowStub(PositionWorkflow.class, opts);
    Async.function(child::run, posInput);
    // Wait until the child is durably scheduled before forwarding the fill.
    Workflow.getWorkflowExecution(child).get();

    // 5. Forward onFill within the TTL so the first-fill gate wakes and PositionEntered fires with
    // the real qty (instead of PositionNeverFilled).
    FillSignalPayload fill = new FillSignalPayload();
    fill.setBrokerOrderId(anchor.getBrokerOrderId());
    fill.setFilledQty(qty);
    fill.setAvgFillPrice(entryPremium);
    fill.setFilledAt(filledAt);
    child.onFill(fill);

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
    subject.put("operator_id", operatorId);
    subject.put("eod_force_flatten", posInput.getEodForceFlatten());
    subject.put(
        "evidence",
        "operator-triggered adoption -> broker truth confirms lot held -> reconstructed"
            + " PositionWorkflowInput started + onFill forwarded");
    audit.log(auditEvent(tenantId, strategyId, entrySignalId, posWfId, operatorId, subject));

    AdoptionResult result = new AdoptionResult();
    result.setSchemaVersion(1L);
    result.setOutcome(AdoptionResult.Outcome.ADOPTED);
    result.setWorkflowId(posWfId);
    result.setEntrySignalId(entrySignalId);
    result.setQty(qty);
    return result;
  }

  private static AdoptionResult refused(AdoptionResult.Outcome outcome) {
    AdoptionResult r = new AdoptionResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    return r;
  }

  /**
   * Resolve the anchoring journal row. Prefer the FILLED row (recon's source). Fall back to an open
   * (RECORDED/SUBMITTED) row matching the OCC — the {@code journal_status=missing} case — so a
   * still-open row can recover the signal_id/intent_key. Padding-agnostic OCC match (#246).
   */
  private JournalEntry resolveAnchor(
      ReconciliationExecActivity exec, String tenantId, String strategyId, String occ) {
    List<JournalEntry> filled = exec.journalListFilledByOcc(tenantId, strategyId, occ);
    if (!filled.isEmpty()) {
      return filled.get(0);
    }
    String compactOcc = OccSymbol.compact(occ);
    for (JournalEntry open : exec.journalDumpOpen(tenantId, strategyId)) {
      String openOcc = open.getOptionSymbol();
      if (compactOcc != null && openOcc != null && compactOcc.equals(OccSymbol.compact(openOcc))) {
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
    // Plan-2A R-AA-5: fix the pre-existing force_close_0dte_et omission — an adopted 0DTE lot must
    // get the same per-strategy expiry-close flatten time as a copytrade-spawned one, sourced from
    // StrategyConfig exactly as CopytradeSignalWorkflowImpl does. Null/absent passes through; the
    // child defaults to 15:30 ET.
    posInput.setForceClose0dteEt(config != null ? config.getForceClose0dteEt() : null);
    if (config != null && config.getMinPartialQtyBehavior() != null) {
      posInput.setMinPartialQtyBehavior(
          PositionWorkflowInput.MinPartialQtyBehavior.fromValue(
              config.getMinPartialQtyBehavior().value()));
    }
    long ttlSecs = selectPendingTtlSecs(config);
    posInput.setFirstFillTtlSecs(ttlSecs);
    posInput.setExitFillTtlSecs(ttlSecs);
    // Plan-2A R-AA-5: carry the bounded-flatten exit floors onto the adopted child so an adopted
    // (often expiry-day) lot arms the same bounded flatten machinery. All three pass through
    // verbatim (null preserved → marketable fail-safe fallback in the child). Not consumed yet.
    if (config != null) {
      posInput.setExitFloorAbs(config.getExitFloorAbs());
      posInput.setExitFloorPct(config.getExitFloorPct());
      posInput.setExpiryDayFloor(config.getExpiryDayFloor());
    }
    return posInput;
  }

  /**
   * Mirror of {@code CopytradeSignalWorkflowImpl.selectPendingTtlSecs}: a "live" broker_target uses
   * {@code pending_ttl_live_secs}; otherwise {@code pending_ttl_paper_secs}; falls back to 90s.
   */
  static long selectPendingTtlSecs(StrategyConfig config) {
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
      String operatorId,
      Map<String, Object> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(tenantId);
    event.setStrategyId(strategyId);
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(KIND_POSITION_ADOPTED);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("operator:" + operatorId);
    event.setWorkflowId(posWfId);
    event.setCorrelationId(entrySignalId);
    return event;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
