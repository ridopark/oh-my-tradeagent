package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * N /live viewers must cost one portfolio load (broker snapshots + Temporal fan-out) per 10s, not
 * N; and an operator action's invalidation must never be undone by a load that started before it.
 */
class PortfolioCacheTest {

  /** A Clock whose instant the test advances by hand. */
  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-10-01T14:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static Map<String, Object> body(String tag) {
    return Map.of(
        "tag",
        tag,
        "realized_pnl_today",
        BigDecimal.ONE,
        "account_equity",
        List.of(Map.of("equity", BigDecimal.TEN)));
  }

  private final PortfolioService service = mock(PortfolioService.class);
  private final MutableClock clock = new MutableClock();
  private final PortfolioCache cache = new PortfolioCache(service, clock);

  @Test
  void twoCallsWithin10s_loadOnce_afterTtl_loadAgain() {
    when(service.portfolio("acme")).thenReturn(body("a"));

    cache.portfolio("acme");
    clock.advance(Duration.ofSeconds(9));
    assertThat(cache.portfolio("acme")).containsEntry("tag", "a");
    verify(service, times(1)).portfolio("acme");

    clock.advance(Duration.ofSeconds(1)); // 10s after the first load: expired
    cache.portfolio("acme");
    verify(service, times(2)).portfolio("acme");
  }

  @Test
  void tenConcurrentCallers_shareOneLoad() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger loads = new AtomicInteger();
    when(service.portfolio("acme"))
        .thenAnswer(
            inv -> {
              loads.incrementAndGet();
              release.await(5, TimeUnit.SECONDS);
              return body("a");
            });

    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      List<Future<Map<String, Object>>> calls = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        calls.add(pool.submit(() -> cache.portfolio("acme")));
      }
      Thread.sleep(200); // let all ten reach the in-flight load
      release.countDown();
      for (Future<Map<String, Object>> f : calls) {
        assertThat(f.get(5, TimeUnit.SECONDS)).containsEntry("tag", "a");
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(loads.get()).isEqualTo(1);
  }

  @Test
  void invalidate_thenCall_loadsFresh() {
    when(service.portfolio("acme")).thenReturn(body("old"), body("new"));

    cache.portfolio("acme");
    cache.invalidate("acme");

    assertThat(cache.portfolio("acme")).containsEntry("tag", "new");
    verify(service, times(2)).portfolio("acme");
  }

  @Test
  void invalidateDuringInFlightLoad_staleResultIsNeverServedAfterwards() throws Exception {
    CountDownLatch staleStarted = new CountDownLatch(1);
    CountDownLatch releaseStale = new CountDownLatch(1);
    AtomicInteger n = new AtomicInteger();
    when(service.portfolio("acme"))
        .thenAnswer(
            inv -> {
              if (n.incrementAndGet() == 1) {
                staleStarted.countDown();
                releaseStale.await(5, TimeUnit.SECONDS);
                return body("stale");
              }
              return body("fresh");
            });

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Map<String, Object>> preInvalidation = pool.submit(() -> cache.portfolio("acme"));
      assertThat(staleStarted.await(5, TimeUnit.SECONDS)).isTrue();

      cache.invalidate("acme"); // the operator's force-exit succeeded mid-load

      // A caller after the invalidation does not join the stale in-flight load.
      assertThat(cache.portfolio("acme")).containsEntry("tag", "fresh");

      releaseStale.countDown();
      assertThat(preInvalidation.get(5, TimeUnit.SECONDS)).containsEntry("tag", "stale");
    } finally {
      pool.shutdownNow();
    }

    // The stale load finishing did not overwrite the cache: later callers still get "fresh".
    assertThat(cache.portfolio("acme")).containsEntry("tag", "fresh");
    verify(service, times(2)).portfolio("acme");
  }

  @Test
  void failedLoad_isNotCached() {
    when(service.portfolio("acme"))
        .thenThrow(new IllegalStateException("db down"))
        .thenReturn(body("ok"));

    assertThatThrownBy(() -> cache.portfolio("acme"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("db down");
    assertThat(cache.portfolio("acme")).containsEntry("tag", "ok");
    verify(service, times(2)).portfolio("acme");
  }

  @Test
  void degradedResult_isCachedOnlyBriefly() {
    Map<String, Object> degraded = new java.util.HashMap<>(body("degraded"));
    degraded.put("realized_pnl_today", null);
    when(service.portfolio("acme")).thenReturn(degraded, body("ok"));

    cache.portfolio("acme");
    clock.advance(Duration.ofSeconds(1));
    assertThat(cache.portfolio("acme")).containsEntry("tag", "degraded");

    clock.advance(Duration.ofSeconds(1)); // DEGRADED_TTL (2s) elapsed, well inside TTL
    assertThat(cache.portfolio("acme")).containsEntry("tag", "ok");
  }

  @Test
  void degradedPositions_areCachedOnlyBriefly() {
    Map<String, Object> degraded = new java.util.HashMap<>(body("degraded"));
    degraded.put("open_positions_degraded", true);
    when(service.portfolio("acme")).thenReturn(degraded, body("ok"));

    cache.portfolio("acme");
    clock.advance(Duration.ofSeconds(2)); // DEGRADED_TTL elapsed, well inside TTL
    assertThat(cache.portfolio("acme")).containsEntry("tag", "ok");
  }

  @Test
  void tenantsAreCachedSeparately() {
    when(service.portfolio("acme")).thenReturn(body("acme"));
    when(service.portfolio("beta")).thenReturn(body("beta"));

    assertThat(cache.portfolio("acme")).containsEntry("tag", "acme");
    assertThat(cache.portfolio("beta")).containsEntry("tag", "beta");
    cache.invalidate("beta");
    cache.portfolio("acme");

    verify(service, times(1)).portfolio("acme");
  }
}
