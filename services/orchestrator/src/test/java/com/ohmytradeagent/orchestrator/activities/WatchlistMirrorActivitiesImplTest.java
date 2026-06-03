package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
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
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

    activity.postWatchlistAlert(payload("AAPL calls\nTSLA puts"));

    String content = capture(webhook);
    assertThat(content).startsWith("📋 Watchlist — 2026-06-03 — via TradingTheTrend");
    assertThat(content).contains("```\nAAPL calls\nTSLA puts\n```");
  }

  @Test
  void oversizedRawTextIsTruncatedWithinDiscordLimitAndKeepsBalancedFence() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

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
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

    activity.postWatchlistAlert(payload("before ``` after"));

    String content = capture(webhook);
    // Only the two fence delimiters remain; the in-body backticks are neutralized.
    assertThat(countOccurrences(content, "```")).isEqualTo(2);
    assertThat(content).endsWith("```");
  }

  @Test
  void cleanParseablePayloadPostsRichEmbedTable() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

    String raw =
        "SPY   762c  >  761.00\n"
            + "753p  <  754.00\n"
            + "SHOP  121c  >  120.00\n"
            + "TSLA  430c  >  425.00\n"
            + "410p  <  413.00";
    activity.postWatchlistAlert(payload(raw));

    WebhookEmbed embed = captureEmbed(webhook);
    // Raw fallback path must NOT fire when the parse is clean.
    verify(webhook, never()).post(org.mockito.ArgumentMatchers.anyString());

    assertThat(embed.title()).contains("Jun 3, 2026");
    assertThat(embed.color()).isEqualTo(5763719);
    assertThat(embed.footer()).contains("TradingTheTrend");

    String desc = embed.description();
    assertThat(desc).startsWith("```");
    assertThat(desc).endsWith("```");
    assertThat(desc).contains("SPY");
    assertThat(desc).contains("762C");
    assertThat(desc).contains("761.00");
    assertThat(desc).contains("753P");
    assertThat(desc).contains("754.00");
    // SHOP has no put leg -> em-dash placeholder.
    assertThat(desc).contains("—");
  }

  @Test
  void unparseableRawTextFallsBackToRawPost() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

    activity.postWatchlistAlert(payload("lol no setups today"));

    String content = capture(webhook);
    verify(webhook, never()).postEmbed(org.mockito.ArgumentMatchers.any());
    assertThat(content).contains("```\nlol no setups today\n```");
  }

  @Test
  void doesNotThrowOnParseablePayload() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

    assertThatCode(() -> activity.postWatchlistAlert(payload("SPY 762c > 761.00")))
        .doesNotThrowAnyException();
  }

  @Test
  void doesNotThrow() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistMirrorActivitiesImpl activity = new WatchlistMirrorActivitiesImpl(webhook);

    assertThatCode(() -> activity.postWatchlistAlert(payload("ok"))).doesNotThrowAnyException();
  }

  private static String capture(WebhookClient webhook) {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(webhook, times(1)).post(captor.capture());
    return captor.getValue();
  }

  private static WebhookEmbed captureEmbed(WebhookClient webhook) {
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1)).postEmbed(captor.capture());
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
