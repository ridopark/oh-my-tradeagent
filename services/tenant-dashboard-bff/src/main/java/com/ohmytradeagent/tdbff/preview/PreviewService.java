package com.ohmytradeagent.tdbff.preview;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient.MarksResponse;
import com.ohmytradeagent.tdbff.orders.OrdersReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.preview.PreviewQuarantine.Buy;
import com.ohmytradeagent.tdbff.preview.PreviewQuarantine.OpenPos;
import com.ohmytradeagent.tdbff.trades.TradesReader;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads behind {@code /api/preview/*}, the public demo's only data source
 * (PLAN-2026-11-26-public-staging-preview.md). Each method runs an existing tenant read, computes
 * the entry-delay quarantine, and returns the {@link PreviewProjection} of the result.
 *
 * <p>Fail-closed: if any input to the quarantine cannot be read, the exception propagates and the
 * endpoint answers 5xx. Nothing is ever served unfiltered; the demo keeps its last good snapshot.
 *
 * <p>Built to be safe in the hands of whoever holds the preview token (assume the demo pod is
 * compromised): at most {@link #MAX_CONCURRENT} requests run at once — the rest are refused, not
 * queued — the broker-backed portfolio read runs at most once per {@link #PORTFOLIO_TTL}, and a
 * tenant with any non-paper broker target is refused outright.
 */
@Component
class PreviewService {

  private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

  static final Duration ENTRY_DELAY = Duration.ofMinutes(15);
  static final Duration HISTORY_WINDOW = Duration.ofDays(30);
  // The open-position list costs a Visibility query plus two Temporal queries per position, so it
  // is reused across the 3-5s polls (same TTL as OpenOccCache). Stale is safe here: a brand-new
  // entry is hidden by its journalled BUY order, which is read fresh on every call.
  private static final Duration OPEN_TTL = Duration.ofSeconds(15);
  private static final int READ_LIMIT = 500;
  // The portfolio read starts broker workflows. PortfolioCache alone would allow one every 10s for
  // as long as someone keeps asking; the demo never needs it more often than this.
  static final Duration PORTFOLIO_TTL = Duration.ofSeconds(30);
  // One poller makes sequential calls. Two leaves room for an overlap without letting a flood take
  // the BFF's request threads or its 4-connection DB pools away from the operators.
  static final int MAX_CONCURRENT = 2;

  /** More than {@link #MAX_CONCURRENT} preview requests are in flight. */
  static final class BusyException extends RuntimeException {}

  /** The tenant has a broker target that is not a paper account; it is never served. */
  static final class NotPaperException extends RuntimeException {}

  private record Body(Map<String, Object> body, Instant loadedAt) {}

  private static final long CIRCUIT_BREAKER_SECONDS = 900;

  // How old a cached open-position list may be and still be trusted to REVEAL something.
  // Also the most often an add-on can force a re-read: during the 15 minutes after one, every
  // poll would otherwise fan out to Temporal on the worker that runs the live tenants.
  private static final Duration ANCHOR_MAX_AGE = Duration.ofSeconds(5);
  // Outside the regular session nothing moves; the broker-backed read is not worth 2 workflows
  // every 30s around the clock.
  static final Duration PORTFOLIO_TTL_CLOSED = Duration.ofMinutes(5);
  private static final java.time.ZoneId MARKET_TZ = java.time.ZoneId.of("America/New_York");
  // A buy still working after this long is abandoned, not resting: it must not hide its contract
  // for as long as the row stays in the journal.
  private static final Duration WORKING_BUY_MAX_AGE = Duration.ofHours(24);
  private static final Set<String> TERMINAL_ORDER_STATES =
      Set.of("FILLED", "CANCELLED", "CANCELED", "ERRORED", "EXPIRED", "REJECTED");
  // The kill-switch state is a Temporal query on the worker that runs the live tenants' workflows.
  static final Duration STATUS_TTL = Duration.ofSeconds(15);
  // After a failed or refused load, do not try again for this long: a flood against a failing
  // dependency must not re-run the slow read (or re-log it) on every request.
  static final Duration FAILURE_HOLD = Duration.ofSeconds(5);

  /** A recent load failed; it is not retried until {@link #FAILURE_HOLD} has passed. */
  static final class UnavailableException extends RuntimeException {}

  private record Failure(RuntimeException cause, Instant at) {}

  private record Open(
      List<OpenPosition> positions, Map<String, Instant> entryAt, Instant loadedAt) {}

  private final PositionsReader positions;
  private final PreviewEntryTimes entryTimes;
  private final OrdersReader ordersReader;
  private final TradesReader tradesReader;
  private final PortfolioCache portfolioCache;
  private final MarketDataMarksClient marksClient;
  private final WorkflowClient workflowClient;
  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader strategyRegistry;
  private final Map<String, Open> openByTenant = new ConcurrentHashMap<>();
  private final Map<String, Body> portfolioByTenant = new ConcurrentHashMap<>();
  private final Map<String, Body> statusByTenant = new ConcurrentHashMap<>();
  private final Map<String, Failure> failureByTenant = new ConcurrentHashMap<>();
  private final Semaphore permits = new Semaphore(MAX_CONCURRENT);
  // Not a constructor parameter: a second constructor trips Spring's ambiguous-constructor abort.
  Clock clock = Clock.systemUTC();

  PreviewService(
      PositionsReader positions,
      PreviewEntryTimes entryTimes,
      OrdersReader ordersReader,
      TradesReader tradesReader,
      PortfolioCache portfolioCache,
      MarketDataMarksClient marksClient,
      WorkflowClient workflowClient,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry) {
    this.positions = positions;
    this.entryTimes = entryTimes;
    this.ordersReader = ordersReader;
    this.tradesReader = tradesReader;
    this.portfolioCache = portfolioCache;
    this.marksClient = marksClient;
    this.workflowClient = workflowClient;
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
  }

  /** Runs one preview read under the concurrency cap, for a paper tenant only. */
  private <T> T guarded(String tenant, Supplier<T> read) {
    if (!permits.tryAcquire()) {
      throw new BusyException();
    }
    try {
      open(tenant); // also the paper-only check; cached, so this is free on the hot path
      return read.get();
    } finally {
      permits.release();
    }
  }

  Map<String, Object> portfolio(String tenant) {
    return guarded(
        tenant,
        () -> {
          // Data FIRST, orders LAST (in every read below): a buy that lands while the data loads
          // is then always in the orders this computes the delay from.
          Map<String, Object> body = portfolioBody(tenant);
          return PreviewProjection.portfolio(
              body, hiddenFrom(tenant, ordersReader.orders(tenant, READ_LIMIT)));
        });
  }

  /** Regular US session, Monday to Friday 09:30-16:00 New York. Holidays count as open. */
  private static boolean marketOpen(Instant now) {
    java.time.ZonedDateTime t = now.atZone(MARKET_TZ);
    java.time.DayOfWeek d = t.getDayOfWeek();
    if (d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY) {
      return false;
    }
    java.time.LocalTime time = t.toLocalTime();
    return !time.isBefore(java.time.LocalTime.of(9, 30))
        && time.isBefore(java.time.LocalTime.of(16, 0));
  }

  /** The un-projected portfolio, reused for {@link #PORTFOLIO_TTL}. The delay is applied fresh. */
  private Map<String, Object> portfolioBody(String tenant) {
    Instant now = clock.instant();
    Body cached = portfolioByTenant.get(tenant);
    Duration ttl = marketOpen(now) ? PORTFOLIO_TTL : PORTFOLIO_TTL_CLOSED;
    if (cached != null && now.isBefore(cached.loadedAt().plus(ttl))) {
      return cached.body();
    }
    Map<String, Object> body = portfolioCache.portfolio(tenant);
    portfolioByTenant.put(tenant, new Body(body, now));
    return body;
  }

  List<Map<String, Object>> fills(String tenant) {
    return guarded(
        tenant,
        () -> {
          String since =
              OffsetDateTime.ofInstant(clock.instant().minus(HISTORY_WINDOW), ZoneOffset.UTC)
                  .toString();
          List<Map<String, Object>> rows =
              tradesReader.trades(
                  tenant, List.copyOf(PreviewProjection.SHOWN_STRATEGIES), since, READ_LIMIT);
          Set<String> signalIds = new LinkedHashSet<>();
          for (Map<String, Object> r : rows) {
            if (r.get("correlation_id") instanceof String id && !id.isBlank()) {
              signalIds.add(id);
            }
          }
          List<PreviewEntryTimes.EntryFill> entries = entryFills(tenant, signalIds);
          Map<String, Instant> hidden = hiddenFrom(tenant, ordersReader.orders(tenant, READ_LIMIT));
          return PreviewProjection.fills(
              rows, hidden, PreviewProjection.entryBasis(entries, hidden));
        });
  }

  /** Fail-soft: without entry fills a row simply carries no basis; the fills still serve. */
  private List<PreviewEntryTimes.EntryFill> entryFills(String tenant, Set<String> signalIds) {
    try {
      return entryTimes.entryFills(tenant, signalIds);
    } catch (RuntimeException e) {
      log.warn("preview entry-fill read failed tenant={}: {}", tenant, e.toString());
      return List.of();
    }
  }

  List<Map<String, Object>> orders(String tenant) {
    return guarded(
        tenant,
        () -> {
          List<Map<String, Object>> rows = ordersReader.orders(tenant, READ_LIMIT);
          return PreviewProjection.orders(
              rows, hiddenFrom(tenant, rows), clock.instant().minus(HISTORY_WINDOW));
        });
  }

  /** Live quotes for the VISIBLE open contracts only; never starts a Temporal workflow. */
  Map<String, Object> marks(String tenant) {
    return guarded(tenant, () -> marksUnguarded(tenant));
  }

  private Map<String, Object> marksUnguarded(String tenant) {
    List<OpenPosition> listed = open(tenant).positions(); // data first, orders last
    Map<String, Instant> hidden = hiddenFrom(tenant, ordersReader.orders(tenant, READ_LIMIT));
    // compact OCC -> the row-form symbol(s) the positions table shows.
    Map<String, Set<String>> rowForms = new LinkedHashMap<>();
    for (OpenPosition p : listed) {
      String compact = PreviewProjection.compact(p.contractSymbol());
      if (compact != null
          && PreviewProjection.SHOWN_STRATEGIES.contains(p.strategyId())
          && !hidden.containsKey(compact)) {
        rowForms.computeIfAbsent(compact, k -> new LinkedHashSet<>()).add(p.contractSymbol());
      }
    }
    MarksResponse resp =
        rowForms.isEmpty() ? null : marksClient.demoMarks(new ArrayList<>(rowForms.keySet()));
    List<Map<String, Object>> marks = new ArrayList<>();
    if (resp != null) {
      for (Map<String, Object> mark : resp.marks()) {
        Set<String> forms = rowForms.get(PreviewProjection.compact(mark.get("occ")));
        if (forms == null) {
          continue; // not a contract the demo may show
        }
        for (String form : forms) {
          marks.add(PreviewProjection.mark(mark, form));
        }
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("now", resp != null && resp.now() != null ? resp.now() : clock.instant().toString());
    body.put("market_data_reachable", rowForms.isEmpty() || resp != null);
    body.put("marks", marks);
    return body;
  }

  /** The account kill switch: state and times only — the reason is free text and never sent. */
  Map<String, Object> status(String tenant) {
    return guarded(tenant, () -> statusUnguarded(tenant));
  }

  private Map<String, Object> statusUnguarded(String tenant) {
    Instant now = clock.instant();
    Body cached = statusByTenant.get(tenant);
    if (cached != null && now.isBefore(cached.loadedAt().plus(STATUS_TTL))) {
      return cached.body();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    try {
      KillSwitchState s =
          workflowClient
              .newUntypedWorkflowStub(WorkflowIds.accountKillswitch(tenant))
              .query("account_killswitch_state", KillSwitchState.class);
      boolean tripped = Boolean.TRUE.equals(s.getTripped());
      OffsetDateTime trippedAt = s.getTrippedAt();
      body.put("tripped", tripped);
      body.put("trippedAt", trippedAt == null ? null : trippedAt.toString());
      body.put(
          "resettableAt",
          tripped && trippedAt != null
              ? trippedAt.plusSeconds(CIRCUIT_BREAKER_SECONDS).toString()
              : null);
    } catch (WorkflowNotFoundException e) {
      body.put("tripped", null);
    }
    statusByTenant.put(tenant, new Body(body, now));
    return body;
  }

  private Map<String, Instant> hiddenFrom(String tenant, List<Map<String, Object>> orderRows) {
    Instant now = clock.instant();
    List<Buy> buys = new ArrayList<>();
    for (Map<String, Object> o : orderRows) {
      // Anything that is not plainly a SELL counts as an entry: an unfamiliar side value must
      // hide its contract, never reveal it.
      // Only rows the demo could publish need hiding: a hidden strategy's buy must not make shown
      // rows vanish (that would signal its activity).
      if (PreviewProjection.SHOWN_STRATEGIES.contains(String.valueOf(o.get("strategy_id")))
          && !"SELL".equalsIgnoreCase(String.valueOf(o.get("side")))) {
        Instant recorded = PreviewProjection.instant(o.get("recorded_at"));
        Instant active = recorded;
        Instant filled = PreviewProjection.instant(o.get("filled_at"));
        if (filled != null && (active == null || filled.isAfter(active))) {
          active = filled;
        }
        // Still working (anything not plainly finished): the entry is happening NOW, however long
        // ago it was journalled — unless it is so old it was simply abandoned.
        boolean working =
            !TERMINAL_ORDER_STATES.contains(
                String.valueOf(o.get("state")).toUpperCase(java.util.Locale.ROOT));
        if (working && recorded != null && recorded.isAfter(now.minus(WORKING_BUY_MAX_AGE))) {
          active = now;
        }
        buys.add(new Buy((String) o.get("option_symbol"), recorded, active));
      }
    }
    Open cached = openByTenant.get(tenant);
    Open open = open(tenant);
    Map<String, Instant> hidden =
        PreviewQuarantine.hiddenFrom(now, ENTRY_DELAY, openPos(open), buys);
    // An old open position un-hides a fresh buy ("adding to a visible position"). Never grant that
    // on the strength of a stale cache: the position may have closed since. Re-read, then decide.
    boolean anchorUsed =
        !hidden
            .keySet()
            .containsAll(PreviewQuarantine.hiddenFrom(now, ENTRY_DELAY, List.of(), buys).keySet());
    if (anchorUsed && open == cached && open.loadedAt().isBefore(now.minus(ANCHOR_MAX_AGE))) {
      openByTenant.remove(tenant);
      hidden = PreviewQuarantine.hiddenFrom(now, ENTRY_DELAY, openPos(open(tenant)), buys);
    }
    return hidden;
  }

  /** Only positions the demo shows take part: a hidden strategy's position must not anchor. */
  private static List<OpenPos> openPos(Open open) {
    List<OpenPos> openPos = new ArrayList<>();
    for (OpenPosition p : open.positions()) {
      if (!PreviewProjection.SHOWN_STRATEGIES.contains(p.strategyId())) {
        continue;
      }
      String signalId = WorkflowIds.entrySignalIdFromPosition(p.workflowId());
      openPos.add(
          new OpenPos(
              p.contractSymbol(),
              signalId == null
                  ? null
                  : open.entryAt().get(PreviewEntryTimes.key(signalId, p.contractSymbol()))));
    }
    return openPos;
  }

  private synchronized Open open(String tenant) {
    Instant now = clock.instant();
    Open cached = openByTenant.get(tenant);
    if (cached != null && now.isBefore(cached.loadedAt().plus(OPEN_TTL))) {
      return cached;
    }
    Failure failed = failureByTenant.get(tenant);
    if (failed != null && now.isBefore(failed.at().plus(FAILURE_HOLD))) {
      throw failed.cause() instanceof NotPaperException
          ? failed.cause()
          : new UnavailableException();
    }
    try {
      Open fresh = load(tenant, now);
      failureByTenant.remove(tenant);
      openByTenant.put(tenant, fresh);
      return fresh;
    } catch (RuntimeException e) {
      // Logged here, once per hold-off, not once per request.
      if (e instanceof NotPaperException) {
        log.warn("preview refused: tenant={} has a non-paper or no broker target", tenant);
      } else {
        log.warn("preview open-position load failed tenant={}", tenant, e);
      }
      openByTenant.remove(tenant);
      failureByTenant.put(tenant, new Failure(e, now));
      throw e;
    }
  }

  private Open load(String tenant, Instant now) {
    List<String> strategyIds = strategyResolver.strategyIdsForTenant(tenant);
    if (strategyIds.isEmpty()) {
      // Nothing proves this tenant is paper (e.g. a torn-down tenant whose fills remain).
      throw new NotPaperException();
    }
    for (String strategyId : strategyIds) {
      String target = strategyRegistry.brokerTarget(tenant, strategyId);
      if (target == null || !target.endsWith("-paper")) {
        throw new NotPaperException();
      }
    }
    List<OpenPosition> list = positions.openPositions(tenant);
    Set<String> signalIds = new LinkedHashSet<>();
    for (OpenPosition p : list) {
      String signalId = WorkflowIds.entrySignalIdFromPosition(p.workflowId());
      if (signalId != null) {
        signalIds.add(signalId);
      }
    }
    Open fresh = new Open(list, entryTimes.entryAt(tenant, signalIds), now);
    return fresh;
  }
}
