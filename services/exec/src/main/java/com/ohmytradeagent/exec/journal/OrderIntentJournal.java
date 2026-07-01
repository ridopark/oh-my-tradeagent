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
   * Issue #295: resolve a row by its broker-facing {@code client_order_id} (the bounded, hashed
   * value derived from {@code intent_key}, persisted in the {@code client_order_id} column and
   * echoed by the broker). Powers the fill-dispatcher's WS submit/fill-race fallback: the broker
   * echoes the {@code client_order_id}, which is no longer equal to the {@code intent_key}, so the
   * fallback must resolve by this column rather than by {@code intent_key}. Returns empty for
   * unknown ids.
   */
  Optional<JournaledOrder> findByClientOrderId(String clientOrderId);

  /**
   * Resolve a row by its broker-issued order ID. Used by the fill listener to map an inbound trade
   * update back to the originating intent / workflow. Backed by the V1 partial index {@code
   * order_intent_journal_broker_order_id_idx (broker_order_id) WHERE broker_order_id IS NOT NULL};
   * returns empty for unknown / never-placed IDs.
   */
  Optional<JournaledOrder> findByBrokerOrderId(String brokerOrderId);

  /**
   * Page through journal rows in state {@code SUBMITTED} whose {@code submitted_at} is older than
   * the cutoff. Powers the fill-listener polling fallback: rows newer than the cutoff are still in
   * the WebSocket's hot window and excluded to avoid wasted broker calls. {@code limit} caps the
   * batch so a single cycle cannot exhaust broker rate budget.
   */
  List<JournaledOrder> findSubmittedOlderThan(OffsetDateTime cutoff, int limit);

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
   * Phase 2 (kill-switch realized re-source): all FILLED rows for one {@code side} ({@code BUY} =
   * entries, {@code SELL} = exits) on {@code tradingDay} (America/New_York) for ({@code tenantId},
   * {@code strategyId}), ordered {@code filled_at ASC, recorded_at ASC} (FIFO). Rows with a null
   * {@code filled_qty} / {@code avg_fill_price} are excluded (they carry no realizable fill). Backs
   * {@code DailyPnlExecActivity.computeRealizedPnl}: the broker-truth realized number the
   * daily-loss kill switches trip on, so a SELL that filled at the broker but whose {@code
   * PartialExitFilled} audit was lost is still counted. The trading-day boundary is {@code
   * (filled_at AT TIME ZONE 'America/New_York')::date = tradingDay} (mirrors the BFF {@code
   * RealizedPnlCalculator} SQL).
   */
  List<JournaledOrder> findFilledBySideOnDay(
      String tenantId, String strategyId, String side, java.time.LocalDate tradingDay);

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
   * Issue #295: records a broker rejection on the place path. Writes {@code last_error} (and bumps
   * {@code last_state_at} / {@code version}) without changing {@code state} — the row stays {@code
   * RECORDED} so a later retry can still transition it. Distinct from {@link #markCancelFailed},
   * which is the cancel-path equivalent. Makes a broker placement failure visible at the DB layer
   * instead of leaving the row {@code RECORDED} with {@code last_error=NULL}.
   */
  void markPlaceFailed(String intentKey, String brokerReason);

  /**
   * Phase 2: terminalizes a place-path rejection that can never resolve on retry (e.g. a 403
   * account-orders-blocked halt — Alpaca {@code 40310000} "new orders are rejected by user
   * request"). Guarded, boolean-returning: transitions {@code RECORDED → ERRORED} only and records
   * {@code reason} in {@code last_error}, bumping {@code version} / {@code last_state_at}. Distinct
   * from {@link #markPlaceFailed} (which deliberately KEEPS {@code RECORDED} so a retry can still
   * place) and from {@link #markBrokerRejected} (which terminalizes {@code SUBMITTED → ERRORED}
   * after the order already reached the broker). Idempotent under at-least-once retry: a repeat
   * call no longer matches {@code state='RECORDED'} and is a silent no-op. Returns true iff the row
   * was updated.
   */
  boolean markErrored(String intentKey, String reason);

  /**
   * Records a broker-confirmed fill discovered during a cancel attempt (cancel-on-filled race) or
   * via reconciliation. Transitions state to FILLED and records fill detail. Conditional on current
   * state in (RECORDED, SUBMITTED) so a repeat call is a no-op; returns true iff the row was
   * updated.
   */
  boolean markFilled(
      String intentKey, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt);

  /**
   * Terminalizes a row whose broker order expired unfilled. Guarded, boolean-returning (modeled on
   * {@link #markFilled}, not the unconditional {@link #markCancelled}): transitions {@code
   * SUBMITTED → EXPIRED} only, so a row that won the late-fill race (already FILLED) is left
   * untouched. Returns true iff the row was updated. Used by the {@code FillPoller} to stop the
   * permanent {@code JournalOrphan} an expired-unfilled exit otherwise leaves stuck SUBMITTED.
   */
  boolean markExpired(String intentKey);

  /**
   * Terminalizes a row the broker rejected after submission, recording {@code reason} in {@code
   * last_error}. Guarded, boolean-returning: transitions {@code SUBMITTED → ERRORED} only (first
   * writer of {@code ERRORED}; distinct from {@link #markPlaceFailed}, which keeps state). Returns
   * true iff the row was updated.
   */
  boolean markBrokerRejected(String intentKey, String reason);

  /**
   * Over-exit-422 benign terminalization (PLAN-over-exit-422): an STC/SELL the broker rejected with
   * a "position intent mismatch" 422 that {@code /v2/positions} CONFIRMED was already flat.
   * Guarded, boolean-returning: transitions {@code RECORDED → CANCELLED} only (the over-exit was
   * rejected before the row ever reached SUBMITTED), records {@code reason} in {@code last_error},
   * bumps {@code version}. Idempotent under at-least-once retry — a repeat call after the first
   * terminalization no longer matches {@code state='RECORDED'} and is a silent no-op. Reuses {@code
   * CANCELLED} (already in the state CHECK constraint) as the cross-Activity signal; disambiguation
   * from a real cancel lives in {@code last_error} + the {@code PartialExitAlreadyFlat} audit
   * event, not the state enum. Distinct from the unconditional {@link #markCancelled}. Returns true
   * iff the row was updated.
   */
  boolean markClosedAlreadyFlat(String intentKey, String reason);

  /**
   * Guarded variant of {@link #markCancelled}: transitions {@code SUBMITTED → CANCELLED} only and
   * returns true iff the row was updated. The poller routes the broker-CANCELLED case through this
   * (not the unconditional {@link #markCancelled}) so a row that won the late-fill race (already
   * FILLED) is not clobbered back to CANCELLED. The unconditional {@link #markCancelled} stays for
   * its existing callers.
   */
  boolean markCancelledIfSubmitted(String intentKey);
}
