package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Alpaca {@code /v2/account} response shape, trimmed to the fields exec-svc consumes. Alpaca
 * returns dozens of additional fields; {@link JsonIgnoreProperties} suppresses the unknowns.
 *
 * <p>{@code equity} is the account's net-liquidation value — the figure the {@code
 * notional_cap_pct_of_equity} gate compares against (issue #317). It is intentionally distinct from
 * {@code buyingPower} (which can be 2-4x equity on a margin account); reading the wrong one would
 * let the cap pass far larger exposure than intended, so the equity gate surfaces {@code equity}
 * only.
 *
 * <p>The remaining fields back the issue #320 {@code pre_trade_check} gate:
 *
 * <ul>
 *   <li>{@code optionsBuyingPower} — options-specific buying power; the pre-trade gate prefers this
 *       and falls back to {@code buyingPower} when absent.
 *   <li>{@code buyingPower} — general buying power; fallback for the options field.
 *   <li>{@code patternDayTrader} / {@code daytradeCount} — derive {@code pdt_status}.
 *   <li>{@code multiplier} — margin multiplier; retained for observability / potential future use.
 *       It is NOT used in the current {@code margin_sufficient} computation, which derives from
 *       {@code options_buying_power} — a figure that already reflects the account multiplier.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaAccountResponse(
    @JsonProperty("equity") BigDecimal equity,
    @JsonProperty("options_buying_power") BigDecimal optionsBuyingPower,
    @JsonProperty("buying_power") BigDecimal buyingPower,
    @JsonProperty("pattern_day_trader") Boolean patternDayTrader,
    @JsonProperty("daytrade_count") Integer daytradeCount,
    @JsonProperty("multiplier") BigDecimal multiplier) {}
