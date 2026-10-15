package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistDailyRepository.Idea;
import com.ohmytradeagent.orchestrator.alert.TenantWebhookResolver;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The shared daily watchlist copy for the /live chain picker: the SQL shape that makes it
 * write-once per day, the idea mapping (which must match what the trigger session trades), and the
 * guarantee that a database problem never reaches the digest post.
 */
class WatchlistDailyRepositoryTest {

  private static final String CLEAN = "SPY   756c  >  755.30\n745p  <  748.00\nNVDA 185c > 181";

  @Test
  void record_isWriteOncePerDay_withExplicitCasts() {
    DSLContext dsl = mock(DSLContext.class);
    new WatchlistDailyRepository(dsl, new ObjectMapper())
        .record(LocalDate.of(2026, 10, 15), "msg-1", "TradingTheTrend", List.of());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl)
        .execute(sql.capture(), eq("2026-10-15"), eq("msg-1"), eq("TradingTheTrend"), eq("[]"));
    assertThat(sql.getValue())
        .contains("ON CONFLICT (et_date) DO NOTHING")
        .contains("?::date")
        .contains("?::jsonb");
  }

  @Test
  void ideasOf_mapsCallsAboveAndPutsBelow_likeTheTriggerSession() {
    List<Idea> ideas = WatchlistDailyRepository.ideasOf(WatchlistParser.parse(CLEAN).rows());

    assertThat(ideas)
        .containsExactly(
            new Idea("SPY", "C", "756", "ABOVE", new BigDecimal("755.30")),
            new Idea("SPY", "P", "745", "BELOW", new BigDecimal("748.00")),
            new Idea("NVDA", "C", "185", "ABOVE", new BigDecimal("181")));
  }

  @Test
  void mirror_recordsTheParsedIdeas() {
    WatchlistDailyRepository repo = mock(WatchlistDailyRepository.class);
    activity(mock(WebhookClient.class), repo).postWatchlistAlert(payload(CLEAN));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Idea>> ideas = ArgumentCaptor.forClass(List.class);
    verify(repo).record(any(), anyString(), anyString(), ideas.capture());
    assertThat(ideas.getValue()).hasSize(3);
  }

  @Test
  void mirror_uncleanPost_isNotRecorded() {
    WatchlistDailyRepository repo = mock(WatchlistDailyRepository.class);
    // One good line plus one malformed level: rows are NOT empty, so only the clean-parse gate
    // stops this (the trigger session would not arm it either).
    activity(mock(WebhookClient.class), repo)
        .postWatchlistAlert(payload("NVDA 185c > 181\n175p < ??"));

    verify(repo, never()).record(any(), anyString(), anyString(), any());
  }

  @Test
  void mirror_recordFailure_stillPostsTheDigest() {
    WebhookClient webhook = mock(WebhookClient.class);
    WatchlistDailyRepository repo = mock(WatchlistDailyRepository.class);
    doThrow(new RuntimeException("db down"))
        .when(repo)
        .record(any(), anyString(), anyString(), any());

    assertThatCode(() -> activity(webhook, repo).postWatchlistAlert(payload(CLEAN)))
        .doesNotThrowAnyException();
    verify(webhook, times(1)).postEmbedToUrl(any(), any());
  }

  private static WatchlistMirrorActivitiesImpl activity(
      WebhookClient webhook, WatchlistDailyRepository repo) {
    TenantWebhookResolver resolver = mock(TenantWebhookResolver.class);
    return new WatchlistMirrorActivitiesImpl(
        webhook, resolver, null, null, "", "orchestrator-core", repo);
  }

  private static WatchlistMirrorPayload payload(String raw) {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("staging_paper");
    p.setStrategyId("watchlist-trigger-v1");
    p.setEtDate(LocalDate.of(2026, 10, 15));
    p.setAuthor("TradingTheTrend");
    p.setRawText(raw);
    p.setSourceMessageId("msg-1");
    return p;
  }
}
