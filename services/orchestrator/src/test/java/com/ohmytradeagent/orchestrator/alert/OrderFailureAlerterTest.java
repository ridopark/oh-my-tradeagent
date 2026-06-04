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
 * Issue #297: per-failure-class dispatch + non-blocking guarantee for the audit-driven Discord
 * alerter. The webhook client is mocked — no live secret required.
 *
 * <p>Issue #311: also asserts the feed-toggle conditional behavior — when the signal-feed mirror is
 * OFF, this alerter must own {@code SignalRejected} (no-alert gap regression guard); when ON, it
 * must NOT include it (de-dupe invariant).
 */
class OrderFailureAlerterTest {

  private static final String DEFAULT_ALLOWLIST = "SignalRejected,OrphanSTC,EntryExpired";
  // Issue #311: the production default from application.yml — no SignalRejected here, the union
  // happens automatically when the feed toggle is off.
  private static final String PRODUCTION_DEFAULT_ALLOWLIST = "OrphanSTC,EntryExpired";

  @Test
  void signalRejectedDispatchesRedBtoEmbedWithYahooLinkedSymbolReasonAndIds() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("option_symbol", "AAPL260116C00200000");
    subject.put("reason_code", "DAILY_LOSS_LIMIT");
    subject.put("reason_detail", "tenant daily loss exceeded");
    AuditEvent event = event("SignalRejected", "wf-sig-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(embed.color()).isEqualTo(15548997); // red
    // The contract field is a clickable Yahoo link over the padded OCC.
    assertThat(field(embed, "symbol"))
        .isEqualTo(
            "[AAPL 260116C00200000]" + "(https://finance.yahoo.com/quote/AAPL260116C00200000/)");
    assertThat(field(embed, "reason")).contains("DAILY_LOSS_LIMIT", "tenant daily loss exceeded");
    assertThat(field(embed, "signal_id")).isEqualTo("111:0");
    // workflow_id demoted to the footer.
    assertThat(embed.footer()).contains("wf-sig-1");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void orphanStcDispatchesStcEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "222:1");
    subject.put("option_symbol", "TSLA260116P00100000");
    AuditEvent event = event("OrphanSTC", "wf-orphan-2", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("STC (exit)");
    assertThat(field(embed, "symbol")).contains("TSLA260116P00100000");
    assertThat(field(embed, "signal_id")).isEqualTo("222:1");
    assertThat(embed.footer()).contains("wf-orphan-2");
  }

