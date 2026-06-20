package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.alert.TenantWebhookResolver;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.workflows.WatchlistTriggerSessionWorkflow;
import io.temporal.client.WorkflowClient;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Ingest-side coverage for {@link WatchlistMirrorActivitiesImpl} (deliverable 3). Asserts the
 * Discord mirror output is UNCHANGED by the fan-out, that a session is started only when the parse
 * is clean AND the strategy is the configured trigger strategy AND it is enabled, and that a
 * session-start exception never breaks the mirror. {@code newWorkflowStub} is the observable gate:
 * it is invoked iff the session is about to be started.
 */
class WatchlistMirrorActivitiesIngestTest {

  private static final TenantWebhookResolver BLANK_RESOLVER =
      new TenantWebhookResolver("", "", null, java.time.Duration.ofSeconds(30));
  private static final String TRIGGER_ID = "watchlist-trigger-v1";

  private static final String REAL_SAMPLE =
      "SPY   756c  >  755.30\n745p  <  748.00\nQQQ   512c  >  511.00";

  private static WatchlistMirrorPayload payload(String strategyId, String raw) {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId(strategyId);
    p.setEtDate(LocalDate.of(2026, 6, 3));
    p.setAuthor("TradingTheTrend");
    p.setRawText(raw);
    p.setSourceMessageId("msg-1");
    return p;
  }

  private static StrategyConfig config(Boolean enabled) {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId(TRIGGER_ID);
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setEnabled(enabled);
    return c;
  }

  private static WatchlistMirrorActivitiesImpl activity(
      WebhookClient webhook, WorkflowClient client, StrategyRegistry registry) {
    return new WatchlistMirrorActivitiesImpl(
        webhook, BLANK_RESOLVER, client, registry, TRIGGER_ID, "orchestrator-core");
  }

  @Test
  void cleanTriggerStrategyEnabled_startsSession_andMirrorEmbedUnchanged() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    lenient()
        .when(
            client.newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class)))
        .thenReturn(mock(WatchlistTriggerSessionWorkflow.class));
    when(registry.get("dev", TRIGGER_ID)).thenReturn(config(true));

    activity(webhook, client, registry).postWatchlistAlert(payload(TRIGGER_ID, REAL_SAMPLE));

    // Mirror embed identical to the mirror-only path.
    ArgumentCaptor<WebhookEmbed> embed = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbedToUrl(anyString(), embed.capture());
    assertThat(embed.getValue().description()).contains("📈 **SPY 756C** — breaks above 755.30");
    verify(webhook, never()).postToUrl(anyString(), anyString());

    // The fan-out gate opened: a session stub was created (the start was attempted).
    verify(client, times(1))
        .newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void nonTriggerStrategy_doesNotStartSession_butMirrorStillPosts() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);

    activity(webhook, client, registry).postWatchlistAlert(payload("copytrade-v1", REAL_SAMPLE));

    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
    verify(client, never())
        .newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void disabledStrategy_doesNotStartSession_butMirrorStillPosts() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("dev", TRIGGER_ID)).thenReturn(config(false));

    activity(webhook, client, registry).postWatchlistAlert(payload(TRIGGER_ID, REAL_SAMPLE));

    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
    verify(client, never())
        .newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void notCleanParse_doesNotStartSession_andFallsBackToRawMirror() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);

    activity(webhook, client, registry).postWatchlistAlert(payload(TRIGGER_ID, "lol no setups"));

    verify(webhook, times(1)).postToUrl(anyString(), anyString());
    verify(client, never())
        .newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void sessionStartException_doesNotBreakMirror() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("dev", TRIGGER_ID)).thenReturn(config(true));
    when(client.newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class)))
        .thenThrow(new RuntimeException("temporal down"));

    WatchlistMirrorActivitiesImpl activity = activity(webhook, client, registry);
    assertThatCode(() -> activity.postWatchlistAlert(payload(TRIGGER_ID, REAL_SAMPLE)))
        .doesNotThrowAnyException();

    // Mirror still posted despite the start blowing up.
    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
  }

  @Test
  void noClientWired_legacyConstructor_mirrorOnly() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    assertThatCode(() -> activity.postWatchlistAlert(payload(TRIGGER_ID, REAL_SAMPLE)))
        .doesNotThrowAnyException();
    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
  }
}
