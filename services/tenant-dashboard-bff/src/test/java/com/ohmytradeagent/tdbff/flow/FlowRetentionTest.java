package com.ohmytradeagent.tdbff.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Two sweeps, two horizons: orders at 90 days, ticker-days at 730 days. Bounded, and never fatal.
 */
class FlowRetentionTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void ordersAreCutAt90Days_andTickerDaysAt730Days() {
    FlowRepository repo = mock(FlowRepository.class);
    when(repo.deleteOrdersOlderThan(any(), eq(FlowRetention.BATCH))).thenReturn(0);
    when(repo.deleteTickerDaysOlderThan(any(), eq(FlowRetention.BATCH))).thenReturn(0);

    new FlowRetention(repo, FIXED).runOnce();

    ArgumentCaptor<OffsetDateTime> orders = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repo).deleteOrdersOlderThan(orders.capture(), eq(FlowRetention.BATCH));
    // 2026-10-31 minus 90 days = 2026-08-02
    assertThat(orders.getValue().toInstant()).isEqualTo(Instant.parse("2026-08-02T12:00:00Z"));

    ArgumentCaptor<LocalDate> days = ArgumentCaptor.forClass(LocalDate.class);
    verify(repo).deleteTickerDaysOlderThan(days.capture(), eq(FlowRetention.BATCH));
    // 2026-10-31 minus 730 days = 2024-10-31 (2 × 365, raw day subtraction)
    assertThat(days.getValue()).isEqualTo(LocalDate.of(2024, 10, 31));
  }

  @Test
  void keepsBatchingWhileFullBatchesComeBack_thenStops_andSumsBothSweeps() {
    FlowRepository repo = mock(FlowRepository.class);
    when(repo.deleteOrdersOlderThan(any(), eq(FlowRetention.BATCH)))
        .thenReturn(FlowRetention.BATCH, FlowRetention.BATCH, 7);
    when(repo.deleteTickerDaysOlderThan(any(), eq(FlowRetention.BATCH))).thenReturn(3);

    int removed = new FlowRetention(repo, FIXED).runOnce();

    assertThat(removed).isEqualTo(FlowRetention.BATCH * 2 + 7 + 3);
    verify(repo, times(3)).deleteOrdersOlderThan(any(), eq(FlowRetention.BATCH));
    verify(repo, times(1)).deleteTickerDaysOlderThan(any(), eq(FlowRetention.BATCH));
  }

  @Test
  void stopsAtThePerRunCapSoOneNightCannotRunAway() {
    FlowRepository repo = mock(FlowRepository.class);
    when(repo.deleteOrdersOlderThan(any(), eq(FlowRetention.BATCH)))
        .thenReturn(FlowRetention.BATCH);
    when(repo.deleteTickerDaysOlderThan(any(), eq(FlowRetention.BATCH))).thenReturn(0);

    new FlowRetention(repo, FIXED).runOnce();

    verify(repo, times(FlowRetention.MAX_BATCHES))
        .deleteOrdersOlderThan(any(), eq(FlowRetention.BATCH));
  }

  @Test
  void aFailedOrderSweepNeverEscapes_andTheTickerDaySweepStillRuns() {
    FlowRepository repo = mock(FlowRepository.class);
    when(repo.deleteOrdersOlderThan(any(), eq(FlowRetention.BATCH)))
        .thenThrow(new RuntimeException("db gone"));
    when(repo.deleteTickerDaysOlderThan(any(), eq(FlowRetention.BATCH))).thenReturn(5);

    int removed = new FlowRetention(repo, FIXED).runOnce();

    assertThat(removed).isEqualTo(5);
    verify(repo).deleteTickerDaysOlderThan(any(), eq(FlowRetention.BATCH));
  }
}
