package com.ohmytradeagent.exec.broker;

public record CancelResponse(boolean cancelled, String brokerReason) {

  public static CancelResponse ok() {
    return new CancelResponse(true, null);
  }

  public static CancelResponse failed(String brokerReason) {
    return new CancelResponse(false, brokerReason);
  }
}
