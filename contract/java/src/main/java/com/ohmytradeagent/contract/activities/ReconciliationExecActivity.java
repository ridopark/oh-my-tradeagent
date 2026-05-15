package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.JournalEntry;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/**
 * Phase 5 cross-service contract owned by exec-svc (one impl per broker target: paper/live).
 * Reconciliation routes these to the relevant broker task queue (e.g. {@code
 * broker-tradier-paper}). Both methods return a flat snapshot; ReconciliationWorkflow walks the two
 * lists in pure workflow code and audits discrepancies (JournalOrphan, BrokerOrphan).
 */
@ActivityInterface
public interface ReconciliationExecActivity {

  /**
   * Dump non-terminal journal entries (RECORDED, SUBMITTED) for the given (tenant, strategy).
   * Reconciliation flags any whose {@code recorded_at} is older than 5 minutes and have no matching
   * broker order as a JournalOrphan.
   */
  List<JournalEntry> journalDumpOpen(String tenantId, String strategyId);

  /** List currently-open broker orders for the broker env this Activity is hosted in. */
  List<BrokerOpenOrder> brokerListOpenOrders();
}
