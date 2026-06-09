package com.ohmytradeagent.marketdata.activities;

import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Plan-2A R-AA-2 worker-side implementation of {@link GetOptionQuoteActivity}. A pure read: maps
 * the Spring-wired {@link MarketDataProvider}'s internal {@link Quote} record
 * (bid/mid/ask/retrievedAt) into the contract DTO so bounded scheduled-flatten sells can anchor on
 * a live bid.
 *
 * <p>{@code getOptionQuote} never throws: an empty provider snapshot returns {@code UNAVAILABLE}
 * and a source-side exception returns {@code FAILED} (with the message), so the caller can fall
 * back to a marketable exit and emit a loud availability audit instead of going into Temporal
 * retry. Registered on the same {@code market-data} task queue as {@link SubscribePremiumActivity}.
 */
@Component
public class GetOptionQuoteActivityImpl implements GetOptionQuoteActivity {

  private static final Logger log = LoggerFactory.getLogger(GetOptionQuoteActivityImpl.class);

  private final MarketDataProvider provider;

  public GetOptionQuoteActivityImpl(MarketDataProvider provider) {
    this.provider = provider;
  }

  @Override
  public OptionQuoteResult getOptionQuote(GetOptionQuoteRequest req) {
    OptionQuoteResult result = new OptionQuoteResult();
    result.setSchemaVersion(1L);
    result.setContractSymbol(req.getContractSymbol());
    result.setRetrievedAt(OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    try {
      Optional<Quote> snapshot = provider.snapshotQuote(req.getContractSymbol());
      if (snapshot.isEmpty()) {
        result.setStatus(OptionQuoteResult.Status.UNAVAILABLE);
        return result;
      }
      Quote q = snapshot.get();
      result.setBid(q.bid());
      result.setMid(q.mid());
      result.setAsk(q.ask());
      if (q.retrievedAt() != null) {
        result.setRetrievedAt(q.retrievedAt());
      }
      result.setStatus(OptionQuoteResult.Status.OK);
      return result;
    } catch (RuntimeException e) {
      log.warn(
          "getOptionQuote failed for tenant={} strategy={} symbol={}: {}",
          req.getTenantId(),
          req.getStrategyId(),
          req.getContractSymbol(),
          e.getMessage());
      result.setStatus(OptionQuoteResult.Status.FAILED);
      result.setError(e.getMessage());
      return result;
    }
  }
}
