package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.JournalEntry;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/**
 * Phase 5 cross-service contract owned by exec-svc (one impl per &lt;provider&gt;-&lt;env&gt;
 * pair). Reconciliation routes these to the relevant broker task queue (Phase 2c.2: {@code
 * broker-<broker_target>}, e.g. {@code broker-alpaca-paper}). Both methods return a flat snapshot;
 * ReconciliationWorkflow walks the two lists in pure workflow code and audits discrepancies
 * (JournalOrphan, BrokerOrphan, PositionOrphan).
 */
@ActivityInterface
public interface ReconciliationExecActivity {

  /**
   * Dump non-terminal journal entries (RECORDED, SUBMITTED) for the given (tenant, strategy).
   * Reconciliation flags any whose {@code recorded_at} is older than 5 minutes and have no matching
   * broker order as a JournalOrphan.
   */
  List<JournalEntry> journalDumpOpen(String tenantId, String strategyId);

  /**
   * List currently-open broker orders for the broker env this Activity is hosted in. The {@code
   * tenantId} / {@code strategyId} parameters resolve the per-tenant broker under the
   * shared-account path (multiple live tenants on one broker_target); the env-fallback source
   * ignores them and resolves the single account, so behavior is preserved. Reconciliation walks
   * the returned list to detect broker open orders with no matching journal entry and emits {@code
   * BrokerOrphan} audits.
   */
  List<BrokerOpenOrder> brokerListOpenOrders(String tenantId, String strategyId);

  /**
   * Issue #165 Phase 3: list currently-held broker positions for the broker env this Activity is
   * hosted in. The {@code tenantId} / {@code strategyId} parameters are forward-compat hooks
   * (Alpaca paper is single-account so they're unused today; future multi-account brokers will
   * filter on them). Reconciliation walks the returned list to detect broker-held positions with no
   * running {@code PositionWorkflow} and emits {@code PositionOrphan} audits.
   */
  List<BrokerPosition> brokerListOpenPositions(String tenantId, String strategyId);

  /**
   * Issue #165 Phase 3: return at most one journal entry for the given OCC option symbol whose
   * state is {@code FILLED}, sorted by {@code filled_at DESC} (most recent first). Used by
   * reconciliation to map a broker-held position back to the {@code (tenant, strategy,
   * option_symbol, entry_signal_id)} tuple that determines the expected {@code PositionWorkflow}
   * id. Returns an empty list if the journal has no FILLED row for that OCC under this (tenant,
   * strategy) — reconciliation treats that as a stronger orphan signal ({@code
   * journal_status=missing}).
   */
  List<JournalEntry> journalListFilledByOcc(String tenantId, String strategyId, String occ);

  /**
   * Issue #239 (orphan adoption): return the broker-held lot for the requested OCC under this
   * (tenant, strategy), or {@code null} when the broker does not hold it. Filters {@link
   * #brokerListOpenPositions} by {@code option_symbol}. This is broker truth — the phantom guard
   * for adoption refuses to adopt when this returns null, and the returned {@code qty} / {@code
   * avg_entry_price} are the authoritative values reconstruction uses (never the author-posted
   * price).
   */
  BrokerPosition brokerGetPositionByOcc(String tenantId, String strategyId, String occ);

  /**
   * Issue #239 (orphan adoption): terminalize a stale entry journal row to {@code FILLED} with
   * broker-confirmed fill detail. Thin wrapper over the existing conditional {@code
   * OrderIntentJournal.markFilled} (transitions only from RECORDED/SUBMITTED, so a repeat call is a
   * no-op). Returns {@code true} iff the row was actually flipped. Used by adoption to reconcile
   * the ledger to broker reality and clear the recon {@code PositionOrphan}/{@code JournalOrphan}
   * noise on the next tick.
   */
  boolean journalReconcileToFilled(
      String intentKey,
      long filledQty,
      java.math.BigDecimal avgFillPrice,
      java.time.OffsetDateTime filledAt);
}
