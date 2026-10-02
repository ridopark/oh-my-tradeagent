package com.ohmytradeagent.tdbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.live.DiscordHealthClient;
import com.ohmytradeagent.tdbff.live.FillListenerStatusClient;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient;
import com.ohmytradeagent.tdbff.portfolio.BrokerPositionsClient;
import com.ohmytradeagent.tdbff.portfolio.RealizedPnlCalculator;
import com.ohmytradeagent.tdbff.positions.PositionLifecycleReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.positions.TradeContextSpotReader;
import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * LOAD CHECK — 10 concurrent /live viewers of ONE tenant for 60s against the real BFF (full Spring
 * Boot context, real controllers + caches + filter chain, real HTTP over loopback). Opt-in: runs
 * only with {@code -Dbff.loadcheck=true} (see {@code scripts/dev/bff-live-loadcheck.sh}).
 *
 * <p>Each viewer polls {@code /api/live/marks} every 1s, {@code /api/live/connection} every 5s and
 * {@code /api/portfolio} every 15s, staggered. Only the OUTBOUND edges are stubbed (and counted):
 * the Temporal-backed readers/clients, market-data/exec/discord HTTP clients, and the
 * Postgres-backed readers (no local DB — the datasources are lazy, so only their readers need
 * stubbing; the context is otherwise the one {@link ApplicationContextSmokeTest} boots).
 *
 * <p>Pass: per 10s window, at most ONE broker snapshot load (BrokerPositionsClient.marksFor,
 * AccountEquityClient.snapshotFor) and at most one PositionsReader.openPositions per cache that
 * issues it (the portfolio cache, 10s; the marks OCC cache, 15s); zero non-2xx; marks p95 &lt;
 * 200ms.
 */
@SpringBootTest(
    classes = TenantDashboardBffApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "DASHBOARD_READONLY_PASSWORD=test-not-used",
      "DASHBOARD_WRITER_PASSWORD=test-not-used",
      "bff.service-token=load-token",
      "logging.level.root=WARN"
    })
@EnabledIfSystemProperty(named = "bff.loadcheck", matches = "true")
class LiveViewersLoadCheckTest {

  private static final String TENANT = "acme";
  private static final String BROKER_TARGET = "alpaca-live";
  private static final int VIEWERS = 10;
  private static final Duration RUN = Duration.ofSeconds(60);
  private static final long WINDOW_MS = 10_000;
  private static final List<String> OCCS =
      List.of("SPY   261120C00600000", "NVDA  261120C00200000", "SMCI  261120C00050000");

  @LocalServerPort private int port;

  // Hermetic context, as ApplicationContextSmokeTest.
  @MockitoBean private WorkflowServiceStubs workflowServiceStubs;
  @MockitoBean private WorkflowClient workflowClient;
  @MockitoBean private StringRedisTemplate stringRedisTemplate;

  // Temporal / broker edges (counted).
  @MockitoBean private PositionsReader positionsReader;
  @MockitoBean private BrokerPositionsClient brokerPositions;
  @MockitoBean private AccountEquityClient accountEquity;
  // Outbound HTTP edges (counted).
  @MockitoBean private MarketDataMarksClient marksClient;
  @MockitoBean private MarketDataLivenessClient liveness;
  @MockitoBean private MarketDataQuoteClient quotes;
  @MockitoBean private FillListenerStatusClient fillListener;
  @MockitoBean private DiscordHealthClient discord;
  // Postgres-backed readers (no DB locally).
  @MockitoBean private TenantStrategyResolver strategyResolver;
  @MockitoBean private DbStrategyConfigReader strategyRegistry;
  @MockitoBean private RealizedPnlCalculator realizedPnl;
  @MockitoBean private TradeContextSpotReader entrySpots;
  @MockitoBean private PositionLifecycleReader lifecycles;

  /** dependency label -> call start times (ms since t0). */
  private final Map<String, ConcurrentLinkedQueue<Long>> calls = new ConcurrentHashMap<>();

  /** endpoint -> [latencyMs, status] per request. */
  private final Map<String, ConcurrentLinkedQueue<long[]>> requests = new ConcurrentHashMap<>();

  // Monotonic: WSL2/NTP can step the wall clock mid-run, which skews the 10s windows.
  private volatile long t0;

  private <T> T counted(String dep, long minMs, long maxMs, Supplier<T> result) {
    calls.computeIfAbsent(dep, k -> new ConcurrentLinkedQueue<>()).add(now());
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return result.get();
  }

