package com.ohmytradeagent.orchestrator.workflows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Issue #318: synchronous, non-mutating query payload exposing the open-position state a
 * portfolio-level risk gate needs to value a running {@link PositionWorkflow}. Returned by {@code
 * positionState()} — observation-only; never mutated by the query.
 *
 * <p>This is the minimal subset of the broader {@code position_state} query sketched in {@code
 * docs/plans/PLAN.md:145} — only the fields the Visibility-backed {@link
 * com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot} consumes to compute {@code
 * openNotional}, plus the two edited-signal-supersede (F1) guardrail fields {@link #entryAt} and
 * {@link #partialExited}. As a query it does not append to workflow history, so it serves on
 * in-flight workflows without {@code Workflow.getVersion} gating.
 *
 * @param contractSymbol OCC option symbol of the open position (e.g. {@code NVDA250516C00140000})
 * @param remainingQty contracts still open after any partial exits (the live position size)
 * @param entryPremium fill premium per contract, dollars (broker-fill cost basis, not author price)
 * @param entryAt the deterministic {@code Workflow.currentTimeMillis()} instant at which the
 *     position was confirmed (first entry fill latched {@code positionConfirmed}). {@code null} on
 *     legacy/pre-fill states. The edited-signal supersede (F1) uses this to enforce the 120s
 *     correction-window guardrail authoritatively from the prior leg's actual entry time.
 * @param partialExited {@code true} once any partial-exit fill has decremented {@code remainingQty}
 *     (set in {@code emitExitFill}). The supersede guardrail refuses to auto-cancel a leg that has
 *     already partially exited — only an untouched just-filled leg may be superseded.
 */
public record PositionState(
    String contractSymbol,
    long remainingQty,
    BigDecimal entryPremium,
    OffsetDateTime entryAt,
    boolean partialExited) {

  /**
   * Back-compat 3-arg form for the many call sites (and test fixtures) that predate the F1
   * supersede fields. Delegates to the canonical constructor with no entry timestamp and {@code
   * partialExited=false} — the conservative defaults that make the supersede guardrails fail-safe
   * (a null {@code entryAt} can never satisfy the in-window check).
   */
  public PositionState(String contractSymbol, long remainingQty, BigDecimal entryPremium) {
    this(contractSymbol, remainingQty, entryPremium, null, false);
  }
}
