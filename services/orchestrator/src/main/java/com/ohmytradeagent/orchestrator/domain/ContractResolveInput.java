package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ContractResolveInput(
    String tenantId, String ticker, LocalDate expiry, BigDecimal strike, String right) {

  public static ContractResolveInput from(CopytradeSignalPayload p) {
    return new ContractResolveInput(
        p.getTenantId(), p.getTicker(), p.getExpiry(), p.getStrike(), p.getRight().value());
  }
}
