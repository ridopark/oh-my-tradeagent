package com.ohmytradeagent.marketdata.quote;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Custom actuator endpoint exposing an option contract's snapshot NBBO for the tenant-dashboard BFF
 * (the /live indicative option premium for an un-fired watchlist leg). Served at {@code
 * /actuator/optionquote/{occ}} once added to {@code management.endpoints.web.exposure.include}.
 *
 * <p>The {@code occ} selector is the COMPACT OCC (no space padding) since path segments cannot
 * carry spaces; {@code snapshotQuote} strips padding either way. Consistent with {@code
 * FeedHealthEndpoint} / {@code EquityQuoteEndpoint}: display-only public market data, no auth, no
 * tenant scope. Shape: {@code {occ, bid, mid, ask}} with null fields when the snapshot is
 * unavailable.
 */
@Component
@Endpoint(id = "optionquote")
public class OptionQuoteEndpoint {

  private final MarketDataProvider provider;

  public OptionQuoteEndpoint(MarketDataProvider provider) {
    this.provider = provider;
  }

  @ReadOperation
  public Map<String, Object> quote(@Selector String occ) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("occ", occ);
    var quote = provider.snapshotQuote(occ);
    out.put("bid", quote.map(q -> (Object) q.bid()).orElse(null));
    out.put("mid", quote.map(q -> (Object) q.mid()).orElse(null));
    out.put("ask", quote.map(q -> (Object) q.ask()).orElse(null));
    return out;
  }
}
