package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry from Alpaca's {@code GET /v2/calendar} response. Each entry is a trading day; Alpaca
 * omits non-trading days (weekends, holidays) entirely, so the mere presence of a {@code date}
 * means that day trades. We consume only {@code date} (ISO {@code yyyy-MM-dd}); {@link
 * JsonIgnoreProperties} suppresses the session-time fields ({@code open}, {@code close}, {@code
 * session_open}, {@code session_close}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaCalendarDay(@JsonProperty("date") String date) {}
