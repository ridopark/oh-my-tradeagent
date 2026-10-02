package com.ohmytradeagent.tdbff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The 1s marks poll must not turn into a 1s Temporal fan-out: the tenant's OCC set is read at most
 * once per 15s per tenant, concurrent misses share one read, and a failed read is remembered
 * briefly so an outage is not hammered either.
 */
class OpenOccCacheTest {

  private static final String OCC_A = "SPY   260519C00737000";
  private static final String OCC_B = "DRAM  270319C00100000";

  /** A Clock whose instant the test advances by hand. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-01T14:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static OpenPosition pos(String wf, String occ) {
    return new OpenPosition(wf, "s1", occ, 1, new BigDecimal("1.00"), new BigDecimal("100"));
  }

  @Test
  void twoCallsWithin15s_readPositionsOnce_thirdAfter15s_readsAgain() {
    PositionsReader reader = mock(PositionsReader.class);
    when(reader.openPositions("acme")).thenReturn(List.of(pos("wf1", OCC_A)));
    MutableClock clock = new MutableClock();
    OpenOccCache cache = new OpenOccCache(reader, clock);

    OpenOccCache.Snapshot first = cache.get("acme");
    clock.advance(Duration.ofSeconds(14));
    OpenOccCache.Snapshot second = cache.get("acme");

    verify(reader, times(1)).openPositions("acme");
    assertThat(first.ok()).isTrue();
    assertThat(first.contractSymbols()).containsExactly(OCC_A);
    assertThat(second.contractSymbols()).containsExactly(OCC_A);

    clock.advance(Duration.ofSeconds(2)); // 16s after the first read
    cache.get("acme");
    verify(reader, times(2)).openPositions("acme");
  }

  @Test
  void contractSymbolsAreDistinctInRowForm() {
    PositionsReader reader = mock(PositionsReader.class);
    // Sibling workflow rows sharing one OCC (#832) yield ONE symbol, kept in the row's own form.
    when(reader.openPositions("acme"))
        .thenReturn(List.of(pos("wf1", OCC_A), pos("wf2", OCC_A), pos("wf3", OCC_B)));
    OpenOccCache cache = new OpenOccCache(reader, new MutableClock());

    assertThat(cache.get("acme").contractSymbols()).containsExactly(OCC_A, OCC_B);
  }

  @Test
  void cacheIsPerTenant() {
    PositionsReader reader = mock(PositionsReader.class);
    when(reader.openPositions("acme")).thenReturn(List.of(pos("wf1", OCC_A)));
    when(reader.openPositions("other")).thenReturn(List.of(pos("wf2", OCC_B)));
    OpenOccCache cache = new OpenOccCache(reader, new MutableClock());

    assertThat(cache.get("acme").contractSymbols()).containsExactly(OCC_A);
    assertThat(cache.get("other").contractSymbols()).containsExactly(OCC_B);
  }

  @Test
  void failedRead_isNotOk_andIsCachedForAbout5s() {
    PositionsReader reader = mock(PositionsReader.class);
    when(reader.openPositions("acme")).thenThrow(new RuntimeException("temporal down"));
    MutableClock clock = new MutableClock();
    OpenOccCache cache = new OpenOccCache(reader, clock);

    OpenOccCache.Snapshot s = cache.get("acme");
    assertThat(s.ok()).isFalse();
    assertThat(s.contractSymbols()).isEmpty();

    clock.advance(Duration.ofSeconds(4));
    cache.get("acme");
    verify(reader, times(1)).openPositions("acme");

    clock.advance(Duration.ofSeconds(2)); // 6s after the failure
    cache.get("acme");
    verify(reader, times(2)).openPositions("acme");
  }

  @Test
  void concurrentMisses_shareOneRead_singleFlight() throws Exception {
    PositionsReader reader = mock(PositionsReader.class);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(reader.openPositions("acme"))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              release.await(5, TimeUnit.SECONDS);
              return List.of(pos("wf1", OCC_A));
            });
    OpenOccCache cache = new OpenOccCache(reader, new MutableClock());

    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<OpenOccCache.Snapshot>> futures =
          List.of(
              pool.submit(() -> cache.get("acme")),
              pool.submit(() -> cache.get("acme")),
              pool.submit(() -> cache.get("acme")),
              pool.submit(() -> cache.get("acme")));
      Thread.sleep(200); // let all four reach the cache while the first read is in flight
      release.countDown();
      for (Future<OpenOccCache.Snapshot> f : futures) {
        assertThat(f.get(5, TimeUnit.SECONDS).contractSymbols()).containsExactly(OCC_A);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void waiterOnInFlightRead_givesUpAfter2s_asUnknown_withoutCachingAFailure() throws Exception {
    PositionsReader reader = mock(PositionsReader.class);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    when(reader.openPositions("acme"))
        .thenAnswer(
            inv -> {
              started.countDown();
              release.await(10, TimeUnit.SECONDS);
              return List.of(pos("wf1", OCC_A));
            });
    OpenOccCache cache = new OpenOccCache(reader, new MutableClock());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<OpenOccCache.Snapshot> loader = pool.submit(() -> cache.get("acme"));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      long t0 = System.nanoTime();
      OpenOccCache.Snapshot waited = cache.get("acme");
      long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

      assertThat(waitedMs).isBetween(1_900L, 3_000L);
      assertThat(waited.ok()).isFalse(); // -> occs_status "unknown"

      // The loader's eventual success is what gets cached, not the waiter's timeout.
      release.countDown();
      assertThat(loader.get(5, TimeUnit.SECONDS).ok()).isTrue();
      assertThat(cache.get("acme").contractSymbols()).containsExactly(OCC_A);
      verify(reader, times(1)).openPositions("acme");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void invalidate_forcesAFreshReadOnTheNextPoll() {
    PositionsReader reader = mock(PositionsReader.class);
    when(reader.openPositions("acme"))
        .thenReturn(List.of(pos("wf1", OCC_A)), List.of(pos("wf1", OCC_A), pos("wf2", OCC_B)));
    OpenOccCache cache = new OpenOccCache(reader, new MutableClock());

    cache.get("acme");
    cache.invalidate("acme");

    assertThat(cache.get("acme").contractSymbols()).containsExactly(OCC_A, OCC_B);
    verify(reader, times(2)).openPositions("acme");
  }
}
