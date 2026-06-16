package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * UI-P3-b config-write carrier. A short-lived, single-step workflow that dispatches the {@link
 * com.ohmytradeagent.orchestrator.activities.StrategyConfigUpdateActivities#update} Activity and
 * returns its {@link StrategyConfigUpdateResult} — the same start-and-getResult pattern api-gateway
 * uses for {@code AdoptionWorkflow} / the dashboard BFF uses for {@code AccountSnapshotWorkflow}.
 *
 * <p>It runs as a workflow (not a plain client→Activity call) because a Temporal <em>client</em>
 * (api-gateway) cannot dispatch an Activity directly. The Activity stays on the orchestrator-core
 * queue (the writer is an in-process {@code @Component}; this NEVER routes to a broker-* queue).
 * The workflow id embeds the {@code correlation_id} ({@link
 * com.ohmytradeagent.contract.identity.WorkflowIds#strategyConfigUpdate}) so a retried api-gateway
 * call dedups on {@code REJECT_DUPLICATE} rather than re-running the non-idempotent CAS.
 */
@WorkflowInterface
public interface StrategyConfigUpdateWorkflow {

  @WorkflowMethod
  StrategyConfigUpdateResult update(StrategyConfigUpdateRequest request);
}