  @Test
  void entryExpiredDispatchesBtoEmbedWithKindAsReason() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "333:0");
    subject.put("option_symbol", "SPY260116C00500000");
    AuditEvent event = event("EntryExpired", "wf-expired-3", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol")).contains("SPY260116C00500000");
    // EntryExpired carries no reason_code; the kind is surfaced as the reason.
    assertThat(field(embed, "reason")).isEqualTo("EntryExpired");
    assertThat(field(embed, "kind")).isEqualTo("EntryExpired");
    assertThat(field(embed, "signal_id")).isEqualTo("333:0");
  }

  @Test
  void nonAllowlistedKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("SignalReceived", "wf-recv-4", Map.of("signal_id", "444:0"));

    alerter.onAuditEvent(event);

    verify(webhook, never()).postEmbed(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void allowlistIsConfigurable() {
    WebhookClient webhook = mock(WebhookClient.class);
    // Only EntryFilled in the allowlist — SignalRejected must NOT alert.
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, "EntryFilled", true);

    alerter.onAuditEvent(event("SignalRejected", "wf-5", Map.of("signal_id", "555:0")));
    verify(webhook, never()).postEmbed(org.mockito.ArgumentMatchers.any());

    alerter.onAuditEvent(event("EntryFilled", "wf-5", Map.of("signal_id", "555:0")));
    verify(webhook, times(1)).postEmbed(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    // A misbehaving WebhookClient that throws (the contract says it shouldn't, but the alerter must
    // be belt-and-suspenders non-blocking regardless).
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbed(org.mockito.ArgumentMatchers.any());
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("SignalRejected", "wf-6", Map.of("signal_id", "666:0"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void nullSubjectAndNullKindAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, DEFAULT_ALLOWLIST, true);

    AuditEvent nullKind = event(null, "wf-7", Map.of());
    AuditEvent nullSubject = event("SignalRejected", "wf-7", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (allowlisted kind) still dispatches with n/a
    // fields.
    verify(webhook, times(1)).postEmbed(org.mockito.ArgumentMatchers.any());
  }

  // ---- Issue #311 regression guards: feed-toggle ⇄ effective allowlist invariant ----

  @Test
  void feedOffWithProductionDefaultAllowlistDispatchesSignalRejectedExactlyOnce() {
    // Issue #311: when ALERT_SIGNAL_FEED_ENABLED=false (the code default) and the operator runs
    // with the production-default failure-kinds allowlist (OrphanSTC,EntryExpired from
    // application.yml), SignalRejected must still post EXACTLY ONE Discord message via this
    // alerter — otherwise the no-alert gap re-opens. This is the behavioral version of the
    // static-allowlist invariant the Step 4 panel flagged on #309.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook, PRODUCTION_DEFAULT_ALLOWLIST, /* signalFeedEnabled= */ false);

    // SignalRejected is auto-unioned into the effective allowlist.
    assertThat(alerter.failureKinds()).contains("SignalRejected");

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "777:0");
    subject.put("option_symbol", "AAPL260116C00200000");
    subject.put("reason_code", "AUTHOR_NOT_WHITELISTED");
    subject.put("reason_detail", "Donald T not on allowlist");
    AuditEvent event = event("SignalRejected", "wf-311-gap", subject);

    alerter.onAuditEvent(event);

    // Exactly one dispatch (the #311 no-alert gap is closed).
    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol")).contains("AAPL260116C00200000");
    assertThat(field(embed, "reason")).contains("AUTHOR_NOT_WHITELISTED");
  }

  @Test
  void feedOnWithProductionDefaultAllowlistOmitsSignalRejectedFromEffectiveAllowlist() {
    // Issue #311 complementary guard: when ALERT_SIGNAL_FEED_ENABLED=true (live state since
    // #308) and the operator runs with the production-default failure-kinds allowlist,
    // SignalRejected must NOT be in this alerter's effective allowlist — SignalFeedAlerter's
    // outcome:rejected path owns it. Otherwise a rejected signal posts twice (#308 invariant).
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook, PRODUCTION_DEFAULT_ALLOWLIST, /* signalFeedEnabled= */ true);

    // De-dupe invariant: SignalRejected is NOT here when the feed is on.
    assertThat(alerter.failureKinds()).doesNotContain("SignalRejected");
    assertThat(alerter.failureKinds()).containsExactlyInAnyOrder("OrphanSTC", "EntryExpired");

    // And dispatching a SignalRejected event must NOT trigger this alerter (it goes to
    // SignalFeedAlerter instead in production).
    alerter.onAuditEvent(event("SignalRejected", "wf-311-dedupe", Map.of("signal_id", "888:0")));
    verify(webhook, never()).postEmbed(org.mockito.ArgumentMatchers.any());
  }

  // ---- Issue #313 regression guard: operator-misconfig (double-post) is documented behavior ----

  @Test
  void feedOnWithExplicitSignalRejectedInAllowlistDocumentsOperatorDoublePostMisconfig() {
    // Issue #313 (follow-up from #311 / PR #312 bot review): documents the
    // operator-misconfiguration (double-post) scenario where the operator explicitly sets
    // ALERT_DISCORD_FAILURE_KINDS to include SignalRejected AND leaves
    // ALERT_SIGNAL_FEED_ENABLED=true. The #311 conditional union only ADDS SignalRejected when
    // the feed is off; it never REMOVES an operator-explicit entry. So this alerter's effective
    // allowlist STILL contains SignalRejected and STILL fires exactly one webhook for a
    // SignalRejected event — and in production, SignalFeedAlerter's outcome:rejected path would
    // ALSO fire (a second Discord post). That double-post is a known operator misconfig (not a
    // code bug): the contract is "operator-explicit allowlist wins, even if it duplicates the
    // feed mirror". SignalFeedAlerter is intentionally OUT OF SCOPE for this test — we only
    // validate this alerter's contribution (one post via the explicit allowlist), matching the
    // pattern of the #311 guards above. The double-post in production is the sum of this
    // alerter's one post plus SignalFeedAlerter's outcome:rejected post, which is exercised in
    // SignalFeedAlerter's own test suite.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook, "SignalRejected,OrphanSTC,EntryExpired", /* signalFeedEnabled= */ true);

    // Operator-explicit wins: SignalRejected is in the effective allowlist even with feed-on.
    assertThat(alerter.failureKinds()).contains("SignalRejected");

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "999:0");
    subject.put("option_symbol", "AAPL260116C00200000");
    subject.put("reason_code", "AUTHOR_NOT_WHITELISTED");
    subject.put("reason_detail", "Donald T not on allowlist");
    AuditEvent event = event("SignalRejected", "wf-313-doublepost", subject);

    alerter.onAuditEvent(event);

    // Exactly one dispatch via THIS alerter (the operator-explicit allowlist path). In
    // production, SignalFeedAlerter's outcome:rejected mirror would post a second time — that
    // second post is the documented operator-misconfig double-post and is asserted in
    // SignalFeedAlerter's own tests, not here.
    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol")).contains("AAPL260116C00200000");
    assertThat(field(embed, "reason")).contains("AUTHOR_NOT_WHITELISTED");
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbed(captor.capture());
    return captor.getValue();
  }

  /** Returns the single field with {@code name}, failing if absent or duplicated. */
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
    ev.setOccurredAt(OffsetDateTime.parse("2026-05-29T07:00:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("corr-1");
    ev.setSubject(subject);
    return ev;
  }
}
