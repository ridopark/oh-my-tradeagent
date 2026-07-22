package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Alpaca {@code GET /v2/account/activities} entry, trimmed to the fields the live-account-view
 * deposit-adjustment consumes. {@link JsonIgnoreProperties} suppresses the rest (e.g. {@code id},
 * {@code status}, {@code description}).
 *
 * <p>Only non-trade cash activities are queried ({@code CSD} deposit, {@code CSW} withdrawal,
 * {@code JNLC} cash journal). Each carries a {@code net_amount} (deposit {@code +}, withdrawal
 * {@code −}) and a date field.
 *
 * <p>{@code date} / {@code transaction_time} are kept as STRINGS and parsed to epoch by the broker.
 * Alpaca returns them as a date/ISO string, NOT an epoch number — binding a date-string to a
 * numeric field throws a Jackson parse error that fails the whole read (see the {@code
 * base_value_asof} lesson in {@link AlpacaPortfolioHistoryResponse}). Non-trade activities carry
 * {@code date} ({@code "YYYY-MM-DD"}); {@code transaction_time} is present on trade activities and
 * kept defensively.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaAccountActivity(
    @JsonProperty("activity_type") String activityType,
    @JsonProperty("net_amount") BigDecimal netAmount,
    @JsonProperty("date") String date,
    @JsonProperty("transaction_time") String transactionTime) {}
