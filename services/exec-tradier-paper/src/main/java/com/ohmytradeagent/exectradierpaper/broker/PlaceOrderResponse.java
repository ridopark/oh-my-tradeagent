package com.ohmytradeagent.exectradierpaper.broker;

public record PlaceOrderResponse(String brokerOrderId, boolean alreadyExisted) {}
