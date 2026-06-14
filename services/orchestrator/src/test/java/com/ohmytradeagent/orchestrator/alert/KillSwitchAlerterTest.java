package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * B2 (P0c-b1): KillSwitchTripped → Discord pager. Mirrors {@link OrderFailureAlerterTest} — the
 * webhook client is mocked, so no live secret is required, and the unconfigured-webhook no-op path
 * is exercised via the real {@link DiscordWebhookClient} contract (a blank URL = no-op, no throw).
 */
class KillSwitchAlerterTest {

  @Test
  void killSwitchTrippedDispatchesRedEmbedWithTenantStrategyReasonActorValueTradingDay() {
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:daily_loss");
    subject.put("actor", "auto:daily_loss");
    subject.put("tripped_at", "2026-06-14T13:30:00Z");
    subject.put("trading_day", "2026-06-14");
    subject.put("value", "-3000");
    AuditEvent event = event("KillSwitchTripped", "t-dev/s-copytrade-v1/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(embed.title()).contains("Kill switch TRIPPED", "auto:daily_loss");
    assertThat(field(embed, "tenant_id")).isEqualTo("dev");
    assertThat(field(embed, "strategy_id")).isEqualTo("copytrade-v1");
    assertThat(field(embed, "reason")).isEqualTo("auto:daily_loss");
    assertThat(field(embed, "actor")).isEqualTo("auto:daily_loss");
    assertThat(field(embed, "value")).isEqualTo("-3000");
    assertThat(field(embed, "trading_day")).isEqualTo("2026-06-14");
    // workflow_id demoted to the footer.
    assertThat(embed.footer()).contains("t-dev/s-copytrade-v1/killswitch");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void killSwitchTrippedMissingLossThreshold_anomalyTrip_pagesWithDistinctReason() {
    // The B2 anomaly trip — a live strategy that reached the heartbeat with no valid loss gate.
    // No quantified `value` (null), so the value field is omitted; the reason distinguishes it.
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:missing_loss_threshold");
    subject.put("actor", "auto:missing_loss_threshold");
    subject.put("trading_day", "2026-06-14");
    AuditEvent event = event("KillSwitchTripped", "t-dev/s-copytrade-live/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("auto:missing_loss_threshold");
    assertThat(field(embed, "reason")).isEqualTo("auto:missing_loss_threshold");
    // No `value` field when the trip carries no quantified value.
    assertThat(embed.fields().stream().anyMatch(f -> f.name().equals("value"))).isFalse();
  }

  @Test
  void nonKillSwitchKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook);

    alerter.onAuditEvent(event("KillSwitchResetApproved", "wf-1", Map.of("approver_id_1", "a")));
    alerter.onAuditEvent(event("SignalRejected", "wf-2", Map.of("signal_id", "1:0")));

    verify(webhook, never()).postEmbed(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nullKindAndNullSubjectAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook);

    AuditEvent nullKind = event(null, "wf-3", Map.of());
    AuditEvent nullSubject = event("KillSwitchTripped", "wf-3", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (KillSwitchTripped) still pages with n/a fields.
    verify(webhook, times(1)).postEmbed(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbed(org.mockito.ArgumentMatchers.any());
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook);

    AuditEvent event = event("KillSwitchTripped", "wf-4", Map.of("reason", "auto:daily_loss"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void unconfiguredWebhookIsNoOpNoThrow() {
    // The real Discord transport with a blank URL must be a no-op (no HTTP, no throw) so CI / tests
    // without a configured webhook never fail. KillSwitchAlerter delegates to it directly.
    DiscordWebhookClient blankUrlClient = new DiscordWebhookClient("");
    KillSwitchAlerter alerter = new KillSwitchAlerter(blankUrlClient);

    AuditEvent event = event("KillSwitchTripped", "wf-5", Map.of("reason", "auto:daily_loss"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbed(captor.capture());
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
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(OffsetDateTime.parse("2026-06-14T13:30:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:KillSwitchWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("dev/copytrade-v1");
    ev.setSubject(subject);
    return ev;
  }
}
