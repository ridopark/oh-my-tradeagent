package com.ohmytradeagent.exec.broker;

public record PlaceOrderResponse(String brokerOrderId, boolean alreadyExisted) {}
