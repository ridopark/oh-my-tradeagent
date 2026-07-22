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
 * B2 (P0c-b1): KillSwitchTripped → Discord pager. Mirrors {@link OrderFailureAlerterTest} — the
 * webhook client is mocked, so no live secret is required, and the unconfigured-webhook no-op path
 * is exercised via the real {@link DiscordWebhookClient} contract (a blank URL = no-op, no throw).
 */
class KillSwitchAlerterTest {

  /**
   * A blank-resolving resolver (no DB, no env, no global) so the URL is "" — embed content is the
   * unit under test here; routing is covered by AlerterWebhookRoutingTest.
   */
  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  @Test
  void killSwitchTrippedDispatchesRedEmbedWithTenantStrategyReasonActorValueTradingDay() {
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

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
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

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
  void killSwitchTrippedFlattenManual_pagesExplicitNoAutoFlattenLineWithCountAndMtm() {
    // Phase 2 (PLAN-2026-07-15) + C3: an AUTO loss-cap trip that no longer auto-flattens stamps
    // flatten=manual plus the open-position count + MTM. The embed must carry the explicit
    // actionable body line AND surface the count/MTM so the operator can gauge exposure.
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:account_daily_loss");
    subject.put("actor", "auto:account_daily_loss");
    subject.put("trading_day", "2026-06-14");
    subject.put("scope", "account");
    subject.put("flatten", "manual");
    subject.put("open_positions", 3);
    subject.put("open_mtm", "-2500");
    AuditEvent event = event("KillSwitchTripped", "t-dev/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.description())
        .contains(
            "NOT auto-flattened",
            "close them manually in Alpaca",
            "trip the kill switch to flatten",
            "Open positions: 3",
            "Unrealized P&L: -$2,500");
    assertThat(embed.description()).doesNotContain("Open MTM: -2500");
  }

  @Test
  void mtmUnavailableTrip_rendersYellowFailSafeFraming_notLoss() {
    // 2026-07-21: a fail-closed data-availability trip (a transient option-quote miss) on a
    // profitable day. It must NOT read as a loss-cap breach — YELLOW fail-safe framing, no
    // "loss"/"breach" wording anywhere in the headline/body.
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:account_mtm_unavailable");
    subject.put("actor", "auto:account_mtm_unavailable");
    subject.put("trading_day", "2026-07-21");
    AuditEvent event = event("KillSwitchTripped", "t-prod_real/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(16705372); // yellow, not red
    String rendered =
        (embed.title() + " " + String.valueOf(embed.description()))
            .toLowerCase(java.util.Locale.US);
    assertThat(rendered).contains("fail-safe");
    assertThat(rendered).contains("temporarily unreadable");
    assertThat(rendered).doesNotContain("loss");
    assertThat(rendered).doesNotContain("breach");
  }

  @Test
  void dailyLossTrip_rendersRedBreachFraming() {
    // A real loss-cap breach keeps the existing RED "Kill switch TRIPPED" framing unchanged.
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:account_daily_loss");
    subject.put("actor", "auto:account_daily_loss");
    subject.put("trading_day", "2026-07-21");
    AuditEvent event = event("KillSwitchTripped", "t-prod_real/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(embed.title()).contains("Kill switch TRIPPED", "auto:account_daily_loss");
  }

  @Test
  void killSwitchTrippedFlattenManual_withoutCountOrMtm_stillPagesTheLine() {
    // The count/MTM keys may be absent (e.g. the fail-closed mtm-unavailable trip carries no MTM):
    // the actionable line still renders; the missing keys just add no extra rows.
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:account_mtm_unavailable");
    subject.put("flatten", "manual");
    subject.put("open_positions", 2);
    AuditEvent event = event("KillSwitchTripped", "t-dev/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.description()).contains("NOT auto-flattened", "Open positions: 2");
    assertThat(embed.description()).doesNotContain("Open MTM:");
  }

  @Test
  void killSwitchTrippedWithoutFlattenKey_hasNoManualFlattenLine() {
    // A legacy trip (or any trip recorded before the no-auto-flatten policy) carries no `flatten`
    // key => the embed must NOT add the manual-flatten line (description stays null).
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:daily_loss");
    subject.put("actor", "auto:daily_loss");
    subject.put("trading_day", "2026-06-14");
    AuditEvent event = event("KillSwitchTripped", "t-dev/s-copytrade-v1/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.description()).isNull();
  }

  @Test
  void nonKillSwitchKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    alerter.onAuditEvent(event("KillSwitchResetApproved", "wf-1", Map.of("approver_id_1", "a")));
    alerter.onAuditEvent(event("SignalRejected", "wf-2", Map.of("signal_id", "1:0")));

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nullKindAndNullSubjectAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    AuditEvent nullKind = event(null, "wf-3", Map.of());
    AuditEvent nullSubject = event("KillSwitchTripped", "wf-3", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (KillSwitchTripped) still pages with n/a fields.
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
    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, RESOLVER);

    AuditEvent event = event("KillSwitchTripped", "wf-4", Map.of("reason", "auto:daily_loss"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void unconfiguredWebhookIsNoOpNoThrow() {
    // The real Discord transport with a blank URL must be a no-op (no HTTP, no throw) so CI / tests
    // without a configured webhook never fail. KillSwitchAlerter delegates to it directly.
    DiscordWebhookClient blankUrlClient = new DiscordWebhookClient("", "");
    KillSwitchAlerter alerter = new KillSwitchAlerter(blankUrlClient, RESOLVER);

    AuditEvent event = event("KillSwitchTripped", "wf-5", Map.of("reason", "auto:daily_loss"));

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
