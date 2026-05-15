package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.JournalEntry;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/**
 * Phase 5 cross-service contract owned by exec-svc (one impl per &lt;provider&gt;-&lt;env&gt;
 * pair). Reconciliation routes these to the relevant broker task queue (Phase 2c.2: {@code
 * broker-<broker_target>}, e.g. {@code broker-alpaca-paper}). Both methods return a flat snapshot;
 * ReconciliationWorkflow walks the two lists in pure workflow code and audits discrepancies
 * (JournalOrphan, BrokerOrphan).
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
