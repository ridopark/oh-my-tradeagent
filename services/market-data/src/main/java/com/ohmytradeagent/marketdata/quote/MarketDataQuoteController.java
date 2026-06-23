package com.ohmytradeagent.marketdata.quote;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import java.util.LinkedHashMap;
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
