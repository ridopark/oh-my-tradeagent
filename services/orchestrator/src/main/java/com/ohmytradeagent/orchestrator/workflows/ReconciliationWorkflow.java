package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ReconciliationSummary;
import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase 5 reconciliation workflow. Started by a Temporal Schedule every 5 minutes (and on
 * orchestrator-svc startup). For each (tenant, strategy, broker_target) tuple:
 *
 * <ol>
 *   <li>Dumps non-terminal journal entries via the broker-specific {@code
 *       ReconciliationExecActivity.journalDumpOpen} (routed by broker_target → task queue).
 *   <li>Lists broker open orders via the same Activity interface's {@code brokerListOpenOrders}.
 *   <li>Walks the two lists in pure workflow code; emits {@code JournalOrphan} for journal entries
 *       older than 5 minutes with no broker match, and {@code BrokerOrphan} for broker orders whose
 *       {@code client_order_id} is not in the journal.
 *   <li>Returns a {@link ReconciliationSummary} with the four counts; emits {@code
 *       ReconciliationCompleted} audit.
 * </ol>
 *
 * <p>v0 audit-logs discrepancies but does NOT signal PositionWorkflows on {@code reconcile_orphan}
 * — that closes-the-loop step is a Phase 5b enhancement.
 */
@WorkflowInterface
public interface ReconciliationWorkflow {

  @WorkflowMethod
  ReconciliationSummary run(ReconciliationWorkflowInput input);
}
