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
 * P3-a (multi-tenant-broker-credentials): LivePromotionMissing → Discord pager. Mirrors {@link
 * KillSwitchAlerterTest} — the webhook client is mocked (no live secret required), and the
 * unconfigured-webhook no-op path is exercised via the real {@link DiscordWebhookClient} contract
 * (a blank URL = no-op, no throw).
 */
class LivePromotionMissingAlerterTest {

  @Test
  void livePromotionMissingDispatchesRedEmbedWithTenantStrategyBrokerTargetReasonSignalId() {
    WebhookClient webhook = mock(WebhookClient.class);
    LivePromotionMissingAlerter alerter = new LivePromotionMissingAlerter(webhook);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("tenant_id", "dev");
    subject.put("strategy_id", "copytrade-v1");
    subject.put("broker_target", "alpaca-live");
    subject.put("reason", "absent");
    subject.put("outcome", "REJECTED");
    AuditEvent event = event("LivePromotionMissing", "t-dev/s-copytrade-v1/signal/111:0", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(embed.title()).contains("Live promotion MISSING", "absent");
    assertThat(field(embed, "tenant_id")).isEqualTo("dev");
    assertThat(field(embed, "strategy_id")).isEqualTo("copytrade-v1");
    assertThat(field(embed, "broker_target")).isEqualTo("alpaca-live");
    assertThat(field(embed, "reason")).isEqualTo("absent");
    assertThat(field(embed, "signal_id")).isEqualTo("111:0");
    assertThat(embed.footer()).contains("t-dev/s-copytrade-v1/signal/111:0");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void nonLivePromotionKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    LivePromotionMissingAlerter alerter = new LivePromotionMissingAlerter(webhook);

    alerter.onAuditEvent(event("SignalRejected", "wf-1", Map.of("signal_id", "1:0")));
    alerter.onAuditEvent(event("KillSwitchTripped", "wf-2", Map.of("reason", "auto:daily_loss")));

    verify(webhook, never())
        .postEmbed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nullKindAndNullSubjectAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    LivePromotionMissingAlerter alerter = new LivePromotionMissingAlerter(webhook);

    AuditEvent nullKind = event(null, "wf-3", Map.of());
    AuditEvent nullSubject = event("LivePromotionMissing", "wf-3", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (LivePromotionMissing) still pages with n/a fields.
    verify(webhook, times(1))
        .postEmbed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    LivePromotionMissingAlerter alerter = new LivePromotionMissingAlerter(webhook);

    AuditEvent event = event("LivePromotionMissing", "wf-4", Map.of("reason", "stale"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void unconfiguredWebhookIsNoOpNoThrow() {
    // The real Discord transport with a blank URL must be a no-op (no HTTP, no throw) so CI / tests
    // without a configured webhook never fail. LivePromotionMissingAlerter delegates to it
    // directly.
    DiscordWebhookClient blankUrlClient = new DiscordWebhookClient("", "");
    LivePromotionMissingAlerter alerter = new LivePromotionMissingAlerter(blankUrlClient);

    AuditEvent event = event("LivePromotionMissing", "wf-5", Map.of("reason", "verify_error"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbed(org.mockito.ArgumentMatchers.anyString(), captor.capture());
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
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("dev/copytrade-v1");
    ev.setSubject(subject);
    return ev;
  }
}
