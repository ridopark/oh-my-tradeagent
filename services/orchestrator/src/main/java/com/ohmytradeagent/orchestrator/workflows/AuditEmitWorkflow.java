package com.ohmytradeagent.orchestrator.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Generic audit-emit carrier (PLAN-2026-07-03, Phase 4). A minimal short-lived workflow that writes
 * ONE hash-chained {@code audit_log} row via the workflow-only {@code AuditActivities.log} path, so
 * the api-gateway — which has no direct audit writer — can record the tenant-delete lifecycle
 * events ({@code TenantDeleteRequested}, {@code TenantDeleteBlocked} for the P0–P3 pre-flight
 * refusals, {@code TenantDeleteCompleted}, {@code TenantDeleteStepFailed}) by starting this
 * workflow and awaiting its (void) result.
 *
 * <p>Net-new workflow type → no {@code Workflow.getVersion} change-point. Deterministic (the
 * event_id + occurred_at are filled from {@code Workflow.*}).
 */
@WorkflowInterface
public interface AuditEmitWorkflow {

  /**
   * Build an {@code AuditEvent} from {@code request} and write it via {@code AuditActivities.log}.
   */
  @WorkflowMethod
  void emit(AuditEmitRequest request);
}
