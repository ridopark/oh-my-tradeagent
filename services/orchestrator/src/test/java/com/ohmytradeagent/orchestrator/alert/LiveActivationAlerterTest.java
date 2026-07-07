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
 * KillSwitchResetApproved(via=live_activation) → GREEN Discord confirmation, the symmetric,
 * false-success-free counterpart to {@link KillSwitchAlerterTest}'s red trip page. Firing off the
 * UNTRIP audit row (written LAST, only when the reset actually committed) means a resetKillSwitch
 * failure produces no green alert. The webhook client is mocked (no live secret) and the
 * blank-resolving resolver keeps embed content the unit under test (routing is covered by
 * AlerterWebhookRoutingTest).
 */
class LiveActivationAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  @Test
  void liveActivationResetDispatchesGreenEmbedWithOperatorAndCooldown() {
    WebhookClient webhook = mock(WebhookClient.class);
    LiveActivationAlerter alerter = new LiveActivationAlerter(webhook, RESOLVER);

    // The subject KillSwitchWorkflowImpl.resetOnActivation writes: via + operator + cooldown.
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("via", "live_activation");
    subject.put("operator", "operator:ridopark");
    subject.put("cooling_down_until", "2026-06-14T13:31:00Z");
    subject.put("cooldown_secs", 60L);
    AuditEvent event =
        event("KillSwitchResetApproved", "t-prod_real/s-copytrade-v1/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(5763719); // green
    assertThat(embed.title()).contains("Strategy activated live");
    assertThat(field(embed, "tenant_id")).isEqualTo("prod_real");
    assertThat(field(embed, "strategy_id")).isEqualTo("copytrade-v1");
    assertThat(field(embed, "operator")).isEqualTo("operator:ridopark");
    assertThat(field(embed, "cooling_down_until")).isEqualTo("2026-06-14T13:31:00Z");
    // workflow_id demoted to the footer.
    assertThat(embed.footer()).contains("t-prod_real/s-copytrade-v1/killswitch");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void manualDualControlReset_withoutVia_doesNotDispatch() {
    // A manual reset_killswitch writes KillSwitchResetApproved WITHOUT `via` — must NOT page green.
    WebhookClient webhook = mock(WebhookClient.class);
    LiveActivationAlerter alerter = new LiveActivationAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("approver_id_1", "alice");
    subject.put("approver_id_2", "bob");
    subject.put("cooling_down_until", "2026-06-14T13:31:00Z");
    subject.put("cooldown_secs", 60L);
    AuditEvent event = event("KillSwitchResetApproved", "wf-manual", subject);

    alerter.onAuditEvent(event);

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nonMatchingKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    LiveActivationAlerter alerter = new LiveActivationAlerter(webhook, RESOLVER);

    // LivePromotionApproved must no longer trigger (the alerter no longer keys on it).
    alerter.onAuditEvent(
        event("LivePromotionApproved", "wf-1", Map.of("activation_mode", "one_click")));
    alerter.onAuditEvent(event("KillSwitchTripped", "wf-2", Map.of("reason", "auto:daily_loss")));

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nullKindAndNullSubjectAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    LiveActivationAlerter alerter = new LiveActivationAlerter(webhook, RESOLVER);

    AuditEvent nullKind = event(null, "wf-3", Map.of());
    // KillSwitchResetApproved with a null subject cannot carry via → no dispatch, no throw.
    AuditEvent nullSubject = event("KillSwitchResetApproved", "wf-3", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    verify(webhook, never())
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
    LiveActivationAlerter alerter = new LiveActivationAlerter(webhook, RESOLVER);

    AuditEvent event = event("KillSwitchResetApproved", "wf-5", Map.of("via", "live_activation"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void unconfiguredWebhookIsNoOpNoThrow() {
    DiscordWebhookClient blankUrlClient = new DiscordWebhookClient("", "");
    LiveActivationAlerter alerter = new LiveActivationAlerter(blankUrlClient, RESOLVER);

    AuditEvent event = event("KillSwitchResetApproved", "wf-6", Map.of("via", "live_activation"));

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
    ev.setEventId("00000000-0000-4000-8000-00000000cccc");
    ev.setOccurredAt(OffsetDateTime.parse("2026-06-14T13:30:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:KillSwitchWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("prod_real/copytrade-v1");
    ev.setSubject(subject);
    return ev;
  }
}
