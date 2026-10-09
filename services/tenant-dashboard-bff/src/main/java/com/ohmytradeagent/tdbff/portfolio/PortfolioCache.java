package com.ohmytradeagent.tdbff.portfolio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-tenant cache of {@link PortfolioService#portfolio} — the read behind {@code GET
 * /api/portfolio}, which costs a PositionSnapshotWorkflow + AccountSnapshotWorkflow round-trip per
 * broker_target (the tenant's Alpaca request budget, shared with live trading) plus a Temporal
 * position fan-out and the realized-P&L journal scans.
 *
 * <p>Read at most once per {@link #TTL} per tenant; concurrent misses share ONE in-flight read
 * (single-flight), so N viewers of /live cost one load per 10s rather than N.
 *
 * <ul>
 *   <li><b>Failures are never cached.</b> A load that throws is handed to the callers already
 *       waiting on it (the same 500 they would have got uncached), and the next request starts a
 *       fresh load.
 *   <li><b>Visibly degraded results are cached only {@link #DEGRADED_TTL}.</b> {@code
 *       PortfolioService} degrades a stalled section instead of failing, and publishes a degraded
 *       realized-P&L or equity figure as null; such a body is kept just long enough to absorb a
 *       burst of viewers (single-flight already stops them stacking loads) without pinning "—" on
 *       the page for the full TTL. Degraded positions ({@code open_positions_degraded}) count too.
 *       A degraded marks section is not distinguishable from a real empty one in the body, so it
 *       gets the normal TTL — at most 10s, under the 15s refresh.
 *   <li><b>{@link #invalidate} wins over an in-flight load.</b> It removes the tenant's entry; the
 *       in-flight load completes only its own future, which is no longer in the map, so its
 *       pre-invalidation result can never be served to a caller arriving after the invalidation.
 *       The future's identity is the generation.
 * </ul>
 */
@Component
public class PortfolioCache {

  static final Duration TTL = Duration.ofSeconds(10);
  static final Duration DEGRADED_TTL = Duration.ofSeconds(2);

  /**
   * Bound on how long a caller waits for someone else's in-flight load. The load itself is bounded
   * by {@code bff.portfolio.subread-timeout-seconds} (9s); past the dashboard's 12s abort a longer
   * wait is pointless, so 11s keeps a waiter inside the existing request budget.
   */
  static final Duration WAIT = Duration.ofSeconds(11);

  private final PortfolioService service;
  private final Clock clock;
  private final ConcurrentHashMap<String, CompletableFuture<Cached>> byTenant =
      new ConcurrentHashMap<>();

  @Autowired
  public PortfolioCache(PortfolioService service) {
    this(service, Clock.systemUTC());
  }

  PortfolioCache(PortfolioService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  private record Cached(Map<String, Object> body, Instant expiresAt) {}

  public Map<String, Object> portfolio(String tenantId) {
    CompletableFuture<Cached> mine = new CompletableFuture<>();
    CompletableFuture<Cached> current =
        byTenant.compute(tenantId, (k, old) -> old == null || isExpired(old) ? mine : old);
    if (current == mine) {
      load(tenantId, mine);
    }
    try {
      return current.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for portfolio read", e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("portfolio read still in flight after " + WAIT, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re; // the loader's own failure, unchanged — same handling as the uncached path
      }
      if (e.getCause() instanceof Error err) {
        throw err;
      }
      throw new IllegalStateException("portfolio read failed", e.getCause());
    }
  }

  /**
   * Drops the tenant's cached portfolio so the next request reads fresh — called after an operator
   * action succeeds. Never throws, so it cannot change the action's outcome.
   */
  public void invalidate(String tenantId) {
    byTenant.remove(tenantId);
  }

  private void load(String tenantId, CompletableFuture<Cached> into) {
    try {
      Map<String, Object> body = service.portfolio(tenantId);
      Duration ttl = isDegraded(body) ? DEGRADED_TTL : TTL;
      into.complete(new Cached(body, clock.instant().plus(ttl)));
    } catch (Throwable t) {
      // Completed exceptionally == expired (see isExpired), so a failure is never served twice.
      into.completeExceptionally(t);
    }
  }

  private static boolean isDegraded(Map<String, Object> body) {
    if (body.get("realized_pnl_today") == null
        || Boolean.TRUE.equals(body.get("open_positions_degraded"))) {
      return true;
    }
    if (body.get("account_equity") instanceof List<?> rows) {
      for (Object row : rows) {
        if (row instanceof Map<?, ?> m && m.get("equity") == null) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isExpired(CompletableFuture<Cached> f) {
    return f.isDone()
        && (f.isCompletedExceptionally() || !clock.instant().isBefore(f.join().expiresAt()));
  }
}
