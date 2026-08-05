package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.CopytradeDeriskPayload;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PLAN-2026-08-04-copytrade-derisk-followup-cue (Phase 2). See {@link CopytradeDeriskWorkflow}.
 *
 * <p>Behaviour, modelled byte-for-byte on {@code CopytradeSignalWorkflowImpl.handleStc}'s trim+arm
 * block (the audit-before-dispatch ordering and the {@code SignalExternalWorkflowException |
 * ApplicationFailure} catch): load the tenant's {@link StrategyConfig}; if the per-tenant {@code
 * derisk_on_followup_cue} flag is unset, no-op with a {@code DeriskSkippedDisabled} audit.
 * Otherwise resolve the OCC the same way the BTO/STC path does, derive the attributed target {@code
 * PositionWorkflow} id from the cue's {@code target_bto_signal_id} (the identical {@link
 * WorkflowIds#position} derivation the BTO used to create it), trim it to {@code
 * derisk_keep_fraction} via the pre-existing {@code partialExit} signal, and arm the chandelier
 * trail on the remainder via the already-version-gated {@code armChandelier} signal.
 *
 * <p>Replay-safety: brand-new workflow type, no prior histories → NO {@code Workflow.getVersion}
 * gate; reuses only the two already-safe {@code PositionWorkflow} signals, adding no command shape
 * to any running workflow.
 */
public class CopytradeDeriskWorkflowImpl implements CopytradeDeriskWorkflow {

  // Parent-side dispatch audit: the de-risk cue trimmed the attributed position (mirrors the STC
  // path's ExitRequested). Neutral observability — registered in AuditEventKinds.ALL_KINDS only.
  private static final String KIND_DERISK_TRIM_REQUESTED = "DeriskTrimRequested";
  // Parent-side dispatch audit: the chandelier trail was armed on the retained lot (mirrors the STC
  // path's ChandelierArmRequested).
  private static final String KIND_DERISK_ARM_REQUESTED = "DeriskArmRequested";
  // The trim applied but the arm was NOT issued this phase — reason=arm_skipped_no_peak when the
  // seed premium is absent (we do not fetch a quote in this phase), reason=invalid_giveback when
  // trail_giveback_pct is unset (armChandelier would reject it).
  private static final String KIND_DERISK_ARM_SKIPPED = "DeriskArmSkipped";
  // Benign catch-path event: the attributed target position was already closed/absent (Friday's QQQ
  // / INTC-195c case). Like StcNoOpenPosition it must NOT page RED.
  private static final String KIND_DERISK_NO_OPEN_POSITION = "DeriskNoOpenPosition";
  // Dark-ship no-op: the per-tenant derisk_on_followup_cue flag is unset/false.
  private static final String KIND_DERISK_SKIPPED_DISABLED = "DeriskSkippedDisabled";

  // Default keep-fraction when the feature is enabled but derisk_keep_fraction is null (keep 25%,
  // trim 75%) — the operator-agreed canary value in the plan.
  private static final double DEFAULT_KEEP_FRACTION = 0.25;

