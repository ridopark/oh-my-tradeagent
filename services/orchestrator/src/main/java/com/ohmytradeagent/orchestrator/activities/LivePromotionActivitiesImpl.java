package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
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
 */
@Component
public class LivePromotionActivitiesImpl implements LivePromotionActivities {

  private static final String KIND_LIVE_PROMOTION_APPROVED = "LivePromotionApproved";
  private static final String ACTOR = "api-gateway:/promotion/approve";

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

    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(request.getTenantId());
    event.setStrategyId(request.getStrategyId());
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(now);
    event.setKind(KIND_LIVE_PROMOTION_APPROVED);
    event.setActor(ACTOR);
    event.setCorrelationId(request.getTenantId() + "/" + request.getStrategyId());
    event.setSubject(subject);

    audit.log(event);
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
