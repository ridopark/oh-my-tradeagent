package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #308: the signal-feed mirror dispatches a "received" message on ingest and an "outcome"
 * message on accept/reject/skip, owns {@code SignalRejected} exclusively (so a rejected signal
 * posts exactly one Discord message — the #297 failure path no longer fires it), respects the
 * independent enable toggle, and is strictly non-blocking. The webhook client is mocked — no live
 * secret required.
 */
class SignalFeedAlerterTest {

  @Test
  void receivedBtoDispatchesReceivedMessageWithSignalDetail() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("action", "BTO");
    subject.put("ticker", "NVDA");
    subject.put("expiry", "2026-05-16");
    subject.put("strike", "140");
    subject.put("right", "C");
    subject.put("price", "2.30");
    subject.put("author", "acme_trader");
    subject.put("posted_at", "2026-05-13T17:22:31Z");
    AuditEvent event = event("SignalReceived", "wf-recv-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("received").contains("BTO").contains("NVDA");
    assertThat(embed.color()).isEqualTo(5793266); // blurple / info
    // The contract field is the Yahoo link constructed from ticker+expiry+strike+right.
    assertThat(field(embed, "contract"))
        .isEqualTo(
            "[NVDA 260516C00140000]" + "(https://finance.yahoo.com/quote/NVDA260516C00140000/)");
    assertThat(field(embed, "price")).isEqualTo("2.30");
    assertThat(field(embed, "author")).isEqualTo("acme_trader");
    assertThat(field(embed, "signal_id")).isEqualTo("111:0");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void receivedStcDispatchesReceivedMessage() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "222:1");
    subject.put("action", "STC");
    subject.put("ticker", "TSLA");
    AuditEvent event = event("SignalReceived", "wf-recv-2", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("received").contains("STC").contains("TSLA");
    assertThat(field(embed, "signal_id")).isEqualTo("222:1");
    // No expiry/strike → contract degrades to readable plain text (no link, never throws).
    assertThat(field(embed, "contract")).doesNotContain("finance.yahoo.com").contains("TSLA");
  }

  @Test
  void receivedAvgDispatchesReceivedMessage() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "333:0");
    subject.put("action", "AVG");
    subject.put("ticker", "SPY");
    AuditEvent event = event("SignalReceived", "wf-recv-3", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("received").contains("AVG").contains("SPY");
  }

  @Test
  void acceptedOutcomeDispatchesAcceptedMessageWithContractsAndRefPremium() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("option_symbol", "NVDA260516C00140000");
    subject.put("contracts", 3L);
    subject.put("ref_premium", "2.30");
    AuditEvent event = event("SignalAccepted", "wf-acc-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("accepted");
    assertThat(embed.color()).isEqualTo(5763719); // green / success
    // The resolved option_symbol becomes a Yahoo link.
    assertThat(field(embed, "symbol")).contains("NVDA260516C00140000");
    assertThat(field(embed, "accepted")).contains("3").contains("2.30");
  }

  @Test
  void rejectedOutcomeDispatchesRejectedMessageWithReason() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("reason_code", "DAILY_LOSS_LIMIT");
    subject.put("reason_detail", "tenant daily loss exceeded");
    subject.put("outcome", "REJECTED");
    AuditEvent event = event("SignalRejected", "wf-rej-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("rejected");
    assertThat(embed.color()).isEqualTo(15548997); // red / failure
    assertThat(field(embed, "rejected")).contains("DAILY_LOSS_LIMIT", "tenant daily loss exceeded");
    assertThat(field(embed, "signal_id")).isEqualTo("111:0");
  }

  @Test
  void rejectedConstructsContractFromPartsWhenNoResolvedSymbol() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("ticker", "NFLX");
    subject.put("expiry", "260918");
    subject.put("strike", "100");
    subject.put("right", "C");
    subject.put("reason_code", "AUTHOR_NOT_WHITELISTED");
    AuditEvent event = event("SignalRejected", "wf-rej-3", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    // No option_symbol → constructed from parts (the plan's matrix), Yahoo-linked.
    assertThat(field(embed, "contract"))
        .isEqualTo(
            "[NFLX 260918C00100000]" + "(https://finance.yahoo.com/quote/NFLX260918C00100000/)");
  }

  @Test
  void avgSkippedOutcomeDispatchesSkippedMessageWithNote() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "333:0");
    subject.put("note", "skip_avg_true");
    AuditEvent event = event("AvgSkipped", "wf-avg-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("AVG skipped");
    assertThat(embed.color()).isEqualTo(16705372); // yellow / warn
    assertThat(field(embed, "note")).isEqualTo("skip_avg_true");
    assertThat(field(embed, "signal_id")).isEqualTo("333:0");
  }

  /**
   * The de-dupe guarantee from the consuming side: a single {@code SignalRejected} audit event
   * posts EXACTLY ONE message via the feed alerter. Combined with the failure-allowlist change
   * (which drops {@code SignalRejected} from {@link OrderFailureAlerter}'s default), this means a
   * rejected signal produces exactly one Discord message overall.
   */
  @Test
  void rejectedSignalPostsExactlyOneMessageFromFeedAlerter() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    AuditEvent event = event("SignalRejected", "wf-rej-2", Map.of("signal_id", "111:0"));
    alerter.onAuditEvent(event);

    verify(webhook, times(1)).postEmbed(any());
  }

  @Test
  void nonSignalFeedKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    alerter.onAuditEvent(event("OrderSubmitted", "wf-1", Map.of("signal_id", "111:0")));
    alerter.onAuditEvent(event("EntryFilled", "wf-1", Map.of("signal_id", "111:0")));
    alerter.onAuditEvent(event("OrphanSTC", "wf-1", Map.of("signal_id", "111:0")));
    alerter.onAuditEvent(event("EntryExpired", "wf-1", Map.of("signal_id", "111:0")));

    verify(webhook, never()).postEmbed(any());
  }

  @Test
  void disabledTogglMakesAlerterANoOp() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ false);

    alerter.onAuditEvent(event("SignalReceived", "wf-1", Map.of("signal_id", "111:0")));
    alerter.onAuditEvent(event("SignalRejected", "wf-1", Map.of("signal_id", "111:0")));
    alerter.onAuditEvent(event("SignalAccepted", "wf-1", Map.of("signal_id", "111:0")));
    alerter.onAuditEvent(event("AvgSkipped", "wf-1", Map.of("signal_id", "111:0")));

    verify(webhook, never()).postEmbed(any());
    assertThat(alerter.enabled()).isFalse();
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbed(any());
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    AuditEvent event = event("SignalReceived", "wf-1", Map.of("signal_id", "111:0"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void nullSubjectAndNullKindAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    SignalFeedAlerter alerter = new SignalFeedAlerter(webhook, /* enabled= */ true);

    AuditEvent nullKind = event(null, "wf-1", Map.of());
    AuditEvent nullSubject = event("SignalReceived", "wf-1", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (feed kind) still dispatches with n/a fields.
    verify(webhook, times(1)).postEmbed(any());
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbed(captor.capture());
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
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(OffsetDateTime.parse("2026-05-29T07:00:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("corr-1");
    ev.setSubject(subject);
    return ev;
  }
}
