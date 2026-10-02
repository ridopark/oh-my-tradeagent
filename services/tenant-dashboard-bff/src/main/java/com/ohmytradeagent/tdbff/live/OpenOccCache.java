package com.ohmytradeagent.tdbff.live;

import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-tenant cache of the open-position OCC set backing the 1s {@code /api/live/marks} poll.
 *
 * <p>The set comes from {@link PositionsReader#openPositions} (Temporal visibility + queries) —
 * never from the broker — and is read at most once per {@link #TTL} per tenant. Concurrent misses
 * share ONE in-flight read (single-flight), so N open tabs polling every second cost one Temporal
 * fan-out per 15s. A failed read is remembered for {@link #FAILURE_TTL} so an outage is not
 * hammered at 1Hz either; it surfaces as {@code ok=false} ("unknown"), never as "no positions".
 */
@Component
public class OpenOccCache {

  private static final Logger log = LoggerFactory.getLogger(OpenOccCache.class);
  static final Duration TTL = Duration.ofSeconds(15);
  static final Duration FAILURE_TTL = Duration.ofSeconds(5);

  /**
   * Bound on how long a caller waits for someone else's in-flight read. Short, so a slow Temporal
   * read cannot pin request threads under the 1Hz poll; a timed-out waiter answers "unknown" and
   * caches nothing — the loader's own result is what gets cached.
   */
  static final Duration WAIT = Duration.ofSeconds(2);

  private final PositionsReader reader;
  private final Clock clock;
  private final ConcurrentHashMap<String, CompletableFuture<Snapshot>> byTenant =
      new ConcurrentHashMap<>();

  @Autowired
  public OpenOccCache(PositionsReader reader) {
    this(reader, Clock.systemUTC());
  }

  OpenOccCache(PositionsReader reader, Clock clock) {
    this.reader = reader;
    this.clock = clock;
  }

  /**
   * @param contractSymbols distinct OCCs exactly as the portfolio rows carry them
   * @param ok false when the positions read failed (the set is then empty and means "unknown")
   */
  public record Snapshot(List<String> contractSymbols, boolean ok, Instant expiresAt) {}

  public Snapshot get(String tenantId) {
    CompletableFuture<Snapshot> mine = new CompletableFuture<>();
    CompletableFuture<Snapshot> current =
        byTenant.compute(tenantId, (k, old) -> old == null || isExpired(old) ? mine : old);
    if (current == mine) {
      load(tenantId, mine);
    }
    try {
      return current.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed();
    } catch (ExecutionException | TimeoutException e) {
      return failed();
    }
  }

  /**
   * Drops the tenant's cached OCC set so the next poll re-reads it — called after an operator
   * action that opens or closes a position. Removing the entry (rather than marking it) means an
   * in-flight read, which completes only its own future, cannot repopulate the cache with its stale
   * set.
   */
  public void invalidate(String tenantId) {
    byTenant.remove(tenantId);
  }

  private void load(String tenantId, CompletableFuture<Snapshot> into) {
    try {
      Set<String> symbols = new LinkedHashSet<>();
      for (OpenPosition p : reader.openPositions(tenantId)) {
        if (p.contractSymbol() != null && !p.contractSymbol().isBlank()) {
          symbols.add(p.contractSymbol());
        }
      }
      into.complete(new Snapshot(List.copyOf(symbols), true, clock.instant().plus(TTL)));
    } catch (RuntimeException e) {
      log.warn("open-position OCC read failed tenant={}: {}", tenantId, e.toString());
    } finally {
      // Always complete, so waiters never hang on a read that died with an Error.
      into.complete(failed());
    }
  }

  private Snapshot failed() {
    return new Snapshot(List.of(), false, clock.instant().plus(FAILURE_TTL));
  }

  private boolean isExpired(CompletableFuture<Snapshot> f) {
    return f.isDone() && !clock.instant().isBefore(f.join().expiresAt());
  }
}
