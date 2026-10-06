package com.ohmytradeagent.marketdata.provider;

import java.time.Instant;

/** One historical 1-minute bar's close; {@code t} is the bar's START time (Alpaca convention). */
public record Bar(Instant t, double close) {}
