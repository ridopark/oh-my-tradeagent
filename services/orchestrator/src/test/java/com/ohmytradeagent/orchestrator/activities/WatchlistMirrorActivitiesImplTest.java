package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
 * Watchlist-mirror activity formats the verbatim daily watchlist and posts it via the SAME {@link
 * WebhookClient} the trade-alert feed uses. The webhook client is mocked — no live secret required.
 */
class WatchlistMirrorActivitiesImplTest {

  private static final TenantWebhookResolver BLANK_RESOLVER =
      new TenantWebhookResolver("", "", null, java.time.Duration.ofSeconds(30));

  private static WatchlistMirrorPayload payload(String raw) {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setEtDate(LocalDate.of(2026, 6, 3));
    p.setAuthor("TradingTheTrend");
    p.setRawText(raw);
    p.setSourceMessageId("msg-1");
    return p;
  }

  @Test
  void postsExactlyOnceWithHeaderAndFencedRawText() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    activity.postWatchlistAlert(payload("AAPL calls\nTSLA puts"));

    String content = capture(webhook);
    assertThat(content).startsWith("📋 Watchlist — 2026-06-03 — via TradingTheTrend");
    assertThat(content).contains("```\nAAPL calls\nTSLA puts\n```");
  }

  @Test
  void oversizedRawTextIsTruncatedWithinDiscordLimitAndKeepsBalancedFence() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    String huge = "x".repeat(5000);
    activity.postWatchlistAlert(payload(huge));

    String content = capture(webhook);
    assertThat(content.length()).isLessThanOrEqualTo(2000);
    assertThat(content).contains("… (truncated)");
    // Balanced fence: an opening ``` after the header and a closing ``` at the very end.
    assertThat(content).endsWith("```");
    assertThat(countOccurrences(content, "```")).isEqualTo(2);
  }

  @Test
  void literalTripleBacktickInRawTextIsNeutralizedSoFenceStaysIntact() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    activity.postWatchlistAlert(payload("before ``` after"));

    String content = capture(webhook);
    // Only the two fence delimiters remain; the in-body backticks are neutralized.
    assertThat(countOccurrences(content, "```")).isEqualTo(2);
    assertThat(content).endsWith("```");
  }

  /** The real watchlist shape: SPY/QQQ call+put, MSFT put-only, ABBV/SCHW/USB call-only. */
  private static final String REAL_SAMPLE =
      "SPY   756c  >  755.30\n"
          + "745p  <  748.00\n"
          + "QQQ   512c  >  511.00\n"
          + "505p  <  507.50\n"
          + "MSFT  420p  <  424.00\n"
          + "ABBV  185c  >  184.00\n"
          + "SCHW  78c   >  77.50\n"
          + "USB   45c   >  44.80";

  @Test
  void cleanParseablePayloadPostsPerPlayEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    activity.postWatchlistAlert(payload(REAL_SAMPLE));

    WebhookEmbed embed = captureEmbed(webhook);
    // Raw fallback path must NOT fire when the parse is clean.
    verify(webhook, never())
        .postToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    assertThat(embed.title()).contains("Jun 3, 2026");
    assertThat(embed.color()).isEqualTo(5763719);
    assertThat(embed.footer()).contains("TradingTheTrend");

    String desc = embed.description();
    // Per-play lines, both legs of SPY, correct emoji/direction/trigger.
    assertThat(desc).contains("📈 **SPY 756C** — breaks above 755.30");
    assertThat(desc).contains("📉 **SPY 745P** — breaks below 748.00");
    assertThat(desc).contains("📈 **QQQ 512C** — breaks above 511.00");
    assertThat(desc).contains("📉 **QQQ 505P** — breaks below 507.50");
    // MSFT is put-only: emits exactly its put line, NO call line, NO em-dash placeholder.
    assertThat(desc).contains("📉 **MSFT 420P** — breaks below 424.00");
    assertThat(desc).doesNotContain("📈 **MSFT");
    // Call-only tickers emit only their call line.
    assertThat(desc).contains("📈 **ABBV 185C** — breaks above 184.00");
    assertThat(desc).contains("📈 **SCHW 78C** — breaks above 77.50");
    assertThat(desc).contains("📈 **USB 45C** — breaks above 44.80");
    // One-sided tickers emit exactly one line (no placeholder leg line). The em-dash is only ever
    // the play→trigger separator inside a real line, never a standalone missing-leg placeholder:
    // exactly one em-dash per emitted leg line, so its count equals the number of legs (8 legs:
    // SPY 2, QQQ 2, MSFT 1, ABBV 1, SCHW 1, USB 1).
    assertThat(countOccurrences(desc, "—")).isEqualTo(8);
    // No code fence — the embed uses its full width.
    assertThat(desc).doesNotContain("```");
  }

  @Test
  void trailingGreetingStillRendersEmbedNotRawFallback() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    // Part A: a trailing "Good luck @everyone" is ignorable chatter → still a clean embed.
    activity.postWatchlistAlert(payload(REAL_SAMPLE + "\n\nGood luck @everyone"));

    WebhookEmbed embed = captureEmbed(webhook);
    verify(webhook, never())
        .postToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    assertThat(embed.description()).contains("📈 **SPY 756C** — breaks above 755.30");
  }

  @Test
  void unparseableRawTextFallsBackToRawPost() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    activity.postWatchlistAlert(payload("lol no setups today"));

    String content = capture(webhook);
    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    assertThat(content).contains("```\nlol no setups today\n```");
  }

  @Test
  void doesNotThrowOnParseablePayload() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    assertThatCode(() -> activity.postWatchlistAlert(payload("SPY 762c > 761.00")))
        .doesNotThrowAnyException();
  }

  @Test
  void doesNotThrow() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity =
        new WatchlistMirrorActivitiesImpl(webhook, BLANK_RESOLVER);

    assertThatCode(() -> activity.postWatchlistAlert(payload("ok"))).doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------------------------
  // Per-(tenant, etDate) digest dedup (Phase 1). The fan-out-wired constructor enables the digest
  // marker; the package-private startDigestMarker(tenant, etDate) seam is the dedup decision point
  // (true = this call won today's marker → post; false = WorkflowExecutionAlreadyStarted → skip the
  // post but STILL run the session start). A Mockito spy drives the seam because a real
  // WorkflowClient.start on a mock client is a no-op (cannot surface REJECT_DUPLICATE in a unit
  // test).
  // ---------------------------------------------------------------------------------------------

  private static final String TRIGGER_ID = "watchlist-trigger-v1";

  private static WatchlistMirrorPayload payload(String tenantId, String strategyId, String raw) {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId(tenantId);
    p.setStrategyId(strategyId);
    p.setEtDate(LocalDate.of(2026, 6, 3));
    p.setAuthor("TradingTheTrend");
    p.setRawText(raw);
    p.setSourceMessageId("msg-1");
    return p;
  }

  private static StrategyConfig triggerConfig(Boolean enabled) {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("prod_real");
    c.setStrategyId(TRIGGER_ID);
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setEnabled(enabled);
    return c;
  }

  private static WatchlistMirrorActivitiesImpl fanoutActivity(
      WebhookClient webhook, WorkflowClient client, StrategyRegistry registry) {
    return new WatchlistMirrorActivitiesImpl(
        webhook, BLANK_RESOLVER, client, registry, TRIGGER_ID, "orchestrator-core");
  }

  private static final String CLEAN_SAMPLE = "SPY   756c  >  755.30\n745p  <  748.00";

  @Test
  void digest_posted_once_per_tenant_when_two_strategies_fan_out() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    lenient()
        .when(
            client.newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class)))
        .thenReturn(mock(WatchlistTriggerSessionWorkflow.class));
    lenient().when(registry.get(anyString(), anyString())).thenReturn(triggerConfig(true));

    WatchlistMirrorActivitiesImpl activity = spy(fanoutActivity(webhook, client, registry));
    // First fan-out entry wins the (tenant, etDate) digest marker; the second sees it already
    // started (REJECT_DUPLICATE) and must NOT re-post.
    doReturn(true, false).when(activity).startDigestMarker("prod_real", "2026-06-03");

    activity.postWatchlistAlert(payload("prod_real", "copytrade-v1", CLEAN_SAMPLE));
    activity.postWatchlistAlert(payload("prod_real", TRIGGER_ID, CLEAN_SAMPLE));

    // Exactly ONE digest embed for the tenant across the two fan-out entries.
    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
    verify(webhook, never()).postToUrl(anyString(), anyString());
  }

  @Test
  void trigger_session_started_for_watchlist_strategy_even_when_digest_skipped() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(client.newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class)))
        .thenReturn(mock(WatchlistTriggerSessionWorkflow.class));
    when(registry.get("prod_real", TRIGGER_ID)).thenReturn(triggerConfig(true));

    WatchlistMirrorActivitiesImpl activity = spy(fanoutActivity(webhook, client, registry));
    // This trigger-strategy entry's digest was already posted by another fan-out entry → skip post.
    doReturn(false).when(activity).startDigestMarker("prod_real", "2026-06-03");

    activity.postWatchlistAlert(payload("prod_real", TRIGGER_ID, CLEAN_SAMPLE));

    // Digest post was deduped away...
    verify(webhook, never()).postEmbedToUrl(anyString(), any());
    verify(webhook, never()).postToUrl(anyString(), anyString());
    // ...but the trigger session start still ran (dedup gates the POST only, never the session).
    verify(client, times(1))
        .newWorkflowStub(
            eq(WatchlistTriggerSessionWorkflow.class),
            any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void trigger_session_not_started_when_disabled() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("prod_real", TRIGGER_ID)).thenReturn(triggerConfig(false));

    WatchlistMirrorActivitiesImpl activity = spy(fanoutActivity(webhook, client, registry));
    doReturn(true).when(activity).startDigestMarker("prod_real", "2026-06-03");

    activity.postWatchlistAlert(payload("prod_real", TRIGGER_ID, CLEAN_SAMPLE));

    // Digest still posts once (this entry won the marker)...
    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
    // ...but the disabled guard blocks the session start entirely.
    verify(client, never())
        .newWorkflowStub(any(Class.class), any(io.temporal.client.WorkflowOptions.class));
  }

  @Test
  void malformed_watchlist_still_posts_raw_once() {
    WebhookClient webhook = mock(WebhookClient.class);
    WorkflowClient client = mock(WorkflowClient.class);
    StrategyRegistry registry = mock(StrategyRegistry.class);

    WatchlistMirrorActivitiesImpl activity = spy(fanoutActivity(webhook, client, registry));
    // Two fan-out entries for the same tenant/day: first wins the marker, second is a duplicate.
    doReturn(true, false).when(activity).startDigestMarker("prod_real", "2026-06-03");

    activity.postWatchlistAlert(payload("prod_real", "copytrade-v1", "lol no setups today"));
    activity.postWatchlistAlert(payload("prod_real", TRIGGER_ID, "lol no setups today"));

    // Raw fallback path is ALSO deduped per tenant: exactly one raw post, never an embed.
    verify(webhook, times(1)).postToUrl(anyString(), anyString());
    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  private static String capture(WebhookClient webhook) {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(webhook, times(1)).postToUrl(org.mockito.ArgumentMatchers.anyString(), captor.capture());
    return captor.getValue();
  }

  private static WebhookEmbed captureEmbed(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1))
        .postEmbedToUrl(org.mockito.ArgumentMatchers.anyString(), captor.capture());
    return captor.getValue();
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) != -1) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
