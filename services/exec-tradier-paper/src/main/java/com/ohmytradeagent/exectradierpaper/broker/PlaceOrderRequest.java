package com.ohmytradeagent.exectradierpaper.broker;

import java.math.BigDecimal;

public record PlaceOrderRequest(
    String clientOrderId, String optionSymbol, String side, long qty, BigDecimal limitPrice) {}
