package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2a audit-log impl: writes the event as a structured slf4j log. The Postgres-backed
 * AuditActivities worker (plan Phase 2b/5) replaces this with a jOOQ append to {@code audit_log}.
 */
@Component
public class AuditActivitiesImpl implements AuditActivities {

  private static final Logger log = LoggerFactory.getLogger(AuditActivitiesImpl.class);

  @Override
  public void log(AuditEvent event) {
    log.info(
        "audit kind={} tenant={} strategy={} event_id={} actor={} workflow_id={} correlation_id={} subject={}",
        event.getKind(),
        event.getTenantId(),
        event.getStrategyId(),
        event.getEventId(),
        event.getActor(),
        event.getWorkflowId(),
        event.getCorrelationId(),
        event.getSubject());
  }
}
