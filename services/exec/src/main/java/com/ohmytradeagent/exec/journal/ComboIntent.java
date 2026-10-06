package com.ohmytradeagent.exec.journal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Gated-condor Phase 3: one multi-leg (mleg) order attempt to journal — a single combo row in
 * {@code order_intent_journal} plus one {@code order_intent_journal_leg} row per leg carrying the
 * NBBO captured at submit. {@code netCredit} is the positive net credit asked (the combo row's
 * {@code limit_price}).
 */
public record ComboIntent(
    String intentKey,
    String signalId,
    String tenantId,
    String strategyId,
    String brokerTarget,
    long qty,
    BigDecimal netCredit,
    List<Leg> legs,
    OffsetDateTime recordedAt) {

  public ComboIntent {
    legs = List.copyOf(legs);
  }

  public record Leg(
      String optionSymbol,
      String side,
      long ratioQty,
      BigDecimal nbboBid,
      BigDecimal nbboAsk,
      BigDecimal nbboMid) {}
}
