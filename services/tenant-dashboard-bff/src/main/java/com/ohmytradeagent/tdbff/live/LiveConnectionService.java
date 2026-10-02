package com.ohmytradeagent.tdbff.live;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code /api/live/connection} body: one status Part per pipeline hop, each read
 * CONCURRENTLY on a small bounded daemon pool and each independently fail-soft — a throw or a hang
 * past {@link #PART_TIMEOUT} turns into {@code status:"unknown"} + reason for that part alone, so
 * the whole response is bounded by roughly one timeout.
 *
 * <p>Part = {@code {status: ok|stale|down|unknown, age_s, reason, ...raw fields}}.
 */
@Component
public class LiveConnectionService {

  private static final Logger log = LoggerFactory.getLogger(LiveConnectionService.class);
  static final Duration PART_TIMEOUT = Duration.ofSeconds(2);
  static final double DISCORD_OK_BELOW_S = 15;
  static final double DISCORD_STALE_BELOW_S = 120;
  static final long FEED_OK_MAX_TICK_AGE_MS = 30_000;

  // Mirrors market-data's MarketHours: Mon-Fri 09:30 inclusive to 16:00 exclusive ET, no holidays.
  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
  private static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

  private final MarketDataLivenessClient marketData;
  private final ExecFillListenerClient exec;
  private final DiscordHealthClient discord;
  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader strategyRegistry;
  private final Clock clock;
  private final Executor executor;
  private final Duration partTimeout;

  @Autowired
  public LiveConnectionService(
      MarketDataLivenessClient marketData,
      ExecFillListenerClient exec,
      DiscordHealthClient discord,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry) {
    this(
        marketData,
        exec,
        discord,
        strategyResolver,
        strategyRegistry,
        Clock.systemUTC(),
        boundedDaemonPool(),
        PART_TIMEOUT);
  }

  LiveConnectionService(
      MarketDataLivenessClient marketData,
      ExecFillListenerClient exec,
      DiscordHealthClient discord,
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry,
      Clock clock,
      Executor executor,
      Duration partTimeout) {
    this.marketData = marketData;
    this.exec = exec;
    this.discord = discord;
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
    this.clock = clock;
    this.executor = executor;
    this.partTimeout = partTimeout;
  }

  /**
   * 4 threads, 32 queued: a part that cannot be scheduled degrades to "unknown" instead of piling
   * up. Every part's HTTP client has its own sub-2s timeouts, so no thread stays pinned for long.
   */
  private static Executor boundedDaemonPool() {
    return new ThreadPoolExecutor(
        4,
        4,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),
        r -> {
          Thread t = new Thread(r, "live-connection-part");
          t.setDaemon(true);
          return t;
        });
  }

  public Map<String, Object> connection(String tenantId) {
    CompletableFuture<Map<String, Object>> feeds =
        part(
            "market_data",
            () -> feedParts(marketData.feedHealth()),
            reason -> Map.of("equity", unknown(reason), "option", unknown(reason)));
    CompletableFuture<Map<String, Object>> broker =
        part("broker", () -> brokerPartFor(tenantId), LiveConnectionService::unknown);
    CompletableFuture<Map<String, Object>> discordPart =
        part("discord", () -> discordPart(discord.health()), LiveConnectionService::unknown);
    CompletableFuture.allOf(feeds, broker, discordPart).join();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("server_time", clock.instant().toString());
    body.put("market_open", isRegularTradingHours(ZonedDateTime.now(clock.withZone(ET))));
    body.put("market_data", feeds.join());
    body.put("broker", broker.join());
    body.put("discord", discordPart.join());
    return body;
  }

  private CompletableFuture<Map<String, Object>> part(
      String name,
      Supplier<Map<String, Object>> read,
      Function<String, Map<String, Object>> fallback) {
    try {
      return CompletableFuture.supplyAsync(read, executor)
          .completeOnTimeout(
              fallback.apply("timed out after " + partTimeout.toMillis() + "ms"),
              partTimeout.toMillis(),
              TimeUnit.MILLISECONDS)
          .exceptionally(
              e -> {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("live connection part {} failed: {}", name, cause.toString());
                return fallback.apply(name + " unreachable: " + cause.getClass().getSimpleName());
              });
    } catch (RuntimeException e) { // RejectedExecutionException: pool saturated
      return CompletableFuture.completedFuture(fallback.apply(name + " not scheduled"));
    }
  }

  private Map<String, Object> brokerPartFor(String tenantId) {
    String brokerTarget = primaryBrokerTarget(tenantId);
    if (brokerTarget == null) {
      return unknown("no broker_target configured for tenant");
    }
    return brokerPart(exec.status(brokerTarget), tenantId, brokerTarget);
  }

  /**
   * First non-null broker_target across the tenant's strategies (as PortfolioHistoryController).
   */
  private String primaryBrokerTarget(String tenantId) {
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      String bt = strategyRegistry.brokerTarget(tenantId, strategyId);
      if (bt != null) {
        return bt;
      }
    }
    return null;
  }

  /**
   * market-data feedhealth → {equity: Part, option: Part}, graded by tick age, not connection
   * alone: connected and ticked ≤ 30s ago → ok; connected and older → stale; connected, never
   * ticked → unknown; not connected but ticked before → down; never opened (lazy socket, no
   * subscribers) → unknown.
   */
  static Map<String, Object> feedParts(Map<String, Object> feedHealth) {
    Map<String, Object> out = new LinkedHashMap<>();
    boolean answered = feedHealth != null && "ok".equals(feedHealth.get("status"));
    for (String feed : List.of("equity", "option")) {
      if (!answered) {
        out.put(feed, unknown("market-data unreachable"));
      } else if (!(feedHealth.get(feed) instanceof Map<?, ?> f)
          || !(f.get("connected") instanceof Boolean connected)) {
        out.put(feed, unknown("feed missing from market-data feedhealth"));
      } else {
        Number ageMs = f.get("lastTickAgeMs") instanceof Number n ? n : null;
        // -1 / null = no tick since boot.
        boolean ticked = ageMs != null && ageMs.longValue() >= 0;
        Double ageS = ticked ? ageMs.longValue() / 1000.0 : null;
        Map<String, Object> p;
        if (!ticked) {
          p = unknown(connected ? "connected, no ticks yet" : "idle (no stream subscribers)");
        } else if (!connected) {
          p = part("down", ageS, "feed disconnected");
        } else if (ageMs.longValue() <= FEED_OK_MAX_TICK_AGE_MS) {
          p = part("ok", ageS, null);
        } else {
          p = part("stale", ageS, "no tick for over 30s");
        }
        p.put("connected", connected);
        p.put("last_tick_age_ms", ageMs);
        out.put(feed, p);
      }
    }
    return out;
  }

  /**
   * exec fill-listener status → this tenant's Part. ok = connected && subscription_confirmed; stale
   * = connected but subscription not confirmed; down = not connected; unknown = listener disabled
   * or no row for this tenant. Other tenants' rows are never read into the response. When the row
   * is {@code metrics_scope:"pod"} and the pod carries more than one tenant row, the confirmation
   * may be another tenant's, so a connected tenant is at best stale.
   */
  static Map<String, Object> brokerPart(
      Map<String, Object> status, String tenantId, String brokerTarget) {
    Map<String, Object> p;
    if (!Boolean.TRUE.equals(status.get("enabled"))) {
      p = unknown("fill listener disabled");
    } else {
      Map<?, ?> row = null;
      int tenantRows = 0;
      if (status.get("tenants") instanceof List<?> rows) {
        tenantRows = rows.size();
        for (Object r : rows) {
          if (r instanceof Map<?, ?> m && tenantId.equals(m.get("tenant_id"))) {
            row = m;
            break;
          }
        }
      }
      if (row == null) {
        p = unknown("no fill-listener row for tenant");
      } else {
        boolean connected = Boolean.TRUE.equals(row.get("connected"));
        boolean confirmed = Boolean.TRUE.equals(row.get("subscription_confirmed"));
        Double age = row.get("last_event_age_s") instanceof Number n ? n.doubleValue() : null;
        if (!connected) {
          p = part("down", age, "fill stream not connected");
        } else if (!confirmed) {
          p = part("stale", age, "subscription not confirmed");
        } else if ("pod".equals(row.get("metrics_scope")) && tenantRows > 1) {
          p = part("stale", age, "subscription confirmation is pod-wide");
        } else {
          p = part("ok", age, null);
        }
        p.put("connected", row.get("connected"));
        p.put("subscription_confirmed", row.get("subscription_confirmed"));
        p.put("last_event_age_s", row.get("last_event_age_s"));
        p.put("reconnects", row.get("reconnects"));
        p.put("metrics_scope", row.get("metrics_scope"));
      }
    }
    p.put("broker_target", brokerTarget);
    return p;
  }

  /** ok below 15s, stale below 120s, down at/after 120s or with no heartbeat at all. */
  static Map<String, Object> discordPart(Map<String, Object> health) {
    if (!health.containsKey("heartbeat_age_s")) {
      return unknown("unexpected healthz response");
    }
    if (!(health.get("heartbeat_age_s") instanceof Number n)) {
      return part("down", null, "no heartbeat");
    }
    double age = n.doubleValue();
    if (age < DISCORD_OK_BELOW_S) {
      return part("ok", age, null);
    }
    if (age < DISCORD_STALE_BELOW_S) {
      return part("stale", age, "heartbeat late");
    }
    return part("down", age, "heartbeat stopped");
  }

  static boolean isRegularTradingHours(ZonedDateTime nowEt) {
    DayOfWeek dow = nowEt.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      return false;
    }
    LocalTime t = nowEt.toLocalTime();
    return !t.isBefore(RTH_OPEN) && t.isBefore(RTH_CLOSE);
  }

  private static Map<String, Object> part(String status, Double ageS, String reason) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("status", status);
    p.put("age_s", ageS);
    p.put("reason", reason);
    return p;
  }

  private static Map<String, Object> unknown(String reason) {
    return part("unknown", null, reason);
  }
}