  private static final String REASON_SIGNAL_DISPATCH_FAILED = "signal_dispatch_failed";
  private static final String REASON_ARM_SKIPPED_NO_PEAK = "arm_skipped_no_peak";
  private static final String REASON_INVALID_GIVEBACK = "invalid_giveback";
  private static final String REASON_DERISK_CUE = "derisk_cue";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, DEFAULT_OPTIONS);
  private final ContractActivities contract =
      Workflow.newActivityStub(ContractActivities.class, DEFAULT_OPTIONS);

  @Override
  public String process(CopytradeDeriskPayload payload) {
    String tenant = payload.getTenantId();
    String strategyId = payload.getStrategyId();
    StrategyConfig config = strategy.get(tenant, strategyId);

    // Dark-ship gate: unset/false → no-op with a single audit, issue NO signals (byte-identical to
    // today's behaviour on every tenant that has not opted in).
    if (!Boolean.TRUE.equals(config.getDeriskOnFollowupCue())) {
      logAudit(
          payload,
          KIND_DERISK_SKIPPED_DISABLED,
          subject(
              "signal_id", payload.getSignalId(),
              "target_bto_signal_id", payload.getTargetBtoSignalId(),
              "author", payload.getAuthor()));
      return payload.getSignalId();
    }

    // Resolve the OCC from the attributed target tuple, identical to the BTO/STC path.
    ContractResolveResult resolved = contract.resolve(ContractResolveInput.from(payload));
    String occ = resolved.optionSymbol();

    // Derive the attributed target PositionWorkflow id from the cue's target_bto_signal_id — the
    // SAME WorkflowIds.position derivation the BTO used to CREATE that position workflow. Precise
    // (targets the exact attributed BTO) and deterministic (no lookup Activity needed, since the
    // cue
    // already carries the entry signal_id — unlike STC, which must Redis-resolve it from the OCC).
    String positionId =
        WorkflowIds.position(tenant, strategyId, occ, payload.getTargetBtoSignalId());

    double keepFraction =
        config.getDeriskKeepFraction() != null
            ? config.getDeriskKeepFraction().doubleValue()
            : DEFAULT_KEEP_FRACTION;
    double exitFraction = 1.0 - keepFraction;
    BigDecimal givebackPct = config.getTrailGivebackPct();

    PartialExitRequest req = new PartialExitRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(tenant);
    req.setStrategyId(strategyId);
    // Use the CUE's signal_id so the trim never collides with an STC's dedup key inside the
    // position.
    req.setSignalId(payload.getSignalId());
    req.setPositionWorkflowId(positionId);
    // fraction < 1 keeps the partial reduce-only (never a full close); the existing handler floors
    // the CLOSED qty against live remainingQty and inherits all partial-exit safety.
    req.setFraction(BigDecimal.valueOf(exitFraction));
    // ref_premium SEEDS the exit LIMIT price (PositionWorkflowImpl.exitIntent): the initial trim
    // SELL is priced here, and if it does not fill the bounded reprice ladder walks it toward the
    // live bid — so a lot that spiked can still sell high before we accept the market. The BTO's
    // stated entry premium is the closest reference available without fetching a quote (deferred to
    // a later phase); may be null → a marketable exit. Fill behaviour is tunable on the paper
    // canary.
    req.setRefPremium(payload.getTargetEntryPremium());
    req.setReason(REASON_DERISK_CUE);
    req.setAuthor(payload.getAuthor());
    req.setRawLine(payload.getRawLine());
    req.setOccurredAt(workflowNow());

    // Audit BEFORE dispatch so the intent is durably recorded even if the target has already closed
    // (race), mirroring handleStc's ExitRequested-before-signal ordering.
    logAudit(
        payload,
        KIND_DERISK_TRIM_REQUESTED,
        subject(
            "signal_id", payload.getSignalId(),
            "target_bto_signal_id", payload.getTargetBtoSignalId(),
            "position_workflow_id", positionId,
            "option_symbol", occ,
            "keep_fraction", keepFraction,
            "fraction", exitFraction,
            "giveback_pct", givebackPct,
            "matched_cue", payload.getMatchedCue(),
            "author", payload.getAuthor()));

    ExternalWorkflowStub stub = Workflow.newUntypedExternalWorkflowStub(positionId);
    // The catch is narrow by construction (the try wraps ONLY the single stub.signal command): a
    // NOT_FOUND/terminal target surfaces as ApplicationFailure (converted from the server Failure
    // proto) — or SignalExternalWorkflowException on the paths where the SDK constructs it. We
    // catch
    // both so a closed/absent target (Friday's QQQ / INTC-195c) audits benignly and returns instead
    // of failing the workflow. Bare RuntimeException is deliberately NOT caught (genuine bugs fail
    // loudly).
    try {
      stub.signal("partialExit", req);
    } catch (SignalExternalWorkflowException | ApplicationFailure e) {
      logAudit(
          payload,
          KIND_DERISK_NO_OPEN_POSITION,
          subject(
              "signal_id",
              payload.getSignalId(),
              "target_bto_signal_id",
              payload.getTargetBtoSignalId(),
              "position_workflow_id",
              positionId,
              "option_symbol",
              occ,
              "reason",
              REASON_SIGNAL_DISPATCH_FAILED,
              "error",
              String.valueOf(e.getMessage())));
      return payload.getSignalId();
    }

    // Arm the chandelier trail on the retained lot. This phase does NOT fetch a quote: if the seed
    // premium is absent we cannot seed the peak, and if trail_giveback_pct is unset armChandelier
    // would reject with invalid_giveback — in both cases the trim already applied, so we audit the
    // arm-skip and return (trim without trail).
    BigDecimal peakPremium = payload.getTargetEntryPremium();
    if (peakPremium == null) {
      logAudit(
          payload,
          KIND_DERISK_ARM_SKIPPED,
          subject(
              "signal_id",
              payload.getSignalId(),
              "target_bto_signal_id",
              payload.getTargetBtoSignalId(),
              "position_workflow_id",
              positionId,
              "reason",
              REASON_ARM_SKIPPED_NO_PEAK));
      return payload.getSignalId();
    }
    if (givebackPct == null || givebackPct.signum() <= 0) {
      logAudit(
          payload,
          KIND_DERISK_ARM_SKIPPED,
          subject(
              "signal_id",
              payload.getSignalId(),
              "target_bto_signal_id",
              payload.getTargetBtoSignalId(),
              "position_workflow_id",
              positionId,
              "reason",
              REASON_INVALID_GIVEBACK));
      return payload.getSignalId();
    }

    ArmChandelierPayload arm = new ArmChandelierPayload();
    arm.setSchemaVersion(1L);
    arm.setTenantId(tenant);
    arm.setStrategyId(strategyId);
    arm.setPositionWorkflowId(positionId);
    arm.setSourceSignalId(payload.getSignalId());
    arm.setPeakPremium(peakPremium);
    arm.setGivebackPct(givebackPct);
    try {
      stub.signal("armChandelier", arm);
    } catch (SignalExternalWorkflowException | ApplicationFailure e) {
      logAudit(
          payload,
          KIND_DERISK_NO_OPEN_POSITION,
          subject(
              "signal_id",
              payload.getSignalId(),
              "target_bto_signal_id",
              payload.getTargetBtoSignalId(),
              "position_workflow_id",
              positionId,
              "option_symbol",
              occ,
              "reason",
              REASON_SIGNAL_DISPATCH_FAILED,
              "error",
              String.valueOf(e.getMessage())));
      return payload.getSignalId();
    }
    logAudit(
        payload,
        KIND_DERISK_ARM_REQUESTED,
        subject(
            "signal_id", payload.getSignalId(),
            "target_bto_signal_id", payload.getTargetBtoSignalId(),
            "position_workflow_id", positionId,
            "peak_premium", peakPremium,
            "giveback_pct", givebackPct));

    return payload.getSignalId();
  }

  private void logAudit(CopytradeDeriskPayload payload, String kind, Map<String, Object> subject) {
    audit.log(auditEvent(payload, kind, subject));
  }

  private AuditEvent auditEvent(
      CopytradeDeriskPayload payload, String kind, Map<String, ?> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(payload.getTenantId());
    event.setStrategyId(payload.getStrategyId());
    event.setEventId(Workflow.randomUUID().toString());
    event.setOccurredAt(workflowNow());
    event.setKind(kind);
    event.setSubject(new LinkedHashMap<>(subject));
    event.setActor("workflow:CopytradeDeriskWorkflow");
    event.setWorkflowId(Workflow.getInfo().getWorkflowId());
    event.setCorrelationId(payload.getSignalId());
    return event;
  }

  /** Builds an insertion-ordered subject map from alternating key/value varargs. */
  private static Map<String, Object> subject(Object... kv) {
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException("subject() requires an even number of key/value args");
    }
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
