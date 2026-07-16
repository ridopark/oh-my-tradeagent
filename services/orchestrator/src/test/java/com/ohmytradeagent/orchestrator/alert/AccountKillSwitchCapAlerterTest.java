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
 * PR #504 follow-up: AccountKillSwitchCapInactive/ReArmed → Discord pager. Mirrors {@link
 * KillSwitchAlerterTest}: the webhook client is mocked (no live secret), routing is the blank-URL
 * resolver, and the non-throwing guarantee is exercised.
 */
class AccountKillSwitchCapAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  @Test
  void capInactiveDispatchesRedEmbedWithTenantTradingDayAndTickCount() {
    WebhookClient webhook = mock(WebhookClient.class);
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("trading_day", "2026-06-29");
    subject.put("consecutive_inactive_ticks", 3);
    subject.put("scope", "account");
    AuditEvent event =
        event("AccountKillSwitchCapInactive", "t-prod_real/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(embed.title()).contains("INACTIVE", "OFF");
    assertThat(field(embed, "tenant_id")).isEqualTo("prod_real");
    assertThat(field(embed, "trading_day")).isEqualTo("2026-06-29");
    assertThat(field(embed, "consecutive_inactive_ticks")).isEqualTo("3");
    assertThat(embed.footer()).contains("t-prod_real/account/killswitch");
  }

  @Test
  void capReArmedDispatchesGreenEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("trading_day", "2026-06-29");
    subject.put("inactive_ticks", 7);
    AuditEvent event =
        event("AccountKillSwitchCapReArmed", "t-prod_real/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(5763719); // green
    assertThat(embed.title()).contains("RE-ARMED");
    assertThat(field(embed, "inactive_ticks")).isEqualTo("7");
  }

  @Test
  void stillHoldingDispatchesRedEmbedWithCountMtmAndMinutes() {
    // Phase 2b (risk C1): the periodic still-tripped-and-holding re-page. RED embed carrying the
    // open-position count, current MTM, and minutes-since-trip, with the actionable body line.
    WebhookClient webhook = mock(WebhookClient.class);
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(webhook, RESOLVER);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("reason", "auto:account_daily_loss");
    subject.put("trading_day", "2026-06-29");
    subject.put("scope", "account");
    subject.put("open_positions", 3);
    subject.put("open_mtm", "-2500");
    subject.put("minutes_since_trip", 45L);
    AuditEvent event =
        event("AccountKillSwitchStillHolding", "t-prod_real/account/killswitch", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(embed.title()).contains("STILL tripped");
    assertThat(embed.description())
        .contains(
            "3 open positions",
            "MTM -2500",
            "45 min since trip",
            "flatten manually in Alpaca",
            "trip-to-flatten",
            "reset");
    assertThat(field(embed, "open_positions")).isEqualTo("3");
    assertThat(field(embed, "open_mtm")).isEqualTo("-2500");
    assertThat(field(embed, "minutes_since_trip")).isEqualTo("45");
  }

  @Test
  void otherKindsDoNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(webhook, RESOLVER);

    alerter.onAuditEvent(event("KillSwitchTripped", "wf-1", Map.of("reason", "auto:daily_loss")));
    alerter.onAuditEvent(event("KillSwitchHeartbeatError", "wf-2", Map.of("error", "boom")));

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nullKindAndNullSubjectAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(webhook, RESOLVER);

    assertThatCode(() -> alerter.onAuditEvent(event(null, "wf-3", Map.of())))
        .doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(event("AccountKillSwitchCapInactive", "wf-3", null)))
        .doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (cap-inactive) still pages with n/a fields.
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
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(webhook, RESOLVER);

    AuditEvent event = event("AccountKillSwitchCapInactive", "wf-4", Map.of("trading_day", "x"));
    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void unconfiguredWebhookIsNoOpNoThrow() {
    DiscordWebhookClient blankUrlClient = new DiscordWebhookClient("", "");
    AccountKillSwitchCapAlerter alerter = new AccountKillSwitchCapAlerter(blankUrlClient, RESOLVER);

    AuditEvent event = event("AccountKillSwitchCapInactive", "wf-5", Map.of("trading_day", "x"));
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
    ev.setStrategyId("__account__");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(OffsetDateTime.parse("2026-06-29T13:30:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:AccountKillSwitchWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("prod_real/account");
    ev.setSubject(subject);
    return ev;
  }
}
