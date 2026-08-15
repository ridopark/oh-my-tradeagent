package com.ohmytradeagent.tdbff.optionschat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The failure that matters here is not failing to delete — it is deleting everything. Most of these
 * exist to pin that a misconfigured retention is inert rather than catastrophic.
 */
class OptionsChatRetentionTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);

  @ParameterizedTest
  @ValueSource(ints = {0, -1, -30})
  void aNonPositiveRetentionDisablesTheSweepRatherThanDeletingEverything(int days) {
    // A 0 would compute a cutoff of "now" and sweep the entire mirror on the first tick. Unset
    // property, typo, bad ConfigMap — all land here, and all must be inert.
    OptionsChatRepository repo = mock(OptionsChatRepository.class);

    int removed = new OptionsChatRetention(repo, days, FIXED).runOnce();

    assertThat(removed).isZero();
    verifyNoInteractions(repo);
  }

  @Test
  void deletesUsingACutoffOfExactlyRetentionDaysAgo() {
    OptionsChatRepository repo = mock(OptionsChatRepository.class);
    when(repo.deleteOlderThan(any(), anyInt())).thenReturn(0);

    new OptionsChatRetention(repo, 30, FIXED).runOnce();

    ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repo).deleteOlderThan(cutoff.capture(), anyInt());
    assertThat(cutoff.getValue().toInstant()).isEqualTo(Instant.parse("2026-07-16T12:00:00Z"));
  }

  @Test
  void keepsBatchingWhileFullBatchesComeBack_thenStops() {
    OptionsChatRepository repo = mock(OptionsChatRepository.class);
    when(repo.deleteOlderThan(any(), anyInt()))
        .thenReturn(OptionsChatRetention.BATCH, OptionsChatRetention.BATCH, 7);

    int removed = new OptionsChatRetention(repo, 30, FIXED).runOnce();

    assertThat(removed).isEqualTo(OptionsChatRetention.BATCH * 2 + 7);
    verify(repo, times(3)).deleteOlderThan(any(), anyInt());
  }

  @Test
  void stopsAtThePerRunCapSoOneNightCannotRunAway() {
    // A first run after months of accumulation must not turn into an unbounded delete loop on the
    // volume the trading databases share.
    OptionsChatRepository repo = mock(OptionsChatRepository.class);
    when(repo.deleteOlderThan(any(), anyInt())).thenReturn(OptionsChatRetention.BATCH);

    int removed = new OptionsChatRetention(repo, 30, FIXED).runOnce();

    verify(repo, times(OptionsChatRetention.MAX_BATCHES)).deleteOlderThan(any(), anyInt());
    assertThat(removed).isEqualTo(OptionsChatRetention.BATCH * OptionsChatRetention.MAX_BATCHES);
  }

  @Test
  void aFailedSweepNeverEscapes_andReportsWhatItManaged() {
    // Retention is housekeeping; it must not take the BFF down or poison the scheduler thread.
    OptionsChatRepository repo = mock(OptionsChatRepository.class);
    when(repo.deleteOlderThan(any(), anyInt()))
        .thenReturn(OptionsChatRetention.BATCH)
        .thenThrow(new RuntimeException("db went away"));

    int removed = new OptionsChatRetention(repo, 30, FIXED).runOnce();

    assertThat(removed).isEqualTo(OptionsChatRetention.BATCH);
  }

  @Test
  void anEmptyStoreDoesNothingQuietly() {
    OptionsChatRepository repo = mock(OptionsChatRepository.class);
    when(repo.deleteOlderThan(any(), anyInt())).thenReturn(0);

    assertThat(new OptionsChatRetention(repo, 30, FIXED).runOnce()).isZero();
    verify(repo, times(1)).deleteOlderThan(any(), anyInt());
    verify(repo, never()).ingest(anyLong(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "thirty", "30d", "1e3"})
  void aRetentionDaysThatIsNotANumberDisablesTheSweepInsteadOfRefusingToStart(String raw) {
    // Through the SPRING constructor, because that is the one that takes the raw property value.
    // The `:30` default only fires when the property is absent; a ConfigMap entry that is present
    // and blank resolves to "" and, bound straight to an int, aborts context refresh — turning a
    // typo into a BFF outage rather than a disabled sweep.
    OptionsChatRepository repo = mock(OptionsChatRepository.class);

    assertThat(new OptionsChatRetention(repo, raw).runOnce()).isZero();
    verify(repo, never()).deleteOlderThan(any(), anyInt());
  }

  @Test
  void aWellFormedRetentionDaysParsesToItsValue_soTheParseIsNotSwallowingGoodConfig() {
    // Asserted on the VALUE, not merely on "a sweep happened": going through runOnce() only proves
    // the result was positive, so a parse returning 1 or 9999 would pass just as happily. Padding
    // is included because a ConfigMap scalar routinely carries surrounding whitespace.
    assertThat(OptionsChatRetention.parseRetentionDays(" 7 ")).isEqualTo(7);
    assertThat(OptionsChatRetention.parseRetentionDays("30")).isEqualTo(30);
  }

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
  }
}
