package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * LivePromotionApproved → GREEN Discord confirmation, the symmetric counterpart to {@link
 * KillSwitchAlerterTest}'s red trip page. Mirrors that test exactly: the webhook client is mocked
 * (no live secret needed) and the blank-resolving resolver keeps embed content the unit under test
 * (routing is covered by AlerterWebhookRoutingTest).
 */
class LivePromotionApprovedAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  @Test
  void livePromotionApprovedDispatchesGreenEmbedWithActivationFields() {
    WebhookClient webhook = mock(WebhookClient.class);
    LivePromotionApprovedAlerter alerter = new LivePromotionApprovedAlerter(webhook, RESOLVER);

    // The subject the LivePromotionActivitiesImpl.activate one-click path writes.
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("operator_id", "ridopark");
    subject.put("tenant_id", "prod_real");
    subject.put("strategy_id", "copytrade-v1");
    subject.put("broker_target", "alpaca-live");
    subject.put("expected_account_id", "847309116");
    subject.put("activation_mode", "one_click");
    subject.put("approved_at", "2026-06-14T13:30:00Z");
    AuditEvent event =
        event("LivePromotionApproved", "t-prod_real/s-copytrade-v1/live-activation", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(5763719); // green
    assertThat(embed.title()).contains("Strategy activated live");
    assertThat(field(embed, "tenant_id")).isEqualTo("prod_real");
    assertThat(field(embed, "strategy_id")).isEqualTo("copytrade-v1");
    assertThat(field(embed, "broker_target")).isEqualTo("alpaca-live");
    assertThat(field(embed, "operator_id")).isEqualTo("ridopark");
    assertThat(field(embed, "expected_account_id")).isEqualTo("847309116");
    assertThat(field(embed, "activation_mode")).isEqualTo("one_click");
    // workflow_id demoted to the footer.
    assertThat(embed.footer()).contains("t-prod_real/s-copytrade-v1/live-activation");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void nonMatchingKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    LivePromotionApprovedAlerter alerter = new LivePromotionApprovedAlerter(webhook, RESOLVER);

    alerter.onAuditEvent(event("KillSwitchTripped", "wf-1", Map.of("reason", "auto:daily_loss")));
    alerter.onAuditEvent(event("LivePromotionMissing", "wf-2", Map.of("reason", "absent")));
    alerter.onAuditEvent(event("LivePromotionDeactivated", "wf-3", Map.of("operator_id", "x")));

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nullKindAndNullSubjectAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    LivePromotionApprovedAlerter alerter = new LivePromotionApprovedAlerter(webhook, RESOLVER);

    AuditEvent nullKind = event(null, "wf-4", Map.of());
    AuditEvent nullSubject = event("LivePromotionApproved", "wf-4", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (LivePromotionApproved) still pages with n/a
    // fields.
    verify(webhook, times(1))
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    LivePromotionApprovedAlerter alerter = new LivePromotionApprovedAlerter(webhook, RESOLVER);

    AuditEvent event =
        event("LivePromotionApproved", "wf-5", Map.of("activation_mode", "one_click"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void unconfiguredWebhookIsNoOpNoThrow() {
    DiscordWebhookClient blankUrlClient = new DiscordWebhookClient("", "");
    LivePromotionApprovedAlerter alerter =
        new LivePromotionApprovedAlerter(blankUrlClient, RESOLVER);

    AuditEvent event =
        event("LivePromotionApproved", "wf-6", Map.of("activation_mode", "one_click"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1))
        .postEmbedToUrl(org.mockito.ArgumentMatchers.anyString(), captor.capture());
    return captor.getValue();
  }

  private static String field(WebhookEmbed embed, String name) {
    java.util.List<WebhookEmbed.Field> matches =
        embed.fields().stream().filter(f -> f.name().equals(name)).toList();
    assertThat(matches).as("field " + name).hasSize(1);
    return matches.get(0).value();
  }

  private static AuditEvent event(String kind, String workflowId, Map<String, Object> subject) {
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("prod_real");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000bbbb");
    ev.setOccurredAt(OffsetDateTime.parse("2026-06-14T13:30:00Z"));
    ev.setKind(kind);
    ev.setActor("api-gateway:/activate-live");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("prod_real/copytrade-v1");
    ev.setSubject(subject);
    return ev;
  }
}
