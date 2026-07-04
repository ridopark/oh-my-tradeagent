package com.ohmytradeagent.orchestrator.workflows;

import java.util.Map;

/**
 * Input to {@link AuditEmitWorkflow} — the generic audit-emit surface the api-gateway uses to write
 * a hash-chained {@code audit_log} row (PLAN-2026-07-03, Phase 4). api-gateway has no direct audit
 * writer ({@code AuditActivities.log} is workflow-only), so it starts this short-lived workflow to
 * record {@code TenantDeleteRequested} / {@code TenantDeleteBlocked} (P0–P3) / {@code
 * TenantDeleteCompleted} / {@code TenantDeleteStepFailed}.
 *
 * <p>Plain POJO (public no-arg ctor + getters/setters) for the Temporal Jackson data converter. The
 * workflow fills {@code event_id} + {@code occurred_at} deterministically ({@link
 * io.temporal.workflow.Workflow#randomUUID} / {@link
 * io.temporal.workflow.Workflow#currentTimeMillis}); the caller supplies the rest.
 */
public class AuditEmitRequest {

  private long schemaVersion = 1L;
  private String kind;
  private String tenantId;
  private String strategyId;
  private Map<String, Object> subject;
  private String correlationId;
  private String actor;

  public long getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(long schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getStrategyId() {
    return strategyId;
  }

  public void setStrategyId(String strategyId) {
    this.strategyId = strategyId;
  }

  public Map<String, Object> getSubject() {
    return subject;
  }

  public void setSubject(Map<String, Object> subject) {
    this.subject = subject;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }

  public String getActor() {
    return actor;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }
}
