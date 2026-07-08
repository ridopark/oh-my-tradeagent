package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * account-loss-cap-db (Phase 3) config-write carrier. A short-lived, single-step workflow that
 * dispatches {@link com.ohmytradeagent.orchestrator.activities.TenantConfigUpdateActivities#update}
 * and returns its {@link TenantConfigUpdateResult} — the same start-and-getResult pattern
 * api-gateway uses for {@code StrategyConfigUpdateWorkflow}.
 *
 * <p>It runs as a workflow (not a plain client→Activity call) because a Temporal <em>client</em>
 * (api-gateway) cannot dispatch an Activity directly. The Activity stays on the orchestrator-core
 * queue (the writer is an in-process {@code @Component}; this NEVER routes to a broker-* queue).
 * The workflow id embeds the {@code correlation_id} ({@link
 * com.ohmytradeagent.contract.identity.WorkflowIds#tenantConfigUpdate}) so a retried api-gateway
 * call dedups on {@code REJECT_DUPLICATE} rather than re-running the non-idempotent CAS.
 */
@WorkflowInterface
public interface TenantConfigUpdateWorkflow {

  @WorkflowMethod
  TenantConfigUpdateResult update(TenantConfigUpdateRequest request);
}
