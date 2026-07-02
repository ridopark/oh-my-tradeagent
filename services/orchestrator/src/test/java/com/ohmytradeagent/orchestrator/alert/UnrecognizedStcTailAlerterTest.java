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
 * PLAN-2026-07-01-unrecognized-stc-tail-alert: observability-only alerter that pages Discord when a
 * copytrade STC signal's free-form tail matched NO configured partial-exit keyword (the fraction
 * fell through to the default). Reacts to the committed {@code ExitRequested} audit event whose
 * subject was enriched with {@code matched_keyword} / {@code tail} / {@code author} / {@code
 * raw_line}. The webhook client is mocked — no live secret required.
 */
class UnrecognizedStcTailAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  private static final int YELLOW = 16705372;

  @Test
  void nonEmptyUnmatchedTailPostsOneYellowEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "555:0");
    subject.put("option_symbol", "DRAM260717P00060000");
    subject.put("position_workflow_id", "pos-1");
    subject.put("fraction", 0.3);
    subject.put("matched_keyword", null); // no keyword matched — default applied
    subject.put("tail", "yolo it, gg");
    subject.put("author", "dram_trader");
    subject.put("raw_line", "STC DRAM 7/17 60p @ 1.90 yolo it, gg");
    AuditEvent event = event("ExitRequested", "wf-stc-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(YELLOW);
    assertThat(embed.title()).containsIgnoringCase("unrecognized");
    assertThat(field(embed, "author")).isEqualTo("dram_trader");
    assertThat(field(embed, "tail")).isEqualTo("yolo it, gg");
    assertThat(field(embed, "raw line")).isEqualTo("STC DRAM 7/17 60p @ 1.90 yolo it, gg");
    assertThat(field(embed, "applied default fraction")).isEqualTo("0.3");
    assertThat(field(embed, "symbol")).contains("DRAM260717P00060000");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void emptyTailPostsNothing() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "555:1");
    subject.put("matched_keyword", null);
    subject.put("tail", ""); // empty tail: no guidance, default is correct — no alert
    subject.put("fraction", 0.3);
    AuditEvent event = event("ExitRequested", "wf-stc-2", subject);

    alerter.onAuditEvent(event);

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void blankTailPostsNothing() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("matched_keyword", null);
    subject.put("tail", "   "); // blank after trim — no alert
    AuditEvent event = event("ExitRequested", "wf-stc-3", subject);

    alerter.onAuditEvent(event);

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void absentTailKeyPostsNothing() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("matched_keyword", null); // tail key absent entirely
    AuditEvent event = event("ExitRequested", "wf-stc-4", subject);

    alerter.onAuditEvent(event);

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void matchedKeywordPostsNothing_evenIfFractionEqualsDefault() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("matched_keyword", "partial"); // a keyword matched
    subject.put("tail", "partial still mainly in");
    subject.put("fraction", 0.3); // equals the default, but a keyword still matched → no alert
    AuditEvent event = event("ExitRequested", "wf-stc-5", subject);

    alerter.onAuditEvent(event);

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void nonExitRequestedKindPostsNothing() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("matched_keyword", null);
    subject.put("tail", "yolo it");
    alerter.onAuditEvent(event("SignalReceived", "wf-1", subject));
    alerter.onAuditEvent(event("OrphanSTC", "wf-1", subject));

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbedToUrl(anyString(), any());
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("matched_keyword", null);
    subject.put("tail", "yolo it");
    AuditEvent event = event("ExitRequested", "wf-1", subject);

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void nullSubjectAndNullKindAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    UnrecognizedStcTailAlerter alerter = new UnrecognizedStcTailAlerter(webhook, RESOLVER);

    assertThatCode(() -> alerter.onAuditEvent(event(null, "wf-1", Map.of())))
        .doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(event("ExitRequested", "wf-1", null)))
        .doesNotThrowAnyException();

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
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
    ev.setEventId("00000000-0000-4000-8000-00000000bbbb");
    ev.setOccurredAt(OffsetDateTime.parse("2026-07-01T15:00:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("corr-1");
    ev.setSubject(subject);
    return ev;
  }
}
