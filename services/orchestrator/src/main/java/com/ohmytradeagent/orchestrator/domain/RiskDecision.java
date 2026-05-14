package com.ohmytradeagent.orchestrator.domain;

public record RiskDecision(boolean allowed, RejectionReason reason, String detail) {

  public static RiskDecision approved() {
    return new RiskDecision(true, null, null);
  }

  public static RiskDecision rejected(RejectionReason reason, String detail) {
    return new RiskDecision(false, reason, detail);
  }
}
