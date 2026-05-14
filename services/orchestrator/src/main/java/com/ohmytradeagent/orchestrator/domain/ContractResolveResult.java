package com.ohmytradeagent.orchestrator.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ContractResolveResult(
    String optionSymbol,
    String ticker,
    LocalDate expiry,
    BigDecimal strike,
    String right,
    String source) {

  public static final String SOURCE_GENERATED = "GENERATED";
  public static final String SOURCE_BROKER = "BROKER";
}
