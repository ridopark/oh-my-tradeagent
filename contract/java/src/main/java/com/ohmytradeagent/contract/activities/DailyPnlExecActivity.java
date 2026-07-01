package com.ohmytradeagent.contract.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase 2 (PLAN-2026-06-30 kill-switch realized re-source): exec-owned realized-P&amp;L read routed
 * to the broker task queue ({@code broker-<broker_target>}). Owned by exec-svc — the impl runs the
 * {@code order_intent_journal} FIFO inside exec (broker truth), so a SELL that filled at the broker
 * but whose {@code PartialExitFilled} audit was lost (the Finding-1 flatten race) is STILL counted.
 * The orchestrator has no exec-DB access and the kill switches are workflows (no JDBC), so the
 * number arrives via this activity rather than the orchestrator {@code audit_log} read.
 *
 * <p>Distinct interface (does NOT extend {@link ReconciliationExecActivity}) so the exec worker can
 * register it independently and the routing stub the kill-switch workflow builds is scoped to this
 * one method. Same package as {@link ReconciliationExecActivity}.
 *
 * <p><b>Non-monotonic caveat (issue #276 §4).</b> This re-source is NOT guaranteed "only more
 * negative" than the {@code audit_log} figure. The journal is broker truth and self-heals the lost
 * PartialExitFilled gap, but it groups on {@code filled_at} (America/New_York) so a cross-day /
 * ET-boundary fill can bucket differently from the {@code audit_log} figure (which groups on {@code
 * occurred_at}); it also does NOT fix the #276 phantom-gain (a prior-day entry exited today credits
 * raw proceeds with no same-day basis). Journal is the intended source of truth going forward.
 */
@ActivityInterface
public interface DailyPnlExecActivity {

  /**
   * Realized P&amp;L in dollars for ({@code tenantId}, {@code strategyId}) on {@code tradingDay}
   * (America/New_York), FIFO-computed from FILLED {@code order_intent_journal} rows (BUY=entries,
   * SELL=exits) grouped per {@code option_symbol}: {@code (exit_price − entry_basis) × 100} per
   * matched contract. Positive = net gain, negative = net loss. Returns zero when no FILLED rows
   * match.
   */
  BigDecimal computeRealizedPnl(String tenantId, String strategyId, LocalDate tradingDay);
}