  private long now() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
  }

  private void stubDependencies() {
    List<OpenPosition> positions = new ArrayList<>();
    for (int i = 0; i < OCCS.size(); i++) {
      positions.add(
          new OpenPosition(
              "t-acme/s-copytrade-v1/pos/" + OCCS.get(i) + "/sig" + i,
              "copytrade-v1",
              OCCS.get(i),
              2,
              new BigDecimal("1.50"),
              new BigDecimal("300")));
    }
    when(positionsReader.openPositions(TENANT))
        .thenAnswer(
            inv -> {
              // Attribute the read: PortfolioService runs it on its sub-read pool; the marks
              // poll's OpenOccCache runs it on the request thread.
              boolean viaPortfolio = Thread.currentThread().getName().startsWith("bff-portfolio");
              return counted(
                  viaPortfolio
                      ? "PositionsReader.openPositions [portfolio]"
                      : "PositionsReader.openPositions [marks OCC set]",
                  50,
                  200,
                  () -> positions);
            });
    Map<String, BrokerPositionsClient.PositionMarks> marks = new TreeMap<>();
    for (String occ : OCCS) {
      marks.put(
          BrokerPositionsClient.compactOcc(occ),
          new BrokerPositionsClient.PositionMarks(
              new BigDecimal("1.80"), new BigDecimal("60"), new BigDecimal("20"), 2L));
    }
    when(brokerPositions.marksFor(eq(BROKER_TARGET), eq(TENANT), anyString()))
        .thenAnswer(inv -> counted("BrokerPositionsClient.marksFor", 50, 300, () -> marks));
    when(accountEquity.snapshotFor(TENANT, BROKER_TARGET))
        .thenAnswer(
            inv ->
                counted(
                    "AccountEquityClient.snapshotFor",
                    50,
                    300,
                    () ->
                        new AccountEquityClient.BrokerAccount(
                            new BigDecimal("100000"), "PA1", new BigDecimal("99000"))));
    when(marksClient.marks(anyList()))
        .thenAnswer(
            inv -> {
              List<String> asked = inv.getArgument(0);
              List<Map<String, Object>> out = new ArrayList<>();
              for (String occ : asked) {
                out.add(Map.of("occ", occ, "bid", 1.75, "ask", 1.85, "mark", 1.80));
              }
              return counted(
                  "MarketDataMarksClient.marks",
                  5,
                  20,
                  () -> new MarketDataMarksClient.MarksResponse("2026-10-01T14:00:00Z", out));
            });
    when(liveness.feedHealth())
        .thenAnswer(inv -> counted("MarketDataLivenessClient.feedHealth", 5, 30, Map::of));
    when(fillListener.status(TENANT))
        .thenAnswer(inv -> counted("FillListenerStatusClient.status", 5, 30, Map::of));
    when(discord.health()).thenAnswer(inv -> counted("DiscordHealthClient.health", 5, 30, Map::of));
    when(quotes.equityPrice(anyString()))
        .thenAnswer(
            inv -> counted("MarketDataQuoteClient.equityPrice", 5, 20, () -> BigDecimal.TEN));

    when(strategyResolver.strategyIdsForTenant(TENANT)).thenReturn(List.of("copytrade-v1"));
    when(strategyRegistry.brokerTarget(TENANT, "copytrade-v1")).thenReturn(BROKER_TARGET);
    when(realizedPnl.computeRealized(
            eq(TENANT), eq("copytrade-v1"), ArgumentMatchers.any(LocalDate.class)))
        .thenAnswer(
            inv ->
                counted(
                    "RealizedPnlCalculator.computeRealized",
                    20,
                    50,
                    () -> new RealizedPnlCalculator.RealizedPnl(BigDecimal.ONE, BigDecimal.TEN)));
    when(entrySpots.entrySpotByWorkflowId(eq(TENANT), anyList())).thenReturn(Map.of());
    when(lifecycles.lifecycleByWorkflowId(eq(TENANT), anyList())).thenReturn(Map.of());
  }

  @Test
  void tenViewersOfOneTenantForSixtySeconds() throws Exception {
    stubDependencies();
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newFixedThreadPool(8))
            .build();
    ScheduledExecutorService sched = Executors.newScheduledThreadPool(40);

    t0 = System.nanoTime();
    for (int v = 0; v < VIEWERS; v++) {
      sched.scheduleAtFixedRate(
          () -> hit(http, "/api/live/marks"), v * 100L, 1_000, TimeUnit.MILLISECONDS);
      sched.scheduleAtFixedRate(
          () -> hit(http, "/api/live/connection"), v * 500L, 5_000, TimeUnit.MILLISECONDS);
      sched.scheduleAtFixedRate(
          () -> hit(http, "/api/portfolio"), v * 1_500L, 15_000, TimeUnit.MILLISECONDS);
    }
    Thread.sleep(RUN.toMillis());
    sched.shutdown(); // stop issuing; let in-flight requests finish
    assertThat(sched.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    long elapsed = now();

    String summary = summary(elapsed);
    System.out.println(summary);

    // Non-vacuous: every endpoint was actually exercised and the portfolio really loaded, so the
    // bounds below cannot pass on an empty run.
    for (String path : List.of("/api/live/marks", "/api/live/connection", "/api/portfolio")) {
      assertThat(latencies(path)).as("requests to %s", path).isNotEmpty();
    }
    assertThat(java.util.Arrays.stream(perWindow("BrokerPositionsClient.marksFor", elapsed)).sum())
        .as("portfolio loads")
        .isPositive();
    // (a) broker/Temporal snapshot loads: at most one per 10s window, per issuing cache.
    for (String dep :
        List.of(
            "BrokerPositionsClient.marksFor",
            "AccountEquityClient.snapshotFor",
            "PositionsReader.openPositions [portfolio]",
            "PositionsReader.openPositions [marks OCC set]")) {
      assertThat(java.util.Arrays.stream(perWindow(dep, elapsed)).max().orElse(0))
          .as("max %s loads in any 10s window", dep)
          .isLessThanOrEqualTo(1);
    }
    // (b) zero request errors.
    for (Map.Entry<String, ConcurrentLinkedQueue<long[]>> e : requests.entrySet()) {
      assertThat(e.getValue())
          .as("non-2xx on %s", e.getKey())
          .allSatisfy(r -> assertThat(r[1]).isBetween(200L, 299L));
    }
    // (c) marks p95 < 200ms.
    assertThat(percentile(latencies("/api/live/marks"), 95)).isLessThan(200);
  }

  private void hit(HttpClient http, String path) {
    long start = System.nanoTime();
    long status;
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer load-token")
              .header("X-Tenant-Id", TENANT)
              .GET()
              .build();
      status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      status = -1; // transport error counts as an error
    }
    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    requests.computeIfAbsent(path, k -> new ConcurrentLinkedQueue<>()).add(new long[] {ms, status});
  }

  private int[] perWindow(String dep, long elapsedMs) {
    int[] w = new int[(int) (elapsedMs / WINDOW_MS) + 1];
    for (long t : calls.getOrDefault(dep, new ConcurrentLinkedQueue<>())) {
      w[(int) (t / WINDOW_MS)]++;
    }
    return w;
  }

  private List<Long> latencies(String path) {
    return requests.getOrDefault(path, new ConcurrentLinkedQueue<>()).stream()
        .map(r -> r[0])
        .sorted()
        .toList();
  }

  private static long percentile(List<Long> sorted, int p) {
    if (sorted.isEmpty()) {
      return -1;
    }
    int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, idx));
  }

  private String summary(long elapsedMs) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n==== /live LOAD CHECK: ")
        .append(VIEWERS)
        .append(" viewers, tenant=")
        .append(TENANT)
        .append(", ")
        .append(elapsedMs / 1000.0)
        .append("s ====\n");
    sb.append(
        String.format(
            "%-22s %8s %7s %7s %7s %7s%n",
            "endpoint", "requests", "errors", "p50ms", "p95ms", "maxms"));
    for (String path : List.of("/api/live/marks", "/api/live/connection", "/api/portfolio")) {
      List<Long> lat = latencies(path);
      long errors =
          requests.getOrDefault(path, new ConcurrentLinkedQueue<>()).stream()
              .filter(r -> r[1] < 200 || r[1] > 299)
              .count();
      sb.append(
          String.format(
              "%-22s %8d %7d %7d %7d %7d%n",
              path,
              lat.size(),
              errors,
              percentile(lat, 50),
              percentile(lat, 95),
              lat.isEmpty() ? -1 : lat.get(lat.size() - 1)));
    }
    int windows = (int) (elapsedMs / WINDOW_MS) + 1;
    sb.append(String.format("%n%-46s %6s", "dependency calls per 10s window", "total"));
    for (int i = 0; i < windows; i++) {
      sb.append(String.format(" %4s", "w" + i));
    }
    sb.append('\n');
    for (String dep : new TreeMap<>(calls).keySet()) {
      int[] w = perWindow(dep, elapsedMs);
      int total = 0;
      for (int n : w) {
        total += n;
      }
      sb.append(String.format("%-46s %6d", dep, total));
      for (int n : w) {
        sb.append(String.format(" %4d", n));
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
