package com.ohmytradeagent.exec.journal;

import com.ohmytradeagent.contract.OrderIntent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable journal of order intents — the middle layer of the plan's 3-layer idempotency model (line
 * 362). All writes go through this interface so the implementation choice (jOOQ today, alternative
 * store later) stays swappable.
 */
public interface OrderIntentJournal {

  /**
   * Idempotent intent record. Implementations use {@code INSERT ... ON CONFLICT (intent_key) DO
   * NOTHING} so two concurrent Activity attempts converge on a single row. Returns true iff this
   * call performed the INSERT (false if a prior call already recorded the same intent_key).
   */
  boolean upsertIntent(OrderIntent intent);

  Optional<JournaledOrder> findByIntentKey(String intentKey);

  /**
   * List non-terminal (RECORDED + SUBMITTED) journal entries scoped to one (tenant, strategy).
   * Powers Phase 5 reconciliation.
   */
  List<JournaledOrder> listOpenByTenantStrategy(String tenantId, String strategyId);

  /**
   * Issue #165 Phase 3: return at most one row for the most recent FILLED entry on this {@code
   * (tenant, strategy, option_symbol)} tuple, ordered by {@code filled_at DESC}. Used by
   * reconciliation to map a broker-held position back to the {@code entry_signal_id} that
   * determines the expected {@code PositionWorkflow} id. Returns {@link Optional#empty()} when no
   * FILLED row exists for that OCC — recon treats this as a stronger orphan signal ({@code
   * journal_status=missing}). Backed by the V3 partial index {@code
   * order_intent_journal_filled_at_idx} so this is a constant-time lookup.
   */
  Optional<JournaledOrder> findLatestFilledByOcc(String tenantId, String strategyId, String occ);

  /**
   * Conditional state-machine transition: flips RECORDED → SUBMITTED only if the current state is
   * still RECORDED. Returns true iff the row was updated; a false return means another concurrent
   * attempt already set SUBMITTED (or the row is in a terminal state) — caller short-circuits.
   */
  boolean markSubmittedIfRecorded(String intentKey, String brokerOrderId);

  /**
   * Records that the workflow attempted a cancel. Sets {@code cancel_attempted_at}; state
   * transitions to CANCELLED only on broker confirmation via {@link #markCancelled}.
   */
  void markCancelAttempted(String intentKey);

  void markCancelled(String intentKey);

  /**
   * Records a cancel-on-filled or other broker-rejected cancel as a non-state- changing event.
   * State stays SUBMITTED; {@code last_error} captures the broker reason for reconciliation /
   * runbook follow-up.
   */
  void markCancelFailed(String intentKey, String brokerReason);

  /**
   * Records a broker-confirmed fill discovered during a cancel attempt (cancel-on-filled race) or
   * via reconciliation. Transitions state to FILLED and records fill detail. Conditional on current
   * state in (RECORDED, SUBMITTED) so a repeat call is a no-op; returns true iff the row was
   * updated.
   */
  boolean markFilled(
      String intentKey, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt);
}
