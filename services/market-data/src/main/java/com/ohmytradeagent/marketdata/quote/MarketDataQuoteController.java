package com.ohmytradeagent.marketdata.quote;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only quote surface the tenant-dashboard BFF polls for the /live underlying spot + indicative
 * option premium. A plain MVC controller (not an actuator {@code @Selector} endpoint) on purpose:
 * actuator selector binding needs runtime parameter names (the {@code -parameters} javac flag,
 * which the production image build does not set), so a selector endpoint crashes the app at
 * startup. {@code @PathVariable("...")} with an EXPLICIT name has no such dependency.
 *
 * <p>Display-only public market data — no auth, no tenant scope (consistent with the actuator
 * {@code FeedHealthEndpoint}; market-data is a single-replica cluster-internal service). Prices are
 * null when the snapshot is unavailable.
 */
@RestController
public class MarketDataQuoteController {

  private final MarketDataProvider provider;

  public MarketDataQuoteController(MarketDataProvider provider) {
    this.provider = provider;
  }

  /** {@code {ticker, price}} — underlying last-trade price (null when unavailable). */
  @GetMapping("/md/equity/{ticker}")
  public Map<String, Object> equity(@PathVariable("ticker") String ticker) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ticker", ticker);
    out.put("price", provider.snapshotEquityPrice(ticker).orElse(null));
    return out;
  }

  /**
   * {@code {now, subscriptions:[{occ, subscribers, poll_ok_count, last_poll_ok_at, last_emit_at,
   * consecutive_failures}]}} — per-contract premium-poll liveness (#717).
   *
   * <p>{@code now} is this service's wall clock, returned so the caller can age the stamps against
   * the SAME clock that wrote them instead of against its own. market-data and the BFF are separate
   * pods; a couple of seconds of drift between them is the difference between "alive" and "dead" at
   * a 500ms poll, so the subtraction has to happen in one frame of reference.
   *
   * <p>A contract absent from the list has NO live subscription — that is the #717 signal, and it
   * is a positive statement, not a missing-data case. Timestamps are ISO-8601 UTC strings rather
   * than epoch millis so a human reading the raw endpoint can diagnose it unaided.
   */
  @GetMapping("/md/premium-subscriptions")
  public Map<String, Object> premiumSubscriptions() {
    List<Map<String, Object>> rows = new ArrayList<>();
    provider.premiumFeedStatus().values().stream()
        .sorted(Comparator.comparing(PremiumFeedStatus::occSymbol))
        .forEach(
            st -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("occ", st.occSymbol());
              row.put("subscribers", st.subscribers());
              row.put("poll_ok_count", st.pollOkCount());
              row.put(
                  "last_poll_ok_at",
                  st.lastPollOkAt() == null ? null : st.lastPollOkAt().toString());
              row.put("last_emit_at", st.lastEmitAt() == null ? null : st.lastEmitAt().toString());
              row.put("consecutive_failures", st.consecutiveFailures());
              rows.add(row);
            });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("now", Instant.now().toString());
    out.put("subscriptions", rows);
    return out;
  }

  /**
   * {@code {occ, iv, delta, gamma, theta, vega}} — the option's implied volatility + greeks (#783;
   * null fields when unavailable). Read once per entry observation by the orchestrator's
   * trade-context recorder — these are the fields no historical API can backfill.
   */
  @GetMapping("/md/option/{occ}/greeks")
  public Map<String, Object> optionGreeks(@PathVariable("occ") String occ) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("occ", occ);
    var greeks = provider.snapshotGreeks(occ);
    out.put("iv", greeks.map(g -> (Object) g.impliedVolatility()).orElse(null));
    out.put("delta", greeks.map(g -> (Object) g.delta()).orElse(null));
    out.put("gamma", greeks.map(g -> (Object) g.gamma()).orElse(null));
    out.put("theta", greeks.map(g -> (Object) g.theta()).orElse(null));
    out.put("vega", greeks.map(g -> (Object) g.vega()).orElse(null));
    return out;
  }

  /** {@code {occ, bid, mid, ask}} — option NBBO (null fields when unavailable). */
  @GetMapping("/md/option/{occ}")
  public Map<String, Object> option(@PathVariable("occ") String occ) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("occ", occ);
    var quote = provider.snapshotQuote(occ);
    out.put("bid", quote.map(q -> (Object) q.bid()).orElse(null));
    out.put("mid", quote.map(q -> (Object) q.mid()).orElse(null));
    out.put("ask", quote.map(q -> (Object) q.ask()).orElse(null));
    return out;
  }
}
