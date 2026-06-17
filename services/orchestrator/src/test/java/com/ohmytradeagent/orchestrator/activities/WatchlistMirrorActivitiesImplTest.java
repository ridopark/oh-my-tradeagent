package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.alert.TenantWebhookResolver;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
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
