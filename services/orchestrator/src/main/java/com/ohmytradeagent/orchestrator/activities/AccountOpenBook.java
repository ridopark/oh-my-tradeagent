package com.ohmytradeagent.orchestrator.activities;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 6: the tenant's whole running options book as seen by {@code AccountKillSwitchWorkflow},
 * produced by {@link AccountPnlActivities#accountOpenBook(String)}.
 *
 * <p>The activity enumerates running PositionWorkflows (the #323 tenant-wide union) and queries
 * each one's {@code positionState}; it returns the raw per-position fields the workflow needs to
 * value UNREALIZED loss ({@code (liveBid - entryPremium) * remainingQty * 100}). The live bid is
 * NOT fetched here — option quotes come from {@code GetOptionQuoteActivity} on the {@code
 * market-data} task queue, which only workflow code can dispatch, so the workflow loops {@link
 * #positions()} and fetches quotes itself.
 *
 * <p>{@code listed} / {@code valueFailures} carry the #325 fail-closed signal for the {@code
 * positionState} query layer up to the workflow, which applies the same relative-{@code >50%} /
 * small-book bound to BOTH the positionState failures AND the option-quote failures so a correlated
 * Visibility/market-data outage cannot silently under-count the loss (fail-OPEN).
 *
 * @param positions one entry per Running PositionWorkflow that answered its state query with a live
 *     position (remainingQty > 0, non-blank symbol, non-null entryPremium)
 * @param listed number of Running PositionWorkflows the Visibility union returned (the denominator)
 * @param valueFailures number that failed to answer their {@code positionState} query (a
 *     degradation signal — distinct from a legitimate just-closed/blank skip)
 */
public record AccountOpenBook(
    List<OpenPositionValuation> positions, int listed, int valueFailures) {

  /**
   * One open position's raw valuation inputs.
   *
   * @param contractSymbol OCC option symbol (drives the GetOptionQuote request)
   * @param entryPremium per-contract fill cost basis, dollars
   * @param remainingQty contracts still open
   */
  public record OpenPositionValuation(
      String contractSymbol, BigDecimal entryPremium, long remainingQty) {}
}
