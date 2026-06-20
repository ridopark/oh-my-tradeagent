package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Exec-side impl of the {@link MarketCalendarActivity} contract. Thin wrapper around {@link
 * OptionsBroker#tradingDays} so the network call to Alpaca's {@code GET /v2/calendar} reuses the
 * same per-account trading-API client (host + {@code APCA-*} auth) the order path already uses.
 *
 * <p>Account-level read: the calendar is per-deployment broker truth, not per-tenant, so the broker
 * resolves on {@link BrokerClientRegistry#ACCOUNT_LEVEL} with the {@code alpaca} provider (this
 * worker only serves alpaca). Stateless and safe under Temporal Activity retry — a transient broker
 * error maps through the adapter's {@code mapError} so the workflow retries rather than silently
 * resolving an expiry against an empty calendar.
 */
@Component
public class MarketCalendarExecActivityImpl implements MarketCalendarActivity {

  private static final String PROVIDER = "alpaca";

  private final BrokerClientRegistry brokerRegistry;

  public MarketCalendarExecActivityImpl(BrokerClientRegistry brokerRegistry) {
    this.brokerRegistry = brokerRegistry;
  }

  @Override
  public List<LocalDate> tradingDays(LocalDate start, LocalDate end) {
    OptionsBroker broker = brokerRegistry.brokerFor(BrokerClientRegistry.ACCOUNT_LEVEL, PROVIDER);
    return broker.tradingDays(start, end);
  }
}
