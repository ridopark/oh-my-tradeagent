package com.ohmytradeagent.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ohmytradeagent.marketdata.health.FeedHealth;
import com.ohmytradeagent.marketdata.marks.DisplayMarksService;
import com.ohmytradeagent.marketdata.marks.ManualScheduler;
import com.ohmytradeagent.marketdata.marks.MutableClock;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import com.ohmytradeagent.marketdata.quote.MarketDataQuoteController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * Hard constraint of PLAN-2026-10-01-live-realtime-holdings: display-only marks polling must be
 * invisible to trail liveness. A display interest must not appear as a premium subscriber, must not
 * emit ticks to trail listeners, must not stamp the poll liveness /api/trail-liveness reads, and
 * must not touch FeedHealth.OPTION. Driven against the REAL {@link AlpacaMarketData} (REST via
 * MockWebServer); neither scheduler runs on its own, so every poll is explicit.
 *
 * <p>The display OCC is exercised in both the compact form the /md/marks wire carries and the
 * space-padded form trail subscriptions are keyed by, so a display path that leaked into the
 * provider's per-OCC state could not hide behind a key-form mismatch.
 */
class DisplayMarksIsolationTest {

  private static final String TRAIL_PADDED = "NVDA  260516C00140000";
  private static final String TRAIL_COMPACT = "NVDA260516C00140000";
  private static final String OTHER_PADDED = "AMD   260516C00150000";
  private static final String OTHER_COMPACT = "AMD260516C00150000";

  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicInteger optionSnapshotRequests = new AtomicInteger();
  private MockWebServer server;
  private FeedHealth feedHealth;
  private AlpacaMarketData provider;
  private ManualScheduler displayScheduler;
  private DisplayMarksService display;
  private final List<Tick> trailTicks = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          private int seq;

