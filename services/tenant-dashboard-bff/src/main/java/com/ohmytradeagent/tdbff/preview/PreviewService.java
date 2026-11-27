package com.ohmytradeagent.tdbff.preview;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient.MarksResponse;
import com.ohmytradeagent.tdbff.orders.OrdersReader;
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
import org.springframework.stereotype.Component;

/**
 * Reads behind {@code /api/preview/*}, the public demo's only data source
 * (PLAN-2026-11-26-public-staging-preview.md). Each method runs an existing tenant read, computes
 * the entry-delay quarantine, and returns the {@link PreviewProjection} of the result.
 *
 * <p>Fail-closed: if any input to the quarantine cannot be read, the exception propagates and the
 * endpoint answers 5xx. Nothing is ever served unfiltered; the demo keeps its last good snapshot.
 */
@Component
class PreviewService {

  static final Duration ENTRY_DELAY = Duration.ofMinutes(15);
  static final Duration HISTORY_WINDOW = Duration.ofDays(30);
  // The open-position list costs a Visibility query plus two Temporal queries per position, so it
  // is reused across the 3-5s polls (same TTL as OpenOccCache). Stale is safe here: a brand-new
  // entry is hidden by its journalled BUY order, which is read fresh on every call.
  private static final Duration OPEN_TTL = Duration.ofSeconds(15);
  private static final int READ_LIMIT = 500;
  private static final long CIRCUIT_BREAKER_SECONDS = 900;

  private record Open(
      List<OpenPosition> positions, Map<String, Instant> entryAt, Instant expires) {}

  private final PositionsReader positions;
  private final PreviewEntryTimes entryTimes;
  private final OrdersReader ordersReader;
  private final TradesReader tradesReader;
  private final PortfolioCache portfolioCache;
  private final MarketDataMarksClient marksClient;
  private final WorkflowClient workflowClient;
  private final Map<String, Open> openByTenant = new ConcurrentHashMap<>();
  // Not a constructor parameter: a second constructor trips Spring's ambiguous-constructor abort.
  Clock clock = Clock.systemUTC();

  PreviewService(
      PositionsReader positions,
      PreviewEntryTimes entryTimes,
      OrdersReader ordersReader,
      TradesReader tradesReader,
      PortfolioCache portfolioCache,
      MarketDataMarksClient marksClient,
      WorkflowClient workflowClient) {
    this.positions = positions;
    this.entryTimes = entryTimes;
    this.ordersReader = ordersReader;
    this.tradesReader = tradesReader;
    this.portfolioCache = portfolioCache;
    this.marksClient = marksClient;
    this.workflowClient = workflowClient;
  }

  Map<String, Object> portfolio(String tenant) {
    Map<String, Instant> hidden = hiddenFrom(tenant, ordersReader.orders(tenant, READ_LIMIT));
    return PreviewProjection.portfolio(portfolioCache.portfolio(tenant), hidden);
  }

  List<Map<String, Object>> fills(String tenant) {
    Map<String, Instant> hidden = hiddenFrom(tenant, ordersReader.orders(tenant, READ_LIMIT));
    String since =
        OffsetDateTime.ofInstant(clock.instant().minus(HISTORY_WINDOW), ZoneOffset.UTC).toString();
    return PreviewProjection.fills(
        tradesReader.trades(
            tenant, List.copyOf(PreviewProjection.SHOWN_STRATEGIES), since, READ_LIMIT),
        hidden);
  }

  List<Map<String, Object>> orders(String tenant) {
    List<Map<String, Object>> rows = ordersReader.orders(tenant, READ_LIMIT);
    return PreviewProjection.orders(
        rows, hiddenFrom(tenant, rows), clock.instant().minus(HISTORY_WINDOW));
  }

  /** Live quotes for the VISIBLE open contracts only; never starts a Temporal workflow. */
  Map<String, Object> marks(String tenant) {
    Map<String, Instant> hidden = hiddenFrom(tenant, ordersReader.orders(tenant, READ_LIMIT));
    // compact OCC -> the row-form symbol(s) the positions table shows.
    Map<String, Set<String>> rowForms = new LinkedHashMap<>();
    for (OpenPosition p : open(tenant).positions()) {
      String compact = PreviewProjection.compact(p.contractSymbol());
      if (compact != null
          && PreviewProjection.SHOWN_STRATEGIES.contains(p.strategyId())
          && !hidden.containsKey(compact)) {
        rowForms.computeIfAbsent(compact, k -> new LinkedHashSet<>()).add(p.contractSymbol());
      }
    }
    MarksResponse resp =
        rowForms.isEmpty() ? null : marksClient.marks(new ArrayList<>(rowForms.keySet()));
    List<Map<String, Object>> marks = new ArrayList<>();
    if (resp != null) {
      for (Map<String, Object> mark : resp.marks()) {
        Set<String> forms = rowForms.get(PreviewProjection.compact(mark.get("occ")));
        if (forms == null) {
          continue; // not a contract the demo may show
        }
        for (String form : forms) {
          Map<String, Object> out = new LinkedHashMap<>();
          for (String f : PreviewProjection.MARK_FIELDS) {
            if (mark.containsKey(f)) {
              out.put(f, mark.get(f));
            }
          }
          out.put("occ", form);
          marks.add(out);
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
    return body;
  }

  private Map<String, Instant> hiddenFrom(String tenant, List<Map<String, Object>> orderRows) {
    Open open = open(tenant);
    List<OpenPos> openPos = new ArrayList<>();
    for (OpenPosition p : open.positions()) {
      String signalId = WorkflowIds.entrySignalIdFromPosition(p.workflowId());
      openPos.add(
          new OpenPos(p.contractSymbol(), signalId == null ? null : open.entryAt().get(signalId)));
    }
    List<Buy> buys = new ArrayList<>();
    for (Map<String, Object> o : orderRows) {
      if ("BUY".equalsIgnoreCase(String.valueOf(o.get("side")))) {
        buys.add(
            new Buy(
                (String) o.get("option_symbol"), PreviewProjection.instant(o.get("recorded_at"))));
      }
    }
    return PreviewQuarantine.hiddenFrom(clock.instant(), ENTRY_DELAY, openPos, buys);
  }

  private synchronized Open open(String tenant) {
    Instant now = clock.instant();
    Open cached = openByTenant.get(tenant);
    if (cached != null && now.isBefore(cached.expires())) {
      return cached;
    }
    List<OpenPosition> list = positions.openPositions(tenant);
    Set<String> signalIds = new LinkedHashSet<>();
    for (OpenPosition p : list) {
      String signalId = WorkflowIds.entrySignalIdFromPosition(p.workflowId());
      if (signalId != null) {
        signalIds.add(signalId);
      }
    }
    Open fresh = new Open(list, entryTimes.firstEntryAt(tenant, signalIds), now.plus(OPEN_TTL));
    openByTenant.put(tenant, fresh);
    return fresh;
  }
}
