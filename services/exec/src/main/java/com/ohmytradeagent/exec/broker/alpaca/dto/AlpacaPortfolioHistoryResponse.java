package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Alpaca {@code GET /v2/account/portfolio/history} response shape, trimmed to the fields the
 * live-account-view consumes. {@link JsonIgnoreProperties} suppresses the rest (e.g. {@code
 * intraday_reporting}).
 *
 * <p>Parallel arrays indexed by {@code timestamp} (epoch seconds): {@code equity} (the chart line),
 * {@code profit_loss}, {@code profit_loss_pct}. {@code base_value} is the baseline (range start),
 * {@code timeframe} echoes the resolved timeframe. Read-only (no order path).
 *
 * <p>{@code base_value_asof} is intentionally NOT mapped: Alpaca's portfolio-history engine returns
 * it as a date STRING (e.g. {@code "2026-06-17"}), not an epoch Long, so binding it to a numeric
 * field threw a Jackson parse error that failed the whole read. It is unused by the UI, so {@link
 * JsonIgnoreProperties}{@code (ignoreUnknown=true)} drops it and the mapped {@code baseValueAsof}
 * stays null. Surface it as a String end-to-end if a future UI needs the as-of date.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaPortfolioHistoryResponse(
    @JsonProperty("timestamp") List<Long> timestamp,
    @JsonProperty("equity") List<BigDecimal> equity,
    @JsonProperty("profit_loss") List<BigDecimal> profitLoss,
    @JsonProperty("profit_loss_pct") List<BigDecimal> profitLossPct,
    @JsonProperty("base_value") BigDecimal baseValue,
    @JsonProperty("timeframe") String timeframe) {}