          @Override
          public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath();
            if (path.startsWith("/v1beta1/options/snapshots")) {
              optionSnapshotRequests.incrementAndGet();
              String sym = req.getRequestUrl().queryParameter("symbols");
              // A new quote stamp per response so the trail's resample guard never swallows a tick.
              String stamp = "2026-10-01T14:30:%02dZ".formatted(++seq % 60);
              return json(
                  "{\"snapshots\":{\""
                      + sym
                      + "\":{\"latestQuote\":{\"bp\":2.90,\"ap\":3.00,\"t\":\""
                      + stamp
                      + "\"}}}}");
            }
            if (path.startsWith("/v2/stocks/")) {
              return json("{\"latestTrade\":{\"p\":140.10}}");
            }
            return new MockResponse().setResponseCode(404);
          }
        });
    server.start();
    String base = server.url("/").toString().replaceAll("/$", "");
    RestClient client = RestClient.builder().baseUrl(base).build();
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            base, "wss://example.invalid/should-not-connect", "k", "s", "", "", null);
    feedHealth = new FeedHealth(new SimpleMeterRegistry());
    // The trail poll is scheduled for real (premiumPolls gets its entry) but never fires by itself.
    ManualScheduler providerScheduler = new ManualScheduler();
    provider =
        new AlpacaMarketData(
            client,
            mapper,
            props,
            HttpClient.newHttpClient(),
            providerScheduler.executor,
            feedHealth);
    displayScheduler = new ManualScheduler();
    display =
        new DisplayMarksService(
            provider,
            feedHealth,
            new MutableClock(Instant.parse("2026-10-01T14:30:00Z")),
            500L,
            displayScheduler.executor);
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private static MockResponse json(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private void displayPolls(String occ, int times) {
    display.marks(List.of(occ));
    for (int i = 0; i < times; i++) {
      displayScheduler.runAll();
    }
  }

  private ObjectNode premiumSubscriptionsJsonWithoutNow() {
    Map<String, Object> body = new MarketDataQuoteController(provider).premiumSubscriptions();
    ObjectNode node = mapper.valueToTree(body);
    node.remove("now");
    return node;
  }

  // --- 1: same OCC as an armed trail ---

  @ParameterizedTest
  @ValueSource(strings = {TRAIL_COMPACT, TRAIL_PADDED})
  void sameOcc_displayLeavesSubscribersLivenessAndTrailTicksUntouched(String displayOcc) {
    Subscription trail = provider.subscribePremium(TRAIL_PADDED, trailTicks::add);
    provider.pollOnce(TRAIL_PADDED);
    assertThat(trailTicks).hasSize(1);
    Map<String, PremiumFeedStatus> before = provider.premiumFeedStatus();
    int restBefore = optionSnapshotRequests.get();

    displayPolls(displayOcc, 5);

    assertThat(provider.premiumFeedStatus()).isEqualTo(before);
    PremiumFeedStatus st = provider.premiumFeedStatus().get(TRAIL_PADDED);
    assertThat(st.subscribers()).isEqualTo(1);
    assertThat(st.pollOkCount()).isEqualTo(1L);
    assertThat(trailTicks).hasSize(1);
    // Reuse: the display copied the trail's cached quote rather than polling a second time.
    assertThat(optionSnapshotRequests.get()).isEqualTo(restBefore);
    var mark = display.marks(List.of(displayOcc)).get(0);
    assertThat(mark.warming()).isFalse();
    assertThat(mark.quote().bid()).isEqualByComparingTo("2.90");
    trail.close();
  }

  // --- 2: a different OCC, and an orphaned trail ---

  @ParameterizedTest
  @ValueSource(strings = {OTHER_COMPACT, OTHER_PADDED})
  void differentOcc_displayCreatesNoSubscriptionAndLeavesOptionFeedHealthAlone(String displayOcc) {
    provider.subscribePremium(TRAIL_PADDED, trailTicks::add);
    boolean connectedBefore = feedHealth.connected(FeedHealth.Feed.OPTION);
    long ageBefore = feedHealth.lastTickAgeMillis(FeedHealth.Feed.OPTION);
    assertThat(ageBefore).isEqualTo(FeedHealth.NO_TICK);

    displayPolls(displayOcc, 5);

    // The display actually polled (its own REST snapshot) ...
    assertThat(optionSnapshotRequests.get()).isEqualTo(5);
    assertThat(display.marks(List.of(displayOcc)).get(0).quote()).isNotNull();
    // ... yet nothing a trail consumer reads moved.
    assertThat(provider.premiumFeedStatus()).containsOnlyKeys(TRAIL_PADDED);
    assertThat(provider.premiumFeedStatus().get(TRAIL_PADDED).pollOkCount()).isZero();
    assertThat(feedHealth.connected(FeedHealth.Feed.OPTION)).isEqualTo(connectedBefore);
    assertThat(feedHealth.lastTickAgeMillis(FeedHealth.Feed.OPTION)).isEqualTo(FeedHealth.NO_TICK);
    assertThat(trailTicks).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {TRAIL_COMPACT, TRAIL_PADDED})
  void orphanedTrail_displayInterestOnItsOccNeverMakesItReadLive(String displayOcc) {
    // Subscribed, but its poll never completes — /api/trail-liveness must keep calling it orphaned.
    provider.subscribePremium(TRAIL_PADDED, trailTicks::add);

    displayPolls(displayOcc, 5);

    PremiumFeedStatus st = provider.premiumFeedStatus().get(TRAIL_PADDED);
    assertThat(st.subscribers()).isEqualTo(1);
    assertThat(st.lastPollOkAt()).isNull();
    assertThat(st.pollOkCount()).isZero();
    assertThat(st.lastEmitAt()).isNull();
    assertThat(trailTicks).isEmpty();
    assertThat(feedHealth.lastTickAgeMillis(FeedHealth.Feed.OPTION)).isEqualTo(FeedHealth.NO_TICK);
  }

  // --- 3: /md/premium-subscriptions body is byte-for-byte the same ---

  @Test
  void premiumSubscriptionsEndpoint_identicalWithAndWithoutDisplayInterest() {
    provider.subscribePremium(TRAIL_PADDED, trailTicks::add);
    provider.subscribePremium(OTHER_PADDED, trailTicks::add);
    provider.pollOnce(TRAIL_PADDED);
    ObjectNode before = premiumSubscriptionsJsonWithoutNow();

    display.marks(List.of(TRAIL_COMPACT, OTHER_COMPACT, "TSLA260516C00250000"));
    for (int i = 0; i < 5; i++) {
      displayScheduler.runAll();
    }

    assertThat(premiumSubscriptionsJsonWithoutNow()).isEqualTo(before);
  }

  // --- the additive provider accessors the display path reads ---

  @Test
  void accessors_reflectTheTrailPollAndClearWithIt() {
    assertThat(provider.premiumPollActive(TRAIL_COMPACT)).isFalse();
    assertThat(provider.lastPolledQuote(TRAIL_COMPACT)).isEmpty();

    Subscription trail = provider.subscribePremium(TRAIL_PADDED, trailTicks::add);
    assertThat(provider.premiumPollActive(TRAIL_COMPACT)).isTrue();
    assertThat(provider.premiumPollActive(TRAIL_PADDED)).isTrue();
    assertThat(provider.lastPolledQuote(TRAIL_COMPACT)).isEmpty();

    provider.pollOnce(TRAIL_PADDED);
    assertThat(provider.lastPolledQuote(TRAIL_COMPACT))
        .get()
        .satisfies(
            q -> {
              assertThat(q.bid()).isEqualByComparingTo("2.90");
              assertThat(q.ask()).isEqualByComparingTo("3.00");
            });
    assertThat(provider.lastPolledQuote(OTHER_COMPACT)).isEmpty();

    trail.close();
    assertThat(provider.premiumPollActive(TRAIL_COMPACT)).isFalse();
    assertThat(provider.lastPolledQuote(TRAIL_COMPACT)).isEmpty();
  }

  @Test
  void lastEquityTick_isRecordedPassivelyFromTheStockStream() {
    assertThat(provider.lastEquityTick("NVDA")).isEmpty();

    provider.dispatchStockWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":140.12,\"t\":\"2026-10-01T14:30:00.1Z\",\"c\":[\"@\"]}]");

    assertThat(provider.lastEquityTick("NVDA"))
        .get()
        .satisfies(t -> assertThat(t.premium()).isEqualByComparingTo("140.12"));
    assertThat(provider.lastEquityTick("AMD")).isEmpty();
  }
}
