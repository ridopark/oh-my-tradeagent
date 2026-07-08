package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase F (operator-account-onboarding) impl for the single-operator one-click writes. {@link
 * #activate} emits a single gate-readable {@code LivePromotionApproved} audit event via the shipped
 * {@link AuditActivities#log} path; hash-chain population ({@code prev_hash} / {@code row_hash}) is
 * handled by {@code AuditActivitiesImpl} (PR #117); this Activity does not bypass that.
 *
 * <p>{@link #activate} attributes the {@code LivePromotionApproved} row to a SINGLE operator — the
 * gate-validity checks live upstream in {@code LiveActivationWorkflow}, not here. {@link
 * #deactivate} emits a {@code LivePromotionDeactivated} row that voids a prior approval at the
 * order-time gate. Both build an identical-shaped event via the shared {@link #approvalEvent}
 * helper.
 */
@Component
public class LivePromotionActivitiesImpl implements LivePromotionActivities {

  private static final String KIND_LIVE_PROMOTION_APPROVED = "LivePromotionApproved";
  private static final String KIND_LIVE_PROMOTION_DEACTIVATED = "LivePromotionDeactivated";
  private static final String ACTOR_ACTIVATE = "api-gateway:/activate-live";
  private static final String ACTOR_DEACTIVATE = "api-gateway:/deactivate-live";
  private static final String ACTIVATION_MODE_ONE_CLICK = "one_click";

  private final AuditActivities audit;

  public LivePromotionActivitiesImpl(AuditActivities audit) {
    this.audit = audit;
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
   * Shared event-assembly so {@link #activate} and {@link #deactivate} build identical-shaped
   * on-chain rows (the hash-chain writer in {@code AuditActivitiesImpl} populates {@code
   * prev_hash}/{@code row_hash}). The caller supplies the kind, actor, timestamp, and the
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
}
