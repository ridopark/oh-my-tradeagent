package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase 7 prep (issue #87) impl. Validates dual-control approver fields and — on pass — emits a
 * single {@code LivePromotionApproved} audit event via the shipped {@link AuditActivities#log}
 * path. Hash-chain population ({@code prev_hash} / {@code row_hash}) is handled by {@code
 * AuditActivitiesImpl} (PR #117); this Activity does not bypass that.
 *
 * <p>Validation mirrors {@code KillSwitchWorkflowImpl.resetValidator}: rejects blank approver IDs
 * and same-approver IDs with {@code IllegalArgumentException("approvers_must_differ")}.
 * Tenant/strategy/broker_target blanks reject with their own field-named codes so the failure
 * surface is precise.
 *
 * <p>Phase F (operator-account-onboarding): {@link #activate} and {@link #deactivate} are the
 * single-operator one-click writes. {@link #activate} reuses the SAME {@code LivePromotionApproved}
 * kind + on-chain {@link AuditActivities#log} path as {@link #approve} (so the already-wired
 * order-time gate sees it) but with a SINGLE operator — the gate-validity checks live upstream in
 * {@code LiveActivationWorkflow}, not here, so {@code activate} does NOT run the dual-control
 * validator. {@link #approve}'s behavior is unchanged: it still hard-requires two distinct
 * approvers via {@link #validate}, and both methods build an identical-shaped event via the shared
 * {@link #approvalEvent} helper.
 */
@Component
public class LivePromotionActivitiesImpl implements LivePromotionActivities {

  private static final String KIND_LIVE_PROMOTION_APPROVED = "LivePromotionApproved";
  private static final String KIND_LIVE_PROMOTION_DEACTIVATED = "LivePromotionDeactivated";
  private static final String ACTOR = "api-gateway:/promotion/approve";
  private static final String ACTOR_ACTIVATE = "api-gateway:/activate-live";
  private static final String ACTOR_DEACTIVATE = "api-gateway:/deactivate-live";
  private static final String ACTIVATION_MODE_ONE_CLICK = "one_click";

  private final AuditActivities audit;

  public LivePromotionActivitiesImpl(AuditActivities audit) {
    this.audit = audit;
  }

  @Override
  public void approve(LivePromotionApprovalRequest request) {
    validate(request);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("approver_id_1", request.getApproverId1());
    subject.put("approver_id_2", request.getApproverId2());
    subject.put("tenant_id", request.getTenantId());
    subject.put("strategy_id", request.getStrategyId());
    subject.put("broker_target", request.getBrokerTarget());
    subject.put("requested_at", now);
    subject.put("approved_at", now);
    if (request.getNote() != null && !request.getNote().isBlank()) {
      subject.put("note", request.getNote());
    }

    audit.log(
        approvalEvent(
            request.getTenantId(),
            request.getStrategyId(),
            KIND_LIVE_PROMOTION_APPROVED,
            ACTOR,
            now,
            subject));
  }

  @Override
  public void activate(LiveActivationRequest request) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // Single-operator one-click subject. Carries the REAL authenticated operator (NOT the static
    // ACTOR constant), the probed expected_account_id, and activation_mode so the row is
    // distinguishable from a dual-control approve() at audit time. ZERO key material.
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("operator_id", request.getOperatorId());
    subject.put("tenant_id", request.getTenantId());
    subject.put("strategy_id", request.getStrategyId());
    // Store the enum's wire string (e.g. "alpaca-live") so the gate's JSONB match
    // (subject->>'broker_target' = config.getBrokerTarget().value()) is exact and unambiguous.
    subject.put(
        "broker_target",
        request.getBrokerTarget() == null ? null : request.getBrokerTarget().value());
    if (request.getExpectedAccountId() != null && !request.getExpectedAccountId().isBlank()) {
      subject.put("expected_account_id", request.getExpectedAccountId());
    }
    subject.put("activation_mode", ACTIVATION_MODE_ONE_CLICK);
    subject.put("approved_at", now);

    audit.log(
        approvalEvent(
            request.getTenantId(),
            request.getStrategyId(),
            KIND_LIVE_PROMOTION_APPROVED,
            ACTOR_ACTIVATE,
            now,
            subject));
  }

  @Override
  public void deactivate(LiveDeactivationRequest request) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("operator_id", request.getOperatorId());
    subject.put("tenant_id", request.getTenantId());
    subject.put("strategy_id", request.getStrategyId());
    subject.put(
        "broker_target",
        request.getBrokerTarget() == null ? null : request.getBrokerTarget().value());
    subject.put("deactivated_at", now);

    audit.log(
        approvalEvent(
            request.getTenantId(),
            request.getStrategyId(),
            KIND_LIVE_PROMOTION_DEACTIVATED,
            ACTOR_DEACTIVATE,
            now,
            subject));
  }

  /**
   * Shared event-assembly so {@link #approve}, {@link #activate}, and {@link #deactivate} build
   * identical-shaped on-chain rows (the hash-chain writer in {@code AuditActivitiesImpl} populates
   * {@code prev_hash}/{@code row_hash}). The caller supplies the kind, actor, timestamp, and the
   * fully-built subject so each write keeps its own subject convention.
   */
  private static AuditEvent approvalEvent(
      String tenantId,
      String strategyId,
      String kind,
      String actor,
      OffsetDateTime occurredAt,
      Map<String, Object> subject) {
    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(tenantId);
    event.setStrategyId(strategyId);
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(occurredAt);
    event.setKind(kind);
    event.setActor(actor);
    event.setCorrelationId(tenantId + "/" + strategyId);
    event.setSubject(subject);
    return event;
  }

  /**
   * Server-side validator mirroring {@code KillSwitchWorkflowImpl.resetValidator}: any blank
   * approver or same-approver pair throws {@code approvers_must_differ}; blank tenant/strategy/
   * broker_target throws its own field-named code so the 400 body identifies the offending field.
   */
  private static void validate(LivePromotionApprovalRequest request) {
    String a1 = request.getApproverId1();
    String a2 = request.getApproverId2();
    if (a1 == null || a1.isBlank() || a2 == null || a2.isBlank() || a1.equals(a2)) {
      throw new IllegalArgumentException("approvers_must_differ");
    }
    String tenant = request.getTenantId();
    if (tenant == null || tenant.isBlank()) {
      throw new IllegalArgumentException("tenant_id_required");
    }
    String strategy = request.getStrategyId();
    if (strategy == null || strategy.isBlank()) {
      throw new IllegalArgumentException("strategy_id_required");
    }
    String brokerTarget = request.getBrokerTarget();
    if (brokerTarget == null || brokerTarget.isBlank()) {
      throw new IllegalArgumentException("broker_target_required");
    }
  }
}
