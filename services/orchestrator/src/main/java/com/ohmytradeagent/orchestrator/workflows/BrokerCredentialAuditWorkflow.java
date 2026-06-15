package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * UI-P2-a credential-audit carrier. A short-lived, single-purpose workflow that hosts the P6-d
 * {@link com.ohmytradeagent.orchestrator.activities.BrokerCredentialAuditActivities#record} call
 * and completes — nothing more. It exists so a tenant broker-credential write/rotation lands a
 * metadata-only, hash-chained audit durably and at-least-once, even if the orchestrator is briefly
 * down when the api-gateway forwards the write.
 *
 * <p>Carrier rationale (see {@code _workspace/PLAN-mt-UI-P2-credential-entry.md}): the audit is
 * {@code (tenant, provider)}-scoped and strategy-agnostic, so it does NOT ride a per-{@code
 * (tenant,strategy)} {@code KillSwitchWorkflow} Update — there is no correct strategy to route it
 * to. Its workflow id is {@link
 * com.ohmytradeagent.contract.identity.WorkflowIds#brokerCredentialAudit} with the {@code
 * correlation_id} embedded so a retried api-gateway call dedups on {@code REJECT_DUPLICATE} rather
 * than double-auditing. The request carries ZERO key material (MF-7).
 */
@WorkflowInterface
public interface BrokerCredentialAuditWorkflow {

  @WorkflowMethod
  void record(BrokerCredentialAuditRequest request);
}
