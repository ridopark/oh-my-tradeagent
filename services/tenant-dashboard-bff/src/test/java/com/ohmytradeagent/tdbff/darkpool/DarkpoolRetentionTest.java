package com.ohmytradeagent.tdbff.darkpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Two sweeps, two horizons: prints at 90 days, bars at 400. Bounded, and never fatal. */
class DarkpoolRetentionTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void printsAreCutAt90DaysAndBarsAt400Days() {
    DarkpoolRepository repo = mock(DarkpoolRepository.class);
    when(repo.deletePrintsOlderThan(any(), eq(DarkpoolRetention.BATCH))).thenReturn(0);
    when(repo.deleteBarsOlderThan(any(), eq(DarkpoolRetention.BATCH))).thenReturn(0);

    new DarkpoolRetention(repo, FIXED).runOnce();

    ArgumentCaptor<OffsetDateTime> prints = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repo).deletePrintsOlderThan(prints.capture(), eq(DarkpoolRetention.BATCH));
    assertThat(prints.getValue().toInstant()).isEqualTo(Instant.parse("2026-07-31T12:00:00Z"));

    ArgumentCaptor<OffsetDateTime> bars = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repo).deleteBarsOlderThan(bars.capture(), eq(DarkpoolRetention.BATCH));
    assertThat(bars.getValue().toInstant()).isEqualTo(Instant.parse("2025-09-24T12:00:00Z"));
  }

  @Test
  void keepsBatchingWhileFullBatchesComeBack_thenStops_andSumsBothSweeps() {
    DarkpoolRepository repo = mock(DarkpoolRepository.class);
    when(repo.deletePrintsOlderThan(any(), eq(DarkpoolRetention.BATCH)))
        .thenReturn(DarkpoolRetention.BATCH, DarkpoolRetention.BATCH, 7);
    when(repo.deleteBarsOlderThan(any(), eq(DarkpoolRetention.BATCH))).thenReturn(3);

    int removed = new DarkpoolRetention(repo, FIXED).runOnce();

    assertThat(removed).isEqualTo(DarkpoolRetention.BATCH * 2 + 7 + 3);
    verify(repo, times(3)).deletePrintsOlderThan(any(), eq(DarkpoolRetention.BATCH));
    verify(repo, times(1)).deleteBarsOlderThan(any(), eq(DarkpoolRetention.BATCH));
  }

  @Test
  void stopsAtThePerRunCapSoOneNightCannotRunAway() {
    DarkpoolRepository repo = mock(DarkpoolRepository.class);
    when(repo.deletePrintsOlderThan(any(), eq(DarkpoolRetention.BATCH)))
        .thenReturn(DarkpoolRetention.BATCH);
    when(repo.deleteBarsOlderThan(any(), eq(DarkpoolRetention.BATCH))).thenReturn(0);

    new DarkpoolRetention(repo, FIXED).runOnce();

    verify(repo, times(DarkpoolRetention.MAX_BATCHES))
        .deletePrintsOlderThan(any(), eq(DarkpoolRetention.BATCH));
  }

  @Test
  void aFailedPrintSweepNeverEscapes_andTheBarSweepStillRuns() {
    DarkpoolRepository repo = mock(DarkpoolRepository.class);
    when(repo.deletePrintsOlderThan(any(), eq(DarkpoolRetention.BATCH)))
        .thenThrow(new RuntimeException("db gone"));
    when(repo.deleteBarsOlderThan(any(), eq(DarkpoolRetention.BATCH))).thenReturn(2);

    int removed = new DarkpoolRetention(repo, FIXED).runOnce();

    assertThat(removed).isEqualTo(2);
    verify(repo).deleteBarsOlderThan(any(), eq(DarkpoolRetention.BATCH));
  }
}
