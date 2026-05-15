package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.AuditEvent;
import java.sql.Timestamp;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Phase 5 audit-log impl: writes the event as a structured slf4j log AND appends a row to {@code
 * audit_log} (jOOQ INSERT). The DB write is tolerant of missing DSLContext (unit-test envs without
 * a database) so test code can mock this Activity without a Postgres container.
 *
 * <p>Subject map is serialized via Jackson to a JSONB column (text cast to jsonb at insert time).
 */
@Component
public class AuditActivitiesImpl implements AuditActivities {

  private static final Logger log = LoggerFactory.getLogger(AuditActivitiesImpl.class);

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  @Autowired
  public AuditActivitiesImpl(DSLContext dsl, ObjectMapper objectMapper) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

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

    if (dsl == null) {
      return;
    }
    try {
      String subjectJson = objectMapper.writeValueAsString(event.getSubject());
      Timestamp occurredAt = Timestamp.from(event.getOccurredAt().toInstant());
      UUID eventId = UUID.fromString(event.getEventId());
      dsl.execute(
          "INSERT INTO audit_log "
              + "(schema_version, tenant_id, strategy_id, event_id, occurred_at, kind, "
              + "actor, workflow_id, correlation_id, subject) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
          event.getSchemaVersion() == null ? 1 : event.getSchemaVersion().intValue(),
          event.getTenantId(),
          event.getStrategyId(),
          eventId,
          occurredAt,
          event.getKind(),
          event.getActor(),
          event.getWorkflowId(),
          event.getCorrelationId(),
          subjectJson);
    } catch (JsonProcessingException e) {
      log.error(
          "audit JSONB serialize failed kind={} event_id={}",
          event.getKind(),
          event.getEventId(),
          e);
    } catch (RuntimeException e) {
      // Persistence is best-effort from a kill-switch latency perspective; slf4j line above is the
      // forensic backstop. Re-throwing would surface Temporal Activity retries — undesired since
      // the slf4j log already captured the event.
      log.error("audit INSERT failed kind={} event_id={}", event.getKind(), event.getEventId(), e);
    }
  }
}
