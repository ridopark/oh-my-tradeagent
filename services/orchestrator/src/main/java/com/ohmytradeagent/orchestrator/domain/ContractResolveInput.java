package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.CopytradeDeriskPayload;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ContractResolveInput(
    String tenantId, String ticker, LocalDate expiry, BigDecimal strike, String right) {

  public static ContractResolveInput from(CopytradeSignalPayload p) {
    return new ContractResolveInput(
        p.getTenantId(), p.getTicker(), p.getExpiry(), p.getStrike(), p.getRight().value());
  }

  /**
   * PLAN-2026-08-04-copytrade-derisk-followup-cue: resolve the OCC for a de-risk cue from the
   * attributed target BTO tuple carried on the payload — same (ticker, expiry, strike, right) shape
   * as the BTO/STC path, so {@code ContractActivities.resolve} composes the identical OCC.
   */
  public static ContractResolveInput from(CopytradeDeriskPayload p) {
    return new ContractResolveInput(
        p.getTenantId(), p.getTicker(), p.getExpiry(), p.getStrike(), p.getRight().value());
  }
}
