package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLegsResult;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.RichnessResult;
import com.ohmytradeagent.marketdata.MarketHours;
import com.ohmytradeagent.marketdata.provider.Bar;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Gated-condor Phase 2. Golden tests pin the pure math to the committed 0DTE research
 * (docs/plans/experiments/0dte-spx-2026-10): {@code condor-golden/} was exported from the research
 * cache by {@code condor-golden/export.py}, which reproduces {@code t4_iv_gate.richness_etf('SPY')}
 * at 14:00, the {@code spx_level.py} parity spot, and the {@code sell_afternoon.legs_for} S2 condor
 * strikes for three committed backtest days. The activity tests drive a mocked provider.
 */
class CondorMarketActivityImplTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 5); // Monday
  private static final Clock CLOCK =
      Clock.fixed(
          ZonedDateTime.of(TODAY, LocalTime.of(14, 0, 5), MarketHours.ET).toInstant(),
          MarketHours.ET);

  // --- golden: pure math vs the research's Python values ---

  @ParameterizedTest
  @ValueSource(strings = {"2025-06-12", "2026-09-21", "2026-10-02"})
  void richness_matchesResearchFormula_to4Decimals(String day) throws IOException {
    Map<String, String> g = golden(day);
    double[] closes =
        csv(day + "-spy-closes.csv").stream().mapToDouble(r -> Double.parseDouble(r[1])).toArray();

    double r =
        CondorMarketActivityImpl.richness(
            Double.parseDouble(g.get("straddle")), closes, 120, Double.parseDouble(g.get("spot")));

    assertThat(r).isCloseTo(Double.parseDouble(g.get("richness")), within(5e-5));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2025-06-12", "2026-09-21", "2026-10-02"})
  void paritySpot_matchesSpxLevelMethod(String day) throws IOException {
    TreeMap<Integer, double[]> cp = new TreeMap<>();
    for (String[] r : csv(day + "-parity.csv")) {
      cp.put(
          Integer.parseInt(r[0]),
          new double[] {Double.parseDouble(r[1]), Double.parseDouble(r[2])});
    }

    assertThat(CondorMarketActivityImpl.paritySpot(cp))
        .isCloseTo(Double.parseDouble(golden(day).get("parity_spot")), within(1e-9));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2025-06-12", "2026-09-21", "2026-10-02"})
  void condorStrikes_matchBacktestS2Rule(String day) throws IOException {
    Map<String, String> g = golden(day);

    int[] k = CondorMarketActivityImpl.condorStrikes(Double.parseDouble(g.get("spot")), 0.15, 0.60);

    assertThat(k)
        .containsExactly(
            Integer.parseInt(g.get("short_call")),
            Integer.parseInt(g.get("short_put")),
            Integer.parseInt(g.get("long_call")),
            Integer.parseInt(g.get("long_put")));
  }

  // --- evaluateRichness through the activity ---

  @Test
  void evaluateRichness_gatesOnTrailingMedian() {
    // Same spot (parity 600) and σ every day, so richness ranks exactly as the straddle does.
    // Trailing straddles 1.0 / 1.5 / 3.0 -> median is the 1.5 day; today 2.0 is above it.
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBars(provider, Map.of());
    stubTrailingOptionBars(
        provider,
        Map.of(
            TODAY,
            2.0,
            LocalDate.of(2026, 10, 2),
            1.0,
            LocalDate.of(2026, 10, 1),
            1.5,
            LocalDate.of(2026, 9, 30),
            3.0));

    RichnessResult r = activity(provider).evaluateRichness("XSP", "14:00", 3);

    double[] closes = proxyCloses();
    assertThat(r.reason()).isNull();
    assertThat(r.richness())
        .isCloseTo(CondorMarketActivityImpl.richness(2.0, closes, 120, 600.0), within(1e-12));
    assertThat(r.trailingMedian())
        .isCloseTo(CondorMarketActivityImpl.richness(1.5, closes, 120, 600.0), within(1e-12));
    assertThat(r.trailingCount()).isEqualTo(3);
    assertThat(r.trailingQuantile()).isCloseTo(2.0 / 3.0, within(1e-12));
    assertThat(r.gate()).isTrue();
  }

  @Test
  void evaluateRichness_belowMedian_doesNotGate() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBars(provider, Map.of());
    stubTrailingOptionBars(
        provider,
        Map.of(
            TODAY,
            1.2,
            LocalDate.of(2026, 10, 2),
            1.0,
            LocalDate.of(2026, 10, 1),
            1.5,
            LocalDate.of(2026, 9, 30),
            3.0));

    RichnessResult r = activity(provider).evaluateRichness("XSP", "14:00", 3);

    assertThat(r.reason()).isNull();
    assertThat(r.trailingQuantile()).isCloseTo(1.0 / 3.0, within(1e-12));
    assertThat(r.gate()).isFalse();
  }

  @Test
  void evaluateRichness_skipsDaysWithoutBars_andWalksFurtherBack() {
    // 10-01 is a "holiday" (no proxy bars): the walk skips it and the weekend, landing on 09-29.
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBars(provider, Map.of(LocalDate.of(2026, 10, 1), List.of()));
    stubTrailingOptionBars(
        provider,
        Map.of(
            TODAY,
            2.0,
            LocalDate.of(2026, 10, 2),
            1.0,
            LocalDate.of(2026, 9, 30),
            3.0,
            LocalDate.of(2026, 9, 29),
            3.5));

    RichnessResult r = activity(provider).evaluateRichness("XSP", "14:00", 3);

    assertThat(r.trailingCount()).isEqualTo(3);
    assertThat(r.gate()).isFalse(); // median is the 3.0 day
  }

  @Test
  void evaluateRichness_insufficientTrailingHistory_failsClosed() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBars(provider, Map.of());
    stubTrailingOptionBars(provider, Map.of(TODAY, 2.0, LocalDate.of(2026, 10, 2), 1.0));

    RichnessResult r = activity(provider).evaluateRichness("XSP", "14:00", 3);

    assertThat(r.richness()).isNotNull();
    assertThat(r.trailingCount()).isEqualTo(1);
    assertThat(r.gate()).isFalse();
    assertThat(r.reason()).contains("trailing");
  }

  @Test
  void evaluateRichness_pricesTodaysStraddleFromBars_notChainNbbo() {
    // #898: today's straddle must be on the SAME basis as the trailing window (option-trade bars),
    // not the chain's NBBO mids — a buyer-heavy print vs a mid would bias the gate silently. The
    // chain here quotes a 9.0 straddle; the bars say 2.0, and only the bars may be used.
    MarketDataProvider provider = mock(MarketDataProvider.class);
    org.mockito.Mockito.lenient()
        .when(provider.optionChainQuotes("XSP", TODAY))
        .thenReturn(parityChain(4.5, 4.5));
    stubProxyBars(provider, Map.of());
    stubTrailingOptionBars(
        provider,
        Map.of(
            TODAY,
            2.0,
            LocalDate.of(2026, 10, 2),
            1.0,
            LocalDate.of(2026, 10, 1),
            1.5,
            LocalDate.of(2026, 9, 30),
            3.0));

    RichnessResult r = activity(provider).evaluateRichness("XSP", "14:00", 3);

    assertThat(r.richness())
        .isCloseTo(
            CondorMarketActivityImpl.richness(2.0, proxyCloses(), 120, 600.0), within(1e-12));
  }

  @Test
  void settlementSpot_isParitySpotFromTheLastBarsBeforeTheClose() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBarsUntil(provider, LocalTime.of(16, 0));
    stubOptionBarsAt(provider, LocalTime.of(15, 59), 1.0, 1.0);

    assertThat(activity(provider).settlementSpot("XSP")).isCloseTo(600.0, within(1e-9));
  }

  @Test
  void settlementSpot_noBars_isNull() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    when(provider.stockBars1Min(eq("SPY"), any(), any())).thenReturn(List.of());

    assertThat(activity(provider).settlementSpot("XSP")).isNull();
  }

  @Test
  void evaluateRichness_noTodayBars_failsClosed() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBars(provider, Map.of());
    stubTrailingOptionBars(provider, Map.of(LocalDate.of(2026, 10, 2), 1.0));

    RichnessResult r = activity(provider).evaluateRichness("XSP", "14:00", 3);

    assertThat(r.gate()).isFalse();
    assertThat(r.richness()).isNull();
    assertThat(r.reason()).isNotBlank();
  }

  // --- #912: minute-bar publication lag on today's read ---

  @Test
  void todaysLastProxyMinuteLate_isWaitedFor_thenRichnessComputes() {
    // 2026-10-07: the eval ran at 14:00:00.177 and the 13:59 SPY bar was not yet published, so the
    // holiday guard gated out a genuine trade day. The first read sees bars through 13:58 only;
    // the 13:59 bar appears on a later poll.
    MarketDataProvider provider = mock(MarketDataProvider.class);
    int[] todayReads = {0};
    when(provider.stockBars1Min(eq("SPY"), any(), any()))
        .thenAnswer(
            inv -> {
              Instant start = inv.getArgument(1);
              List<Bar> bars = proxyBarsFrom(start);
              if (start.atZone(MarketHours.ET).toLocalDate().equals(TODAY)
                  && todayReads[0]++ == 0) {
                return bars.subList(0, bars.size() - 1); // 13:59 not yet published
              }
              return bars;
            });
    stubTrailingOptionBars(
        provider,
        Map.of(
            TODAY,
            2.0,
            LocalDate.of(2026, 10, 2),
            1.0,
            LocalDate.of(2026, 10, 1),
            1.5,
            LocalDate.of(2026, 9, 30),
            3.0));
    int[] sleeps = {0};

    RichnessResult r =
        new CondorMarketActivityImpl(provider, CLOCK, d -> sleeps[0]++)
            .evaluateRichness("XSP", "14:00", 3);

    assertThat(r.reason()).isNull();
    assertThat(r.gate()).isTrue();
    assertThat(sleeps[0]).isEqualTo(1);
  }

  @Test
  void noProxyBarsAtAllToday_isAHoliday_noWait() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    stubProxyBars(provider, Map.of(TODAY, List.of()));
    int[] sleeps = {0};

    RichnessResult r =
        new CondorMarketActivityImpl(provider, CLOCK, d -> sleeps[0]++)
            .evaluateRichness("XSP", "14:00", 3);

    assertThat(r.gate()).isFalse();
    assertThat(r.reason()).contains("no proxy bars");
    assertThat(sleeps[0]).isZero();
  }

  @Test
  void barsNeverComplete_givesUpAfterTheBoundedWait_withADistinctReason() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    when(provider.stockBars1Min(eq("SPY"), any(), any()))
        .thenAnswer(
            inv -> {
              List<Bar> bars = proxyBarsFrom(inv.getArgument(1));
              return bars.subList(0, bars.size() - 1); // 13:59 never arrives
            });
    int[] sleeps = {0};

    RichnessResult r =
        new CondorMarketActivityImpl(provider, CLOCK, d -> sleeps[0]++)
            .evaluateRichness("XSP", "14:00", 3);

    assertThat(r.gate()).isFalse();
    assertThat(r.reason()).contains("incomplete after").doesNotContain("no proxy bars");
    assertThat(sleeps[0]).isEqualTo(CondorMarketActivityImpl.MAX_PUBLICATION_POLLS);
  }

  @Test
  void publicationWait_isBoundedByTheClock_too() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    when(provider.stockBars1Min(eq("SPY"), any(), any()))
        .thenAnswer(
            inv -> {
              List<Bar> bars = proxyBarsFrom(inv.getArgument(1));
              return bars.subList(0, bars.size() - 1);
            });
    Clock late =
        Clock.fixed(
            ZonedDateTime.of(TODAY, LocalTime.of(14, 2), MarketHours.ET).toInstant(),
            MarketHours.ET);
    int[] sleeps = {0};

    new CondorMarketActivityImpl(provider, late, d -> sleeps[0]++)
        .evaluateRichness("XSP", "14:00", 3);

    assertThat(sleeps[0]).isZero(); // already past entry + MAX_PUBLICATION_WAIT
  }

  // --- resolveCondorLegs ---

  @Test
  void resolveCondorLegs_buildsFourLegsWithNbboAndNetCreditMid() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    Map<String, Quote> chain = parityChain(1.0, 1.0); // spot 600
    // spot 600: short C ceil(600.9)=601, short P floor(599.1)=599, wings 604 / 596
    chain.put(compact('C', 601), quote(compact('C', 601), "0.60", "0.64")); // mid 0.62
    chain.put(compact('P', 599), quote(compact('P', 599), "0.58", "0.62")); // mid 0.60
    chain.put(compact('C', 604), quote(compact('C', 604), "0.04", "0.06")); // mid 0.05
    chain.put(compact('P', 596), quote(compact('P', 596), "0.05", "0.07")); // mid 0.06
    when(provider.optionChainQuotes("XSP", TODAY)).thenReturn(chain);

    CondorLegsResult r = activity(provider).resolveCondorLegs("XSP", 0.15, 0.60);

    assertThat(r.reason()).isNull();
    assertThat(r.spot()).isCloseTo(600.0, within(1e-9));
    assertThat(r.legs())
        .extracting(CondorLeg::occSymbol, CondorLeg::side, CondorLeg::type, CondorLeg::strike)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("XSP   261005C00601000", "sell", "C", 601),
            org.assertj.core.groups.Tuple.tuple("XSP   261005P00599000", "sell", "P", 599),
            org.assertj.core.groups.Tuple.tuple("XSP   261005C00604000", "buy", "C", 604),
            org.assertj.core.groups.Tuple.tuple("XSP   261005P00596000", "buy", "P", 596));
    assertThat(r.legs().get(0).bid()).isEqualByComparingTo("0.60");
    assertThat(r.legs().get(0).ask()).isEqualByComparingTo("0.64");
    assertThat(r.netCreditMid()).isEqualByComparingTo("1.11"); // 0.62 + 0.60 - 0.05 - 0.06
  }

  @Test
  void resolveCondorLegs_missingLegQuote_failsClosed() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    Map<String, Quote> chain = parityChain(1.0, 1.0);
    chain.put(compact('C', 601), quote(compact('C', 601), "0.60", "0.64"));
    chain.put(compact('P', 599), quote(compact('P', 599), "0.58", "0.62"));
    chain.put(compact('C', 604), quote(compact('C', 604), "0.04", "0.06")); // no 596 put
    when(provider.optionChainQuotes("XSP", TODAY)).thenReturn(chain);

    CondorLegsResult r = activity(provider).resolveCondorLegs("XSP", 0.15, 0.60);

    assertThat(r.legs()).isEmpty();
    assertThat(r.netCreditMid()).isNull();
    assertThat(r.reason()).contains("XSP261005P00596000");
  }

  // --- fixtures ---

  private static CondorMarketActivityImpl activity(MarketDataProvider provider) {
    return new CondorMarketActivityImpl(provider, CLOCK);
  }

  private static String compact(char cp, int strike) {
    return compact(TODAY, cp, strike);
  }

  private static String compact(LocalDate d, char cp, int strike) {
    return String.format(
        "XSP%02d%02d%02d%c%08d",
        d.getYear() % 100, d.getMonthValue(), d.getDayOfMonth(), cp, strike * 1000);
  }

  private static Quote quote(String sym, String bid, String ask) {
    BigDecimal b = new BigDecimal(bid);
    BigDecimal a = new BigDecimal(ask);
    return new Quote(sym, b, b.add(a).divide(BigDecimal.valueOf(2)), a, null);
  }

  /** 599/600/601 with |C−P| minimal at 600 (C=atmCall, P=atmPut) so parity spot = 600. */
  private static Map<String, Quote> parityChain(double atmCall, double atmPut) {
    Map<String, Quote> chain = new HashMap<>();
    double[][] rows = {{599, 1.6, 0.6}, {600, atmCall, atmPut}, {601, 0.6, 1.6}};
    for (double[] r : rows) {
      int k = (int) r[0];
      String c = BigDecimal.valueOf(r[1]).toPlainString();
      String p = BigDecimal.valueOf(r[2]).toPlainString();
      chain.put(compact('C', k), quote(compact('C', k), c, c));
      chain.put(compact('P', k), quote(compact('P', k), p, p));
    }
    return chain;
  }

  /** 09:30..13:59 closes alternating 600.0 / 600.3 (identical every day). */
  private static double[] proxyCloses() {
    double[] c = new double[270];
    for (int i = 0; i < c.length; i++) {
      c[i] = i % 2 == 0 ? 600.0 : 600.3;
    }
    return c;
  }

  /** SPY 1-min bars from 09:30 up to (not including) {@code end} today, alternating closes. */
  private static void stubProxyBarsUntil(MarketDataProvider provider, LocalTime end) {
    when(provider.stockBars1Min(eq("SPY"), any(), any()))
        .thenAnswer(
            inv -> {
              Instant start =
                  ZonedDateTime.of(TODAY, LocalTime.of(9, 30), MarketHours.ET).toInstant();
              Instant stop = ZonedDateTime.of(TODAY, end, MarketHours.ET).toInstant();
              List<Bar> bars = new ArrayList<>();
              for (int i = 0; start.plusSeconds(60L * i).isBefore(stop); i++) {
                bars.add(new Bar(start.plusSeconds(60L * i), i % 2 == 0 ? 600.0 : 600.3));
              }
              return bars;
            });
  }

  /** Today's 599/600/601 option-bar strip, one bar at {@code t}, ATM call/put as given. */
  private static void stubOptionBarsAt(
      MarketDataProvider provider, LocalTime t, double atmCall, double atmPut) {
    Instant at = ZonedDateTime.of(TODAY, t, MarketHours.ET).toInstant();
    Map<String, List<Bar>> out = new HashMap<>();
    double[][] rows = {{599, 1.6, 0.6}, {600, atmCall, atmPut}, {601, 0.6, 1.6}};
    for (double[] r : rows) {
      out.put(compact('C', (int) r[0]), List.of(new Bar(at, r[1])));
      out.put(compact('P', (int) r[0]), List.of(new Bar(at, r[2])));
    }
    when(provider.optionBars1Min(any(), any(), any())).thenReturn(out);
  }

  /** The full 09:30..13:59 alternating-close SPY bar list starting at {@code start}. */
  private static List<Bar> proxyBarsFrom(Instant start) {
    double[] c = proxyCloses();
    List<Bar> bars = new ArrayList<>();
    for (int i = 0; i < c.length; i++) {
      bars.add(new Bar(start.plusSeconds(60L * i), c[i]));
    }
    return bars;
  }

  /** SPY bars for any day, except the overridden days. */
  private static void stubProxyBars(MarketDataProvider provider, Map<LocalDate, List<Bar>> over) {
    when(provider.stockBars1Min(eq("SPY"), any(), any()))
        .thenAnswer(
            inv -> {
              Instant start = inv.getArgument(1);
              LocalDate d = start.atZone(MarketHours.ET).toLocalDate();
              if (over.containsKey(d)) {
                return over.get(d);
              }
              double[] c = proxyCloses();
              List<Bar> bars = new ArrayList<>();
              for (int i = 0; i < c.length; i++) {
                bars.add(new Bar(start.plusSeconds(60L * i), c[i]));
              }
              return bars;
            });
  }

  /**
   * Option bars per trailing day: the 599/600/601 parity strip with the ATM straddle split evenly,
   * one bar at 13:59. Days absent from the map have no option bars.
   */
  private static void stubTrailingOptionBars(
      MarketDataProvider provider, Map<LocalDate, Double> straddleByDay) {
    when(provider.optionBars1Min(any(), any(), any()))
        .thenAnswer(
            inv -> {
              Instant start = inv.getArgument(1);
              LocalDate d = start.atZone(MarketHours.ET).toLocalDate();
              Double st = straddleByDay.get(d);
              if (st == null) {
                return Map.of();
              }
              Instant t = ZonedDateTime.of(d, LocalTime.of(13, 59), MarketHours.ET).toInstant();
              Map<String, List<Bar>> out = new HashMap<>();
              double[][] rows = {{599, 1.6, 0.6}, {600, st / 2, st / 2}, {601, 0.6, 1.6}};
              for (double[] r : rows) {
                out.put(compact(d, 'C', (int) r[0]), List.of(new Bar(t, r[1])));
                out.put(compact(d, 'P', (int) r[0]), List.of(new Bar(t, r[2])));
              }
              return out;
            });
  }

  private static Map<String, String> golden(String day) throws IOException {
    List<String[]> rows = csv("golden.csv");
    String[] header = header("golden.csv");
    for (String[] r : rows) {
      if (r[0].equals(day)) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
          m.put(header[i], r[i]);
        }
        return m;
      }
    }
    throw new IllegalArgumentException("no golden row for " + day);
  }

  private static String[] header(String name) throws IOException {
    return read(name).get(0).split(",");
  }

  private static List<String[]> csv(String name) throws IOException {
    List<String> lines = read(name);
    return lines.subList(1, lines.size()).stream().map(l -> l.split(",")).toList();
  }

  private static List<String> read(String name) throws IOException {
    try (InputStream in =
        CondorMarketActivityImplTest.class.getResourceAsStream("/condor-golden/" + name)) {
      return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
          .filter(l -> !l.isBlank())
          .toList();
    }
  }
}
