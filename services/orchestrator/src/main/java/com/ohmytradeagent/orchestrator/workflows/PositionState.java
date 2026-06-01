package com.ohmytradeagent.orchestrator.workflows;

import java.math.BigDecimal;

/**
 * Issue #318: synchronous, non-mutating query payload exposing the open-position state a
 * portfolio-level risk gate needs to value a running {@link PositionWorkflow}. Returned by {@code
 * positionState()} — observation-only; never mutated by the query.
 *
 * <p>This is the minimal subset of the broader {@code position_state} query sketched in {@code
 * docs/plans/PLAN.md:145} — only the fields the Visibility-backed {@link
 * com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot} consumes to compute {@code
 * openNotional}. As a query it does not append to workflow history, so it serves on in-flight
 * workflows without {@code Workflow.getVersion} gating.
 *
 * @param contractSymbol OCC option symbol of the open position (e.g. {@code NVDA250516C00140000})
 * @param remainingQty contracts still open after any partial exits (the live position size)
 * @param entryPremium fill premium per contract, dollars (broker-fill cost basis, not author price)
 */
public record PositionState(String contractSymbol, long remainingQty, BigDecimal entryPremium) {}
