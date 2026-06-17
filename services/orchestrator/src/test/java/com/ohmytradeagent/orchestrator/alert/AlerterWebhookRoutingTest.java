package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies an alerter routes through {@link TenantWebhookResolver} with the AuditEvent's exact
 * {@code (tenantId, strategyId)} and posts the embed to the resolved URL via {@link
 * WebhookClient#postEmbedToUrl}. All URLs are fake — NO real Discord webhook in the repo.
 */
class AlerterWebhookRoutingTest {

  private static final String RESOLVED_URL = "https://example.test/webhook/resolved";

  @Test
  void killSwitchAlerter_resolvesWithEventTenantStrategy_andPostsToResolvedUrl() {
    WebhookClient webhook = mock(WebhookClient.class);
    TenantWebhookResolver resolver = mock(TenantWebhookResolver.class);
    when(resolver.resolve(eq("acme"), eq("strat-7"))).thenReturn(RESOLVED_URL);

    KillSwitchAlerter alerter = new KillSwitchAlerter(webhook, resolver);

    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId("acme");
    event.setStrategyId("strat-7");
    event.setEventId("00000000-0000-4000-8000-00000000aaaa");
    event.setOccurredAt(OffsetDateTime.parse("2026-06-14T13:30:00Z"));
    event.setKind("KillSwitchTripped");
    event.setActor("workflow:KillSwitchWorkflow");
    event.setWorkflowId("wf-1");
    event.setCorrelationId("acme/strat-7");
    event.setSubject(Map.of("reason", "auto:daily_loss"));

    alerter.onAuditEvent(event);

    // Resolver is consulted with the event's exact tenant + strategy.
    verify(resolver, times(1)).resolve("acme", "strat-7");

    // The embed is posted to the explicit resolved URL (not the tenant-scoped overload).
    ArgumentCaptor<WebhookEmbed> embedCaptor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbedToUrl(eq(RESOLVED_URL), embedCaptor.capture());
    assertThat(embedCaptor.getValue().title()).contains("Kill switch TRIPPED");
    // The tenant-scoped overload is no longer used by the routed path.
    verify(webhook, times(0)).postEmbed(any(String.class), any(WebhookEmbed.class));
  }
}
