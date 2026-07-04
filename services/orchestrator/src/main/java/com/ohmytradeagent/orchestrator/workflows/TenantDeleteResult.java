package com.ohmytradeagent.orchestrator.workflows;

/**
 * Result of {@link TenantDeleteWorkflow#deleteTenant} (PLAN-2026-07-03, Phase 4 — the real-money
 * safety gate moved INTO the orchestrator workflow because only orchestrator-core can reach the
 * broker / exec journal via activities). The workflow evaluates the two orchestrator-reachable
 * live-safety gates FIRST — P4 {@code BROKER_NOT_FLAT} and P5 {@code HAS_TRADE_HISTORY} — and only
 * runs the teardown (schedule reap → kill-switch terminate → config delete) when BOTH pass.
 *
 * <ul>
 *   <li>{@link Status#BLOCKED} — a gate refused (or a broker/journal read faulted → fail-closed);
 *       {@link #getBlockedBy()} names which. ZERO teardown ran and {@link #getDeletedConfigRows()}
 *       is {@code 0}.
 *   <li>{@link Status#COMPLETED} — both gates passed and the teardown ran; {@link
 *       #getDeletedConfigRows()} is the rows deleted from {@code strategy_config} (0 when already
 *       absent — idempotent).
 * </ul>
 *
 * <p>A plain POJO (public no-arg ctor + getters/setters) so the Temporal Jackson data converter can
 * round-trip it across the client boundary — the api-gateway (Phase 4 controller) reads it back.
 */
public class TenantDeleteResult {

  /** Terminal disposition of the teardown. */
  public enum Status {
    /** Both live-safety gates passed and the teardown ran. */
    COMPLETED,
    /** A live-safety gate refused (or a read faulted). No teardown ran. */
    BLOCKED
  }

  /** Which orchestrator-reachable gate refused the delete. */
  public enum BlockReason {
    /** P4: broker reports a non-flat book (open positions and/or open/pending orders). */
    BROKER_NOT_FLAT,
    /** P5: {@code order_intent_journal} is non-empty (the tenant placed an order at some point). */
    HAS_TRADE_HISTORY
  }

  private Status status;
  private BlockReason blockedBy;
  private int deletedConfigRows;

  public TenantDeleteResult() {}

  private TenantDeleteResult(Status status, BlockReason blockedBy, int deletedConfigRows) {
    this.status = status;
    this.blockedBy = blockedBy;
    this.deletedConfigRows = deletedConfigRows;
  }

  /**
   * A BLOCKED result naming the gate that refused; carries no deleted rows (teardown never ran).
   */
  public static TenantDeleteResult blocked(BlockReason reason) {
    return new TenantDeleteResult(Status.BLOCKED, reason, 0);
  }

  /** A COMPLETED result carrying the {@code strategy_config} rows-deleted count from step (c). */
  public static TenantDeleteResult completed(int deletedConfigRows) {
    return new TenantDeleteResult(Status.COMPLETED, null, deletedConfigRows);
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public BlockReason getBlockedBy() {
    return blockedBy;
  }

  public void setBlockedBy(BlockReason blockedBy) {
    this.blockedBy = blockedBy;
  }

  public int getDeletedConfigRows() {
    return deletedConfigRows;
  }

  public void setDeletedConfigRows(int deletedConfigRows) {
    this.deletedConfigRows = deletedConfigRows;
  }
}
