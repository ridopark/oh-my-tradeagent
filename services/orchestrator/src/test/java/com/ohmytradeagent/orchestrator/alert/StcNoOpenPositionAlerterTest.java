package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PLAN-2026-07-21-benign-stc-no-position: benign informational alerter that posts a YELLOW Discord
 * note when a copytrade STC arrived after the position was ALREADY FULLY CLOSED (audit kind {@code
 * StcNoOpenPosition}, emitted by handleStc Sites A/B). It must NOT page RED — that stays owned by
 * {@link OrderFailureAlerter} for the genuine Site-C dispatch failure ({@code OrphanSTC}). The
 * webhook client is mocked — no live secret required.
 */
class StcNoOpenPositionAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  private static final int YELLOW = 16705372;

  @Test
  void stcNoOpenPositionPostsOneYellowEmbedNamingSymbolAndSignalId() {
    WebhookClient webhook = mock(WebhookClient.class);
    StcNoOpenPositionAlerter alerter = new StcNoOpenPositionAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "555:0");
    subject.put("option_symbol", "NVDA260720P00200000");
    subject.put("author", "nvda_trader");
    AuditEvent event = event("StcNoOpenPosition", "wf-stc-flat-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(YELLOW);
    assertThat(embed.title()).containsIgnoringCase("no position to close");
    assertThat(field(embed, "author")).isEqualTo("nvda_trader");
    assertThat(field(embed, "symbol")).contains("NVDA260720P00200000");
    assertThat(field(embed, "signal_id")).isEqualTo("555:0");
    assertThat(field(embed, "note")).contains("nothing to sell");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void stcNoOpenPositionSiteBShapePostsYellowEmbed() {
    // Site B subject shape (found-but-not-RUNNING): signal_id, option_symbol, position_workflow_id,
    // reason — and NO author. author renders n/a (null-safe), still exactly one YELLOW post.
    WebhookClient webhook = mock(WebhookClient.class);
    StcNoOpenPositionAlerter alerter = new StcNoOpenPositionAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "555:1");
    subject.put("option_symbol", "NVDA260720P00200000");
    subject.put("position_workflow_id", "pos-1");
    subject.put("reason", "position_workflow_not_running");
    AuditEvent event = event("StcNoOpenPosition", "wf-stc-flat-2", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(YELLOW);
    assertThat(field(embed, "author")).isEqualTo("n/a");
    assertThat(field(embed, "signal_id")).isEqualTo("555:1");
  }

  @Test
  void nonMatchingKindPostsNothing() {
    WebhookClient webhook = mock(WebhookClient.class);
    StcNoOpenPositionAlerter alerter = new StcNoOpenPositionAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "666:0");
    subject.put("option_symbol", "NVDA260720P00200000");
    // OrphanSTC (the genuine Site-C failure) is owned by OrderFailureAlerter, not this one.
    alerter.onAuditEvent(event("OrphanSTC", "wf-1", subject));
    alerter.onAuditEvent(event("ExitRequested", "wf-1", subject));

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbedToUrl(anyString(), any());
    StcNoOpenPositionAlerter alerter = new StcNoOpenPositionAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "777:0");
    subject.put("option_symbol", "NVDA260720P00200000");
    AuditEvent event = event("StcNoOpenPosition", "wf-1", subject);

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void resolverFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    TenantWebhookResolver resolver = mock(TenantWebhookResolver.class);
    org.mockito.Mockito.when(resolver.resolve(anyString(), anyString()))
        .thenThrow(new RuntimeException("resolve boom"));
    StcNoOpenPositionAlerter alerter = new StcNoOpenPositionAlerter(webhook, resolver);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "888:0");
    AuditEvent event = event("StcNoOpenPosition", "wf-1", subject);

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void nullSubjectAndNullKindAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    StcNoOpenPositionAlerter alerter = new StcNoOpenPositionAlerter(webhook, RESOLVER);

    assertThatCode(() -> alerter.onAuditEvent(event(null, "wf-1", Map.of())))
        .doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(event("StcNoOpenPosition", "wf-1", null)))
        .doesNotThrowAnyException();

    // null-subject on a matching kind still posts (n/a fields); null-kind must not post.
    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbedToUrl(anyString(), captor.capture());
    return captor.getValue();
  }

  /** Returns the single field with {@code name}, failing if absent or duplicated. */
  private static String field(WebhookEmbed embed, String name) {
    List<WebhookEmbed.Field> matches =
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
    ev.setOccurredAt(OffsetDateTime.parse("2026-07-21T15:00:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("corr-1");
    ev.setSubject(subject);
    return ev;
  }
}
