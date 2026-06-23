package com.ohmytradeagent.marketdata.quote;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Custom actuator endpoint exposing the underlying-equity last-trade price for the tenant-dashboard
 * BFF to read (the /live underlying spot). Served at {@code /actuator/equityquote/{ticker}} once
 * added to {@code management.endpoints.web.exposure.include}.
 *
 * <p>Consistent with {@code FeedHealthEndpoint}: market-data is worker-only and exposes its small
 * HTTP surface through actuator rather than a web controller. Display-only public market data — no
 * auth, no tenant scope. Shape: {@code {ticker, price}} where {@code price} is null when the
 * snapshot is unavailable.
 */
@Component
@Endpoint(id = "equityquote")
public class EquityQuoteEndpoint {

  private final MarketDataProvider provider;

  public EquityQuoteEndpoint(MarketDataProvider provider) {
    this.provider = provider;
  }

  @ReadOperation
  public Map<String, Object> quote(@Selector String ticker) {
    BigDecimal price = provider.snapshotEquityPrice(ticker).orElse(null);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ticker", ticker);
    out.put("price", price);
    return out;
  }
}
