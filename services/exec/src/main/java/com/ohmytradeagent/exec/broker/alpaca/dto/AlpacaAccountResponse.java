package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Alpaca {@code /v2/account} response shape, trimmed to the fields exec-svc consumes. Alpaca
 * returns dozens of additional fields; {@link JsonIgnoreProperties} suppresses the unknowns.
 *
 * <p>{@code equity} is the account's net-liquidation value (issue #317). It is intentionally
 * distinct from {@code buyingPower} (which can be 2-4x equity on a margin account); reading the
 * wrong one would let the cap pass far larger exposure than intended.
 *
 * <p>{@code cash} is the account's cash balance (issue #323). The {@code
 * notional_cap_pct_of_equity} gate's MTM-stable denominator is the cost-basis capital base {@code
 * cash + sum_open_notional}, so the gate reads {@code cash} (not net-liq {@code equity}) for its
 * denominator — keeping numerator and denominator on the same cost basis. Like {@code equity},
 * {@code cash} is distinct from {@code buyingPower}. {@code cash} is ALSO the affordability basis
 * for the {@code pre_trade_check} gate (see below), so a margin account cannot lever past its cash.
 *
 * <p>The remaining fields back the issue #320 {@code pre_trade_check} gate:
 *
 * <ul>
 *   <li>{@code cash} — available cash; the pre-trade gate's affordability basis ({@code
 *       margin_sufficient} and the reported {@code buying_power} field derive from this). A 200
 *       that omits it fails the gate closed.
 *   <li>{@code optionsBuyingPower} / {@code buyingPower} — margin/options buying power; on a Reg-T
 *       account these are 2-4x cash. Logged for observability ONLY — they do NOT drive the gate
 *       (gating on them would lever the account past its cash).
 *   <li>{@code patternDayTrader} / {@code daytradeCount} — derive {@code pdt_status}.
 *   <li>{@code multiplier} — margin multiplier; retained for observability / potential future use.
 *       It is NOT used in the {@code margin_sufficient} computation, which derives from {@code
 *       cash}.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaAccountResponse(
    @JsonProperty("equity") BigDecimal equity,
    @JsonProperty("cash") BigDecimal cash,
    @JsonProperty("options_buying_power") BigDecimal optionsBuyingPower,
    @JsonProperty("buying_power") BigDecimal buyingPower,
    @JsonProperty("pattern_day_trader") Boolean patternDayTrader,
    @JsonProperty("daytrade_count") Integer daytradeCount,
    @JsonProperty("multiplier") BigDecimal multiplier,
    /**
     * Prior market-close net-liquidation equity (Alpaca {@code /v2/account 'last_equity'}). The
     * live intraday "today" P&L on the dashboard is {@code equity - last_equity}. Informational
     * only — NOT a credential and NOT used by any risk gate. Nullable: a response omitting it
     * simply leaves the downstream {@code today_pl} unavailable (never fabricated).
     */
    @JsonProperty("last_equity") BigDecimal lastEquity,
    /**
     * Informational brokerage account identity for the tenant dashboard (Alpaca {@code
     * account_number}). NOT a credential and NOT used by any gate.
     */
    @JsonProperty("account_number") String accountNumber) {}
