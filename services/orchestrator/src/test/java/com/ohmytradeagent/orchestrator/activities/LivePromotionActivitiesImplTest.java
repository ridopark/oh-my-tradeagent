package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #87: unit tests for the dual-control validator + audit-emission Activity. Confirms (1)
 * single/same-approver requests reject and emit zero audit events, and (2) distinct-ID requests
 * emit exactly one {@code LivePromotionApproved} event with the full subject convention populated.
 */
class LivePromotionActivitiesImplTest {

  @Test
  void singleApprover_rejects_approversMustDiffer() {
    AuditActivities audit = mock(AuditActivities.class);
    LivePromotionActivitiesImpl activities = new LivePromotionActivitiesImpl(audit);

    LivePromotionApprovalRequest req = baseRequest();
    req.setApproverId1("alice");
    req.setApproverId2(""); // blank approver_id_2

    assertThatThrownBy(() -> activities.approve(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("approvers_must_differ");

    // Critical: no audit event when validation fails.
    verify(audit, never()).log(any(AuditEvent.class));
  }

  @Test
  void sameId_rejects_approversMustDiffer() {
    AuditActivities audit = mock(AuditActivities.class);
    LivePromotionActivitiesImpl activities = new LivePromotionActivitiesImpl(audit);

    LivePromotionApprovalRequest req = baseRequest();
    req.setApproverId1("alice");
    req.setApproverId2("alice");

    assertThatThrownBy(() -> activities.approve(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("approvers_must_differ");

    verify(audit, never()).log(any(AuditEvent.class));
  }

  @Test
  void distinctIds_emitsExactlyOneEvent() {
    AuditActivities audit = mock(AuditActivities.class);
    LivePromotionActivitiesImpl activities = new LivePromotionActivitiesImpl(audit);

    LivePromotionApprovalRequest req = baseRequest();
    req.setApproverId1("alice");
    req.setApproverId2("bob");
    req.setNote("phase-7 gate signoff drill");

    activities.approve(req);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent emitted = captor.getValue();

    assertThat(emitted.getKind()).isEqualTo("LivePromotionApproved");
    assertThat(emitted.getTenantId()).isEqualTo("dev");
    assertThat(emitted.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(emitted.getActor()).isEqualTo("api-gateway:/promotion/approve");
    assertThat(emitted.getCorrelationId()).isEqualTo("dev/copytrade-v1");
    assertThat(emitted.getEventId()).isNotBlank();
    assertThat(emitted.getOccurredAt()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> subject = (Map<String, Object>) emitted.getSubject();
    assertThat(subject)
        .containsEntry("approver_id_1", "alice")
        .containsEntry("approver_id_2", "bob")
        .containsEntry("tenant_id", "dev")
        .containsEntry("strategy_id", "copytrade-v1")
        .containsEntry("broker_target", "tradier-live")
        .containsEntry("note", "phase-7 gate signoff drill")
        .containsKey("requested_at")
        .containsKey("approved_at");
    // Plan requires both IDs present AND distinct in the subject.
    assertThat(subject.get("approver_id_1")).isNotEqualTo(subject.get("approver_id_2"));
  }

  // --- Phase F (operator-account-onboarding): single-operator one-click activate/deactivate ----

  @Test
  void activate_emitsOneLivePromotionApproved_withOperatorAndAccount_andNoKeyMaterial() {
    AuditActivities audit = mock(AuditActivities.class);
    LivePromotionActivitiesImpl activities = new LivePromotionActivitiesImpl(audit);

    LiveActivationRequest req = new LiveActivationRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setBrokerTarget(LiveActivationRequest.BrokerTarget.ALPACA_LIVE);
    req.setOperatorId("ridopark");
    req.setExpectedAccountId("PA3FKGPFYPLH");

    activities.activate(req);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent emitted = captor.getValue();

    // SAME gate-readable kind as approve() so the order-time gate sees it.
    assertThat(emitted.getKind()).isEqualTo("LivePromotionApproved");
    assertThat(emitted.getTenantId()).isEqualTo("dev");
    assertThat(emitted.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(emitted.getActor()).isEqualTo("api-gateway:/activate-live");
    assertThat(emitted.getCorrelationId()).isEqualTo("dev/copytrade-v1");
    assertThat(emitted.getEventId()).isNotBlank();
    assertThat(emitted.getOccurredAt()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> subject = (Map<String, Object>) emitted.getSubject();
    assertThat(subject)
        .containsEntry("operator_id", "ridopark")
        .containsEntry("tenant_id", "dev")
        .containsEntry("strategy_id", "copytrade-v1")
        // Stored as the enum wire string so the gate's JSONB match is exact.
        .containsEntry("broker_target", "alpaca-live")
        .containsEntry("expected_account_id", "PA3FKGPFYPLH")
        .containsEntry("activation_mode", "one_click")
        .containsKey("approved_at");

    // No two-approver fields (single operator) and ZERO key material in the subject.
    assertThat(subject)
        .doesNotContainKeys(
            "approver_id_1", "approver_id_2", "api_key_id", "api_key", "secret", "secret_key");
  }

  @Test
  void deactivate_emitsOneLivePromotionDeactivated_withOperator_andNoKeyMaterial() {
    AuditActivities audit = mock(AuditActivities.class);
    LivePromotionActivitiesImpl activities = new LivePromotionActivitiesImpl(audit);

    LiveDeactivationRequest req = new LiveDeactivationRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setBrokerTarget(LiveDeactivationRequest.BrokerTarget.ALPACA_LIVE);
    req.setOperatorId("ridopark");

    activities.deactivate(req);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent emitted = captor.getValue();

    assertThat(emitted.getKind()).isEqualTo("LivePromotionDeactivated");
    assertThat(emitted.getTenantId()).isEqualTo("dev");
    assertThat(emitted.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(emitted.getActor()).isEqualTo("api-gateway:/deactivate-live");

    @SuppressWarnings("unchecked")
    Map<String, Object> subject = (Map<String, Object>) emitted.getSubject();
    assertThat(subject)
        .containsEntry("operator_id", "ridopark")
        .containsEntry("tenant_id", "dev")
        .containsEntry("strategy_id", "copytrade-v1")
        .containsEntry("broker_target", "alpaca-live")
        .containsKey("deactivated_at");
    assertThat(subject).doesNotContainKeys("api_key_id", "api_key", "secret", "secret_key");
  }

  private static LivePromotionApprovalRequest baseRequest() {
    LivePromotionApprovalRequest req = new LivePromotionApprovalRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setBrokerTarget("tradier-live");
    return req;
  }
}
