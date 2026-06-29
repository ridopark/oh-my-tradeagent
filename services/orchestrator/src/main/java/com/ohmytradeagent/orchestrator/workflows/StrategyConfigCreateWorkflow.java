package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase I-1b create-tenant carrier. A short-lived, single-step workflow that dispatches the {@link
 * com.ohmytradeagent.orchestrator.activities.StrategyConfigCreateActivities#create} Activity and
 * returns its {@link StrategyConfigCreateResult} — the same start-and-getResult pattern api-gateway
 * uses for the {@code StrategyConfigUpdateWorkflow}.
 *
 * <p>It runs as a workflow (not a plain client→Activity call) because a Temporal <em>client</em>
 * (api-gateway) cannot dispatch an Activity directly. The Activity stays on the orchestrator-core
 * queue (the writer is an in-process {@code @Component}; this NEVER routes to a broker-* queue).
 * The workflow id embeds the {@code correlation_id} ({@link
 * com.ohmytradeagent.contract.identity.WorkflowIds#strategyConfigCreate}) so a retried api-gateway
 * call dedups on {@code REJECT_DUPLICATE} rather than re-running the INSERT.
 */
@WorkflowInterface
public interface StrategyConfigCreateWorkflow {

  @WorkflowMethod
  StrategyConfigCreateResult create(StrategyConfigCreateRequest request);
}
