package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase F (operator-account-onboarding): unit tests for the single-operator one-click
 * activate/deactivate audit-emission Activity. Confirms {@code activate} emits exactly one {@code
 * LivePromotionApproved} event (with operator + account, no key material) and {@code deactivate}
 * emits exactly one {@code LivePromotionDeactivated} event.
 */
class LivePromotionActivitiesImplTest {

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

    // Gate-readable kind that the order-time checkLivePromotion gate matches on.
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
}
