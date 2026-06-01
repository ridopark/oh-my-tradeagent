package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Alpaca {@code /v2/account} response shape, trimmed to the field {@code
 * AlpacaPaperBroker.getAccountEquity} consumes. Alpaca returns dozens of additional fields (cash,
 * buying_power, portfolio_value, daytrade_count, etc.); {@link JsonIgnoreProperties} suppresses the
 * unknowns.
 *
 * <p>Issue #317. {@code equity} is the account's net-liquidation value — the figure the {@code
 * notional_cap_pct_of_equity} gate compares against. It is intentionally distinct from {@code
 * buying_power} (which can be 2-4x equity on a margin account); reading the wrong one would let the
 * cap pass far larger exposure than intended, so we surface {@code equity} only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaAccountResponse(@JsonProperty("equity") BigDecimal equity) {}
