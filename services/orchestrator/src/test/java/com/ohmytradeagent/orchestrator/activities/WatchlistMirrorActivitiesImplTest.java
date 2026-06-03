package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
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
