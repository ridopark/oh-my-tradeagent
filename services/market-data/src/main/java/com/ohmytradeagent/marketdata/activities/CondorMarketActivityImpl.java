package com.ohmytradeagent.marketdata.activities;

import static com.ohmytradeagent.marketdata.MarketHours.ET;

import com.ohmytradeagent.contract.activities.CondorMarketActivity;
import com.ohmytradeagent.marketdata.provider.Bar;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gated-condor (PLAN-2026-10-05 Phase 2) worker-side implementation of {@link
 * CondorMarketActivity}, registered on the {@code market-data} task queue alongside {@link
 * GetOptionQuoteActivityImpl}.
 *
 * <p>Mirrors the research (docs/plans/experiments/0dte-spx-2026-10): the richness formula is {@code
 * t4_iv_gate.py}'s, spot is {@code spx_level.py}'s put-call parity (S = K + C − P at the strike
 * minimizing |C − P|), condor strikes are {@code sell_afternoon.legs_for}'s S2 rule. Today's
 * straddle and parity use live NBBO mids from the chain; the trailing distribution is recomputed
 * from option BARS (quotes don't backfill) on the same parity basis. σ always comes from the proxy
 * equity's 1-min bars ({@code XSP → SPY}; any other underlying is its own proxy, as in the
 * research's ETF runs). Bars are taken strictly before {@code entryEt} on every day so today and
 * the trailing days share one live-achievable basis.
 */
@Component
public class CondorMarketActivityImpl implements CondorMarketActivity {

  /** √(2/π): E|X| of a unit normal, the research's straddle ≈ 0.7979·σ·√T·S approximation. */
  static final double SQRT_2_OVER_PI = 0.7979;

  /** The research skipped a day with fewer than 200 bars before the entry minute. */
  static final int MIN_SIGMA_BARS = 200;

  /** The research dropped a straddle whose last print was more than 15 minutes old. */
  static final Duration MAX_BAR_STALENESS = Duration.ofMinutes(15);

  /** Option-bar strike strip searched around the proxy's last close on a trailing day. */
  static final int TRAILING_STRIKE_HALF_WIDTH = 8;

  private static final Map<String, String> SIGMA_PROXY = Map.of("XSP", "SPY");
  private static final LocalTime OPEN = LocalTime.of(9, 30);
  private static final LocalTime CLOSE = LocalTime.of(16, 0);

  private final MarketDataProvider provider;
  private final Clock clock;

  @Autowired
  public CondorMarketActivityImpl(MarketDataProvider provider) {
    this(provider, Clock.system(ET));
  }

  /** Visible for tests: inject a fixed clock. */
  CondorMarketActivityImpl(MarketDataProvider provider, Clock clock) {
    this.provider = provider;
    this.clock = clock;
  }

  @Override
  public RichnessResult evaluateRichness(String underlying, String entryEt, int lookbackDays) {
    LocalTime entry = LocalTime.parse(entryEt);
    LocalDate today = LocalDate.now(clock);
    String proxy = SIGMA_PROXY.getOrDefault(underlying, underlying);
    long minutesToClose = Duration.between(entry, CLOSE).toMinutes();

    TreeMap<Integer, double[]> chain = new TreeMap<>();
    provider
        .optionChainQuotes(underlying, today)
        .forEach((sym, q) -> put(chain, sym, q.mid().doubleValue()));
    double[] closes =
        closesBefore(
            provider.stockBars1Min(proxy, at(today, OPEN), at(today, entry)), at(today, entry));
    Double richness = richnessOf(chain, closes, minutesToClose);
    if (richness == null) {
      return new RichnessResult(
          null, null, null, 0, false, "today: no parity strip / ATM straddle / proxy bars");
    }

    List<Double> trailing = new ArrayList<>();
    LocalDate d = today;
    for (int scanned = 0; trailing.size() < lookbackDays && scanned < 2 * lookbackDays; ) {
      d = d.minusDays(1);
      if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
        continue;
      }
      scanned++;
      Double r = trailingRichness(underlying, proxy, d, entry, minutesToClose);
      if (r != null) {
        trailing.add(r);
      }
    }
    if (trailing.isEmpty()) {
      return new RichnessResult(richness, null, null, 0, false, "no trailing history");
    }
    double[] sorted = trailing.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    double median = median(sorted);
    double quantile =
        Arrays.stream(sorted).filter(v -> v <= richness).count() / (double) sorted.length;
    if (sorted.length < lookbackDays) {
      return new RichnessResult(
          richness,
          median,
          quantile,
          sorted.length,
          false,
          "insufficient trailing history " + sorted.length + "/" + lookbackDays);
    }
    return new RichnessResult(richness, median, quantile, sorted.length, richness >= median, null);
  }

  @Override
  public CondorLegsResult resolveCondorLegs(
      String underlying, double shortOffsetPct, double wingOffsetPct) {
    LocalDate today = LocalDate.now(clock);
    Map<String, Quote> quotes = provider.optionChainQuotes(underlying, today);
    TreeMap<Integer, double[]> chain = new TreeMap<>();
    quotes.forEach((sym, q) -> put(chain, sym, q.mid().doubleValue()));
    double spot = paritySpot(chain);
    if (Double.isNaN(spot)) {
      return new CondorLegsResult(null, List.of(), null, "no parity strip in today's chain");
    }
    int[] k = condorStrikes(spot, shortOffsetPct, wingOffsetPct);
    char[] types = {'C', 'P', 'C', 'P'};
    List<CondorLeg> legs = new ArrayList<>();
    BigDecimal credit = BigDecimal.ZERO;
    for (int i = 0; i < 4; i++) {
      String compact = compactOcc(underlying, today, types[i], k[i]);
      Quote q = quotes.get(compact);
      if (q == null) {
        return new CondorLegsResult(spot, List.of(), null, "no quote for " + compact);
      }
      boolean isShort = i < 2;
      legs.add(
          new CondorLeg(
              String.format(
                  Locale.ROOT, "%-6s%s", underlying, compact.substring(underlying.length())),
              isShort ? "sell" : "buy",
              String.valueOf(types[i]),
              k[i],
              q.bid(),
              q.ask()));
      credit = isShort ? credit.add(q.mid()) : credit.subtract(q.mid());
    }
    return new CondorLegsResult(spot, legs, credit, null);
  }

  private Double trailingRichness(
      String underlying, String proxy, LocalDate d, LocalTime entry, long minutesToClose) {
    Instant open = at(d, OPEN);
    Instant entryAt = at(d, entry);
    List<Bar> proxyBars = provider.stockBars1Min(proxy, open, entryAt);
    // A holiday has no bars; a half day has none in the minute before entry.
    if (proxyBars.stream().noneMatch(b -> b.t().equals(entryAt.minusSeconds(60)))) {
      return null;
    }
    double[] closes = closesBefore(proxyBars, entryAt);
    int center = (int) Math.rint(closes[closes.length - 1]);
    List<String> syms = new ArrayList<>();
    for (int k = center - TRAILING_STRIKE_HALF_WIDTH;
        k <= center + TRAILING_STRIKE_HALF_WIDTH;
        k++) {
      syms.add(compactOcc(underlying, d, 'C', k));
      syms.add(compactOcc(underlying, d, 'P', k));
    }
    TreeMap<Integer, double[]> chain = new TreeMap<>();
    provider
        .optionBars1Min(syms, open, entryAt)
        .forEach(
            (sym, bars) ->
                bars.stream()
                    .filter(b -> b.t().isBefore(entryAt))
                    .reduce((a, b) -> b)
                    .filter(b -> !b.t().isBefore(entryAt.minus(MAX_BAR_STALENESS)))
                    .ifPresent(b -> put(chain, sym, b.close())));
    return richnessOf(chain, closes, minutesToClose);
  }

  /** Richness from a per-strike {C, P} strip and the proxy closes, or null when not computable. */
  private static Double richnessOf(
      TreeMap<Integer, double[]> chain, double[] closes, long minutesToClose) {
    double spot = paritySpot(chain);
    if (Double.isNaN(spot) || closes.length < MIN_SIGMA_BARS) {
      return null;
    }
    double[] atm = chain.get((int) Math.rint(spot)); // Math.rint = Python round(): half-even
    if (atm == null || Double.isNaN(atm[0]) || Double.isNaN(atm[1])) {
      return null;
    }
    double r = richness(atm[0] + atm[1], closes, minutesToClose, spot);
    return Double.isFinite(r) ? r : null;
  }

  /**
   * t4_iv_gate.py: straddle / (0.7979 · σ · √minutes · spot), σ the SAMPLE std (pandas ddof=1) of
   * 1-min log returns of {@code closes}.
   */
  static double richness(double straddle, double[] closes, long minutesToClose, double spot) {
    int n = closes.length - 1;
    double[] ret = new double[n];
    double mean = 0;
    for (int i = 0; i < n; i++) {
      ret[i] = Math.log(closes[i + 1]) - Math.log(closes[i]);
      mean += ret[i];
    }
    mean /= n;
    double ss = 0;
    for (double r : ret) {
      ss += (r - mean) * (r - mean);
    }
    double sigma = Math.sqrt(ss / (n - 1));
    return straddle / (SQRT_2_OVER_PI * sigma * Math.sqrt(minutesToClose) * spot);
  }

  /**
   * spx_level.py: S = K + C − P at the strike minimizing |C − P| (lowest strike on a tie, as pandas
   * idxmin over the strike-sorted pivot). NaN when no strike has both sides.
   */
  static double paritySpot(TreeMap<Integer, double[]> callPutByStrike) {
    double best = Double.NaN;
    double bestGap = Double.POSITIVE_INFINITY;
    for (Map.Entry<Integer, double[]> e : callPutByStrike.entrySet()) {
      double c = e.getValue()[0];
      double p = e.getValue()[1];
      if (Double.isNaN(c) || Double.isNaN(p)) {
        continue;
      }
      double gap = Math.abs(c - p);
      if (gap < bestGap) {
        bestGap = gap;
        best = e.getKey() + c - p;
      }
    }
    return best;
  }

  /**
   * sell_afternoon.legs_for S2 on the $1 grid: {short C, short P, long C, long P}, each rounded
   * OUTWARD (call up, put down); a wing that collapses onto its short moves one strike out.
   */
  static int[] condorStrikes(double spot, double shortOffsetPct, double wingOffsetPct) {
    int sc = (int) Math.ceil(spot * (1 + shortOffsetPct / 100) - 1e-9);
    int sp = (int) Math.floor(spot * (1 - shortOffsetPct / 100) + 1e-9);
    int lc = (int) Math.ceil(spot * (1 + wingOffsetPct / 100) - 1e-9);
    int lp = (int) Math.floor(spot * (1 - wingOffsetPct / 100) + 1e-9);
    return new int[] {sc, sp, lc <= sc ? sc + 1 : lc, lp >= sp ? sp - 1 : lp};
  }

  private static double median(double[] sorted) {
    int n = sorted.length;
    return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  }

  private static double[] closesBefore(List<Bar> bars, Instant entryAt) {
    return bars.stream().filter(b -> b.t().isBefore(entryAt)).mapToDouble(Bar::close).toArray();
  }

  /** Files a compact OCC's price into the {C, P} strip; non-integer strikes are ignored. */
  private static void put(TreeMap<Integer, double[]> chain, String compactOcc, double price) {
    String tail = compactOcc.replace(" ", "");
    long strikeMillis = Long.parseLong(tail.substring(tail.length() - 8));
    if (strikeMillis % 1000 != 0) {
      return;
    }
    double[] cp =
        chain.computeIfAbsent(
            (int) (strikeMillis / 1000), k -> new double[] {Double.NaN, Double.NaN});
    cp[tail.charAt(tail.length() - 9) == 'C' ? 0 : 1] = price;
  }

  private static String compactOcc(String root, LocalDate exp, char cp, int strike) {
    return String.format(
        Locale.ROOT,
        "%s%02d%02d%02d%c%08d",
        root,
        exp.getYear() % 100,
        exp.getMonthValue(),
        exp.getDayOfMonth(),
        cp,
        strike * 1000L);
  }

  private static Instant at(LocalDate d, LocalTime t) {
    return ZonedDateTime.of(d, t, ET).toInstant();
  }
}
